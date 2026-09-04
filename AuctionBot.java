package com.threerings.opengl.gui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Auction-house bot. Two independent halves sharing the service plumbing:
 * the BUY side (Ctrl+E live mode) sweeps for {@code auction_watch.txt} rules and
 * buys/bids under their caps; the SELL side (Ctrl+W) keeps the main's inventory
 * listed per {@code auction_sells.txt}, relisting an item only when ALL of its
 * live listings are gone (sold/expired).
 *
 * <p><b>How the game does an auction search</b> (decompiled from
 * {@code auction.client.SearchPanel}):
 *
 * <pre>
 *   Client client   = ctx.zn();                                  // 0-arg getter -> presents Client
 *   AuctionService s = client.bS(auction.client.c.class);        // generic service lookup
 *   s.a(Search, int page, ResultListener);                       // dispatch id 5
 * </pre>
 *
 * The service interface is {@code com.threerings.projectx.auction.client.c} and the
 * marshaller {@code auction.data.AuctionMarshaller}; both are client-side, so a search
 * can be issued from anywhere (no auction-house scene or open dialog required).
 * {@code ResultListener} = {@code presents.client.A$c}: {@code bs(Object)} on success
 * (an {@code auction.data.ListingsPage}), {@code aT(String)} on failure.
 *
 * <p>Results: {@code ListingsPage{int pageCount; List<ListingInfo> listings}} and
 * {@code ListingInfo{long auctionId; int buyPrice; int bidPrice; String name; int count;
 * boolean myAuction; BidStatus bidStatus; TimeLeft timeLeft; ...}} — all real-named
 * public fields, so no accessor guessing.
 *
 * <p>The watch table lives in {@code ~/.sk-utils/auction_watch.txt} and is re-read on
 * every scan, so rules change without a rebuild. Matches are appended to
 * {@code ~/.sk-utils/auction_finds.log} and echoed to debug.log.
 */
public final class AuctionBot {

    private static final String DIR = System.getProperty("user.home") + "/.sk-utils";
    private static final String WATCH_FILE = DIR + "/auction_watch.txt";
    private static final String FINDS_FILE = DIR + "/auction_finds.log";
    private static final String CATALOG_FILE = DIR + "/item_configs.txt";

    private static final String SVC_CLASS = "com.threerings.projectx.auction.client.c";
    private static final String CLIENT_CLASS = "com.threerings.presents.client.Client";
    private static final String LISTENER_CLASS = "com.threerings.presents.client.A$c";
    private static final String SEARCH_CLASS = "com.threerings.projectx.auction.data.Search";
    private static final String SORT_CLASS = "com.threerings.projectx.auction.data.Search$Sort";

    private static final int MAX_PAGES = 40;      // safety cap on a full sweep
    private static final long PAGE_TIMEOUT_MS = 15000L; // no reply this long => give up
    // Pacing between buy/bid calls (user-set): 1s plus jitter, so the traffic never
    // looks metronomic and a burst of matches can't hammer the service.
    private static final long ACTION_INTERVAL_MS = 1000L;
    private static final long ACTION_JITTER_MS = 700L;
    // How long after finishing a pass before sweeping again. Re-sweeping is what makes
    // bidding responsive: it refreshes bidStatus, so an OUTBID listing gets a counter-bid.
    private static final long RESWEEP_MS = 8000L;

    // ── seller constants ─────────────────────────────────────────────────────
    private static final String SELLS_FILE = DIR + "/auction_sells.txt";
    private static final String DURATION_CLASS = "com.threerings.projectx.auction.data.Duration";
    /** Pause between seller passes. Listings live for hours, so a replenish landing
     *  a minute late costs nothing — no need for the buy sweep's 8s cadence. */
    private static final long SELL_INTERVAL_MS = 60000L;
    /** A rule that failed (server refusal, ambiguous match, no full stack) sits out
     *  this long before being retried, so a permanent problem logs every 10 minutes
     *  instead of every pass. */
    private static final long SELL_BACKOFF_MS = 600000L;
    /** e.please_wait is a SERVER-WIDE listing throttle (too many listings by players
     *  online in a short window — user-identified), not a problem with the rule or
     *  the item: retry on this short cadence instead of the rule backoff. */
    private static final long SELL_THROTTLE_RETRY_MS = 10000L;
    /** Throttle retries per Create before treating it as a permanent failure (rule
     *  backoff + purge) — bounds a refusal that merely CONTAINS please_wait, which
     *  would otherwise block every other rule's creates forever. ~15 min at 10s. */
    private static final int SELL_THROTTLE_MAX_RETRIES = 90;

    /** Set by the Ctrl+E hotkey (UDP "AUCTIONSCAN"); consumed by {@link #tick}. */
    public static volatile boolean scanPending = false;
    /** Set by the Ctrl+U hotkey (UDP "ITEMDUMP"); writes the item-name lookup table. */
    public static volatile boolean catalogPending = false;
    /** LIVE mode: actually buy and bid. Toggled by Ctrl+E; off = report only. */
    public static volatile boolean liveMode = false;
    private static volatile boolean scanRunning = false;
    private static volatile long pageSentAt = 0L;
    private static volatile long nextSweepAt = 0L;
    private static volatile long nextActionAt = 0L;

    // Per-scan state.
    private static ArrayList<Rule> rules = new ArrayList<Rule>();
    private static ArrayList<String> hits = new ArrayList<String>();
    private static ArrayList<Action> actions = new ArrayList<Action>();
    private static int pagesSeen = 0, listingsSeen = 0;
    private static Object svc = null;
    private static Object search = null;
    private static Object botCtx = null; // last context seen by tick()
    // Item configs matching the current rules, cached against the watch file's
    // fingerprint so an unchanged file skips the expensive catalogue scan.
    private static java.util.HashSet<String> cachedConfigs = null;
    private static String cachedStamp = null;
    // Auctions already acted on, so a re-sweep doesn't buy the same thing twice or
    // re-bid while our own bid is still the high one.
    private static final java.util.HashSet<Long> boughtIds = new java.util.HashSet<Long>();
    private static final java.util.HashMap<Long, Integer> myBids = new java.util.HashMap<Long, Integer>();
    /**
     * Crowns committed to buys and bids since live mode was switched on. Read by
     * MissionStats: the bot spends from the MAIN's wallet and keeps running through
     * mission runs, so without subtracting this a run's "crowns earned" silently mixes
     * loot income with auction spending — one live run measured -16993 when the party
     * had actually looted +9132. Monotonic within a live session; reset when live mode
     * is toggled on.
     */
    static volatile int spentThisSession = 0;

    // ── seller state ─────────────────────────────────────────────────────────
    /** Set by the Ctrl+W hotkey (UDP "AUCTIONSELL"); consumed by {@link #tick}. */
    public static volatile boolean sellPending = false;
    /** SELLER mode: keep inventory listed per auction_sells.txt. Toggled by Ctrl+W. */
    public static volatile boolean sellMode = false;
    private static volatile boolean sellRunning = false;
    private static volatile long sellPageSentAt = 0L;
    private static volatile long nextSellAt = 0L;
    private static volatile long nextSellActionAt = 0L;
    private static Object sellSvc = null;
    private static Object sellSearch = null;
    private static ArrayList<SellRule> sellRules = new ArrayList<SellRule>();
    private static int sellRuleIdx = 0;
    private static int sellMyCount = 0; // my live listings of the rule being counted
    private static final ArrayList<Create> sellCreates = new ArrayList<Create>();
    private static final java.util.HashMap<String, Long> sellBackoffUntil = new java.util.HashMap<String, Long>();
    // Config path -> display name, resolved from the player's own items. The item
    // catalogue is static, so entries never invalidate.
    private static final java.util.HashMap<String, String> displayNameCache = new java.util.HashMap<String, String>();
    private static java.lang.reflect.Method itemToOriginal = null, origDisplayName = null;
    private static boolean sellNoRulesLogged = false;
    private static ArrayList<String> sellNotes = new ArrayList<String>(); // per-pass summary; quiet pass logs nothing

    /** One pending buy or bid, captured from a sweep. */
    private static final class Action {
        final long auctionId;
        final String name;
        final boolean buyout;  // true = buy out, false = bid
        final int amount;      // buyout price, or the next valid bid
        final int ruleMax;

        Action(long auctionId, String name, boolean buyout, int amount, int ruleMax) {
            this.auctionId = auctionId;
            this.name = name;
            this.buyout = buyout;
            this.amount = amount;
            this.ruleMax = ruleMax;
        }
    }

    private AuctionBot() {
    }

    /** One watch-table row: a name substring, a crowns ceiling, and optional exclusions. */
    private static final class Rule {
        final String needle;             // lower-case substring to look for in the item name
        final int maxPrice;              // buyout ceiling, crowns
        final String[] excludes;         // lower-case substrings that disqualify a match

        Rule(String needle, int maxPrice, String[] excludes) {
            this.needle = needle;
            this.maxPrice = maxPrice;
            this.excludes = excludes;
        }

        boolean matches(String lowerName) {
            if (!lowerName.contains(needle))
                return false;
            for (int i = 0; i < excludes.length; i++)
                if (lowerName.contains(excludes[i]))
                    return false;
            return true;
        }
    }

    /**
     * One sells-table row. Prices are PER LISTING (the whole stack), exactly what the
     * game's own sell dialog takes; buyout 0 = a bid-only listing (the seller offered
     * no buyout — the same kind the buy side learned to see with the BID_PRICE sort).
     */
    private static final class SellRule {
        final String needle;   // lower-case substring to find among the OWN inventory's item names
        final int startBid;    // whole-listing starting bid, crowns
        final int buyout;      // whole-listing buyout, crowns; 0 = no buyout
        final int listings;    // listings to (re)create when none of this item are live
        final int stack;       // units per listing
        final String durName;  // Duration enum constant name (real-named, stable)
        // Resolved per pass against the inventory:
        String config = null;  // the ONE matching item config, or null = skip this pass
        String display = null;
        ArrayList<long[]> stock = null; // matching inventory entries as {oid, count}

        SellRule(String needle, int startBid, int buyout, int listings, int stack, String durName) {
            this.needle = needle;
            this.startBid = startBid;
            this.buyout = buyout;
            this.listings = listings;
            this.stack = stack;
            this.durName = durName;
        }
    }

    /** One pending create-listing call, captured by a seller pass. */
    private static final class Create {
        final long itemOid;
        final int count;
        final int startBid;
        final int buyout;
        final String durName;
        final String label;      // display name, for logs
        final String ruleNeedle; // owning rule, for failure backoff
        int throttleRetries = 0; // please_wait requeues so far (capped)

        Create(long itemOid, int count, int startBid, int buyout, String durName,
                String label, String ruleNeedle) {
            this.itemOid = itemOid;
            this.count = count;
            this.startBid = startBid;
            this.buyout = buyout;
            this.durName = durName;
            this.label = label;
            this.ruleNeedle = ruleNeedle;
        }
    }

    // ── entry point ──────────────────────────────────────────────────────────

    /**
     * Called every tick from the patched poll. Drives the whole cycle: sweep →
     * act on the matches one at a time (paced) → re-sweep, for as long as LIVE
     * mode is on. Ctrl+E toggles live mode; a single sweep also runs when it is off,
     * which reports without spending anything.
     */
    public static void tick(Object ctx) {
        long now = System.currentTimeMillis();
        if (ctx != null)
            botCtx = ctx; // kept so queued actions can re-resolve the service after a sweep ends
        if (catalogPending) { // item-name lookup dump — no network, just the config manager
            catalogPending = false;
            dumpItemCatalog(ctx);
            return;
        }
        if (scanPending) {
            scanPending = false;
            liveMode = !liveMode;
            log("[auction] LIVE mode " + (liveMode ? "ON — will BUY and BID" : "OFF"));
            if (liveMode) {
                boughtIds.clear();
                myBids.clear();
                spentThisSession = 0;
            } else {
                actions.clear();
            }
            nextSweepAt = 0L;
        }
        if (sellPending) {
            sellPending = false;
            sellMode = !sellMode;
            log("[auction] SELLER " + (sellMode ? "ON — will LIST per " + SELLS_FILE : "OFF"));
            if (sellMode) {
                nextSellAt = 0L;
                sellBackoffUntil.clear();
                sellNoRulesLogged = false;
            } else {
                sellCreates.clear();
                sellFinish();
            }
        }
        if (scanRunning) {
            if (pageSentAt != 0L && now - pageSentAt >= PAGE_TIMEOUT_MS) {
                log("[auction] no reply for " + (PAGE_TIMEOUT_MS / 1000) + "s — aborting the sweep");
                finish();
            }
            return;
        }
        // ── seller half: independent of live (buy) mode ──────────────────────
        if (sellRunning) {
            if (sellPageSentAt != 0L && now - sellPageSentAt >= PAGE_TIMEOUT_MS) {
                log("[auction] seller: no reply for " + (PAGE_TIMEOUT_MS / 1000) + "s — abandoning the pass");
                sellFinish();
            }
            return;
        }
        // Work pending listings one at a time, paced like buys — never a burst.
        // While the queue is merely WAITING (pacing gap or a please_wait throttle
        // retry), FALL THROUGH so the buy half keeps sweeping — a throttle wait can
        // now stretch to many seconds and must not starve Ctrl+E. A fresh sell pass
        // still cannot start until the queue drains (else it would recount listings
        // mid-batch, read 0 live, and plan the same creates twice).
        if (!sellCreates.isEmpty()) {
            if (now >= nextSellActionAt) {
                doCreate(sellCreates.remove(0));
                nextSellActionAt = now + ACTION_INTERVAL_MS + (long) (Math.random() * ACTION_JITTER_MS);
                return;
            }
        } else if (sellMode && now >= nextSellAt) {
            nextSellAt = now + SELL_INTERVAL_MS;
            startSellPass(ctx);
            return;
        }
        if (!liveMode)
            return;
        // Work the queue first, one action per interval.
        if (!actions.isEmpty()) {
            if (now >= nextActionAt) {
                doAction(actions.remove(0));
                nextActionAt = now + ACTION_INTERVAL_MS + (long) (Math.random() * ACTION_JITTER_MS);
                if (actions.isEmpty())
                    nextSweepAt = now + RESWEEP_MS;
            }
            return;
        }
        if (now >= nextSweepAt)
            startScan(ctx);
    }

    private static void startScan(Object ctx) {
        rules = loadWatchTable();
        if (rules.isEmpty()) {
            log("[auction] no usable rules in " + WATCH_FILE + " — nothing to scan for");
            return;
        }
        try {
            svc = resolveService(ctx);
            if (svc == null) {
                log("[auction] auction service unavailable (not logged in?)");
                return;
            }
            search = buildSearch(ctx);
            if (search == null) {
                log("[auction] no item config matches the watch rules — nothing to ask the server for");
                return;
            }
            hits = new ArrayList<String>();
            actions = new ArrayList<Action>();
            pagesSeen = 0;
            listingsSeen = 0;
            scanRunning = true;
            log("[auction] sweep started — " + rules.size() + " rule(s), live=" + liveMode);
            requestPage(0);
        } catch (Exception e) {
            log("[auction] could not start: " + describe(e));
            scanRunning = false;
        }
    }

    // ── the game calls ───────────────────────────────────────────────────────

    /** ctx -> presents Client -> the auction service, all by structure (no obfuscated names hardcoded). */
    private static Object resolveService(Object ctx) throws Exception {
        if (ctx == null)
            return null;
        // The shared Mappings pair — one definition of ctx -> Client -> service; the
        // skip-throwing-candidates hardening (a getter can THROW in bad client states
        // like relog/character select, and the seller polls in every state) lives in
        // Mappings.getService now, so every other subsystem gets it too. A throw here
        // (bad state) is "unavailable, retry later", not an abort.
        try {
            Object client = Mappings.getClientManager(ctx);
            if (client == null)
                return null;
            return Mappings.getService(client, Class.forName(SVC_CLASS));
        } catch (Exception e) {
            return null;
        }
    }

    /** Throwable -> log string with reflective wrappers unwrapped (an
     *  InvocationTargetException's toString hides the actual cause). */
    private static String describe(Throwable t) {
        Throwable c = Reflect.rootCause(t);
        return (c == t) ? String.valueOf(t) : t + " <- " + c;
    }

    /** True for the server's transient "too many requests" refusal (e.please_wait —
     *  a server-wide window throttle, user-identified): says nothing about the
     *  request, the rule, or the service method being wrong. */
    private static boolean isTransientThrottle(String why) {
        return why != null && why.contains("please_wait");
    }

    /**
     * Builds the Search the way the game's own SearchPanel.SearchBuilder does:
     * {@code configs} is a set of ITEM CONFIG NAMES resolved CLIENT-SIDE, and the
     * server merely returns listings for them.
     *
     * <p>An empty {@code configs} is NOT "browse everything" — {@code Search.isEmpty()}
     * is defined as "configs null or empty", and sending one earns an
     * {@code m.internal_error} from the server. That is what the first version did.
     *
     * <p>So this walks every {@code ItemConfig} in the config manager, resolves each
     * one's display name (the same string the search box matches against), and keeps
     * the configs whose name satisfies one of the watch rules. Returns null when
     * nothing matches, so the caller can skip the request entirely.
     */
    private static Object buildSearch(Object ctx) throws Exception {
        // Resolving 9000+ item configs to display names takes ~8s and runs on the game's
        // tick thread, so it must NOT happen every sweep. The item catalogue is static;
        // only the watch file changes. Cache the resolved set against the file's
        // fingerprint (size + mtime), which keeps edits hot — save the file and the very
        // next sweep rescans — while an unchanged file costs nothing.
        File wf = new File(WATCH_FILE);
        String stamp = wf.lastModified() + ":" + wf.length();
        java.util.HashSet<String> configs;
        boolean rescanned = false;
        if (cachedConfigs != null && stamp.equals(cachedStamp)) {
            configs = cachedConfigs;
        } else {
            configs = matchingConfigs(ctx);
            cachedConfigs = configs;
            cachedStamp = stamp;
            rescanned = true;
        }
        if (configs.isEmpty())
            return null;
        Object s = newSearch(configs);
        log("[auction] " + configs.size() + " item config(s) match the watch rules"
                + (rescanned ? " (watch file changed — catalogue rescanned)" : " (cached)"));
        return s;
    }

    /**
     * A Search over exactly these config names, sorted by BID_PRICE — the one sort
     * that hides nothing (a BUY_PRICE sort makes the server omit bid-only listings
     * entirely; that cost the buy side a whole bug hunt).
     */
    private static Object newSearch(java.util.HashSet<String> configs) throws Exception {
        Class<?> searchCls = Class.forName(SEARCH_CLASS);
        Object s = searchCls.getConstructor().newInstance();
        searchCls.getField("configs").set(s, configs);
        searchCls.getField("all").setBoolean(s, false);
        searchCls.getField("featured").setBoolean(s, false);
        searchCls.getField("ascending").setBoolean(s, true);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object sort = Enum.valueOf((Class) Class.forName(SORT_CLASS), "BID_PRICE");
        searchCls.getField("sort").set(s, sort);
        return s;
    }

    /**
     * Every item config whose display name satisfies a watch rule. Mirrors the
     * client-side half of the game's search: config manager → the ItemConfig group →
     * each config's Original → its localised display name.
     */
    private static java.util.ArrayList<String[]> scanItemConfigs(Object ctx,
            java.util.ArrayList<String[]> catalog) {
        try {
            Object cfgmgr = Mappings.getConfigManager(ctx);
            Class<?> itemCfgCls = Class.forName("com.threerings.projectx.item.config.ItemConfig");
            // ConfigManager: the (Class) -> ConfigGroup lookup.
            Method groupOf = null;
            for (Method m : cfgmgr.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Class.class && m.getReturnType().getName().endsWith("ConfigGroup")) {
                    groupOf = m;
                    break;
                }
            }
            if (groupOf == null) {
                log("[auction] no ConfigManager group lookup found");
                return catalog;
            }
            Object cfgGroup = groupOf.invoke(cfgmgr, itemCfgCls);
            // ConfigGroup's config iterator. It has SEVERAL 0-arg methods returning an
            // Iterable — kH() (the configs), kI() (them as ManagedConfig) and kG(),
            // which returns List<Class> and is NOT what we want. getMethods() order is
            // unspecified, so taking "the first Iterable" picked kG() and scanned one
            // element. Pick the one that actually yields the most items, which is the
            // config list by construction and survives any renaming.
            Object configs = null;
            int best = 0;
            for (Method m : cfgGroup.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0 || !Iterable.class.isAssignableFrom(m.getReturnType()))
                    continue;
                try {
                    Object it = m.invoke(cfgGroup);
                    if (it == null)
                        continue;
                    int n = 0;
                    for (java.util.Iterator<?> i = ((Iterable<?>) it).iterator(); i.hasNext(); i.next())
                        n++;
                    if (n > best) {
                        best = n;
                        configs = it;
                    }
                } catch (Exception ignored) {
                }
            }
            if (configs == null || best == 0) {
                log("[auction] could not iterate the item configs");
                return catalog;
            }
            Method toOriginal = null, displayName = null;
            int scanned = 0;
            for (Object cfg : (Iterable<?>) configs) {
                if (cfg == null)
                    continue;
                scanned++;
                String cfgName = String.valueOf(cfg.getClass().getMethod("getName").invoke(cfg));
                // ItemConfig.x(ConfigManager) -> ItemConfig$Original (resolved once).
                if (toOriginal == null) {
                    for (Method m : cfg.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && p[0].getName().endsWith("ConfigManager")
                                && m.getReturnType().getName().endsWith("$Original")) {
                            toOriginal = m;
                            break;
                        }
                    }
                }
                Object orig = (toOriginal == null) ? null : toOriginal.invoke(cfg, cfgmgr);
                String display = null;
                if (orig != null) {
                    // Original.e(ctx, configName) -> the localised display name the
                    // search box matches against.
                    if (displayName == null) {
                        for (Method m : orig.getClass().getMethods()) {
                            Class<?>[] p = m.getParameterTypes();
                            if (p.length == 2 && p[1] == String.class && m.getReturnType() == String.class
                                    && p[0].isInstance(ctx)) {
                                displayName = m;
                                break;
                            }
                        }
                    }
                    if (displayName != null) {
                        try {
                            Object d = displayName.invoke(orig, ctx, cfgName);
                            if (d != null)
                                display = d.toString();
                        } catch (Exception ignored) {
                        }
                    }
                }
                catalog.add(new String[] { display == null ? "" : display, cfgName });
            }
            log("[auction] scanned " + scanned + " item configs"
                    + (displayName == null ? " (NO display-name method — CONFIG PATHS ONLY)" : ""));
        } catch (Exception e) {
            log("[auction] config scan error: " + e);
        }
        return catalog;
    }

    /**
     * Every item config as {displayName, configName}. Shared by the rule matcher and the
     * catalog dump, so what a rule sees is exactly what the dump shows.
     */
    private static java.util.ArrayList<String[]> allItemConfigs(Object ctx) {
        java.util.ArrayList<String[]> catalog = new java.util.ArrayList<String[]>();
        scanItemConfigs(ctx, catalog);
        return catalog;
    }

    /** Config names whose display name (or config path) satisfies a watch rule. */
    private static java.util.HashSet<String> matchingConfigs(Object ctx) {
        java.util.HashSet<String> out = new java.util.HashSet<String>();
        for (String[] row : allItemConfigs(ctx)) {
            // Match on the display name when we have one, and on the config path as a
            // fallback — a superset, which only risks extra listings.
            String hay = (row[0] + " " + row[1]).toLowerCase(Locale.ROOT);
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).matches(hay)) {
                    out.add(row[1]);
                    break;
                }
            }
        }
        log("[auction] " + out.size() + " config(s) match the watch rules");
        return out;
    }

    /**
     * Writes every item in the game to {@code ~/.sk-utils/item_configs.txt} as
     * {@code <display name><TAB><config name>}, sorted by display name — a lookup table
     * for writing watch rules, since an item's config path doesn't always read like it's in-game
     * name.
     */
    public static void dumpItemCatalog(Object ctx) {
        java.util.ArrayList<String[]> catalog = allItemConfigs(ctx);
        if (catalog.isEmpty()) {
            log("[auction] item catalog dump found nothing (not logged in?)");
            return;
        }
        java.util.Collections.sort(catalog, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                int c = a[0].compareToIgnoreCase(b[0]);
                return (c != 0) ? c : a[1].compareToIgnoreCase(b[1]);
            }
        });
        try {
            new File(DIR).mkdirs();
            FileWriter fw = new FileWriter(CATALOG_FILE);
            fw.write("# Every item config in the game, as <display name><TAB><config name>.\n"
                    + "# Search this for an item, then use either name in auction_watch.txt —\n"
                    + "# rules match the display name OR the config path.\n\n");
            for (String[] row : catalog)
                fw.write(row[0] + "\t" + row[1] + "\n");
            fw.close();
            log("[auction] wrote " + catalog.size() + " item configs to " + CATALOG_FILE);
        } catch (Exception e) {
            log("[auction] catalog write error: " + e);
        }
    }

    /** Issues one page request; the reply drives the next page (or the report). */
    private static void requestPage(final int page) {
        try {
            Class<?> listenerCls = Class.forName(LISTENER_CLASS);
            Object listener = Proxy.newProxyInstance(AuctionBot.class.getClassLoader(),
                    new Class<?>[] { listenerCls }, new InvocationHandler() {
                        public Object invoke(Object proxy, Method m, Object[] args) {
                            try {
                                if (m.getParameterTypes().length == 1
                                        && m.getParameterTypes()[0] == String.class) {
                                    log("[auction] search failed: " + (args == null ? "?" : args[0]));
                                    finish();
                                } else {
                                    onPage(page, (args == null) ? null : args[0]);
                                }
                            } catch (Exception e) {
                                log("[auction] result error: " + e);
                                finish();
                            }
                            return null;
                        }
                    });
            Method call = findSearchMethod(svc);
            if (call == null) {
                log("[auction] search method not found on the service");
                finish();
                return;
            }
            pageSentAt = System.currentTimeMillis();
            call.invoke(svc, search, Integer.valueOf(page), listener);
        } catch (Exception e) {
            log("[auction] request error: " + e);
            finish();
        }
    }

    /** Handles one ListingsPage: filter it, then ask for the next page or report. */
    private static void onPage(int page, Object result) throws Exception {
        pageSentAt = 0L;
        if (result == null) {
            finish();
            return;
        }
        Class<?> pageCls = result.getClass();
        int pageCount = pageCls.getField("pageCount").getInt(result);
        Object listings = pageCls.getField("listings").get(result);
        pagesSeen++;
        if (listings instanceof Iterable) {
            for (Object li : (Iterable<?>) listings) {
                listingsSeen++;
                Class<?> c = li.getClass();
                String name = String.valueOf(c.getField("name").get(li));
                int buy = c.getField("buyPrice").getInt(li);
                int bid = c.getField("bidPrice").getInt(li);
                boolean mine = c.getField("myAuction").getBoolean(li);
                long id = c.getField("auctionId").getLong(li);
                int count = c.getField("count").getInt(li);
                if (mine)
                    continue; // never act on our own listings
                String lower = name.toLowerCase(Locale.ROOT);
                Rule hit = null;
                for (int i = 0; i < rules.size(); i++) {
                    if (rules.get(i).matches(lower)) {
                        hit = rules.get(i);
                        break;
                    }
                }
                if (hit == null)
                    continue;
                String status = String.valueOf(c.getField("bidStatus").get(li));
                hits.add("MATCH " + name + " x" + count + "  buyout=" + (buy > 0 ? (buy + "cr") : "none")
                        + " bid=" + bid + "cr status=" + status
                        + "  (rule '" + hit.needle + "' <= " + hit.maxPrice + ")  id=" + id);
                if (!liveMode)
                    continue;
                // BUY OUT when there is a buyout price within the cap — instant and
                // final, so it always beats bidding and waiting. Guarded to once per
                // auction; BIDS deliberately are NOT, because answering someone else's
                // outbid means acting on the same auction again.
                if (buy > 0 && buy <= hit.maxPrice) {
                    if (!boughtIds.contains(Long.valueOf(id)))
                        actions.add(new Action(id, name, true, buy, hit.maxPrice));
                    continue;
                }
                // Otherwise BID, but only while we are not already the high bidder.
                // The bid amount is the listing's own next valid bid (Ba()), the same
                // value the game's client passes.
                if ("HIGH_BIDDER".equals(status)) {
                    log("[auction] no bid on " + name + " id=" + id + ": already HIGH_BIDDER");
                    continue;
                }
                int next = nextBid(li);
                if (next <= 0) {
                    // AX()/next-valid-bid came back non-positive (e.g. a fresh listing
                    // whose starting price we couldn't read there) — fall back to the
                    // listed bidPrice, which for a no-bid auction IS the opening bid.
                    log("[auction] " + name + " id=" + id + ": nextBid=" + next
                            + " unreadable, falling back to bidPrice=" + bid);
                    next = bid;
                }
                if (next <= 0) {
                    log("[auction] no bid on " + name + " id=" + id
                            + ": no positive bid amount (bidPrice=" + bid + ", buyPrice=" + buy + ")");
                    continue;
                }
                if (next > hit.maxPrice) {
                    log("[auction] no bid on " + name + " id=" + id + ": next bid " + next
                            + "cr exceeds cap " + hit.maxPrice + "cr");
                    continue;
                }
                Integer mine2 = myBids.get(Long.valueOf(id));
                if (mine2 != null && mine2.intValue() >= next) {
                    log("[auction] no bid on " + name + " id=" + id + ": our standing bid "
                            + mine2 + "cr already covers " + next + "cr");
                    continue;
                }
                log("[auction] queuing BID " + next + "cr on " + name + " id=" + id
                        + " (no-buyout=" + (buy <= 0) + ", cap " + hit.maxPrice + ")");
                actions.add(new Action(id, name, false, next, hit.maxPrice));
            }
        }
        int next = page + 1;
        if (next < pageCount && next < MAX_PAGES) {
            requestPage(next);
        } else {
            if (pageCount > MAX_PAGES)
                log("[auction] stopped at the " + MAX_PAGES + "-page cap (" + pageCount + " pages exist)");
            report();
            if (!actions.isEmpty())
                log("[auction] queued " + actions.size() + " action(s)");
            else if (liveMode)
                nextSweepAt = System.currentTimeMillis() + RESWEEP_MS;
            finish();
        }
    }

    // ── acting ───────────────────────────────────────────────────────────────

    /**
     * The listing's own next valid bid — {@code ListingInfo.Ba()}, the value the
     * game's own client passes when it bids. Found structurally as the only 0-arg
     * int method that is not {@code hashCode}, so obfuscation churn can't break it.
     */
    private static int nextBid(Object listing) {
        try {
            for (Method m : listing.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == int.class
                        && !"hashCode".equals(m.getName())) {
                    Object v = m.invoke(listing);
                    if (v instanceof Integer)
                        return ((Integer) v).intValue();
                }
            }
        } catch (Exception e) {
        }
        return -1;
    }

    /** Issues one buy or bid. Never called with an amount above the rule's cap. */
    private static void doAction(Action a) {
        if (a.amount > a.ruleMax) { // belt and braces — should be impossible
            log("[auction] REFUSING " + a.name + ": " + a.amount + "cr exceeds the cap " + a.ruleMax);
            return;
        }
        try {
            // The queue is worked AFTER the sweep that built it, and finish() drops the
            // service reference — so re-resolve here rather than hold a stale one across
            // a reconnect.
            if (svc == null)
                svc = resolveService(botCtx);
            if (svc == null) {
                log("[auction] service unavailable — dropping " + (a.buyout ? "buy" : "bid") + " on " + a.name);
                return;
            }
            // ONE service call for both. The game has no dedicated buyout method, its own Buy Now
            // button just bids the buyout price.
            Method m = null;
            for (Method cand : svc.getClass().getMethods()) {
                Class<?>[] p = cand.getParameterTypes();
                if (p.length == 3 && p[0] == long.class && p[1] == int.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                log("[auction] no bid method on the service");
                return;
            }
            m.invoke(svc, Long.valueOf(a.auctionId), Integer.valueOf(a.amount), listenerFor(a));
            if (a.buyout) {
                boughtIds.add(Long.valueOf(a.auctionId)); // buy once; bids may repeat when outbid
                log("[auction] BUY " + a.name + " for " + a.amount + "cr (id=" + a.auctionId
                        + ", bid at the buyout price)");
            } else {
                myBids.put(Long.valueOf(a.auctionId), Integer.valueOf(a.amount));
                log("[auction] BID " + a.amount + "cr on " + a.name + " (id=" + a.auctionId
                        + ", cap " + a.ruleMax + ")");
            }
            spentThisSession += a.amount;
        } catch (Exception e) {
            log("[auction] action error on " + a.name + ": " + e);
        }
    }

    /** The service's (Search, int page, listener) method — shared by both halves. */
    private static Method findSearchMethod(Object service) {
        for (Method m : service.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0].getName().equals(SEARCH_CLASS) && p[1] == int.class)
                return m;
        }
        return null;
    }

    /** Result listener that logs the outcome and rolls back the bookkeeping on failure. */
    private static Object listenerFor(final Action a) throws Exception {
        Class<?> listenerCls = Class.forName(LISTENER_CLASS);
        return Proxy.newProxyInstance(AuctionBot.class.getClassLoader(),
                new Class<?>[] { listenerCls }, new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        boolean failed = m.getParameterTypes().length == 1
                                && m.getParameterTypes()[0] == String.class;
                        if (failed) {
                            String why = (args == null) ? "?" : String.valueOf(args[0]);
                            log("[auction] " + (a.buyout ? "BUY" : "BID") + " FAILED on " + a.name + ": " + why);
                            spentThisSession -= a.amount; // it never happened
                            if (a.buyout)
                                boughtIds.remove(Long.valueOf(a.auctionId));
                            else
                                myBids.remove(Long.valueOf(a.auctionId));
                        } else {
                            log("[auction] " + (a.buyout ? "BOUGHT " : "BID OK ") + a.name
                                    + " for " + a.amount + "cr — session spend " + spentThisSession + "cr");
                        }
                        return null;
                    }
                });
    }

    // ── reporting ────────────────────────────────────────────────────────────

    private static void report() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== auction sweep ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date())).append(" — ").append(pagesSeen).append(" page(s), ")
                .append(listingsSeen).append(" listing(s), ").append(hits.size()).append(" match(es)\n");
        for (int i = 0; i < hits.size(); i++)
            sb.append("  ").append(hits.get(i)).append('\n');
        String text = sb.toString();
        try {
            new File(DIR).mkdirs();
            FileWriter fw = new FileWriter(FINDS_FILE, true);
            fw.write(text);
            fw.close();
        } catch (Exception e) {
        }
        log(text.trim());
    }

    private static void finish() {
        scanRunning = false;
        pageSentAt = 0L;
        svc = null;
        search = null;
    }

    // ── seller (Ctrl+W): keep inventory listed per auction_sells.txt ─────────

    /**
     * One seller pass: resolve each rule against the INVENTORY (strict: it must match
     * exactly ONE distinct item config among the items held, else warn + back off),
     * then per rule count MY live listings of that config on the AH and, when there
     * are ZERO, queue <listings> fresh ones. Replenish-at-zero is the user's rule:
     * old listings must ALL sell/expire before new ones go up, so the item's total
     * exposure never exceeds one batch.
     */
    private static void startSellPass(Object ctx) {
        sellRules = loadSellsTable();
        if (sellRules.isEmpty()) {
            if (!sellNoRulesLogged) {
                sellNoRulesLogged = true;
                log("[auction] seller: no usable rules in " + SELLS_FILE);
            }
            return;
        }
        sellNoRulesLogged = false;
        try {
            sellSvc = resolveService(ctx);
            if (sellSvc == null)
                return; // not logged in — retry next interval, quietly
            sellNotes = new ArrayList<String>();
            resolveRuleStock(ctx);
            sellRuleIdx = 0;
            sellRunning = true;
            processNextSellRule();
        } catch (Exception e) {
            log("[auction] seller: could not start: " + describe(e));
            sellFinish();
        }
    }

    /**
     * Matches every sells rule against the player's OWN items (display name or config
     * path, same superset the watch matcher uses) and fills rule.config/display/stock.
     * Equipped gear is excluded up front — an auto-lister must never sell what is
     * being worn, and PlayerObject.equipment (real-named) is the equipped-oid array.
     */
    private static void resolveRuleStock(Object ctx) {
        long now = System.currentTimeMillis();
        ArrayList<Object[]> inv = new ArrayList<Object[]>(); // {cfgName, hayLower, Long oid, Integer count, display}
        try {
            Object po = Mappings.getPlayerObject(ctx);
            java.util.HashSet<Long> equipped = new java.util.HashSet<Long>();
            try {
                Object eq = po.getClass().getField("equipment").get(po);
                if (eq instanceof long[])
                    for (long e : (long[]) eq)
                        equipped.add(Long.valueOf(e));
            } catch (Exception ignored) {
            }
            Object cfgmgr = Mappings.getConfigManager(ctx);
            for (Object item : Mappings.getPlayerItems(po)) {
                if (item == null)
                    continue;
                long oid = Mappings.getItemOid(item);
                if (equipped.contains(Long.valueOf(oid)))
                    continue;
                String cfg = Mappings.getItemName(item);
                if (cfg == null)
                    continue;
                int count = 1;
                try {
                    count = ((Integer) item.getClass().getMethod("getCount").invoke(item)).intValue();
                } catch (Exception ignored) {
                }
                String display = displayNameFor(ctx, cfgmgr, item, cfg);
                inv.add(new Object[] { cfg, (display + " " + cfg).toLowerCase(Locale.ROOT),
                        Long.valueOf(oid), Integer.valueOf(count), display });
            }
        } catch (Exception e) {
            log("[auction] seller: inventory read failed: " + e);
        }
        for (SellRule r : sellRules) {
            Long backoff = sellBackoffUntil.get(r.needle);
            if (backoff != null && now < backoff.longValue())
                continue; // config stays null -> the pass skips it
            java.util.LinkedHashSet<String> cfgs = new java.util.LinkedHashSet<String>();
            for (Object[] row : inv)
                if (((String) row[1]).contains(r.needle))
                    cfgs.add((String) row[0]);
            if (cfgs.isEmpty())
                continue; // no stock at all -> nothing to list, nothing to say
            if (cfgs.size() > 1) {
                sellNotes.add("rule '" + r.needle + "' matches more than one item in the inventory "
                        + cfgs + " — skipped; use an exact name (Ctrl+U catalog)");
                sellBackoffUntil.put(r.needle, Long.valueOf(now + SELL_BACKOFF_MS));
                continue;
            }
            String cfg = cfgs.iterator().next();
            ArrayList<long[]> stock = new ArrayList<long[]>();
            String display = null;
            for (Object[] row : inv) {
                if (!cfg.equals(row[0]))
                    continue;
                stock.add(new long[] { ((Long) row[2]).longValue(), ((Integer) row[3]).intValue() });
                display = (String) row[4];
            }
            r.config = cfg;
            r.display = (display == null || display.isEmpty()) ? cfg : display;
            r.stock = stock;
        }
    }

    /**
     * An item's localised display name via its own config: Item.(ConfigManager)->$Original,
     * then Original.(ctx, cfgName)->String — the same two structural lookups the catalogue
     * scan uses, but per held item so a pass never pays the 8s full-catalogue walk.
     */
    private static String displayNameFor(Object ctx, Object cfgmgr, Object item, String cfgName) {
        String cached = displayNameCache.get(cfgName);
        if (cached != null)
            return cached;
        String display = "";
        try {
            if (itemToOriginal == null) {
                for (Method m : item.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0].getName().endsWith("ConfigManager")
                            && m.getReturnType().getName().endsWith("$Original")) {
                        itemToOriginal = m;
                        break;
                    }
                }
            }
            Object orig = (itemToOriginal == null) ? null : itemToOriginal.invoke(item, cfgmgr);
            if (orig != null) {
                // The name method is declared on the $Original base, but guard the cache
                // anyway: a subclass-declared Method invoked on a sibling type throws.
                if (origDisplayName == null || !origDisplayName.getDeclaringClass().isInstance(orig)) {
                    origDisplayName = null;
                    for (Method m : orig.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 2 && p[1] == String.class && m.getReturnType() == String.class
                                && p[0].isInstance(ctx)) {
                            origDisplayName = m;
                            break;
                        }
                    }
                }
                if (origDisplayName != null) {
                    Object d = origDisplayName.invoke(orig, ctx, cfgName);
                    if (d != null)
                        display = d.toString();
                }
            }
        } catch (Exception ignored) {
        }
        displayNameCache.put(cfgName, display);
        return display;
    }

    /** Advances to the next resolved rule's count search, or ends the pass. */
    private static void processNextSellRule() {
        while (sellRuleIdx < sellRules.size() && sellRules.get(sellRuleIdx).config == null)
            sellRuleIdx++;
        if (sellRuleIdx >= sellRules.size()) {
            for (int i = 0; i < sellNotes.size(); i++)
                log("[auction] seller: " + sellNotes.get(i));
            if (!sellCreates.isEmpty())
                log("[auction] seller: queued " + sellCreates.size() + " listing(s)");
            sellFinish();
            return;
        }
        sellMyCount = 0;
        requestSellCountPage(sellRules.get(sellRuleIdx), 0);
    }

    /**
     * One page of the count search for a rule's config. Counting via a normal search
     * (server filtered to exactly this config) + the myAuction flag means MANUAL
     * listings of the item count too — which is right: "0 of the main's listings live"
     * is about the item, not about who clicked the sell button.
     */
    private static void requestSellCountPage(final SellRule rule, final int page) {
        try {
            if (page == 0) {
                java.util.HashSet<String> one = new java.util.HashSet<String>();
                one.add(rule.config);
                sellSearch = newSearch(one);
            }
            Class<?> listenerCls = Class.forName(LISTENER_CLASS);
            Object listener = Proxy.newProxyInstance(AuctionBot.class.getClassLoader(),
                    new Class<?>[] { listenerCls }, new InvocationHandler() {
                        public Object invoke(Object proxy, Method m, Object[] args) {
                            try {
                                if (m.getParameterTypes().length == 1
                                        && m.getParameterTypes()[0] == String.class) {
                                    log("[auction] seller: count search failed on '" + rule.needle
                                            + "': " + (args == null ? "?" : args[0]));
                                    sellFinish();
                                } else {
                                    onSellCountPage(rule, page, (args == null) ? null : args[0]);
                                }
                            } catch (Exception e) {
                                log("[auction] seller: result error: " + e);
                                sellFinish();
                            }
                            return null;
                        }
                    });
            Method call = findSearchMethod(sellSvc);
            if (call == null) {
                log("[auction] seller: search method not found on the service");
                sellFinish();
                return;
            }
            sellPageSentAt = System.currentTimeMillis();
            call.invoke(sellSvc, sellSearch, Integer.valueOf(page), listener);
        } catch (Exception e) {
            log("[auction] seller: request error: " + e);
            sellFinish();
        }
    }

    private static void onSellCountPage(SellRule rule, int page, Object result) throws Exception {
        sellPageSentAt = 0L;
        if (result == null) {
            sellFinish();
            return;
        }
        Class<?> pageCls = result.getClass();
        int pageCount = pageCls.getField("pageCount").getInt(result);
        Object listings = pageCls.getField("listings").get(result);
        if (listings instanceof Iterable)
            for (Object li : (Iterable<?>) listings)
                if (li.getClass().getField("myAuction").getBoolean(li))
                    sellMyCount++;
        int next = page + 1;
        if (next < pageCount && next < MAX_PAGES) {
            requestSellCountPage(rule, next);
            return;
        }
        if (sellMyCount == 0)
            planListings(rule);
        sellRuleIdx++;
        processNextSellRule();
    }

    /**
     * Zero live listings: queue up to <listings> creates, FULL stacks only (user rule:
     * partial stacks are held back rather than listed — the price is for the full
     * quantity). One big stack can supply several listings; the local deduction keeps
     * the plan honest, and a stale count just earns a server refusal that logs.
     */
    private static void planListings(SellRule rule) {
        int planned = 0;
        outer: for (int i = 0; i < rule.listings; i++) {
            for (int s = 0; s < rule.stock.size(); s++) {
                long[] entry = rule.stock.get(s);
                if (entry[1] >= rule.stack) {
                    sellCreates.add(new Create(entry[0], rule.stack, rule.startBid, rule.buyout,
                            rule.durName, rule.display, rule.needle));
                    entry[1] -= rule.stack;
                    planned++;
                    continue outer;
                }
            }
            break; // nothing can fill a whole stack any more
        }
        if (planned > 0) {
            sellNotes.add("'" + rule.display + "': 0 live — queuing " + planned + " listing(s) of "
                    + rule.stack + " @ bid " + rule.startBid
                    + (rule.buyout > 0 ? " / buyout " + rule.buyout : " / no buyout")
                    + " (" + rule.durName + ")"
                    + (planned < rule.listings
                            ? " — stock short of the " + rule.listings + " wanted"
                            : ""));
        } else {
            sellNotes.add("'" + rule.display + "': 0 live but no full stack of " + rule.stack
                    + " held — nothing listed (rechecking in " + (SELL_BACKOFF_MS / 60000) + " min)");
            sellBackoffUntil.put(rule.needle,
                    Long.valueOf(System.currentTimeMillis() + SELL_BACKOFF_MS));
        }
    }

    /**
     * Issues one create-listing call — the exact call the game's sell dialog makes:
     * {@code service.a(itemOid, count, startBid, buyPrice, Duration, featured=false,
     * listener)} (decoded from auction.client's dialog: start_bid feeds arg 3,
     * buy_price arg 4, the featured checkbox arg 6). Resolved structurally: the only
     * 7-arg service method led by (long, int, int, int, Duration, boolean).
     */
    private static void doCreate(final Create c) {
        try {
            // Creates drain AFTER the pass that queued them, so re-resolve rather than
            // hold a stale service reference (same lifetime lesson as the buy queue).
            Object s = resolveService(botCtx);
            if (s == null) {
                log("[auction] seller: service unavailable — dropping listing of " + c.label);
                return;
            }
            Method m = null;
            for (Method cand : s.getClass().getMethods()) {
                Class<?>[] p = cand.getParameterTypes();
                if (p.length == 7 && p[0] == long.class && p[1] == int.class && p[2] == int.class
                        && p[3] == int.class && p[4].getName().equals(DURATION_CLASS)
                        && p[5] == boolean.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                log("[auction] seller: create-listing method not found on the service");
                return;
            }
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object dur = Enum.valueOf((Class) Class.forName(DURATION_CLASS), c.durName);
            // The listing fee is charged up front; measure it as the wallet delta across
            // the confirm so MissionStats' auction-spend correction stays honest. Crowns
            // looted in the ~round-trip window would understate the fee — rare and small.
            final int crownsBefore = Mappings.getCrowns(botCtx);
            Class<?> listenerCls = Class.forName(LISTENER_CLASS);
            Object listener = Proxy.newProxyInstance(AuctionBot.class.getClassLoader(),
                    new Class<?>[] { listenerCls }, new InvocationHandler() {
                        public Object invoke(Object proxy, Method mm, Object[] args) {
                            if (mm.getParameterTypes().length == 1
                                    && mm.getParameterTypes()[0] == String.class) {
                                String why = (args == null) ? "?" : String.valueOf(args[0]);
                                if (isTransientThrottle(why)
                                        && ++c.throttleRetries <= SELL_THROTTLE_MAX_RETRIES) {
                                    // Transient global throttle: requeue this exact create at
                                    // the head and hold the whole queue briefly — retries every
                                    // ~10s until the server accepts, up to the retry cap
                                    // (past it, fall through to the permanent-failure path).
                                    log("[auction] seller: server listing throttle (please_wait) on "
                                            + c.label + " — retrying in "
                                            + (SELL_THROTTLE_RETRY_MS / 1000) + "s ("
                                            + c.throttleRetries + "/" + SELL_THROTTLE_MAX_RETRIES + ")");
                                    sellCreates.add(0, c);
                                    nextSellActionAt = System.currentTimeMillis() + SELL_THROTTLE_RETRY_MS;
                                    return null;
                                }
                                log("[auction] seller: LISTING FAILED on " + c.label + ": "
                                        + why + " — rule '" + c.ruleNeedle
                                        + "' backing off " + (SELL_BACKOFF_MS / 60000) + " min");
                                sellBackoffUntil.put(c.ruleNeedle, Long.valueOf(
                                        System.currentTimeMillis() + SELL_BACKOFF_MS));
                                // The rest of this rule's queued creates would fail the same way.
                                for (java.util.Iterator<Create> it = sellCreates.iterator(); it.hasNext();)
                                    if (it.next().ruleNeedle.equals(c.ruleNeedle))
                                        it.remove();
                            } else {
                                int after = Mappings.getCrowns(botCtx);
                                int fee = (crownsBefore > 0 && after > 0 && crownsBefore > after)
                                        ? crownsBefore - after
                                        : 0;
                                if (fee > 0)
                                    spentThisSession += fee;
                                log("[auction] seller: LISTED " + c.count + "x " + c.label
                                        + " @ bid " + c.startBid
                                        + (c.buyout > 0 ? " / buyout " + c.buyout : " / no buyout")
                                        + " (" + c.durName + (fee > 0 ? ", fee " + fee + "cr" : "") + ")");
                            }
                            return null;
                        }
                    });
            m.invoke(s, Long.valueOf(c.itemOid), Integer.valueOf(c.count),
                    Integer.valueOf(c.startBid), Integer.valueOf(c.buyout), dur,
                    Boolean.FALSE, listener);
        } catch (Exception e) {
            log("[auction] seller: create error on " + c.label + ": " + e);
        }
    }

    private static void sellFinish() {
        sellRunning = false;
        sellPageSentAt = 0L;
        sellSvc = null;
        sellSearch = null;
    }

    // ── sells table ──────────────────────────────────────────────────────────

    /**
     * Reads {@code auction_sells.txt}: one rule per line,
     * {@code <name substring> | <starting bid> | <buyout> | <listings> | [stack, default 1] | [duration, default 1d]}.
     * Buyout 0 = bid-only. Prices accept 100000, 100,000 or 100k; durations 4h, 12h,
     * 1d..7d. Malformed lines are skipped individually (like the watch table).
     */
    private static ArrayList<SellRule> loadSellsTable() {
        ArrayList<SellRule> out = new ArrayList<SellRule>();
        File f = new File(SELLS_FILE);
        if (!f.isFile())
            seedSellsTable(f);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            int no = 0;
            while ((line = br.readLine()) != null) {
                no++;
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty())
                    continue;
                String[] parts = line.split("\\|");
                if (parts.length < 4) {
                    log("[auction] sells line " + no
                            + " needs '<name> | <start bid> | <buyout> | <listings>': " + line);
                    continue;
                }
                String needle = parts[0].trim().toLowerCase(Locale.ROOT);
                int bid = parsePrice(parts[1]);
                int buyout = parsePrice(parts[2]);
                int listings = parsePrice(parts[3]);
                int stack = (parts.length >= 5 && !parts[4].trim().isEmpty()) ? parsePrice(parts[4]) : 1;
                String durTok = (parts.length >= 6 && !parts[5].trim().isEmpty())
                        ? parts[5].trim().toLowerCase(Locale.ROOT)
                        : "1d";
                String durName = durationName(durTok);
                if (needle.isEmpty() || bid < 1 || buyout < 0 || listings < 1 || stack < 1
                        || durName == null) {
                    log("[auction] sells line " + no + " has a bad value: " + line);
                    continue;
                }
                if (buyout > 0 && buyout < bid) {
                    log("[auction] sells line " + no + ": buyout " + buyout
                            + " below starting bid " + bid + " — skipped");
                    continue;
                }
                out.add(new SellRule(needle, bid, buyout, listings, stack, durName));
            }
        } catch (Exception e) {
            log("[auction] could not read " + SELLS_FILE + ": " + e);
        } finally {
            try {
                if (br != null)
                    br.close();
            } catch (Exception e) {
            }
        }
        return out;
    }

    /** Duration column token -> the real-named Duration enum constant (null = bad token). */
    private static String durationName(String tok) {
        if ("4h".equals(tok))
            return "FOUR_HOURS";
        if ("12h".equals(tok))
            return "TWELVE_HOURS";
        if ("1d".equals(tok))
            return "ONE_DAY";
        if ("2d".equals(tok))
            return "TWO_DAYS";
        if ("3d".equals(tok))
            return "THREE_DAYS";
        if ("4d".equals(tok))
            return "FOUR_DAYS";
        if ("5d".equals(tok))
            return "FIVE_DAYS";
        if ("6d".equals(tok))
            return "SIX_DAYS";
        if ("7d".equals(tok))
            return "SEVEN_DAYS";
        return null;
    }

    private static void seedSellsTable(File f) {
        try {
            new File(DIR).mkdirs();
            FileWriter fw = new FileWriter(f);
            fw.write("# sk-utils auction SELLS table (Ctrl+W) — re-read on every pass, no rebuild needed.\n"
                    + "#\n"
                    + "#   <name substring> | <starting bid> | <buyout> | <listings at a time> | [stack size] | [duration]\n"
                    + "#\n"
                    + "# While the seller is ON (main only), each rule keeps <listings> auctions of its\n"
                    + "# item up: once ALL your live listings of that item are gone (sold/expired), it\n"
                    + "# lists <listings> fresh ones. Prices are PER LISTING (the whole stack); buyout 0\n"
                    + "# = bid-only. Stack size defaults to 1. Duration: 4h 12h 1d 2d 3d 4d 5d 6d 7d\n"
                    + "# (default 1d) — the listing fee scales with it.\n"
                    + "# A rule must match exactly ONE distinct item you hold (Ctrl+U dumps exact names).\n"
                    + "# Equipped gear is never listed; only FULL stacks are listed.\n"
                    + "# NOTE: expired unsold listings land in the AH claim box — reclaim those yourself.\n"
                    + "#\n"
                    + "# Mirrored Lockbox | 150k | 200k | 3\n"
                    + "# Green Shard      | 100  | 500  | 2 | 10 | 2d\n");
            fw.close();
            log("[auction] seeded " + SELLS_FILE);
        } catch (Exception e) {
        }
    }

    /** "100000", "100,000" or "100k" -> crowns; -1 when unparseable. */
    private static int parsePrice(String s) {
        try {
            return Integer.parseInt(s.trim().replace(",", "").replace("k", "000"));
        } catch (Exception e) {
            return -1;
        }
    }

    // ── watch table ──────────────────────────────────────────────────────────

    /**
     * Reads {@code auction_watch.txt}: one rule per line,
     * {@code <name substring> | <max buyout> | <exclusion, exclusion, …>}.
     * {@code #} comments, blank lines ignored, matching is case-insensitive. Seeds the
     * file with the starting rules if it does not exist yet.
     */
    private static ArrayList<Rule> loadWatchTable() {
        ArrayList<Rule> out = new ArrayList<Rule>();
        File f = new File(WATCH_FILE);
        if (!f.isFile())
            seedWatchTable(f);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            int no = 0;
            while ((line = br.readLine()) != null) {
                no++;
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty())
                    continue;
                String[] parts = line.split("\\|");
                if (parts.length < 2) {
                    log("[auction] watch line " + no + " needs '<name> | <max price>': " + line);
                    continue;
                }
                String needle = parts[0].trim().toLowerCase(Locale.ROOT);
                int max = parsePrice(parts[1]);
                if (max < 0) {
                    log("[auction] watch line " + no + " has a bad price: " + parts[1].trim());
                    continue;
                }
                String[] ex = new String[0];
                if (parts.length >= 3) {
                    String[] raw = parts[2].split(",");
                    ArrayList<String> keep = new ArrayList<String>();
                    for (int i = 0; i < raw.length; i++) {
                        String s = raw[i].trim().toLowerCase(Locale.ROOT);
                        if (!s.isEmpty())
                            keep.add(s);
                    }
                    ex = keep.toArray(new String[0]);
                }
                if (!needle.isEmpty())
                    out.add(new Rule(needle, max, ex));
            }
        } catch (Exception e) {
            log("[auction] could not read " + WATCH_FILE + ": " + e);
        } finally {
            try {
                if (br != null)
                    br.close();
            } catch (Exception e) {
            }
        }
        return out;
    }

    private static void seedWatchTable(File f) {
        try {
            new File(DIR).mkdirs();
            FileWriter fw = new FileWriter(f);
            fw.write("# sk-utils auction watch table — re-read on every scan, no rebuild needed.\n"
                    + "#\n"
                    + "#   <name substring> | <max buyout in crowns> | <exclusion, exclusion, ...>\n"
                    + "#\n"
                    + "# Matching is case-insensitive substring on the listing's item name.\n"
                    + "# Prices accept 100000, 100,000 or 100k. '#' starts a comment.\n"
                    + "\n"
                    + "Aura             | 100k | Haunted Aura, Ghostly Aura\n"
                    + "Mirrored Lockbox | 200k\n"
                    + "Prismatic        | 50k\n");
            fw.close();
            log("[auction] seeded " + WATCH_FILE);
        } catch (Exception e) {
        }
    }

    private static void log(String msg) {
        SocketInputState.writeLogAlways(msg);
    }
}

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
 * Auction-house sweeper — SEARCH AND REPORT ONLY (no bidding, no buying).
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
    /**
     * Which single-argument service method is BUY OUT. The client jar never calls
     * either candidate — `b(long,listener)` and `a(long,listener)` have no callers
     * anywhere — so it is resolved by TRYING: `b` first, and on a server error the
     * other one, remembering whichever succeeds. Safe to probe because it only ever
     * runs on a listing that already matches a rule and sits under its price cap:
     * if `b` is buyout we bought something we wanted, and if it turns out to be
     * watch/cancel, nothing is lost (you cannot cancel someone else's auction).
     */
    private static String buyMethod = "b";
    private static boolean buyMethodConfirmed = false;

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
        if (scanRunning) {
            if (pageSentAt != 0L && now - pageSentAt >= PAGE_TIMEOUT_MS) {
                log("[auction] no reply for " + (PAGE_TIMEOUT_MS / 1000) + "s — aborting the sweep");
                finish();
            }
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
            log("[auction] could not start: " + e);
            scanRunning = false;
        }
    }

    // ── the game calls ───────────────────────────────────────────────────────

    /** ctx -> presents Client -> the auction service, all by structure (no obfuscated names hardcoded). */
    private static Object resolveService(Object ctx) throws Exception {
        if (ctx == null)
            return null;
        Class<?> clientCls = Class.forName(CLIENT_CLASS);
        Object client = null;
        for (Method m : ctx.getClass().getMethods()) {
            if (m.getParameterTypes().length == 0 && clientCls.isAssignableFrom(m.getReturnType())) {
                m.setAccessible(true);
                client = m.invoke(ctx);
                if (client != null)
                    break;
            }
        }
        if (client == null)
            return null;
        Class<?> svcCls = Class.forName(SVC_CLASS);
        // Client.bS(Class) -> service. Found by shape: one Class parameter, Object return.
        for (Method m : clientCls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == Class.class && m.getReturnType() == Object.class) {
                m.setAccessible(true);
                Object s = m.invoke(client, svcCls);
                if (s != null)
                    return s;
            }
        }
        return null;
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
        Class<?> searchCls = Class.forName(SEARCH_CLASS);
        Object s = searchCls.getConstructor().newInstance();
        searchCls.getField("configs").set(s, configs);
        searchCls.getField("all").setBoolean(s, false);
        searchCls.getField("featured").setBoolean(s, false);
        searchCls.getField("ascending").setBoolean(s, true);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object sort = Enum.valueOf((Class) Class.forName(SORT_CLASS), "BID_PRICE");
        searchCls.getField("sort").set(s, sort);
        log("[auction] " + configs.size() + " item config(s) match the watch rules"
                + (rescanned ? " (watch file changed — catalogue rescanned)" : " (cached)"));
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
            Method call = null;
            for (Method m : svc.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0].getName().equals(SEARCH_CLASS) && p[1] == int.class) {
                    call = m;
                    break;
                }
            }
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
                // value the game's client passes; bidding our cap outright would just
                // pay the cap, since the high bid wins at its own price.
                // This is the ONLY path for bid-only (no-buyout) listings. Each skip
                // is logged with its reason so a "why won't it bid?" is answerable from
                // one live sweep instead of guessed at.
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
            if (a.buyout) {
                Method m = findSvcMethod(buyMethod, new Class<?>[] { long.class, null });
                if (m == null) {
                    log("[auction] no buyout method on the service");
                    return;
                }
                m.invoke(svc, Long.valueOf(a.auctionId), listenerFor(a));
                log("[auction] BUY " + a.name + " for " + a.amount + "cr (id=" + a.auctionId
                        + ", via '" + buyMethod + "'" + (buyMethodConfirmed ? "" : ", UNCONFIRMED") + ")");
            } else {
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
                myBids.put(Long.valueOf(a.auctionId), Integer.valueOf(a.amount));
                log("[auction] BID " + a.amount + "cr on " + a.name + " (id=" + a.auctionId
                        + ", cap " + a.ruleMax + ")");
            }
            if (a.buyout)
                boughtIds.add(Long.valueOf(a.auctionId)); // buy once; bids may repeat when outbid
            spentThisSession += a.amount;
        } catch (Exception e) {
            log("[auction] action error on " + a.name + ": " + e);
        }
    }

    /** A service method by name whose first parameter is long and which takes a listener. */
    private static Method findSvcMethod(String name, Class<?>[] shape) {
        for (Method m : svc.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == long.class && name.equals(m.getName()))
                return m;
        }
        return null;
    }

    /** Result listener that records success and, for buyouts, calibrates which method works. */
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
                            if (a.buyout && !buyMethodConfirmed) {
                                // Wrong candidate: switch to the other single-arg method
                                // and let the next sweep retry this listing.
                                buyMethod = "b".equals(buyMethod) ? "a" : "b";
                                log("[auction] buyout method '" + (("b".equals(buyMethod)) ? "a" : "b")
                                        + "' rejected — trying '" + buyMethod + "' next");
                            }
                        } else {
                            if (a.buyout && !buyMethodConfirmed) {
                                buyMethodConfirmed = true;
                                log("[auction] buyout confirmed on service method '" + buyMethod + "'");
                            }
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
                int max;
                try {
                    max = Integer.parseInt(parts[1].trim().replace(",", "").replace("k", "000"));
                } catch (NumberFormatException nfe) {
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

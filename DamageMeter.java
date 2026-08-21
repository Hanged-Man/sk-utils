package com.threerings.opengl.gui;

import com.threerings.opengl.gui.config.BackgroundConfig;
import com.threerings.opengl.gui.config.FontConfig;
import com.threerings.opengl.gui.config.InsetsConfig;
import com.threerings.opengl.gui.config.StyleConfig;
import com.threerings.opengl.renderer.Color4f;
import com.threerings.opengl.util.d;

/**
 * "DPS Breakdown" card — per-player mission damage. damage bars fill by each knight's SHARE of the party total; numbers use k/M.
 *
 * Totals shared via DMGSTAT broadcasts every ~2s, reset whenever THIS client
 * enters a new dungeon instance and via
 * the campaign's DMGRESET broadcast (MissionStats.missionStart); SocketInputState
 * forwards those two UDP messages and lends its socket.
 * Lives in its own Patcher-generated click-transparent overlay
 * window (DamageMeterWindow), anchored halfway down the screen's right edge;
 * visibility follows the ` HUD toggle.
 */
public class DamageMeter {

    private static final long SHARE_INTERVAL_MS = 2000L;

    // Card geometry (Y-UP, matching the tooltip parts' proportions).
    private static final int CARD_W = 202;
    private static final int PAD_X = 12;              // backing_stats content padding
    private static final int CONTENT_W = CARD_W - 2 * PAD_X; // 178
    private static final int TITLE_H = 16;
    private static final int NAME_H = 13;
    private static final int BAR_ROW_H = 16;          // stat_bar height
    private static final int ROW_GAP = 3;
    private static final int N_ROWS = Math.max(1, SKConfig.PARTY_SIZE); // one bar per knight (config party_size)
    private static final int CARD_H = 8 + TITLE_H + 2 + N_ROWS * (NAME_H + BAR_ROW_H + ROW_GAP) - ROW_GAP + 8; // 159
    private static final int BAR_X = 8;               // fill inset inside the track (stat_bar: bars at x=8)
    private static final int BAR_MAX_W = CONTENT_W - 2 * BAR_X; // 162
    private static final int BAR_Y = 3, BAR_H = 10;   // fill rect inside the row (stat_bar: y=3, h=10)
    private static final int ICON_S = 16;             // icon at the row's left edge
    private static final int MARGIN_R = 8;            // card offset from the screen's right edge

    // Vanilla tooltip palette (decoded from stat_bar.dat / container_stats.dat).
    private static final Color4f COLOR_BONUS = new Color4f(0.16078432f, 0.8392157f, 1.0f, 1.0f);
    private static final Color4f COLOR_STAT_TEXT = new Color4f(0.06666667f, 0.2509804f, 0.32941177f, 1.0f);
    private static final Color4f COLOR_LABEL = new Color4f(0.2901961f, 0.43529412f, 0.54901963f, 1.0f);

    public static volatile long ownMissionDamage = 0L;
    private static volatile int myPawnId = 0; // this client's controlled-pawn ACTOR id (refreshed each tick; changes per floor)
    private static final java.util.Map<String, Long> statMap =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static volatile long lastShareAt = 0L;
    private static Object lastInstance; // dungeonClient identity at the last tick — change = new dungeon entered

    private static Object _window;
    private static boolean _needsReinstate = false;
    private static boolean _visible = true;
    private static Label _title;
    private static Label[] _names = new Label[N_ROWS];
    private static Label[] _tracks = new Label[N_ROWS]; // backing_bonusbar (nine-sliced)
    private static Label[] _fills = new Label[N_ROWS];  // solid cyan share bar
    private static Label[] _hashes = new Label[N_ROWS]; // hashmarks overlay (TILE_X)
    private static Label[] _nums = new Label[N_ROWS];   // raw damage (k/M)
    private static Label[] _icons = new Label[N_ROWS];  // attack_normal.png

    // ── Data plumbing ─────────────────────────────────────────────────────────

    /**
     * Called by the instrumented DamageEvent.applyToObject on EVERY received event.
     * Count only "damage"-named events (heals ride the same class) from MY pawn —
     * the game's own render filter, replicated — so the total is exactly "the
     * numbers I see on my screen".
     */
    public static void recordDamageEvent(String name, int sourceId, int actorId, int amount) {
        if (amount <= 0 || !"damage".equals(name))
            return;
        int me = myPawnId;
        if (me == 0 || sourceId != me)
            return;
        ownMissionDamage += amount; // single writer (this client's event thread)
    }

    /** SocketInputState's DMGSTAT handler: a character shared its mission total. */
    static void recordStat(String knightName, long total) {
        statMap.put(knightName, Long.valueOf(total));
    }

    /** SocketInputState's DMGRESET handler (mission start): zero the tally. */
    static void reset() {
        ownMissionDamage = 0L;
        statMap.clear();
    }

    // ── Window lifecycle (called from HeatHudPanel's init/tick) ───────────────

    public static void onWindowRemoved() {
        _needsReinstate = true;
    }

    /** Build the card window + styled children and add it to the Root. */
    static void init(Object ctxObj) {
        if (_window != null)
            return;
        try {
            d ctx = (d) ctxObj;
            _window = Class.forName("com.threerings.opengl.gui.DamageMeterWindow")
                    .getConstructor(d.class).newInstance(ctx);

            // Styles — verbatim ports of the vanilla tooltip-part styles.
            StyleConfig cardStyle = style(null, null, 0, null,
                    imageBg("ui/window/tooltip_parts/backing_stats.png",
                            BackgroundConfig.Image.Mode.FRAME_XY, 8));
            StyleConfig statTextStyle = style(COLOR_STAT_TEXT, null, 0, null, null); // "stat_text"
            StyleConfig numStyle = style(COLOR_LABEL, FontConfig.Style.ITALIC, 11,
                    StyleConfig.TextAlignment.RIGHT, null); // "label"
            StyleConfig trackStyle = style(null, null, 0, null,
                    imageBg("ui/window/tooltip_parts/backing_bonusbar.png",
                            BackgroundConfig.Image.Mode.FRAME_XY, 8)); // "backing"
            BackgroundConfig.Solid fillBg = new BackgroundConfig.Solid();
            fillBg.color = COLOR_BONUS;
            StyleConfig fillStyle = style(null, null, 0, null, fillBg); // "bar" (Bonus)
            StyleConfig hashStyle = style(null, null, 0, null,
                    imageBg("ui/window/tooltip_parts/hashmarks.png",
                            BackgroundConfig.Image.Mode.TILE_X, -1)); // "hashmarks"
            StyleConfig iconStyle = style(null, null, 0, null,
                    imageBg("ui/icon/stats/attack_normal.png",
                            BackgroundConfig.Image.Mode.FRAME_XY, 0)); // "icon"

            // Hand-made ManagedConfigs need the live ConfigManager before components
            // listen to them; a failure in any single style application must never
            // stop the card from building (worst case: that piece renders default).
            Object cfgmgr = Mappings.getConfigManager(ctxObj);
            for (StyleConfig sc : new StyleConfig[] { cardStyle, statTextStyle, numStyle,
                    trackStyle, fillStyle, hashStyle, iconStyle })
                initConfig(sc, cfgmgr);

            setStyle(_window, cardStyle);
            _title = new Label(ctx, "DPS Breakdown"); // default (offwhite) style per user
            addChild(_window, _title);
            for (int i = 0; i < N_ROWS; i++) {
                _tracks[i] = new Label(ctx, "");
                setStyle(_tracks[i], trackStyle);
                addChild(_window, _tracks[i]);
                _fills[i] = new Label(ctx, "");
                setStyle(_fills[i], fillStyle);
                addChild(_window, _fills[i]);
                _hashes[i] = new Label(ctx, "");
                setStyle(_hashes[i], hashStyle);
                addChild(_window, _hashes[i]);
                _nums[i] = new Label(ctx, "");
                setStyle(_nums[i], numStyle);
                addChild(_window, _nums[i]);
                _icons[i] = new Label(ctx, "");
                setStyle(_icons[i], iconStyle);
                addChild(_window, _icons[i]);
                _names[i] = new Label(ctx, "");
                setStyle(_names[i], statTextStyle);
                addChild(_window, _names[i]);
            }
            Mappings.addWindowToRoot(ctxObj, _window);
        } catch (Throwable t) {
            ForgeTracker.debug("DamageMeter.init ERROR: " + t);
        }
    }

    /** HeatHudPanel.tick (throttled by the HUD): pawn-id refresh, share, display. */
    static void tick(Object ctxObj) {
        long now = System.currentTimeMillis();
        // Refresh my controlled pawn's actor id (changes every floor; the event
        // filter compares _sourceId against it).
        try {
            Integer pid = Reflect.readIntFieldNullable(
                    SocketInputState.dungeonClient, MappingsNames.PAWN_ID_FIELD);
            myPawnId = (pid == null) ? 0 : pid.intValue();
        } catch (Exception ignored) {
        }
        // Fresh tally per DUNGEON INSTANCE: dungeonClient is repointed by its injected
        // ctor exactly once per instance and stays stale after leaving, so a reference
        // change here IS "entered a new dungeon". Every party member runs this on its
        // own entry; the cleared statMap re-syncs within one DMGSTAT interval. The
        // campaign's DMGRESET broadcast stays as the synchronized mission-start reset.
        Object dc = SocketInputState.dungeonClient;
        if (dc != null && dc != lastInstance) {
            lastInstance = dc;
            reset();
        }
        if (now - lastShareAt >= SHARE_INTERVAL_MS) {
            lastShareAt = now;
            try {
                String kn = Mappings.getKnightCharacterName(Mappings.getPlayerObject(ctxObj));
                if (kn != null && !kn.isEmpty())
                    SocketInputState.broadcastAll("DMGSTAT " + ownMissionDamage + " " + kn);
            } catch (Exception ignored) {
            }
        }
        if (_window == null)
            return;
        if (_needsReinstate) {
            _needsReinstate = false;
            try {
                Mappings.addWindowToRoot(ctxObj, _window);
            } catch (Throwable t) {
                ForgeTracker.debug("DamageMeter.reinstate ERROR: " + t);
            }
        }
        boolean visible = SocketInputState.showHeatHud;
        if (visible != _visible) {
            _visible = visible;
            callSetVisible(_window, visible);
        }
        refresh();
    }

    /** DamageMeterWindow.layoutWindow: place the card + all children (Y-UP coords). */
    public static void onLayout(Object window, int screenW, int screenH) {
        try {
            // Right edge of the screen, vertically centred (Y-UP: y = bottom of the card).
            setBounds(window, screenW - CARD_W - MARGIN_R, (screenH - CARD_H) / 2, CARD_W, CARD_H);
            int y = CARD_H - 8 - TITLE_H;
            setBounds(_title, PAD_X, y, CONTENT_W, TITLE_H);
            y -= 2;
            for (int i = 0; i < N_ROWS; i++) {
                y -= NAME_H;
                setBounds(_names[i], PAD_X, y, CONTENT_W, NAME_H);
                y -= BAR_ROW_H;
                setBounds(_tracks[i], PAD_X, y, CONTENT_W, BAR_ROW_H);
                // fill width is data-driven; refresh() re-sets it, so give a zero here
                setBounds(_fills[i], PAD_X + BAR_X, y + BAR_Y, 0, BAR_H);
                // Hashmarks span the full 16px row (their native height) so the ticks
                // centre on the bar — pinned to the fill rect they floated 3px high.
                setBounds(_hashes[i], PAD_X + BAR_X, y, BAR_MAX_W, BAR_ROW_H);
                setBounds(_nums[i], PAD_X + BAR_X, y, BAR_MAX_W, BAR_ROW_H);
                setBounds(_icons[i], PAD_X, y, ICON_S, ICON_S);
                y -= ROW_GAP;
            }
            refresh();
        } catch (Throwable t) {
            ForgeTracker.debug("DamageMeter.onLayout ERROR: " + t);
        }
    }

    /** Knight names in MY current dungeon party (PartyObject.members), or empty if none. */
    private static java.util.HashSet<String> partyNames() {
        java.util.HashSet<String> names = new java.util.HashSet<String>();
        try {
            Object party = Reflect.partyObjectOf(SocketInputState.dungeonClient);
            Object members = (party == null) ? null : Reflect.readObjectFieldNullable(party, "members");
            if (members != null)
                for (Object m : (Iterable<?>) members) {
                    Object n = Reflect.readObjectFieldNullable(m, "name");
                    if (n != null)
                        names.add(n.toString());
                }
        } catch (Exception ignored) {
        }
        return names;
    }

    /** Re-fills names, numbers, and bar widths from the latest shared totals. */
    private static void refresh() {
        // Only knights actually IN the dungeon with me — DMGSTAT broadcasts arrive
        // from every logged-in client, so filter by my party roster (no bars for
        // alts in readyroom / Haven).
        java.util.HashSet<String> roster = partyNames();
        java.util.List<java.util.Map.Entry<String, Long>> es =
                new java.util.ArrayList<java.util.Map.Entry<String, Long>>();
        for (java.util.Map.Entry<String, Long> e : statMap.entrySet())
            if (roster.contains(e.getKey()))
                es.add(e);
        es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long partyTotal = 0L;
        for (java.util.Map.Entry<String, Long> e : es)
            partyTotal += e.getValue();
        int rowTop = CARD_H - 8 - TITLE_H - 2;
        for (int i = 0; i < N_ROWS; i++) {
            int barY = rowTop - NAME_H - BAR_ROW_H;
            boolean has = i < es.size();
            _names[i].setText(has ? es.get(i).getKey() : "");
            _nums[i].setText(has ? fmt(es.get(i).getValue()) : "");
            int w = 0;
            if (has && partyTotal > 0)
                w = (int) (BAR_MAX_W * es.get(i).getValue() / (double) partyTotal);
            setBounds(_fills[i], PAD_X + BAR_X, barY + BAR_Y, Math.max(0, Math.min(w, BAR_MAX_W)), BAR_H);
            callSetVisible(_tracks[i], has && _visible);
            callSetVisible(_hashes[i], has && _visible);
            callSetVisible(_icons[i], has && _visible);
            rowTop -= NAME_H + BAR_ROW_H + ROW_GAP;
        }
    }

    /** Raw damage, compact: k for thousands, M for millions. */
    private static String fmt(long v) {
        if (v >= 1000000)
            return String.format("%.2fM", v / 1000000f);
        if (v >= 1000)
            return String.format("%.1fK", v / 1000f);
        return Long.toString(v);
    }

    // ── Style builders (real-named Clyde config classes — no reflection) ──────

    /** A BackgroundConfig.Image; frame &gt;= 0 adds uniform FRAME insets. */
    private static BackgroundConfig.Image imageBg(String file, BackgroundConfig.Image.Mode mode, int frame) {
        BackgroundConfig.Image bg = new BackgroundConfig.Image();
        bg.file = file;
        bg.mode = mode;
        if (frame >= 0) {
            InsetsConfig ins = new InsetsConfig();
            ins.top = frame;
            ins.right = frame;
            ins.bottom = frame;
            ins.left = frame;
            bg.frame = ins;
        }
        return bg;
    }

    /** A StyleConfig with the given text attributes and/or background (nulls = defaults). */
    private static StyleConfig style(Color4f color, FontConfig.Style fontStyle, int fontSize,
            StyleConfig.TextAlignment align, BackgroundConfig bg) {
        StyleConfig sc = new StyleConfig();
        StyleConfig.Original o = new StyleConfig.Original();
        if (color != null) {
            o.color = color;
            o.font = "Arial";
        }
        if (fontStyle != null)
            o.fontStyle = fontStyle;
        if (fontSize > 0)
            o.fontSize = fontSize;
        if (align != null)
            o.textAlignment = align;
        if (bg != null)
            o.background = bg;
        sc.implementation = o;
        return sc;
    }

    /** setStyleConfigs on any component — fault-isolated so one bad style can't kill init. */
    private static void setStyle(Object comp, StyleConfig sc) {
        try {
            comp.getClass().getMethod("setStyleConfigs", StyleConfig[].class)
                    .invoke(comp, (Object) new StyleConfig[] { sc });
        } catch (Throwable e) {
            Throwable c = (e instanceof java.lang.reflect.InvocationTargetException) ? e.getCause() : e;
            ForgeTracker.debug("DamageMeter.setStyle ERR on " + comp.getClass().getSimpleName()
                    + ": " + c + (c.getStackTrace().length > 0 ? " @ " + c.getStackTrace()[0] : ""));
        }
    }

    /** Registers a hand-made ManagedConfig with the live ConfigManager (reflective init). */
    private static void initConfig(StyleConfig sc, Object cfgmgr) {
        try {
            for (java.lang.reflect.Method m : sc.getClass().getMethods()) {
                if ("init".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().equals("com.threerings.config.ConfigManager")) {
                    m.invoke(sc, cfgmgr);
                    return;
                }
            }
        } catch (Throwable e) {
            ForgeTracker.debug("DamageMeter.initConfig ERR: " + e);
        }
    }

    // ── Widget helpers (self-contained; reflection over the obfuscated GUI) ───

    private static void addChild(Object parent, Object child) {
        try {
            for (java.lang.reflect.Method m : parent.getClass().getMethods()) {
                if ("add".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] != int.class
                        && !m.getParameterTypes()[0].getName().equals("java.lang.Object")) {
                    m.invoke(parent, child);
                    return;
                }
            }
        } catch (Exception e) {
            ForgeTracker.debug("DamageMeter.addChild ERR: " + e);
        }
    }

    private static void setBounds(Object comp, int x, int y, int w, int h) {
        try {
            comp.getClass()
                    .getMethod("setBounds", int.class, int.class, int.class, int.class)
                    .invoke(comp, x, y, w, h);
        } catch (Exception ignored) {
        }
    }

    private static void callSetVisible(Object comp, boolean visible) {
        if (comp == null)
            return;
        try {
            comp.getClass().getMethod("setVisible", boolean.class).invoke(comp, visible);
        } catch (Exception ignored) {
        }
    }
}

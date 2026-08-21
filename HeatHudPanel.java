package com.threerings.opengl.gui;

import com.threerings.opengl.renderer.Color4f;
import com.threerings.opengl.util.d;

public class HeatHudPanel {

    // Row 0 (top):    helm(0), armor(1), weapon1(6), weapon2(7)
    // Row 1 (bottom): empty,  shield(2), weapon3(8), weapon4(9)
    private static final int[] SLOT_INDICES = {0, 1, 6, 7, -1, 2, 8, 9};
    private static final int N_COLS = 4;
    private static final int N_ROWS = 2;
    private static final int N     = N_COLS * N_ROWS;  // 8

    // Layout — Y-UP: y=0 = window bottom, y=WINDOW_H = window top = screen top.
    // Each row is ROW_H tall. Row 0 (top) has yOff=ROW_H; row 1 (bottom) has yOff=0.
    private static final int CARD_W    = 136;
    private static final int ICON_W    = 32;
    private static final int ICON_H    = 32;
    private static final int ICON_GAP  = 16;
    private static final int CONTENT_W  = CARD_W - ICON_W - ICON_GAP;      // 102
    private static final int LABEL_H    = 11;
    private static final int BAR_H      = 12;
    private static final int BAR_Y      = 7;                              // bar bottom in Y-UP
    private static final int LABEL_Y    = BAR_Y + BAR_H + 4;             // 21 — level text bottom
    private static final int BAR_EXTRA  = 5;                              // offset from icon-right to text/bar start
    private static final int BAR_TEXT_W    = CONTENT_W - BAR_EXTRA;       // 97 — text region width
    private static final int BAR_PAD      = 2;                           // bar extends 2px past text on each side
    private static final int BAR_W        = BAR_TEXT_W + 2 * BAR_PAD;   // 99 — total bar width
    // Clyde centers text within label bounds; no alignment API available.
    // Shift labels left so the centered text visually lands near barX.
    // Derived from: shift = (BAR_TEXT_W - estimatedTextW) / 2
    private static final int PCT_X_SHIFT   = 16; // "50%"    ~27px in 97px label
    private static final int ICON_Y     = 4;                              // icon bottom in Y-UP
    private static final int PADDING   = 1;
    private static final int GROUP_GAP = 10;
    private static final int ROW_H     = 42;                             // height per row
    private static final int SYNC_BTN_W    = CARD_W / 2;  // 68
    private static final int SYNC_BTN_H    = ROW_H  / 2;  // 21
    static final int WINDOW_H = ROW_H * N_ROWS;                          // 84

    private static HeatHudWindow _window;
    private static Label[] _iconLabels;
    private static Label[] _levelLabels;   // shows "Level X" to the right of icon
    private static Label[] _heatTrack;
    private static Label[] _heatFill;
    private static Label[] _heatPctLabels;
    private static int[]   _slotX;
    private static int[]   _slotYOff;
    static Object  _ctx;  // package-visible for SocketInputState's COORD_REQUEST handler

    private static Object _trackBg;
    private static Object _orangeBg;
    private static Object _greenBg;

    private static com.threerings.opengl.gui.e _syncBtn;
    private static Object _btnWindow;

    private static int     _tickCount          = 0;
    private static boolean _hudVisible         = true;   // tracks applied HUD visibility
    private static boolean _needsReinstate     = false;
    private static boolean _needsBtnReinstate  = false;
    private static float[]   _prevHeatProgress  = initPrevHP();
    private static boolean[] _postDistribution  = new boolean[N];
    private static long[]    _itemOids          = new long[N];
    private static Object    _registeredPoRef   = null;
    private static float[] initPrevHP() {
        float[] a = new float[N];
        java.util.Arrays.fill(a, -1.0f);
        return a;
    }

    public static void onWindowRemoved() {
        _needsReinstate = true;
    }

    public static void onBtnWindowRemoved() {
        _needsBtnReinstate = true;
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    // Suppress "Dismissing left-open window" log spam for our overlay windows.
    // dx.wasRemoved() logs+dismisses any window still in the root on scene exit; since we
    // intentionally persist and reinstate them, this fires every transition. One-time filter.
    private static boolean _logFilterInstalled = false;
    private static void installLogFilter() {
        if (_logFilterInstalled) return;
        _logFilterInstalled = true;
        try {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            java.util.logging.Filter prev = root.getFilter();
            root.setFilter(record -> {
                if (record.getMessage() != null
                        && record.getMessage().startsWith("Dismissing left-open window")) {
                    Object[] p = record.getParameters();
                    if (p != null) {
                        for (Object o : p) {
                            String s = o != null ? o.toString() : "";
                            if (s.contains("HeatHudWindow") || s.contains("HeatSyncBtnWindow")
                                    || s.contains("DamageMeterWindow"))
                                return false;
                        }
                    }
                }
                return prev == null || prev.isLoggable(record);
            });
        } catch (Exception ignored) {}
    }

    public static void init(Object ctxObj) {
        if (_window != null) return;
        installLogFilter();
        try {
            _ctx = ctxObj;
            d ctx = (d) ctxObj;
            _trackBg  = makeBg(0.20f, 0.20f, 0.24f, 1.0f);
            _orangeBg = makeBg(0.90f, 0.45f, 0.0f,  1.0f);
            _greenBg  = makeBg(0.15f, 0.85f, 0.15f, 1.0f);
            _window = new HeatHudWindow(ctx);
            setupChildren(ctx);
            Mappings.addWindowToRoot(ctxObj, _window);
            if (_btnWindow != null) Mappings.addWindowToRoot(ctxObj, _btnWindow);
            DamageMeter.init(ctxObj); // the DPS-breakdown card (its own overlay window)
        } catch (Throwable t) {
            ForgeTracker.debug("HeatHudPanel.init ERROR: " + t);
        }
    }

    public static void tick(Object ctxObj) {
        // Always cache ctx so background threads (e.g. COORD_REQUEST handler) can use it.
        SocketInputState._cachedCtx = ctxObj;

        if (_window == null) return;
        if (_needsReinstate) {
            _needsReinstate = false;
            try { Mappings.addWindowToRoot(ctxObj, _window); }
            catch (Throwable t) { ForgeTracker.debug("HeatHudPanel.reinstate ERROR: " + t); }
        }
        if (_needsBtnReinstate) {
            _needsBtnReinstate = false;
            try { if (_btnWindow != null) Mappings.addWindowToRoot(ctxObj, _btnWindow); }
            catch (Throwable t) { ForgeTracker.debug("HeatHudPanel.reinstateBtn ERROR: " + t); }
        }

        // Show/hide the HUD per the multibox ` toggle. Re-applied on change and
        // after any reinstate, so it stays in sync with the flag.
        boolean visible = SocketInputState.showHeatHud;
        if (visible != _hudVisible) {
            _hudVisible = visible;
            callSetVisible(_window, visible);
            callSetVisible(_btnWindow, visible);
        }

        if (++_tickCount % 12 != 0) return;

        // Per-player damage meter (separate class; see DamageMeter): share + refresh.
        try { DamageMeter.tick(ctxObj); } catch (Throwable ignored) {}

        try {
            Object po        = Mappings.getPlayerObject(ctxObj);
            long[] equipment = Mappings.getPlayerEquipment(po);
            Object configMgr = Mappings.getConfigManager(ctxObj);

            // Pass 1 — resolve items so we can count non-max receivers
            long[]   oids  = new long[N];
            Object[] items = new Object[N];
            for (int i = 0; i < N; i++) {
                int slot = SLOT_INDICES[i];
                oids[i]  = (slot >= 0 && slot < equipment.length) ? equipment[slot] : 0L;
                items[i] = (oids[i] > 0) ? findItemByOid(po, oids[i]) : null;
            }

            // Compute per-item share of stage heat (0 when in town or no heat yet)
            float share = 0f;
            float rawHeat = Mappings.getStageHeatPool(ctxObj, po);
            if (rawHeat > 0f) {
                float stageHeat = rawHeat;
                try {
                    if (po.getClass().getField("heatBonus").getBoolean(po)) stageHeat *= 2f;
                } catch (Exception ignored) {}
                int nonMaxCount = 0;
                for (int i = 0; i < N; i++) {
                    if (SLOT_INDICES[i] < 0 || items[i] == null) continue;
                    if (Mappings.getItemRawLevel(items[i]) < 9) {
                        float hp = 0f;
                        try { hp = Mappings.getHeatProgress(items[i]); } catch (Exception ignored) {}
                        if (hp < 1.0f) nonMaxCount++;
                    }
                }
                if (nonMaxCount > 0) share = stageHeat / nonMaxCount;
            }

            // Snapshot OIDs for the listener callback, then ensure listener is registered.
            System.arraycopy(oids, 0, _itemOids, 0, N);
            ensureItemListener(po);

            // Pass 2 — update UI
            for (int i = 0; i < N; i++) {
                updateSlot(i, oids[i], items[i], configMgr, share);
            }
        } catch (Throwable t) {
            ForgeTracker.debug("HeatHudPanel.tick ERROR: " + t);
        }
    }

    public static void onLayout(Object window, int screenW, int screenH) {
        if (_levelLabels == null) return;
        try {
            for (int i = 0; i < N; i++) {
                callSetBackground(_heatTrack[i], _trackBg);
                callSetBackground(_heatFill[i],  _orangeBg);
            }
            int totalW = N_COLS * CARD_W + (N_COLS - 1) * PADDING + GROUP_GAP;
            int startX = (screenW > 0) ? Math.max(PADDING, (screenW - totalW) / 2) : PADDING;
            for (int row = 0; row < N_ROWS; row++) {
                int yOff = (N_ROWS - 1 - row) * ROW_H;  // row 0 → ROW_H (top); row 1 → 0 (bottom)
                int x = startX;
                for (int col = 0; col < N_COLS; col++) {
                    if (col == 2) x += GROUP_GAP;
                    layoutCard(row * N_COLS + col, x, yOff);
                    x += CARD_W + PADDING;
                }
            }
            // Slot i=4 (col=0, row=1) is empty — use it for the SYNC button.
            // hudBtnX tells HeatSyncBtnWindow.layoutWindow where to position itself on screen.
            // The button itself sits at local (0,0) filling its 68x21 parent window.
            int syncX = _slotX[4] + (CARD_W - SYNC_BTN_W) / 2;
            SocketInputState.hudBtnX = syncX;
            if (_syncBtn != null) {
                setBounds(_syncBtn, 0, 0, SYNC_BTN_W, SYNC_BTN_H);
            }
        } catch (Throwable t) {
            ForgeTracker.debug("HeatHudPanel.onLayout ERROR: " + t);
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private static void setupChildren(d ctx) throws Exception {
        _iconLabels    = new Label[N];
        _levelLabels   = new Label[N];
        _heatTrack     = new Label[N];
        _heatFill      = new Label[N];
        _heatPctLabels = new Label[N];
        _slotX         = new int[N];
        _slotYOff      = new int[N];

        for (int i = 0; i < N; i++) {
            _iconLabels[i]    = new Label(ctx, "");
            _levelLabels[i]   = new Label(ctx, "");
            _heatTrack[i]     = new Label(ctx, "");
            _heatFill[i]      = new Label(ctx, "");
            _heatPctLabels[i] = new Label(ctx, "");

            addChild(_window, _iconLabels[i]);
            addChild(_window, _levelLabels[i]);
            addChild(_window, _heatTrack[i]);
            addChild(_window, _heatFill[i]);
            addChild(_window, _heatPctLabels[i]);
        }

        try {
            _btnWindow = Class.forName("com.threerings.opengl.gui.HeatSyncBtnWindow")
                .getDeclaredConstructors()[0].newInstance(ctx);
        } catch (Exception e) {
            ForgeTracker.debug("setupChildren btnWindow ERROR: " + e);
        }

        _syncBtn = new com.threerings.opengl.gui.e(ctx, "SYNC",
            new com.threerings.opengl.gui.event.a() {
                public void actionPerformed(com.threerings.opengl.gui.event.ActionEvent ev) {
                    ForgeTracker.debug("SYNC clicked ctx=" + (_ctx != null ? "ok" : "NULL"));
                    com.threerings.projectx.item.client.ForgeAllAdapter.manualCoordEquip(_ctx);
                }
            }, "sync");
        if (_btnWindow != null) {
            addChild(_btnWindow, _syncBtn);
        } else {
            addChild(_window, _syncBtn);
        }
    }

    // ── Per-slot update ───────────────────────────────────────────────────────

    private static void updateSlot(int i, long oid, Object item, Object configMgr, float share) {
        int barX = (_slotX != null ? _slotX[i] : 0) + ICON_W + ICON_GAP + BAR_EXTRA;
        int yOff = _slotYOff != null ? _slotYOff[i] : 0;
        if (oid <= 0) {
            _prevHeatProgress[i] = -1.0f;
            _postDistribution[i] = false;
            _levelLabels[i].setText("");
            _heatPctLabels[i].setText("");
            setBounds(_heatTrack[i], barX - BAR_PAD, BAR_Y + yOff, 0,     BAR_H);
            setBounds(_heatFill[i],  barX - BAR_PAD, BAR_Y + yOff, 0,     BAR_H);
            return;
        }
        if (item == null) {
            _prevHeatProgress[i] = -1.0f;
            _postDistribution[i] = false;
            _levelLabels[i].setText("---");
            _heatPctLabels[i].setText("");
            setBounds(_heatTrack[i], barX - BAR_PAD, BAR_Y + yOff, BAR_W, BAR_H);
            setBounds(_heatFill[i],  barX - BAR_PAD, BAR_Y + yOff, 0,     BAR_H);
            return;
        }
        setBounds(_heatTrack[i], barX - BAR_PAD, BAR_Y + yOff, BAR_W, BAR_H);

        int rawLevel = Mappings.getItemRawLevel(item);
        boolean maxLevel = rawLevel >= 9;

        float currentHeat = 0.0f;
        try { currentHeat = Mappings.getHeatProgress(item); } catch (Exception ignored) {}

        // Delta detection: if _heatProgress jumped since last tick, items already got heat.
        boolean deltaDetected = _prevHeatProgress[i] >= 0f
            && Math.abs(currentHeat - _prevHeatProgress[i]) > 0.005f;
        if (deltaDetected && !_postDistribution[i]) {
            _postDistribution[i] = true;
        }
        if (share <= 0f && !deltaDetected && _postDistribution[i]) {
            _postDistribution[i] = false;
        }
        _prevHeatProgress[i] = currentHeat;

        float displayHeat = currentHeat;
        if (!maxLevel && share > 0f) {
            if (!_postDistribution[i]) {
                float projected = Mappings.projectHeatProgress(item, configMgr, share);
                if (projected > currentHeat) displayHeat = projected;
            }
        }

        _levelLabels[i].setText("Lv " + (rawLevel + 1));

        int fillW = maxLevel ? BAR_W : (int)(Math.min(1.0f, displayHeat) * BAR_W);
        setBounds(_heatFill[i], barX - BAR_PAD, BAR_Y + yOff, fillW, BAR_H);
        callSetBackground(_heatFill[i], (displayHeat >= 1.0f || maxLevel) ? _greenBg : _orangeBg);

        _heatPctLabels[i].setText(maxLevel ? "MAX" : Math.round(displayHeat * 100) + "%");

        try {
            for (java.lang.reflect.Method m : item.getClass().getMethods()) {
                if ("getIcon".equals(m.getName()) && m.getParameterCount() == 2
                        && m.getParameterTypes()[1] == int.class) {
                    Object icon = m.invoke(item, _ctx, ICON_W);
                    if (icon != null) callSetIcon(_iconLabels[i], icon);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private static void layoutCard(int i, int x, int yOff) {
        _slotX[i]    = x;
        _slotYOff[i] = yOff;
        int barX = x + ICON_W + ICON_GAP + BAR_EXTRA;
        setBounds(_iconLabels[i],    x,                     ICON_Y  + yOff, ICON_W,     ICON_H);
        setBounds(_levelLabels[i],   barX - PCT_X_SHIFT,    LABEL_Y + yOff, BAR_TEXT_W, LABEL_H);
        setBounds(_heatTrack[i],     barX - BAR_PAD,        BAR_Y   + yOff, BAR_W,      BAR_H);
        setBounds(_heatFill[i],      barX - BAR_PAD,        BAR_Y   + yOff, 0,          BAR_H);
        setBounds(_heatPctLabels[i], barX - PCT_X_SHIFT,    BAR_Y   + yOff, BAR_TEXT_W, BAR_H);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Object findItemByOid(Object po, long oid) {
        try {
            for (Object it : Mappings.getPlayerItems(po)) {
                if (Mappings.isLevelItem(it) && Mappings.getItemOid(it) == oid) return it;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // Called the instant Narya delivers an EntryUpdatedEvent for po.items.
    // Fires on the same thread as tick(), so no synchronization needed.
    private static void noteItemUpdated(Object item) {
        try {
            long updOid = Mappings.getItemOid(item);
            float newCur = 0f;
            try { newCur = Mappings.getHeatProgress(item); } catch (Exception ignored2) {}
            for (int i = 0; i < N; i++) {
                if (_itemOids[i] > 0 && _itemOids[i] == updOid) {
                    _postDistribution[i] = true;
                }
            }
        } catch (Exception ignored) {}
    }

    private static void ensureItemListener(Object po) {
        if (_registeredPoRef == po) return;
        _registeredPoRef = po;
        try {
            Class<?> sIface = Class.forName("com.threerings.presents.dobj.s");
            Class<?> dIface = Class.forName("com.threerings.presents.dobj.d");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                HeatHudPanel.class.getClassLoader(),
                new Class[]{ sIface },
                (proxy, method, args) -> {
                    if ("a".equals(method.getName()) && args != null && args.length == 1
                            && args[0] != null
                            && "EntryUpdatedEvent".equals(args[0].getClass().getSimpleName())) {
                        try {
                            String fieldName = (String) args[0].getClass()
                                .getMethod("getName").invoke(args[0]);
                            if ("items".equals(fieldName)) {
                                Object entry = args[0].getClass().getMethod("xV").invoke(args[0]);
                                if (entry != null) noteItemUpdated(entry);
                            }
                        } catch (Exception ignored) {}
                    }
                    return null;
                }
            );
            po.getClass().getMethod("a", dIface).invoke(po, listener);
        } catch (Exception e) {
            ForgeTracker.debug("ensureItemListener ERROR: " + e);
        }
    }

    private static Object makeBg(float r, float g, float b, float a) throws Exception {
        return Class.forName(MappingsNames.GUI_COLOR_BG_CLASS)
            .getConstructor(Color4f.class)
            .newInstance(new Color4f(r, g, b, a));
    }

    private static void callSetIcon(Object label, Object icon) {
        try {
            for (java.lang.reflect.Method m : label.getClass().getMethods()) {
                if ("setIcon".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.invoke(label, icon);
                    return;
                }
            }
        } catch (Exception e) { ForgeTracker.debug("setIcon ERR: " + e); }
    }

    // Shows/hides a HUD window via Component.setVisible (public on the gui base
    // class o). No-op if the window isn't built yet.
    private static void callSetVisible(Object comp, boolean visible) {
        if (comp == null) return;
        try {
            comp.getClass().getMethod("setVisible", boolean.class).invoke(comp, visible);
        } catch (Exception e) { ForgeTracker.debug("setVisible ERR: " + e); }
    }

    private static void callSetBackground(Object comp, Object bg) {
        try {
            for (java.lang.reflect.Method m : comp.getClass().getMethods()) {
                if ("setBackground".equals(m.getName()) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == int.class) {
                    m.invoke(comp, 0, bg);
                    return;
                }
            }
        } catch (Exception e) { ForgeTracker.debug("setBackground ERR: " + e); }
    }

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
        } catch (Exception e) { ForgeTracker.debug("addChild ERR: " + e); }
    }

    private static void setBounds(Object comp, int x, int y, int w, int h) {
        try {
            comp.getClass()
                .getMethod("setBounds", int.class, int.class, int.class, int.class)
                .invoke(comp, x, y, w, h);
        } catch (Exception ignored) {}
    }
}

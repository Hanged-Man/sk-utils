package com.threerings.opengl.gui;

import java.util.prefs.Preferences;

/**
 * The player's LIVE control scheme, read from the game's own settings so the mod
 * never assumes a key layout.
 *
 * <p>projectx stores every binding in Java Preferences under the {@code projectx}
 * node (on Windows: {@code HKCU\Software\JavaSoft\Prefs\projectx}), one entry per
 * action, holding up to three alternatives:
 *
 * <pre>
 *   move_north.keyz = 87, -1, -1          defend.keyz = 88, -1, -1
 *   action.keyz     = 90, 513, -1         dodge.keyz  = 268435544, -1, -1
 *   modifier_1.keyz = 340, -1, -1         sprite_action_1.keyz = 49, -1, -1
 * </pre>
 *
 * <p>Slot encoding (verified against a live CUSTOM scheme):
 * <ul>
 * <li>{@code -1} — unbound.
 * <li>{@code 0..511} — a GLFW key code (87=W, 88=X, 90=Z).
 * <li>{@code 512+n} — mouse button n (512=LMB, 513=RMB, 514=MMB; 515/516 are the
 * wheel, which the mod cannot synthesise, so those slots are ignored).
 * <li>bit {@code 0x10000000} — the binding also requires {@code modifier_1} held.
 * {@code dodge = 0x10000000|88} is exactly the Shift+X the mod used to hardcode.
 * </ul>
 *
 * <p>{@link #load} decodes the actions the mod actually synthesises or watches and
 * publishes them into {@link SocketInputState}'s {@code bind*} fields, which the
 * Patcher-injected input code reads (fields must live on SIS — that class is the
 * stub template). Every value falls back to the previously hardcoded default, so a
 * missing/unreadable node leaves behavior exactly as it was.
 *
 * <p>Two shapes are published per action: a flat DISPATCH target (one key or one
 * mouse button — what the mod presses) and a SLOTS array (every alternative — what
 * the mod watches for on the human-driven main, so either binding registers).
 */
public final class KeyBinds {

    private static final int MOUSE_BASE = 512;      // 512+n == mouse button n
    private static final int MOD_FLAG = 0x10000000; // "modifier_1 must be held too"
    private static final int MAX_MOUSE_BUTTON = 2;  // 0/1/2 = L/R/M; 3+ is the wheel (not synthesisable)
    private static final String NODE = "projectx";

    private KeyBinds() {
    }

    /**
     * Reads the game's bindings and republishes them on SocketInputState. Safe to
     * call repeatedly (the campaign re-reads on every Ctrl+R, so a rebind mid-session
     * is picked up without a restart). Never throws: any failure leaves the previous
     * values — i.e. the hardcoded defaults — in place.
     */
    public static synchronized void load() {
        Preferences p = null;
        try {
            p = Preferences.userRoot().node(NODE);
        } catch (Throwable t) {
            SocketInputState.debugFile("[keybinds] prefs unavailable (" + t + ") — keeping defaults");
            return;
        }

        // Movement: keys only (the mod drives these by synthesising key holds).
        SocketInputState.bindMoveN = firstKey(p, "move_north", 87);
        SocketInputState.bindMoveS = firstKey(p, "move_south", 83);
        SocketInputState.bindMoveW = firstKey(p, "move_west", 65);
        SocketInputState.bindMoveE = firstKey(p, "move_east", 68);
        SocketInputState.bindModifier1 = firstKey(p, "modifier_1", 340);

        int[] defend = slots(p, "defend", new int[] { 88 });
        int[] dodge = slots(p, "dodge", new int[] { MOD_FLAG | 88 });
        int[] action = slots(p, "action", new int[] { MOUSE_BASE + 1 });

        SocketInputState.bindDefendSlots = defend;
        SocketInputState.bindDodgeSlots = dodge;
        SocketInputState.bindActionSlots = action;
        SocketInputState.bindSprite1Slots = slots(p, "sprite_action_1", new int[] { 49 });
        SocketInputState.bindSprite2Slots = slots(p, "sprite_action_2", new int[] { 50 });
        SocketInputState.bindSprite3Slots = slots(p, "sprite_action_3", new int[] { 51 });

        // DEFEND / DODGE prefer a key binding: both are held for long stretches and
        // a key hold can't fight the cursor work the aim system does.
        SocketInputState.bindDefendKey = preferKey(defend);
        SocketInputState.bindDefendMouse = (SocketInputState.bindDefendKey >= 0) ? -1 : preferMouse(defend);
        SocketInputState.bindDodgeKey = preferKey(dodge);
        SocketInputState.bindDodgeMouse = (SocketInputState.bindDodgeKey >= 0) ? -1 : preferMouse(dodge);
        SocketInputState.bindDodgeMod = needsMod(dodge, SocketInputState.bindDodgeKey, SocketInputState.bindDodgeMouse);

        // ACTION prefers the MOUSE: every attack the mod synthesises is aimed by
        // parking the cursor first, so a click carries the aim for free. Falling
        // back to a key still works — the cursor is placed either way.
        SocketInputState.bindActionMouse = preferMouse(action);
        SocketInputState.bindActionKey = (SocketInputState.bindActionMouse >= 0) ? -1 : preferKey(action);

        // A dodge on the wheel (or otherwise unsynthesisable) leaves both targets
        // unset; fall back to modifier+defend, the classic scheme, so the bots keep
        // dodging instead of silently doing nothing.
        if (SocketInputState.bindDodgeKey < 0 && SocketInputState.bindDodgeMouse < 0) {
            SocketInputState.bindDodgeKey = SocketInputState.bindDefendKey;
            SocketInputState.bindDodgeMouse = SocketInputState.bindDefendMouse;
            SocketInputState.bindDodgeMod = true;
            SocketInputState.debugFile("[keybinds] dodge unusable — falling back to modifier_1 + defend");
        }
        if (SocketInputState.bindActionMouse < 0 && SocketInputState.bindActionKey < 0) {
            SocketInputState.bindActionMouse = 1; // nothing usable bound: keep the old RMB behavior
            SocketInputState.debugFile("[keybinds] action unusable — falling back to mouse button 1");
        }

        SocketInputState.debugFile("[keybinds] " + describe());
    }

    /** One-line summary of the resolved scheme, for debug.log. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("move=").append(name(SocketInputState.bindMoveN)).append(name(SocketInputState.bindMoveW))
                .append(name(SocketInputState.bindMoveS)).append(name(SocketInputState.bindMoveE));
        sb.append(" defend=").append(target(SocketInputState.bindDefendKey, SocketInputState.bindDefendMouse));
        sb.append(" dodge=").append(SocketInputState.bindDodgeMod ? (name(SocketInputState.bindModifier1) + "+") : "")
                .append(target(SocketInputState.bindDodgeKey, SocketInputState.bindDodgeMouse));
        sb.append(" action=").append(target(SocketInputState.bindActionKey, SocketInputState.bindActionMouse));
        sb.append(" sprites=").append(list(SocketInputState.bindSprite1Slots))
                .append('/').append(list(SocketInputState.bindSprite2Slots))
                .append('/').append(list(SocketInputState.bindSprite3Slots));
        return sb.toString();
    }

    // ── decoding ─────────────────────────────────────────────────────────────

    /** Every usable slot of {@code <action>.keyz}, raw (mod flag intact), or the fallback. */
    private static int[] slots(Preferences p, String action, int[] dflt) {
        String v;
        try {
            v = p.get(action + ".keyz", null);
        } catch (Throwable t) {
            return dflt;
        }
        return parseSlots(v, dflt);
    }

    /**
     * Decodes one {@code .keyz} value ("90, 513, -1") into its usable slots, keeping
     * the modifier bit. Unbound entries, wheel bindings and junk are dropped; if
     * nothing usable survives the fallback is returned, so the mod keeps whatever
     * behavior it had. Package-private: exercised directly by the binding probe.
     */
    static int[] parseSlots(String v, int[] dflt) {
        if (v == null)
            return dflt;
        String[] tok = v.split(",");
        int[] out = new int[tok.length];
        int n = 0;
        for (int i = 0; i < tok.length; i++) {
            int raw;
            try {
                raw = Integer.parseInt(tok[i].trim());
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (raw < 0)
                continue;
            int code = raw & ~MOD_FLAG;
            if (code >= MOUSE_BASE && code - MOUSE_BASE > MAX_MOUSE_BUTTON)
                continue; // wheel — can't be synthesised or polled as a button
            out[n++] = raw;
        }
        if (n == 0)
            return dflt; // action unbound entirely: keep doing what the mod always did
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** The first plain-key slot of an action, or {@code dflt} if it has none. */
    private static int firstKey(Preferences p, String action, int dflt) {
        int k = preferKey(slots(p, action, new int[] { dflt }));
        return (k >= 0) ? k : dflt;
    }

    /** First slot that is a keyboard key (mod flag stripped), or -1. */
    static int preferKey(int[] sl) {
        for (int i = 0; i < sl.length; i++) {
            int code = sl[i] & ~MOD_FLAG;
            if (code < MOUSE_BASE)
                return code;
        }
        return -1;
    }

    /** First slot that is a mouse button, as a button index, or -1. */
    static int preferMouse(int[] sl) {
        for (int i = 0; i < sl.length; i++) {
            int code = sl[i] & ~MOD_FLAG;
            if (code >= MOUSE_BASE)
                return code - MOUSE_BASE;
        }
        return -1;
    }

    /** Whether the slot chosen as the dispatch target carries the modifier_1 flag. */
    static boolean needsMod(int[] sl, int chosenKey, int chosenMouse) {
        for (int i = 0; i < sl.length; i++) {
            int code = sl[i] & ~MOD_FLAG;
            boolean mod = (sl[i] & MOD_FLAG) != 0;
            if (code < MOUSE_BASE) {
                if (code == chosenKey)
                    return mod;
            } else if (code - MOUSE_BASE == chosenMouse) {
                return mod;
            }
        }
        return false;
    }

    // ── pretty-printing ──────────────────────────────────────────────────────

    private static String target(int key, int mouse) {
        if (mouse >= 0)
            return "mouse" + mouse;
        return name(key);
    }

    private static String list(int[] sl) {
        if (sl == null || sl.length == 0)
            return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sl.length; i++) {
            if (i > 0)
                sb.append('|');
            int code = sl[i] & ~MOD_FLAG;
            if ((sl[i] & MOD_FLAG) != 0)
                sb.append("mod+");
            sb.append(code >= MOUSE_BASE ? ("mouse" + (code - MOUSE_BASE)) : name(code));
        }
        return sb.toString();
    }

    /** Human-readable name for a GLFW key code (letters/digits spelled out). */
    private static String name(int code) {
        if (code < 0)
            return "-";
        if (code >= 65 && code <= 90)
            return String.valueOf((char) code);
        if (code >= 48 && code <= 57)
            return String.valueOf((char) code);
        switch (code) {
            case 32:
                return "SPACE";
            case 340:
                return "LSHIFT";
            case 341:
                return "LCTRL";
            case 342:
                return "LALT";
            case 344:
                return "RSHIFT";
            case 345:
                return "RCTRL";
            default:
                return "key" + code;
        }
    }
}

package com.threerings.opengl.gui;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SocketInputState {
    static volatile boolean debug = false; // debug.log writes; OFF by default (bots run for hours). RUNTIME-toggled by multibox '=' (DEBUGMODE broadcast) — no rebuild needed. Package-visible: ForgeTracker/MissionStats/PvpAutoQueuer gate on it too.
    static final String DIR = System.getProperty("user.home") + "/.sk-utils"; // package-private: MissionStats uses it

    static final Set<String> DOWN = ConcurrentHashMap.newKeySet();
    private static volatile boolean started;

    // ── Control scheme (see KeyBinds.java) ───────────────────────────────────
    // Every key/button the mod synthesizes or watches, resolved from the GAME's
    // own bindings at startup instead of being hardcoded, so a rebind in the
    // options screen doesn't break the bots. These fields live HERE (not on
    // KeyBinds) because the Patcher-injected input code reads them, and injected
    // code may only reference stub-declared members of this class.
    // Defaults = the scheme the mod hardcoded before KeyBinds existed, so an
    // unreadable prefs node changes nothing.
    // DISPATCH targets — what the mod presses. A key code, or a mouse button
    // index when the action is bound to the mouse (the other is then -1).
    public static volatile int bindMoveN = 87, bindMoveS = 83, bindMoveW = 65, bindMoveE = 68;
    public static volatile int bindModifier1 = 340;                    // "shift" of the game's scheme
    public static volatile int bindDefendKey = 88, bindDefendMouse = -1;  // shield
    public static volatile int bindDodgeKey = 88, bindDodgeMouse = -1;    // dash
    public static volatile boolean bindDodgeMod = true;                // dash also needs modifier_1
    public static volatile int bindActionKey = -1, bindActionMouse = 1;   // attack / fire
    // WATCH slots — every alternative the human main might press (mod flag intact,
    // 512+n = mouse). Read by the injected poll to mirror the main to the alts.
    public static volatile int[] bindDefendSlots = { 88 };
    public static volatile int[] bindDodgeSlots = { 0x10000000 | 88 };
    public static volatile int[] bindActionSlots = { 513 };
    public static volatile int[] bindSprite1Slots = { 49 };
    public static volatile int[] bindSprite2Slots = { 50 };
    public static volatile int[] bindSprite3Slots = { 51 };
    // Cached framebuffer centre, refreshed by the injected __skRefreshCenter():
    // where a MOUSE-bound shield/dash press lands (a click needs coordinates; a
    // key hold doesn't). Aimed presses pass their own point instead.
    public static volatile int screenCx = 0, screenCy = 0;
    public static volatile long screenCenterAt = 0L;
    public static volatile Object dungeonClient = null;
    public static Object currentForgeBtn = null;
    public static volatile boolean isAutoForging = false;
    public static volatile boolean isAutoFollowing = false;
    public static volatile boolean isCtrlDown = false;

    // Main's broadcast position — written by main's TudeyController tick, read by
    // alts.
    public static volatile float mainBroadcastX = 0f;
    public static volatile float mainBroadcastY = 0f;
    public static volatile long mainBroadcastTime = 0L;

    // Breadcrumb-follow (routine): the main drops a trail of the actual positions
    // it walks (which the A* pather made obstacle-avoiding) and the alts retrace
    // that trail instead of naive straight-line auto-follow. Independent of Ctrl+F
    // (isAutoFollowing), which is left fully intact for manual play.
    public static volatile boolean isBreadcrumbFollow = false; // alt: follow the trail
    private static final java.util.concurrent.ConcurrentLinkedQueue<float[]> crumbTrail =
            new java.util.concurrent.ConcurrentLinkedQueue<float[]>(); // alt: queued path points (FIFO)
    private static volatile float lastCrumbX = 0f, lastCrumbY = 0f; // main: last dropped crumb
    private static volatile boolean crumbHasLast = false;           // main: whether a crumb was dropped yet
    private static final float BREADCRUMB_SPACING_SQ = 1.0f; // drop one crumb per ~1 tile of main travel
    private static final float CRUMB_ARRIVE_SQ = 0.64f;      // alt pops a crumb once within ~0.8 tile
    private static final int MAX_CRUMBS = 600;               // trail cap (alt hopelessly behind)

    private static volatile long dashUntil = 0L;
    private static volatile long lastDashTime = 0L;
    private static final long DASH_COOLDOWN_MS = 10000L;
    private static final long DASH_HOLD_MS = 150L;

    private static volatile long consumableCooldownUntil = 0L;
    private static volatile long consumablePendingUntil = 0L; // time when 1s pre-use delay expires
    private static final long CONSUMABLE_COOLDOWN_MS = 2000L;
    private static final long CONSUMABLE_DELAY_MS = 1000L;

    // Barrier hotkey (G) — set on press-edge by the main's patched
    // InputState.poll (which also broadcasts BARRIER to alts) or by the
    // listener thread on a BARRIER message. Consumed by tryUseBarrier, which
    // the patched tick invokes only while the flag is set.
    public static volatile boolean pendingBarrierUse = false;
    private static volatile long barrierCooldownUntil = 0L;
    private static final long BARRIER_COOLDOWN_MS = 1000L;

    // Vial hotkey (H) — same pattern as the barrier hotkey: set on press-edge
    // by the main's patched InputState.poll (which also broadcasts VIAL to
    // alts) or by the listener thread on a VIAL message. Consumed by
    // tryUseVial, which the patched tick invokes only while the flag is set.
    public static volatile boolean pendingVialUse = false;
    private static volatile long vialCooldownUntil = 0L;
    private static final long VIAL_COOLDOWN_MS = 1000L;

    // Sprite ability hotkeys (1/2/3) — slot set on press-edge by the main's
    // patched InputState.poll (which also broadcasts SPRITE to alts) or by
    // the listener thread on a SPRITE message. Consumed by tryUseSprite,
    // which the patched tick invokes only while a press or release is
    // pending. aq(key, slot) queues the press; dU(key) releases it after a
    // short hold, mirroring the game's own SPRITE_ACTION_1..3 key listeners.
    public static volatile int pendingSpriteSlot = -1;
    public static volatile int spriteReleaseKey = -1;
    private static volatile long spriteReleaseAtMs = 0L;
    private static final long SPRITE_HOLD_MS = 150L;
    // Synthetic key codes passed to aq()/dU() — the key is only a dedup
    // identity in the controller's action queue, so any value that cannot
    // collide with a real keyboard key code works.
    private static final int SPRITE_KEY_BASE = 0x534B00;

    // AUTO-FIRE REQUEST (combat steps). The 19s timer no longer presses the sprite
    // itself: it RAISES A REQUEST, and tickCombatBot fires it on the first pass where
    // this client actually has a target acquired and its cursor parked on it. Firing
    // straight off the timer aimed the ability at wherever the knight happened to face
    // — visible at the START of combat, before the bot had acquired anything at all.
    // Each client resolves its OWN closest enemy, exactly like weapon choice and normal
    // firing, so four knights can fire at four different targets.
    public static volatile boolean spriteAimedRequest = false;
    private static volatile long spriteAimedSince = 0L;
    private static final long SPRITE_AIM_SETTLE_MS = 250L;  // let the poll park the cursor on the target first
    private static final long SPRITE_AIM_GIVEUP_MS = 15000L; // no target this long => drop it, don't fire stale
    // Auto-fire press BURST (user-directed 2026-08-05): a sprite press that lands mid
    // attack-animation is EATEN by the game, so the aimed auto-fire re-presses on a
    // cadence until the burst is spent. Re-pressing is free — once a cast actually
    // takes, the ability's own cooldown no-ops the remainder of the burst. The attack
    // cycle is deliberately NOT paused around the press (user: sprites aren't worth
    // stalling attacks for); the burst just outlasts the animation windows instead.
    // Manual sprite hotkeys arm no burst — they stay single instant presses.
    private static volatile int spriteBurstLeft = 0;      // presses remaining in the current burst
    private static volatile long spriteNextPressAt = 0L;  // press-to-press pacing
    private static final int SPRITE_BURST_PRESSES = 8;    // total presses ≈ 1.75s of coverage
    private static final long SPRITE_BURST_INTERVAL_MS = 250L; // user-set re-press cadence

    // Auto-queue Blast Network (multibox Ctrl+Q). The MACHINERY lives in
    // PvpAutoQueuer.java; these three fields stay HERE because the injected
    // code reads them directly (Patcher stub fields): the toggle (set by the
    // PVPQUEUE broadcast on every account) and the anti-idle key + deadline
    // (armed by PvpAutoQueuer.tickPvpAntiIdle on the match-start edge; the
    // patched InputState.poll dispatches the held key). Per-process fields,
    // so each client has its own.
    public static volatile boolean isAutoPvpQueue = false;
    public static volatile int antiIdleKeyCode = -1;
    public static volatile long antiIdleUntil = 0L;

    // Combat bot (multibox Ctrl+B, all accounts): switch to weapon slot 2 and
    // fire at the nearest living enemy while enabled. tickCombatBot (game tick)
    // enumerates Monster actors from the tudey view, picks the closest, selects
    // weapon 2 via the controller's dW(int), and sets botFireAngle/botFiring;
    // the patched InputState.poll dispatches aimed weapon fire.
    public static volatile boolean isCombatBot = false;
    public static volatile boolean botFiring = false;   // tick: cleared to run a firing cycle now
    public static volatile float botFireAngle = 0f;     // world→screen aim angle
    public static volatile boolean botFireHeld = false; // poll has the fire button pressed
    public static volatile long botTapReleaseAt = 0L;   // when to release the current press
    public static volatile long botNextTapAt = 0L;      // when the current wait/rest ends
    // Firing-cycle state machine (poll-owned). A whole cycle — 3 taps 100ms apart
    // + 150ms reload for the gun (weapon 2), or 2 taps 250ms apart + 150ms reload
    // for weapon 1 — runs to completion, and the tick is forbidden from changing
    // weapon/mode while botCycleActive is true, so a swap never lands mid-animation.
    public static volatile boolean botCycleActive = false; // a firing cycle is in progress
    public static volatile int botCyclePhase = 0;          // 0 idle, 1 pressed, 2 waiting
    public static volatile int botTapIndex = 0;            // gun taps fired so far this cycle
    public static volatile boolean botCycleWeapon2 = false; // slot latched at this cycle's start (true = weapon 1; cadence in botCycleBlaster)
    // Shield dodge: hold shield while an enemy bullet is within 2 tiles.
    public static volatile boolean botShieldHold = false; // a dangerous bullet is near
    public static volatile boolean botShieldHeld = false; // poll has X held for shield
    // Scene-scan (multibox Ctrl+D): one-shot dump of the loaded scene to debug.log.
    public static volatile boolean sceneScanPending = false;
    // Position dump (multibox Ctrl+J): one-shot dump of the pawn's map coords.
    public static volatile boolean dumpPosPending = false;
    // Loot mode (multibox Ctrl+K, MAIN only): after clearing waves, sweep nearby
    // loot. Plans a shortest CLOSED route (start -> collection waypoints -> start)
    // through target pickups within 10 tiles, exploiting the 2-tile magnet so one
    // waypoint can collect a whole cluster. Alts trail via auto-follow (assumed on).
    public static volatile boolean isLootMode = false;
    private static volatile boolean lootPlanned = false;   // route computed for the current run
    private static volatile boolean lootDoneLogged = false;
    private static float[] lootRouteX = null; // waypoint xs in visit order; last entry = start (return)
    private static float[] lootRouteY = null;
    private static float[] lootRouteArriveSq = null; // per-waypoint arrival radius^2 (tight for step-on drops)
    private static int lootRouteIdx = 0;
    private static long lootWaypointDeadline = 0L; // skip a waypoint we can't reach (no obstacle avoidance)
    private static float lootStartX = 0f, lootStartY = 0f;
    private static final long LOOT_WAYPOINT_TIMEOUT_MS = 4000L; // give up on an unreachable waypoint after this
    private static final float LOOT_SCAN_RADIUS_SQ = 100f; // (10 tiles)^2, measured from the start
    private static final float LOOT_MAGNET_TILES = 2.0f;  // pickups collect within this of the player
    private static final float LOOT_COVER_RADIUS = 1.3f;  // a waypoint "collects" pickups within this
    private static final float LOOT_ARRIVE_SQ = 0.36f;      // (0.6 tile)^2 magnet-cluster arrival
    private static final float LOOT_STEP_ARRIVE_SQ = 0.25f; // (0.5 tile)^2 step-on drop arrival (no magnet — land on it)
    private static final int LOOT_MAX_EXACT = 15;         // exact Held-Karp TSP up to this many waypoints
    // detour filter: a destination whose REAL A* walking path is
    // more than LOOT_DETOUR_RATIO x its straight-line distance from the sweep
    // origin is skipped at plan time. SLACK is an
    // absolute grace (tiles) so grid quantisation on very short paths can never
    // trip the ratio.
    private static final float LOOT_DETOUR_RATIO = 2.0f;
    private static final float LOOT_DETOUR_SLACK = 1.0f;
    // 1 tile ≈ 1.0 world unit in tudey; compared squared. Tunable.
    private static final float BULLET_SHIELD_RADIUS_SQ = 4.0f; // (2 tiles)^2
    private static java.lang.reflect.Method cachedBulletHitMethod = null; // Bullet.e(Actor)
    // Weapon choice: the CLOSEST enemy's family decides the slot each cycle —
    // family in gunFamilies → weapon 2, anything else (unknowns included) →
    // weapon 1. Both the family map and each slot's FIRING CADENCE are
    // per-mission DATA (loadMissionWeaponConfig), from optional mission_data.txt
    // lines:
    //
    //     1 | <Autogun|Blaster> | <families weapon 1 handles>
    //     2 | <Autogun|Blaster> | <families weapon 2 handles>
    //
    // The cadence token is optional (absent → slot 1 Autogun, slot 2 Blaster):
    // Autogun = 2 taps 250ms apart, Blaster = 3 taps 100ms apart (both 40ms
    // holds + a 150ms reload; timings inline in the Patcher poll block). The
    // "1" line's families are explicit weapon-1 assignments — same as the
    // default for unlisted families, but a family on BOTH lines logs a warning
    // and weapon 1 wins. NO weapon line at all = this legacy default trio, kept
    // for missions without one and for manual Ctrl+B combat outside a routine.
    // All of it is broadcast to the alts as GUNFAMILIES so every client's
    // combat bot switches and fires identically.
    // NOTE the "Weapon2" in the bot field names below is HISTORICAL: it means the
    // PRIMARY (single-tap) weapon, which sat in-game slot 2 until the 2026-08-27
    // slot shift moved everything down — primary is now in-game weapon 1, the
    // sidearm/gun in-game weapon 2. The names stay because they are Patcher-pinned.
    private static volatile String[] gunFamilies = { "construct", "slime", "undead" };
    public static volatile boolean botWeapon2Mode = false; // committed slot; poll latches it at cycle start (true = PRIMARY weapon)
    // Per-slot cadence config (true = Blaster 3-tap, false = Autogun 2-tap),
    // and the cadence latched for the CURRENT firing cycle — set alongside
    // botCycleWeapon2 in botSelectWeaponForCycle, read by the poll's tap logic.
    private static volatile boolean slot1CadenceBlaster = false; // weapon 1 default: Autogun
    private static volatile boolean slot2CadenceBlaster = true;  // weapon 2 default: Blaster
    public static volatile boolean botCycleBlaster = false; // Patcher-pinned (poll reads it)
    private static volatile int botLastWeaponSlot = -1; // slot we believe is equipped; -1 = unknown
    private static volatile long botNextTickAt = 0L;
    private static volatile long botWeaponSwitchAt = 0L;
    // Fire is held off until this time after a weapon switch, so the new weapon
    // is actually equipped before the first press of the new cycle.
    private static volatile long botFireBlockedUntil = 0L;
    private static final long BOT_TICK_INTERVAL_MS = 100L;
    private static final long BOT_WEAPON_SWITCH_MS = 400L;  // periodic same-slot re-assert
    private static final long BOT_WEAPON_SETTLE_MS = 300L;  // fire hold after a switch
    // Cycle timings live inline in the Patcher poll block, keyed on the latched
    // botCycleBlaster: Blaster cadence = 3 taps of 40ms held / 100ms apart,
    // Autogun cadence = 2 taps of 40ms held / 250ms apart, both then a 150ms
    // reload. Kept there because the poll (a separate class) cannot read
    // SocketInputState's private constants.
    private static final int PRIMARY_WEAPON_SLOT = 0; // in-game weapon 1 (0-indexed; the single-tap weapon — was slot 2 pre-2026-08-27)
    private static final int GUN_WEAPON_SLOT = 1;     // in-game weapon 2 (0-indexed; the SIDEARM/gun — was slot 3)
    // Vertical foreshortening of the world→screen aim: the gameplay camera sits
    // at 45° elevation (scene_global.dat), so world-depth (Y) compresses to
    // sin(45°) on screen while world-X is unchanged. Scale dy before atan2.
    // dW(int) selects the equipped weapon; obfuscated, version-specific like aq/dU.
    private static java.lang.reflect.Method cachedWeaponSelectMethod = null;
    private static Class<?> cachedBotCtrlClass = null;

    // Main input state — written by InputState.poll() on the main, read by the
    // tick.
    public static volatile boolean mainMouseLeft = false;
    public static volatile boolean prevMainMouseLeft = false;
    public static volatile boolean mainMouseRight = false;
    public static volatile boolean prevMainMouseRight = false;
    public static volatile boolean prevMainXShield = false;
    public static volatile boolean prevMainDash = false;
    public static volatile boolean prevMainBarrier = false;
    public static volatile boolean prevMainVial = false;
    public static volatile boolean prevMainSprite1 = false;
    public static volatile boolean prevMainSprite2 = false;
    public static volatile boolean prevMainSprite3 = false;

    public static volatile float mainCursorAngle = 0f;

    // Alt pending actions — set by listener thread, consumed by InputState.poll()
    // on game thread.
    public static volatile long pendingFireAtMs = -1L;
    public static volatile long pendingFireReleaseAtMs = -1L;
    public static volatile float pendingFireAngle = 0f;
    public static volatile int pendingFireX = 0;
    public static volatile int pendingFireY = 0;
    public static volatile boolean pendingShieldPress = false;
    public static volatile boolean pendingShieldRelease = false;
    public static volatile boolean pendingWeaponPress = false;
    public static volatile boolean pendingWeaponRelease = false;
    public static volatile boolean pendingWeaponAimDirty = false;
    public static volatile boolean pendingDashPress = false;
    /** The main's aim at the moment it dashed; the alt faces this first (see the DASH handler). */
    public static volatile float pendingDashAngle = 0f;
    public static volatile boolean pendingDashHasAngle = false;
    public static volatile boolean pendingDashRelease = false;
    public static volatile boolean dashActive = false;
    public static volatile boolean fireActive = false;
    public static volatile boolean shieldActive = false;
    public static volatile long shieldRepressAt = 0L; // last alt shield-key edge refresh (re-raises a shield the game drops on death)
    public static volatile float altFollowDx = 0f;
    public static volatile float altFollowDy = 0f;

    // ChatDirector reference — captured once when first chat event fires.
    public static volatile Object chatDirector = null;

    // Context captured each tick for all accounts — lets background threads call
    // Mappings.
    public static volatile Object _cachedCtx = null;

    // HUD sync button — X position written by HeatHudPanel.onLayout, read by
    // HeatHudWindow.hitTest.
    public static volatile int hudBtnX = -1;

    // Heat-status HUD visibility — toggled by the multibox ` hotkey (HEATHUD
    // message). HeatHudPanel.tick applies it via setVisible on the HUD windows.
    public static volatile boolean showHeatHud = true;

    // Own listening port — stored when successfully bound.
    public static volatile int _ownListeningPort = -1;

    // Loopback control plane: main listens on the base port, alts bind the first
    // free port in [ALT_PORT_FIRST, ALT_PORT_LAST]. All derived from config
    // (udp_base_port) so a distributed install can dodge a local port conflict
    // without a rebuild; multibox.py reads the same file so both sides agree.
    private static final int MAIN_REPLY_PORT = SKConfig.BASE_PORT;
    private static final int ALT_PORT_FIRST = SKConfig.BASE_PORT + 1;
    private static final int ALT_PORT_LAST = SKConfig.BASE_PORT + SKConfig.MAX_ALTS;

    /**
     * Displays a message in the local chat window using one of the ChatDirector
     * display methods.
     * type values and their typical colors:
     * "feedback" — gray (default)
     * "info" — gray/white
     * "attention" — orange/yellow
     * "error" — red
     */
    public static void displayChat(String msg, String type) {
        Object dir = chatDirector;
        if (dir == null)
            return;
        try {
            String method;
            switch (type.toLowerCase(Locale.ROOT)) {
                case "info":
                    method = "displayInfo";
                    break;
                case "attention":
                    method = "displayAttention";
                    break;
                case "error":
                    method = "displayError";
                    break;
                default:
                    method = "displayFeedback";
                    break;
            }
            dir.getClass().getMethod(method, String.class, String.class)
                    .invoke(dir, null, msg);
        } catch (Exception e) {
        }
    }

    public static void displayChat(String msg) {
        displayChat(msg, "feedback");
    }

    /**
     * Writes useful debug information about socket binding and packet
     * sending/receiving to ~\.sk-utils\debug.log.
     *
     * @param msg The debug message to be written to the log.
     */
    static void debugFile(String msg) { // package-private: MissionStats uses it
        if (!debug)
            return; // silent unless debug logging is enabled — bots run for hours, keep debug.log from ballooning
        writeLogAlways(msg);
    }

    /**
     * Writes to debug.log UNCONDITIONALLY (ignores the `debug` gate) — for on-demand recon output
     * the user wants even in the shipped debug=false build (Ctrl+L pos dump; Ctrl+D scene scan).
     */
    static void writeLogAlways(String msg) { // package-private: AuctionBot reports through it
        try {
            new File(DIR).mkdirs();
            FileWriter fw = new FileWriter(DIR + "/debug.log", true);
            fw.write(new java.util.Date() + "  " + msg + "\n");
            fw.close();
        } catch (Exception e) {
        }
    }

    // ── Window-stack diagnostic ────────────────────────────────────────────────
    // After the Snarbolax (terminal) floor completes, a mission-complete/reward
    // screen pops up as a GUI window that must be dismissed before we return to
    // town. We don't yet know its class, so — armed at that routineFinish — dump
    // the whole live window tree (P.getWindow(i) → aD/Container → o children, with
    // any text/action) to debug.log across the post-completion window, so the
    // panel + its dismiss button can be read off and wired into the restart cycle.
    private static boolean windowDiag = false; // flip true to re-capture the post-completion GUI window tree
    public static volatile boolean windowDumpArmed = false;
    private static long windowDumpUntil = 0L;
    private static long windowDumpNextAt = 0L;
    private static int windowDumpSeq = 0;
    private static String windowDumpLastSig = null; // window-class set last time; trees re-dumped only on change

    /** Arms ~25s of periodic window-tree dumps (called at terminal-floor completion). */
    private static void armWindowDump() {
        long now = System.currentTimeMillis();
        windowDumpUntil = now + 25000L;
        windowDumpNextAt = now; // first dump immediately
        windowDumpSeq = 0;
        windowDumpLastSig = null;
        windowDumpArmed = true;
        debugFile("[windump] armed 25s — capturing the post-completion reward screen");
    }

    /** MAIN-only tudey-tick driver: dumps the window tree every ~2s while armed. */
    public static void tickWindowDump(Object ctx) {
        try {
            if (!windowDumpArmed)
                return;
            long now = System.currentTimeMillis();
            if (now >= windowDumpUntil) {
                windowDumpArmed = false;
                debugFile("[windump] disarmed");
                return;
            }
            if (now < windowDumpNextAt)
                return;
            windowDumpNextAt = now + 2000L;
            dumpWindows(ctx, ++windowDumpSeq);
        } catch (Exception e) {
            debugFile("[windump] tick error: " + e);
        }
    }

    /** Walks the GUI Root (P) window stack and logs each window's component tree. */
    // -- End-of-floor auto-advance ------------------------------------------
    // Every floor ends on a summary screen that dismisses itself after ~10s. That is
    // noise on a 15-minute run and a heavy tax on a 1-minute floor farmed on loop, so
    // each client presses its own ADVANCE NOW.
    //
    // The control was located by TRACING A REAL CLICK (a temporary hook on the Button's
    // fireAction, since removed) rather than by guessing: it is a gui Button labelled
    // "ADVANCE NOW", three Containers deep inside a UserInterface, in the window
    // com.threerings.projectx.dungeon.client.R -- NOT in EndOfLevelWindow, which is why
    // searching that window turned up nothing. R is obfuscated and will drift on the
    // next re-obfuscation, so the match is on the BUTTON TEXT plus doClick/getText/
    // getComponent (all real names), and the search covers EVERY root window instead of
    // assuming which one owns it.
    //
    // Runs on EVERY account: each knight has its own screen, and one client dismissing
    // its own does not move the party.
    public static volatile boolean autoAdvanceOn = false;
    private static long autoAdvanceNextAt = 0L;
    private static final long AUTO_ADVANCE_INTERVAL_MS = 400L; // retry cadence while the screen is up
    private static final String ADVANCE_LABEL = "ADVANCE";     // the label reads "ADVANCE\nNOW"

    public static void tickAutoAdvance(Object ctx) {
        if (!autoAdvanceOn || ctx == null)
            return;
        long now = System.currentTimeMillis();
        if (now < autoAdvanceNextAt)
            return;
        autoAdvanceNextAt = now + AUTO_ADVANCE_INTERVAL_MS;
        try {
            Object root = ctx.getClass().getMethod("getRoot").invoke(ctx);
            if (root == null)
                return;
            int wc = ((Integer) root.getClass().getMethod("getWindowCount").invoke(root)).intValue();
            java.lang.reflect.Method getWin = root.getClass().getMethod("getWindow", int.class);
            for (int i = 0; i < wc; i++) {
                Object w = getWin.invoke(root, new Object[] { Integer.valueOf(i) });
                if (clickAdvanceButton(w, 0)) {
                    debugFile("[advance] pressed ADVANCE NOW");
                    return;
                }
            }
        } catch (Exception e) {
        }
    }

    /**
     * Depth-first hunt for the advance control: a component that BOTH has doClick() and
     * whose text contains "ADVANCE". Requiring the label is what makes this safe -- a
     * blind "click the first button below this window" would press whatever else the
     * screen happens to show.
     */
    private static boolean clickAdvanceButton(Object comp, int depth) {
        if (comp == null || depth > 12)
            return false;
        try {
            java.lang.reflect.Method click = null;
            try {
                click = comp.getClass().getMethod("doClick");
            } catch (NoSuchMethodException ignored) {
            }
            if (click != null) {
                String txt = null;
                try {
                    Object v = comp.getClass().getMethod("getText").invoke(comp);
                    txt = (v == null) ? null : v.toString();
                } catch (Exception ignored) {
                }
                if (txt != null && txt.toUpperCase(Locale.ROOT).contains(ADVANCE_LABEL)) {
                    click.invoke(comp);
                    return true;
                }
            }
            java.lang.reflect.Method cnt;
            try {
                cnt = comp.getClass().getMethod("getComponentCount");
            } catch (NoSuchMethodException ignored) {
                return false; // not a container, nothing below it
            }
            java.lang.reflect.Method get = comp.getClass().getMethod("getComponent", int.class);
            int n = ((Integer) cnt.invoke(comp)).intValue();
            for (int i = 0; i < n; i++) {
                if (clickAdvanceButton(get.invoke(comp, new Object[] { Integer.valueOf(i) }), depth + 1))
                    return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    public static void dumpWindows(Object ctx, int seq) {
        StringBuilder sb = new StringBuilder();
        try {
            if (ctx == null) {
                debugFile("[windump#" + seq + "] ctx null");
                return;
            }
            Object root = ctx.getClass().getMethod("getRoot").invoke(ctx);
            if (root == null) {
                debugFile("[windump#" + seq + "] root null");
                return;
            }
            int wc = ((Integer) root.getClass().getMethod("getWindowCount").invoke(root)).intValue();
            java.lang.reflect.Method getWin = root.getClass().getMethod("getWindow", int.class);
            // Compact summary first (scan these lines to spot the NEW reward window).
            StringBuilder sum = new StringBuilder();
            for (int i = 0; i < wc; i++) {
                Object w = getWin.invoke(root, new Object[] { Integer.valueOf(i) });
                sum.append(i == 0 ? "" : ", ").append(w == null ? "null" : w.getClass().getName());
            }
            String sig = sum.toString();
            boolean changed = !sig.equals(windowDumpLastSig);
            windowDumpLastSig = sig;
            sb.append("[windump#").append(seq).append("] ").append(wc)
                    .append(" window(s): [").append(sig).append("]").append(changed ? "  <<< CHANGED" : "").append("\n");
            // Only walk the full component trees when the window set changed — that's
            // when the reward screen appears — so the log stays readable.
            if (changed) {
                for (int i = 0; i < wc; i++) {
                    Object w = getWin.invoke(root, new Object[] { Integer.valueOf(i) });
                    if (w == null)
                        continue;
                    sb.append("  WINDOW[").append(i).append("] ").append(w.getClass().getName());
                    appendCompText(w, sb);
                    sb.append("\n");
                    dumpComponent(w, 2, sb);
                }
            }
        } catch (Exception e) {
            sb.append("[windump#").append(seq).append("] error: ").append(e).append("\n");
        }
        debugFile(sb.toString());
    }

    /** Recursively logs a Container's children (class + any text) one line each, indented. */
    private static void dumpComponent(Object comp, int depth, StringBuilder sb) {
        if (comp == null || depth > 14)
            return;
        int cc;
        try {
            cc = ((Integer) comp.getClass().getMethod("getComponentCount").invoke(comp)).intValue();
        } catch (Exception e) {
            return; // not a container — leaf already printed by its parent
        }
        java.lang.reflect.Method getComp;
        try {
            getComp = comp.getClass().getMethod("getComponent", int.class);
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < cc; i++) {
            Object child;
            try {
                child = getComp.invoke(comp, new Object[] { Integer.valueOf(i) });
            } catch (Exception e) {
                continue;
            }
            if (child == null)
                continue;
            for (int d = 0; d < depth; d++)
                sb.append("  ");
            sb.append(child.getClass().getName());
            appendCompText(child, sb);
            sb.append("\n");
            dumpComponent(child, depth + 1, sb);
        }
    }

    /** Appends any readable text/action/tooltip a component exposes (marks the dismiss button). */
    private static void appendCompText(Object comp, StringBuilder sb) {
        String[] getters = { "getText", "getAction", "getTooltipText" };
        for (String g : getters) {
            try {
                Object v = comp.getClass().getMethod(g).invoke(comp);
                if (v instanceof String && !((String) v).isEmpty())
                    sb.append("  ").append(g.substring(3).toLowerCase(Locale.ROOT))
                            .append("=\"").append(v).append("\"");
            } catch (Exception e) {
                /* getter absent on this component */
            }
        }
    }

    // Reflection caches — filled on first use, keyed by controller class to survive
    // hot-reload.
    private static Class<?> cachedCtrlClass = null;
    private static java.lang.reflect.Method cachedUseItemMethod = null; // ItemService a(long,Object,ConfirmListener)
    private static Class<?> cachedSpriteCtrlClass = null;
    private static java.lang.reflect.Method cachedSpriteAqMethod = null; // aq(int,int) — sprite press
    private static java.lang.reflect.Method cachedSpriteDuMethod = null; // dU(int) — sprite release

    private static DatagramSocket senderSocket;

    private SocketInputState() {
    }

    public static boolean isMainAccount() {
        String role = System.getenv("SK_ROLE"); // explicit per-client override ("main"/"alt") for setups the Steam test misreads
        if (role != null) {
            if ("main".equalsIgnoreCase(role.trim())) return true;
            if ("alt".equalsIgnoreCase(role.trim())) return false;
        }
        String appId = System.getenv("SteamAppId");
        // if the game is running on Steam, it's my main
        return appId != null && !appId.trim().isEmpty();
    }

    public static synchronized void start() {
        if (started) {
            return;
        }

        started = true;

        KeyBinds.load(); // resolve the player's control scheme before any input is synthesised

        if (SKConfig.MAIN_ACCOUNT.isEmpty()) // fresh install: without it, follow/invites/PvP main-detection silently no-op
            writeLogAlways("[config] main_account is NOT SET in ~/.sk-utils/config.properties — set it to the main's knight name");

        // Every account gets a sender socket. The main uses it to broadcast to
        // alts; all accounts additionally broadcast their Krogmo Coin booster
        // status (PVPBOOST) so every client can map boosters → teams.
        try {
            senderSocket = new DatagramSocket();
        } catch (Throwable t) {
            debugFile("senderSocket failed: " + t);
        }

        if (isMainAccount()) {
            startListener(MAIN_REPLY_PORT);
            return;
        }

        String configured = System.getProperty("sk.socketInputPort");
        int port;

        if (configured != null && !configured.isBlank()) {
            port = Integer.parseInt(configured.trim());

            if (port <= 0) {
                debugFile("configured port <= 0: " + port);
                return;
            }

            startListener(port);
            return;
        }

        for (int candidate = ALT_PORT_FIRST; candidate <= ALT_PORT_LAST; candidate++) {
            if (startListener(candidate)) {
                return;
            }
        }
    }

    private static boolean startListener(int port) {
        try {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", port));
            _ownListeningPort = port;
            if (debug)
                debugFile("listening on 127.0.0.1:" + port);
            Thread thread = new Thread(() -> listen(socket), "SK Socket Input");
            thread.setDaemon(true);
            thread.start();

            return true;
        } catch (Throwable t) {
            debugFile("bind failed on " + port + ": " + t);
            return false;
        }
    }

    /** Sends a raw message to all alt listener ports (the configured alt range). */
    public static void broadcast(String msg) {
        if (senderSocket == null)
            return;
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            java.net.InetAddress lo = java.net.InetAddress.getByName("127.0.0.1");
            for (int port = ALT_PORT_FIRST; port <= ALT_PORT_LAST; port++) {
                senderSocket.send(new DatagramPacket(b, b.length, lo, port));
            }
        } catch (Throwable t) {
        }
    }

    /** Sends a raw message to the MAIN's listener port only (alt → main replies). */
    public static void sendToMain(String msg) {
        if (senderSocket == null)
            return;
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            senderSocket.send(new DatagramPacket(b, b.length,
                    java.net.InetAddress.getByName("127.0.0.1"), MAIN_REPLY_PORT));
        } catch (Throwable t) {
        }
    }

    /** Sends a raw message to every account's port (main + the alt range). */
    public static void broadcastAll(String msg) {
        if (senderSocket == null)
            return;
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            java.net.InetAddress lo = java.net.InetAddress.getByName("127.0.0.1");
            for (int port = MAIN_REPLY_PORT; port <= ALT_PORT_LAST; port++) {
                senderSocket.send(new DatagramPacket(b, b.length, lo, port));
            }
        } catch (Throwable t) {
        }
    }

    public static void broadcastPosition(float x, float y) {
        broadcast("POS " + x + " " + y);
    }

    /**
     * Main-side: drop a breadcrumb at (x,y) if it has moved ~1 tile since the last
     * one, and broadcast it to the alts. The trail = the main's actual (A*-walked,
     * obstacle-avoiding) path, so alts can retrace it exactly.
     */
    public static void dropBreadcrumb(float x, float y) {
        if (crumbHasLast) {
            float dx = x - lastCrumbX, dy = y - lastCrumbY;
            if (dx * dx + dy * dy < BREADCRUMB_SPACING_SQ)
                return; // hasn't moved far enough yet
        }
        lastCrumbX = x;
        lastCrumbY = y;
        crumbHasLast = true;
        broadcast("CRUMB " + x + " " + y);
    }

    /**
     * Alt-side: the current follow target on the breadcrumb trail. Pops crumbs the
     * alt has already reached, then returns the oldest remaining one (the next
     * point on the main's path ahead of the alt), or null if the trail is empty
     * (caught up — caller falls back to following the main directly).
     */
    public static float[] crumbFollowTarget(float ax, float ay) {
        // PRECISE gather: drive onto the gather point itself, not the crumb trail. The
        // returned point is amplified 3x about the target once close, because the follow
        // path feeds it through setMovementFromDelta's ~0.39 deadzone — unamplified, an
        // alt parks up to 0.39 away, which can be the NEXT TILE. Far out (>0.6) the
        // plain target is returned so the approach (and its dash trigger) stays normal.
        if (preciseGatherOn) {
            float dx = preciseGX - ax, dy = preciseGY - ay;
            if (dx * dx + dy * dy > 0.36f)
                return new float[] { preciseGX, preciseGY };
            return new float[] { preciseGX + 2f * dx, preciseGY + 2f * dy };
        }
        float[] c;
        while ((c = crumbTrail.peek()) != null) {
            float dx = c[0] - ax, dy = c[1] - ay;
            if (dx * dx + dy * dy <= CRUMB_ARRIVE_SQ)
                crumbTrail.poll(); // reached this crumb; advance to the next
            else
                break;
        }
        float[] next = crumbTrail.peek();
        // Siege-wheel gate for the ALTS. Returning the alt's OWN position reads as a
        // zero delta upstream, so setMovementFromDelta holds it still — returning null
        // would instead send it charging at the main's live position, straight through
        // the lane we are trying to avoid.
        if (next != null) {
            try {
                Object view = Reflect.readObjectFieldNullable(dungeonClient, MappingsNames.VIEW_FIELD);
                if (view != null) {
                    WheelDodge.updateWheelTracks(view, System.currentTimeMillis());
                    float[] here = { ax, ay };
                    if (WheelDodge.wheelHold(here, next[0], next[1])) {
                        // Hold ON THE TILE CENTRE, not wherever the alt happened to stop —
                        // off-centre straddles into the adjacent lane's clip zone (needs
                        // ≥0.73 from a lane centreline; centre gives 1.0). The returned
                        // point is amplified 3x about the centre because the follow path
                        // feeds it to setMovementFromDelta, whose ~0.39 deadzone would
                        // otherwise swallow the whole correction; the alt converges to
                        // within ~0.13 of centre and stops.
                        float cx = (float) Math.floor(ax) + 0.5f;
                        float cy = (float) Math.floor(ay) + 0.5f;
                        if (!WheelDodge.inWheelDanger(cx, cy))
                            return new float[] { cx + 2f * (cx - ax), cy + 2f * (cy - ay) };
                        return here;
                    }
                }
            } catch (Exception e) {
            }
        }
        return next;
    }

    /** Main-side: toggle the alts' breadcrumb-follow and reset the trail bookkeeping. */
    private static void routineBroadcastBreadcrumb(boolean on) {
        crumbHasLast = false; // start a fresh trail
        broadcast("BREADCRUMB " + (on ? "1" : "0"));
    }

    private static void listen(DatagramSocket socket) {
        byte[] buffer = new byte[128];

        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                String[] parts = message.split("\\s+");

                if (parts.length == 3 && "POS".equals(parts[0])) {
                    mainBroadcastX = Float.parseFloat(parts[1]);
                    mainBroadcastY = Float.parseFloat(parts[2]);
                    mainBroadcastTime = System.currentTimeMillis();
                    continue;
                }

                if (parts.length == 3 && "CRUMB".equals(parts[0])) {
                    // A breadcrumb from the main's actual path — append to the trail.
                    try {
                        crumbTrail.add(new float[] { Float.parseFloat(parts[1]), Float.parseFloat(parts[2]) });
                        while (crumbTrail.size() > MAX_CRUMBS)
                            crumbTrail.poll(); // drop oldest if we're hopelessly behind
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                if (parts.length >= 4 && "MINGATHER".equals(parts[0])) {
                    // Main's mineral-gather assignment, keyed by pawn id:
                    // "MINGATHER <id> <x> <y> [<id> <x> <y> ...]". This alt picks out
                    // its own id in the injected gather step.
                    mineralAssignMap.clear();
                    try {
                        for (int i = 1; i + 2 < parts.length; i += 3) {
                            mineralAssignMap.put(Integer.valueOf(Integer.parseInt(parts[i])),
                                    new float[] { Float.parseFloat(parts[i + 1]), Float.parseFloat(parts[i + 2]) });
                        }
                    } catch (Exception ignored) {
                    }
                    isMineralGather = true;
                    mineralPickedUp = false;
                    mineralTapActive = false;
                    continue;
                }

                if ("SHOOTALT".equals(parts[0])) {
                    // SHOOT reroute: the main tells the alts to fire weapon 2 at (x,y)
                    // ("SHOOTALT x y") or to stop ("SHOOTALT off"). Alt-side only.
                    if (parts.length >= 3) {
                        try {
                            shootAltX = Float.parseFloat(parts[1]);
                            shootAltY = Float.parseFloat(parts[2]);
                            isRoutineShootAlt = true;
                            shootAltSelectedAt = 0L; // re-arm the weapon-select settle
                        } catch (Exception ignored) {
                        }
                    } else {
                        isRoutineShootAlt = false;
                        routineShootActive = false;
                    }
                    continue;
                }

                if (parts.length == 3 && "PVPBOOST".equals(parts[0])) {
                    // A character announcing its Krogmo Coin booster status:
                    // "PVPBOOST <playerOid> <0|1>". Keyed by playerOid, which
                    // matches ArenaPartyObject.teams' _playerOid.
                    try {
                        PvpAutoQueuer.recordBoost(Integer.parseInt(parts[1]), "1".equals(parts[2]));
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                if (parts.length == 2 && "COORD_QUERY".equals(parts[0])) {
                    final int returnPort;
                    try {
                        returnPort = Integer.parseInt(parts[1]);
                    } catch (Exception e) {
                        continue;
                    }
                    new Thread(() -> {
                        try {
                            Object ctx = _cachedCtx;
                            if (ctx == null)
                                return;
                            int mask = Mappings.computeAvailableLevelMask(
                                    ctx, Mappings.getPlayerObject(ctx));
                            byte[] b = ("COORD_REPLY " + mask).getBytes(StandardCharsets.UTF_8);
                            DatagramSocket ds = new DatagramSocket();
                            ds.send(new DatagramPacket(b, b.length,
                                    java.net.InetAddress.getByName("127.0.0.1"), returnPort));
                            ds.close();
                            ForgeTracker.debug("COORD_REPLY mask=" + Integer.toBinaryString(mask)
                                    + " -> port " + returnPort);
                        } catch (Exception e) {
                            ForgeTracker.debug("COORD_QUERY handler error: " + e);
                        }
                    }, "SK CoordReply").start();
                    continue;
                }

                if (parts.length == 2 && "CROWNQUERY".equals(parts[0])) {
                    // Mission logger: the main asks each alt for its own crown balance so it
                    // can sum the party's crowns (before/after a mission). Reply off-thread.
                    final int returnPort;
                    try {
                        returnPort = Integer.parseInt(parts[1]);
                    } catch (Exception e) {
                        continue;
                    }
                    new Thread(() -> {
                        try {
                            if (_cachedCtx == null)
                                return;
                            int cr = Mappings.getCrowns(_cachedCtx);
                            // Reply IDENTIFIED by this client's listener port. Anonymous
                            // replies made a missing alt invisible: absent from BOTH the
                            // before and after snapshots, its earnings just vanished from
                            // the delta and the total still looked plausible.
                            byte[] b = ("CROWNREPLY " + _ownListeningPort + " " + cr)
                                    .getBytes(StandardCharsets.UTF_8);
                            DatagramSocket ds = new DatagramSocket();
                            ds.send(new DatagramPacket(b, b.length,
                                    java.net.InetAddress.getByName("127.0.0.1"), returnPort));
                            ds.close();
                        } catch (Exception e) {
                        }
                    }, "SK CrownReply").start();
                    continue;
                }

                if (parts.length >= 3 && "DMGSTAT".equals(parts[0])) {
                    // A character sharing its mission damage total: "DMGSTAT <total> <knightName…>"
                    // (name last — knight names can contain spaces).
                    try {
                        long total = Long.parseLong(parts[1]);
                        StringBuilder nm = new StringBuilder(parts[2]);
                        for (int pi = 3; pi < parts.length; pi++)
                            nm.append(' ').append(parts[pi]);
                        DamageMeter.recordStat(nm.toString(), total);
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                if (parts.length == 2 && "INVITEQUERY".equals(parts[0])) {
                    // Invite-based lobby fill: the main asks each alt for its knight name
                    // so it can party-invite the ones missing from the roster. Reply
                    // off-thread (the name read touches the ctx/PlayerObject).
                    final int inviteReturnPort;
                    try {
                        inviteReturnPort = Integer.parseInt(parts[1]);
                    } catch (Exception e) {
                        continue;
                    }
                    new Thread(() -> {
                        try {
                            if (_cachedCtx == null)
                                return;
                            // KNIGHT name (PlayerObject.knight), NOT the account username —
                            // PartyMarshaller invites only resolve knight names.
                            String kn = Mappings.getKnightCharacterName(Mappings.getPlayerObject(_cachedCtx));
                            if (kn == null || kn.isEmpty())
                                return;
                            byte[] b = ("INVITENAME " + kn).getBytes(StandardCharsets.UTF_8);
                            DatagramSocket ds = new DatagramSocket();
                            ds.send(new DatagramPacket(b, b.length,
                                    java.net.InetAddress.getByName("127.0.0.1"), inviteReturnPort));
                            ds.close();
                        } catch (Exception e) {
                        }
                    }, "SK InviteName").start();
                    continue;
                }

                // PRECISEGATHER sits ABOVE the two-token gate (its ON form has 3 tokens):
                // "PRECISEGATHER <x> <y>" starts an alt's fine drive onto the target
                // tile; "PRECISEGATHER off" ends it. See T_PRECISE_MOVETO.
                if ("PRECISEGATHER".equalsIgnoreCase(parts[0])) {
                    if (parts.length >= 3) {
                        try {
                            preciseGX = Float.parseFloat(parts[1]);
                            preciseGY = Float.parseFloat(parts[2]);
                            preciseGatherOn = true;
                        } catch (NumberFormatException nfe) {
                            preciseGatherOn = false;
                        }
                    } else {
                        preciseGatherOn = false;
                    }
                    continue;
                }

                // DASH is handled ABOVE the two-token gate below: a manual dash carries a
                // third token (the main's aim angle) so the alt can FACE that way first,
                // and the gate would otherwise drop the whole message. A bare "DASH 1"
                // (SNARBY boss-dodge) still works and simply supplies no angle.
                if ("DASH".equalsIgnoreCase(parts[0]) && parts.length >= 2) {
                    if ("1".equals(parts[1])) {
                        if (parts.length >= 3) {
                            try {
                                pendingDashAngle = Float.parseFloat(parts[2]);
                                pendingDashHasAngle = true;
                            } catch (NumberFormatException nfe) {
                                pendingDashHasAngle = false;
                            }
                        }
                        pendingDashPress = true;
                        pendingDashRelease = false;
                    } else {
                        pendingDashRelease = true;
                        pendingDashPress = false;
                    }
                    continue;
                }

                if (parts.length != 2) {
                    continue;
                }

                String key = parts[0].toUpperCase(Locale.ROOT);
                if ("AUTOFOLLOW".equals(key)) {
                    isAutoFollowing = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    continue;
                }

                if ("BREADCRUMB".equals(key)) {
                    // Main toggles alts into/out of breadcrumb-follow (routine).
                    isBreadcrumbFollow = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    if (!isBreadcrumbFollow)
                        preciseGatherOn = false; // a precision gather cannot outlive the follow mode it rides on
                    crumbTrail.clear();
                    continue;
                }


                if ("DMGRESET".equals(key)) {
                    // Mission start (main broadcasts from missionStart): zero the damage tally.
                    DamageMeter.reset();
                    continue;
                }

                if ("FORGEALL".equals(key)) {
                    // Main entered a fresh mission lobby — run this alt's forge pass once
                    // it's standing in the lobby too (deferred via the game tick).
                    forgePassPending = true;
                    continue;
                }

                if ("FORGEDONE".equals(key)) {
                    // An alt finished its lobby forge pass (main-side tally for the hold).
                    if (isMainAccount())
                        forgeDoneAltCount++;
                    continue;
                }

                if ("FEEDALL".equals(key)) {
                    // Main entered a fresh mission lobby — run this alt's sprite-feed pass
                    // once it's standing in the lobby too (deferred via SpriteFeeder.tick).
                    SpriteFeeder.feedPassPending = true;
                    continue;
                }

                if ("FEEDDONE".equals(key)) {
                    // An alt finished its lobby sprite-feed pass (main-side tally for the hold).
                    if (isMainAccount())
                        SpriteFeeder.noteAltDone();
                    continue;
                }

                if ("MINERALGATHER".equals(key)) {
                    // Main ends (or, unused, starts) the mineral-gather phase. MINGATHER
                    // is what actually turns it on with an assignment.
                    isMineralGather = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    if (!isMineralGather) {
                        mineralAssignMap.clear();
                        mineralTapActive = false;
                        mineralPickedUp = false;
                        pickupAimActive = false;
                        clearMovementKeys();
                    }
                    continue;
                }

                if ("PVPQUEUE".equals(key)) {
                    isAutoPvpQueue = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    if (debug) {
                        debugFile("[pvpqueue] auto-queue " + (isAutoPvpQueue ? "ON" : "OFF"));
                    }
                    continue;
                }

                if ("DEBUGMODE".equals(key)) {
                    // Runtime debug toggle (multibox '='): flips debug.log writing on every
                    // client without a rebuild (debug is volatile, no longer compile-final).
                    debug = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    debugFile("[debug] debug logging ON"); // no-op when switching OFF
                    continue;
                }

                if ("HEATHUD".equals(key)) {
                    showHeatHud = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    if (debug) {
                        debugFile("[heathud] " + (showHeatHud ? "shown" : "hidden"));
                    }
                    continue;
                }

                if ("COMBATBOT".equals(key)) {
                    isCombatBot = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    if (!isCombatBot) {
                        botFiring = false;
                        botShieldHold = false;
                        botCycleActive = false;
                        botCyclePhase = 0;
                        // Forget the equipped-weapon belief so re-enabling forces a
                        // fresh select + settle (the player may have swapped weapons
                        // while the bot was off). Do NOT touch botFireHeld — the poll
                        // owns it and releases any live press itself.
                        botLastWeaponSlot = -1;
                        botFireBlockedUntil = 0L;
                    }
                    if (debug) {
                        debugFile("[combatbot] " + (isCombatBot ? "ON" : "OFF"));
                    }
                    continue;
                }

                if ("GUNFAMILIES".equals(key)) {
                    // Per-mission weapon config from the main (see gunFamilies /
                    // loadMissionWeaponConfig): "<fams|-> <w1 cadence> <w2 cadence>".
                    // "-" = empty family list (weapon 1 always); absent cadence
                    // tokens fall back to the defaults (w1 autogun, w2 blaster).
                    if (parts.length >= 2 && !"-".equals(parts[1]))
                        gunFamilies = parts[1].toLowerCase(Locale.ROOT).split(",");
                    else
                        gunFamilies = new String[0];
                    slot1CadenceBlaster = parts.length >= 3 && "blaster".equals(parts[2]);
                    slot2CadenceBlaster = parts.length < 4 || !"autogun".equals(parts[3]);
                    if (debug)
                        debugFile("[combatbot] weapon-2 families <- " + String.join(",", gunFamilies)
                                + "; cadences w1=" + (slot1CadenceBlaster ? "blaster" : "autogun")
                                + " w2=" + (slot2CadenceBlaster ? "blaster" : "autogun"));
                    continue;
                }

                if ("SCENESCAN".equals(key)) {
                    // Edge-triggered: each press requests one dump (performed on the
                    // tick, which has the controller in hand). Sent to the main only.
                    sceneScanPending = true;
                    debugFile("[scenescan] dump requested");
                    continue;
                }

                if ("DUMPPOS".equals(key)) {
                    // Edge-triggered: dump the pawn's current map coords on the tick.
                    dumpPosPending = true;
                    continue;
                }

                if ("LOOTMODE".equals(key)) {
                    boolean on = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    isLootMode = on;
                    lootPlanned = false;   // (re)plan on each ON; harmless on OFF
                    lootDoneLogged = false;
                    if (!on) {
                        clearMovementKeys(); // cancel: stop the main immediately
                        blockClearStop();
                    }
                    debugFile("[loot] " + (on ? "ON (planning route)" : "OFF"));
                    continue;
                }

                if ("PATHTEST".equals(key)) {
                    boolean on = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    isPathTest = on;
                    pathTestPlanned = false; // re-plan on each ON
                    pathTestDone = false;
                    if (!on) {
                        clearMovementKeys();
                    }
                    debugFile("[path] test " + (on ? "ON" : "OFF"));
                    continue;
                }

                if ("ROUTINE".equals(key)) {
                    boolean on = "1".equals(parts[1]) || "True".equalsIgnoreCase(parts[1]);
                    if (on) {
                        // Ctrl+R begins the automatic cycle and LOOPS until Ctrl+R off. It first loads
                        // the ACTIVE MISSION (routines/active_mission.txt → its mission_data.txt: launch
                        // id, floor count, difficulty); if that's missing/malformed it refuses to start.
                        // If we're already inside a mission floor it runs that floor; otherwise (ready
                        // room or Haven) it launches the mission, the alts auto-join, and the run begins.
                        // tickCampaign then auto-starts each floor until the terminal floor, which
                        // relaunches the mission — repeating.
                        //
                        // Gate on the LIVE LevelPartyObject, NOT the possibly-stale dungeonClient:
                        // after a mission, Ctrl+R in Haven must relaunch, not try to re-run the
                        // last dungeon's routine (which resolveFloorKey would still report).
                        if (!loadActiveMission()) {
                            debugFile("[campaign] no valid active mission — not starting (see active_mission.txt / mission_data.txt)");
                            campaignActive = false;
                            continue;
                        }
                        KeyBinds.load(); // re-read the control scheme: a rebind mid-session takes effect here
                        campaignActive = true;
                        autoAdvanceOn = true;       // skip each floor's ~10s summary screen
                        broadcast("AUTOADVANCE 1"); // every knight dismisses its own
                        mainIdleSince = 0L; // fresh idle-watchdog window (a stale anchor must not insta-fire)
                        campaignLastFloor = null;
                        campaignLoadedSince = 0L;
                        campaignRestartPending = false;
                        campaignRestartPhase = 0;
                        campaignTownSince = 0L;
                        campaignRelaunchAt = 0L;
                        if (Mappings.getLevelPartyObject(_cachedCtx) != null && startRoutineForCurrentFloor()) {
                            debugFile("[campaign] resuming on the current mission floor");
                        } else {
                            // Ready room / Haven (or a floor with no routine): launch the mission.
                            campaignRestartPending = true;
                            debugFile("[campaign] launching '" + campaignMissionId + "' to begin the cycle");
                        }
                    } else {
                        campaignActive = false; // abort the whole campaign
                        autoAdvanceOn = false;
                        broadcast("AUTOADVANCE 0");
                        campaignRestartPending = false; // cancel any pending endless-cycle relaunch
                        isRoutineActive = false;
                        isCombatBot = false;
                        isLootMode = false;
                        routineShootActive = false;
                        blockClearActive = false;
                        isMineralGather = false;
                        mineralTapActive = false;
                        routineBroadcastCombat(false); // stop the alts' combat bots
                        routineBroadcastBreadcrumb(false); // back to naive auto-follow
                        broadcast("MINERALGATHER 0");
                        broadcast("SHOOTALT off"); // stop any alt SHOOT firing
                        releaseShieldBump(); // drop any shield raised for a bump
                        snarbyStop(); // clear SNARBY state + drop the alts' sustained shield
                        clearMovementKeys();
                        debugFile("[routine] STOP");
                    }
                    continue;
                }

                if ("FIRE_DOWN".equals(key)) {
                    try {
                        pendingFireAngle = Float.parseFloat(parts[1]);
                        pendingWeaponPress = true;
                        pendingWeaponRelease = false;
                        pendingWeaponAimDirty = true;
                    } catch (Exception ignored) {
                    }
                } else if ("FIRE_AIM".equals(key)) {
                    try {
                        pendingFireAngle = Float.parseFloat(parts[1]);
                        pendingWeaponAimDirty = true;
                    } catch (Exception ignored) {
                    }
                } else if ("FIRE_UP".equals(key)) {
                    try {
                        pendingFireAngle = Float.parseFloat(parts[1]);
                        pendingWeaponAimDirty = true;
                    } catch (Exception ignored) {
                    }
                    pendingWeaponPress = false;
                    pendingWeaponRelease = true;
                }

                if ("SHIELD".equals(key)) {
                    if ("1".equals(parts[1])) {
                        pendingShieldPress = true;
                    } else {
                        pendingShieldRelease = true;
                    }
                    continue;
                }


                // Alts only act on group barrier/vial triggers while
                // auto-following — parked alts should not pop consumables.
                // (The main never receives these messages; its own press sets
                // the pending flag directly in the patched poll.)
                // Auction sweep (Ctrl+E, main only). The whole feature lives in
                // AuctionBot.java — this is its ONLY hook into SIS besides the poll
                // call, so it can be lifted out into a standalone mod.
                if ("AUCTIONSCAN".equals(key)) {
                    if (isMainAccount())
                        AuctionBot.scanPending = true;
                    continue;
                }

                // Item-name lookup table (Ctrl+U): dumps every item's display name and
                // config name, for writing auction_watch rules.
                if ("ITEMDUMP".equals(key)) {
                    if (isMainAccount())
                        AuctionBot.catalogPending = true;
                    continue;
                }

                // Auction auto-SELLER toggle (Ctrl+W, main only): keeps inventory listed
                // per ~/.sk-utils/auction_sells.txt — the seller half of AuctionBot.java.
                if ("AUCTIONSELL".equals(key)) {
                    if (isMainAccount())
                        AuctionBot.sellPending = true;
                    continue;
                }

                if ("BARRIER".equals(key)) {
                    if ("1".equals(parts[1]) && isAutoFollowing) {
                        pendingBarrierUse = true;
                    }
                    continue;
                }

                if ("VIAL".equals(key)) {
                    if ("1".equals(parts[1]) && isAutoFollowing) {
                        pendingVialUse = true;
                    }
                    continue;
                }

                if ("AUTOADVANCE".equals(key)) {
                    // Each client dismisses its OWN summary screen; the party only moves
                    // on once everybody has.
                    autoAdvanceOn = "1".equals(parts[1]);
                    continue;
                }

                if ("SPRITEAIMED".equals(key)) {
                    // Routine auto-fire: arm it; this client's own combat bot presses the
                    // ability once it has a target acquired and aimed (see tickCombatBot).
                    spriteAimedRequest = true;
                    spriteAimedSince = System.currentTimeMillis();
                    continue;
                }

                if ("SPRITE".equals(key)) {
                    try {
                        int slot = Integer.parseInt(parts[1]);
                        if (slot >= 0 && slot <= 2) {
                            pendingSpriteSlot = slot;
                        }
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                if (!isAllowedKey(key)) {
                } else if ("1".equals(parts[1])) {
                    DOWN.add(key);
                } else if ("0".equals(parts[1])) {
                    DOWN.remove(key);
                }
            } catch (Throwable t) {
            }
        }

    }

    public static boolean isDown(String key) {
        if (isMainAccount())
            return false;
        return DOWN.contains(key.toUpperCase(Locale.ROOT));
    }

    public static void clear() {
        DOWN.clear();
    }

    public static void logFollowSource(boolean usingBroadcast) {
    }

    /**
     * Called from Patcher-injected code in TudeyController.tick to clear movement
     * keys.
     */
    public static void clearMovementKeys() {
        DOWN.remove("W");
        DOWN.remove("A");
        DOWN.remove("S");
        DOWN.remove("D");
    }

    /**
     * Called from Patcher-injected code in TudeyController.tick to apply movement
     * from delta.
     */
    public static void setMovementFromDelta(float dx, float dy) {
        float distSq = dx * dx + dy * dy;
        if (distSq > 0.15f) {
            if (dy > 0.15f)
                DOWN.add("W");
            else if (dy < -0.15f)
                DOWN.add("S");
            if (dx > 0.15f)
                DOWN.add("D");
            else if (dx < -0.15f)
                DOWN.add("A");
        }
    }

    /** Triggers a SHIFT+X dash if the cooldown has elapsed. */
    public static void triggerDash() {
        long now = System.currentTimeMillis();
        if (now - lastDashTime >= DASH_COOLDOWN_MS) {
            lastDashTime = now;
            dashUntil = now + DASH_HOLD_MS;
        }
    }

    public static String[] getDownKeys() {
        if (System.currentTimeMillis() < dashUntil) {
            // Dash = the game's `dodge` binding, which usually rides on modifier_1
            // (classic Shift+X). Both are appended as LOGICAL names so getKeyCode
            // resolves them against the live scheme.
            String[] base = DOWN.toArray(new String[0]);
            int extra = bindDodgeMod ? 2 : 1;
            String[] result = java.util.Arrays.copyOf(base, base.length + extra);
            int i = base.length;
            if (bindDodgeMod)
                result[i++] = "MOD1";
            result[i] = "DODGE";

            return result;
        }

        return DOWN.toArray(new String[0]);
    }

    // ── Auto-consumable (vitapod / remedy capsule) ────────────────────────────

    /**
     * Called each tick on alts. Scans all 4 pickup quickbar slots, finds the right
     * item type for the current need, and uses it via the item service's
     * use call (long oid, Object, ConfirmListener) — the same path the game's
     * QuickbarPanel slot widgets invoke on a quickslot key press.
     *
     * Priority: remedy capsule (negative status condition) > health capsule
     * (health below 1/3 of maximum).
     */
    public static void tryUseConsumable(Object controller, Object ctx, Object actor) {
        if (controller == null) {
            return;
        }
        // Alts always auto-heal/remedy; the MAIN does too, but ONLY while Ctrl+R campaign mode
        // is active (its call site is likewise campaign-gated — this is belt-and-suspenders).
        if (isMainAccount() && !campaignActive)
            return;
        long now = System.currentTimeMillis();
        if (now < consumableCooldownUntil)
            return;
        try {
            // Invalidate caches when the controller class changes (scene transition).
            if (controller.getClass() != cachedCtrlClass) {
                cachedCtrlClass = controller.getClass();
                cachedUseItemMethod = null;
                Reflect.invalidateCaches();
            }

            Object po = Mappings.getPlayerObject(ctx);
            if (po == null) {
                if (debug) {
                    debugFile("[consumable] no player object");
                }
                return;
            }
            // Determine what we need.
            boolean needHeal = false;
            boolean needRemedy = actor != null && Reflect.hasNegativeStatus(actor);

            Object health = getHealth(controller, po);
            if (health != null) {
                Integer cur = Reflect.readIntFieldNullable(health, "current");
                Integer max = Reflect.readIntFieldNullable(health, "maximum");
                needHeal = cur != null && max != null && max > 0 && cur * 3 < max;
            }

            if (!needHeal && !needRemedy) {
                consumablePendingUntil = 0L; // condition cleared — reset delay
                return;
            }

            // First pass: start the 1s pre-use delay and return.
            if (consumablePendingUntil == 0L) {
                consumablePendingUntil = now + CONSUMABLE_DELAY_MS;
                return;
            }

            // Still within the delay window — wait.
            if (now < consumablePendingUntil)
                return;

            Object configMgr = ctx.getClass().getMethod("getConfigManager").invoke(ctx);
            Object itemService = Mappings.getItemService(Mappings.getClientManager(ctx));
            java.lang.reflect.Method useMethod = findItemUseMethod(itemService.getClass());
            if (useMethod == null) {
                if (debug) {
                    debugFile("[consumable] no item-use service method");
                }
                return;
            }
            java.lang.reflect.Method getItemMethod = findGetQuickbarItem(po.getClass());
            if (getItemMethod == null) {
                if (debug) {
                    debugFile("[consumable] no quickbar get method");
                }
                return;
            }

            // Scan all 4 pickup slots for $Capsule items, remembering the first
            // remedy and first health capsule found. Remedy takes priority over
            // health regardless of slot order.
            // $Vial items are status-inflicting (fire/shock/etc.) — skipped entirely.
            // All pickups share the $Capsule config class (barriers, buffs, kits,
            // potions included), so match the config name positively:
            //   "Pickup/Capsule/[Super |Ultra ]Health Capsule" — heal
            //   "Pickup/Capsule/Remedy Capsule"                — remedy
            Object remedyItem = null;
            int remedySlot = -1;
            Object healthItem = null;
            int healthSlot = -1;
            for (int slot = 0; slot < 4; slot++) {
                Object item = getItemMethod.invoke(po, slot);
                if (item == null)
                    continue;
                String cfgClass = getItemConfigClass(item, configMgr);
                if (cfgClass == null || !cfgClass.endsWith("DungeonItemConfig$Capsule"))
                    continue;

                String cfgName = getItemConfigRefName(item);
                if (cfgName == null)
                    continue;
                String cfgNameLower = cfgName.toLowerCase(Locale.ROOT);
                if (remedyItem == null && cfgNameLower.contains("remedy capsule")) {
                    remedyItem = item;
                    remedySlot = slot;
                }
                if (healthItem == null && cfgNameLower.contains("health capsule")) {
                    healthItem = item;
                    healthSlot = slot;
                }
            }

            if (needRemedy && remedyItem != null) {
                useItem(itemService, useMethod, remedyItem, remedySlot);
                consumableCooldownUntil = now + CONSUMABLE_COOLDOWN_MS;
                consumablePendingUntil = 0L;
                return;
            }
            if (needHeal && healthItem != null) {
                useItem(itemService, useMethod, healthItem, healthSlot);
                consumableCooldownUntil = now + CONSUMABLE_COOLDOWN_MS;
                consumablePendingUntil = 0L;
                return;
            }
        } catch (Exception e) {
            if (debug) {
                debugFile("[consumable] error: " + e);
            }
        }
    }

    /**
     * Called each tick on all accounts — main included. When the multibox G
     * hotkey has flagged a pending barrier use, scans the 4 quickbar slots for
     * Orbital Barrier capsules ("Pickup/Capsule/Orbital Barrier/...") and uses
     * a random one via the item service. Does nothing if no barrier is present.
     */
    public static void tryUseBarrier(Object ctx) {
        if (!pendingBarrierUse)
            return;
        pendingBarrierUse = false;
        long now = System.currentTimeMillis();
        if (now < barrierCooldownUntil)
            return;
        try {
            Object po = Mappings.getPlayerObject(ctx);
            if (po == null)
                return;
            Object itemService = Mappings.getItemService(Mappings.getClientManager(ctx));
            java.lang.reflect.Method useMethod = findItemUseMethod(itemService.getClass());
            java.lang.reflect.Method getItemMethod = findGetQuickbarItem(po.getClass());
            if (useMethod == null || getItemMethod == null) {
                if (debug) {
                    debugFile("[barrier] no item-use/quickbar method");
                }
                return;
            }

            java.util.List<Object> barrierItems = new java.util.ArrayList<>();
            java.util.List<Integer> barrierSlots = new java.util.ArrayList<>();
            for (int slot = 0; slot < 4; slot++) {
                Object item = getItemMethod.invoke(po, slot);
                if (item == null)
                    continue;
                String cfgName = getItemConfigRefName(item);
                if (cfgName == null || !cfgName.toLowerCase(Locale.ROOT).contains("orbital barrier"))
                    continue;
                barrierItems.add(item);
                barrierSlots.add(slot);
            }
            if (barrierItems.isEmpty()) {
                if (debug) {
                    debugFile("[barrier] no barrier items in quickbar");
                }
                return;
            }

            int pick = new java.util.Random().nextInt(barrierItems.size());
            if (debug) {
                debugFile("[barrier] using " + getItemConfigRefName(barrierItems.get(pick))
                        + " (slot " + barrierSlots.get(pick) + " of " + barrierItems.size() + " found)");
            }
            useItem(itemService, useMethod, barrierItems.get(pick), barrierSlots.get(pick));
            barrierCooldownUntil = now + BARRIER_COOLDOWN_MS;
        } catch (Exception e) {
            if (debug) {
                debugFile("[barrier] error: " + e);
            }
        }
    }

    /**
     * Called each tick on all accounts — main included. When the H hotkey has
     * flagged a pending vial use, scans the 4 quickbar slots for vial items
     * ("Pickup/Vial/...") and uses a random one via the item service. Does
     * nothing if no vial is present.
     */
    public static void tryUseVial(Object ctx) {
        if (!pendingVialUse)
            return;
        pendingVialUse = false;
        long now = System.currentTimeMillis();
        if (now < vialCooldownUntil)
            return;
        try {
            Object po = Mappings.getPlayerObject(ctx);
            if (po == null)
                return;
            Object itemService = Mappings.getItemService(Mappings.getClientManager(ctx));
            java.lang.reflect.Method useMethod = findItemUseMethod(itemService.getClass());
            java.lang.reflect.Method getItemMethod = findGetQuickbarItem(po.getClass());
            if (useMethod == null || getItemMethod == null) {
                if (debug) {
                    debugFile("[vial] no item-use/quickbar method");
                }
                return;
            }

            java.util.List<Object> vialItems = new java.util.ArrayList<>();
            java.util.List<Integer> vialSlots = new java.util.ArrayList<>();
            for (int slot = 0; slot < 4; slot++) {
                Object item = getItemMethod.invoke(po, slot);
                if (item == null)
                    continue;
                String cfgName = getItemConfigRefName(item);
                if (cfgName == null || !cfgName.toLowerCase(Locale.ROOT).contains("pickup/vial/"))
                    continue;
                vialItems.add(item);
                vialSlots.add(slot);
            }
            if (vialItems.isEmpty()) {
                if (debug) {
                    debugFile("[vial] no vial items in quickbar");
                }
                return;
            }

            int pick = new java.util.Random().nextInt(vialItems.size());
            if (debug) {
                debugFile("[vial] using " + getItemConfigRefName(vialItems.get(pick))
                        + " (slot " + vialSlots.get(pick) + " of " + vialItems.size() + " found)");
            }
            useItem(itemService, useMethod, vialItems.get(pick), vialSlots.get(pick));
            vialCooldownUntil = now + VIAL_COOLDOWN_MS;
        } catch (Exception e) {
            if (debug) {
                debugFile("[vial] error: " + e);
            }
        }
    }


    // ── Combat bot (multibox Ctrl+B) ──────────────────────────────────────────

    /**
     * Called each tick on every account while the combat bot is enabled.
     * Self-throttled to BOT_TICK_INTERVAL_MS. Reads the tudey view's actor map,
     * finds the closest living Monster to this character's pawn, selects weapon
     * slot 2 via the controller's dW(int), and sets botFireAngle (world→screen
     * aim) + botFiring so the patched InputState.poll fires. Clears botFiring
     * when no enemy is present. The controller is the dungeon client (m).
     */
    public static void tickCombatBot(Object controller) {
        if (controller == null) {
            botFiring = false;
            return;
        }
        if (controller.getClass() != cachedBotCtrlClass) {
            cachedBotCtrlClass = controller.getClass();
            cachedWeaponSelectMethod = null;
        }
        long now = System.currentTimeMillis();
        if (now < botNextTickAt)
            return;
        botNextTickAt = now + BOT_TICK_INTERVAL_MS;
        try {
            Object view = Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            Integer pawnId = Reflect.readIntFieldNullable(controller, MappingsNames.PAWN_ID_FIELD);
            if (view == null || pawnId == null) {
                botFiring = false;
                botShieldHold = false;
                return;
            }
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null) {
                botFiring = false;
                botShieldHold = false;
                return;
            }

            // This character's pawn position.
            Object myWrapper = actorMap.getClass().getMethod("get", int.class).invoke(actorMap, pawnId.intValue());
            Object myActor = (myWrapper == null) ? null : Reflect.getWrappedActor(myWrapper);
            if (myActor == null) {
                botFiring = false;
                botShieldHold = false;
                return;
            }
            float[] mp = Reflect.actorPos(myActor);
            if (mp == null) {
                botFiring = false;
                botShieldHold = false;
                return;
            }

            // Single pass: flag a nearby incoming bullet (→ shield) and track the
            // closest living Monster (→ aim + weapon choice by its family).
            Class<?> monsterCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Monster");
            Class<?> bulletCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Bullet");
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            float bestSq = Float.MAX_VALUE;
            float bestDx = 0f, bestDy = 0f;
            boolean found = false;
            boolean shield = false;
            Object closest = null;
            for (Object wrapper : (Iterable<?>) values) {
                Object actor = Reflect.getWrappedActor(wrapper);
                if (actor == null)
                    continue;
                if (bulletCls.isInstance(actor)) {
                    if (!shield) {
                        float[] bp = Reflect.actorPos(actor);
                        if (bp != null) {
                            float bdx = bp[0] - mp[0], bdy = bp[1] - mp[1];
                            // Within 2 tiles AND its faction can damage me
                            // (excludes my own and teammates' shots).
                            if (bdx * bdx + bdy * bdy <= BULLET_SHIELD_RADIUS_SQ
                                    && bulletCanHit(actor, myActor)) {
                                shield = true;
                            }
                        }
                    }
                    continue;
                }
                if (!monsterCls.isInstance(actor))
                    continue;
                Float hp = Reflect.readFloatFieldNullable(actor, "_healthPct");
                if (hp != null && hp <= 0f)
                    continue; // dead / dying
                float[] ep = Reflect.actorPos(actor);
                if (ep == null)
                    continue;
                float dx = ep[0] - mp[0], dy = ep[1] - mp[1];
                float d2 = dx * dx + dy * dy;
                if (d2 < bestSq) {
                    bestSq = d2;
                    bestDx = dx;
                    bestDy = dy;
                    found = true;
                    closest = actor;
                }
            }

            // Shield takes priority: raise it and suppress fire while a bullet is close.
            botShieldHold = shield;
            if (shield) {
                botFiring = false;
                return;
            }

            // Nothing to shoot: stop firing (the poll ends the current cycle).
            // Leave the equipped weapon/cadence as-is — still matched, in hand.
            if (!found) {
                botFiring = false;
                return;
            }

            // Weapon choice by the CLOSEST enemy's family: a gunFamilies family
            // → weapon 2; anything else (unknowns included) → weapon 1. Each
            // slot fires with its configured cadence (see slot1/2CadenceBlaster).
            boolean useWeapon2 = !isGunFamily(closest);

            // Aim at the closest enemy. No camera rotation (azimuth 0), so the
            // world direction maps to the screen angle after (a) compressing
            // world-Y for the 45° camera tilt and (b) flipping world-Y, because
            // world +Y renders toward the bottom of the screen (the fire code's
            // screenY = centre - sin already assumes screen-up = +angle).
            botFireAngle = (float) Math.atan2(-bestDy * Reflect.BOT_AIM_VERTICAL_SCALE, bestDx);

            // AIMED SPRITE AUTO-FIRE — runs on EVERY client (this method is invoked on the
            // main and on each alt, since the injected tick gates it on isCombatBot, which
            // the routine mirrors to the alts). botFireAngle above is THIS client's own
            // closest enemy, so each knight casts at its own target.
            //
            // The botFiring/botShieldHold test is what actually guarantees the aim: the
            // poll only parks the cursor on botFireAngle while `isCombatBot && botFiring &&
            // !botShieldHold`. Between cycles — notably the ~300ms weapon settle right at
            // engage — it does NOT, so firing then would send the ability along whatever
            // bearing the cursor still held. Both flags are read BEFORE this pass updates
            // them, i.e. they describe the state the poll has actually been rendering.
            // Held (not dropped) until all of that is true, which is what stops the
            // combat-start misfire; abandoned after SPRITE_AIM_GIVEUP_MS so it can't fire
            // stale.
            if (spriteAimedRequest && now - spriteAimedSince >= SPRITE_AIM_GIVEUP_MS)
                spriteAimedRequest = false; // never found a target — don't fire it stale (runs on ALTS too, unlike the old tickCombatSprite-side check)
            if (spriteAimedRequest && botFiring && !botShieldHold
                    && now - spriteAimedSince >= SPRITE_AIM_SETTLE_MS) {
                spriteAimedRequest = false;
                spriteBurstLeft = SPRITE_BURST_PRESSES; // re-press burst: outlast attack-animation eating
                spriteNextPressAt = 0L;
                pendingSpriteSlot = 0; // sprite ability 1
                combatSpriteNextAt = now + COMBAT_SPRITE_INTERVAL_MS; // cadence re-arms from the CAST, not the request
                if (debug)
                    debugFile("[sprite] auto-fire aimed at the closest enemy");
            }

            // A firing cycle is mid-animation: NEVER change weapon/mode here.
            // Just keep the poll's cycle alive; we re-decide at the next boundary.
            if (botCycleActive) {
                botFiring = true;
                return;
            }

            // Between cycles (safe boundary): commit the weapon for the next
            // cycle, advancing the equipped-slot belief and the cadence flag
            // together only on a successful select.
            int desiredSlot = useWeapon2 ? PRIMARY_WEAPON_SLOT : GUN_WEAPON_SLOT;
            if (desiredSlot != botLastWeaponSlot) {
                if (selectWeapon(controller, desiredSlot)) {
                    botLastWeaponSlot = desiredSlot;
                    botWeapon2Mode = useWeapon2;
                    botWeaponSwitchAt = now + BOT_WEAPON_SWITCH_MS;
                    botFireBlockedUntil = now + BOT_WEAPON_SETTLE_MS; // let it equip first
                }
            } else if (now >= botWeaponSwitchAt) {
                // Re-assert the same slot periodically so an external weapon
                // change can't silently desync it from the cadence.
                botWeaponSwitchAt = now + BOT_WEAPON_SWITCH_MS;
                selectWeapon(controller, botLastWeaponSlot);
            }

            // Grant the next cycle once any just-issued switch has settled.
            botFiring = (now >= botFireBlockedUntil);
        } catch (Exception e) {
            botFiring = false;
            botShieldHold = false;
            if (debug) {
                debugFile("[combatbot] error: " + e);
            }
        }
    }

    /**
     * True if a Bullet actor's faction can damage the given actor — the game's
     * own Bullet.e(Actor) predicate (the unique boolean method taking an Actor).
     * Used to shield only against incoming enemy shots, not our own or allies'.
     */
    private static boolean bulletCanHit(Object bullet, Object target) {
        try {
            if (cachedBulletHitMethod == null || !cachedBulletHitMethod.getDeclaringClass().isInstance(bullet)) {
                Class<?> actorCls = Class.forName(MappingsNames.ACTOR_CLASS);
                for (java.lang.reflect.Method m : bullet.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && m.getReturnType() == boolean.class && p[0] == actorCls) {
                        cachedBulletHitMethod = m;
                        break;
                    }
                }
            }
            if (cachedBulletHitMethod != null) {
                return (Boolean) cachedBulletHitMethod.invoke(bullet, target);
            }
        } catch (Exception ignored) {
        }
        // If we can't resolve the predicate, treat the bullet as dangerous
        // (fail safe: shield rather than eat the shot).
        return true;
    }

    /**
     * True if a Monster belongs to a weapon-2 (sidearm) family — one weapon 1 is
     * ineffective against — read from its actor config reference path (e.g.
     * "Monster/Construct/..."). The family list is per-mission data (gunFamilies).
     * Unknown/unreadable families return false (→ weapon 1).
     */
    private static boolean isGunFamily(Object actor) {
        String s = Reflect.actorConfigName(actor).toLowerCase(Locale.ROOT);
        String[] fams = gunFamilies;
        for (int i = 0; i < fams.length; i++)
            if (s.contains(fams[i]))
                return true;
        return false;
    }

    /**
     * Latches the cadence for the slot just committed for the current firing
     * cycle (botCycleWeapon2, true = weapon 1) and selects the matching weapon.
     * Called from the poll at the START of every cycle so a weapon/cadence
     * mismatch can never persist beyond a single cycle. Runs on the local
     * account's controller (dungeonClient), same GL thread as the tick.
     */
    public static void botSelectWeaponForCycle() {
        // Latch the cadence BEFORE the controller guard: the poll has already
        // committed botCycleWeapon2 for this cycle, so the tap timing must
        // follow it even when the select itself can't be issued.
        botCycleBlaster = botCycleWeapon2 ? slot1CadenceBlaster : slot2CadenceBlaster;
        Object ctrl = dungeonClient;
        if (ctrl == null)
            return;
        int slot = botCycleWeapon2 ? PRIMARY_WEAPON_SLOT : GUN_WEAPON_SLOT;
        selectWeapon(ctrl, slot);
        botLastWeaponSlot = slot; // keep the tick's equipped-slot belief in sync
    }

    /**
     * Selects the equipped weapon by index via the controller's dW(int).
     * Returns true only if the select call was actually issued, so the caller
     * can keep its equipped-slot/cadence belief in sync with reality (a failed
     * select must not advance botLastWeaponSlot/botWeapon2Mode).
     */
    private static boolean selectWeapon(Object controller, int slot) {
        try {
            if (cachedWeaponSelectMethod == null) {
                for (Class<?> c = controller.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        java.lang.reflect.Method m = c.getDeclaredMethod(MappingsNames.WEAPON_SELECT_METHOD, int.class);
                        m.setAccessible(true);
                        cachedWeaponSelectMethod = m;
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (cachedWeaponSelectMethod != null) {
                cachedWeaponSelectMethod.invoke(controller, slot);
                return true;
            } else if (debug) {
                debugFile("[combatbot] no " + MappingsNames.WEAPON_SELECT_METHOD + "(int) weapon-select method");
            }
        } catch (Exception e) {
            if (debug) {
                debugFile("[combatbot] selectWeapon error: " + e);
            }
        }
        return false;
    }


    // ── Scene scan (multibox Ctrl+D): dump the loaded scene to debug.log ──────
    //
    // A one-shot reconnaissance dump for the pathing stage: logs every static
    // scene entry (placeables/areas/paths) and every dynamic actor with its
    // CONFIG NAME + world position, so we can eyeball the real config strings
    // (crown vs heat, lava configs, the DungeonLift, trap configs) in a couple
    // of dungeons before building the pathing map. All reflective — obfuscated
    // method names are discovered structurally so this survives version bumps.

    private static java.lang.reflect.Method cachedSceneModelMethod = null; // view -> TudeySceneModel
    private static java.lang.reflect.Method cachedEntriesMethod = null;    // model -> Collection<Entry>
    private static java.lang.reflect.Method cachedEntryPosMethod = null;   // Entry.aw(ConfigManager) -> Vector2f

    /**
     * Dumps the current scene (statics + actors) to debug.log. controller =
     * dungeon client. Buffers into one local StringBuilder and writes once — a
     * dense scene is hundreds of lines, and per-line file opens would thrash the
     * disk and hitch the game (this runs on the tick thread).
     */
    public static void dumpScene(Object controller) {
        StringBuilder sb = new StringBuilder();
        try {
            if (controller == null) {
                writeLog("[scenescan] no controller\n");
                return;
            }
            Object view = Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            if (view == null) {
                writeLog("[scenescan] no tudey view (not in a scene?)\n");
                return;
            }
            ln(sb, "[scenescan] ================= SCENE DUMP @ " + new java.util.Date() + " =================");
            ln(sb, "[scenescan] floor info: " + floorInfoSummary());

            // --- Static scene model: placeables / areas / paths (known at load) ---
            Object model = sceneModelOf(view);
            if (model == null) {
                ln(sb, "[scenescan] scene model unavailable");
            } else {
                Object cfgmgr = null;
                try {
                    cfgmgr = model.getClass().getMethod("getConfigManager").invoke(model);
                } catch (Exception e) {
                }
                java.util.Collection<?> entries = null;
                try {
                    entries = entriesCollection(model);
                } catch (Exception e) {
                    ln(sb, "[scenescan] entries error: " + e);
                }
                if (entries == null) {
                    ln(sb, "[scenescan] scene entries unavailable");
                } else {
                    ln(sb, "[scenescan] --- scene entries (placeables/areas/paths): " + entries.size() + " ---");
                    java.util.TreeMap<String, Integer> tally = new java.util.TreeMap<String, Integer>();
                    int n = 0;
                    for (Object e : entries) {
                        if (e == null)
                            continue;
                        String type = e.getClass().getSimpleName();
                        String name = entryConfigName(e);
                        float[] p = entryPos(e, cfgmgr);
                        String pos = (p == null) ? "(?,?)" : ("(" + Reflect.fmt(p[0]) + ", " + Reflect.fmt(p[1]) + ")");
                        Integer cf = Reflect.readIntFieldNullable(e, "_collisionFlags");
                        String extra = (cf == null) ? "" : (" collFlags=" + cf);
                        ln(sb, "[scenescan]   " + type + " | " + name + " @ " + pos + extra);
                        String key = type + " | " + name;
                        tally.put(key, (tally.containsKey(key) ? tally.get(key) : 0) + 1);
                        if (++n >= 4000) {
                            ln(sb, "[scenescan]   ...truncated at 4000 entries");
                            break;
                        }
                    }
                    ln(sb, "[scenescan] --- entry tally (type | config) ---");
                    for (java.util.Map.Entry<String, Integer> t : tally.entrySet())
                        ln(sb, "[scenescan]   x" + t.getValue() + "  " + t.getKey());
                }
                dumpTileSummary(sb, model);
                dumpPlaceables(sb, model);
                dumpWalkGrid(sb, model, view);
            }

            // --- Dynamic actors (traps/lift/pickups/monsters/blocks/...) ---
            dumpActors(sb, controller, view);

            ln(sb, "[scenescan] ================= END DUMP =================");
        } catch (Exception e) {
            ln(sb, "[scenescan] error: " + e);
        }
        writeLog(sb.toString());
    }

    /** Dumps the pawn's current map coordinates to debug.log (multibox Ctrl+J). */
    public static void dumpPlayerPos(Object controller) {
        try {
            if (controller == null) {
                writeLogAlways("[dumppos] no controller");
                return;
            }
            Object view = Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            Integer pawnId = Reflect.readIntFieldNullable(controller, MappingsNames.PAWN_ID_FIELD);
            if (view == null || pawnId == null) {
                writeLogAlways("[dumppos] no view/pawn (not in a scene?)");
                return;
            }
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null) {
                writeLogAlways("[dumppos] actor map unavailable");
                return;
            }
            Object wrapper = actorMap.getClass().getMethod("get", int.class).invoke(actorMap, pawnId.intValue());
            Object actor = (wrapper == null) ? null : Reflect.getWrappedActor(wrapper);
            float[] p = (actor == null) ? null : Reflect.actorPos(actor);
            if (p == null) {
                writeLogAlways("[dumppos] pawn position unavailable (pawnId=" + pawnId + ")");
                return;
            }
            writeLogAlways("[dumppos] player @ (" + Reflect.fmt(p[0]) + ", " + Reflect.fmt(p[1]) + ")  pawnId=" + pawnId);
        } catch (Exception e) {
            writeLogAlways("[dumppos] error: " + e);
        }
    }

    /** Enumerates the actor map and appends each actor's class, config name, position, and key state. */
    private static void dumpActors(StringBuilder sb, Object controller, Object view) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null) {
                ln(sb, "[scenescan] actor map unavailable");
                return;
            }
            Integer pawnId = Reflect.readIntFieldNullable(controller, MappingsNames.PAWN_ID_FIELD);
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            ln(sb, "[scenescan] --- actors ---");
            java.util.TreeMap<String, Integer> tally = new java.util.TreeMap<String, Integer>();
            int n = 0;
            for (Object wrapper : (Iterable<?>) values) {
                Object actor = Reflect.getWrappedActor(wrapper);
                if (actor == null)
                    continue;
                String type = actor.getClass().getSimpleName();
                String name = Reflect.actorConfigName(actor);
                float[] p = Reflect.actorPos(actor);
                String pos = (p == null) ? "(?,?)" : ("(" + Reflect.fmt(p[0]) + ", " + Reflect.fmt(p[1]) + ")");
                StringBuilder extra = new StringBuilder();
                Integer id = Reflect.readIntFieldNullable(actor, "_id");
                if (id != null && pawnId != null && id.intValue() == pawnId.intValue())
                    extra.append(" [SELF]");
                Object st = Reflect.readObjectFieldNullable(actor, "_state"); // Trap.State
                if (st != null)
                    extra.append(" state=").append(st);
                Float hp = Reflect.readFloatFieldNullable(actor, "_healthPct"); // Monster
                if (hp != null) {
                    extra.append(" hp=").append(Reflect.fmt(hp));
                    // SNARBY stun check: dump any ConfigReference[] getters (status
                    // conditions) so we can see whether a bell-stunned Snarbolax exposes a
                    // readable "stun" condition, and via which getter (for the SNARBY gate).
                    String conds = Reflect.actorConfigRefArrays(actor);
                    if (!conds.isEmpty())
                        extra.append(" conds{").append(conds).append("}");
                }
                Object dp = Reflect.readObjectFieldNullable(actor, "_dropPoint"); // Lift
                if (dp != null) {
                    float[] d = Reflect.vec2xy(dp);
                    if (d != null)
                        extra.append(" drop=(").append(Reflect.fmt(d[0])).append(", ").append(Reflect.fmt(d[1])).append(")");
                }
                Integer faction = Reflect.readIntFieldNullable(actor, "_faction"); // Lift / factioned actors
                if (faction != null)
                    extra.append(" faction=").append(faction);
                Object po = Reflect.readObjectFieldNullable(actor, "playerOids"); // Pickup
                if (po instanceof int[])
                    extra.append(" playerOids=").append(java.util.Arrays.toString((int[]) po));
                ln(sb, "[scenescan]   " + type + " | " + name + " @ " + pos + extra);
                String key = type + " | " + name;
                tally.put(key, (tally.containsKey(key) ? tally.get(key) : 0) + 1);
                if (++n >= 4000) {
                    ln(sb, "[scenescan]   ...truncated at 4000 actors");
                    break;
                }
            }
            ln(sb, "[scenescan] --- actor tally (type | config) ---");
            for (java.util.Map.Entry<String, Integer> t : tally.entrySet())
                ln(sb, "[scenescan]   x" + t.getValue() + "  " + t.getKey());
        } catch (Exception e) {
            ln(sb, "[scenescan] actors error: " + e);
        }
    }

    /** Best-effort tile-layer summary: grid bounds + a per-config cell tally (for spotting hazard tiles like lava). */
    private static void dumpTileSummary(StringBuilder sb, Object model) {
        try {
            Object tiles = Reflect.readObjectFieldNullable(model, "_tiles");
            if (tiles == null) {
                ln(sb, "[scenescan] no _tiles field");
                return;
            }
            Object es = tiles.getClass().getMethod("entrySet").invoke(tiles);
            // Discover aP(int,int) -> TileEntry to resolve a cell's config name.
            java.lang.reflect.Method tileAt = null;
            for (java.lang.reflect.Method m : model.getClass().getMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class
                        && m.getReturnType().getName().endsWith("$TileEntry")) {
                    tileAt = m;
                    break;
                }
            }
            int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE, count = 0;
            java.util.HashMap<Integer, String> valName = new java.util.HashMap<Integer, String>();
            java.util.TreeMap<String, Integer> tally = new java.util.TreeMap<String, Integer>();
            for (Object o : (Iterable<?>) es) {
                java.util.Map.Entry<?, ?> me = (java.util.Map.Entry<?, ?>) o;
                Object coord = me.getKey();
                int cx = coord.getClass().getField("x").getInt(coord);
                int cy = coord.getClass().getField("y").getInt(coord);
                if (cx < minx) minx = cx;
                if (cy < miny) miny = cy;
                if (cx > maxx) maxx = cx;
                if (cy > maxy) maxy = cy;
                count++;
                Integer val = (me.getValue() instanceof Integer) ? (Integer) me.getValue() : Integer.valueOf(0);
                String nm = valName.get(val);
                if (nm == null) {
                    nm = "raw:" + val;
                    if (tileAt != null) {
                        try {
                            Object te = tileAt.invoke(model, cx, cy);
                            if (te != null)
                                nm = entryConfigName(te);
                        } catch (Exception ig) {
                        }
                    }
                    valName.put(val, nm);
                }
                tally.put(nm, (tally.containsKey(nm) ? tally.get(nm) : 0) + 1);
            }
            ln(sb, "[scenescan] --- tiles: " + count + " cells, bounds x[" + minx + ".." + maxx
                    + "] y[" + miny + ".." + maxy + "] ---");
            for (java.util.Map.Entry<String, Integer> t : tally.entrySet())
                ln(sb, "[scenescan]   tile x" + t.getValue() + "  " + t.getKey());
        } catch (Exception e) {
            ln(sb, "[scenescan] tile summary error: " + e);
        }
    }

    /**
     * Coarse walkability class of a tile config, from its name. Tilesets label
     * non-walkable tiles explicitly ("no-walk", "Wall", "Edge"); standable ground is
     * any "…/Floor…" tile (minus the "no-walk" grass), and any "…/Stairs…" or
     * Clockworks "…/Ramp…" is walkable too. Matches WITHOUT a trailing slash so a bare
     * config like Clockworks/Floor or Clockworks/Ramp (mission lobby) also classifies
     * — the gloaming-wildwoods tiles were "…/Floor/<variant>", other tilesets end at
     * "…/Floor". Walls/edges are non-walkable whether classed '#'/'E'/'?', so they need
     * no per-tileset handling. 'F' walkable, ',' no-walk grass, '#' wall, 'E' edge,
     * 'S' stairs/ramp, '?' unclassified.
     */
    static char tileWalkClass(String cfg) {
        if (cfg == null)
            return '?';
        String c = cfg.toLowerCase(Locale.ROOT);
        if (c.contains("no-walk"))
            return ',';
        // "Floor/Wall runner" tiles are WALKABLE floor strips running along walls,
        // not walls (user, sovereign_slime) — let them fall through to the floor match.
        if (c.contains("/wall") && !c.contains("wall runner"))
            return '#';
        if (c.contains("edge/"))
            return 'E';
        if (c.contains("/stairs") || c.contains("/ramp"))
            return 'S';
        if (c.contains("/floor") || c.contains("haven/natural/") && !c.contains("/floor edge"))
            return 'F';
        return '?';
    }

    /**
     * Renders an ASCII walkability grid from the LIVE scene model, classified by
     * tile CONFIG NAME.
     * 'F' = walkable floor, ',' = no-walk grass, '#' = wall, 'E' = edge, 'S' =
     * stairs, '?' = unclassified, ' ' = void (no tile). Full map (iterates all of
     * _tiles, no truncation). Rows are high-y to low-y, labelled with y; x l→r.
     */
    private static void dumpWalkGrid(StringBuilder sb, Object model, Object view) {
        try {
            Object tiles = Reflect.readObjectFieldNullable(model, "_tiles");
            if (tiles == null) {
                ln(sb, "[scenescan] no _tiles for walk grid");
                return;
            }
            Object es = tiles.getClass().getMethod("entrySet").invoke(tiles);
            // Discover aP(int,int)->TileEntry to resolve a cell's config name.
            java.lang.reflect.Method tileAt = null;
            for (java.lang.reflect.Method m : model.getClass().getMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class
                        && m.getReturnType().getName().endsWith("$TileEntry")) {
                    tileAt = m;
                    break;
                }
            }
            java.util.HashMap<Long, Integer> cell = new java.util.HashMap<Long, Integer>();
            int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
            for (Object o : (Iterable<?>) es) {
                java.util.Map.Entry<?, ?> me = (java.util.Map.Entry<?, ?>) o;
                Object c = me.getKey();
                int x = c.getClass().getField("x").getInt(c);
                int y = c.getClass().getField("y").getInt(c);
                cell.put((((long) x) << 32) ^ (y & 0xffffffffL),
                        (me.getValue() instanceof Integer) ? (Integer) me.getValue() : Integer.valueOf(0));
                if (x < minx) minx = x;
                if (y < miny) miny = y;
                if (x > maxx) maxx = x;
                if (y > maxy) maxy = y;
            }
            if (cell.isEmpty()) {
                ln(sb, "[scenescan] empty tile map");
                return;
            }
            int w = maxx - minx + 1, h = maxy - miny + 1;
            if ((long) w * h > 120000) {
                ln(sb, "[scenescan] walk grid too large (" + w + "x" + h + "); skipped");
                return;
            }
            java.util.HashMap<Integer, String> valName = new java.util.HashMap<Integer, String>();
            // class char -> (config -> count), for the legend.
            java.util.TreeMap<Character, java.util.TreeMap<String, Integer>> byClass =
                    new java.util.TreeMap<Character, java.util.TreeMap<String, Integer>>();
            java.util.HashSet<Long> blockedObj = placeableBlockedCells(model);
            java.util.HashSet<Long> hazardInflated = hazardInflatedCells(model, HAZARD_INFLATION);
            java.util.HashSet<Long> actorBlk = actorBlockedCells(view);
            java.util.HashSet<Long> trapCells = new java.util.HashSet<Long>(buildTrapCells(model).keySet());
            java.util.HashSet<Long> ghostCells = ghostBlockCells(model, view);
            ln(sb, "[scenescan] --- WALK GRID  x[" + minx + ".." + maxx + "] y[" + miny + ".." + maxy
                    + "]  (F=floor ','=no-walk #=wall E=edge S=stairs O=placeable H=hazard-inflation B=block"
                    + " G=ghost block(shoot to open its cluster) T=spike-trap(walkable) ?=other ' '=void) ---");
            for (int y = maxy; y >= miny; y--) {
                StringBuilder row = new StringBuilder();
                for (int x = minx; x <= maxx; x++) {
                    long kk = (((long) x) << 32) ^ (y & 0xffffffffL);
                    if (ghostCells.contains(Long.valueOf(kk))) {
                        row.append('G'); // the ghost block itself — routing within 4 tiles opens its cluster
                        continue;
                    }
                    if (trapCells.contains(Long.valueOf(kk))) {
                        row.append('T'); // spike-trap footprint — walkable (HAZARD_MOVETO times the crossing)
                        continue;
                    }
                    Integer tv = cell.get(kk);
                    if (tv == null) {
                        row.append(' ');
                        continue;
                    }
                    if (blockedObj.contains(Long.valueOf(kk))) {
                        row.append('O'); // collision-bearing placeable
                        continue;
                    }
                    if (hazardInflated.contains(Long.valueOf(kk))) {
                        row.append('H'); // bramble inflation margin (blocked for hurtbox clearance)
                        continue;
                    }
                    if (actorBlk.contains(Long.valueOf(kk))) {
                        row.append('B'); // block actor (unbreakable/stone/shrub)
                        continue;
                    }
                    String nm = valName.get(tv);
                    if (nm == null) {
                        nm = "raw:" + tv;
                        if (tileAt != null) {
                            try {
                                Object te = tileAt.invoke(model, x, y);
                                if (te != null)
                                    nm = entryConfigName(te);
                            } catch (Exception ig) {
                            }
                        }
                        valName.put(tv, nm);
                    }
                    char cls = tileWalkClass(nm);
                    java.util.TreeMap<String, Integer> mp = byClass.get(cls);
                    if (mp == null) {
                        mp = new java.util.TreeMap<String, Integer>();
                        byClass.put(cls, mp);
                    }
                    mp.put(nm, (mp.containsKey(nm) ? mp.get(nm) : 0) + 1);
                    row.append(cls);
                }
                ln(sb, String.format(Locale.ROOT, "[grid y=%4d] %s", y, row.toString()));
            }
            ln(sb, "[scenescan] --- grid legend (class -> configs) ---");
            for (java.util.Map.Entry<Character, java.util.TreeMap<String, Integer>> ce : byClass.entrySet()) {
                ln(sb, "[scenescan]  class '" + ce.getKey() + "':");
                for (java.util.Map.Entry<String, Integer> t : ce.getValue().entrySet())
                    ln(sb, "[scenescan]     x" + t.getValue() + "  " + t.getKey());
            }
        } catch (Exception e) {
            ln(sb, "[scenescan] walk grid error: " + e);
        }
    }

    /** Diagnostic: lists placeable entries with config, position, collision flags, and blocked footprint. */
    private static void dumpPlaceables(StringBuilder sb, Object model) {
        try {
            Object cfgmgr = model.getClass().getMethod("getConfigManager").invoke(model);
            java.util.Collection<?> entries = entriesCollection(model);
            if (entries == null) {
                ln(sb, "[scenescan] no entries for placeables");
                return;
            }
            int n = 0, blocking = 0;
            java.util.TreeMap<String, Integer> tally = new java.util.TreeMap<String, Integer>();
            ln(sb, "[scenescan] --- placeables (config @ pos  collFlags  blocked-cells) ---");
            for (Object e : entries) {
                if (e == null || !"PlaceableEntry".equals(e.getClass().getSimpleName()))
                    continue;
                n++;
                String nm = entryConfigName(e);
                float[] p = entryPos(e, cfgmgr);
                String pos = (p == null) ? "(?,?)" : ("(" + Reflect.fmt(p[0]) + "," + Reflect.fmt(p[1]) + ")");
                Integer cf = Reflect.readIntFieldNullable(e, "_collisionFlags");
                boolean blocks = placeableIsObstacle(e);
                if (blocks)
                    blocking++;
                StringBuilder fp = new StringBuilder();
                if (blocks)
                    for (int[] c : entryFootprintCells(e, cfgmgr))
                        fp.append(" ").append(c[0]).append(",").append(c[1]);
                if (n <= 3000)
                    ln(sb, "[scenescan]   " + nm + " @ " + pos + "  collFlags=" + cf
                            + (blocks ? "  BLOCK[" + fp.toString().trim() + "]" : "  pass"));
                tally.put(nm, (tally.containsKey(nm) ? tally.get(nm) : 0) + 1);
            }
            ln(sb, "[scenescan] --- placeables: " + n + " total, " + blocking + " blocking ---");
            for (java.util.Map.Entry<String, Integer> t : tally.entrySet())
                ln(sb, "[scenescan]   x" + t.getValue() + "  " + t.getKey());
        } catch (Exception e) {
            ln(sb, "[scenescan] placeables error: " + e);
        }
    }

    /** Appends a line to the scan buffer. */
    private static void ln(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }

    /** One-shot append of the whole scan buffer to debug.log (single file open). */
    private static void writeLog(String text) {
        try {
            new File(DIR).mkdirs();
            FileWriter fw = new FileWriter(DIR + "/debug.log", true);
            fw.write(text);
            fw.close();
        } catch (Exception e) {
        }
    }

    /** view -> TudeySceneModel (the 0-arg method returning it, e.g. abJ()). */
    private static Object sceneModelOf(Object view) {
        try {
            if (cachedSceneModelMethod == null || !cachedSceneModelMethod.getDeclaringClass().isInstance(view)) {
                for (java.lang.reflect.Method m : view.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType().getName().endsWith(".TudeySceneModel")) {
                        cachedSceneModelMethod = m;
                        break;
                    }
                }
            }
            return (cachedSceneModelMethod == null) ? null : cachedSceneModelMethod.invoke(view);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The routine-file key for the current floor: the scene model name (SceneModel.name,
     * unobfuscated) with its translation prefix stripped, e.g.
     * "%dungeon:m.gloaming_wildwoods_path" -> "gloaming_wildwoods_path". Stable per
     * level layout. Null if not in a loaded scene. The seam future auto-floor-selection
     * hangs off.
     */
    static String resolveFloorKey() { // package-private: SpriteFeeder's lobby gate uses it
        try {
            Object view = (dungeonClient == null) ? null
                    : Reflect.readObjectFieldNullable(dungeonClient, MappingsNames.VIEW_FIELD);
            Object model = (view == null) ? null : sceneModelOf(view);
            Object nm = (model == null) ? null : Reflect.readObjectFieldNullable(model, "name");
            if (nm == null)
                return null;
            String s = nm.toString().trim();
            if (s.isEmpty())
                return null;
            int colon = s.lastIndexOf(':');
            if (colon >= 0)
                s = s.substring(colon + 1); // drop the "%dungeon:" namespace
            if (s.startsWith("m."))
                s = s.substring(2); // drop the message-key prefix
            s = sanitizeFilename(s);
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** The current floor number (LevelPartyObject.floor, 1-based), or -1. */
    private static int resolveFloorNumber() {
        try {
            Object lpo = Mappings.getLevelPartyObject(_cachedCtx);
            if (lpo != null) {
                Integer f = Reflect.readIntFieldNullable(lpo, "floor");
                if (f != null)
                    return f.intValue();
            }
        } catch (Exception e) {
        }
        return -1;
    }

    /** Every field that might identify the current floor, for eyeballing which holds the display name. */
    private static String floorInfoSummary() {
        StringBuilder sb = new StringBuilder();
        Object model = null;
        try {
            Object view = (dungeonClient == null) ? null
                    : Reflect.readObjectFieldNullable(dungeonClient, MappingsNames.VIEW_FIELD);
            model = (view == null) ? null : sceneModelOf(view);
        } catch (Exception e) {
        }
        sb.append("sceneModel.name=").append(model == null ? "?" : Reflect.readObjectFieldNullable(model, "name"));
        try {
            Object lpo = Mappings.getLevelPartyObject(_cachedCtx);
            if (lpo == null) {
                sb.append("  LevelPartyObject=null");
            } else {
                sb.append("  floor=").append(Reflect.readIntFieldNullable(lpo, "floor"));
                sb.append("  actualFloor=").append(Reflect.readIntFieldNullable(lpo, "actualFloor"));
                sb.append("  actualGateId=").append(Reflect.readIntFieldNullable(lpo, "actualGateId"));
                sb.append("  returnFloor=").append(Reflect.readIntFieldNullable(lpo, "returnFloor"));
                sb.append("  pendingFloorName=").append(Reflect.readObjectFieldNullable(lpo, "pendingFloorName"));
                sb.append("  floorType=").append(Reflect.readObjectFieldNullable(lpo, "floorType"));
            }
        } catch (Exception e) {
            sb.append("  LPO-err=").append(e);
        }
        return sb.toString();
    }

    /** Sanitizes a floor name into a safe routine-file basename (keeps spaces + case). */
    private static String sanitizeFilename(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** model -> Collection<Entry> (the 0-arg method whose generic element type is TudeySceneModel$Entry, e.g. acM()). */
    /** The scene's placeable/tile entry count, or -1 if unreadable — the walk-grid cache key. */
    private static int entryCountOf(Object model) {
        try {
            java.util.Collection<?> c = entriesCollection(model);
            return (c == null) ? -1 : c.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static java.util.Collection<?> entriesCollection(Object model) throws Exception {
        if (cachedEntriesMethod == null || !cachedEntriesMethod.getDeclaringClass().isInstance(model)) {
            for (java.lang.reflect.Method m : model.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || !java.util.Collection.class.isAssignableFrom(m.getReturnType()))
                    continue;
                java.lang.reflect.Type gt = m.getGenericReturnType();
                if (gt instanceof java.lang.reflect.ParameterizedType) {
                    java.lang.reflect.Type[] a = ((java.lang.reflect.ParameterizedType) gt).getActualTypeArguments();
                    if (a.length == 1 && a[0].getTypeName().contains("TudeySceneModel$Entry")) {
                        cachedEntriesMethod = m;
                        break;
                    }
                }
            }
        }
        if (cachedEntriesMethod == null)
            return null;
        Object r = cachedEntriesMethod.invoke(model);
        return (r instanceof java.util.Collection) ? (java.util.Collection<?>) r : null;
    }

    /** Entry.aw(ConfigManager) -> Vector2f (world position/center of any entry). */
    private static float[] entryPos(Object entry, Object cfgmgr) {
        try {
            if (cfgmgr == null)
                return null;
            if (cachedEntryPosMethod == null || !cachedEntryPosMethod.getDeclaringClass().isInstance(entry)) {
                for (java.lang.reflect.Method m : entry.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0].getName().endsWith(".ConfigManager")
                            && m.getReturnType().getName().endsWith(".Vector2f")) {
                        cachedEntryPosMethod = m;
                        break;
                    }
                }
            }
            if (cachedEntryPosMethod == null)
                return null;
            return Reflect.vec2xy(cachedEntryPosMethod.invoke(entry, cfgmgr));
        } catch (Exception e) {
            return null;
        }
    }

    /** Entry.getReference().getName() — the config path string (real method names). */
    private static String entryConfigName(Object entry) {
        try {
            Object ref = entry.getClass().getMethod("getReference").invoke(entry);
            if (ref == null)
                return "null";
            Object nm = ref.getClass().getMethod("getName").invoke(ref);
            return (nm == null) ? "null" : nm.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    // ── Loot mode (multibox Ctrl+K, MAIN only) ────────────────────────────────
    //
    // Plans and drives a shortest closed loot-sweep. Runs each tick on the main
    // while isLootMode is set. First tick plans the route (captures the start,
    // gathers target pickups within 10 tiles, covers them into waypoints using the
    // 2-tile magnet, orders them with an exact TSP), then walks the main through
    // the waypoints and back to the start. Alts trail via auto-follow (assumed
    // on). Movement uses the same DOWN-set path as auto-follow; the poll's
    // key-injection gate is widened to the main while isLootMode is set.

    public static void tickLootMode(Object controller) {
        try {
            if (controller == null)
                return;
            Object view = Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            Integer pawnId = Reflect.readIntFieldNullable(controller, MappingsNames.PAWN_ID_FIELD);
            if (view == null || pawnId == null) {
                clearMovementKeys();
                return;
            }
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null) {
                clearMovementKeys();
                return;
            }
            Object wrapper = actorMap.getClass().getMethod("get", int.class).invoke(actorMap, pawnId.intValue());
            Object myActor = (wrapper == null) ? null : Reflect.getWrappedActor(wrapper);
            float[] mp = (myActor == null) ? null : Reflect.actorPos(myActor);
            if (mp == null) {
                clearMovementKeys();
                return;
            }

            if (!lootPlanned) {
                lootStartX = mp[0];
                lootStartY = mp[1];
                planLootRoute(view, mp[0], mp[1]);
                lootPlanned = true;
            }
            if (lootRouteX == null || lootRouteX.length == 0) {
                clearMovementKeys();
                if (!lootDoneLogged) {
                    debugFile("[loot] nothing to collect; staying put");
                    lootDoneLogged = true;
                }
                return;
            }

            long now = System.currentTimeMillis();
            int prevIdx = lootRouteIdx;
            // Advance past any waypoints already reached (the magnet may have
            // collected several en route; skipping keeps the main moving).
            while (lootRouteIdx < lootRouteX.length) {
                float ddx = lootRouteX[lootRouteIdx] - mp[0];
                float ddy = lootRouteY[lootRouteIdx] - mp[1];
                if (ddx * ddx + ddy * ddy <= lootRouteArriveSq[lootRouteIdx])
                    lootRouteIdx++;
                else
                    break;
            }
            // Stuck guard: if a waypoint stays unreached past its deadline (blocked
            // by geometry we can't path around), skip it so the run can finish.
            if (lootRouteIdx == prevIdx && lootRouteIdx < lootRouteX.length
                    && lootWaypointDeadline != 0L && now > lootWaypointDeadline) {
                debugFile("[loot] waypoint " + lootRouteIdx + " unreachable, skipping");
                lootRouteIdx++;
            }
            if (lootRouteIdx != prevIdx || lootWaypointDeadline == 0L) {
                lootWaypointDeadline = now + LOOT_WAYPOINT_TIMEOUT_MS; // reset timer for the new target
            }
            if (lootRouteIdx >= lootRouteX.length) {
                clearMovementKeys(); // finished; back at the start. Idle (stays ON until toggled off).
                blockClearStop();
                if (!lootDoneLogged) {
                    debugFile("[loot] route complete, returned to start");
                    lootDoneLogged = true;
                }
                return;
            }

            // TRAP-CONSCIOUS SWEEP (user-directed, with HAZARD_LOOT): never step onto
            // an unsafe spike trap between loot waypoints — wait at its near edge like
            // HAZARD_MOVETO does, then cross when it reads DOWN. A no-op on floors
            // without traps (empty trap-cell map). The hold refreshes the waypoint
            // deadline so the unreachable-skip can't fire mid-wait.
            ensureTrapCells(view);
            updateTrapStates(view, now);
            if (shouldHoldForTrap(mp, now, lootPath)) {
                clearMovementKeys();
                tickBreakableClear(controller, view, mp, lootPath); // keep clearing from safe ground
                lootWaypointDeadline = now + LOOT_WAYPOINT_TIMEOUT_MS;
                return;
            }

            // Path to the current loot waypoint via A* (dodges brambles / solid
            // props / blocks), advancing when arrived or if it's unreachable. The
            // arrival radius is tight for step-on drops, loose for magnet clusters.
            boolean lootArrived = driveAlongPath(view, mp, lootRouteX[lootRouteIdx], lootRouteY[lootRouteIdx], lootPath,
                    "loot", lootRouteArriveSq[lootRouteIdx]);
            // Shoot breakable blocks (shrubs) sitting on the loot path instead of
            // being stuck against them; hold off the unreachable-skip while clearing.
            tickBreakableClear(controller, view, mp, lootPath);
            if (blockClearActive)
                lootWaypointDeadline = now + LOOT_WAYPOINT_TIMEOUT_MS;
            if (lootArrived)
                lootRouteIdx++;
        } catch (Exception e) {
            clearMovementKeys();
            if (debug)
                debugFile("[loot] error: " + e);
        }
    }

    /**
     * Plans the loot route. Magnet pickups (heat/crowns) are covered into shared
     * waypoints (one stop collects a 2-tile-magnet cluster, loose arrival). Step-on
     * pickups (Item/Drop materials, no magnet) each become their own waypoint with
     * a TIGHT arrival so the character actually lands on the tile. The two waypoint
     * sets are TSP-ordered together, then the return-to-start is appended.
     */
    private static void planLootRoute(Object view, float sx, float sy) {
        java.util.List<float[]> pts = gatherLootPickups(view, sx, sy); // {x,y,stepOn}
        pts = dropDetours(view, sx, sy, pts, "loot", 0f); // skip winding/unreachable pickups
        lootRouteIdx = 0;
        lootWaypointDeadline = 0L;
        lootPath.tgtX = Float.NaN; // force an A* replan for the new route
        java.util.List<float[]> magnet = new java.util.ArrayList<float[]>();
        java.util.List<float[]> stepOn = new java.util.ArrayList<float[]>();
        for (float[] p : pts)
            (p[2] != 0f ? stepOn : magnet).add(p);
        if (magnet.isEmpty() && stepOn.isEmpty()) {
            lootRouteX = new float[0];
            lootRouteY = new float[0];
            lootRouteArriveSq = new float[0];
            debugFile("[loot] no target loot within 10 tiles");
            return;
        }
        // Waypoints: magnet cover centres (loose) followed by step-on exact spots (tight).
        float[][] magWps = magnet.isEmpty() ? new float[0][] : coverPickups(magnet);
        int m = magWps.length, so = stepOn.size();
        float[][] wps = new float[m + so][];
        float[] wArr = new float[m + so];
        for (int i = 0; i < m; i++) {
            wps[i] = magWps[i];
            wArr[i] = LOOT_ARRIVE_SQ;
        }
        for (int i = 0; i < so; i++) {
            wps[m + i] = new float[] { stepOn.get(i)[0], stepOn.get(i)[1] };
            wArr[m + i] = LOOT_STEP_ARRIVE_SQ;
        }
        int[] order = tspOrder(sx, sy, wps);
        int n = order.length;
        float[] rx = new float[n + 1];
        float[] ry = new float[n + 1];
        float[] ra = new float[n + 1];
        for (int k = 0; k < n; k++) {
            rx[k] = wps[order[k]][0];
            ry[k] = wps[order[k]][1];
            ra[k] = wArr[order[k]];
        }
        rx[n] = sx; // return to start
        ry[n] = sy;
        ra[n] = LOOT_ARRIVE_SQ;
        lootRouteX = rx;
        lootRouteY = ry;
        lootRouteArriveSq = ra;
        debugFile("[loot] planned " + pts.size() + " pickups (" + magnet.size() + " magnet, " + so
                + " step-on) -> " + n + " waypoints (+return)");
    }

    /**
     * Drops every destination ({@code float[]{x, y, ...}}) in {@code pts} that is a
     * DETOUR from the sweep origin (sx,sy) — walking path longer than
     * LOOT_DETOUR_RATIO x straight-line (+ SLACK) — or unreachable, and logs what
     * it dropped. Destinations within sqrt(exemptSq) of the origin are kept
     * untested (0 = none): TREASURESWEEP passes its firing range, since a block
     * already in range is shot without any walking. Returns {@code pts} unchanged
     * when there is no scene model yet — it never filters blind.
     */
    private static java.util.List<float[]> dropDetours(Object view, float sx, float sy,
            java.util.List<float[]> pts, String who, float exemptSq) {
        Object model = sceneModelOf(view);
        if (model == null || pts.isEmpty())
            return pts;
        java.util.List<float[]> keep = new java.util.ArrayList<float[]>(pts.size());
        int unreachable = 0, detour = 0;
        for (float[] p : pts) {
            float dx = p[0] - sx, dy = p[1] - sy;
            float straightSq = dx * dx + dy * dy;
            if (exemptSq > 0f && straightSq <= exemptSq) {
                keep.add(p);
                continue;
            }
            float straight = (float) Math.sqrt(straightSq);
            float walk = walkDistance(model, view, sx, sy, p[0], p[1]);
            if (walk < 0f) {
                unreachable++;
                continue;
            }
            if (walk > LOOT_DETOUR_RATIO * straight + LOOT_DETOUR_SLACK) {
                detour++;
                if (debug)
                    debugFile("[" + who + "] detour skip (" + Reflect.fmt(p[0]) + "," + Reflect.fmt(p[1])
                            + "): walk " + Reflect.fmt(walk) + " > " + LOOT_DETOUR_RATIO + "x straight "
                            + Reflect.fmt(straight));
                continue;
            }
            keep.add(p);
        }
        if (unreachable + detour > 0)
            debugFile("[" + who + "] detour filter dropped " + detour + " winding + " + unreachable
                    + " unreachable of " + pts.size() + " destination(s)");
        return keep;
    }

    /**
     * Real walking distance (A* polyline length, tiles) from (sx,sy) to (gx,gy) on
     * the live grid — the same path the driver would follow — or -1 if unreachable.
     * Plan-time only: one A* per call.
     */
    private static float walkDistance(Object model, Object view, float sx, float sy, float gx, float gy) {
        float[][] wp = findPathWorld(model, view, sx, sy, gx, gy);
        if (wp == null || wp.length == 0)
            return -1f;
        float len = 0f, px = sx, py = sy;
        for (int i = 0; i < wp.length; i++) {
            float dx = wp[i][0] - px, dy = wp[i][1] - py;
            len += (float) Math.sqrt(dx * dx + dy * dy);
            px = wp[i][0];
            py = wp[i][1];
        }
        return len;
    }

    /** Target pickups within 10 tiles of (sx,sy), each as {x, y, stepOn} (stepOn=1 for no-magnet drops). */
    private static java.util.List<float[]> gatherLootPickups(Object view, float sx, float sy) {
        java.util.List<float[]> out = new java.util.ArrayList<float[]>();
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return out;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object wrapper : (Iterable<?>) values) {
                Object actor = Reflect.getWrappedActor(wrapper);
                if (actor == null)
                    continue;
                if (!"Pickup".equals(actor.getClass().getSimpleName()))
                    continue;
                String name = Reflect.actorConfigName(actor);
                if (!isTargetLoot(name))
                    continue;
                float[] p = Reflect.actorPos(actor);
                if (p == null)
                    continue;
                float dx = p[0] - sx, dy = p[1] - sy;
                if (dx * dx + dy * dy <= LOOT_SCAN_RADIUS_SQ)
                    out.add(new float[] { p[0], p[1], isStepOnLoot(name) ? 1f : 0f });
            }
        } catch (Exception e) {
            if (debug)
                debugFile("[loot] gather error: " + e);
        }
        return out;
    }

    /**
     * True for heat embers, silver/silver-gold/gold crowns, and material drops
     * ("Item/Drop" — crystals, Orbs of Alchemy, etc.). Crowns are matched as "crown
     * but not copper" — that captures the three non-copper tiers exactly (and avoids
     * "Copper-Silver Crown", which contains "silver").
     */
    private static boolean isTargetLoot(String cfg) {
        if (cfg == null)
            return false;
        String s = cfg.toLowerCase(Locale.ROOT);
        if (s.contains("/heat/"))
            return true;
        if (s.contains("crown") && !s.contains("copper"))
            return true;
        if (s.contains("item/drop"))
            return true; // materials (crystals, Orbs of Alchemy, …)
        return false;
    }

    /**
     * True for pickups that have NO magnet pull ("Item/Drop" materials) — the
     * character must physically step on the tile, so they must be visited exactly
     * rather than covered as a cluster.
     */
    private static boolean isStepOnLoot(String cfg) {
        return cfg != null && cfg.toLowerCase(Locale.ROOT).contains("item/drop");
    }

    /**
     * Covers pickups into collection waypoints using the magnet: a waypoint
     * collects every pickup within LOOT_COVER_RADIUS of it. Greedy max-coverage
     * with pickup positions AND pairwise midpoints as candidate centers, so a
     * single stop between two nearby pickups can grab both.
     */
    private static float[][] coverPickups(java.util.List<float[]> pts) {
        java.util.List<float[]> remaining = new java.util.ArrayList<float[]>(pts);
        java.util.List<float[]> waypoints = new java.util.ArrayList<float[]>();
        float r2 = LOOT_COVER_RADIUS * LOOT_COVER_RADIUS;
        while (!remaining.isEmpty()) {
            float[] bestC = null;
            int bestCount = -1;
            int m = remaining.size();
            for (int i = 0; i < m; i++) {
                float[] c = remaining.get(i);
                int cnt = countWithin(remaining, c[0], c[1], r2);
                if (cnt > bestCount) {
                    bestCount = cnt;
                    bestC = c;
                }
            }
            for (int i = 0; i < m; i++) {
                for (int j = i + 1; j < m; j++) {
                    float[] a = remaining.get(i), b = remaining.get(j);
                    float cx = (a[0] + b[0]) * 0.5f, cy = (a[1] + b[1]) * 0.5f;
                    int cnt = countWithin(remaining, cx, cy, r2);
                    if (cnt > bestCount) {
                        bestCount = cnt;
                        bestC = new float[] { cx, cy };
                    }
                }
            }
            waypoints.add(bestC);
            java.util.Iterator<float[]> it = remaining.iterator();
            while (it.hasNext()) {
                float[] p = it.next();
                float dx = p[0] - bestC[0], dy = p[1] - bestC[1];
                if (dx * dx + dy * dy <= r2)
                    it.remove();
            }
        }
        return waypoints.toArray(new float[0][]);
    }

    private static int countWithin(java.util.List<float[]> pts, float cx, float cy, float r2) {
        int n = 0;
        for (float[] p : pts) {
            float dx = p[0] - cx, dy = p[1] - cy;
            if (dx * dx + dy * dy <= r2)
                n++;
        }
        return n;
    }

    /**
     * Orders waypoints into the shortest closed tour start -> ... -> start.
     * Straight-line (Euclidean) distances, valid since movement is omnidirectional.
     * Exact Held-Karp up to LOOT_MAX_EXACT waypoints, nearest-neighbour beyond.
     * Returns waypoint indices (0-based into wps) in visit order.
     */
    private static int[] tspOrder(float sx, float sy, float[][] wps) {
        int m = wps.length;
        if (m <= 1)
            return (m == 1) ? new int[] { 0 } : new int[0];
        int N = m + 1; // node 0 = start, nodes 1..m = waypoints
        float[] xs = new float[N], ys = new float[N];
        xs[0] = sx;
        ys[0] = sy;
        for (int i = 0; i < m; i++) {
            xs[i + 1] = wps[i][0];
            ys[i + 1] = wps[i][1];
        }
        float[][] d = new float[N][N];
        for (int i = 0; i < N; i++)
            for (int j = 0; j < N; j++) {
                float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                d[i][j] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        return (m <= LOOT_MAX_EXACT) ? heldKarp(d, m) : nearestNeighbour(d, m);
    }

    /** Held-Karp exact TSP over waypoints 1..m with fixed start/return at node 0. */
    private static int[] heldKarp(float[][] d, int m) {
        int full = 1 << m;
        float[][] dp = new float[full][m];
        int[][] par = new int[full][m];
        for (float[] row : dp)
            java.util.Arrays.fill(row, Float.MAX_VALUE);
        for (int i = 0; i < m; i++) {
            dp[1 << i][i] = d[0][i + 1];
            par[1 << i][i] = -1;
        }
        for (int mask = 1; mask < full; mask++) {
            for (int i = 0; i < m; i++) {
                if ((mask & (1 << i)) == 0)
                    continue;
                float cur = dp[mask][i];
                if (cur == Float.MAX_VALUE)
                    continue;
                for (int j = 0; j < m; j++) {
                    if ((mask & (1 << j)) != 0)
                        continue;
                    int nm = mask | (1 << j);
                    float nd = cur + d[i + 1][j + 1];
                    if (nd < dp[nm][j]) {
                        dp[nm][j] = nd;
                        par[nm][j] = i;
                    }
                }
            }
        }
        float best = Float.MAX_VALUE;
        int bestI = -1;
        for (int i = 0; i < m; i++) {
            float c = dp[full - 1][i];
            if (c == Float.MAX_VALUE)
                continue;
            c += d[i + 1][0]; // close the tour back to the start
            if (c < best) {
                best = c;
                bestI = i;
            }
        }
        int[] order = new int[m];
        int mask = full - 1, i = bestI, idx = m - 1;
        while (i != -1) {
            order[idx--] = i;
            int pi = par[mask][i];
            mask &= ~(1 << i);
            i = pi;
        }
        return order;
    }

    /** Nearest-neighbour fallback for large waypoint counts. */
    private static int[] nearestNeighbour(float[][] d, int m) {
        boolean[] used = new boolean[m];
        int[] order = new int[m];
        int cur = 0; // node 0 = start
        for (int k = 0; k < m; k++) {
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (int j = 0; j < m; j++) {
                if (used[j])
                    continue;
                if (d[cur][j + 1] < bd) {
                    bd = d[cur][j + 1];
                    best = j;
                }
            }
            order[k] = best;
            used[best] = true;
            cur = best + 1;
        }
        return order;
    }

    // ── Pathfinding (A* over the walkable tile grid) + path test (Ctrl+P) ──────
    //
    // Live walkability from the scene model: a cell is walkable iff its tile
    // config classifies as floor ('F') or stairs ('S') via tileWalkClass. A*
    // additionally treats the START cell + its 8 neighbours as walkable (so the
    // main can step off a spawn pad with no floor tile) and the GOAL cell as
    // walkable (so it can route onto a void target such as the elevator).
    // 8-directional, Euclidean heuristic, no corner-cutting. This is the movement
    // foundation for the stage routine.

    private static java.util.HashSet<Long> pathWalkable = null; // cached walkable cells
    private static java.util.HashSet<Long> pathHazardCells = null; // cached hazard (bramble) cells, for the strict corner rule
    private static Object pathGridModel = null;                 // scene model the grid was built for
    private static int pathGridEntryCount = -1;                 // scene entry count when it was built (a destroyed gate removes entries)
    // Path-follow test: Ctrl+P walks the main to a fixed target via A*.
    public static volatile boolean isPathTest = false;
    private static volatile boolean pathTestPlanned = false;
    private static volatile boolean pathTestDone = false;
    private static float[] pathWpX = null, pathWpY = null; // path waypoints (world)
    private static int pathWpIdx = 0;
    private static final float PATH_ARRIVE_SQ = 0.49f;        // (0.7 tile)^2 waypoint arrival
    private static final float PATH_TARGET_ARRIVE_SQ = 1.0f;  // (1 tile)^2 final-target arrival
    private static final float PATH_CLEARANCE = 0.5f;         // shove waypoints this far off obstacle edges (0.5 = up to the cell edge on the open side)
    // Hazard margin (tiles): block a cell if its CENTRE is within this of a hazard rect.
    // 0.30 = the MEASURED danger reach + margin (user's fire-tile probes, July 2026: the
    // damage zone is the tile rect inflated 0.23 per axis with SQUARE corners — axis
    // probes 0.23/0.23, diagonal hit at 0.298 from the corner ruled a circle out).
    // Corner safety (reach 0.23*sqrt2=0.325) is the strict no-corner-cut rule's job, not
    // this value's. Still <0.5, so orthogonally-adjacent cell centres stay walkable and
    // 1-cell corridors survive; grid-aligned 1x1 tiles are unaffected by 0.48 vs 0.30 —
    // the difference only opens corridors near OFF-GRID hazard rects (bramble).
    private static final float HAZARD_INFLATION = 0.30f;      // was 0.48 (pre-measurement guess)
    private static final float PATH_TEST_X = 6.04f, PATH_TEST_Y = -16.97f; // first route waypoint

    /** True while a bot system is driving the main's movement (widens the poll's key-injection gate to the main). */
    public static boolean mainMovementDriven() {
        return isLootMode || isPathTest || isRoutineActive;
    }

    private static long ck(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xffffffffL);
    }

    private static int ckx(long k) {
        return (int) (k >> 32);
    }

    private static int cky(long k) {
        return (int) (k & 0xffffffffL);
    }

    /** Builds (and caches per scene model) the set of walkable tile cells (floor + stairs). */
    private static java.util.HashSet<Long> buildWalkGrid(Object model) {
        // Cache key is the scene model AND its ENTRY COUNT. Identity alone is not enough:
        // destroying a gate REMOVES its placeable entries, and those are baked into this
        // grid, so a model-only key kept routing around a shortcut that had already opened
        // (user-reported: Ctrl+D showed the cells walkable — it recomputes fresh — while
        // A* still took the long way off the stale cache). Counting entries is O(1) and
        // catches the change even mid-step; routineResetStepState also drops the cache at
        // every step boundary, which covers a change that keeps the count identical.
        int entryCount = entryCountOf(model);
        if (pathWalkable != null && pathGridModel == model && pathGridEntryCount == entryCount)
            return pathWalkable;
        java.util.HashSet<Long> ws = new java.util.HashSet<Long>();
        try {
            Object tiles = Reflect.readObjectFieldNullable(model, "_tiles");
            if (tiles == null)
                return null;
            java.lang.reflect.Method tileAt = null;
            for (java.lang.reflect.Method m : model.getClass().getMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class
                        && m.getReturnType().getName().endsWith("$TileEntry")) {
                    tileAt = m;
                    break;
                }
            }
            Object es = tiles.getClass().getMethod("entrySet").invoke(tiles);
            java.util.HashMap<Integer, Boolean> valWalk = new java.util.HashMap<Integer, Boolean>();
            for (Object o : (Iterable<?>) es) {
                java.util.Map.Entry<?, ?> me = (java.util.Map.Entry<?, ?>) o;
                Object c = me.getKey();
                int x = c.getClass().getField("x").getInt(c);
                int y = c.getClass().getField("y").getInt(c);
                Integer tv = (me.getValue() instanceof Integer) ? (Integer) me.getValue() : Integer.valueOf(0);
                Boolean wk = valWalk.get(tv);
                if (wk == null) {
                    String nm = "";
                    if (tileAt != null) {
                        try {
                            Object te = tileAt.invoke(model, x, y);
                            if (te != null)
                                nm = entryConfigName(te);
                        } catch (Exception ig) {
                        }
                    }
                    char cls = tileWalkClass(nm);
                    wk = Boolean.valueOf(cls == 'F' || cls == 'S');
                    valWalk.put(tv, wk);
                }
                if (wk.booleanValue())
                    ws.add(ck(x, y));
            }
            // Remove cells blocked by collision-bearing placeables (props like
            // lanterns/pillars that sit on walkable floor but are impassable).
            removePlaceableCollision(ws, model);
            // Hazard (bramble) cells: inflated footprints. Removed from the walkable
            // set AND cached so A* can apply a strict no-corner-cut around them.
            java.util.HashSet<Long> hz = hazardInflatedCells(model, HAZARD_INFLATION);
            ws.removeAll(hz);
            pathHazardCells = hz;
            // Multi-tile spike-trap tiles: _tiles stores only the anchor cell, so the
            // rest of a UxV trap reads as void and breaks pathing across it. Mark the
            // whole footprint walkable — HAZARD_MOVETO times the actual crossing.
            for (Long cell : buildTrapCells(model).keySet())
                ws.add(cell);
        } catch (Exception e) {
            if (debug)
                debugFile("[path] grid build error: " + e);
            return null;
        }
        pathWalkable = ws;
        pathGridModel = model;
        pathGridEntryCount = entryCount;
        if (debug)
            debugFile("[path] walk grid rebuilt: " + ws.size() + " walkable cells, " + entryCount + " scene entries");
        if (debug)
            debugFile("[path] walk grid built: " + ws.size() + " walkable cells");
        return ws;
    }

    private static java.lang.reflect.Method cachedEntryShapeMethod = null;  // Entry.ax(ConfigManager) -> Shape
    private static java.lang.reflect.Method cachedShapeBoundsMethod = null; // Shape.adA() -> Rect

    /** Removes from the walkable set every cell covered by a collision-bearing placeable. */
    private static void removePlaceableCollision(java.util.HashSet<Long> ws, Object model) {
        int blocked = 0;
        for (Long k : placeableBlockedCells(model))
            if (ws.remove(k))
                blocked++;
        if (debug)
            debugFile("[path] placeable collision blocked " + blocked + " cells");
    }

    /**
     * Whether a placeable is a pathing obstacle. Config-name aware because raw
     * collision flags mislead both ways here:
     *   - HAZARDS (e.g. "Dynamic/Hazards/Bramble thicket/…") have collFlags=0 yet
     *     must be AVOIDED (walk-through-but-damaging), so they always block.
     *   - ONE-WAY barriers ("Dynamic/Barrier/One-Way/Force Field", collFlags=192)
     *     are passable in the travel direction, so they never block.
     *   - Otherwise a nonzero collFlags = solid prop (lantern/pillar) → blocks.
     */
    private static boolean placeableIsObstacle(Object entry) {
        String nm = entryConfigName(entry).toLowerCase(Locale.ROOT);
        if (nm.contains("one-way") || nm.contains("one way"))
            return false; // passable one-way barrier / force field
        if (nm.contains("block/treasure/hearts"))
            return true; // heart treasure block: physically collides but reads collFlags=0,
                         // and TREASURESWEEP never breaks it — so treat it as a solid obstacle
        if (placeableIsHazard(entry))
            return true; // avoid hazards even when non-colliding
        Integer cf = Reflect.readIntFieldNullable(entry, "_collisionFlags");
        return cf != null && cf.intValue() != 0; // solid collision prop
    }

    /** A hazard placeable (bramble thicket etc.): walk-through-but-damaging, so it is AVOIDED and inflated. */
    private static boolean placeableIsHazard(Object entry) {
        String nm = entryConfigName(entry).toLowerCase(Locale.ROOT);
        if (nm.contains("/hazards/") || nm.contains("bramble"))
            return true;
        // Per-MISSION opt-in (mission_data.txt `hazard_configs=`) — e.g. a dungeon whose
        // firestorm tiles spawn at random spots each run and whose only stable marker is
        // a plain "Generic Marker" placeable. Never global: the same config name is
        // harmless furniture elsewhere.
        String[] extra = missionHazardConfigs;
        for (int i = 0; i < extra.length; i++)
            if (nm.contains(extra[i]))
                return true;
        return false;
    }

    /**
     * Whether an ACTOR is a static obstacle the router must go around. Included
     * (per user): unbreakable blocks and Dynamic/Monster Objects (spawners/nests
     * etc.). NOT explosive or other treasure (the routine shoots those via
     * TREASURESWEEP), nor STONE or breakable shrubs — both are now WALKABLE and shot
     * on approach (see isBreakableBlock / tickBreakableClear) — nor mineral blocks.
     * (Heart treasure blocks are PLACEABLES — handled in placeableIsObstacle.)
     */
    private static boolean actorIsObstacle(Object actor) {
        String nm = Reflect.actorConfigName(actor).toLowerCase(Locale.ROOT);
        return nm.contains("block/unbreakable")
                || nm.contains("monster objects") // Dynamic/Monster Objects/... — solid, route around
                || nm.contains("switch/lever/one-time"); // Switch | Dynamic/Switch/Lever/One-Time —
                        // solid + collidable (user, sovereign_slime); flipped by SWITCHSWEEP's shots,
                        // never stepped on
    }

    /**
     * A block the mover shoots when it lies on the path: walkable in the grid,
     * cleared by tickBreakableClear on approach. Shrubs, 3-hit stone (the
     * shoot-until-gone loop handles the extra hits), GHOST blocks (destroying one
     * removes every block connected to it — see ghostBlockCells), EXPLOSIVE
     * blocks, CRYSTAL blocks (Block/Crystal — also a button/lever covering in the
     * sweeps), and TREASURE blocks (Block/Treasure). "block/stone" does NOT catch
     * "Block/Mineral/Moonstone" (that's "mineral/moonstone").
     */
    private static boolean isBreakableBlock(Object actor) {
        String nm = Reflect.actorConfigName(actor).toLowerCase(Locale.ROOT);
        return nm.contains("block/breakable") || nm.contains("block/stone")
                || nm.contains("block/ghost") || nm.contains("block/explosive")
                || nm.contains("block/crystal")
                || (nm.contains("block/treasure") && !nm.contains("block/treasure/hearts"));
    }

    /** The grid cells occupied by obstacle block actors (read live from the actor map). */
    private static java.util.HashSet<Long> actorBlockedCells(Object view) {
        java.util.HashSet<Long> out = new java.util.HashSet<Long>();
        try {
            if (view == null)
                return out;
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return out;
            java.util.ArrayList<PropMask> masks = loadPropMasks();
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object wrapper : (Iterable<?>) values) {
                Object actor = Reflect.getWrappedActor(wrapper);
                if (actor == null)
                    continue;
                // Prop masks apply to matching ACTORS too (mirrors placeableBlockedCells;
                // a masked prop like the Arsenal Zone can live in either layer), and they
                // override the obstacle test — same offsets, whichever layer carries it.
                PropMask mask = null;
                if (!masks.isEmpty()) {
                    String nm = Reflect.actorConfigName(actor).toLowerCase(Locale.ROOT);
                    for (PropMask m : masks)
                        if (nm.contains(m.match)) {
                            mask = m;
                            break;
                        }
                }
                if (mask != null) {
                    float[] p = Reflect.actorPos(actor);
                    if (p != null) {
                        int ax = (int) Math.floor(p[0]), ay = (int) Math.floor(p[1]);
                        for (int[] o : mask.offsets)
                            out.add(Long.valueOf(ck(ax + o[0], ay + o[1])));
                    }
                    continue;
                }
                if (!actorIsObstacle(actor))
                    continue;
                float[] p = Reflect.actorPos(actor);
                if (p != null)
                    out.add(Long.valueOf(ck((int) Math.floor(p[0]), (int) Math.floor(p[1]))));
            }
        } catch (Exception e) {
            if (debug)
                debugFile("[path] actor obstacle error: " + e);
        }
        return out;
    }

    // Per-cell shape intersection (large irregular props). Resolved structurally once.
    private static java.lang.reflect.Method cachedShapeIsectMethod = null; // Shape.getIntersectionType(Rect) -> enum
    private static java.lang.reflect.Constructor<?> cachedRectCtor = null; // Rect(Vector2f, Vector2f)
    private static java.lang.reflect.Constructor<?> cachedVec2Ctor = null; // Vector2f(float, float)
    private static boolean shapeIsectUnavailable = false;                  // resolution failed; fall back quietly

    /**
     * Cells whose 1x1 square REALLY intersects the entry's collision shape — not just
     * its bounding rect.
     * <p>Cell rects are inset slightly so grazing a shape's edge doesn't block the
     * neighbouring cell. Falls back to the plain bounding-rect fill if the intersection API can't
     * be resolved.
     */
    private static int[][] preciseFootprintCells(Object entry, Object cfgmgr) {
        int[][] coarse = entryFootprintCells(entry, cfgmgr);
        if (coarse.length <= 1 || shapeIsectUnavailable)
            return coarse; // 1-cell props need no refinement
        try {
            Object shape = entryShape(entry, cfgmgr);
            if (shape == null)
                return coarse;
            if (cachedShapeIsectMethod == null || !cachedShapeIsectMethod.getDeclaringClass().isInstance(shape)) {
                Object bounds = cachedShapeBoundsMethod.invoke(shape);
                Class<?> rectCls = bounds.getClass();
                Class<?> vecCls = Class.forName("com.threerings.math.Vector2f");
                cachedVec2Ctor = vecCls.getConstructor(float.class, float.class);
                cachedRectCtor = rectCls.getConstructor(vecCls, vecCls);
                for (java.lang.reflect.Method m : shape.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == rectCls && m.getReturnType().isEnum()) {
                        cachedShapeIsectMethod = m; // getIntersectionType(Rect)
                        break;
                    }
                }
                if (cachedShapeIsectMethod == null) {
                    shapeIsectUnavailable = true;
                    debugFile("[path] no Shape∩Rect method — large props stay bounding-rect blocked");
                    return coarse;
                }
            }
            java.util.ArrayList<int[]> kept = new java.util.ArrayList<int[]>();
            final float eps = 0.02f;
            for (int[] c : coarse) {
                Object lo = cachedVec2Ctor.newInstance(c[0] + eps, c[1] + eps);
                Object hi = cachedVec2Ctor.newInstance(c[0] + 1 - eps, c[1] + 1 - eps);
                Object cellRect = cachedRectCtor.newInstance(lo, hi);
                Object isect = cachedShapeIsectMethod.invoke(shape, cellRect);
                if (isect != null && !"NONE".equals(String.valueOf(isect)))
                    kept.add(c);
            }
            return kept.toArray(new int[0][]);
        } catch (Exception ex) {
            if (debug)
                debugFile("[path] precise footprint error: " + ex);
            return coarse;
        }
    }

    // ── Per-prop collision masks (~/.sk-utils/prop_masks/*.txt) ──────────────
    // Manual override for the rare prop whose collision data itself misleads: each
    // file names a config substring and lists EXACTLY the tiles to block, as integer
    // offsets from the prop's anchor cell. A matching mask REPLACES the computed
    // footprint entirely ("none" = block nothing), and it applies even to props with
    // NO collision data at all (e.g. Arsenal Zone markers: collFlags=0 yet the
    // station tiles are solid) — masks are checked BEFORE the obstacle test, in both
    // the placeable and the actor layer. NOTE: offsets are applied without
    // rotation — fine for scenery placed unrotated, wrong for a rotated instance.
    //
    //   # prop_masks/gnarled_tree.txt
    //   match=gnarled tree base
    //   0 0
    //   1 0
    //   ...        (or the single word: none)
    private static final String PROP_MASKS_DIR = DIR + "/prop_masks";
    private static java.util.ArrayList<PropMask> propMasksCache = null; // loadPropMasks TTL cache
    private static long propMasksLoadedAt = 0L;
    private static final long PROP_MASKS_TTL_MS = 10000L; // re-read at most every 10s (actor scan runs per path plan)

    private static final class PropMask {
        final String match;     // lower-case config-name substring
        final int[][] offsets;  // tile offsets from the anchor cell (empty = block nothing)

        PropMask(String match, int[][] offsets) {
            this.match = match;
            this.offsets = offsets;
        }
    }

    private static java.util.ArrayList<PropMask> loadPropMasks() {
        long nowMs = System.currentTimeMillis();
        if (propMasksCache != null && nowMs - propMasksLoadedAt < PROP_MASKS_TTL_MS)
            return propMasksCache;
        java.util.ArrayList<PropMask> out = new java.util.ArrayList<PropMask>();
        File d = new File(PROP_MASKS_DIR);
        File[] files = d.isFile() ? null : d.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (files == null) {
            propMasksCache = out;
            propMasksLoadedAt = nowMs;
            return out;
        }
        for (File f : files) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String match = null;
                java.util.ArrayList<int[]> offs = new java.util.ArrayList<int[]>();
                boolean none = false;
                String line;
                while ((line = br.readLine()) != null) {
                    int h = line.indexOf('#');
                    if (h >= 0)
                        line = line.substring(0, h);
                    line = line.trim();
                    if (line.isEmpty())
                        continue;
                    if (line.toLowerCase(Locale.ROOT).startsWith("match=")) {
                        match = line.substring(6).trim().toLowerCase(Locale.ROOT);
                    } else if (line.equalsIgnoreCase("none")) {
                        none = true;
                    } else {
                        String[] t = line.split("\\s+");
                        if (t.length == 2)
                            offs.add(new int[] { Integer.parseInt(t[0]), Integer.parseInt(t[1]) });
                    }
                }
                if (match != null && !match.isEmpty())
                    out.add(new PropMask(match, none ? new int[0][] : offs.toArray(new int[0][])));
            } catch (Exception e) {
                debugFile("[path] bad prop mask " + f.getName() + ": " + e);
            }
        }
        propMasksCache = out;
        propMasksLoadedAt = nowMs;
        return out;
    }

    /** All cells covered by obstacle placeables (solid props + hazards, minus passable one-way barriers). */
    private static java.util.HashSet<Long> placeableBlockedCells(Object model) {
        java.util.HashSet<Long> out = new java.util.HashSet<Long>();
        try {
            Object cfgmgr = model.getClass().getMethod("getConfigManager").invoke(model);
            java.util.Collection<?> entries = entriesCollection(model);
            if (entries == null)
                return out;
            java.util.ArrayList<PropMask> masks = loadPropMasks();
            for (Object e : entries) {
                if (e == null || !"PlaceableEntry".equals(e.getClass().getSimpleName()))
                    continue;
                // A user mask overrides everything for its prop — INCLUDING the obstacle
                // test, so a mask can FORCE blocking onto a prop with no collision data
                // (Arsenal Zone: collFlags=0 yet its station tiles are solid) as well as
                // shrink an over-blocking one.
                PropMask mask = null;
                if (!masks.isEmpty()) {
                    String nm = entryConfigName(e).toLowerCase(Locale.ROOT);
                    for (PropMask m : masks)
                        if (nm.contains(m.match)) {
                            mask = m;
                            break;
                        }
                }
                if (mask != null) {
                    float[] p = entryPos(e, cfgmgr);
                    if (p != null) {
                        int ax = (int) Math.floor(p[0]), ay = (int) Math.floor(p[1]);
                        for (int[] o : mask.offsets)
                            out.add(Long.valueOf(ck(ax + o[0], ay + o[1])));
                    }
                    continue;
                }
                if (!placeableIsObstacle(e))
                    continue;
                for (int[] c : preciseFootprintCells(e, cfgmgr))
                    out.add(Long.valueOf(ck(c[0], c[1])));
            }
        } catch (Exception ex) {
            if (debug)
                debugFile("[path] placeable collision error: " + ex);
        }
        return out;
    }

    /**
     * Cells holding a GHOST block ("Block/Ghost") — the ghost ITSELF, never the
     * blocks wired to it. Marked 'G' in the Ctrl+D walk grid, purely as an authoring
     * aid: route a step within 4 tiles of a G and block-clearing opens that cluster.
     *
     * Both layers are read: a ghost may be actor-only (the whole
     * gloaming_wildwoods_ruins gate is), scene-model-only, or both.
     */
    private static java.util.HashSet<Long> ghostBlockCells(Object model, Object view) {
        java.util.HashSet<Long> out = new java.util.HashSet<Long>();
        try {
            Object actorMap = (view == null) ? null : Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap != null) {
                Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
                for (Object w : (Iterable<?>) values) {
                    Object a = Reflect.getWrappedActor(w);
                    if (a == null || !isGhostBlock(a))
                        continue;
                    float[] p = Reflect.actorPos(a);
                    if (p != null)
                        out.add(Long.valueOf(ck((int) Math.floor(p[0]), (int) Math.floor(p[1]))));
                }
            }
        } catch (Exception ex) {
            if (debug)
                debugFile("[path] ghost actor scan error: " + ex);
        }
        try {
            Object cfgmgr = model.getClass().getMethod("getConfigManager").invoke(model);
            java.util.Collection<?> entries = entriesCollection(model);
            if (entries != null) {
                for (Object e : entries) {
                    if (e == null || !"PlaceableEntry".equals(e.getClass().getSimpleName()))
                        continue;
                    if (!entryConfigName(e).toLowerCase(Locale.ROOT).contains("block/ghost"))
                        continue;
                    for (int[] c : entryFootprintCells(e, cfgmgr))
                        out.add(Long.valueOf(ck(c[0], c[1])));
                }
            }
        } catch (Exception ex) {
            if (debug)
                debugFile("[path] ghost placeable scan error: " + ex);
        }
        return out;
    }

    /** A "Block/Ghost" actor: shooting it removes every block connected to it. */
    private static boolean isGhostBlock(Object actor) {
        return Reflect.actorConfigName(actor).toLowerCase(Locale.ROOT).contains("block/ghost");
    }

    /**
     * True if a GHOST BLOCK actor still stands within ALCH_GHOST_MATCH_SQ of (wx,wy).
     * ACTOR layer only: destroying a ghost cluster removes the actors, while the scene
     * model's placeable entries stay put, so the placeable layer can never report the
     * gate as opened. Used by ALCH_CHARGE to tell whether its shot landed.
     */
    private static boolean ghostBlockAt(Object view, float wx, float wy) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return false;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isGhostBlock(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - wx, dy = p[1] - wy;
                if (dx * dx + dy * dy <= ALCH_GHOST_MATCH_SQ)
                    return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    /**
     * Cells whose CENTRE lies within {@code inflation} tiles of a hazard (bramble)
     * shape rect — a continuous, sub-cell margin. Unlike whole-cell dilation this is
     * tunable below one cell: at 0.48 an orthogonally-adjacent centre (0.5 away) and
     * a diagonal corner (~0.707) both stay walkable, so 1-cell corridors survive,
     * while cells whose centre genuinely grazes a bramble (<inflation) are blocked.
     */
    private static java.util.HashSet<Long> hazardInflatedCells(Object model, float inflation) {
        java.util.HashSet<Long> out = new java.util.HashSet<Long>();
        float infSq = inflation * inflation;
        int margin = Math.max(1, (int) Math.ceil(inflation));
        try {
            Object cfgmgr = model.getClass().getMethod("getConfigManager").invoke(model);
            java.util.Collection<?> entries = entriesCollection(model);
            if (entries == null)
                return out;
            for (Object e : entries) {
                if (e == null || !"PlaceableEntry".equals(e.getClass().getSimpleName()))
                    continue;
                if (!placeableIsHazard(e))
                    continue;
                float[] r = entryRect(e, cfgmgr);
                if (r == null) {
                    // No shape bounds: block the footprint cell(s), no inflation.
                    for (int[] c : entryFootprintCells(e, cfgmgr))
                        out.add(Long.valueOf(ck(c[0], c[1])));
                    continue;
                }
                int cx0 = (int) Math.floor(r[0]) - margin, cy0 = (int) Math.floor(r[1]) - margin;
                int cx1 = (int) Math.floor(r[2] - 1e-4f) + margin, cy1 = (int) Math.floor(r[3] - 1e-4f) + margin;
                for (int x = cx0; x <= cx1; x++) {
                    for (int y = cy0; y <= cy1; y++) {
                        float px = x + 0.5f, py = y + 0.5f;
                        float ddx = Math.max(Math.max(r[0] - px, px - r[2]), 0f);
                        float ddy = Math.max(Math.max(r[1] - py, py - r[3]), 0f);
                        if (ddx * ddx + ddy * ddy <= infSq)
                            out.add(Long.valueOf(ck(x, y)));
                    }
                }
            }
        } catch (Exception ex) {
            if (debug)
                debugFile("[path] hazard inflate error: " + ex);
        }
        return out;
    }

    /** An entry's collision-shape bounds as a float rect {minX,minY,maxX,maxY}, or null. */
    /** The entry's world-space collision Shape (tudey.shape.Shape), or null. */
    private static Object entryShape(Object entry, Object cfgmgr) {
        try {
            if (cfgmgr == null)
                return null;
            if (cachedEntryShapeMethod == null || !cachedEntryShapeMethod.getDeclaringClass().isInstance(entry)) {
                for (java.lang.reflect.Method m : entry.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0].getName().endsWith(".ConfigManager")
                            && m.getReturnType().getName().endsWith(".Shape")) {
                        cachedEntryShapeMethod = m;
                        break;
                    }
                }
            }
            return (cachedEntryShapeMethod == null) ? null : cachedEntryShapeMethod.invoke(entry, cfgmgr);
        } catch (Exception e) {
            return null;
        }
    }

    private static float[] entryRect(Object entry, Object cfgmgr) {
        try {
            Object shape = entryShape(entry, cfgmgr);
            if (shape == null)
                return null;
            if (cachedShapeBoundsMethod == null || !cachedShapeBoundsMethod.getDeclaringClass().isInstance(shape)) {
                for (java.lang.reflect.Method m : shape.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType().getName().endsWith(".Rect")) {
                        cachedShapeBoundsMethod = m;
                        break;
                    }
                }
            }
            Object rect = (cachedShapeBoundsMethod == null) ? null : cachedShapeBoundsMethod.invoke(shape);
            if (rect == null)
                return null;
            float[] mn = Reflect.vec2xy(Reflect.readObjectFieldNullable(rect, "_minExtent"));
            float[] mx = Reflect.vec2xy(Reflect.readObjectFieldNullable(rect, "_maxExtent"));
            if (mn != null && mx != null && mx[0] >= mn[0] && mx[1] >= mn[1])
                return new float[] { mn[0], mn[1], mx[0], mx[1] };
        } catch (Exception e) {
        }
        return null;
    }

    /** The grid cells covered by an entry's collision shape (bounds-rasterised), or its centre cell. */
    private static int[][] entryFootprintCells(Object entry, Object cfgmgr) {
        try {
            float[] r = entryRect(entry, cfgmgr);
            if (r != null) {
                int x0 = (int) Math.floor(r[0]), y0 = (int) Math.floor(r[1]);
                int x1 = (int) Math.floor(r[2] - 1e-4f), y1 = (int) Math.floor(r[3] - 1e-4f);
                long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
                if (area >= 1 && area <= 256) {
                    int[][] cells = new int[(int) area][2];
                    int i = 0;
                    for (int x = x0; x <= x1; x++)
                        for (int y = y0; y <= y1; y++) {
                            cells[i][0] = x;
                            cells[i][1] = y;
                            i++;
                        }
                    return cells;
                }
            }
            float[] c = entryPos(entry, cfgmgr); // fallback: the centre cell
            if (c != null)
                return new int[][] { { (int) Math.floor(c[0]), (int) Math.floor(c[1]) } };
        } catch (Exception e) {
        }
        return new int[0][];
    }

    /**
     * Whether cell (x,y) is traversable for A*: a walkable tile, or — when the
     * start/goal sits on void — its 3x3 pad. The pads let the main step off a
     * spawn platform and onto a void platform (e.g. the elevator, which sits in a
     * 3x3 void gap). Pads are only enabled for void endpoints, so a floor target
     * can't corner-cut through an adjacent pit.
     */
    private static boolean pWalk(java.util.HashSet<Long> ws, int x, int y, int spx, int spy, int gcx, int gcy,
            boolean startPad, boolean goalPad) {
        if (ws.contains(ck(x, y)))
            return true;
        if (startPad && Math.abs(x - spx) <= 1 && Math.abs(y - spy) <= 1)
            return true;
        if (goalPad && Math.abs(x - gcx) <= 1 && Math.abs(y - gcy) <= 1)
            return true;
        return false;
    }

    /** Nearest walkable cell to (cx,cy) within maxR (ring search), or null. */
    private static int[] nearestWalkable(java.util.HashSet<Long> ws, int cx, int cy, int maxR) {
        if (ws.contains(ck(cx, cy)))
            return new int[] { cx, cy };
        for (int r = 1; r <= maxR; r++) {
            int[] best = null;
            int bestd = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
                        continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (ws.contains(ck(nx, ny))) {
                        int d = dx * dx + dy * dy;
                        if (d < bestd) {
                            bestd = d;
                            best = new int[] { nx, ny };
                        }
                    }
                }
            }
            if (best != null)
                return best;
        }
        return null;
    }

    private static final class PNode {
        final long k;
        final float f;

        PNode(long k, float f) {
            this.k = k;
            this.f = f;
        }
    }

    private static float hEuclid(int x, int y, int gx, int gy) {
        int dx = x - gx, dy = y - gy;
        return (float) Math.sqrt((double) (dx * dx + dy * dy));
    }

    /**
     * A* over cells from (scx,scy) to (gcx,gcy). Returns the cell path
     * (start..goal, inclusive) or null. {@code hazardCells} = bramble cells: a
     * diagonal move whose corner touches one is forbidden (strict no-corner-cut) so
     * the hurtbox never clips a bramble corner — even though walls keep the lenient
     * rule that only blocks a diagonal between two solid cells.
     */
    private static java.util.ArrayList<int[]> aStarCells(java.util.HashSet<Long> ws, int scx, int scy, int gcx, int gcy,
            java.util.HashSet<Long> hazardCells) {
        long start = ck(scx, scy), goal = ck(gcx, gcy);
        final boolean startPad = !ws.contains(Long.valueOf(start)); // start on void => allow its 3x3 pad
        final boolean goalPad = !ws.contains(Long.valueOf(goal));   // goal on void => treat as a platform
        java.util.HashMap<Long, Float> gscore = new java.util.HashMap<Long, Float>();
        java.util.HashMap<Long, Long> came = new java.util.HashMap<Long, Long>();
        java.util.HashSet<Long> closed = new java.util.HashSet<Long>();
        java.util.PriorityQueue<PNode> open = new java.util.PriorityQueue<PNode>(64,
                new java.util.Comparator<PNode>() {
                    public int compare(PNode a, PNode b) {
                        return Float.compare(a.f, b.f);
                    }
                });
        gscore.put(Long.valueOf(start), Float.valueOf(0f));
        open.add(new PNode(start, hEuclid(scx, scy, gcx, gcy)));
        boolean found = false;
        while (!open.isEmpty()) {
            PNode cur = open.poll();
            if (closed.contains(Long.valueOf(cur.k)))
                continue;
            closed.add(Long.valueOf(cur.k));
            if (cur.k == goal) {
                found = true;
                break;
            }
            int cx = ckx(cur.k), cy = cky(cur.k);
            float cg = gscore.get(Long.valueOf(cur.k)).floatValue();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0)
                        continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (!pWalk(ws, nx, ny, scx, scy, gcx, gcy, startPad, goalPad))
                        continue;
                    if (dx != 0 && dy != 0) {
                        boolean o1 = pWalk(ws, cx + dx, cy, scx, scy, gcx, gcy, startPad, goalPad);
                        boolean o2 = pWalk(ws, cx, cy + dy, scx, scy, gcx, gcy, startPad, goalPad);
                        if (!o1 && !o2)
                            continue; // no corner-cutting between two solid cells
                        // Strict no-corner-cut around hazards: a diagonal whose corner
                        // cell is a bramble would sweep the hurtbox across it. Route
                        // orthogonally around instead.
                        if (hazardCells != null
                                && (hazardCells.contains(Long.valueOf(ck(cx + dx, cy)))
                                        || hazardCells.contains(Long.valueOf(ck(cx, cy + dy)))))
                            continue;
                    }
                    long nk = ck(nx, ny);
                    if (closed.contains(Long.valueOf(nk)))
                        continue;
                    float step = (dx != 0 && dy != 0) ? 1.41421356f : 1f;
                    float ng = cg + step;
                    Float old = gscore.get(Long.valueOf(nk));
                    if (old == null || ng < old.floatValue()) {
                        gscore.put(Long.valueOf(nk), Float.valueOf(ng));
                        came.put(Long.valueOf(nk), Long.valueOf(cur.k));
                        open.add(new PNode(nk, ng + hEuclid(nx, ny, gcx, gcy)));
                    }
                }
            }
        }
        if (!found)
            return null;
        java.util.ArrayList<Long> rev = new java.util.ArrayList<Long>();
        long k = goal;
        while (true) {
            rev.add(Long.valueOf(k));
            if (k == start)
                break;
            Long p = came.get(Long.valueOf(k));
            if (p == null)
                break;
            k = p.longValue();
        }
        java.util.ArrayList<int[]> path = new java.util.ArrayList<int[]>();
        for (int i = rev.size() - 1; i >= 0; i--) {
            long kk = rev.get(i).longValue();
            path.add(new int[] { ckx(kk), cky(kk) });
        }
        return path;
    }

    /**
     * A* from world (sx,sy) to world (gx,gy) over the live walkable grid. Returns
     * world-space waypoints (cell centres) ending with the target point, or null
     * if unreachable. A void goal is first attempted as a platform (goal pad); if
     * that fails, the goal is snapped to the nearest floor.
     */
    private static float[][] findPathWorld(Object model, Object view, float sx, float sy, float gx, float gy) {
        java.util.HashSet<Long> base = buildWalkGrid(model);
        if (base == null)
            return null;
        // Copy the cached static grid (tiles + placeables) and subtract the
        // current block actors (unbreakable/stone/breakable-shrub) fresh each
        // path, so destroyed blocks open back up instead of staying stale.
        java.util.HashSet<Long> ws = new java.util.HashSet<Long>(base);
        ws.removeAll(actorBlockedCells(view));
        java.util.HashSet<Long> extraBlk = pathExtraBlocked;
        if (extraBlk != null)
            ws.removeAll(extraBlk); // chase-scoped overlay (e.g. PLATFORM's sealed gate cells)
        // NOTE: blocks wired to a ghost stay SOLID here on purpose. They can form a
        // long winding wall that only opens when the ghost is shot, so pre-freeing
        // them let A* beeline through a standing gate — including when the goal WAS
        // the ghost block, which then became unreachable. The gate is opened by
        // tickBreakableClear instead (any ghost within range is a target), and THIS
        // resample — run on the next plan, once the cluster's actors are gone — is
        // what routes through the opened corridor.
        int scx = (int) Math.floor(sx), scy = (int) Math.floor(sy);
        int gcx = (int) Math.floor(gx), gcy = (int) Math.floor(gy);
        float fgx = gx, fgy = gy; // exact point to finish on
        java.util.HashSet<Long> hazardCells = pathHazardCells;
        java.util.ArrayList<int[]> cells = aStarCells(ws, scx, scy, gcx, gcy, hazardCells);
        if (cells == null) {
            int[] sn = nearestWalkable(ws, gcx, gcy, 6); // fallback: path to nearest floor
            if (sn == null)
                return null;
            cells = aStarCells(ws, scx, scy, sn[0], sn[1], hazardCells);
            if (cells == null)
                return null;
            fgx = sn[0] + 0.5f;
            fgy = sn[1] + 0.5f;
        }
        int n = cells.size();
        float[][] out = new float[n + 1][2];
        for (int i = 0; i < n; i++) {
            int cx = cells.get(i)[0], cy = cells.get(i)[1];
            float wx = cx + 0.5f, wy = cy + 0.5f;
            // Clearance nudge: shove the waypoint away from any non-walkable
            // neighbour (obstacle / wall / void) so the player keeps a margin off
            // edges instead of grazing them. At PATH_CLEARANCE=0.5 an axis-aligned
            // push lands right on the cell edge, toward the open (non-obstacle) side.
            // Opposing neighbours cancel, so a 1-tile corridor self-centres.
            float rx = 0f, ry = 0f;
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0)
                        continue;
                    if (!ws.contains(Long.valueOf(ck(cx + dx, cy + dy)))) {
                        rx -= dx;
                        ry -= dy;
                    }
                }
            float mag = (float) Math.sqrt(rx * rx + ry * ry);
            if (mag > 1e-4f) {
                wx += PATH_CLEARANCE * rx / mag;
                wy += PATH_CLEARANCE * ry / mag;
            }
            out[i][0] = wx;
            out[i][1] = wy;
        }
        out[n][0] = fgx;
        out[n][1] = fgy;
        return out;
    }

    /** Ctrl+P path test: A*-walk the main to (PATH_TEST_X, PATH_TEST_Y). */
    public static void tickPathTest(Object controller) {
        try {
            if (controller == null)
                return;
            Object view = Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            Integer pawnId = Reflect.readIntFieldNullable(controller, MappingsNames.PAWN_ID_FIELD);
            if (view == null || pawnId == null) {
                clearMovementKeys();
                return;
            }
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null) {
                clearMovementKeys();
                return;
            }
            Object wr = actorMap.getClass().getMethod("get", int.class).invoke(actorMap, pawnId.intValue());
            Object meActor = (wr == null) ? null : Reflect.getWrappedActor(wr);
            float[] mp = (meActor == null) ? null : Reflect.actorPos(meActor);
            if (mp == null) {
                clearMovementKeys();
                return;
            }
            if (!pathTestPlanned) {
                Object model = sceneModelOf(view);
                float[][] wp = (model == null) ? null : findPathWorld(model, view, mp[0], mp[1], PATH_TEST_X, PATH_TEST_Y);
                if (wp == null || wp.length == 0) {
                    pathWpX = new float[0];
                    pathWpY = new float[0];
                    debugFile("[path] test: NO PATH to (" + Reflect.fmt(PATH_TEST_X) + "," + Reflect.fmt(PATH_TEST_Y) + ")");
                } else {
                    pathWpX = new float[wp.length];
                    pathWpY = new float[wp.length];
                    for (int i = 0; i < wp.length; i++) {
                        pathWpX[i] = wp[i][0];
                        pathWpY[i] = wp[i][1];
                    }
                    debugFile("[path] test: " + wp.length + " waypoints to (" + Reflect.fmt(PATH_TEST_X) + "," + Reflect.fmt(PATH_TEST_Y) + ")");
                }
                pathWpIdx = 0;
                pathTestPlanned = true;
            }
            if (pathWpX == null || pathWpX.length == 0) {
                clearMovementKeys();
                return;
            }
            int last = pathWpX.length - 1;
            while (pathWpIdx < pathWpX.length) {
                float ddx = pathWpX[pathWpIdx] - mp[0], ddy = pathWpY[pathWpIdx] - mp[1];
                float thr = (pathWpIdx == last) ? PATH_TARGET_ARRIVE_SQ : PATH_ARRIVE_SQ;
                if (ddx * ddx + ddy * ddy <= thr)
                    pathWpIdx++;
                else
                    break;
            }
            if (pathWpIdx >= pathWpX.length) {
                clearMovementKeys();
                if (!pathTestDone) {
                    debugFile("[path] test: arrived");
                    pathTestDone = true;
                }
                return;
            }
            clearMovementKeys();
            setMovementFromDelta(pathWpX[pathWpIdx] - mp[0], pathWpY[pathWpIdx] - mp[1]);
        } catch (Exception e) {
            clearMovementKeys();
            if (debug)
                debugFile("[path] test error: " + e);
        }
    }

    // ── Stage routine (multibox Ctrl+R): full-auto clear of a hardcoded stage ──
    //
    // A per-step state machine on the MAIN. Each step is a (type, x, y). Movement
    // uses the A* pathfinder; combat/loot reuse isCombatBot / isLootMode; SHOOT
    // uses a dedicated poll fire (routineShoot*). Alts trail via auto-follow
    // (assumed ON). Sub-states within a step are numbered per type (see below).

    public static volatile boolean isRoutineActive = false;
    // Campaign (Ctrl+R full run): run each floor's routine head-to-toe, auto-starting the
    // next floor's routine once the whole party has loaded in, until a TERMINAL floor.
    public static volatile boolean campaignActive = false; // gates tickCampaign (Patcher-stubbed)

    /** True while ANY full-auto mode drives this client: the Ctrl+R cycle
     *  (campaignActive on the main, its autoAdvanceOn broadcast mirror on every
     *  client) or the Ctrl+Q PvP autoqueue. Relog gates auto-reconnect on this —
     *  a NEW full-auto mode's flag belongs in this list, or its clients won't
     *  self-reconnect after a disconnect. */
    static boolean isFullAutoArmed() {
        return campaignActive || autoAdvanceOn || isAutoPvpQueue;
    }
    private static String campaignLastFloor = null;        // floor key of the running/last routine (detects a NEW floor)
    private static long campaignLoadedSince = 0L;          // when the party finished loading on the new floor (settle start)
    // Active mission (multi-mission, data-driven): routines/active_mission.txt names the mission
    // subfolder; routines/<mission>/mission_data.txt = "<launchId> <numFloors> <difficulty>".
    // Loaded at Ctrl+R by loadActiveMission(). The terminal floor is the numFloors-th non-lobby
    // floor (campaignFloorSeq counts them), so no per-mission floor NAME is hardcoded.
    private static String campaignMissionDir = null;       // routines/<active mission subfolder>
    private static String campaignMissionId = null;        // launch id (mission_data.txt token 0)
    private static String campaignDifficulty = "HARD";     // launch difficulty (mission_data.txt token 2)
    private static int campaignNumFloors = 0;              // floors EXCLUDING the lobby (mission_data.txt token 1)
    private static int campaignFloorSeq = 0;               // non-lobby floors started this mission; terminal when == campaignNumFloors
    private static final long CAMPAIGN_LOAD_SETTLE_MS = 1500L;         // wait this long after all loaded before starting the routine
    private static final int CAMPAIGN_PARTY_SIZE = SKConfig.PARTY_SIZE; // main + alts (config party_size, default 4) — wait for all of them to be present before starting a floor
    // Mission-lobby JOIN-BUG backup: alts sometimes can't join while the main is in the lobby
    // (join requests hang; clears once the main hits floor 1). If the party ROSTER is still just
    // the main this long after entering the lobby, start the lobby routine solo → floor 1, where
    // the alts CAN join (the per-floor presence wait then catches them).
    private static long lobbyBugSince = 0L;                           // when the lobby roster was first seen stuck at 1 (0 = not stuck)
    private static final long CAMPAIGN_LOBBY_BUG_MS = 10000L;         // roster stuck at 1 this long in the lobby => the join bug
    // Automatic lobby forge pass: each mission lobby, every client (main + alts) runs
    // ForgeAllAdapter.autoForgeAll (forge every heat-ready item + replace fully-leveled
    // equipped items with fresh same-name copies). The main kicks it off on lobby entry
    // (broadcast FORGEALL + its own pass) and HOLDS the lobby routine until its own pass
    // is done AND every alt has replied FORGEDONE (or the backstop expires).
    public static volatile boolean forgePassPending = false;   // alt-side: FORGEALL received; run once we're in the lobby
    private static volatile boolean forgePassRunning = false;  // this client's pass thread is active
    // Invite-based lobby fill: while the campaign runs and the roster is short, the MAIN
    // party-invites the missing alts (their knight names gathered by UDP round-trip)
    // instead of waiting on uninvited auto-join. An invite flips the server-side
    // joinability the alts' 3s AutoJoiner poll checks, so invited alts join ~100%
    // reliably (user-observed); the alt-side AutoJoiner is unchanged (it IS the accept).
    private static long inviteNextAt = 0L;                     // next invite sweep no earlier than this
    private static volatile boolean inviteFillRunning = false; // an invite sweep thread is active
    private static final long INVITE_INTERVAL_MS = 5000L;      // base gap between invite sweeps (+0-1s jitter)
    private static final long INVITE_FIRST_DELAY_MS = 5000L;   // settle before the FIRST invite of a fresh lobby (+jitter): alts invited mid-scene-transition blackscreen between loading screens
    private static final long INVITE_FIRST_JITTER_MS = 1500L;
    private static long campaignForgeStartedAt = 0L;           // when the main kicked off this lobby's pass (0 = not yet)
    private static volatile int forgeDoneAltCount = 0;         // FORGEDONE replies this lobby (UDP thread only)
    private static final long FORGE_HOLD_BACKSTOP_MS = 30000L; // hold the lobby at most this long for forging

    // Endless cycle: at the terminal floor, don't stop — the mission auto-advances
    // everyone to town in ~15s; then (in town, launch-capable) the main relaunches
    // the mission privately on Elite, the alts auto-join, the fresh lobby loads, and
    // the campaign restarts. Loops until Ctrl+R off.
    private static boolean campaignRestartPending = false; // in the terminal→town→relaunch→lobby sequence
    private static int campaignRestartPhase = 0;           // 0 = await town, 1 = await the fresh lobby
    private static long campaignTownSince = 0L;            // when town (no LevelPartyObject) was first seen (settle start)
    // PARTIAL RUNS. mission_data's numFloors is AUTHORITATIVE:
    // set it below the mission's real floor count and the cycle treats that many floors as
    // a complete run — repeating floor 1 can pay better than clearing the whole mission. The
    // catch is that the mission is then still LIVE when the routine list runs out, so the
    // normal "wait to be returned to town" restart never fires and the run used to hang
    // until the 2-minute idle watchdog aborted it (logging a bogus FAILURE). The restart
    // flow now asks campaignKnowsFloor() instead of waiting: no routine file for where we
    // stand => nothing left to do => launch a fresh mission IN PLACE (the same mid-mission
    // launch campaignAbort uses, which pulls the main into a new lobby for the invite fill).
    private static long campaignRelaunchAt = 0L;           // when launchMission was called (lobby-wait timeout)
    static final String CAMPAIGN_LOBBY_KEY = "mission_lobby"; // scene key of the fresh mission lobby (shared across all missions; package-private: SpriteFeeder)
    private static final String MISSION_DATA_NAME = "mission_data.txt"; // per-mission config file inside its subfolder
    // The town settle is RANDOMIZED per cycle: a value in [min,max] chosen when the settle starts.
    // (The old pre-launch ready-room hop + alt RRPORT porting were removed once the invite-based
    // lobby fill landed — invites pull alts into the fresh lobby from anywhere.)
    private static final long CAMPAIGN_TOWN_SETTLE_MIN_MS = 1000L, CAMPAIGN_TOWN_SETTLE_MAX_MS = 3000L;
    private static long campaignTownSettleMs = 0L;      // the town settle randomly chosen this cycle
    private static final long CAMPAIGN_RELAUNCH_TIMEOUT_MS = 40000L;  // no lobby within this => retry the launch


    // No-progress watchdog: if the main sits within STUCK_MOVE_EPS of an anchor for
    // STUCK_TIMEOUT_MS on the SAME step, the run is stuck (blocked path, unexpected mob) —
    // abort + relaunch a fresh mission. A KEY_LIFT/GATE timeout aborts immediately (a floor
    // can't be finished without those objectives).
    private static int stuckStepIdx = -1;                            // step the watchdog is tracking
    private static float stuckAnchorX = 0f, stuckAnchorY = 0f;       // last position that counted as progress
    private static long stuckSince = 0L;                             // when progress was last seen
    private static final float STUCK_MOVE_EPS_SQ = 2.25f;            // (1.5 tiles)^2 — moving this far from the anchor = progress
    private static final long STUCK_TIMEOUT_MS = 60000L;             // no progress this long on one step => stuck (abort + relaunch)
    // ABSOLUTE per-step cap, independent of movement. The watchdog above is
    // POSITION-based and re-anchors on any real move, which a combat phase defeats
    // completely: the main orbits a 3-tile circle, so an arena that never clears
    // reads as healthy progress forever. Same for the 2-minute idle switch (also
    // position-based) and the died-twice rule (counts only the MAIN's deaths — a
    // party wiped except the main never reaches 2). That combination let a run sit
    // in combat for SIX HOURS with the alts dead and no failsafe firing, and no
    // stats row written because campaignAbort was never reached. This cap is the
    // backstop that needs no assumption about movement, health or step type.
    private static int routineStepIdx = 0;
    private static int routineSub = 0;
    private static long routineSubStart = 0L;
    private static final PathFollow routinePath = new PathFollow(); // routine A* follow state
    private static final PathFollow lootPath = new PathFollow();     // loot A* follow state
    private static long routineCombatClearSince = 0L;
    private static long routineCombatEndAt = 0L; // when the last COMBAT phase concluded (for the pre-loot delay)
    private static int routineWaveCount = 0;
    // The tracked wave's live enemy actor-IDs. A wave clears when this set empties
    // OR is replaced by a disjoint set (the next wave) — identity-based, so it
    // never depends on sampling the (possibly sub-tick) zero-window between waves.
    private static java.util.HashSet<Integer> routineWaveIds = new java.util.HashSet<Integer>();
    // PLATFORM arena: the exact set of arena tiles (interior + monster-gate tiles), flood-filled
    // once at combat start from (X,Y) with unwalkable tiles AND monster gates as walls. Wave
    // detection counts enemies whose tile is in this set. Empty/null => no monster gates found,
    // fall back to the fixed ROUTINE_ENEMY_RADIUS_SQ circle.
    private static java.util.HashSet<Long> platformArenaCells = null;
    private static long routineShootStart = 0L;
    private static volatile boolean routineCombatBroadcast = false; // last combat state pushed to the alts
    // SHOOT fire (poll-driven, independent of the combat bot).
    public static volatile boolean routineShootActive = false;
    public static volatile float routineShootAngle = 0f;
    // SHOOT reroute (user): the ALTS fire weapon 2 at the target while the MAIN only
    // monitors + advances — the main never fires, so a carried key is never dropped
    // (SHOOT is safe between KEY and GATE). Main broadcasts "SHOOTALT x y" / "SHOOTALT off".
    public static volatile boolean isRoutineShootAlt = false;    // alt: fire-at-target mode
    public static volatile float shootAltX = 0f, shootAltY = 0f; // alt: the SHOOT target world coord
    private static long shootAltSelectedAt = 0L;                 // alt: when weapon 2 was selected (for the settle)
    public static volatile boolean routineShootHeld = false;
    public static volatile long routineShootReleaseAt = 0L;
    public static volatile long routineShootNextAt = 0L;
    // MINERALS (destroy mineral nodes, then gather the drops). Gather-phase state
    // is shared across main + alts; the tap fields are one-per-process.
    public static volatile boolean isMineralGather = false; // gather phase active (main + alts)
    // Assignment: pawn id -> {x,y} of the mineral drop that character should collect.
    // Populated locally on the main and broadcast (keyed by pawn id) to the alts.
    private static final java.util.HashMap<Integer, float[]> mineralAssignMap = new java.util.HashMap<Integer, float[]>();
    // Pawn ids that are already holding a mineral this floor (main-side authority;
    // each character holds at most one per floor). Cleared when the routine starts.
    private static final java.util.HashSet<Integer> mineralHeldIds = new java.util.HashSet<Integer>();
    // Remembered node positions = where the drops land (nodes are gone by gather time).
    private static float[] mineralNodeX = new float[0], mineralNodeY = new float[0];
    private static int mineralNodeCount = 0;
    private static boolean mineralGatherPlanned = false;
    private static long mineralGatherStart = 0L, mineralShootStart = 0L;
    // Pickup tap (poll-driven; one per process/character). "Attack button" tap that
    // collects the drop the character is standing on.
    public static volatile boolean mineralTapActive = false;
    public static volatile boolean mineralTapHeld = false;
    public static volatile long mineralTapReleaseAt = 0L, mineralTapNextAt = 0L;
    public static volatile float mineralTapAngle = 0f;     // aim of the pickup click (must face the drop)
    // Pickup PRE-AIM (KEY_LIFT/LIFT/MINERALS): while true, the poll parks the cursor
    // toward pickupAimAngle every poll (no clicks) so the knight FACES the object
    // through the settle/approach — the later tap then can't misfire as a turn+attack.
    public static volatile boolean pickupAimActive = false;
    public static volatile float pickupAimAngle = 0f;
    public static volatile boolean mineralPickedUp = false; // this character grabbed its assigned drop
    private static final float MINERAL_SCAN_RADIUS_SQ = 25f;      // (5 tiles)^2
    private static final float MINERAL_PICKUP_RADIUS_SQ = 1.0f; // (1 tile)^2 — drops are collidable, so the char's
                                                                // center stops well short of the drop; 0.39/0.5 were unreachable
    private static final float MINERAL_DROP_MATCH_SQ = 1.0f;      // a drop within 1 tile of the target = still on the ground
    private static final long MINERAL_SHOOT_TIMEOUT_MS = 12000L;  // safety cap on the destroy phase
    private static final long MINERAL_GATHER_TIMEOUT_MS = 8000L;  // grab window, then advance (optimistic fallback)

    // KEY (pick up a Dynamic/Lift Objects/Gold Key). Same collidable-pickup feel as
    // minerals, but attack TOGGLES carry — a second tap drops the key — so we tap
    // ONCE per attempt and only while the key is still on its spawn tile. One per
    // process (KEY is main-driven; alts just breadcrumb-follow).
    public static volatile boolean keyTapFire = false;    // tick requests one attack tap; poll clears it after the release
    public static volatile boolean keyTapHeld = false;    // poll: mouse currently pressed
    public static volatile long keyTapReleaseAt = 0L;     // poll: when to release
    public static volatile float keyTapAngle = 0f;        // aim of the tap (face the key)
    public static volatile long keyTapHoldMs = 80L;       // attack-tap hold (ms); gates sometimes need a longer press
    private static final long KEY_TAP_HOLD_DEFAULT_MS = 80L; // restore value — ALCH_CHARGE borrows keyTapHoldMs for a 1.6s charge, and leaving it long would turn every later KEY/GATE tap into a charged attack

    // ALCH_CHARGE X Y W Z — charge-shot a switch a straight line can't reach. Holds
    // weapon 2's attack aimed at (X,Y) for ALCH_CHARGE_HOLD_MS, releases, then looks at
    // the GHOST GATE at (W,Z); repeats until that gate is gone. For ricochet guns
    // (Alchemer family), whose charged shot banks off walls — so (X,Y) is a BANK ANGLE,
    // not the switch itself. MAIN-only; alts just hold position.
    private static boolean alchSawGhost = false;   // latch: the gate was observed present (guards a phantom "already open")
    private static long alchAbsentSince = 0L;      // when the never-seen-gate confirmation began
    private static long alchNextChargeAt = 0L;     // no new charge before this (release + recovery gap)
    private static long alchOpenedAt = 0L;         // when the gate was first seen open (chain-reaction settle)
    private static final long ALCH_OPEN_SETTLE_MS = 2500L; // the ghost goes first, its wired blocks follow over ~1-2s — let them all clear
    // The charged shot KNOCKS THE MAIN BACK (user-observed), and the whole technique is a
    // BANK ANGLE off geometry — a knight that has drifted even a fraction of a tile aims
    // the ricochet somewhere else. So every charge is fired from the SAME spot: the
    // position held when the step armed, restored by PIN_MOVETO's push-until-collision
    // (PIN_MOVE_EPS/PIN_STALL_MS) rather than by any stopping tolerance.

    // PIN_MOVETO X Y — park the main HARD AGAINST collision at (X,Y). PRECISE_MOVETO
    // stops within a tolerance, and no tolerance is tight enough for a ricochet bank
    // angle: the user needs the knight pressed against a map-edge tile at a coordinate
    // like (11.67,-8.67), which is not a tile centre and can't be hit by stopping — it
    // is where the WALL puts you. So don't aim to stop there: push into it and let
    // collision do the placing, which is exact and repeatable to the pixel. Arrival =
    // the push stops making progress (the GATE "kissing" test), not a distance.
    private static float pinBestDist = Float.MAX_VALUE; // closest approach so far this step
    private static long pinStalledSince = 0L;           // when progress last beat PIN_MOVE_EPS
    private static final float PIN_MOVE_EPS = 0.02f;    // must close this many tiles to count as progress
    private static final long PIN_STALL_MS = 500L;      // no progress this long while pushing = pinned
    private static final float PIN_ENGAGE_SQ = 4f;      // (2 tiles)^2 — start pushing (not pathing) inside this
    private static final long PIN_TIMEOUT_MS = 20000L;  // give up and advance; ALCH_CHARGE's own timeout is the real guard
    // PIN HOLD — the scoped form: PIN_MOVETO X Y START ... PIN_MOVETO END. Between the
    // markers the main is pushed into (X,Y) EVERY TICK by the driver at the end of
    // tickRoutine, so it stays wall-seated through whatever steps run in between.
    // Per-shot recovery inside ALCH_CHARGE was tried first and did NOT work in play
    // (user: "doesn't move at all between shots, just gets pushed back") — a step that
    // clears movement keys and re-decides each tick fights itself. Holding the push
    // continuously is simpler AND stronger: recoil never gets to accumulate, because the
    // knight is already leaning into the wall when the shot lands.
    // NOT per-step state: it deliberately outlives the step that opened it, so it is
    // reset only by PIN_MOVETO END and by routineStopCleanup.
    private static boolean pinHoldActive = false;
    private static float pinHoldX = 0f, pinHoldY = 0f;

    /**
     * Keeps the main pressed against the pin target while a PIN_MOVETO scope is open.
     * Runs AFTER the step switch so it wins over whatever the step did with the movement
     * keys. Pushing into collision costs nothing when already seated — the knight simply
     * doesn't move — which is what makes it safe to hold through a charge.
     */
    private static void tickPinHold(float[] mp) {
        if (!pinHoldActive || mp == null)
            return;
        float dx = pinHoldX - mp[0], dy = pinHoldY - mp[1];
        if (dy > PIN_MOVE_EPS)
            DOWN.add("W");
        else if (dy < -PIN_MOVE_EPS)
            DOWN.add("S");
        if (dx > PIN_MOVE_EPS)
            DOWN.add("D");
        else if (dx < -PIN_MOVE_EPS)
            DOWN.add("A");
    }
    private static final long ALCH_CHARGE_HOLD_MS = 1800L;   // attack held this long = a full charge (1.6s→1.8s, user-set)
    private static final long ALCH_CHARGE_GAP_MS = 500L;     // recovery between the release and the next charge
    private static final float ALCH_GHOST_MATCH_SQ = 2.25f;  // (1.5 tiles)^2 — a ghost block this close to (W,Z) is THE gate
    private static final long ALCH_ABSENT_CONFIRM_MS = 2000L; // gate never seen: confirm before advancing
    private static final long ALCH_TIMEOUT_MS = 45000L;      // under the 60s no-progress watchdog, so this reason wins
    private static long keyLastTapAt = 0L;                // tick: spacing between single taps
    private static long keyInRangeSince = 0L;             // tick: when the main first reached pickup range (settle before the first tap)
    private static final float KEY_PICKUP_RADIUS_SQ = 1.0f;  // (1 tile)^2 — matches the mineral collidable-pickup radius
    private static final long KEY_TAP_INTERVAL_MS = 1000L;   // min gap between GATE deposit taps (matches KEY_LIFT's 1000ms — a tap sent during/just after an attack anim is rejected)
    private static final long KEY_PICKUP_INTERVAL_MS = 1000L; // min gap between KEY pickup taps: the game rejects a pickup press sent during/just after an attack animation (a failed pickup = an attack), so retries too close together loop forever
    private static final long KEY_PICKUP_SETTLE_MS = 500L;    // wait this long after REACHING the object before the FIRST tap (1000→500: the pre-aim now faces it from the moment of arrival, so the settle only needs to cover the turn, not hide the aimless window)
    private static final long KEY_TIMEOUT_MS = 12000L;       // give up and advance if the key never lifts
    // STATUE geometry (user-diagnosed): keys have a 0.5-radius CIRCLE collision, so
    // the main gets within KEY_PICKUP_RADIUS of the centre; statues/totems carry a
    // full 1x1 RECT (corner 0.707) + the main's own radius, which can pin the main
    // OUTSIDE that radius. So LIFT also accepts a GATE-style PIN: pressed against
    // the object with no progress = arrived, tap from there.
    private static float liftPinDist = Float.MAX_VALUE; // best (min) distance (tiles) to the liftable so far
    private static long liftPinSince = 0L;              // when that best last improved by >= LIFT_PIN_EPS
    private static final float LIFT_PIN_EPS = 0.05f;    // must get this many tiles closer to count as progress
    private static final float LIFT_PIN_MAX_SQ = 4f;    // only "pinned" if within 2 tiles of the object
    private static final long LIFT_PIN_MS = 400L;       // no progress this long while pushing = pressed against it
    private static final float LIFT_HANDOFF_SQ = 2.25f; // (1.5 tiles)^2 — sub0 → tap phase on PROXIMITY (pathTo can never "arrive" on a solid object's own tile); 2→1.5 to keep A* driving a little longer, since sub1's close-in is a straight-line push
    // CARRY STATE is read off the object: liftables are DungeonLift actors, and
    // Actor._flags carries bit 4 EXACTLY while one is held. VERIFIED in-game with the
    // [liftdiag] dump (2026-07-28): across a lift/drop cycle _flags alternated 4 (held,
    // and the statue's position equalled the carrier's) and 0 (grounded). The Lift
    // class's own _z and _dropPoint fields are NOT usable client-side — both read 0.0 /
    // null the entire time, held or not, which is why the first cut of this never saw a
    // pickup and re-tapped the statue down again.
    private static final int LIFT_CARRIED_FLAG = 4;        // Actor._flags bit set while a liftable is held
    // (2 tiles)^2. The carried object TRAILS the carrier rather than riding exactly on
    // it — measured up to 0.96 tile while held, which all but touched the old 1-tile
    // radius and would have read as "dropped" at peak lag. _flags is the real carry
    // test; this radius only has to reject an object an ALT is carrying nearby.
    private static final float LIFT_CARRY_RADIUS_SQ = 4f;
    // How far from the authored (X,Y) to look for the object to pick up. LIFT is STRICT:
    // a statue/totem is placed by the level and sits ON the coordinate, so 0.5 tile only
    // absorbs a nudge and can never latch a different object. KEY_LIFT is TOLERANT
    // (user-directed): a key is often put down by a preceding KEY_DROP, which lands it
    // anywhere within ~a tile of ITS coordinate, so a strict search would miss the very
    // key the routine just set down. Tolerance is safe here precisely because KEY_LIFT's
    // matcher is gold-key-only (liftableForStep) and the search takes the CLOSEST match.
    private static final float LIFT_TARGET_RADIUS_SQ = 0.25f;     // (0.5 tile)^2 — LIFT/STATUE_LIFT
    private static final float KEY_LIFT_TARGET_RADIUS_SQ = 2.25f; // (1.5 tiles)^2 — KEY_LIFT (1.0→1.5, user-set)
    // Tap window for DROP. MUST stay above setMovementFromDelta's deadzone (it only
    // presses keys past distSq 0.15 = 0.387 tile) AND above the carried object's lag
    // behind its carrier (measured up to ~0.22 tile). At the old 0.3 the two bands
    // overlapped: inside 0.30..0.387 the main neither moved nor tapped, the lagging
    // object jittered the delta across the edge, and every exit reset the settle
    // (keyInRangeSince) — so the knight paced back and forth and never dropped.
    private static final float DROP_PLACE_EPS_SQ = 0.25f;  // (0.5 tile)^2 — object this close to (X,Y) = stop and tap
    // DROP does NOT need KEY_PICKUP_SETTLE_MS. That 500ms exists so a LIFT tap isn't
    // read as an ATTACK on arrival — impossible here, since carrying leaves no hands
    // free to attack, so the tap can only mean "put it down". All this has to cover is
    // the knight's momentum bleeding off (and the trailing object catching up) so the
    // drop lands where it was aimed.
    private static final long KEY_DROP_SETTLE_MS = 150L;

    // GATE (unlock a locked gate with the carried key). Gates are Door actors
    // ("Door | Door/Iron Gate/..."), NOT Block/Stateful/Switch. A wide gate stops
    // the main well short of the GATE coordinate (the gate's centre), so a fixed
    // in-range radius never triggers. Instead, drive toward the gate until progress
    // STALLS (pressed against its collision = "kissing"), then STOP pushing and tap
    // in place — tapping while pushing/mispositioned drops the key instead of
    // depositing it. Retry until the Door transitions out of its locked state (its
    // _stateEntered changes) or is removed; gateSawDoor guards an early advance.
    private static boolean gateSawDoor = false;     // saw the Door actor (arms the unlock check)
    private static Integer gateDoorBaseline = null; // Door._stateEntered when first (locked) seen
    private static long gateInRangeSince = 0L;      // when the main first kissed the gate (for settle)
    private static float gatePinDist = Float.MAX_VALUE; // best (min) DISTANCE (tiles) to the gate reached so far
    private static long gatePinSince = 0L;          // when that best last improved by >= GATE_PIN_EPS (stall => kissing)
    private static final float GATE_DOOR_MATCH_SQ = 2.25f; // a Door within 1.5 tiles of (X,Y) is this gate
    private static final float GATE_PIN_EPS = 0.2f;       // must get this many TILES closer to count as progress (else it's clipping)
    private static final float GATE_PIN_MAX_SQ = 9f;      // only "kissing" if within 3 tiles of the gate centre
    private static final long GATE_PIN_MS = 400L;         // <GATE_PIN_EPS tiles of progress for this long = pressed against the gate
    private static final long GATE_SETTLE_MS = 350L;    // stay stopped against the gate this long before the first tap
    private static final long GATE_TIMEOUT_MS = 12000L; // hard cap: advance even if the unlock is never observed

    // BUTTONSWEEP: MOVETO (X,Y), scan for buttons within range (optional 3rd routine
    // param, in tiles; default BSW_SCAN_RADIUS_SQ), then for each
    // (nearest-first): SHOOT the block covering it (if any), step on it to trigger,
    // and finally return to (X,Y). Main-only (alts breadcrumb-follow).
    private static float[] bswX = new float[0], bswY = new float[0]; // detected button positions, nearest-first
    private static int bswCount = 0, bswIdx = 0, bswPhase = 0;       // count, current button, per-button phase
    private static long bswPhaseStart = 0L;
    private static final float BSW_SCAN_RADIUS_SQ = 60f;   // DEFAULT scan radius² (~7.75 tiles; covers the 5 floor-2 button spots from (21.5,57.5), max dist sqrt(58)=7.62); the optional 3rd routine param overrides (RANGE in tiles)

    // SWITCHSWEEP: MOVETO (X,Y), scan LEVERS (config "switch/lever") within range
    // (optional 3rd param, in tiles; default = BUTTONSWEEP's radius), then per lever
    // (nearest-first) approach to firing range and SHOOT it until its _state reads
    // TRIGGERED (SHOOT-style completion). Levers ALREADY thrown at scan time are
    // skipped outright — never walked to, never shot. One-Time levers are
    // SOLID + collidable (see actorIsObstacle) so stepping on them is impossible —
    // a shot flips them. A breakable COVERING (shrub/stone/crystal) near the lever
    // is shot first (clearBreakableNear, like BUTTONSWEEP's press phase). Finally
    // return to (X,Y). MAIN fires (like TREASURESWEEP), so never SWITCHSWEEP while
    // carrying a key. Alts breadcrumb-follow.
    private static float[] sswX = new float[0], sswY = new float[0]; // UNTRIGGERED lever positions, nearest-first
    private static int sswCount = 0, sswIdx = 0, sswPhase = 0;       // count, current lever, per-lever phase
    private static long sswPhaseStart = 0L;
    private static final float SSW_SHOOT_RANGE_SQ = 16f;      // (4 tiles)^2: close to here before shooting (like TREASURESWEEP)
    private static final long SSW_SHOOT_TIMEOUT_MS = 8000L;   // give up on a stubborn lever (covers a 3-hit covering + the flip; matches BUTTON_TIMEOUT_MS)
    private static final long SSW_APPROACH_TIMEOUT_MS = 5000L; // give up on an UNREACHABLE lever (levers are SOLID on the walk grid)

    // TREASURESWEEP: MOVETO (X,Y), scan treasure blocks ("Block/Treasure") within
    // range (optional 3rd routine param, in tiles; default TSW_SCAN_RADIUS_SQ),
    // then per block (nearest-first) approach to firing range and shoot until
    // it's destroyed. Finally return to (X,Y) and, if anything was destroyed, run the
    // LOOT subroutine from there to collect the drops. Main-only (alts breadcrumb-follow).
    private static float[] tswX = new float[0], tswY = new float[0]; // treasure-block positions, nearest-first
    private static int tswCount = 0, tswIdx = 0, tswPhase = 0;        // count, current block, per-block phase
    private static long tswPhaseStart = 0L;
    private static long tswStepStart = 0L;                   // when the whole step began (0 = not started; step-skip backstop)
    private static final float TSW_SCAN_RADIUS_SQ = 64f;    // DEFAULT scan radius² ((8 tiles)^2, from (X,Y)); the optional 3rd routine param overrides (RANGE in tiles)
    private static final float TSW_BLOCK_MATCH_SQ = 0.36f;  // a treasure block within 0.6 tile of a recorded spot = still there
    private static final float TSW_SHOOT_RANGE_SQ = 16f;    // (4 tiles)^2: close to here before shooting
    private static final long TSW_SHOOT_TIMEOUT_MS = 6000L; // give up on a stubborn block
    private static final long TSW_APPROACH_TIMEOUT_MS = 5000L; // give up on an UNREACHABLE block (treasure blocks are SOLID on the walk grid, so a pocketed block's pathTo fails forever — this was the idle-stall)
    private static final long TSW_STEP_TIMEOUT_MS = 40000L; // whole-step backstop: SKIP the step (advance, keep the mission) before a stall can ride into the watchdog's mission-failing abort

    // HAZARD_MOVETO: a MOVETO that waits-and-crosses spike-trap fields. Each trap has
    // a static TileEntry ("Traps and Hazards/Floor/Spike/UxV" — gives the footprint;
    // the centre-coordinate parity gives orientation) and a co-located Trap actor
    // ("…/Floor/Spikes" — gives _state UP/DOWN/WARN). We step onto a trap whenever it
    // reads DOWN (WARN is a sufficient buffer before UP); once on it, commit and push
    // through. A trap that's already DOWN on first scan is crossable — no freshness req.
    private static final PathFollow hazPath = new PathFollow();
    private static Object trapCellModel = null;                 // scene the trap-cell map was built for
    private static java.util.HashMap<Long, Long> trapCellKey = new java.util.HashMap<Long, Long>(); // footprint cell -> trap centre-key
    private static final java.util.HashMap<Long, String> trapLastState = new java.util.HashMap<Long, String>(); // centre-key -> last observed state
    // Keys whose CURRENT DOWN period began while we were watching (an observed
    // non-DOWN -> DOWN transition). Only these are crossable — see trapSafe.
    private static final java.util.HashSet<Long> trapDownEdge = new java.util.HashSet<Long>();
    private static long trapLastSampleAt = 0L;             // last updateTrapStates run (gap => phases unknown again)
    private static final long TRAP_OBS_GAP_MS = 1500L;     // observation gap longer than this invalidates all edges

    // Breakable-block clearing (MOVETO + LOOT). Breakable blocks are walkable, but
    // when one sits on the path and the main gets within range, shoot it (weapon 2)
    // like SHOOT until it's gone. Reuses routineShoot* for the actual firing.
    private static boolean blockClearActive = false;      // currently firing at a path block (weapon selected)
    private static long blockClearSelectAt = 0L;          // when weapon 2 was selected (for the settle)
    private static final float BLOCK_SHOOT_RANGE_SQ = 16f; // (4 tiles)^2 — start shooting a path block within this of the main
    private static final float BLOCK_PATH_MATCH_SQ = 0.64f; // block within 0.8 tile of a remaining waypoint = on the path
    private static final float BLOCK_COLLIDE_SQ = 1.44f;    // (1.2 tiles)^2 — a breakable this close is blocking the main even if its waypoint was already passed
    private static final float BUTTON_BREAKABLE_RADIUS_SQ = 2.25f; // (1.5 tiles)^2 — a shrub this close to a BUTTON blocks the press; shoot it
    // Tunables.
    // DEFAULT enemy-detection radius² for COMBAT/COMBATLOOT/ATTACKMOVE — overridden per
    // step by their optional 3rd param (RANGE in tiles; see stepEnemyRadiusSq). Still
    // FIXED for SNARBY's exit test and PLATFORM's arena fallback.
    private static final float ROUTINE_ENEMY_RADIUS_SQ = 100f; // (10 tiles)^2
    private static final long COMBAT_CLEAR_DEBOUNCE_MS = 500L;
    // COMBATLOOT combat orbit: the MAIN revolves on a 1-tile circle around the
    // command's (X,Y) instead of standing still (a sitting target). It steers toward a
    // point ORBIT_LEAD radians ahead of its current angle around the centre, so it keeps
    // circling, walks back onto the ring if an attack knocks it off, and always moves a
    // chord (~0.69 tile) past the movement deadzone. No walkability check — assumes ~1
    // tile of clear ground around the coord (place COMBATLOOT coords in open space).
    private static final float ORBIT_RADIUS = 1.5f; // tiles from (X,Y) (1.0→1.5, user-tuned)
    private static final float ORBIT_LEAD = 0.7f;   // radians look-ahead (CCW); chord at R=1.5 ≈ 1.03 tiles, well clear of the ~0.39 movement deadzone
    // Stutter-step (user-directed): travel ORBIT_MOVE_MS, stand ORBIT_PAUSE_MS, repeat —
    // instead of a smooth continuous circle. Shared phase state is fine: only the MAIN
    // orbits, one orbit at a time; a stale phase self-corrects within one cycle.
    private static final long ORBIT_MOVE_MS = 1000L; // (1500→1000, user-tuned)
    private static final long ORBIT_PAUSE_MS = 1000L;
    private static long orbitPhaseUntil = 0L;   // when the current move/pause phase ends
    private static boolean orbitMoving = false; // current phase (first call flips this true)
    // After a COMBAT phase concludes, wait this long before starting LOOT so
    // monster death animations finish dropping loot (else pickups are missed).
    private static final long LOOT_POST_COMBAT_DELAY_MS = 1000L;
    private static final int ROUTINE_WAVES = 3;
    private static final float GATHER_RADIUS_SQ = 1f; // (1 tile)^2 — tight: "gathered" means the alts are packed onto the pad with the main (elevator descent needs the whole party on it); 2 tiles let an alt count while off the edge
    private static final long GATHER_TIMEOUT_MS = 30000L; // all gather waits
    private static final long SHOOT_SETTLE_MS = 350L;
    private static final long SHOOT_TIMEOUT_MS = 4000L;
    // KILL: alts fire at (X,Y) until the Monster there is GONE. A stationary mob with
    // real HP (wheel launcher) needs far longer than SHOOT's block-pop timeout.
    private static final long KILL_TIMEOUT_MS = 20000L;
    private static final float KILL_RADIUS_SQ = 2.25f; // (1.5 tiles)^2 — match the mob at (X,Y); stationary targets only
    private static final long KILL_ABSENT_CONFIRM_MS = 2000L; // absence must persist this long before "no target"

    // PRECISE_MOVETO: put the WHOLE PARTY on the target's exact tile. Two phases on the
    // main — normal pathing, then a fine per-axis settle — because two coarse layers
    // make a plain tighter threshold impossible: the final-arrival radius is 1 tile
    // (PATH_TARGET_ARRIVE_SQ) and setMovementFromDelta ignores deltas under ~0.39, so a
    // 0.3 arrival test would deadlock in the 0.3..0.39 band. The alts get a dedicated
    // PRECISEGATHER drive for the same reason (breadcrumb-follow parks ~0.39 from the
    // main, which can be the NEXT TILE over).
    private static final float PRECISE_SETTLE_EPS = 0.15f;  // per-axis: main settles within this of (X,Y)
    // Alt-side precision gather (set by the PRECISEGATHER broadcast).
    private static volatile boolean preciseGatherOn = false;
    private static volatile float preciseGX = 0f, preciseGY = 0f;
    private static boolean killSawTarget = false;        // latch: target observed alive this step
    private static long killAbsentSince = 0L;            // when the pre-fire absence confirmation began

    /**
     * A Monster-class actor within KILL_RADIUS of (tx,ty) — PRESENCE only, no health
     * test (see the T_KILL comment: passive trap-mobs read _healthPct 0 while alive).
     */
    private static boolean killTargetPresent(Object view, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return false;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> monsterCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Monster");
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !monsterCls.isInstance(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                if (dx * dx + dy * dy <= KILL_RADIUS_SQ)
                    return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    private static final long BUTTON_TIMEOUT_MS = 8000L; // give up waiting for a floor button to flip
    // SNARBY (Snarbolax boss fight): STAND at (X,Y); loop shield → boss within 2 tiles of a
    // Beast Bell → MAIN rings the bell (weapon 2) → boss STUNNED (readable condition, name
    // contains "stun") → all drop shields + combat (weapon 1) → stun ends → re-shield; exit
    // when no monster within 10 tiles. Reuses routineShieldHold (main shield) + SHIELD
    // broadcast (alts) + routineShoot* (bell) + isCombatBot/combat-mirror (attack).
    private static boolean isSnarbyActive = false;            // gates serviceShieldBump off (SNARBY owns routineShieldHold)
    private static boolean snarbyAltShieldBroadcast = false;  // last SHIELD state broadcast to alts (only broadcast on change)
    private static long snarbyBellSelectAt = 0L;              // when weapon 2 was armed for the bell shot (settle)
    private static boolean snarbyBossDead = false;           // observed the Snarbolax with hp<=0 (a BURROW vanishes at >0 hp → does NOT set this)
    private static long snarbyBellInRangeSince = 0L;         // when the boss entered the bell radius (dwell before ringing)
    private static final float SNARBY_BELL_RADIUS_SQ = 12.25f; // (3.5 tiles)^2 — boss within this of a Beast Bell => (after the dwell + activity gate) ring it
    private static final long SNARBY_BELL_DWELL_MS = 250L;    // boss must be in the bell radius this long before ringing — mostly vestigial now that the _activity gate stops misfires; kept as a small debounce
    private static long snarbyBellCommitUntil = 0L;           // ring COMMIT latch: keep ringing until this deadline even if the boss flickers out of the radius (bell priority over shielding)
    private static long snarbyShieldClearAt = 0L;             // don't fire before this: pushed forward while the poll still holds X (a tap during shield-lower is eaten)
    private static final long SNARBY_BELL_COMMIT_MS = 1200L;  // how long an engaged ring stays committed past the last in-range tick
    private static final long SNARBY_SHIELD_DROP_GRACE_MS = 200L; // fire this long AFTER the shield key actually released
    // Boss-on-top dodge: mid-BITE (activity 9-11) + in bell range + within 1 tile of the
    // MAIN = he's standing on us, likely blocking the bell shot — dash clear (main + alts).
    private static final int SNARBY_ACT_BITE_MIN = 9, SNARBY_ACT_BITE_MAX = 11;
    private static final float SNARBY_DASH_RADIUS_SQ = 2.25f; // (1.5 tiles)^2 boss-to-main distance
    private static long snarbyAltDashReleaseAt = 0L;         // when to broadcast the alts' DASH 0 (0 = no pulse pending)
    private static boolean snarbyAltBellShoot = false;       // alts currently ordered to fire at the bell (SHOOTALT; broadcast on change)
    private static final float SNARBY_BOSS_DEAD_HP = 0.001f;  // _healthPct at/below this = brought down (hp starts 1.0)
    private static final long SNARBY_CLEAR_DEBOUNCE_MS = 500L;   // boss CONFIRMED dead + no monster this long => exit (fast)
    private static final long SNARBY_CLEAR_BACKSTOP_MS = 30000L; // no monster this long WITHOUT a confirmed kill => exit (survives a burrow AND the boss taking time to engage at fight start)
    // Boss _activity decode (confirmed via the Ctrl+N dump; activity = bSr.attacks index + 4):
    // 0=idle/none (also treated TARGETABLE — user-added), 4=Bark, 5-8=Dodges, 9/10/11=Bite chain
    // 1/2/3 — the boss is TARGETABLE/stunnable, and these near-bell windows are our only real
    // stun chances, so NOBODY may shield-bump him off the bell.
    // 12=Charge start/loop (the BURROW dig-down), 13=Charge end (re-emerge), 14=Warp To,
    // 15=Tail-Whip — he is untargetable/un-stunnable during these, so ringing the bell then
    // only wastes the ring (and triggers the bell cooldown).
    private static final int SNARBY_ACT_STUNNABLE_MIN = 4;     // Bark
    private static final int SNARBY_ACT_STUNNABLE_MAX = 11;    // Bite chain 3
    private static final int SNARBY_ACT_UNTARGETABLE_MIN = 12; // Charge start/loop (burrow)
    private static final int SNARBY_ACT_UNTARGETABLE_MAX = 15; // Tail-Whip
    // Shield-bump: during routine-driven NON-COMBAT movement, if a Monster comes
    // within 1 tile while the main is pathing to a waypoint, the main + all alts
    // raise shield for 750ms. Raising the shield "bumps" the enemy out of the path;
    // movement continues (shield-walk). Re-bumps after a short cooldown if a monster
    // is still in range. MAIN detects + drives; alts mirror via the SHIELD broadcast.
    public static volatile boolean routineShieldHold = false; // main: poll holds shield (X) while true
    public static volatile boolean routineShieldHeld = false; // poll's held-state tracker (main)
    private static long shieldBumpReleaseAt = 0L;     // lower the shield at this time
    private static long shieldBumpNextAllowedAt = 0L; // no new bump before this (cooldown after release)
    private static final float SHIELD_BUMP_RADIUS_SQ = 1.0f;   // (1 tile)^2 monster proximity
    private static final long SHIELD_BUMP_HOLD_MS = 750L;      // shield-raised duration per bump
    private static final long SHIELD_BUMP_COOLDOWN_MS = 250L;  // gap after release before re-bumping
    // Step types.
    // Step-type codes mirror RoutineFile (the single source of truth) so the
    // tickRoutine switch below stays readable. They're constant variables, so the
    // case labels still compile.
    private static final int T_MOVETO = RoutineFile.MOVETO, T_COMBATLOOT = RoutineFile.COMBATLOOT,
            T_ATTACKMOVE = RoutineFile.ATTACKMOVE, T_SHOOT = RoutineFile.SHOOT, T_PLATFORM = RoutineFile.PLATFORM,
            T_ELEVATOR = RoutineFile.ELEVATOR, T_LOOT = RoutineFile.LOOT, T_BUTTON = RoutineFile.BUTTON,
            T_MINERALS = RoutineFile.MINERALS, T_KEY_LIFT = RoutineFile.KEY_LIFT, T_GATE = RoutineFile.GATE,
            T_BUTTONSWEEP = RoutineFile.BUTTONSWEEP, T_HAZARD_MOVETO = RoutineFile.HAZARD_MOVETO,
            T_CRITICAL_MOVETO = RoutineFile.CRITICAL_MOVETO,
            T_TREASURESWEEP = RoutineFile.TREASURESWEEP, T_WAIT = RoutineFile.WAIT,
            T_LIFT = RoutineFile.LIFT, T_DROP = RoutineFile.DROP,
            T_KEY_DROP = RoutineFile.KEY_DROP, T_SNARBY = RoutineFile.SNARBY,
            T_KILL = RoutineFile.KILL, T_PRECISE_MOVETO = RoutineFile.PRECISE_MOVETO,
            T_SWITCHSWEEP = RoutineFile.SWITCHSWEEP, T_COMBAT = RoutineFile.COMBAT,
            T_HAZARD_LOOT = RoutineFile.HAZARD_LOOT, T_ALCH_CHARGE = RoutineFile.ALCH_CHARGE,
            T_PIN_MOVETO = RoutineFile.PIN_MOVETO;
    // The active routine's steps, loaded from ~/.sk-utils/routines/<floor>.txt at
    // routine start (see the ROUTINE listener). Empty until then.
    private static final String ROUTINES_DIR = DIR + "/routines";
    private static int[] routineType = new int[0];
    private static float[] routineX = new float[0];
    private static float[] routineY = new float[0];
    private static float[][] routineParams = new float[0][]; // per-step params ([0]=X,[1]=Y, extras command-specific — see RoutineFile.paramCount)

    /** The routine tick (MAIN only, while isRoutineActive). Advances the step machine. */
    public static void tickRoutine(Object controller) {
        // ORPHANED-ROUTINE GUARD — FIRST, before every early return below. A routine only ever
        // starts as part of a campaign, so isRoutineActive without campaignActive is a broken
        // invariant, and it is the worst state the bot can be in: the step machine (and its
        // combat loop) keeps driving while every campaignActive-gated watchdog is disarmed —
        // exactly how a wiped party sat on the floor for six hours. It must precede the
        // controller/view/pawn reads because each of those returns early on its own, and a DEAD
        // main's position is unreadable, so a stranded routine would never reach a guard placed
        // further down. Ctrl+R off clears both flags together, so this only fires on a leak.
        if (!campaignActive) {
            debugFile("[routine] campaign is not active but a routine is — stopping the orphaned routine");
            routineStopCleanup();
            return;
        }
        try {
            if (controller == null)
                return;
            Object view = Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            Integer pawnId = Reflect.readIntFieldNullable(controller, MappingsNames.PAWN_ID_FIELD);
            if (view == null || pawnId == null) {
                clearMovementKeys();
                return;
            }
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null) {
                clearMovementKeys();
                return;
            }
            Object wr = actorMap.getClass().getMethod("get", int.class).invoke(actorMap, pawnId.intValue());
            Object meActor = (wr == null) ? null : Reflect.getWrappedActor(wr);
            float[] mp = (meActor == null) ? null : Reflect.actorPos(meActor);
            if (mp == null) {
                clearMovementKeys();
                return;
            }
            dropBreadcrumb(mp[0], mp[1]); // leave a trail for the alts to retrace
            long now = System.currentTimeMillis();
            // FOOLPROOF stale-routine guard: if the scene changed out from under a running routine —
            // e.g. the game auto-descended the elevator mid-step (common on the solo lobby-bug
            // descent) before the ELEVATOR step could routineFinish — the step machine below would
            // keep driving pathTo toward the PREVIOUS floor's coords, straight into a wall. Detect the
            // floor mismatch, HALT, and finish the stale routine so the campaign re-evaluates the new
            // floor from scratch (wait for the party, then start its routine).
            String liveFloor = resolveFloorKey();
            if (liveFloor != null && campaignLastFloor != null && !liveFloor.equals(campaignLastFloor)) {
                debugFile("[routine] scene changed mid-routine (" + campaignLastFloor + " → " + liveFloor
                        + ") — halting stale routine");
                clearMovementKeys();
                routineFinish();
                return;
            }
            if (routineStepIdx >= routineType.length) {
                routineFinish();
                return;
            }
            if (checkRoutineStuck(mp, now)) // no-progress watchdog → abort + relaunch a fresh mission
                return;
            int type = routineType[routineStepIdx];
            float tx = routineX[routineStepIdx], ty = routineY[routineStepIdx];
            int myId = pawnId.intValue();

            switch (type) {
                case T_MOVETO: {
                    if (pathTo(view, mp, tx, ty)) {
                        blockClearStop();
                        routineAdvance();
                    } else {
                        tickBreakableClear(controller, view, mp, routinePath); // shoot shrubs on the path
                    }
                    break;
                }
                case T_WAIT: {
                    // WAIT X Y SECONDS (legacy WAIT3 X Y = implicit 3s): stand still for the
                    // step's duration param, then advance. NO pathing: by design a WAIT is
                    // always preceded by a command that already positioned the party at
                    // (X,Y), so the main is already there. routineSubStart is stamped at step
                    // entry (when the previous step advanced), so it times the wait from arrival.
                    isCombatBot = false;
                    clearMovementKeys();
                    if (now - routineSubStart >= (long) (stepParam(2, 3f) * 1000f))
                        routineAdvance();
                    break;
                }
                case T_CRITICAL_MOVETO: {
                    // MOVETO, then hold at (X,Y) until the whole party gathers before
                    // advancing — for a time-sensitive gate/trap ahead the party must
                    // pass through together. Same gather as PLATFORM/ELEVATOR.
                    if (routineSub == 0) { // path in (clearing shrubs), like MOVETO
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    } else { // wait for the party — advance ONLY on a real gather
                        clearMovementKeys();
                        if (altsGathered(view, myId, mp)) {
                            routineAdvance();
                        } else if (now - routineSubStart >= GATHER_TIMEOUT_MS) {
                            // Gather timed out → someone fell behind. CRITICAL_MOVETO precedes a
                            // together-only crossing, so advancing blind would strand the party and
                            // stall the floor — abort + relaunch instead (like KEY_LIFT/GATE).
                            campaignAbort("CRITICAL_MOVETO gather timeout at (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ")");
                        }
                    }
                    break;
                }
                case T_HAZARD_MOVETO: { // MOVETO that waits-and-crosses spike-trap fields
                    ensureTrapCells(view);       // build the footprint map (once per scene)
                    updateTrapStates(view, now); // track UP/WARN->DOWN transitions (freshness)
                    if (shouldHoldForTrap(mp, now, hazPath)) {
                        clearMovementKeys();     // wait on safe ground at the trap's near edge
                        // PRE-CLEAR WHILE WAITING (user-directed): keep shooting path blocks
                        // during the hold instead of stopping. A block gating the FAR side
                        // (explosive/ghost — both respawn on their own clock) is then killed
                        // from safe ground; the old blockClearStop() deferred it until the
                        // party was already ON the spikes (the failure the retired
                        // HAZARD_SHOOTMOVE existed to work around). No-op when nothing
                        // matched is in range, so plain spike crossings are unchanged.
                        tickBreakableClear(controller, view, mp, hazPath);
                    } else if (driveAlongPath(view, mp, tx, ty, hazPath, "hazard", PATH_TARGET_ARRIVE_SQ)) {
                        blockClearStop();
                        routineAdvance();
                    } else {
                        tickBreakableClear(controller, view, mp, hazPath); // shoot shrubs en route
                    }
                    break;
                }
                case T_BUTTON: { // a floor button/switch: step on it and confirm it flips
                    if (routineSub == 0) { // path onto the button
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath); // shoot shrubs en route
                        }
                    } else { // press precisely onto it, hold until its state != 0 (or timeout)
                        clearMovementKeys();
                        setMovementFromDelta(tx - mp[0], ty - mp[1]);
                        // A shrub on/next to the button stops the main ~1 tile short (so
                        // sub0 already "arrived"); shoot it here so the press can land.
                        clearBreakableNear(controller, view, mp, tx, ty);
                        if (buttonFlipped(view, tx, ty)) {
                            blockClearStop();
                            debugFile("[routine] button (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") triggered");
                            routineAdvance();
                        } else if (now - routineSubStart >= BUTTON_TIMEOUT_MS) {
                            blockClearStop();
                            debugFile("[routine] button (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") timeout; advancing. "
                                    + describeButtonArea(view, tx, ty));
                            routineAdvance();
                        }
                    }
                    break;
                }
                case T_LOOT: {
                    if (routineSub == 0) { // path to (X,Y), clearing shrubs like MOVETO
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            isLootMode = true;
                            lootPlanned = false;
                            lootDoneLogged = false;
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath); // shoot shrubs en route
                        }
                    } else if (lootDoneLogged) {
                        isLootMode = false;
                        routineAdvance();
                    }
                    break;
                }
                case T_HAZARD_LOOT: {
                    // LOOT with HAZARD_MOVETO's path-in: wait-and-cross spike-trap fields
                    // on the way to (X,Y), then run the normal loot sweep from there.
                    // NOTE: the sweep itself is the plain loot driver — trap tiles are
                    // ordinary walkable cells to it — so keep the LOOT AREA clear of
                    // trap fields; only the approach is trap-timed.
                    if (routineSub == 0) { // hazard-aware path to (X,Y)
                        ensureTrapCells(view);
                        updateTrapStates(view, now);
                        if (shouldHoldForTrap(mp, now, hazPath)) {
                            clearMovementKeys(); // wait on safe ground at the trap's near edge
                            tickBreakableClear(controller, view, mp, hazPath); // pre-clear far-side blocks from safety
                        } else if (driveAlongPath(view, mp, tx, ty, hazPath, "hazard", PATH_TARGET_ARRIVE_SQ)) {
                            blockClearStop();
                            isLootMode = true;
                            lootPlanned = false;
                            lootDoneLogged = false;
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, hazPath); // shoot shrubs en route
                        }
                    } else if (lootDoneLogged) {
                        isLootMode = false;
                        routineAdvance();
                    }
                    break;
                }
                case T_COMBAT: {
                    // COMBATLOOT without the loot subroutine: path in, orbit-fight until
                    // the 10-tile circle around the FIXED (X,Y) stays clear, return to
                    // (X,Y), advance. Combat/orbit/clear-detection logic is COMBATLOOT's.
                    if (routineSub == 0) { // path in
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop(); // stop firing before combat takes over the weapon
                            isCombatBot = true;
                            routineCombatClearSince = 0L;
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath); // shoot shrubs en route
                        }
                    } else if (routineSub == 1) { // fight until clear, orbiting (tx,ty)
                        if (combatPhaseOvertime(now))
                            break; // unwinnable fight — aborted + relaunching
                        isCombatBot = true;
                        tickCombatSprite(now); // party sprite ability 1 on its own cadence
                        java.util.HashSet<Integer> en = enemyIdsWithin(view, new float[] { tx, ty },
                                stepEnemyRadiusSq());
                        if (tickStragglerChase(view, mp, now, en))
                            break; // chasing the last mob — no orbit, no centre-clear test
                        driveCombatOrbit(mp, tx, ty);
                        // Detect from the FIXED centre (tx,ty), not the drifting/knocked-back
                        // main, so the phase can't falsely conclude when pushed off position.
                        if (en.isEmpty()) {
                            if (routineCombatClearSince == 0L)
                                routineCombatClearSince = now;
                            else if (now - routineCombatClearSince >= COMBAT_CLEAR_DEBOUNCE_MS) {
                                isCombatBot = false;
                                routineSetSub(2);
                            }
                        } else {
                            routineCombatClearSince = 0L;
                        }
                    } else { // routineSub == 2: return to the coord, then advance (no loot)
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            clearMovementKeys();
                            routineAdvance();
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    }
                    break;
                }
                case T_COMBATLOOT: {
                    if (routineSub == 0) { // path in
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop(); // stop firing before combat takes over the weapon
                            isCombatBot = true;
                            routineCombatClearSince = 0L;
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath); // shoot shrubs en route
                        }
                    } else if (routineSub == 1) { // fight until clear, orbiting (tx,ty)
                        if (combatPhaseOvertime(now))
                            break; // unwinnable fight — aborted + relaunching
                        isCombatBot = true;
                        tickCombatSprite(now); // party sprite ability 1 on its own cadence
                        java.util.HashSet<Integer> en = enemyIdsWithin(view, new float[] { tx, ty },
                                stepEnemyRadiusSq());
                        if (tickStragglerChase(view, mp, now, en))
                            break; // chasing the last mob — no orbit, no centre-clear test
                        driveCombatOrbit(mp, tx, ty); // revolve on a 1-tile circle instead of standing still
                        // Detect from the FIXED centre (tx,ty), not the drifting/knocked-back main, so the
                        // phase can't falsely conclude when a character is pushed out of position.
                        if (en.isEmpty()) {
                            if (routineCombatClearSince == 0L)
                                routineCombatClearSince = now;
                            else if (now - routineCombatClearSince >= COMBAT_CLEAR_DEBOUNCE_MS) {
                                isCombatBot = false;
                                routineCombatEndAt = now;
                                routineSetSub(2);
                            }
                        } else {
                            routineCombatClearSince = 0L;
                        }
                    } else if (routineSub == 2) { // return to the coord, then loot after a post-combat delay
                        if (pathTo(view, mp, tx, ty)) {
                            clearMovementKeys();
                            if (now - routineCombatEndAt >= LOOT_POST_COMBAT_DELAY_MS) {
                                isLootMode = true;
                                lootPlanned = false;
                                lootDoneLogged = false;
                                routineSetSub(3);
                            }
                        }
                    } else if (lootDoneLogged) { // loot done
                        isLootMode = false;
                        routineAdvance();
                    }
                    break;
                }
                case T_ATTACKMOVE: {
                    if (routineSub == 0) { // move with combat on
                        isCombatBot = true;
                        if (pathTo(view, mp, tx, ty)) {
                            routineCombatClearSince = 0L;
                            routineSetSub(1);
                        }
                    } else { // arrived: stand and finish clearing
                        if (combatPhaseOvertime(now))
                            break; // unwinnable fight — aborted + relaunching
                        clearMovementKeys();
                        isCombatBot = true;
                        if (enemiesWithin(view, mp, stepEnemyRadiusSq()) == 0) {
                            if (routineCombatClearSince == 0L)
                                routineCombatClearSince = now;
                            else if (now - routineCombatClearSince >= COMBAT_CLEAR_DEBOUNCE_MS) {
                                isCombatBot = false;
                                routineAdvance();
                            }
                        } else {
                            routineCombatClearSince = 0L;
                        }
                    }
                    break;
                }
                case T_SHOOT: {
                    // Rerouted (user): the ALTS fire weapon 2 at (X,Y); the MAIN only
                    // monitors and advances — it NEVER fires, so a carried key is never
                    // dropped (SHOOT is safe between KEY and GATE). Main holds position;
                    // combat forced off so the alts' weapon-2 selection isn't overridden.
                    clearMovementKeys();
                    isCombatBot = false;
                    if (routineSub == 0) { // wait for the alts to gather at the shot spot, then start them
                        if (altsGathered(view, myId, mp) || now - routineSubStart >= GATHER_TIMEOUT_MS) {
                            broadcast("SHOOTALT " + tx + " " + ty);
                            routineShootStart = now;
                            routineSetSub(1);
                        }
                    } else { // alts firing: advance when the target resolves or on timeout
                        if (shootTargetResolved(view, tx, ty) || now - routineShootStart >= SHOOT_TIMEOUT_MS) {
                            broadcast("SHOOTALT off");
                            routineAdvance();
                        }
                    }
                    break;
                }
                case T_PIN_MOVETO: {
                    // SCOPED PAIR: "PIN_MOVETO X Y START" ... "PIN_MOVETO END".
                    // START paths in and seats the main against (X,Y), then leaves the hold
                    // OPEN so every step until END keeps being pushed into that spot. END
                    // just releases it. Only stationary steps belong inside a scope - the
                    // hold overrides step movement by design.
                    //
                    // Seating is a PUSH, never a stop: the useful spots here are
                    // wall-contact positions like (11.67,-8.67), which are not tile centres
                    // and are unreachable by any arrival tolerance. Walk into the wall and
                    // let the geometry place the knight - exact and identical every run.
                    if (stepParam(2, 1f) <= 0f) { // END
                        pinHoldActive = false;
                        clearMovementKeys();
                        debugFile("[routine] PIN_MOVETO END - releasing the pin at ("
                                + Reflect.fmt(pinHoldX) + "," + Reflect.fmt(pinHoldY) + ")");
                        routineAdvance();
                        break;
                    }
                    isCombatBot = false;
                    float pdx = tx - mp[0], pdy = ty - mp[1];
                    float pd2 = pdx * pdx + pdy * pdy;
                    if (routineSub == 0) { // A* until close enough to push the last bit
                        if (pd2 <= PIN_ENGAGE_SQ || pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            pinBestDist = Float.MAX_VALUE;
                            pinStalledSince = now;
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                        break;
                    }
                    clearMovementKeys();
                    // Per-axis keys with NO deadzone: setMovementFromDelta refuses deltas
                    // under ~0.39, which is larger than the whole approach we have left.
                    if (pdy > PIN_MOVE_EPS)
                        DOWN.add("W");
                    else if (pdy < -PIN_MOVE_EPS)
                        DOWN.add("S");
                    if (pdx > PIN_MOVE_EPS)
                        DOWN.add("D");
                    else if (pdx < -PIN_MOVE_EPS)
                        DOWN.add("A");
                    float pdNow = (float) Math.sqrt(pd2);
                    if (pdNow < pinBestDist - PIN_MOVE_EPS) { // still closing
                        pinBestDist = pdNow;
                        pinStalledSince = now;
                    }
                    boolean pinned = now - pinStalledSince >= PIN_STALL_MS;
                    if (pinned || pd2 <= PIN_MOVE_EPS * PIN_MOVE_EPS
                            || now - routineSubStart >= PIN_TIMEOUT_MS) {
                        // Open the scope wherever we ended up: the hold keeps pushing, so a
                        // seat that is still settling finishes settling under the hold.
                        pinHoldActive = true;
                        pinHoldX = tx;
                        pinHoldY = ty;
                        debugFile("[routine] PIN_MOVETO START (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") seated at ("
                                + Reflect.fmt(mp[0]) + "," + Reflect.fmt(mp[1]) + ") - " + Reflect.fmt(pdNow)
                                + " tile(s) off; holding");
                        routineAdvance();
                    }
                    break;
                }
                case T_ALCH_CHARGE: {
                    // Charge weapon 2 aimed at (X,Y), release, then check the GHOST GATE at
                    // (W,Z); repeat until it's gone. The aim point is a BANK ANGLE for a
                    // ricochet gun, so it is deliberately NOT the switch's own position and
                    // nothing here tries to path or re-aim toward the gate.
                    //
                    // Completion is the gate LEAVING the actor map, guarded by a seen-latch —
                    // the same trap KILL hit: an actor map that hasn't streamed the block in
                    // yet reads identically to one that's been destroyed.
                    float gx = stepParam(2, tx), gy = stepParam(3, ty);
                    clearMovementKeys();
                    isCombatBot = false;
                    boolean ghost = ghostBlockAt(view, gx, gy);
                    if (ghost) {
                        alchSawGhost = true;
                        alchAbsentSince = 0L;
                    }
                    if (alchSawGhost && !ghost) { // it opened — but the wall is still going
                        keyTapFire = false;
                        pickupAimActive = false;
                        keyTapHoldMs = KEY_TAP_HOLD_DEFAULT_MS;
                        // SETTLE BEFORE ADVANCING (user-diagnosed). The ghost block vanishes
                        // FIRST; the unbreakable blocks wired to it come down after it in a
                        // chain, over a second or two. Advancing on the ghost's disappearance
                        // meant the next step planned its route while most of the wall was
                        // still standing — and a route is committed for the whole step, so
                        // the bot walked the long way round a shortcut that opened a moment
                        // later. Wait for the chain, then hand over a settled floor.
                        if (alchOpenedAt == 0L) {
                            alchOpenedAt = now;
                            debugFile("[routine] ALCH_CHARGE gate (" + Reflect.fmt(gx) + "," + Reflect.fmt(gy)
                                    + ") opened — settling " + ALCH_OPEN_SETTLE_MS + "ms for the block chain");
                        } else if (now - alchOpenedAt >= ALCH_OPEN_SETTLE_MS) {
                            debugFile("[routine] ALCH_CHARGE chain settled; advancing");
                            routineAdvance();
                        }
                        break;
                    }
                    if (!alchSawGhost) {
                        // Never seen: either already open before we arrived, or nothing is
                        // there. Confirm the absence rather than trusting a single glance.
                        if (alchAbsentSince == 0L) {
                            alchAbsentSince = now;
                        } else if (now - alchAbsentSince >= ALCH_ABSENT_CONFIRM_MS) {
                            pickupAimActive = false;
                            keyTapHoldMs = KEY_TAP_HOLD_DEFAULT_MS;
                            debugFile("[routine] ALCH_CHARGE no gate at (" + Reflect.fmt(gx) + "," + Reflect.fmt(gy)
                                    + ") — advancing");
                            routineAdvance();
                            break;
                        }
                    }
                    // Face the bank angle. TWO things here are load-bearing, and getting
                    // either wrong sends the ricochet somewhere else after the first shot:
                    //
                    // 1. AIM FROM THE ANCHOR, NOT THE LIVE POSITION. The bearing is a
                    //    property of the firing SPOT. Recoil moves the knight, so
                    //    Reflect.aimAngleTo(mp, ...) yields a different angle on every tick that
                    //    follows a shot — and even the ±ALCH_REPOS_EPS slack allowed at the
                    //    anchor would wobble it. The anchor is fixed, so the angle is too.
                    //
                    // 2. FREEZE IT WHILE THE CHARGE IS HELD. The poll's release fires
                    //    __skAttackRelease at coordinates recomputed from the CURRENT
                    //    keyTapAngle, and a charged shot takes its direction at RELEASE —
                    //    so letting the angle move mid-wind-up re-aims the shot at the last
                    //    instant, which is exactly the "wrong spot after the first shot"
                    //    symptom. Press and release must resolve to the same bearing.
                    if (!keyTapHeld) {
                        pickupAimActive = true;
                        pickupAimAngle = Reflect.aimAngleTo(mp, tx, ty);
                        keyTapAngle = pickupAimAngle;
                    }
                    if (routineSub == 0) { // equip the gun, let it settle before the first charge
                        selectWeapon(controller, GUN_WEAPON_SLOT);
                        if (now - routineSubStart >= SHOOT_SETTLE_MS) {
                            alchNextChargeAt = 0L;
                            routineSetSub(1);
                        }
                        break;
                    }
                    // One charge at a time: keyTapFire/keyTapHeld clear themselves on release
                    // (the poll's one-shot press+hold+release block), so this starts the next
                    // charge only once the previous has actually let go.
                    // Positioning is NOT this step's job: wrap it in a PIN_MOVETO scope
                    // and the hold driver keeps the main wall-seated through every shot.
                    // (An earlier version re-pinned per shot from inside this case and did
                    // not work in play - it cleared and re-decided the movement keys every
                    // tick, so the knight never actually walked back.)
                    if (!keyTapFire && !keyTapHeld && now >= alchNextChargeAt) {
                        keyTapHoldMs = ALCH_CHARGE_HOLD_MS;
                        keyTapFire = true;
                        alchNextChargeAt = now + ALCH_CHARGE_HOLD_MS + ALCH_CHARGE_GAP_MS;
                        debugFile("[routine] ALCH_CHARGE firing at (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty)
                                + ") from (" + Reflect.fmt(mp[0]) + "," + Reflect.fmt(mp[1])
                                + ") - gate (" + Reflect.fmt(gx) + "," + Reflect.fmt(gy) + ") still up");
                    }
                    if (now - routineSubStart >= ALCH_TIMEOUT_MS) {
                        keyTapFire = false;
                        pickupAimActive = false;
                        keyTapHoldMs = KEY_TAP_HOLD_DEFAULT_MS;
                        // The route past this point assumes the skip worked, so a fresh run
                        // beats walking into a gate the party can't open.
                        campaignAbort("ALCH_CHARGE gate (" + Reflect.fmt(gx) + "," + Reflect.fmt(gy) + ") never opened in "
                                + (ALCH_TIMEOUT_MS / 1000) + "s");
                        return;
                    }
                    break;
                }
                case T_KILL: {
                    // SHOOT with a MONSTER-GONE completion: the ALTS fire weapon 2 at
                    // (X,Y) until the Monster there LEAVES THE ACTOR MAP. Main-only
                    // monitoring, same as SHOOT, so KILL stays key-carry-safe.
                    //
                    // NOT enemiesWithin: its _healthPct filter treats passive trap-mobs
                    // (wheel launchers report healthPct 0 while alive) as already dead —
                    // the step advanced "target down" without a shot fired. PRESENCE is
                    // the truth for KILL: destroyed monsters vanish from the map. And a
                    // GATE-style seen-latch guards the other direction — "gone" only
                    // counts after the target has been SEEN once, so an actor map that
                    // hasn't streamed the target in yet can't fake a kill either.
                    clearMovementKeys();
                    isCombatBot = false;
                    boolean killPresent = killTargetPresent(view, tx, ty);
                    if (killPresent)
                        killSawTarget = true;
                    if (killSawTarget && !killPresent) { // seen alive, now gone => killed
                        broadcast("SHOOTALT off");
                        debugFile("[routine] KILL (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") target down");
                        routineAdvance();
                        break;
                    }
                    if (routineSub == 0) { // gather the alts at the firing spot first
                        if (!killPresent) {
                            // Never seen here at all: either an earlier step's combat
                            // already destroyed it, or there is nothing at (X,Y). Confirm
                            // the absence briefly rather than trusting one glance.
                            if (killAbsentSince == 0L)
                                killAbsentSince = now;
                            else if (now - killAbsentSince >= KILL_ABSENT_CONFIRM_MS) {
                                debugFile("[routine] KILL (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty)
                                        + ") no target seen — advancing");
                                routineAdvance();
                                break;
                            }
                        } else {
                            killAbsentSince = 0L;
                        }
                        if (altsGathered(view, myId, mp) || now - routineSubStart >= GATHER_TIMEOUT_MS) {
                            broadcast("SHOOTALT " + tx + " " + ty);
                            routineShootStart = now;
                            routineSetSub(1);
                        }
                    } else if (now - routineShootStart >= KILL_TIMEOUT_MS) {
                        // Fail-open like SHOOT/BUTTON: the run survives, later steps just
                        // face whatever the target keeps doing. The log names it.
                        broadcast("SHOOTALT off");
                        debugFile("[routine] KILL (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") timeout ("
                                + (KILL_TIMEOUT_MS / 1000) + "s) — target still alive; advancing");
                        routineAdvance();
                    }
                    break;
                }
                case T_PRECISE_MOVETO: {
                    // MOVETO with a SAME-TILE guarantee for the whole party: the main
                    // paths in normally, then fine-settles onto (X,Y) with per-axis keys
                    // (setMovementFromDelta's deadzone is wider than the precision
                    // needed), while the alts run the PRECISEGATHER drive onto the same
                    // point. Advances only when every knight's tile == the target tile;
                    // gather timeout ABORTS, like CRITICAL_MOVETO — a step this precise
                    // exists because whatever comes next needs everyone exactly here.
                    if (routineSub == 0) { // normal pathing to within a tile
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            broadcast("PRECISEGATHER " + tx + " " + ty);
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    } else { // fine settle + same-tile gather
                        clearMovementKeys();
                        float pdx = tx - mp[0], pdy = ty - mp[1];
                        if (pdy > PRECISE_SETTLE_EPS)
                            DOWN.add("W");
                        else if (pdy < -PRECISE_SETTLE_EPS)
                            DOWN.add("S");
                        if (pdx > PRECISE_SETTLE_EPS)
                            DOWN.add("D");
                        else if (pdx < -PRECISE_SETTLE_EPS)
                            DOWN.add("A");
                        boolean mainOnTile = Math.floor(mp[0]) == Math.floor(tx)
                                && Math.floor(mp[1]) == Math.floor(ty);
                        if (mainOnTile && altsOnTile(view, myId, tx, ty)) {
                            broadcast("PRECISEGATHER off");
                            debugFile("[routine] PRECISE_MOVETO (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty)
                                    + ") — whole party on tile");
                            routineAdvance();
                        } else if (now - routineSubStart >= GATHER_TIMEOUT_MS) {
                            broadcast("PRECISEGATHER off");
                            campaignAbort("PRECISE_MOVETO gather timeout at (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ")");
                            return;
                        }
                    }
                    break;
                }
                case T_PLATFORM: {
                    if (routineSub == 0) { // path onto the platform
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath); // shoot shrubs en route
                        }
                    } else if (routineSub == 1) { // wait for the whole party
                        clearMovementKeys();
                        if (altsGathered(view, myId, mp) || now - routineSubStart >= GATHER_TIMEOUT_MS) {
                            isCombatBot = true;
                            routineWaveCount = 0;
                            routineWaveIds = new java.util.HashSet<Integer>();
                            // Bound the arena by its monster gates (flood-fill from (tx,ty)); wave
                            // detection then counts enemies by ARENA membership, not a fixed radius —
                            // so mobs pushed toward the far wall aren't missed. Empty => fall back.
                            Object arenaModel = sceneModelOf(view);
                            platformArenaCells = (arenaModel == null) ? null
                                    : computePlatformArena(view, arenaModel, tx, ty);
                            if (platformArenaCells != null && !platformArenaCells.isEmpty())
                                debugFile("[routine] PLATFORM arena = " + platformArenaCells.size() + " cells (monster-gate bounded)");
                            else
                                debugFile("[routine] PLATFORM no monster gates found — fixed "
                                        + (int) Math.sqrt(ROUTINE_ENEMY_RADIUS_SQ) + "-tile radius");
                            routineSetSub(2);
                        }
                    } else if (routineSub == 2) { // 3-wave arena, orbiting (tx,ty)
                        if (combatPhaseOvertime(now))
                            break; // arena never finished its waves — aborted + relaunching
                        isCombatBot = true;
                        tickCombatSprite(now); // party sprite ability 1 on its own cadence
                        // Sealed monster gates are walkable cells on the grid; a chase must
                        // never plan through one. Computed once per step (gates don't move).
                        if (platformArenaCells != null && !platformArenaCells.isEmpty()
                                && chaseGateCells == null) {
                            Object gm = sceneModelOf(view);
                            java.util.HashSet<Long> gw = (gm == null) ? null : buildWalkGrid(gm);
                            if (gw != null)
                                chaseGateCells = monsterGateCells(view, gw);
                        }
                        java.util.HashSet<Integer> curEn = (platformArenaCells != null && !platformArenaCells.isEmpty())
                                ? enemyIdsInCells(view, platformArenaCells)
                                : enemyIdsWithin(view, new float[] { tx, ty }, ROUTINE_ENEMY_RADIUS_SQ);
                        if (tickStragglerChase(view, mp, now, curEn))
                            break; // chasing this wave's last mob — no orbit, wave logic resumes on resolve
                        driveCombatOrbit(mp, tx, ty); // revolve on a 1-tile circle instead of standing still
                        // Identity-based wave detection. A wave clears when the tracked
                        // enemy set (a) empties out, or (b) is replaced by a fully
                        // DISJOINT set — the next wave (waves spawn all-at-once with new
                        // actor IDs, and each is defeated before the next spawns). The
                        // disjoint case catches an instant transition — e.g. wave 1's
                        // instant-death mobs clearing and wave 2 spawning between ticks —
                        // without ever needing to observe a zero-enemy sample.
                        // Detect from the FIXED centre (tx,ty), not the orbiting/knocked-back main.
                        java.util.HashSet<Integer> cur = curEn; // same scan the chase gate used this tick
                        if (cur.isEmpty()) {
                            if (!routineWaveIds.isEmpty()) {
                                routineWaveCount++;
                                debugFile("[routine] wave " + routineWaveCount + "/" + ROUTINE_WAVES + " (cleared)");
                                routineWaveIds = cur;
                            }
                        } else if (routineWaveIds.isEmpty()) {
                            routineWaveIds = cur; // a wave appeared; count it on clear/replace
                        } else if (java.util.Collections.disjoint(routineWaveIds, cur)) {
                            routineWaveCount++;
                            debugFile("[routine] wave " + routineWaveCount + "/" + ROUTINE_WAVES + " (replaced)");
                            routineWaveIds = cur;
                        } else {
                            routineWaveIds = cur; // same wave, shrinking as enemies die
                        }
                        if (routineWaveCount >= ROUTINE_WAVES) {
                            isCombatBot = false;
                            routineCombatEndAt = now;
                            routineSetSub(3);
                        }
                    } else if (routineSub == 3) { // return to the platform, then loot after a post-combat delay
                        if (pathTo(view, mp, tx, ty)) {
                            clearMovementKeys();
                            if (now - routineCombatEndAt >= LOOT_POST_COMBAT_DELAY_MS) {
                                isLootMode = true;
                                lootPlanned = false;
                                lootDoneLogged = false;
                                routineSetSub(4);
                            }
                        }
                    } else if (lootDoneLogged) {
                        isLootMode = false;
                        routineAdvance();
                    }
                    break;
                }
                case T_ELEVATOR: {
                    if (routineSub == 0) {
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath); // shoot shrubs en route
                        }
                    } else { // wait for the party, then descend (ends the routine)
                        clearMovementKeys();
                        if (altsGathered(view, myId, mp)) {
                            routineFinish();
                        } else if (now - routineSubStart >= GATHER_TIMEOUT_MS) {
                            // FAIL-CLOSED (user-directed; was fail-open): finishing with a
                            // straggler severs everyone's movement and strands the run — the
                            // elevator only descends with the WHOLE party on the pad. Abort
                            // and relaunch instead.
                            campaignAbort("ELEVATOR gather timeout at (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ")");
                        }
                    }
                    break;
                }
                case T_MINERALS: {
                    // Preceded by a MOVETO that positions the main to hit the cluster,
                    // so there is no path-in phase — scan from the current position.
                    if (routineSub == 0) { // remember the cluster, ready the gun
                        clearMovementKeys();
                        isCombatBot = false;
                        scanMineralNodes(view, mp);
                        selectWeapon(controller, GUN_WEAPON_SLOT);
                        if (mineralNodeCount == 0) { // nothing here — skip the whole step
                            debugFile("[routine] MINERALS: no nodes in range; skipping");
                            routineAdvance();
                            break;
                        }
                        if (now - routineSubStart >= SHOOT_SETTLE_MS) {
                            debugFile("[routine] MINERALS: " + mineralNodeCount + " node(s) to clear");
                            mineralShootStart = now;
                            routineSetSub(1);
                        }
                    } else if (routineSub == 1) { // shoot every node until the cluster is clear
                        clearMovementKeys();
                        float[] node = nearestLiveMineralNode(view, mp);
                        if (node == null || now - mineralShootStart >= MINERAL_SHOOT_TIMEOUT_MS) {
                            routineShootActive = false;
                            routineSetSub(2);
                        } else {
                            routineShootAngle = Reflect.aimAngleTo(mp, node[0], node[1]);
                            routineShootActive = true;
                        }
                    } else { // routineSub == 2: gather the drops
                        routineShootActive = false;
                        if (!mineralGatherPlanned) {
                            planMineralGather(view); // free chars -> nearest distinct drops; broadcast
                            mineralGatherPlanned = true;
                            mineralGatherStart = now;
                            mineralPickedUp = false;
                            if (mineralAssignMap.isEmpty()) { // all holding, or no reachable drops
                                debugFile("[routine] MINERALS: gather skipped (no free characters)");
                                isMineralGather = false;
                                broadcast("MINERALGATHER 0");
                                routineAdvance();
                                break;
                            }
                            isMineralGather = true;
                            debugFile("[routine] MINERALS: gathering (" + mineralAssignMap.size() + " assigned)");
                        }
                        // Drive the MAIN to its own assigned drop (if any); once in range,
                        // face it and tap attack until the drop actor is gone (collected).
                        float[] tgt = mineralAssignMap.get(Integer.valueOf(myId));
                        if (tgt != null && !mineralPickedUp) {
                            float dx = tgt[0] - mp[0], dy = tgt[1] - mp[1];
                            clearMovementKeys();
                            // PRE-AIM the whole final approach: facing is settled long
                            // before the first tap, so it can't misfire as a turn+attack.
                            mineralTapAngle = Reflect.aimAngleTo(mp, tgt[0], tgt[1]); // face the drop
                            pickupAimActive = true;
                            pickupAimAngle = mineralTapAngle;
                            if (dx * dx + dy * dy <= MINERAL_PICKUP_RADIUS_SQ) {
                                if (mineralDropGone(view, tgt[0], tgt[1])) {
                                    mineralPickedUp = true;
                                    mineralTapActive = false;
                                    pickupAimActive = false;
                                } else {
                                    mineralTapActive = true; // poll taps attack toward the drop
                                }
                            } else {
                                setMovementFromDelta(dx, dy); // straight-line hop to the drop
                                mineralTapActive = false;
                            }
                        } else {
                            clearMovementKeys();
                            mineralTapActive = false;
                            pickupAimActive = false;
                        }
                        // Finish as soon as every assigned drop is collected, or on a
                        // timeout (assume grabbed — optimistic per-floor fallback).
                        if (allMineralDropsGone(view) || now - mineralGatherStart >= MINERAL_GATHER_TIMEOUT_MS) {
                            mineralHeldIds.addAll(mineralAssignMap.keySet());
                            isMineralGather = false;
                            mineralTapActive = false;
                            broadcast("MINERALGATHER 0");
                            debugFile("[routine] MINERALS: gather done; holding=" + mineralHeldIds.size());
                            routineAdvance();
                        }
                    }
                    break;
                }
                case T_KEY_LIFT:
                case T_LIFT: {
                    // Path to the liftable (key/statue/totem — see liftableForStep), then
                    // single-tap it up. Attack TOGGLES carry, so we stop the instant the
                    // object is IN HAND — a second tap would put it straight back down.
                    //
                    // Completion is "we are holding it" — Actor._flags' carried bit, via
                    // carriedLiftable. NOT the old "the spawn tile is empty" (keyGrounded),
                    // which an object resting >0.4 tile from the authored (X,Y) made false
                    // on the first tick, logging a phantom pickup.
                    //
                    // Everything targets the object's LIVE position, never the authored
                    // coordinate: the aim, the approach and the arrival test. A liftable
                    // seldom rests exactly on the routine coord, and each failed attempt
                    // (lift + immediate re-drop) walks it further away.
                    final String liftLabel = (routineType[routineStepIdx] == T_LIFT) ? "LIFT" : "KEY_LIFT";
                    if (routineSub == 0) {
                        // Hand off to the tap phase on PROXIMITY, not on pathTo arrival —
                        // the same trap GATE hit. The object SITS ON the step coordinate
                        // and is physically solid, but it is not a grid obstacle, so A*
                        // routes into its cell while collision stops the knight ~0.8 tile
                        // short: driveAlongPath then pushes at a waypoint it can never
                        // reach and never reports arrival, so LIFT sat in sub0 forever —
                        // never aiming, never tapping, until the stuck watchdog aborted.
                        isCombatBot = false;
                        float[] lop0 = groundedLiftablePos(view, tx, ty);
                        float hx = (lop0 == null) ? tx : lop0[0], hy = (lop0 == null) ? ty : lop0[1];
                        float hdx = hx - mp[0], hdy = hy - mp[1];
                        if (hdx * hdx + hdy * hdy <= LIFT_HANDOFF_SQ || pathTo(view, mp, tx, ty))
                            routineSetSub(1);
                    } else {
                        if (carriedLiftable(view, mp) != null) {
                            keyTapFire = false;
                            pickupAimActive = false;
                            debugFile("[routine] " + liftLabel + " (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") picked up");
                            routineAdvance();
                            break;
                        }
                        float[] lop = groundedLiftablePos(view, tx, ty);
                        float ox = (lop == null) ? tx : lop[0], oy = (lop == null) ? ty : lop[1];
                        float dx = ox - mp[0], dy = oy - mp[1];
                        float d2 = dx * dx + dy * dy;
                        clearMovementKeys();
                        // FACE the object for the WHOLE close-in (not just at range) —
                        // the settle then only covers stopping, never the turn.
                        pickupAimActive = true;
                        pickupAimAngle = Reflect.aimAngleTo(mp, ox, oy);
                        // Arrival = within the key radius OR PINNED against the object:
                        // a statue's 1x1 collision RECT stops the main outside the key
                        // radius, so "pushing but no longer getting closer" counts too.
                        float dNow = (float) Math.sqrt(d2);
                        if (dNow < liftPinDist - LIFT_PIN_EPS) {
                            liftPinDist = dNow;
                            liftPinSince = now;
                        }
                        boolean atObject = d2 <= KEY_PICKUP_RADIUS_SQ
                                || (d2 <= LIFT_PIN_MAX_SQ && liftPinSince != 0L
                                        && now - liftPinSince >= LIFT_PIN_MS);
                        if (atObject) {
                            keyTapAngle = pickupAimAngle; // tap toward the object
                            if (keyInRangeSince == 0L)
                                keyInRangeSince = now; // reached the object: start the settle
                            // Settle KEY_PICKUP_SETTLE_MS after REACHING the key before the
                            // FIRST tap — a tap the instant we arrive registers as an attack,
                            // not a pickup. Then one tap per KEY_PICKUP_INTERVAL_MS: a successful
                            // grab puts the object in hand within a frame (the carry check above
                            // advances well before a 2nd tap could drop it); a failed pickup
                            // registers as an attack, so its animation must finish first.
                            if (now - keyInRangeSince >= KEY_PICKUP_SETTLE_MS
                                    && now - keyLastTapAt >= KEY_PICKUP_INTERVAL_MS) {
                                keyTapFire = true;
                                keyLastTapAt = now;
                            }
                        } else {
                            keyInRangeSince = 0L; // not at the object yet (or knocked out of range): re-settle on arrival
                            setMovementFromDelta(dx, dy); // close the final gap into the collision
                        }
                        if (now - routineSubStart >= KEY_TIMEOUT_MS) {
                            keyTapFire = false;
                            // Can't lift it => the floor's objective can't complete. Abort.
                            campaignAbort(liftLabel + " timeout at (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ")");
                            return;
                        }
                    }
                    break;
                }
                case T_KEY_DROP:
                case T_DROP: {
                    // Drop the carried liftable at (X,Y). Path there CARRY-SAFE (no
                    // firing/combat, so it isn't dropped en route), then tap ATTACK to drop
                    // it (attack TOGGLES carry → one tap drops). Done when the object is no
                    // longer held — carriedLiftable reads Actor._flags' carry bit, so it is
                    // the object's own state, not a guess from how far it sits from the
                    // carrier. MAIN-only; alts breadcrumb-follow.
                    final String dropLabel = (routineType[routineStepIdx] == T_DROP) ? "DROP" : "KEY_DROP";
                    if (routineSub == 0) {
                        isCombatBot = false;
                        if (pathTo(view, mp, tx, ty)) // no tickBreakableClear: firing would drop the carry
                            routineSetSub(1);
                    } else {
                        Object held = carriedLiftable(view, mp);
                        if (held == null) { // nothing in hand => it's down
                            keyTapFire = false;
                            pickupAimActive = false;
                            // Release position is logged because the config carries
                            // throwTime=1300/fallSpeed=6.0: if a drop ever lands
                            // systematically off (X,Y), this line measures the offset.
                            debugFile("[routine] " + dropLabel + " (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty)
                                    + ") dropped; main was @(" + Reflect.fmt(mp[0]) + "," + Reflect.fmt(mp[1]) + ")");
                            routineAdvance();
                            break;
                        }
                        // Steer by the MAIN's own delta, NOT the carried object's. The
                        // object TRAILS its carrier (measured: dMain swinging 0.02..0.96
                        // while held) and only settles onto it once the main stops, so
                        // driving the main from the object's position is a control loop
                        // with lag in the feedback path: the main overshot ~0.7 tile past
                        // (X,Y) every pass, the delta flipped, and it paced back and forth
                        // without ever holding the window long enough to tap. The main's
                        // own position has no lag, and the object lands on the carrier —
                        // so placing the MAIN places the object.
                        float dx = tx - mp[0], dy = ty - mp[1];
                        float d2 = dx * dx + dy * dy;
                        clearMovementKeys();
                        // Face (X,Y), but don't recompute the bearing from a near-zero
                        // delta — that flips the facing every tick once we're on the spot.
                        if (d2 > 0.04f) {
                            pickupAimActive = true;
                            pickupAimAngle = Reflect.aimAngleTo(mp, tx, ty);
                            keyTapAngle = pickupAimAngle;
                        }
                        if (d2 <= DROP_PLACE_EPS_SQ) { // on the spot: stop, settle, tap
                            if (keyInRangeSince == 0L)
                                keyInRangeSince = now; // in place: start the settle (also lets the object catch up)
                            if (now - keyInRangeSince >= KEY_DROP_SETTLE_MS
                                    && now - keyLastTapAt >= KEY_PICKUP_INTERVAL_MS) {
                                keyTapFire = true; // one attack tap => drop the carry
                                keyLastTapAt = now;
                            }
                        } else {
                            keyInRangeSince = 0L; // not in place yet: re-settle on arrival
                            setMovementFromDelta(dx, dy);
                        }
                        if (now - routineSubStart >= KEY_TIMEOUT_MS) {
                            keyTapFire = false;
                            pickupAimActive = false;
                            debugFile("[routine] " + dropLabel + " (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") timeout; advancing");
                            routineAdvance();
                        }
                    }
                    break;
                }
                case T_GATE: {
                    // Unlock the gate with the carried key. Approach it like a MINERAL
                    // pickup (drive toward it, then STOP in range — do NOT pin/push
                    // into it: pinning misaligns the deposit and a mispositioned attack
                    // DROPS the key instead of consuming it), face it, and tap. Retries
                    // every interval until the Door transitions out of its locked state
                    // (its _stateEntered changes) or is removed; gateSawDoor guards a
                    // missing door from advancing early.
                    // PRE-AIM (user): park the cursor on the gate for the WHOLE step —
                    // approach included — so the knight arrives already facing it and the
                    // tap settle only covers the stop, never the turn (same mechanism as
                    // the KEY_LIFT/MINERALS pickup pre-aim; safe while carrying: aiming
                    // never clicks, facing just follows the cursor).
                    pickupAimActive = true;
                    pickupAimAngle = Reflect.aimAngleTo(mp, tx, ty);
                    if (routineSub == 0) {
                        // No block-clearing here: the main is carrying the key, and any
                        // attack (incl. firing at a block) while holding a key drops it.
                        // Hand off to the stall-and-tap phase once CLOSE — NOT on pathTo
                        // "arrival": the LOCKED gate blocks the coordinate, so pathTo
                        // never completes and the tap would never fire.
                        isCombatBot = false;
                        float gdx = tx - mp[0], gdy = ty - mp[1];
                        if (gdx * gdx + gdy * gdy <= GATE_PIN_MAX_SQ)
                            routineSetSub(1);
                        else
                            pathTo(view, mp, tx, ty);
                    } else {
                        Object door = findGateDoor(view, tx, ty);
                        boolean unlocked = false;
                        if (door != null) {
                            gateSawDoor = true;
                            Integer se = Reflect.readIntFieldNullable(door, "_stateEntered");
                            if (se != null) {
                                if (gateDoorBaseline == null)
                                    gateDoorBaseline = se; // first sighting = locked baseline
                                else if (!se.equals(gateDoorBaseline))
                                    unlocked = true; // Door changed state => opening
                            }
                        } else if (gateSawDoor) {
                            unlocked = true; // Door removed => open
                        }
                        if (unlocked) {
                            keyTapFire = false;
                            debugFile("[routine] GATE (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") unlocked");
                            routineAdvance();
                            break;
                        }
                        float dx = tx - mp[0], dy = ty - mp[1];
                        float d2 = dx * dx + dy * dy;
                        float d = (float) Math.sqrt(d2);
                        clearMovementKeys();
                        // Track progress toward the gate. While still closing in (getting
                        // at least GATE_PIN_EPS tiles closer), keep driving. Once progress
                        // stalls near the gate — clipping against its collision without
                        // getting meaningfully closer — we're "kissing": stop and tap.
                        if (d < gatePinDist - GATE_PIN_EPS) {
                            gatePinDist = d;
                            gatePinSince = now;
                        }
                        boolean kissing = (now - gatePinSince >= GATE_PIN_MS) && d2 <= GATE_PIN_MAX_SQ;
                        if (!kissing) {
                            setMovementFromDelta(dx, dy); // still closing in
                            gateInRangeSince = 0L;
                        } else {
                            // Pressed against the gate: movement already cleared (no
                            // pushing), so tap in place after a settle. Retry every
                            // interval until the gate opens.
                            keyTapAngle = Reflect.aimAngleTo(mp, tx, ty); // face the gate
                            if (gateInRangeSince == 0L)
                                gateInRangeSince = now;
                            if (now - gateInRangeSince >= GATE_SETTLE_MS && now - keyLastTapAt >= KEY_TAP_INTERVAL_MS) {
                                keyTapFire = true;
                                keyLastTapAt = now;
                            }
                        }
                        if (now - routineSubStart >= GATE_TIMEOUT_MS) {
                            keyTapFire = false;
                            // Gate never unlocked => floor is unfinishable. Abort + relaunch.
                            campaignAbort("GATE timeout at (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + "). "
                                    + describeGateArea(view, tx, ty));
                            return;
                        }
                    }
                    break;
                }
                case T_SNARBY: {
                    // Snarbolax boss fight. ORBIT (X,Y) like COMBATLOOT (recovers if knocked off),
                    // standing only for the bell shot. Loop: SHIELD (main+alts) until the boss DWELLS
                    // within SNARBY_BELL_RADIUS of a Beast Bell for SNARBY_BELL_DWELL_MS → BELL (stand;
                    // main drops shield + rings the bell w/ weapon 2) until the boss is STUNNED →
                    // ATTACK (all drop shields, combat bot fires weapon 1) until the stun ends → back
                    // to SHIELD. The boss's _activity gates both the ring (never during charge/warp
                    // 12-14) and the shields (ALL down while he's targetable 4-11 in bell range — see
                    // SNARBY_ACT_*). Exit when no monster within 10 tiles of (X,Y).
                    if (routineSub == 0) { // approach (X,Y), then enter the loop
                        isCombatBot = false;
                        if (pathTo(view, mp, tx, ty)) {
                            clearMovementKeys();
                            isSnarbyActive = true;
                            snarbyAltShieldBroadcast = false; // force a fresh shield broadcast
                            snarbyBellSelectAt = 0L;
                            snarbyBellInRangeSince = 0L;
                            routineCombatClearSince = 0L;
                            routineSetSub(1);
                        }
                    } else { // combat loop — ORBIT (X,Y) (stand only for the bell shot)
                        // Finish a pending alt dash pulse (DASH 1 was broadcast by the
                        // boss-on-top dodge below; release after the short hold).
                        if (snarbyAltDashReleaseAt != 0L && now >= snarbyAltDashReleaseAt) {
                            broadcast("DASH 0");
                            snarbyAltDashReleaseAt = 0L;
                        }
                        Object boss = findSnarbolax(view);
                        if (boss != null) {
                            Float bhp = Reflect.readFloatFieldNullable(boss, "_healthPct");
                            if (bhp != null && bhp <= SNARBY_BOSS_DEAD_HP)
                                snarbyBossDead = true; // brought down — a BURROW vanishes at >0 hp, so it can't set this
                        }
                        // EXIT: no living monster within 10 tiles of the FIXED (X,Y). FAST once the
                        // boss is CONFIRMED dead; otherwise a long backstop so the Snarbolax's brief
                        // BURROW (it disappears from the scene mid-fight) can't false-clear.
                        if (enemiesWithin(view, new float[] { tx, ty }, ROUTINE_ENEMY_RADIUS_SQ) == 0) {
                            if (routineCombatClearSince == 0L)
                                routineCombatClearSince = now;
                            if (now - routineCombatClearSince
                                    >= (snarbyBossDead ? SNARBY_CLEAR_DEBOUNCE_MS : SNARBY_CLEAR_BACKSTOP_MS)) {
                                snarbyStop();
                                debugFile("[routine] SNARBY (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") clear"
                                        + (snarbyBossDead ? " (boss down)" : " (backstop)"));
                                routineAdvance();
                                break;
                            }
                        } else {
                            routineCombatClearSince = 0L;
                        }
                        if (boss != null && isStunned(boss)) {
                            // ATTACK: all drop shields; combat bot fires weapon 1 at the boss. ORBIT (X,Y).
                            driveCombatOrbit(mp, tx, ty);
                            routineShieldHold = false;
                            routineShootActive = false;
                            if (snarbyAltBellShoot) {
                                broadcast("SHOOTALT off"); // stun landed — alts switch to combat
                                snarbyAltBellShoot = false;
                            }
                            snarbyBellSelectAt = 0L;
                            snarbyBellInRangeSince = 0L;
                            snarbyBellCommitUntil = 0L; // the ring worked — stop ringing
                            snarbySetAltShield(false);
                            isCombatBot = true;
                        } else {
                            // Not stunned: SHIELD/dwell (ORBIT) → BELL (STAND + ring), driven by the
                            // boss's live _activity (see SNARBY_ACT_* decode). Keep weapon 2 ARMED
                            // throughout (arm once; settles in the background) so the bell shot fires
                            // instantly. RING only after the boss has dwelt in the bell radius for
                            // SNARBY_BELL_DWELL_MS continuously AND he isn't charging/warping
                            // (activity 12-14 = underground/un-stunnable; ringing then wastes the
                            // ring and cools the bell down for a long while). While he's in bell
                            // range doing a targetable action (activity 4-11 = bark/dodge/bite),
                            // EVERYONE drops shields — a shield bump would knock him off the bell
                            // during one of our only stun windows.
                            isCombatBot = false;
                            if (snarbyBellSelectAt == 0L) {
                                selectWeapon(controller, GUN_WEAPON_SLOT);
                                snarbyBellSelectAt = now;
                            }
                            boolean gunReady = (now - snarbyBellSelectAt >= SHOOT_SETTLE_MS);
                            float[] bell = (boss == null) ? null : bellNearBoss(view, boss);
                            Integer act = (boss == null) ? null : Reflect.readIntFieldNullable(boss, "_activity");
                            boolean actStunnable = act != null
                                    && (act.intValue() == 0 // idle/none — targetable too (user-added)
                                            || (act >= SNARBY_ACT_STUNNABLE_MIN && act <= SNARBY_ACT_STUNNABLE_MAX));
                            boolean actUntargetable = act != null
                                    && act >= SNARBY_ACT_UNTARGETABLE_MIN && act <= SNARBY_ACT_UNTARGETABLE_MAX;
                            // Boss-to-main distance² (also feeds the dodge below).
                            float bossMainDistSq = Float.MAX_VALUE;
                            float[] bp = (boss == null) ? null : Reflect.actorPos(boss);
                            if (bp != null) {
                                float bmx = bp[0] - mp[0], bmy = bp[1] - mp[1];
                                bossMainDistSq = bmx * bmx + bmy * bmy;
                            }
                            // All shields DOWN while a targetable boss is on the bell OR within
                            // 3.5 tiles of the MAIN (user-widened): no shield-bumping him off the
                            // bell, and no bumping him away when he's engaging us up close.
                            boolean shieldsDown = actStunnable
                                    && (bell != null || bossMainDistSq <= SNARBY_BELL_RADIUS_SQ);
                            // BOSS-ON-TOP DODGE (user-directed): mid-bite + on the bell + within
                            // 1 tile of the main = he's on top of us, likely blocking the bell
                            // shot. Dash clear — main via triggerDash (SHIFT+X joins the driven
                            // key set), alts via a DASH 1/0 pulse. Shares the trigger cooldown
                            // with triggerDash so main + alts always dash together.
                            if (bell != null && act != null
                                    && act >= SNARBY_ACT_BITE_MIN && act <= SNARBY_ACT_BITE_MAX
                                    && bossMainDistSq <= SNARBY_DASH_RADIUS_SQ
                                    && now - lastDashTime >= DASH_COOLDOWN_MS) {
                                triggerDash();
                                broadcast("DASH 1");
                                snarbyAltDashReleaseAt = now + DASH_HOLD_MS;
                                debugFile("[routine] SNARBY: boss on top mid-bite — dashing clear");
                            }
                            if (bell == null)
                                snarbyBellInRangeSince = 0L; // out of range (or burrowed away) — reset the dwell
                            else if (snarbyBellInRangeSince == 0L)
                                snarbyBellInRangeSince = now; // boss just entered the radius — start the dwell
                            boolean bellReady = bell != null && !actUntargetable
                                    && now - snarbyBellInRangeSince >= SNARBY_BELL_DWELL_MS;
                            // BELL PRIORITY (user-directed): once the ring engages, COMMIT to it —
                            // the latch keeps ringing through a boss flickering at the radius edge
                            // (which used to flap BELL↔SHIELD, re-raising the shield and eating
                            // every tap). Only a charge/warp (untargetable) cancels the commit.
                            if (bellReady)
                                snarbyBellCommitUntil = now + SNARBY_BELL_COMMIT_MS;
                            else if (actUntargetable)
                                snarbyBellCommitUntil = 0L; // he dove — stop ringing, re-shield
                            if (bellReady || now < snarbyBellCommitUntil) {
                                // BELL: STAND, MAIN drops shield, then rings. SHOOTING OUTRANKS
                                // SHIELDING: an LMB tap landing while the shield is still lowering
                                // is EATEN by the game, so hold fire until the poll has actually
                                // RELEASED X (routineShieldHeld false) + a settle grace.
                                clearMovementKeys();
                                routineShieldHold = false;
                                // EVERYONE shoots the bell (user-directed): alts drop shields
                                // (a held shield eats their shots) and get a SHOOTALT order at
                                // the bell's coords — same reroute the SHOOT command uses. The
                                // order persists through a commit-flicker (they keep firing at
                                // the last coords until the ring ends).
                                snarbySetAltShield(false);
                                if (bell != null && !snarbyAltBellShoot) {
                                    broadcast("SHOOTALT " + bell[0] + " " + bell[1]);
                                    snarbyAltBellShoot = true;
                                }
                                if (routineShieldHeld)
                                    snarbyShieldClearAt = now + SNARBY_SHIELD_DROP_GRACE_MS; // X still down this poll — push the fire gate out
                                if (bell != null)
                                    routineShootAngle = Reflect.aimAngleTo(mp, bell[0], bell[1]); // keep the last aim if he flickers out
                                routineShootActive = gunReady && now >= snarbyShieldClearAt;
                            } else {
                                // SHIELD (incl. the pre-ring dwell + charge/warp holds): ORBIT (X,Y), wait.
                                driveCombatOrbit(mp, tx, ty);
                                routineShootActive = false;
                                if (snarbyAltBellShoot) {
                                    broadcast("SHOOTALT off"); // ring over — alts back to shields
                                    snarbyAltBellShoot = false;
                                }
                                routineShieldHold = !shieldsDown;
                                snarbySetAltShield(!shieldsDown);
                            }
                        }
                    }
                    break;
                }
                case T_BUTTONSWEEP: {
                    // MOVETO (X,Y), scan buttons within ~7.75 tiles, then per button
                    // (nearest-first): path onto it — the general block-clear shoots any
                    // covering breakable (shrub OR 3-hit stone) en route — press, confirm
                    // the flip, then return to (X,Y). NOT block-type specific.
                    if (routineSub == 0) { // path to (X,Y), then scan
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            float bswR = stepParam(2, -1f); // optional RANGE (tiles); <=0 = legacy default
                            bswScan(view, tx, ty, bswR > 0f ? bswR * bswR : BSW_SCAN_RADIUS_SQ);
                            bswIdx = 0;
                            bswPhase = 0;
                            bswPhaseStart = now;
                            routinePath.tgtX = Float.NaN;
                            if (bswCount == 0) {
                                debugFile("[routine] BUTTONSWEEP (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") no buttons; returning");
                                routineSetSub(2);
                            } else {
                                debugFile("[routine] BUTTONSWEEP: " + bswCount + " button(s)");
                                routineSetSub(1);
                            }
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    } else if (routineSub == 1) { // handle each button in turn: path on, press, confirm
                        if (bswIdx >= bswCount) {
                            blockClearStop();
                            routineSetSub(2);
                            break;
                        }
                        float bx = bswX[bswIdx], by = bswY[bswIdx];
                        if (bswPhase == 0) { // path onto the button; block-clear removes any cover (shrub OR stone)
                            if (pathTo(view, mp, bx, by)) {
                                blockClearStop();
                                bswPhase = 1;
                                bswPhaseStart = now;
                            } else {
                                tickBreakableClear(controller, view, mp, routinePath);
                            }
                        } else { // bswPhase == 1: press precisely, clear a cover blocking the press, confirm the flip
                            clearMovementKeys();
                            setMovementFromDelta(bx - mp[0], by - mp[1]);
                            clearBreakableNear(controller, view, mp, bx, by);
                            boolean done = buttonFlipped(view, bx, by);
                            if (done || now - bswPhaseStart >= BUTTON_TIMEOUT_MS) {
                                blockClearStop();
                                debugFile("[routine] BUTTONSWEEP button (" + Reflect.fmt(bx) + "," + Reflect.fmt(by) + ")"
                                        + (done ? " triggered" : " timeout; next"));
                                bswIdx++;
                                bswPhase = 0;
                                bswPhaseStart = now;
                                routinePath.tgtX = Float.NaN;
                            }
                        }
                    } else { // routineSub == 2: return to (X,Y)
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            routineAdvance();
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    }
                    break;
                }
                case T_SWITCHSWEEP: {
                    // BUTTONSWEEP's shape, SHOOT's resolution: MOVETO (X,Y), scan LEVERS
                    // within range (optional 3rd param; default = BUTTONSWEEP's radius),
                    // then per lever (nearest-first) approach to firing range and shoot
                    // until its _state reads triggered (already-thrown levers never make
                    // the scan list). No stepping-on — levers are SOLID (actorIsObstacle).
                    // MAIN fires — never use while carrying a key (SHOOT is carry-safe).
                    if (routineSub == 0) { // path to (X,Y), then scan
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            float sswR = stepParam(2, -1f); // optional RANGE (tiles); <=0 = default
                            sswScan(view, tx, ty, sswR > 0f ? sswR * sswR : BSW_SCAN_RADIUS_SQ);
                            sswIdx = 0;
                            sswPhase = 0;
                            sswPhaseStart = now;
                            routinePath.tgtX = Float.NaN;
                            if (sswCount == 0) {
                                debugFile("[routine] SWITCHSWEEP (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") no levers; returning");
                                routineSetSub(2);
                            } else {
                                debugFile("[routine] SWITCHSWEEP: " + sswCount + " lever(s)");
                                routineSetSub(1);
                            }
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    } else if (routineSub == 1) { // shoot each lever in turn
                        if (sswIdx >= sswCount) {
                            blockClearStop();
                            routineShootActive = false;
                            routineSetSub(2);
                            break;
                        }
                        float bx = sswX[sswIdx], by = sswY[sswIdx];
                        if (leverFlipped(view, bx, by)) {
                            if (sswPhase != 0)
                                debugFile("[routine] SWITCHSWEEP lever (" + Reflect.fmt(bx) + "," + Reflect.fmt(by) + ") flipped");
                            else
                                debugFile("[routine] SWITCHSWEEP lever (" + Reflect.fmt(bx) + "," + Reflect.fmt(by) + ") already flipped; next");
                            blockClearStop();
                            routineShootActive = false;
                            sswIdx++;
                            sswPhase = 0;
                            sswPhaseStart = now;
                            routinePath.tgtX = Float.NaN;
                        } else if (sswPhase == 0) { // approach to firing range
                            float ddx = bx - mp[0], ddy = by - mp[1];
                            if (ddx * ddx + ddy * ddy <= SSW_SHOOT_RANGE_SQ) {
                                clearMovementKeys();
                                blockClearStop();
                                selectWeapon(controller, GUN_WEAPON_SLOT);
                                sswPhase = 1;
                                sswPhaseStart = now;
                            } else if (now - sswPhaseStart >= SSW_APPROACH_TIMEOUT_MS) {
                                // Levers are SOLID on the walk grid; a pocketed lever (no
                                // reachable cell within firing range) fails pathTo forever
                                // — skip to the next lever rather than idle the step.
                                blockClearStop();
                                debugFile("[routine] SWITCHSWEEP lever (" + Reflect.fmt(bx) + "," + Reflect.fmt(by)
                                        + ") unreachable (" + (SSW_APPROACH_TIMEOUT_MS / 1000) + "s); next");
                                sswIdx++;
                                sswPhase = 0;
                                sswPhaseStart = now;
                                routinePath.tgtX = Float.NaN;
                            } else { // path in; clear any breakable (shrub/stone/crystal) in the way
                                pathTo(view, mp, bx, by);
                                tickBreakableClear(controller, view, mp, routinePath);
                            }
                        } else { // sswPhase == 1: fire at the lever until it flips (or timeout)
                            clearMovementKeys();
                            if (now - sswPhaseStart >= SSW_SHOOT_TIMEOUT_MS) {
                                blockClearStop();
                                routineShootActive = false;
                                debugFile("[routine] SWITCHSWEEP lever (" + Reflect.fmt(bx) + "," + Reflect.fmt(by) + ") timeout; next");
                                sswIdx++;
                                sswPhase = 0;
                                sswPhaseStart = now;
                                routinePath.tgtX = Float.NaN;
                            } else if (!clearBreakableNear(controller, view, mp, bx, by)) {
                                // no covering (left) near the lever — fire at the lever itself
                                routineShootAngle = Reflect.aimAngleTo(mp, bx, by);
                                if (now - sswPhaseStart >= SHOOT_SETTLE_MS)
                                    routineShootActive = true;
                            }
                        }
                    } else { // routineSub == 2: return to (X,Y)
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            routineAdvance();
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    }
                    break;
                }
                case T_TREASURESWEEP: {
                    // MOVETO (X,Y); scan treasure blocks within 8 tiles; per block
                    // (nearest-first) approach to firing range and shoot until it's
                    // destroyed; return to (X,Y); then LOOT the drops. Main-only.
                    // STEP BACKSTOP: a sweep that can't finish (unreachable block/return/loot)
                    // is SKIPPED — advancing loses some loot but keeps the mission alive,
                    // instead of idling into the 60s watchdog's mission-failing abort.
                    if (tswStepStart == 0L)
                        tswStepStart = now;
                    if (now - tswStepStart >= TSW_STEP_TIMEOUT_MS) {
                        blockClearStop();
                        routineShootActive = false;
                        isLootMode = false;
                        clearMovementKeys();
                        debugFile("[routine] TREASURESWEEP (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty)
                                + ") step timeout (" + (TSW_STEP_TIMEOUT_MS / 1000) + "s) — skipping to the next step");
                        routineAdvance();
                        break;
                    }
                    if (routineSub == 0) { // path to (X,Y), then scan
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            float tswR = stepParam(2, -1f); // optional RANGE (tiles); <=0 = legacy default
                            tswScan(view, tx, ty, tswR > 0f ? tswR * tswR : TSW_SCAN_RADIUS_SQ);
                            tswIdx = 0;
                            tswPhase = 0;
                            tswPhaseStart = now;
                            routinePath.tgtX = Float.NaN;
                            if (tswCount == 0)
                                debugFile("[routine] TREASURESWEEP (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + ") no treasure; returning");
                            else
                                debugFile("[routine] TREASURESWEEP: " + tswCount + " block(s)");
                            routineSetSub(1);
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    } else if (routineSub == 1) { // shoot each treasure block in turn
                        if (tswIdx >= tswCount) {
                            routineShootActive = false;
                            routineSetSub(2);
                            break;
                        }
                        float bx = tswX[tswIdx], by = tswY[tswIdx];
                        boolean present = treasureOnTile(view, bx, by);
                        if (!present) { // destroyed (or splashed by a neighbour): next block
                            if (tswPhase != 0)
                                debugFile("[routine] TREASURESWEEP block (" + Reflect.fmt(bx) + "," + Reflect.fmt(by) + ") destroyed");
                            routineShootActive = false;
                            tswIdx++;
                            tswPhase = 0;
                            tswPhaseStart = now;
                            routinePath.tgtX = Float.NaN;
                        } else if (tswPhase == 0) { // approach to firing range
                            float ddx = bx - mp[0], ddy = by - mp[1];
                            if (ddx * ddx + ddy * ddy <= TSW_SHOOT_RANGE_SQ) {
                                clearMovementKeys();
                                selectWeapon(controller, GUN_WEAPON_SLOT);
                                tswPhase = 1;
                                tswPhaseStart = now;
                            } else if (now - tswPhaseStart >= TSW_APPROACH_TIMEOUT_MS) {
                                // Treasure blocks are SOLID on the walk grid; a pocketed
                                // block (no reachable adjacent tile) fails pathTo forever
                                // and used to idle the whole step — skip to the next block.
                                debugFile("[routine] TREASURESWEEP block (" + Reflect.fmt(bx) + "," + Reflect.fmt(by)
                                        + ") unreachable (" + (TSW_APPROACH_TIMEOUT_MS / 1000) + "s); next");
                                tswIdx++;
                                tswPhase = 0;
                                tswPhaseStart = now;
                                routinePath.tgtX = Float.NaN;
                            } else {
                                routineShootActive = false;
                                pathTo(view, mp, bx, by);
                            }
                        } else { // tswPhase == 1: fire at the block until it's gone (or timeout)
                            clearMovementKeys();
                            routineShootAngle = Reflect.aimAngleTo(mp, bx, by);
                            if (now - tswPhaseStart >= TSW_SHOOT_TIMEOUT_MS) {
                                routineShootActive = false;
                                debugFile("[routine] TREASURESWEEP block (" + Reflect.fmt(bx) + "," + Reflect.fmt(by) + ") timeout; next");
                                tswIdx++;
                                tswPhase = 0;
                                tswPhaseStart = now;
                                routinePath.tgtX = Float.NaN;
                            } else if (now - tswPhaseStart >= SHOOT_SETTLE_MS) {
                                routineShootActive = true;
                            }
                        }
                    } else if (routineSub == 2) { // return to (X,Y)
                        if (pathTo(view, mp, tx, ty)) {
                            blockClearStop();
                            if (tswCount == 0) { // nothing destroyed: skip the loot phase
                                routineAdvance();
                            } else { // loot the treasure drops from (X,Y)
                                clearMovementKeys();
                                isLootMode = true;
                                lootPlanned = false;
                                lootDoneLogged = false;
                                routineSetSub(3);
                            }
                        } else {
                            tickBreakableClear(controller, view, mp, routinePath);
                        }
                    } else { // routineSub == 3: LOOT subroutine drives movement; wait for it
                        if (lootDoneLogged) {
                            isLootMode = false;
                            routineAdvance();
                        }
                    }
                    break;
                }
            }

            // Shield-bump: lower the shield once its 750ms hold elapses (it was
            // raised in driveAlongPath while moving past a monster).
            serviceShieldBump(now);
            tickPinHold(mp); // hold the wall seat while a PIN_MOVETO scope is open (after the step, so it wins)

            // Mirror the main's combat state to the alts (broadcast on change only),
            // so they run their own combat bot in step — the routine sets isCombatBot
            // locally on the main; the alts only hear about it here.
            if (isCombatBot != routineCombatBroadcast)
                routineBroadcastCombat(isCombatBot);
        } catch (Exception e) {
            if (debug)
                debugFile("[routine] error: " + e);
        }
    }

    /** Tells the alts to (stop) run(ning) their combat bots, and records the state. */
    private static void routineBroadcastCombat(boolean on) {
        broadcast("COMBATBOT " + (on ? "1" : "0"));
        routineCombatBroadcast = on;
    }

    /**
     * MAIN, non-combat routine movement: if a Monster is within 1 tile, raise the
     * shield (main + all alts) for a 750ms "bump" that knocks it out of the path.
     * Called from driveAlongPath while actively moving; no-op during combat, off a
     * routine, mid-bump, or within the post-bump cooldown. Movement is unaffected
     * (shielding doesn't stop the walk).
     */
    private static void maybeStartShieldBump(Object view, float[] mp) {
        if (!isRoutineActive || isCombatBot)
            return;
        long now = System.currentTimeMillis();
        if (routineShieldHold || now < shieldBumpNextAllowedAt)
            return;
        if (enemiesWithin(view, mp, SHIELD_BUMP_RADIUS_SQ) == 0)
            return;
        routineShieldHold = true;      // main raises shield (poll holds X)
        shieldBumpReleaseAt = now + SHIELD_BUMP_HOLD_MS;
        broadcast("SHIELD 1");         // all alts raise shield
    }

    /** Lowers a shield-bump once its 750ms hold elapses (main + alts), then starts the cooldown. Called every routine tick. */
    private static void serviceShieldBump(long now) {
        if (!isSnarbyActive && routineShieldHold && now >= shieldBumpReleaseAt) { // SNARBY drives its own sustained shield
            routineShieldHold = false; // main lowers shield
            broadcast("SHIELD 0");     // all alts lower shield
            shieldBumpNextAllowedAt = now + SHIELD_BUMP_COOLDOWN_MS;
        }
    }

    /** Force-drops any active shield-bump (main + alts) — for routine stop/finish. */
    private static void releaseShieldBump() {
        if (routineShieldHold) {
            routineShieldHold = false;
            broadcast("SHIELD 0");
        }
        shieldBumpReleaseAt = 0L;
        shieldBumpNextAllowedAt = 0L;
    }

    // ===== SNARBY (Snarbolax boss fight) helpers =====

    /** Broadcast the alts' sustained shield state (SHIELD 1/0) — only on change (no spam). */
    private static void snarbySetAltShield(boolean on) {
        if (on != snarbyAltShieldBroadcast) {
            broadcast(on ? "SHIELD 1" : "SHIELD 0");
            snarbyAltShieldBroadcast = on;
        }
    }

    /** Clear all SNARBY state (main shield, alt shield, combat, bell shot) — on exit/stop/finish. */
    private static void snarbyStop() {
        isSnarbyActive = false;
        isCombatBot = false;
        routineShootActive = false;
        routineShieldHold = false;
        snarbyBellSelectAt = 0L;
        snarbyBellInRangeSince = 0L;
        snarbyBellCommitUntil = 0L;
        snarbyShieldClearAt = 0L;
        snarbyBossDead = false;
        if (snarbyAltDashReleaseAt != 0L) {
            broadcast("DASH 0"); // never leave the alts' dash keys held
            snarbyAltDashReleaseAt = 0L;
        }
        if (snarbyAltShieldBroadcast) {
            broadcast("SHIELD 0");
            snarbyAltShieldBroadcast = false;
        }
        if (snarbyAltBellShoot) {
            broadcast("SHOOTALT off"); // never leave the alts firing
            snarbyAltBellShoot = false;
        }
    }

    /** The Snarbolax boss actor (Monster-class, config contains "snarbolax"), or null. */
    private static Object findSnarbolax(Object view) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> monsterCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Monster");
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !monsterCls.isInstance(a))
                    continue;
                if (Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("snarbolax"))
                    return a;
            }
        } catch (Exception e) {
        }
        return null;
    }

    /**
     * True if the actor carries a "stun" status condition. Reads every 0-arg
     * ConfigReference[] getter on the actor (per-actor reflection to dodge the
     * cross-class method-caching gotcha) and matches any condition name containing
     * "stun" (a bell-stunned Snarbolax shows conds{QN=[Stun 3]}; empty otherwise).
     */
    private static boolean isStunned(Object actor) {
        try {
            for (java.lang.reflect.Method m : actor.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || !m.getReturnType().isArray())
                    continue;
                if (!m.getReturnType().getComponentType().getName()
                        .equals("com.threerings.config.ConfigReference"))
                    continue;
                Object arr;
                try {
                    arr = m.invoke(actor);
                } catch (Exception ie) {
                    continue;
                }
                if (!(arr instanceof Object[]))
                    continue;
                for (Object cr : (Object[]) arr) {
                    if (cr == null)
                        continue;
                    try {
                        Object nm = cr.getClass().getMethod("getName").invoke(cr);
                        if (nm != null && nm.toString().toLowerCase(Locale.ROOT).contains("stun"))
                            return true;
                    } catch (Exception ne) {
                    }
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    /** A Beast Bell switch (config contains "beast bell"). */
    private static boolean isBeastBell(Object a) {
        return Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("beast bell");
    }

    /** The nearest Beast Bell within SNARBY_BELL_RADIUS of the boss, or null — the bell the main should ring. */
    private static float[] bellNearBoss(Object view, Object boss) {
        try {
            float[] bp = Reflect.actorPos(boss);
            if (bp == null)
                return null;
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            float[] best = null;
            float bestD = SNARBY_BELL_RADIUS_SQ;
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isBeastBell(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - bp[0], dy = p[1] - bp[1];
                float d2 = dx * dx + dy * dy;
                if (d2 <= bestD) {
                    bestD = d2;
                    best = p;
                }
            }
            return best;
        } catch (Exception e) {
        }
        return null;
    }

    /**
     * COMBATLOOT: drive the MAIN one step around a 1-tile circle centred on (cx,cy)
     * — revolve instead of standing still. Steers toward a point ORBIT_LEAD radians
     * ahead of the main's current angle about the centre: on the ring that walks it
     * around; knocked off, the same target pulls it back on. Firing/shielding are
     * independent (the combat bot owns the mouse), so the main strafes and shoots.
     */
    private static void driveCombatOrbit(float[] mp, float cx, float cy) {
        long now = System.currentTimeMillis();
        if (now >= orbitPhaseUntil) { // flip move↔pause (stutter-step)
            orbitMoving = !orbitMoving;
            orbitPhaseUntil = now + (orbitMoving ? ORBIT_MOVE_MS : ORBIT_PAUSE_MS);
        }
        clearMovementKeys();
        if (!orbitMoving)
            return; // pause leg: stand still on the ring
        float ang = (float) Math.atan2(mp[1] - cy, mp[0] - cx) + ORBIT_LEAD;
        setMovementFromDelta(cx + ORBIT_RADIUS * (float) Math.cos(ang) - mp[0],
                cy + ORBIT_RADIUS * (float) Math.sin(ang) - mp[1]);
    }

    /** A* path-follow state (waypoints + progress), one instance per driver (routine, loot). */
    private static final class PathFollow {
        float tgtX = Float.NaN, tgtY = Float.NaN;
        float[] x, y;
        int idx;
        boolean noPathLogged;
    }

    /** Drives the main along an A* path to (tx,ty) for the routine. Returns true once arrived (or unreachable). */
    private static boolean pathTo(Object view, float[] mp, float tx, float ty) {
        return driveAlongPath(view, mp, tx, ty, routinePath, "routine", PATH_TARGET_ARRIVE_SQ);
    }

    /**
     * Drives the main via A* (obstacle-avoiding) toward (tx,ty), tracking progress
     * in the given PathFollow state. Re-plans when the target changes. Returns true
     * once arrived or if unreachable (skip rather than hang).
     */
    private static boolean driveAlongPath(Object view, float[] mp, float tx, float ty, PathFollow pf, String who,
            float targetArriveSq) {
        // Re-plan when the plan is cleared (routineResetStepState does that on EVERY step
        // advance) or the target moved. No periodic re-plan: a step boundary is already the
        // natural moment for the floor to have changed, and the step that OPENS terrain is
        // responsible for settling before it hands over (see ALCH_CHARGE's chain settle).
        if (pf.x == null || Float.isNaN(pf.tgtX)
                || Math.abs(tx - pf.tgtX) > 0.05f || Math.abs(ty - pf.tgtY) > 0.05f) {
            Object model = sceneModelOf(view);
            if (model == null) {
                clearMovementKeys();
                return false; // wait for the scene
            }
            float[][] wp = findPathWorld(model, view, mp[0], mp[1], tx, ty);
            pf.tgtX = tx;
            pf.tgtY = ty;
            pf.idx = 0;
            if (wp == null || wp.length == 0) {
                pf.x = new float[0];
                pf.y = new float[0];
                if (!pf.noPathLogged) {
                    debugFile("[" + who + "] no path to (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + "); skipping");
                    pf.noPathLogged = true;
                }
            } else {
                pf.x = new float[wp.length];
                pf.y = new float[wp.length];
                for (int i = 0; i < wp.length; i++) {
                    pf.x[i] = wp[i][0];
                    pf.y[i] = wp[i][1];
                }
                pf.noPathLogged = false;
                if (debug) { // the planned ROUTE — a detour shows up here as waypoints
                    StringBuilder wsb = new StringBuilder("[" + who + "] plan (" + Reflect.fmt(mp[0]) + "," + Reflect.fmt(mp[1])
                            + ") -> (" + Reflect.fmt(tx) + "," + Reflect.fmt(ty) + "): " + wp.length + " wp");
                    for (int i = 0; i < wp.length && i < 12; i++)
                        wsb.append(" (").append(Reflect.fmt(wp[i][0])).append(",").append(Reflect.fmt(wp[i][1])).append(")");
                    if (wp.length > 12)
                        wsb.append(" …");
                    debugFile(wsb.toString());
                }
            }
        }
        if (pf.x.length == 0) {
            clearMovementKeys();
            return true; // unreachable — skip rather than hang
        }
        int last = pf.x.length - 1;
        while (pf.idx < pf.x.length) {
            float ddx = pf.x[pf.idx] - mp[0], ddy = pf.y[pf.idx] - mp[1];
            float thr = (pf.idx == last) ? targetArriveSq : PATH_ARRIVE_SQ;
            if (ddx * ddx + ddy * ddy <= thr)
                pf.idx++;
            else
                break;
        }
        if (pf.idx >= pf.x.length) {
            clearMovementKeys();
            return true;
        }
        clearMovementKeys();
        // Siege-wheel gate: hold on safe ground rather than step into a lane a wheel is
        // about to sweep. Returns "not arrived", so the caller simply tries again next
        // tick — no step state changes, and the path stays valid.
        WheelDodge.updateWheelTracks(view, System.currentTimeMillis());
        if (WheelDodge.wheelHold(mp, pf.x[pf.idx], pf.y[pf.idx])) {
            WheelDodge.wheelHoldCenterNudge(mp); // never hold straddling between lanes
            maybeStartShieldBump(view, mp);
            return false;
        }
        setMovementFromDelta(pf.x[pf.idx] - mp[0], pf.y[pf.idx] - mp[1]);
        maybeStartShieldBump(view, mp); // non-combat routine move: bump monsters out of the path
        return false;
    }

    /**
     * Clears breakable blocks (shrubs) that sit on the path {@code pf} ahead of the
     * main: the nearest one within BLOCK_SHOOT_RANGE gets shot with weapon 2 (like
     * SHOOT) until it's gone. Movement is left to the caller — the main pushes up to
     * the block's collision and keeps firing until it breaks, then walks through.
     * Called from the loot-sweep driver and every routine step's PATH-IN leg
     * (MOVETO/LOOT/BUTTON/COMBAT/... approaches) — NOT from combat orbit phases
     * (it competes with the combat bot for the weapon) or carry steps (firing
     * would drop the carry).
     */
    private static void tickBreakableClear(Object controller, Object view, float[] mp, PathFollow pf) {
        float[] target = nearestBreakableOnPath(view, mp, pf);
        if (target == null) {
            blockClearStop();
            return;
        }
        long now = System.currentTimeMillis();
        if (!blockClearActive) {
            blockClearActive = true;
            blockClearSelectAt = now;
            selectWeapon(controller, GUN_WEAPON_SLOT);
        }
        routineShootAngle = Reflect.aimAngleTo(mp, target[0], target[1]);
        routineShootActive = (now - blockClearSelectAt >= SHOOT_SETTLE_MS); // fire once the gun has settled
    }

    /**
     * If a breakable block sits within ~1.5 tiles of (tx,ty) and within shooting
     * range of the main, fire weapon 2 at it (like SHOOT). Used during a BUTTON
     * press: a shrub on/next to the button stops the main ~1 tile short (so pathTo
     * already reported "arrived"), and this clears it so the step can land. Returns
     * true while firing. Reuses the block-clear fire state.
     */
    private static boolean clearBreakableNear(Object controller, Object view, float[] mp, float tx, float ty) {
        float[] blk = nearestBreakableNear(view, tx, ty, BUTTON_BREAKABLE_RADIUS_SQ);
        if (blk == null) {
            blockClearStop();
            return false;
        }
        float dmx = blk[0] - mp[0], dmy = blk[1] - mp[1];
        if (dmx * dmx + dmy * dmy > BLOCK_SHOOT_RANGE_SQ) {
            blockClearStop();
            return false; // too far to hit from here
        }
        long now = System.currentTimeMillis();
        if (!blockClearActive) {
            blockClearActive = true;
            blockClearSelectAt = now;
            selectWeapon(controller, GUN_WEAPON_SLOT);
        }
        routineShootAngle = Reflect.aimAngleTo(mp, blk[0], blk[1]);
        routineShootActive = (now - blockClearSelectAt >= SHOOT_SETTLE_MS);
        return true;
    }

    /** The nearest breakable block within radiusSq of (cx,cy), or null. */
    private static float[] nearestBreakableNear(Object view, float cx, float cy, float radiusSq) {
        float[] best = null;
        float bestD = Float.MAX_VALUE;
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isBreakableBlock(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - cx, dy = p[1] - cy;
                float d = dx * dx + dy * dy;
                if (d <= radiusSq && d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
        } catch (Exception e) {
        }
        return best;
    }

    // ── HAZARD_MOVETO trap tracking ───────────────────────────────────────────

    /** A packed key for a trap's centre (coords are multiples of 0.5). */
    private static long trapCenterKey(float cx, float cy) {
        return (((long) Math.round(cx * 2f)) << 32) ^ (Math.round(cy * 2f) & 0xffffffffL);
    }

    /** Parses "…/Spike/UxV" -> {U,V}, or null. */
    private static int[] parseTrapSize(String nm) {
        try {
            String last = nm.substring(nm.lastIndexOf('/') + 1); // e.g. "3x2"
            int xi = last.indexOf('x');
            if (xi <= 0)
                return null;
            return new int[] { Integer.parseInt(last.substring(0, xi).trim()),
                    Integer.parseInt(last.substring(xi + 1).trim()) };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The grid cells a UxV trap centred at (cx,cy) covers. Orientation comes from the
     * centre parity: an odd extent centres on a tile centre (.5), an even extent on a
     * boundary (.0) — so a "5x2" at (78.0,41.5) is 2 wide x 5 deep.
     */
    private static java.util.ArrayList<int[]> trapFootprintCells(float cx, float cy, int U, int V) {
        boolean cxHalf = (Math.round(cx * 2f) & 1) == 1;
        boolean cyHalf = (Math.round(cy * 2f) & 1) == 1;
        int xExt, yExt;
        boolean uOdd = (U & 1) == 1, vOdd = (V & 1) == 1;
        if (uOdd != vOdd) {
            int odd = uOdd ? U : V, even = uOdd ? V : U;
            xExt = cxHalf ? odd : even;
            yExt = cyHalf ? odd : even;
        } else {
            xExt = U;
            yExt = V; // same parity: assume unrotated (U=x, V=y)
        }
        int cxMin = (int) Math.floor(cx - xExt / 2.0f);
        int cxMax = (int) Math.floor(cx + xExt / 2.0f - 1e-3f);
        int cyMin = (int) Math.floor(cy - yExt / 2.0f);
        int cyMax = (int) Math.floor(cy + yExt / 2.0f - 1e-3f);
        java.util.ArrayList<int[]> cells = new java.util.ArrayList<int[]>();
        for (int x = cxMin; x <= cxMax; x++)
            for (int y = cyMin; y <= cyMax; y++)
                cells.add(new int[] { x, y });
        return cells;
    }

    /** Builds cell -> trap-centre-key from the static spike-trap TileEntries. */
    private static java.util.HashMap<Long, Long> buildTrapCells(Object model) {
        java.util.HashMap<Long, Long> map = new java.util.HashMap<Long, Long>();
        try {
            Object cfgmgr = model.getClass().getMethod("getConfigManager").invoke(model);
            java.util.Collection<?> entries = entriesCollection(model);
            if (entries == null)
                return map;
            for (Object e : entries) {
                if (e == null)
                    continue;
                String nm = entryConfigName(e).toLowerCase(Locale.ROOT);
                if (!nm.contains("traps and hazards/floor/spike/"))
                    continue;
                int[] uv = parseTrapSize(nm);
                if (uv == null)
                    continue;
                float[] c = entryPos(e, cfgmgr);
                if (c == null)
                    continue;
                long key = trapCenterKey(c[0], c[1]);
                for (int[] cell : trapFootprintCells(c[0], c[1], uv[0], uv[1]))
                    map.put(Long.valueOf(ck(cell[0], cell[1])), Long.valueOf(key));
            }
        } catch (Exception ex) {
            if (debug)
                debugFile("[hazard] trap-cell build error: " + ex);
        }
        return map;
    }

    /** Builds the trap-cell map once per scene (and clears stale state tracking). */
    private static void ensureTrapCells(Object view) {
        Object model = sceneModelOf(view);
        if (model == null || model == trapCellModel)
            return;
        trapCellModel = model;
        trapCellKey = buildTrapCells(model);
        trapLastState.clear();
        trapDownEdge.clear();
        if (debug)
            debugFile("[hazard] trap cells built: " + trapCellKey.size());
    }

    /** Each tick: read every Trap actor's _state, recording non-DOWN -> DOWN transitions (phase knowledge). */
    private static void updateTrapStates(Object view, long now) {
        try {
            // An observation gap (this machinery only runs during hazard-aware phases)
            // means every trap may have cycled any number of times since the last
            // sample: the recorded states are stale and any remembered DOWN edge no
            // longer describes the CURRENT down period. Re-baseline everything —
            // first observations carry no edge, so nothing is crossable until each
            // trap is WATCHED dropping again.
            if (now - trapLastSampleAt > TRAP_OBS_GAP_MS) {
                trapLastState.clear();
                trapDownEdge.clear();
            }
            trapLastSampleAt = now;
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !"Trap".equals(a.getClass().getSimpleName()))
                    continue;
                float[] p = Reflect.actorPos(a);
                Object stObj = (p == null) ? null : Reflect.readObjectFieldNullable(a, "_state");
                if (stObj == null)
                    continue;
                String st = stObj.toString();
                Long key = Long.valueOf(trapCenterKey(p[0], p[1]));
                String prev = trapLastState.put(key, st);
                if ("DOWN".equals(st)) {
                    if (prev != null && !"DOWN".equals(prev))
                        trapDownEdge.add(key); // watched it drop — this down period's start is known
                } else {
                    trapDownEdge.remove(key); // left DOWN — the next crossing needs a fresh observed drop
                }
            }
        } catch (Exception e) {
        }
    }

    /**
     * True if the trap (centre-key) is safe to START crossing: it is currently DOWN
     * AND its drop was OBSERVED (we know when this down period began). A trap that
     * already reads DOWN on first scan is NOT crossable — the remaining window is
     * unknowable, and on large patches that gamble killed knights mid-crossing
     * (user-directed revision 2026-08-05; the old rule crossed any DOWN on the
     * theory that WARN buffers the pop — it doesn't buffer a whole field). Cost:
     * at most one full trap cycle of waiting, only when observation just began.
     */
    private static boolean trapSafe(Long key, long now) {
        return "DOWN".equals(trapLastState.get(key)) && trapDownEdge.contains(key);
    }

    /** The trap centre-key of the cell the main is currently standing on, or null. */
    private static Long trapKeyAt(float[] mp) {
        if (trapCellKey == null)
            return null;
        return trapCellKey.get(Long.valueOf(ck((int) Math.floor(mp[0]), (int) Math.floor(mp[1]))));
    }

    /**
     * True if a trap-conscious mover should wait rather than step forward: the cell
     * ~0.8 tile ahead ON pf (the path being followed — hazPath for HAZARD_MOVETO /
     * HAZARD_LOOT's approach, lootPath for the loot sweep) is an unsafe trap AND the
     * main is on safe ground (not already crossing that trap, and not standing on
     * some other dangerous trap it should push off of).
     */
    private static boolean shouldHoldForTrap(float[] mp, long now, PathFollow pf) {
        if (pf.x == null || pf.idx >= pf.x.length)
            return false;
        float dx = pf.x[pf.idx] - mp[0], dy = pf.y[pf.idx] - mp[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float ax = mp[0], ay = mp[1];
        if (len > 0.05f) {
            ax += dx / len * 0.8f; // ~0.8 tile ahead, in the direction of travel
            ay += dy / len * 0.8f;
        }
        Long tKey = trapCellKey.get(Long.valueOf(ck((int) Math.floor(ax), (int) Math.floor(ay))));
        if (tKey == null || trapSafe(tKey, now))
            return false; // not heading onto a trap, or it's safe to cross
        Long botKey = trapCellKey.get(Long.valueOf(ck((int) Math.floor(mp[0]), (int) Math.floor(mp[1]))));
        if (tKey.equals(botKey))
            return false; // already on this trap: commit and push through
        if (botKey != null && !trapSafe(botKey, now))
            return false; // standing on a dangerous trap: push forward, don't wait on it
        return true; // safe ground about to step onto an unsafe trap: wait
    }

    /** Stops any in-progress block clearing (releases the fire). */
    private static void blockClearStop() {
        if (blockClearActive) {
            blockClearActive = false;
            routineShootActive = false;
        }
    }

    /**
     * The nearest breakable block within BLOCK_SHOOT_RANGE of the main that either
     * lies on the remaining path OR is close enough to be blocking the main right now
     * (within BLOCK_COLLIDE). The collide case matters when a speed boost carries the
     * main into a shrub before it's shot: driveAlongPath has already advanced its
     * waypoint index past that cell, so the shrub is no longer "on the remaining
     * path", yet it's still physically blocking — shoot it anyway.
     */
    private static float[] nearestBreakableOnPath(Object view, float[] mp, PathFollow pf) {
        if (pf == null || pf.x == null || pf.x.length == 0)
            return null;
        float[] best = null;
        float bestD = Float.MAX_VALUE;
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isBreakableBlock(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dmx = p[0] - mp[0], dmy = p[1] - mp[1];
                float dm2 = dmx * dmx + dmy * dmy;
                if (dm2 > BLOCK_SHOOT_RANGE_SQ || dm2 >= bestD)
                    continue;
                // GHOST blocks need no on-path test: being in range is enough. A ghost
                // gate RESPAWNS, and it re-erects unbreakable tiles that are never
                // shootable themselves — so a party that already walked past the ghost's
                // waypoint gets walled in with nothing eligible to shoot, and only the
                // 60s watchdog notices. Treating any in-range ghost as a target means the
                // gate is re-opened from wherever the party is standing, including while
                // HAZARD_MOVETO waits at a trap edge (that hold now clears too).
                if (isGhostBlock(a) || blockOnPath(p, pf) || dm2 <= BLOCK_COLLIDE_SQ)
                    best = p;
                if (best == p)
                    bestD = dm2;
            }
        } catch (Exception e) {
        }
        return best;
    }

    /** True if (p) lies on the remaining path (within BLOCK_PATH_MATCH of an upcoming waypoint). */
    private static boolean blockOnPath(float[] p, PathFollow pf) {
        for (int i = Math.max(0, pf.idx); i < pf.x.length; i++) {
            float dx = pf.x[i] - p[0], dy = pf.y[i] - p[1];
            if (dx * dx + dy * dy <= BLOCK_PATH_MATCH_SQ)
                return true;
        }
        return false;
    }

    /** Count of living Monster actors within sqrt(r2) tiles of (mp). */
    private static int enemiesWithin(Object view, float[] mp, float r2) {
        int n = 0;
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return 0;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> monsterCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Monster");
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !monsterCls.isInstance(a))
                    continue;
                Float hp = Reflect.readFloatFieldNullable(a, "_healthPct");
                if (hp != null && hp <= 0f)
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - mp[0], dy = p[1] - mp[1];
                if (dx * dx + dy * dy <= r2)
                    n++;
            }
        } catch (Exception e) {
        }
        return n;
    }

    /** Actor IDs of living Monster actors within sqrt(r2) tiles of (mp) — for identity-based wave detection. */
    private static java.util.HashSet<Integer> enemyIdsWithin(Object view, float[] mp, float r2) {
        java.util.HashSet<Integer> ids = new java.util.HashSet<Integer>();
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return ids;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> monsterCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Monster");
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !monsterCls.isInstance(a))
                    continue;
                Float hp = Reflect.readFloatFieldNullable(a, "_healthPct");
                if (hp != null && hp <= 0f)
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - mp[0], dy = p[1] - mp[1];
                if (dx * dx + dy * dy > r2)
                    continue;
                Integer id = Reflect.readIntFieldNullable(a, "_id");
                if (id != null)
                    ids.add(id);
            }
        } catch (Exception e) {
        }
        return ids;
    }

    /** Actor IDs of living Monster actors whose TILE is in {@code cells} — region-based (arena) wave detection. */
    private static java.util.HashSet<Integer> enemyIdsInCells(Object view, java.util.HashSet<Long> cells) {
        java.util.HashSet<Integer> ids = new java.util.HashSet<Integer>();
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return ids;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> monsterCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Monster");
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !monsterCls.isInstance(a))
                    continue;
                Float hp = Reflect.readFloatFieldNullable(a, "_healthPct");
                if (hp != null && hp <= 0f)
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                if (cells.contains(ck((int) Math.floor(p[0]), (int) Math.floor(p[1])))) {
                    Integer id = Reflect.readIntFieldNullable(a, "_id");
                    if (id != null)
                        ids.add(id);
                }
            }
        } catch (Exception e) {
        }
        return ids;
    }

    /**
     * The tile cells spanned by monster gates in the scene — read from the `Door | Door/Iron
     * Gate/Monster N` actors. Each gate is N×1; its orientation is whichever axis has UNWALKABLE
     * tiles at ±(N+1)/2 from the gate centre (exactly one axis, per the game). These gates seal a
     * PLATFORM arena during its waves, so they act as WALLS for the arena flood-fill (they stay
     * walkable for normal pathing). {@code walk} = the mob-traversable set (floor+stairs+hazards);
     * a gate end is flanked by cells NOT in it (true walls), which is how orientation is read.
     */
    // ── Straggler chase (COMBAT / COMBATLOOT / PLATFORM combat waits) ─────────
    //
    // User-directed 2026-08-05, after the 58-minute orbit stall: when exactly ONE mob
    // remains in the step's detection area, stop orbiting the fixed centre and PATH to
    // within CHASE_ARRIVE of the mob itself, the combat bot firing all the while (the
    // ATTACKMOVE pattern pointed at a live target). Guards, in order:
    //   HYSTERESIS — engage only after the SAME sole-survivor id has been alone for
    //     STRAGGLER_CONFIRM_MS (a range-edge flicker changes the count or the id and
    //     restarts the dwell); disengage INSTANTLY when a second mob shows up.
    //   HAZARDS — the drive is trap-timed like HAZARD_MOVETO (ensureTrapCells /
    //     updateTrapStates / shouldHoldForTrap on chasePath): wait on safe ground for
    //     spike cycles, never walk onto UP spikes after a mob that stands on them
    //     unharmed. Static hazards (brambles/fire) are already excluded by the walk
    //     grid, and the siege-wheel gate rides inside driveAlongPath.
    //   COMPLETION — KILL-style id presence: the chase resolves when the target's actor
    //     leaves the map (dead) — never by the centre-radius count, because the chase
    //     deliberately drags the fight OUT of that radius, which would false-clear.
    //   FAIL-FAST — driveAlongPath returns true for UNREACHABLE as well as arrived; if
    //     the plan is exhausted at the mob's live position and the gap is still open,
    //     no walkable cell reaches firing range: suppress that id for the rest of the
    //     step and go back to the orbit (the 4-minute combat bound keeps running
    //     underneath). CHASE_MAX_MS backstops a kiting target the same way.
    //   NO BLOCK-CLEARING — tickBreakableClear competes with the combat bot for the
    //     weapon/fire channel (nowhere in the codebase do they run together), so a
    //     chase walled off by breakables fail-fasts instead of shooting them.
    // PLATFORM extra: sealed monster-gate cells are walkable on the grid but solid
    // during waves — chaseGateCells (computed once per step) is fed to the planner via
    // pathExtraBlocked for exactly the duration of the drive call, so a chase can never
    // plan through a gate and pin the main against it.
    private static final PathFollow chasePath = new PathFollow();
    private static int chaseTargetId = -1;      // engaged target actor id (-1 = not chasing)
    private static int chaseConfirmId = -1;     // sole-survivor candidate during the dwell
    private static long chaseConfirmSince = 0L; // when the candidate became the sole survivor
    private static int chaseSuppressId = -1;    // proved unreachable this step — don't re-engage
    private static long chaseStartedAt = 0L;
    private static boolean chaseSawTarget = false;
    private static long chaseAbsentSince = 0L;
    private static boolean chaseHolding = false; // within CHASE_ARRIVE: stand and shoot
    private static float chaseTgtX = 0f, chaseTgtY = 0f; // quantized drive target
    private static java.util.HashSet<Long> chaseGateCells = null; // PLATFORM: sealed gate cells (per step)
    // Planner overlay: cells findPathWorld additionally treats as blocked. Set ONLY
    // around a single drive call (set -> drive -> null in a finally) so it can never
    // leak into another subsystem's planning.
    private static volatile java.util.HashSet<Long> pathExtraBlocked = null;
    private static final long STRAGGLER_CONFIRM_MS = 2500L; // sole-survivor dwell before engaging
    private static final float CHASE_ARRIVE_SQ = 25f;       // (5 tiles)^2 firing range (user-set)
    private static final float CHASE_REENGAGE_SQ = 42.25f;  // (6.5 tiles)^2 — resume driving past this
    private static final float CHASE_TARGET_QUANT_SQ = 1f;  // re-plan only when the mob moved > 1 tile
    private static final long CHASE_ABSENT_CONFIRM_MS = 1000L; // id gone this long => dead, resolve
    private static final long CHASE_MAX_MS = 30000L;        // kiting backstop => suppress + fall back

    private static void chaseReset() {
        chaseTargetId = -1;
        chaseConfirmId = -1;
        chaseConfirmSince = 0L;
        chaseStartedAt = 0L;
        chaseSawTarget = false;
        chaseAbsentSince = 0L;
        chaseHolding = false;
        chasePath.tgtX = Float.NaN;
        chasePath.x = null;
        chasePath.noPathLogged = false;
        // chaseGateCells + chaseSuppressId deliberately survive a reset: gates don't move
        // during a step, and a suppressed id stays hopeless from this step's ground.
        // Both clear in routineResetStepState/routineStopCleanup.
    }

    /** {x,y} of the actor with this id, or null when it's no longer in the map. */
    private static float[] actorPosById(Object view, int id) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object w = actorMap.getClass().getMethod("get", int.class).invoke(actorMap, id);
            Object a = (w == null) ? null : Reflect.getWrappedActor(w);
            return (a == null) ? null : Reflect.actorPos(a);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Straggler-chase driver for a combat wait. Returns true while the chase OWNS the
     * tick (a confirmed straggler is being chased or held at range) — the caller must
     * then skip its orbit AND its centre-radius clear test. Returns false when idle,
     * still confirming, or resolved; a killed straggler then surfaces in the caller's
     * normal flow as zero enemies -> clear debounce -> advance.
     */
    private static boolean tickStragglerChase(Object view, float[] mp, long now,
            java.util.HashSet<Integer> ids) {
        int count = ids.size();
        if (chaseTargetId < 0) {
            if (count != 1) {
                chaseConfirmId = -1; // zero or many — nothing to confirm
                return false;
            }
            int sole = ids.iterator().next().intValue();
            if (sole == chaseSuppressId)
                return false;
            if (sole != chaseConfirmId) {
                chaseConfirmId = sole; // new candidate — restart the dwell
                chaseConfirmSince = now;
                return false;
            }
            if (now - chaseConfirmSince < STRAGGLER_CONFIRM_MS)
                return false;
            chaseTargetId = sole;
            chaseStartedAt = now;
            chaseSawTarget = false;
            chaseAbsentSince = 0L;
            chaseHolding = false;
            float[] p0 = actorPosById(view, sole);
            chaseTgtX = (p0 == null) ? mp[0] : p0[0];
            chaseTgtY = (p0 == null) ? mp[1] : p0[1];
            chasePath.tgtX = Float.NaN; // force a fresh plan
            chasePath.x = null;
            debugFile("[chase] straggler id=" + sole + " confirmed — chasing to within "
                    + (int) Math.sqrt(CHASE_ARRIVE_SQ) + " tiles");
        }
        // Engaged.
        if (count >= 2) { // reinforcements / next wave — it's a real fight again
            debugFile("[chase] " + count + " enemies present — disengaging, back to orbit");
            chaseReset();
            return false;
        }
        if (now - chaseStartedAt >= CHASE_MAX_MS) {
            debugFile("[chase] target id=" + chaseTargetId + " not closed in "
                    + (CHASE_MAX_MS / 1000) + "s — suppressing, back to orbit");
            chaseSuppressId = chaseTargetId;
            chaseReset();
            return false;
        }
        float[] tp = actorPosById(view, chaseTargetId);
        if (tp == null) {
            // Left the actor map = dead; confirm briefly (KILL's pattern) before resolving.
            if (chaseAbsentSince == 0L)
                chaseAbsentSince = now;
            if (now - chaseAbsentSince >= CHASE_ABSENT_CONFIRM_MS) {
                debugFile("[chase] target id=" + chaseTargetId + " gone"
                        + (chaseSawTarget ? " (dead)" : " (never seen)") + " — resolved");
                chaseReset();
                return false; // normal clear detection takes over
            }
            clearMovementKeys(); // unknown position — hold, don't run at a stale coord
            return true;
        }
        chaseSawTarget = true;
        chaseAbsentSince = 0L;
        float ddx = tp[0] - mp[0], ddy = tp[1] - mp[1];
        float d2 = ddx * ddx + ddy * ddy;
        if (chaseHolding && d2 <= CHASE_REENGAGE_SQ) {
            clearMovementKeys(); // in range (with hysteresis): stand; the bot shoots
            return true;
        }
        if (d2 <= CHASE_ARRIVE_SQ) {
            chaseHolding = true;
            clearMovementKeys();
            return true;
        }
        chaseHolding = false;
        float qdx = tp[0] - chaseTgtX, qdy = tp[1] - chaseTgtY;
        if (Float.isNaN(chasePath.tgtX) || qdx * qdx + qdy * qdy > CHASE_TARGET_QUANT_SQ) {
            chaseTgtX = tp[0]; // re-plan only on real target movement, not jitter
            chaseTgtY = tp[1];
        }
        // Trap-timed drive: wait out raised spikes on safe ground (HAZARD_MOVETO's rules).
        ensureTrapCells(view);
        updateTrapStates(view, now);
        if (shouldHoldForTrap(mp, now, chasePath)) {
            clearMovementKeys();
            return true;
        }
        boolean arrived;
        pathExtraBlocked = chaseGateCells; // PLATFORM: never plan through a sealed gate
        try {
            arrived = driveAlongPath(view, mp, chaseTgtX, chaseTgtY, chasePath, "chase", CHASE_ARRIVE_SQ);
        } finally {
            pathExtraBlocked = null;
        }
        if (arrived && d2 > CHASE_ARRIVE_SQ) {
            float sdx = tp[0] - chaseTgtX, sdy = tp[1] - chaseTgtY;
            if (sdx * sdx + sdy * sdy > 0.25f) {
                // The exhausted plan chased a STALE spot (mob moved since) — replan live.
                chaseTgtX = tp[0];
                chaseTgtY = tp[1];
                chasePath.tgtX = Float.NaN;
                chasePath.x = null;
                return true;
            }
            // Plan exhausted AT the mob's live position with the gap still open: no
            // walkable cell reaches firing range — chasing harder won't help.
            debugFile("[chase] target id=" + chaseTargetId + " unreachable ("
                    + Reflect.fmt((float) Math.sqrt(d2)) + " tiles away) — suppressing, back to orbit");
            chaseSuppressId = chaseTargetId;
            chaseReset();
            return false;
        }
        return true;
    }

    private static java.util.HashSet<Long> monsterGateCells(Object view, java.util.HashSet<Long> walk) {
        java.util.HashSet<Long> cells = new java.util.HashSet<Long>();
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return cells;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null)
                    continue;
                String nm = Reflect.actorConfigName(a).toLowerCase(Locale.ROOT);
                if (!nm.contains("gate/monster")) // "Door/Iron Gate/Monster N"
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                int cx = (int) Math.floor(p[0]), cy = (int) Math.floor(p[1]);
                int n = parseTrailingInt(nm, 3); // gate width
                int half = (n - 1) / 2;          // gate spans centre ± half
                int k = half + 1;                // wall probe sits one tile past each end
                boolean ew = !walk.contains(ck(cx - k, cy)) && !walk.contains(ck(cx + k, cy));
                boolean ns = !walk.contains(ck(cx, cy - k)) && !walk.contains(ck(cx, cy + k));
                if (ew && !ns) {
                    for (int i = -half; i <= half; i++)
                        cells.add(ck(cx + i, cy));
                } else if (ns && !ew) {
                    for (int i = -half; i <= half; i++)
                        cells.add(ck(cx, cy + i));
                } else { // ambiguous (both/neither) — block just the centre cell as a safe minimum
                    cells.add(ck(cx, cy));
                    if (debug)
                        debugFile("[arena] monster gate (" + Reflect.fmt(p[0]) + "," + Reflect.fmt(p[1]) + ") orientation ambiguous (ew=" + ew + " ns=" + ns + ")");
                }
            }
        } catch (Exception e) {
        }
        return cells;
    }

    /**
     * Flood-fills the PLATFORM arena from (sx,sy). Traversable = floor/stairs PLUS hazard tiles
     * (mobs stand on bramble etc. unharmed, so hazards are "walkable" for the arena and mobs on
     * them count). Borders = TRUE walls/void AND monster-gate tiles. Returns the membership set =
     * connected interior + gate tiles (so a mob shoved onto a gate still counts). Returns EMPTY if
     * there are no monster gates, if (X,Y) isn't traversable, or if the fill runs away (unbounded)
     * — the caller then falls back to the fixed radius. 4-connected so a diagonal wall gap can't leak.
     */
    private static java.util.HashSet<Long> computePlatformArena(Object view, Object model, float sx, float sy) {
        java.util.HashSet<Long> arena = new java.util.HashSet<Long>();
        try {
            java.util.HashSet<Long> walk = buildWalkGrid(model);
            if (walk == null)
                return arena;
            // Mobs take no damage from hazards, so bramble/hazard tiles (which buildWalkGrid strips
            // out for the main's pathing) are traversable HERE and mobs on them are in the arena.
            java.util.HashSet<Long> mobWalk = new java.util.HashSet<Long>(walk);
            if (pathHazardCells != null)
                mobWalk.addAll(pathHazardCells);
            java.util.HashSet<Long> gates = monsterGateCells(view, mobWalk); // gates already traversable; we just border them
            if (gates.isEmpty())
                return arena; // no gates → fixed-radius fallback
            long start = ck((int) Math.floor(sx), (int) Math.floor(sy));
            if (!mobWalk.contains(start) || gates.contains(start))
                return arena;
            java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<Long>();
            java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
            q.add(start);
            seen.add(start);
            final int CAP = 4000; // a real (gated) arena is small; a runaway fill means a leak
            while (!q.isEmpty()) {
                if (seen.size() > CAP) {
                    if (debug)
                        debugFile("[arena] flood-fill exceeded " + CAP + " cells (unbounded?) — using fixed radius");
                    return new java.util.HashSet<Long>();
                }
                long c = q.poll();
                int x = ckx(c), y = cky(c);
                int[][] nb = { { x + 1, y }, { x - 1, y }, { x, y + 1 }, { x, y - 1 } };
                for (int[] d : nb) {
                    long nc = ck(d[0], d[1]);
                    if (seen.contains(nc) || !mobWalk.contains(nc) || gates.contains(nc))
                        continue; // already visited, a true wall/void, or a gate border
                    seen.add(nc);
                    q.add(nc);
                }
            }
            arena.addAll(seen);  // interior (floor + stairs + hazards)
            arena.addAll(gates); // gate tiles count for membership
        } catch (Exception e) {
        }
        return arena;
    }

    /** The last run of digits in {@code s} parsed as an int (e.g. "…/Monster 3" → 3), or {@code dflt}. */
    private static int parseTrailingInt(String s, int dflt) {
        int end = s.length();
        while (end > 0 && !Character.isDigit(s.charAt(end - 1)))
            end--;
        int start = end;
        while (start > 0 && Character.isDigit(s.charAt(start - 1)))
            start--;
        if (start < end) {
            try {
                return Integer.parseInt(s.substring(start, end));
            } catch (Exception e) {
            }
        }
        return dflt;
    }

    /** True if every other party member (Dungeoneer actor) is within GATHER_RADIUS of the main. */
    /** True when every OTHER Dungeoneer stands on exactly the tile containing (tx,ty). */
    private static boolean altsOnTile(Object view, int myId, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return true;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> pcCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Dungeoneer");
            int txi = (int) Math.floor(tx), tyi = (int) Math.floor(ty);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !pcCls.isInstance(a))
                    continue;
                Integer id = Reflect.readIntFieldNullable(a, "_id");
                if (id == null || id.intValue() == myId)
                    continue; // skip the main
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    return false;
                if ((int) Math.floor(p[0]) != txi || (int) Math.floor(p[1]) != tyi)
                    return false;
            }
            return true;
        } catch (Exception e) {
            return true; // fail-open: don't hang on gather
        }
    }

    private static boolean altsGathered(Object view, int myId, float[] mp) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return true;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> pcCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Dungeoneer");
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !pcCls.isInstance(a))
                    continue;
                Integer id = Reflect.readIntFieldNullable(a, "_id");
                if (id == null || id.intValue() == myId)
                    continue; // skip the main
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    return false;
                float dx = p[0] - mp[0], dy = p[1] - mp[1];
                if (dx * dx + dy * dy > GATHER_RADIUS_SQ)
                    return false;
            }
            return true;
        } catch (Exception e) {
            return true; // fail-open: don't hang on gather
        }
    }

    /** True once the SHOOT target near (tx,ty) is gone (block destroyed) or triggered (switch state != 0). */
    private static boolean shootTargetResolved(Object view, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return false;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null)
                    continue;
                String cls = a.getClass().getSimpleName();
                if (!(cls.equals("Block") || cls.equals("Stateful") || cls.equals("Switch")))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                if (dx * dx + dy * dy > 2.25f) // within 1.5 tiles of the target
                    continue;
                if (cls.equals("Switch")) {
                    Integer st = Reflect.readIntFieldNullable(a, "_state");
                    if (st == null || st.intValue() == 0)
                        return false; // lever not yet flipped
                    // else triggered — treat as resolved
                } else {
                    return false; // block/stateful still standing
                }
            }
            return true;
        } catch (Exception e) {
            return false; // keep firing until the timeout
        }
    }

    /**
     * True once the BUTTON at (tx,ty) has flipped: the CLOSEST button actor
     * (`isButtonActor` — config contains "button", class-agnostic so a Switch OR a
     * Stateful button both count) within 1.5 tiles reports `_state`!=0. Unlike
     * shootTargetResolved (borrowed from SHOOT), this looks ONLY at the button.
     */
    private static boolean buttonFlipped(Object view, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return false;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Object best = null;
            float bestSq = 2.25f; // within 1.5 tiles of the coord
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isButtonActor(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                float d2 = dx * dx + dy * dy;
                if (d2 <= bestSq) {
                    bestSq = d2;
                    best = a;
                }
            }
            if (best == null)
                return false; // button not seen yet — keep waiting (timeout guards)
            Integer st = Reflect.readIntFieldNullable(best, "_state");
            return st != null && st.intValue() != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Debug (BUTTON timeout only): lists nearby Switch/Stateful/Block/button actors so a hang can be diagnosed. */
    private static String describeButtonArea(Object view, float tx, float ty) {
        StringBuilder sb = new StringBuilder("near:");
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return sb.append(" <no actor map>").toString();
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            int n = 0;
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null)
                    continue;
                String cls = a.getClass().getSimpleName();
                if (!(cls.equals("Block") || cls.equals("Stateful") || cls.equals("Switch") || isButtonActor(a)))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                if (dx * dx + dy * dy > 6.25f) // within 2.5 tiles
                    continue;
                sb.append(" [").append(cls).append("|").append(Reflect.actorConfigName(a))
                        .append(" @(").append(Reflect.fmt(p[0])).append(",").append(Reflect.fmt(p[1])).append(") state=")
                        .append(Reflect.readIntFieldNullable(a, "_state")).append("]");
                n++;
            }
            if (n == 0)
                sb.append(" <nothing within 2.5 tiles>");
        } catch (Exception e) {
            sb.append(" <err ").append(e).append(">");
        }
        return sb.toString();
    }

    /** True if the actor is a destructible mineral node (Stateful/Block, config name has "mineral"). */
    private static boolean isMineralNode(Object a) {
        String cls = a.getClass().getSimpleName();
        if (!(cls.equals("Stateful") || cls.equals("Block")))
            return false;
        return Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("mineral");
    }

    /** Snapshot every mineral node within 5 tiles of (mp) into mineralNodeX/Y (= where the drops will land). */
    private static void scanMineralNodes(Object view, float[] mp) {
        java.util.ArrayList<float[]> nodes = new java.util.ArrayList<float[]>();
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap != null) {
                Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
                for (Object w : (Iterable<?>) values) {
                    Object a = Reflect.getWrappedActor(w);
                    if (a == null || !isMineralNode(a))
                        continue;
                    float[] p = Reflect.actorPos(a);
                    if (p == null)
                        continue;
                    float dx = p[0] - mp[0], dy = p[1] - mp[1];
                    if (dx * dx + dy * dy <= MINERAL_SCAN_RADIUS_SQ)
                        nodes.add(p);
                }
            }
        } catch (Exception e) {
        }
        mineralNodeCount = nodes.size();
        mineralNodeX = new float[mineralNodeCount];
        mineralNodeY = new float[mineralNodeCount];
        for (int i = 0; i < mineralNodeCount; i++) {
            mineralNodeX[i] = nodes.get(i)[0];
            mineralNodeY[i] = nodes.get(i)[1];
        }
    }

    /** The nearest still-standing mineral node within 5 tiles of (mp), or null if the cluster is clear. */
    private static float[] nearestLiveMineralNode(Object view, float[] mp) {
        float[] best = null;
        float bestD = Float.MAX_VALUE;
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isMineralNode(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - mp[0], dy = p[1] - mp[1];
                float d = dx * dx + dy * dy;
                if (d <= MINERAL_SCAN_RADIUS_SQ && d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
        } catch (Exception e) {
        }
        return best;
    }

    /**
     * Assigns each free party member (not already holding this floor) to a distinct
     * nearest mineral drop, greedily minimising travel. Fills mineralAssignMap keyed
     * by pawn id and broadcasts it to the alts. Leftover drops (more nodes than free
     * characters) are left behind; extra characters get no assignment and idle.
     */
    private static void planMineralGather(Object view) {
        mineralAssignMap.clear();
        try {
            java.util.ArrayList<Integer> freeIds = new java.util.ArrayList<Integer>();
            java.util.ArrayList<float[]> freePos = new java.util.ArrayList<float[]>();
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            Class<?> pcCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Dungeoneer");
            if (actorMap != null) {
                Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
                for (Object w : (Iterable<?>) values) {
                    Object a = Reflect.getWrappedActor(w);
                    if (a == null || !pcCls.isInstance(a))
                        continue;
                    Integer id = Reflect.readIntFieldNullable(a, "_id");
                    if (id == null || mineralHeldIds.contains(id))
                        continue; // already carrying a mineral this floor
                    float[] p = Reflect.actorPos(a);
                    if (p == null)
                        continue;
                    freeIds.add(id);
                    freePos.add(p);
                }
            }
            boolean[] charUsed = new boolean[freeIds.size()];
            boolean[] nodeUsed = new boolean[mineralNodeCount];
            int assignable = Math.min(freeIds.size(), mineralNodeCount);
            for (int k = 0; k < assignable; k++) {
                int bi = -1, bj = -1;
                float bd = Float.MAX_VALUE;
                for (int i = 0; i < freeIds.size(); i++) {
                    if (charUsed[i])
                        continue;
                    for (int j = 0; j < mineralNodeCount; j++) {
                        if (nodeUsed[j])
                            continue;
                        float dx = mineralNodeX[j] - freePos.get(i)[0], dy = mineralNodeY[j] - freePos.get(i)[1];
                        float d = dx * dx + dy * dy;
                        if (d < bd) {
                            bd = d;
                            bi = i;
                            bj = j;
                        }
                    }
                }
                if (bi < 0)
                    break;
                charUsed[bi] = true;
                nodeUsed[bj] = true;
                mineralAssignMap.put(freeIds.get(bi), new float[] { mineralNodeX[bj], mineralNodeY[bj] });
            }
        } catch (Exception e) {
        }
        if (!mineralAssignMap.isEmpty()) {
            StringBuilder sb = new StringBuilder("MINGATHER");
            for (java.util.Map.Entry<Integer, float[]> e : mineralAssignMap.entrySet())
                sb.append(' ').append(e.getKey()).append(' ').append(e.getValue()[0]).append(' ')
                        .append(e.getValue()[1]);
            broadcast(sb.toString());
        }
    }

    /**
     * Alt-side gather step (called each poll from injected code with the alt's own
     * position + pawn id). Drives the alt to its assigned drop and taps the attack
     * button once in range; idles if it has no assignment or has already picked up.
     */
    public static void tickMineralGatherAlt(Object view, float ax, float ay, int pawnId) {
        if (!isMineralGather)
            return;
        float[] tgt = mineralAssignMap.get(Integer.valueOf(pawnId));
        if (tgt == null || mineralPickedUp) {
            clearMovementKeys();
            mineralTapActive = false;
            pickupAimActive = false;
            return;
        }
        float dx = tgt[0] - ax, dy = tgt[1] - ay;
        clearMovementKeys();
        // PRE-AIM the whole final approach (see the main-side gather driver).
        mineralTapAngle = Reflect.aimAngleTo(new float[] { ax, ay }, tgt[0], tgt[1]); // face the drop
        pickupAimActive = true;
        pickupAimAngle = mineralTapAngle;
        if (dx * dx + dy * dy <= MINERAL_PICKUP_RADIUS_SQ) {
            if (mineralDropGone(view, tgt[0], tgt[1])) {
                mineralPickedUp = true;
                mineralTapActive = false;
                pickupAimActive = false;
            } else {
                mineralTapActive = true;
            }
        } else {
            setMovementFromDelta(dx, dy);
            mineralTapActive = false;
        }
    }

    /**
     * Alt-side SHOOT (called each tick from injected code with the alt's own pos +
     * pawn id): while isRoutineShootAlt, select weapon 2, aim at the broadcast target
     * from this alt's position, and fire via the shared routine-shoot poll block.
     * Holds position (auto-follow is gated off) so the shot stays lined up. The MAIN
     * no longer fires SHOOT, so a carried key is never dropped (SHOOT is KEY-GATE safe).
     */
    public static void tickRoutineShootAlt(Object view, float ax, float ay, int pawnId) {
        if (!isRoutineShootAlt) {
            routineShootActive = false;
            shootAltSelectedAt = 0L;
            return;
        }
        clearMovementKeys(); // hold position; aim + fire in place
        long now = System.currentTimeMillis();
        if (shootAltSelectedAt == 0L) {
            selectWeapon(dungeonClient, GUN_WEAPON_SLOT); // ensure weapon 2 (once, then settle)
            shootAltSelectedAt = now;
        }
        routineShootAngle = Reflect.aimAngleTo(new float[] { ax, ay }, shootAltX, shootAltY);
        routineShootActive = (now - shootAltSelectedAt >= SHOOT_SETTLE_MS);
    }

    /** True if the actor is a dropped mineral ("Actor | Mineral/Base") — the pickup a destroyed node leaves behind. */
    private static boolean isMineralDrop(Object a) {
        return Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("mineral/base");
    }

    /** True if no mineral drop remains within 1 tile of (tx,ty) — collected (or it never spawned). */
    private static boolean mineralDropGone(Object view, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return false; // can't tell — keep trying
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isMineralDrop(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                if (dx * dx + dy * dy <= MINERAL_DROP_MATCH_SQ)
                    return false; // still on the ground
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True once every assigned drop has been collected (main-side end condition). */
    private static boolean allMineralDropsGone(Object view) {
        if (mineralAssignMap.isEmpty())
            return true;
        for (float[] t : mineralAssignMap.values())
            if (!mineralDropGone(view, t[0], t[1]))
                return false;
        return true;
    }

    /**
     * Liftable matcher for the CURRENT step. KEY_LIFT/KEY_DROP match ONLY gold keys
     * ("Dynamic/Lift Objects/Gold Key" — legacy strictness, safe when a key and a
     * statue share a room); LIFT/DROP match anything the game files as liftable:
     * the "Dynamic/Lift Objects/" config folder (Gold Key, Heavy Statue, ...) plus
     * Grim Totems ("Dynamic/Monster Objects/Grim Totem" — liftable, but filed with
     * the monster props).
     */
    private static boolean liftableForStep(Object a) {
        String cfg = Reflect.actorConfigName(a).toLowerCase(Locale.ROOT);
        int t = (routineStepIdx < routineType.length) ? routineType[routineStepIdx] : -1;
        if (t == T_KEY_LIFT || t == T_KEY_DROP)
            return cfg.contains("gold key");
        return cfg.contains("lift objects/") || cfg.contains("grim totem");
    }

    /**
     * The liftable the MAIN is carrying, or null — a liftable whose {@code _flags}
     * has {@link #LIFT_CARRIED_FLAG} set, within LIFT_CARRY_RADIUS of the main (a
     * held object rides on its carrier, so the radius only serves to reject an
     * object an ALT is carrying while standing on top of us).
     */
    private static Object carriedLiftable(Object view, float[] mp) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Object best = null;
            float bestSq = LIFT_CARRY_RADIUS_SQ;
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !liftableForStep(a))
                    continue;
                Integer fl = Reflect.readIntFieldNullable(a, "_flags");
                if (fl == null || (fl.intValue() & LIFT_CARRIED_FLAG) == 0)
                    continue; // grounded
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - mp[0], dy = p[1] - mp[1];
                float d2 = dx * dx + dy * dy;
                if (d2 > bestSq)
                    continue;
                bestSq = d2;
                best = a;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Search radius² for the CURRENT lift step's target — see the constants:
     * KEY_LIFT is tolerant (1 tile, because a KEY_DROP can put the key down anywhere
     * within ~a tile of its own coord), LIFT/STATUE_LIFT strict (0.5 tile).
     */
    private static float liftTargetRadiusSq() {
        int t = (routineStepIdx < routineType.length) ? routineType[routineStepIdx] : -1;
        return (t == T_KEY_LIFT) ? KEY_LIFT_TARGET_RADIUS_SQ : LIFT_TARGET_RADIUS_SQ;
    }

    /**
     * The position of the GROUNDED liftable this LIFT step should pick up — the CLOSEST
     * one to the authored (tx,ty) within {@link #liftTargetRadiusSq()} — or null if none
     * is in range. The step aims and closes on THIS, not on the authored coordinate: a
     * liftable rarely sits exactly on the routine's coord to begin with, and one that has
     * been picked up and put back down wanders further with every attempt (measured:
     * 0.5 → 1.6 tiles), so a fixed coord aims the knight past it.
     */
    private static float[] groundedLiftablePos(Object view, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            float[] best = null;
            float bestSq = liftTargetRadiusSq();
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !liftableForStep(a))
                    continue;
                Integer fl = Reflect.readIntFieldNullable(a, "_flags");
                if (fl != null && (fl.intValue() & LIFT_CARRIED_FLAG) != 0)
                    continue; // someone is holding it
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                float d2 = dx * dx + dy * dy;
                if (d2 > bestSq)
                    continue;
                bestSq = d2;
                best = p;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }


    /** A button actor (config name contains "button" — e.g. Dynamic/Switch/Button/...). */
    private static boolean isButtonActor(Object a) {
        return Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("button");
    }

    /** Fills bswX/bswY/bswCount with button positions within sqrt(radiusSq) tiles of (cx,cy), nearest-first. */
    private static void bswScan(Object view, final float cx, final float cy, float radiusSq) {
        java.util.ArrayList<float[]> found = new java.util.ArrayList<float[]>();
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap != null) {
                Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
                for (Object w : (Iterable<?>) values) {
                    Object a = Reflect.getWrappedActor(w);
                    if (a == null || !isButtonActor(a))
                        continue;
                    float[] p = Reflect.actorPos(a);
                    if (p == null)
                        continue;
                    float dx = p[0] - cx, dy = p[1] - cy;
                    if (dx * dx + dy * dy <= radiusSq)
                        found.add(p);
                }
            }
        } catch (Exception e) {
        }
        found.sort(new java.util.Comparator<float[]>() {
            public int compare(float[] a, float[] b) {
                float da = (a[0] - cx) * (a[0] - cx) + (a[1] - cy) * (a[1] - cy);
                float db = (b[0] - cx) * (b[0] - cx) + (b[1] - cy) * (b[1] - cy);
                return Float.compare(da, db);
            }
        });
        bswCount = found.size();
        bswX = new float[bswCount];
        bswY = new float[bswCount];
        for (int i = 0; i < bswCount; i++) {
            bswX[i] = found.get(i)[0];
            bswY[i] = found.get(i)[1];
        }
    }

    /** A treasure block actor (config name contains "treasure" — e.g. Block/Treasure). */
    private static boolean isTreasureBlock(Object a) {
        return Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("treasure");
    }

    /** Fills tswX/tswY/tswCount with treasure-block positions within sqrt(radiusSq) tiles of (cx,cy), nearest-first. */
    private static void tswScan(Object view, final float cx, final float cy, float radiusSq) {
        java.util.List<float[]> found = new java.util.ArrayList<float[]>();
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap != null) {
                Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
                for (Object w : (Iterable<?>) values) {
                    Object a = Reflect.getWrappedActor(w);
                    if (a == null || !isTreasureBlock(a))
                        continue;
                    float[] p = Reflect.actorPos(a);
                    if (p == null)
                        continue;
                    float dx = p[0] - cx, dy = p[1] - cy;
                    if (dx * dx + dy * dy <= radiusSq)
                        found.add(p);
                }
            }
        } catch (Exception e) {
        }
        // Skip pocketed/winding blocks up front; in-range blocks are exempt (shot without walking).
        found = dropDetours(view, cx, cy, found, "routine", TSW_SHOOT_RANGE_SQ);
        found.sort(new java.util.Comparator<float[]>() {
            public int compare(float[] a, float[] b) {
                float da = (a[0] - cx) * (a[0] - cx) + (a[1] - cy) * (a[1] - cy);
                float db = (b[0] - cx) * (b[0] - cx) + (b[1] - cy) * (b[1] - cy);
                return Float.compare(da, db);
            }
        });
        tswCount = found.size();
        tswX = new float[tswCount];
        tswY = new float[tswCount];
        for (int i = 0; i < tswCount; i++) {
            tswX[i] = found.get(i)[0];
            tswY[i] = found.get(i)[1];
        }
    }

    /** A lever actor (config name contains "switch/lever" — e.g. Dynamic/Switch/Lever/One-Time). */
    private static boolean isLeverActor(Object a) {
        return Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("switch/lever");
    }

    /**
     * Fills sswX/sswY/sswCount with UNTRIGGERED lever positions within sqrt(radiusSq)
     * tiles of (cx,cy), nearest-first. Levers already reporting {@code _state != 0}
     * are SKIPPED outright (user-directed): a sweep must not walk to and shoot a
     * switch that is already thrown — with every lever in range already triggered the
     * scan comes back empty and the step just returns to (X,Y). An unreadable state
     * counts as untriggered, so an unknown lever is still handled.
     */
    private static void sswScan(Object view, final float cx, final float cy, float radiusSq) {
        java.util.ArrayList<float[]> found = new java.util.ArrayList<float[]>();
        int skipped = 0;
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap != null) {
                Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
                for (Object w : (Iterable<?>) values) {
                    Object a = Reflect.getWrappedActor(w);
                    if (a == null || !isLeverActor(a))
                        continue;
                    float[] p = Reflect.actorPos(a);
                    if (p == null)
                        continue;
                    float dx = p[0] - cx, dy = p[1] - cy;
                    if (dx * dx + dy * dy > radiusSq)
                        continue;
                    Integer st = Reflect.readIntFieldNullable(a, "_state");
                    if (st != null && st.intValue() != 0) {
                        skipped++; // already thrown — nothing to do here
                        continue;
                    }
                    found.add(p);
                }
            }
        } catch (Exception e) {
        }
        found.sort(new java.util.Comparator<float[]>() {
            public int compare(float[] a, float[] b) {
                float da = (a[0] - cx) * (a[0] - cx) + (a[1] - cy) * (a[1] - cy);
                float db = (b[0] - cx) * (b[0] - cx) + (b[1] - cy) * (b[1] - cy);
                return Float.compare(da, db);
            }
        });
        sswCount = found.size();
        sswX = new float[sswCount];
        sswY = new float[sswCount];
        for (int i = 0; i < sswCount; i++) {
            sswX[i] = found.get(i)[0];
            sswY[i] = found.get(i)[1];
        }
        if (skipped > 0)
            debugFile("[routine] SWITCHSWEEP: skipped " + skipped + " already-triggered lever(s)");
    }

    /**
     * True once the lever nearest (tx,ty) (within 1.5 tiles) reports {@code _state != 0}
     * — or is GONE (some switches despawn on trigger; it was seen at scan, so absence
     * means resolved, not not-yet-streamed). Only untriggered levers are ever scanned
     * (see sswScan), so state 0 = still needs shooting.
     */
    private static boolean leverFlipped(Object view, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return false;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Object best = null;
            float bestSq = 2.25f; // within 1.5 tiles of the recorded spot
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isLeverActor(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                float d2 = dx * dx + dy * dy;
                if (d2 <= bestSq) {
                    bestSq = d2;
                    best = a;
                }
            }
            if (best == null)
                return true; // seen at scan, gone now => triggered (despawn-on-flip)
            Integer st = Reflect.readIntFieldNullable(best, "_state");
            return st != null && st.intValue() != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** True if a treasure block still sits on the tile at (bx,by) (within 0.6 tile). */
    private static boolean treasureOnTile(Object view, float bx, float by) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return false;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !isTreasureBlock(a))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - bx, dy = p[1] - by;
                if (dx * dx + dy * dy <= TSW_BLOCK_MATCH_SQ)
                    return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    /**
     * The CLOSEST GOLD (key-openable) gate Door within 1.5 tiles of (tx,ty), or null.
     * Gates are "Door" actors; only "Iron Gate/Gold" ones use keys — a co-located
     * "…/Trigger" gate (button-opened) is ignored by config. CLOSEST, not first-in-range:
     * a TWIN gold gate ~1 tile away (e.g. 50.50,46.50 beside the target 50.50,47.50) also
     * falls inside the 1.5-tile radius, and latching the neighbour hung `GATE 50.50 47.50`
     * (the bot unlocked the target but tracked the twin, whose _stateEntered never changed).
     */
    private static Object findGateDoor(Object view, float tx, float ty) {
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return null;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Object best = null;
            float bestSq = GATE_DOOR_MATCH_SQ; // within 1.5 tiles of (tx,ty)
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !"Door".equals(a.getClass().getSimpleName()))
                    continue;
                if (!Reflect.actorConfigName(a).toLowerCase(Locale.ROOT).contains("gold"))
                    continue; // key gates are Gold; skip Trigger/etc. doors
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                float d2 = dx * dx + dy * dy;
                if (d2 <= bestSq) { // keep the nearest, so a twin gate can't be latched
                    bestSq = d2;
                    best = a;
                }
            }
            return best;
        } catch (Exception e) {
        }
        return null;
    }

    /** Debug (GATE timeout only): lists nearby Door actors (config + _stateEntered) so a twin-gate hang can be diagnosed. */
    private static String describeGateArea(Object view, float tx, float ty) {
        StringBuilder sb = new StringBuilder("doors near:");
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return sb.append(" <no actor map>").toString();
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            int n = 0;
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null || !"Door".equals(a.getClass().getSimpleName()))
                    continue;
                float[] p = Reflect.actorPos(a);
                if (p == null)
                    continue;
                float dx = p[0] - tx, dy = p[1] - ty;
                if (dx * dx + dy * dy > 9f) // within 3 tiles
                    continue;
                sb.append(" [").append(Reflect.actorConfigName(a))
                        .append(" @(").append(Reflect.fmt(p[0])).append(",").append(Reflect.fmt(p[1])).append(") stateEntered=")
                        .append(Reflect.readIntFieldNullable(a, "_stateEntered")).append("]");
                n++;
            }
            if (n == 0)
                sb.append(" <no Door within 3 tiles>");
        } catch (Exception e) {
            sb.append(" <err ").append(e).append(">");
        }
        return sb.toString();
    }

    /**
     * Resolves the current floor, loads its routine file (&lt;floorKey&gt;.txt, else
     * floor&lt;N&gt;.txt), and starts it — recording the floor key in campaignLastFloor.
     * Returns true if a routine started, false if the floor can't be resolved or no
     * valid file exists. Shared by the Ctrl+R start (first floor) and tickCampaign
     * (each subsequent floor). Extracted verbatim from the old inline ROUTINE-on logic.
     */
    /**
     * A label for the ACTIVE mission, for stats rows: the routines subfolder name (what
     * active_mission.txt selects, and how the user refers to a mission), falling back to
     * the launch id from mission_data. Null when no mission is loaded.
     */
    static String activeMissionLabel() {
        if (campaignMissionDir != null) {
            String d = campaignMissionDir.replace('\\', '/');
            int i = d.lastIndexOf('/');
            String base = (i >= 0) ? d.substring(i + 1) : d;
            if (!base.isEmpty())
                return base;
        }
        return campaignMissionId;
    }

    /**
     * The routine file that would drive {@code floorKey} — the SHARED top-level
     * mission_lobby.txt for the lobby, else {@code <activeMissionDir>/<floorKey>.txt}.
     * Null when no mission is loaded. Existence is NOT checked (see
     * {@link #campaignKnowsFloor}).
     */
    private static String routinePathForFloor(String floorKey) {
        if (floorKey == null)
            return null;
        if (floorKey.contains(CAMPAIGN_LOBBY_KEY))
            return ROUTINES_DIR + "/mission_lobby.txt";
        return (campaignMissionDir == null) ? null : campaignMissionDir + "/" + floorKey + ".txt";
    }

    /**
     * Whether the ACTIVE MISSION has instructions for where we are standing — the lobby,
     * or a floor with a routine file in its folder. This is the campaign's "am I still
     * somewhere I know what to do" test: once the routine list is finished, being on an
     * UNKNOWN floor means the run is over (partial or complete) and the main should make
     * a fresh lobby rather than wait to be returned to town.
     */
    private static boolean campaignKnowsFloor(String floorKey) {
        String p = routinePathForFloor(floorKey);
        return p != null && new File(p).isFile();
    }

    private static boolean startRoutineForCurrentFloor() {
        debugFile("[routine] floor info: " + floorInfoSummary());
        String floorKey = resolveFloorKey();
        if (floorKey == null) {
            debugFile("[routine] can't resolve the current floor (not in a dungeon?); not starting");
            return false;
        }
        // The lobby routine is SHARED (top-level mission_lobby.txt — every mission starts there);
        // every other floor lives in the active mission's subfolder, keyed by scene name.
        boolean isLobby = floorKey.contains(CAMPAIGN_LOBBY_KEY);
        String path = routinePathForFloor(floorKey);
        if (path == null) {
            debugFile("[routine] no active mission loaded (call loadActiveMission first); not starting");
            return false;
        }
        RoutineFile rf;
        try {
            if (!new File(path).isFile()) {
                debugFile("[routine] no routine file for this floor: " + path);
                return false;
            }
            rf = RoutineFile.load(path);
            debugFile("[routine] loaded " + path);
        } catch (Exception e) {
            debugFile("[routine] routine file malformed, not starting: " + path + ": " + e.getMessage());
            return false;
        }
        routineType = rf.type;
        routineX = rf.x;
        routineY = rf.y;
        routineParams = rf.params;
        isRoutineActive = true;
        routineStepIdx = 0;
        stuckStepIdx = -1; // re-arm the no-progress watchdog for the new floor
        routineResetStepState();
        isCombatBot = false;
        mineralHeldIds.clear(); // fresh floor: nobody is holding a mineral
        mineralAssignMap.clear();
        routineBroadcastCombat(false); // start with the alts' combat off
        routineBroadcastBreadcrumb(true); // alts retrace the main's path
        broadcast("GUNFAMILIES " + gunFamiliesPayload()); // idempotent re-push (covers an alt restarted mid-session)
        campaignLastFloor = floorKey;
        // Terminal tracking: the lobby resets the floor counter + starts the stats clock; each
        // non-lobby floor bumps campaignFloorSeq, so the numFloors-th one is the terminal floor.
        if (isLobby) {
            campaignFloorSeq = 0;
            MissionStats.missionStart();
        } else {
            campaignFloorSeq++;
            if (campaignFloorSeq == 1)
                MissionStats.markAllOnFloor1(); // runtime clock starts at floor 1, not the lobby
        }
        debugFile("[routine] START floor='" + floorKey + "' seq=" + campaignFloorSeq + "/" + campaignNumFloors
                + " (" + routineType.length + " steps)");
        return true;
    }

    /**
     * Loads the active mission config at Ctrl+R. routines/active_mission.txt names the mission
     * subfolder; routines/&lt;mission&gt;/mission_data.txt is one line "&lt;launchId&gt; &lt;numFloors&gt;
     * &lt;difficulty&gt;". Sets campaignMissionDir/Id/NumFloors/Difficulty (and resets the floor
     * counter). Returns false + logs if either file is missing or malformed — the campaign then
     * refuses to start rather than run blind.
     */
    private static boolean loadActiveMission() {
        try {
            String mission = firstConfigLine(new File(ROUTINES_DIR + "/active_mission.txt"));
            if (mission == null) {
                debugFile("[campaign] active_mission.txt missing/empty in " + ROUTINES_DIR);
                return false;
            }
            String dir = ROUTINES_DIR + "/" + mission;
            String line = firstConfigLine(new File(dir + "/" + MISSION_DATA_NAME));
            if (line == null) {
                debugFile("[campaign] no " + MISSION_DATA_NAME + " for mission '" + mission + "'");
                return false;
            }
            String[] tok = line.split("\\s+");
            if (tok.length < 3) {
                debugFile("[campaign] " + MISSION_DATA_NAME + " malformed (want '<id> <numFloors> <difficulty>'): " + line);
                return false;
            }
            int nf;
            try {
                nf = Integer.parseInt(tok[1]);
            } catch (NumberFormatException e) {
                debugFile("[campaign] " + MISSION_DATA_NAME + " bad floor count '" + tok[1] + "'");
                return false;
            }
            if (nf <= 0) {
                debugFile("[campaign] " + MISSION_DATA_NAME + " floor count must be > 0, got " + nf);
                return false;
            }
            campaignMissionDir = dir;
            campaignMissionId = tok[0];
            campaignNumFloors = nf;
            campaignDifficulty = tok[2];
            campaignFloorSeq = 0;
            loadMissionHazardConfigs(new File(dir + "/" + MISSION_DATA_NAME));
            loadMissionWeaponConfig(new File(dir + "/" + MISSION_DATA_NAME));
            broadcast("GUNFAMILIES " + gunFamiliesPayload()); // alts' combat bots switch on the same families
            debugFile("[campaign] active mission '" + mission + "' → id=" + campaignMissionId
                    + " floors=" + campaignNumFloors + " difficulty=" + campaignDifficulty);
            return true;
        } catch (Exception e) {
            debugFile("[campaign] loadActiveMission error: " + e);
            return false;
        }
    }

    /** First non-blank line of a file with any trailing {@code #} comment stripped (trimmed), or null. */
    /**
     * Extra placeable-config substrings this MISSION should treat as hazards, from an
     * optional {@code hazard_configs=a, b, c} line in mission_data.txt (case-insensitive,
     * comma-separated). Empty for every mission that doesn't opt in.
     *
     * <p>Exists because one dungeon marks its firestorm spawn points with plain
     * {@code Generic Marker} placeables — the tiles themselves land in RANDOM spots each
     * run, so the markers are the only stable thing to avoid. "Generic Marker" is far too
     * generic to match mod-wide (markers are spawn points, camera hints and script
     * anchors elsewhere), so the match is opt-in PER MISSION and can't touch a floor that
     * didn't ask for it.
     */
    private static volatile String[] missionHazardConfigs = new String[0];

    private static void loadMissionHazardConfigs(File missionData) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(missionData))) {
            String line;
            while ((line = br.readLine()) != null) {
                int h = line.indexOf('#');
                if (h >= 0)
                    line = line.substring(0, h);
                line = line.trim();
                if (!line.toLowerCase(Locale.ROOT).startsWith("hazard_configs"))
                    continue;
                int eq = line.indexOf('=');
                if (eq < 0)
                    continue;
                for (String s : line.substring(eq + 1).split(",")) {
                    s = s.trim().toLowerCase(Locale.ROOT);
                    if (!s.isEmpty())
                        out.add(s);
                }
            }
        } catch (Exception e) {
        }
        missionHazardConfigs = out.toArray(new String[0]);
        if (!out.isEmpty())
            debugFile("[campaign] mission hazard configs: " + out);
    }

    /**
     * Per-mission weapon config from optional mission_data.txt lines
     * {@code <slot> | <Autogun|Blaster> | <families>} (slot 1 or 2; the middle
     * cadence token is optional — {@code 2 | Construct, Slime, Undead} still
     * parses, defaulting slot 1 to Autogun 2-tap and slot 2 to Blaster 3-tap).
     * The "2" line's families are the ones the combat bot (main + alts) switches
     * to weapon 2 for; every other family — the "1" line's and any unlisted one —
     * gets weapon 1, so an underdetermined map just biases to weapon 1. A family
     * on BOTH lines logs a warning and weapon 1 wins. {@code 2 |} with no
     * families = never switch. NO weapon line at all = the legacy default trio
     * with default cadences, so config-less missions behave as before. The first
     * line seen per slot wins. (The pre-2026-08-27 {@code 3 |} spelling is no
     * longer accepted.) Loaded on the MAIN at Ctrl+R; pushed to the alts via the
     * GUNFAMILIES broadcast (see gunFamilies).
     */
    private static void loadMissionWeaponConfig(File missionData) {
        java.util.ArrayList<String> fams1 = null, fams2 = null; // null = no line for that slot
        boolean cad1 = false, cad2 = true; // cadence defaults: w1 Autogun, w2 Blaster
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(missionData))) {
            String line;
            while ((line = br.readLine()) != null) {
                int h = line.indexOf('#');
                if (h >= 0)
                    line = line.substring(0, h);
                line = line.trim();
                String[] seg = line.split("\\|", -1);
                String slotTag = seg[0].trim();
                boolean isSlot1 = "1".equals(slotTag), isSlot2 = "2".equals(slotTag);
                if (seg.length < 2 || (!isSlot1 && !isSlot2))
                    continue;
                if (isSlot1 ? fams1 != null : fams2 != null)
                    continue; // first line per slot wins
                // Optional cadence token between the slot and the families.
                String second = seg[1].trim().toLowerCase(Locale.ROOT);
                boolean blaster = "blaster".equals(second);
                String famsSeg = (blaster || "autogun".equals(second))
                        ? (seg.length >= 3 ? seg[2] : "")
                        : seg[1];
                java.util.ArrayList<String> out = new java.util.ArrayList<String>();
                for (String s : famsSeg.split(",")) {
                    s = s.trim().toLowerCase(Locale.ROOT);
                    if (!s.isEmpty())
                        out.add(s);
                }
                if (isSlot1) {
                    fams1 = out;
                    if (blaster || "autogun".equals(second))
                        cad1 = blaster;
                } else {
                    fams2 = out;
                    if (blaster || "autogun".equals(second))
                        cad2 = blaster;
                }
            }
        } catch (Exception e) {
        }
        String[] fams;
        if (fams1 == null && fams2 == null) {
            fams = new String[] { "construct", "slime", "undead" }; // legacy default (no lines)
        } else {
            // Weapon 2 gets exactly the "2" line's families; a family the "1" line
            // also claims is a CONFLICT — weapon 1 wins (same bias as unknowns).
            java.util.ArrayList<String> out = (fams2 == null)
                    ? new java.util.ArrayList<String>()
                    : new java.util.ArrayList<String>(fams2);
            if (fams1 != null)
                for (String s : fams1)
                    if (out.remove(s))
                        debugFile("[campaign] WARNING: family '" + s
                                + "' on both weapon lines — weapon 1 wins");
            fams = out.toArray(new String[0]);
        }
        gunFamilies = fams;
        slot1CadenceBlaster = cad1;
        slot2CadenceBlaster = cad2;
        debugFile("[campaign] weapon-2 families: "
                + (fams.length == 0 ? "(none — weapon 1 always)" : String.join(",", fams))
                + "; cadences w1=" + (cad1 ? "blaster" : "autogun")
                + " w2=" + (cad2 ? "blaster" : "autogun"));
    }

    /** The GUNFAMILIES broadcast payload: {@code <fams|-> <w1 cadence> <w2 cadence>}. */
    private static String gunFamiliesPayload() {
        String[] fams = gunFamilies;
        return (fams.length == 0 ? "-" : String.join(",", fams))
                + " " + (slot1CadenceBlaster ? "blaster" : "autogun")
                + " " + (slot2CadenceBlaster ? "blaster" : "autogun");
    }

    private static String firstConfigLine(File f) {
        if (!f.isFile())
            return null;
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                int h = line.indexOf('#');
                if (h >= 0)
                    line = line.substring(0, h);
                line = line.trim();
                if (!line.isEmpty())
                    return line;
            }
        } catch (Exception e) {
        }
        return null;
    }

    /**
     * One invite sweep (MAIN, off-thread): gathers every alt's knight name by UDP
     * round-trip (INVITEQUERY → INVITENAME), then party-invites each name missing from
     * the roster via the live PartyObject's real-named partyService (the same call the
     * game's invite dialog makes — see Mappings.callPartyInvite). Invited alts join via
     * their unchanged AutoJoiner poll, which an invite makes ~100% reliable.
     */
    private static void startInviteFill(final Object controller) {
        if (inviteFillRunning || controller == null)
            return;
        inviteFillRunning = true;
        new Thread(() -> {
            try {
                Object party = Reflect.partyObjectOf(controller);
                Object svc = (party == null) ? null : Reflect.readObjectFieldNullable(party, "partyService");
                if (svc == null)
                    return; // no live party service here — retry next sweep
                java.util.List<String> names = new java.util.ArrayList<>();
                DatagramSocket ds = new DatagramSocket();
                ds.setSoTimeout(200);
                byte[] q = ("INVITEQUERY " + ds.getLocalPort()).getBytes(StandardCharsets.UTF_8);
                java.net.InetAddress lo = java.net.InetAddress.getByName("127.0.0.1");
                for (int p = ALT_PORT_FIRST; p <= ALT_PORT_LAST; p++)
                    ds.send(new DatagramPacket(q, q.length, lo, p));
                byte[] buf = new byte[256];
                long deadline = System.currentTimeMillis() + 800;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        ds.receive(pkt);
                        String reply = new String(pkt.getData(), 0, pkt.getLength(),
                                StandardCharsets.UTF_8).trim();
                        if (reply.startsWith("INVITENAME ")) {
                            String kn = reply.substring("INVITENAME ".length()).trim();
                            if (!kn.isEmpty() && !names.contains(kn))
                                names.add(kn);
                        }
                    } catch (java.net.SocketTimeoutException e) {
                    }
                }
                ds.close();
                int sent = 0;
                for (String kn : names) {
                    if (partyHasMember(party, kn))
                        continue; // already on the roster
                    try {
                        Mappings.callPartyInvite(svc, new com.threerings.util.Name(kn));
                        sent++;
                    } catch (Throwable t) {
                        debugFile("[invite] invite '" + kn + "' failed: " + t);
                    }
                }
                if (sent > 0)
                    debugFile("[invite] sent " + sent + " party invite(s)");
            } catch (Exception e) {
                debugFile("[invite] sweep error: " + e);
            } finally {
                inviteFillRunning = false;
            }
        }, "SK InviteFill").start();
    }

    /** True if the party's members DSet has an entry whose (real-named) Name matches. */
    private static boolean partyHasMember(Object party, String knightName) {
        try {
            Object members = Reflect.readObjectFieldNullable(party, "members");
            if (members == null)
                return false;
            for (Object m : (Iterable<?>) members) {
                Object n = Reflect.readObjectFieldNullable(m, "name");
                if (n != null && knightName.equals(n.toString()))
                    return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Spawns this client's forge-all pass off-thread (forge every heat-ready item +
     * replace fully-leveled equipped items — ForgeAllAdapter.autoForgeAll, resolved
     * reflectively since the adapter compiles after SIS). Re-entry-guarded; alts
     * report FORGEDONE to the main when their pass ends (success or not).
     */
    private static void startForgePass(final Object ctx) {
        if (forgePassRunning || ctx == null)
            return;
        forgePassRunning = true;
        new Thread(() -> {
            try {
                Class.forName("com.threerings.projectx.item.client.ForgeAllAdapter")
                        .getMethod("autoForgeAll", Object.class).invoke(null, ctx);
            } catch (Throwable t) {
                debugFile("[forge] auto pass error: " + t);
            } finally {
                forgePassRunning = false;
                if (!isMainAccount())
                    sendToMain("FORGEDONE 1");
            }
        }, "SK AutoForge").start();
    }

    /**
     * Alt-side FORGEALL execution (game tick, while forgePassPending): waits until this
     * client is actually standing in the mission lobby, then runs its forge pass. The
     * pending flag survives scene changes, so an alt that missed the lobby (join bug /
     * mid-port) self-heals by forging at the next lobby it reaches.
     */
    public static void tickForgePass() {
        SpriteFeeder.tick(); // the sprite-feed pass shares this per-tick driver (same lobby deferral)
        try {
            if (!forgePassPending || forgePassRunning)
                return;
            String fk = resolveFloorKey();
            if (fk == null || !fk.contains(CAMPAIGN_LOBBY_KEY))
                return; // not in the mission lobby yet — keep waiting
            forgePassPending = false;
            startForgePass(_cachedCtx);
        } catch (Exception e) {
            if (debug)
                debugFile("[forge] tick error: " + e);
        }
    }

    /**
     * Campaign driver (MAIN only, every tick while campaignActive). Between floors —
     * once a routine finishes and a NEW floor has loaded — it waits for the whole party
     * to load in (loaded Dungeoneer count >= party roster) plus a settle, then starts
     * that floor's routine. Stops the campaign if the floor has no runnable routine. The
     * terminal-floor check lives in routineFinish; a running routine leaves this a no-op.
     */
    // Campaign idle watchdog (dead-man's switch). Anchor + wall-clock timer on the
    // MAIN's position; any real movement (>= 0.5 tile) re-anchors. An unreadable
    // position (loading screen, no pawn, no scene AT ALL) does NOT reset the timer —
    // a client wedged without a pawn is exactly a state to recover from; a NORMAL
    // between-floors load resolves to a far-away position, which re-anchors on
    // arrival. 2 minutes without movement while the cycle is on can never be
    // legitimate (every stationary wait — gathers, forge hold, buttons — is bounded
    // well under a minute), so it aborts + relaunches. Fires at most once per window
    // (the fire re-seeds the timer, giving each recovery attempt a fresh 2 minutes).
    private static float mainIdleX = 0f, mainIdleY = 0f; // last anchored main position
    private static long mainIdleSince = 0L;              // when it was anchored (0 = re-seed)
    private static final float MAIN_IDLE_EPS_SQ = 0.25f;      // (0.5 tile)^2 — less = "hasn't moved"
    private static final long MAIN_IDLE_TIMEOUT_MS = 120000L; // 2 minutes

    /** Delegate so the injected poll needs no AuctionBot stub — see AuctionBot.java. */
    public static void tickAuctionBot() {
        AuctionBot.tick(_cachedCtx);
    }

    /** Delegate so the injected poll needs no Relog stub — see Relog.java (auto-reconnect). */
    public static void tickRelog() {
        Relog.tick();
    }

    public static void tickMainIdleWatch(Object controller) {
        if (!campaignActive || !isMainAccount())
            return;
        long now = System.currentTimeMillis();
        float[] mp = null;
        try {
            Object view = (controller == null) ? null
                    : Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            Integer pawnId = (controller == null) ? null
                    : Reflect.readIntFieldNullable(controller, MappingsNames.PAWN_ID_FIELD);
            if (view != null && pawnId != null) {
                Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
                if (actorMap != null) {
                    Object w = actorMap.getClass().getMethod("get", int.class).invoke(actorMap, pawnId.intValue());
                    Object me = (w == null) ? null : Reflect.getWrappedActor(w);
                    if (me != null)
                        mp = Reflect.actorPos(me);
                }
            }
        } catch (Exception e) {
        }
        if (mainIdleSince == 0L) { // (re)seed: campaign just started or watchdog just fired
            mainIdleSince = now;
            if (mp != null) {
                mainIdleX = mp[0];
                mainIdleY = mp[1];
            }
            return;
        }
        if (mp != null) {
            float dx = mp[0] - mainIdleX, dy = mp[1] - mainIdleY;
            if (dx * dx + dy * dy >= MAIN_IDLE_EPS_SQ) { // moved — all is well
                mainIdleX = mp[0];
                mainIdleY = mp[1];
                mainIdleSince = now;
                return;
            }
        }
        if (now - mainIdleSince >= MAIN_IDLE_TIMEOUT_MS) {
            mainIdleSince = now; // fresh window for the recovery attempt itself
            campaignAbort("main idle for " + (MAIN_IDLE_TIMEOUT_MS / 1000) + "s — forcing a fresh mission");
        }
    }

    public static void tickCampaign(Object controller) {
        try {
            if (!campaignActive)
                return;
            if (campaignRestartPending) {
                tickCampaignRestart();
                return;
            }
            if (isRoutineActive || controller == null)
                return; // a routine is running (or no dungeon controller yet) — leave it be
            String floorKey = resolveFloorKey();
            if (floorKey == null || floorKey.equals(campaignLastFloor))
                return; // scene hasn't settled onto a NEW floor yet
            // Fresh floor, routine not started yet: clear any STALE movement keys carried over
            // from the previous floor.
            clearMovementKeys();
            Object view = Reflect.readObjectFieldNullable(controller, MappingsNames.VIEW_FIELD);
            int loaded = (view == null) ? 0 : countDungeoneers(view);
            int roster = campaignPartySize(); // PartyObject members = party membership
            long now = System.currentTimeMillis();
            // LOBBY FORGE KICKOFF (once per lobby, the moment the main is in it — runs in
            // parallel with the alt-join wait below): every client forges + restocks its
            // own equipment. Alts defer until they're in the lobby (tickForgePass).
            if (floorKey.contains(CAMPAIGN_LOBBY_KEY) && campaignForgeStartedAt == 0L) {
                campaignForgeStartedAt = now;
                forgeDoneAltCount = 0;
                // Settle before the FIRST invite of this lobby (user-tuned): inviting the
                // instant the lobby forms catches alts mid-scene-transition and blackscreens
                // them between loading screens. Later sweeps keep the normal 5s cadence.
                inviteNextAt = now + INVITE_FIRST_DELAY_MS + randRangeMs(0, INVITE_FIRST_JITTER_MS);
                broadcast("FORGEALL 1");
                startForgePass(_cachedCtx);
                // Sprite-feed pass rides the same once-per-lobby kickoff + hold.
                SpriteFeeder.resetLobby();
                broadcast("FEEDALL 1");
                SpriteFeeder.startPass(_cachedCtx);
                debugFile("[campaign] lobby forge + sprite-feed passes started (main + alts)");
            }
            // INVITE-BASED LOBBY FILL (any in-mission floor, lobby included): a short roster
            // means alts are missing — INVITE them by knight name every ~5s instead of
            // waiting on uninvited auto-join (unreliable). Runs during the lobby wait AND
            // per-floor gates, so a solo descend still fills the party at floor 1.
            if (roster >= 1 && roster < CAMPAIGN_PARTY_SIZE
                    && now >= inviteNextAt && !inviteFillRunning) {
                inviteNextAt = now + INVITE_INTERVAL_MS + randRangeMs(0, 1000);
                startInviteFill(controller);
            }
            // PER-FLOOR GATE (every floor): wait until all CAMPAIGN_PARTY_SIZE players are BOTH in
            // the party roster AND physically loaded before starting — so no alt lags behind at the
            // start line. Requiring LOADED (not just roster) also stops the main running ahead: on a
            // floor transition the roster is already full while actors are still loading, so a
            // roster-only gate would start too early.
            boolean ready = (loaded >= CAMPAIGN_PARTY_SIZE && roster >= CAMPAIGN_PARTY_SIZE);
            if (!ready && floorKey.contains(CAMPAIGN_LOBBY_KEY)) {
                if (roster == 1) {
                    if (lobbyBugSince == 0L)
                        lobbyBugSince = now;
                    else if (now - lobbyBugSince >= CAMPAIGN_LOBBY_BUG_MS) {
                        debugFile("[campaign] lobby join-bug (roster stuck at 1 for "
                                + (CAMPAIGN_LOBBY_BUG_MS / 1000) + "s) — descending solo; alts join next floor");
                        ready = true;
                    }
                } else {
                    lobbyBugSince = 0L; // roster grew — alts ARE joining, not the bug
                }
            } else {
                lobbyBugSince = 0L;
            }
            if (!ready) {
                campaignLoadedSince = 0L; // not everyone's fully in yet (roster AND loaded)
                return;
            }
            if (campaignLoadedSince == 0L) {
                campaignLoadedSince = now; // ready — start the settle
                return;
            }
            if (now - campaignLoadedSince < CAMPAIGN_LOAD_SETTLE_MS)
                return;
            // FORGE + FEED HOLD (lobby only): don't start the lobby routine (walk to the
            // elevator) until the main's own forge AND sprite-feed passes are done AND every
            // alt replied FORGEDONE + FEEDDONE — a descent mid-pass would cut off the service
            // calls (and feeding is lobby-only, so a cut-off feed is lost until the next
            // lobby). Solo descend (join bug, roster<4) only waits for the main's own passes;
            // the shared backstop bounds a lost reply.
            if (floorKey.contains(CAMPAIGN_LOBBY_KEY) && campaignForgeStartedAt != 0L) {
                boolean altsDone = roster < CAMPAIGN_PARTY_SIZE
                        || (forgeDoneAltCount >= CAMPAIGN_PARTY_SIZE - 1
                                && SpriteFeeder.altsDone(CAMPAIGN_PARTY_SIZE - 1));
                if (forgePassRunning || SpriteFeeder.running() || !altsDone) {
                    if (now - campaignForgeStartedAt < FORGE_HOLD_BACKSTOP_MS)
                        return; // still forging/feeding — hold the lobby
                    debugFile("[campaign] forge/feed hold backstop (" + (FORGE_HOLD_BACKSTOP_MS / 1000)
                            + "s) — proceeding (" + forgeDoneAltCount + " forge, "
                            + "feed done per SpriteFeeder)");
                }
                campaignForgeStartedAt = 0L; // reset for the next lobby visit
            }
            debugFile("[campaign] floor '" + floorKey + "' ready (" + loaded + " loaded, " + roster
                    + " roster); starting its routine");
            if (!startRoutineForCurrentFloor()) {
                // No routine for this floor while CYCLING = the run is over — we descended past
                // the floors this mission has routes for (a partial run's last ELEVATOR, or a
                // floor not authored yet). ABORT so it RELAUNCHES.
                campaignAbort("no routine for floor '" + floorKey + "' — run complete, relaunching");
            }
        } catch (Exception e) {
            if (debug)
                debugFile("[campaign] error: " + e);
        }
    }

    /** A random duration in [min,max] ms (inclusive-ish) — for jittered settle/port timings. */
    private static long randRangeMs(long min, long max) {
        return (max <= min) ? min : min + (long) (Math.random() * (max - min + 1));
    }

    /**
     * Endless-cycle restart driver (MAIN only, via tickCampaign while
     * campaignRestartPending). Phase 0: wait for the mission to auto-advance us back
     * to town (no LevelPartyObject) + a settle, then relaunch the mission privately on
     * Elite. Phase 1: wait for the fresh lobby to load, then hand back to tickCampaign
     * (which auto-starts the lobby routine once the party is in), restarting the run.
     */
    private static void tickCampaignRestart() {
        try {
            Object ctx = _cachedCtx;
            if (ctx == null)
                return;
            long now = System.currentTimeMillis();
            if (campaignRestartPhase == 0) { // AWAIT TOWN → LAUNCH
                boolean inTown = (Mappings.getLevelPartyObject(ctx) == null);
                if (!inTown) {
                    campaignTownSince = 0L; // still in the dungeon / mid-transition
                    // PARTIAL RUN: the run is over (numFloors done) but the MISSION is still
                    // live, so town never comes on its own. The test is "do we still have
                    // instructions for where we're standing" — on an UNKNOWN floor (e.g. the
                    // routine's last ELEVATOR dropped us onto a floor with no routine file)
                    // there is nothing left to do, so make our own lobby NOW. A floor we DO
                    // know is the real-completion case mid-advance (the boss floor, waiting
                    // out the ~15s auto-return to town) — keep waiting there so the
                    // end-of-mission payout is never cut short.
                    String fkNow = resolveFloorKey();
                    if (!campaignKnowsFloor(fkNow)) {
                        try {
                            Mappings.launchMission(ctx, campaignMissionId, campaignDifficulty, true, true, true);
                            campaignRelaunchAt = now;
                            campaignRestartPhase = 1;
                            debugFile("[campaign] run complete on an unrouted floor '" + fkNow
                                    + "' — launching a fresh '" + campaignMissionId + "' in place");
                        } catch (Exception e) {
                            debugFile("[campaign] in-place launchMission failed: " + e + " — retrying");
                        }
                    }
                    return;
                }
                if (campaignTownSince == 0L) {
                    campaignTownSince = now; // reached Haven — start the settle
                    campaignTownSettleMs = randRangeMs(CAMPAIGN_TOWN_SETTLE_MIN_MS, CAMPAIGN_TOWN_SETTLE_MAX_MS);
                    debugFile("[campaign] back in town — settling " + campaignTownSettleMs + "ms before relaunch");
                    return;
                }
                if (now - campaignTownSince < campaignTownSettleMs)
                    return;
                try {
                    Mappings.launchMission(ctx, campaignMissionId, campaignDifficulty, true, true, true);
                    campaignRelaunchAt = now;
                    campaignRestartPhase = 1;
                    debugFile("[campaign] relaunched '" + campaignMissionId + "' — awaiting the lobby");
                } catch (Exception e) {
                    campaignTownSince = 0L; // launch failed — re-settle and retry
                    debugFile("[campaign] launchMission failed: " + e + " — retrying");
                }
                return;
            }
            // PHASE 1: AWAIT the fresh lobby (new dungeon client loads it).
            boolean inLevel = (Mappings.getLevelPartyObject(ctx) != null);
            String fk = resolveFloorKey();
            if (inLevel && fk != null && fk.contains(CAMPAIGN_LOBBY_KEY)) {
                campaignRestartPending = false;
                campaignRestartPhase = 0;
                campaignLastFloor = null;  // force tickCampaign to (re)start this floor's routine
                campaignLoadedSince = 0L;
                debugFile("[campaign] lobby '" + fk + "' loaded — campaign restarting");
                return;
            }
            if (now - campaignRelaunchAt > CAMPAIGN_RELAUNCH_TIMEOUT_MS) {
                // No lobby in time — re-attempt the launch in place; the invite fill
                // brings the alts in once the lobby finally forms.
                campaignRelaunchAt = now;
                try {
                    Mappings.launchMission(ctx, campaignMissionId, campaignDifficulty, true, true, true);
                    debugFile("[campaign] no lobby within timeout — re-launched '" + campaignMissionId + "'");
                } catch (Exception e) {
                    debugFile("[campaign] re-launch failed: " + e);
                }
            }
        } catch (Exception e) {
            debugFile("[campaign] restart error: " + e);
        }
    }

    /** The party roster size (PartyObject.members count) on the main's dungeonClient, or -1 if unreadable. */
    static int campaignPartySize() { // package-private: MissionStats sizes its crown poll by it
        try {
            if (dungeonClient == null)
                return -1;
            Object partyObj = Reflect.partyObjectOf(dungeonClient);
            if (partyObj == null)
                return -1;
            Object members = partyObj.getClass().getField("members").get(partyObj);
            if (members == null)
                return -1;
            int n = 0;
            for (Object m : (Iterable<?>) members)
                n++;
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Count of Dungeoneer (player-character) actors currently loaded in the scene. */
    private static int countDungeoneers(Object view) {
        int n = 0;
        try {
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return 0;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            Class<?> pcCls = Class.forName("com.threerings.projectx.dungeon.data.actor.Dungeoneer");
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a != null && pcCls.isInstance(a))
                    n++;
            }
        } catch (Exception e) {
        }
        return n;
    }

    /**
     * No-progress watchdog. If the main hasn't moved more than STUCK_MOVE_EPS from its
     * anchor for STUCK_TIMEOUT_MS while on the SAME step, the run is stuck (blocked path,
     * unexpected mob behaviour) — abort and relaunch a fresh mission. The anchor re-seeds on
     * every step change and every time the main makes real progress, so long-but-moving
     * steps (combat orbit, loot sweeps) never trip it. Returns true if it aborted (the caller
     * must stop processing this tick). MAIN-only (tickRoutine is main-only).
     */
    private static boolean checkRoutineStuck(float[] mp, long now) {
        if (mp == null)
            return false;
        if (routineStepIdx != stuckStepIdx) { // new step — (re)anchor
            stuckStepIdx = routineStepIdx;
            stuckAnchorX = mp[0];
            stuckAnchorY = mp[1];
            stuckSince = now;
            return false;
        }
        float dx = mp[0] - stuckAnchorX, dy = mp[1] - stuckAnchorY;
        if (dx * dx + dy * dy >= STUCK_MOVE_EPS_SQ) { // moved = progress — re-anchor, reset the timer
            stuckAnchorX = mp[0];
            stuckAnchorY = mp[1];
            stuckSince = now;
            return false;
        }
        if (now - stuckSince >= STUCK_TIMEOUT_MS) {
            // Include the SUB-PHASE and where the main actually froze: a multi-phase step
            // (COMBATLOOT = path-in / orbit-fight / return / loot) stalls for completely
            // different reasons per phase, and the step coord alone can't tell them apart.
            campaignAbort("no progress for " + (STUCK_TIMEOUT_MS / 1000) + "s on step "
                    + routineStepIdx + " " + RoutineFile.typeName(routineType[routineStepIdx])
                    + " (" + Reflect.fmt(routineX[routineStepIdx]) + "," + Reflect.fmt(routineY[routineStepIdx]) + ")"
                    + " sub=" + routineSub + " stuck@(" + Reflect.fmt(mp[0]) + "," + Reflect.fmt(mp[1]) + ")");
            return true;
        }
        return false;
    }

    private static final long COMBAT_PHASE_MAX_MS = 240000L; // 4 min in ONE combat wait => abort + relaunch

    /** True (after firing the abort) when the current combat wait has exceeded its bound. */
    private static boolean combatPhaseOvertime(long now) {
        if (now - routineSubStart < COMBAT_PHASE_MAX_MS)
            return false;
        campaignAbort("combat unresolved for " + (COMBAT_PHASE_MAX_MS / 1000) + "s on step "
                + routineStepIdx + " " + RoutineFile.typeName(routineType[routineStepIdx])
                + " (" + Reflect.fmt(routineX[routineStepIdx]) + "," + Reflect.fmt(routineY[routineStepIdx]) + ")"
                + " sub=" + routineSub);
        return true;
    }

    /**
     * Aborts the current run and relaunches a fresh mission party. Triggered by the
     * no-progress watchdog, a KEY_LIFT/GATE timeout, a gather timeout, or the main dying
     * twice (MissionStats' death watch) — states a floor can't recover from.
     * Launches immediately in place (a mid-mission launch pulls the main to a new lobby)
     * and the INVITE-based lobby fill brings the alts in from wherever they are — the old
     * ready-room porting (main + alt RRPORT) was removed once invites made it redundant.
     */
    static void campaignAbort(String reason) { // package-private: MissionStats' death watch aborts too
        debugFile("[campaign] ABORT: " + reason);
        MissionStats.missionEnd("FAILURE", campaignLastFloor, reason); // log the failed attempt (no-op if none tracked)
        routineStopCleanup();
        stuckStepIdx = -1; // reset the watchdog for the next run
        if (!campaignActive)
            return; // not cycling — just stop
        campaignRestartPending = true;
        campaignRestartPhase = 1; // launch now, then await the fresh lobby
        campaignTownSince = 0L;
        campaignLastFloor = null;
        campaignLoadedSince = 0L;
        campaignForgeStartedAt = 0L; // an abort mid-forge-hold must not skip the next lobby's pass
        campaignRelaunchAt = System.currentTimeMillis();
        try {
            Mappings.launchMission(_cachedCtx, campaignMissionId, campaignDifficulty, true, true, true);
            debugFile("[campaign] abort → relaunched '" + campaignMissionId + "' — awaiting the fresh lobby");
        } catch (Exception e) {
            debugFile("[campaign] abort launch failed: " + e + " — phase 1 will retry");
        }
    }

    /** Stops the running routine and clears all combat/loot/shield/mineral state (main + alts). */
    private static void routineStopCleanup() {
        isRoutineActive = false;
        isCombatBot = false;
        spriteAimedRequest = false; // a pending auto-fire must not survive the run
        spriteBurstLeft = 0; // nor a re-press burst (an in-flight press just single-fires)
        chaseReset();
        chaseSuppressId = -1;
        chaseGateCells = null;
        pathExtraBlocked = null;
        pinHoldActive = false;      // ditto an open PIN_MOVETO scope
        isLootMode = false;
        routineShootActive = false;
        blockClearActive = false;
        isMineralGather = false;
        mineralTapActive = false;
        pickupAimActive = false;
        broadcast("MINERALGATHER 0");
        routineBroadcastCombat(false); // stop the alts' combat bots
        routineBroadcastBreadcrumb(false); // back to naive auto-follow
        releaseShieldBump(); // drop any shield raised for a bump
        broadcast("SHOOTALT off"); // stop any alt SHOOT firing
        broadcast("PRECISEGATHER off"); // release any alt precision gather
        preciseGatherOn = false;
        snarbyStop(); // clear SNARBY state + drop the alts' sustained shield
        WheelDodge.reset(); // a run's wheel tracks must not survive into the next lobby/floor
        clearMovementKeys();
        lobbyBugSince = 0L; // fresh lobby-join-bug timer for the next floor/mission
    }

    // ── Mission stats logger: extracted to MissionStats.java (same package) ────

    /** Patcher-stubbed entry point (the injected tick calls SIS) — delegates to MissionStats. */
    public static void tickDeathWatch(Object controller) {
        MissionStats.tickDeathWatch(controller);
    }

    private static void routineFinish() {
        routineStopCleanup();
        debugFile("[routine] finished");
        // Terminal = the last non-lobby floor: the numFloors-th one (campaignFloorSeq was bumped
        // when this floor's routine started). Mission-agnostic — no floor NAME is hardcoded.
        boolean terminalDone = (campaignNumFloors > 0 && campaignFloorSeq >= campaignNumFloors);
        if (terminalDone && windowDiag)
            armWindowDump();
        if (terminalDone)
            MissionStats.missionEnd("SUCCESS", campaignLastFloor, null); // boss floor cleared — log the attempt
        if (campaignActive) {
            if (terminalDone) {
                campaignRestartPending = true;
                campaignRestartPhase = 0;
                campaignTownSince = 0L;
                campaignRelaunchAt = 0L;
                debugFile("[campaign] terminal floor complete — cycling: awaiting town to relaunch '"
                        + campaignMissionId + "'");
            } else {
                campaignLoadedSince = 0L; // await the next floor; tickCampaign starts it once the party loads
                debugFile("[campaign] floor '" + campaignLastFloor + "' done; awaiting the next floor");
            }
        }
    }

    private static long combatSpriteNextAt = 0L;
    private static long combatSpriteLastTick = 0L; // last tickCombatSprite call (gap => new fight)
    private static long combatSpriteEnteredAt = 0L; // when the current fight started
    private static final long COMBAT_SPRITE_INTERVAL_MS = 18000L;    // 19s→18s, user-set
    private static final long COMBAT_SPRITE_ENTRY_DELAY_MS = 1000L;  // settle in before the first cast (3s→1s, user-set)
    private static final long COMBAT_SPRITE_ENTRY_GAP_MS = 1000L;    // no call for this long = a new fight

    /** Requests sprite ability 1 from the whole party if the interval has elapsed. */
    private static void tickCombatSprite(long now) {
        if (now - combatSpriteLastTick > COMBAT_SPRITE_ENTRY_GAP_MS)
            combatSpriteEnteredAt = now; // fight just started
        combatSpriteLastTick = now;
        if (now - combatSpriteEnteredAt < COMBAT_SPRITE_ENTRY_DELAY_MS)
            return; // opening seconds: let the party engage first
        if (spriteAimedRequest)
            return; // one pending request at a time — it either fires (re-arming the clock) or the giveup drops it
        if (now < combatSpriteNextAt)
            return;
        // REQUEST, don't press: each client fires on its own next aimed combat pass.
        // (Deliberately NOT the plain "SPRITE 0" message — that is the manual hotkey
        // path and must stay instant and unaimed.) The interval clock is NOT advanced
        // here — see the cast site in tickCombatBot's aimed pass.
        spriteAimedRequest = true;
        spriteAimedSince = now;
        broadcast("SPRITEAIMED 0");
        if (debug)
            debugFile("[routine] sprite ability 1 requested (party)");
    }

    /**
     * Enemy-detection radius² for the CURRENT combat step: the optional 3rd parameter
     * (RANGE, in tiles) squared, else the default {@link #ROUTINE_ENEMY_RADIUS_SQ}.
     * COMBAT / COMBATLOOT / ATTACKMOVE only — SNARBY's exit test and PLATFORM's
     * arena fallback deliberately keep the fixed radius.
     */
    private static float stepEnemyRadiusSq() {
        float r = stepParam(2, -1f);
        return (r > 0f) ? r * r : ROUTINE_ENEMY_RADIUS_SQ;
    }

    /** The current step's idx-th parameter ([0]=X, [1]=Y, extras command-specific), or dflt if absent. */
    private static float stepParam(int idx, float dflt) {
        try {
            float[] p = routineParams[routineStepIdx];
            return (idx < p.length) ? p[idx] : dflt;
        } catch (Exception e) {
            return dflt;
        }
    }

    private static void routineAdvance() {
        routineStepIdx++;
        routineResetStepState();
        if (routineStepIdx >= routineType.length) {
            routineFinish();
            return;
        }
        debugFile("[routine] step " + routineStepIdx + " " + RoutineFile.typeName(routineType[routineStepIdx])
                + " @ (" + Reflect.fmt(routineX[routineStepIdx]) + "," + Reflect.fmt(routineY[routineStepIdx]) + ")");
    }

    private static void routineSetSub(int s) {
        routineSub = s;
        routineSubStart = System.currentTimeMillis();
        routinePath.tgtX = Float.NaN; // force a replan for the next path sub-state
    }

    private static void routineResetStepState() {
        killSawTarget = false; // KILL's seen-latch + absence confirm are per-step
        killAbsentSince = 0L;
        alchSawGhost = false;
        alchAbsentSince = 0L;
        alchNextChargeAt = 0L;
        alchOpenedAt = 0L;
        pinBestDist = Float.MAX_VALUE;
        pinStalledSince = 0L;
        keyTapHoldMs = KEY_TAP_HOLD_DEFAULT_MS; // ALCH_CHARGE's long hold must not leak into the next step's taps
        routineSub = 0;
        routineSubStart = System.currentTimeMillis();
        // Drop the cached walk grid at every step boundary: a step may have opened a
        // route, and the next step should plan against the floor as it is NOW.
        pathGridModel = null;
        routinePath.tgtX = Float.NaN;
        routinePath.x = null;
        hazPath.tgtX = Float.NaN;
        hazPath.x = null;
        hazPath.noPathLogged = false;
        chaseReset();
        chaseSuppressId = -1;
        chaseGateCells = null;
        pathExtraBlocked = null; // paranoia: the overlay must never outlive its drive call
        routineCombatClearSince = 0L;
        routineCombatEndAt = 0L;
        routineWaveCount = 0;
        routineWaveIds = new java.util.HashSet<Integer>();
        routinePath.noPathLogged = false;
        routineShootActive = false;
        mineralGatherPlanned = false;
        isMineralGather = false;
        mineralTapActive = false;
        mineralPickedUp = false;
        mineralNodeCount = 0;
        keyTapFire = false;
        keyLastTapAt = 0L;
        keyInRangeSince = 0L;
        pickupAimActive = false; // stop parking the cursor between steps
        tswStepStart = 0L; // re-arm the TREASURESWEEP step-skip backstop
        isSnarbyActive = false;
        snarbyAltShieldBroadcast = false;
        snarbyBellSelectAt = 0L;
        snarbyBellInRangeSince = 0L;
        snarbyBossDead = false;
        gateSawDoor = false;
        gateDoorBaseline = null;
        gateInRangeSince = 0L;
        gatePinDist = Float.MAX_VALUE;
        gatePinSince = 0L;
        liftPinDist = Float.MAX_VALUE;
        liftPinSince = 0L;
        bswCount = 0;
        bswIdx = 0;
        bswPhase = 0;
        bswPhaseStart = 0L;
        sswCount = 0;
        sswIdx = 0;
        sswPhase = 0;
        sswPhaseStart = 0L;
        blockClearActive = false;
    }

    // ── Auto-queue Blast Network (multibox Ctrl+Q): extracted to PvpAutoQueuer.java ──

    /** Patcher-stubbed entry point (injected tick, gated isAutoPvpQueue) — delegates. */
    public static void tryPvpQueue(Object ctx) {
        PvpAutoQueuer.tryPvpQueue(ctx);
    }

    /** Patcher-stubbed entry point (injected tick, gated isAutoPvpQueue) — delegates. */
    public static void tickPvpAntiIdle(Object ctx) {
        PvpAutoQueuer.tickPvpAntiIdle(ctx);
    }

    /**
     * Called each tick on all accounts — main included. Handles the multibox
     * sprite hotkeys (1/2/3): queues the sprite ability via the dungeon
     * controller's aq(key, slot) and releases it via dU(key) after a short
     * hold, mirroring the game's own SPRITE_ACTION_1..3 key listeners.
     * Without the dU release the queued action would stay in the controller's
     * action queue and repeat taps of the same slot would not re-trigger.
     */
    public static void tryUseSprite(Object controller) {
        if (controller == null) {
            pendingSpriteSlot = -1;
            spriteReleaseKey = -1;
            spriteBurstLeft = 0;
            return;
        }
        if (controller.getClass() != cachedSpriteCtrlClass) {
            cachedSpriteCtrlClass = controller.getClass();
            cachedSpriteAqMethod = null;
            cachedSpriteDuMethod = null;
            spriteReleaseKey = -1; // stale press on a previous controller
            spriteBurstLeft = 0;   // and any burst dies with the old scene
        }
        long now = System.currentTimeMillis();

        // Release phase — dU() the previously pressed key once the hold expires.
        if (spriteReleaseKey != -1) {
            if (now < spriteReleaseAtMs)
                return;
            try {
                java.lang.reflect.Method du = findSpriteReleaseMethod(controller.getClass());
                if (du != null) {
                    du.invoke(controller, spriteReleaseKey);
                }
            } catch (Exception e) {
                if (debug) {
                    debugFile("[sprite] release error: " + e);
                }
            }
            spriteReleaseKey = -1;
        }

        int slot = pendingSpriteSlot;
        if (slot < 0)
            return;
        if (now < spriteNextPressAt)
            return; // burst pacing — the next re-press isn't due yet
        // A burst keeps the slot ARMED (pendingSpriteSlot >= 0, so the injected tick's
        // gate keeps routing here between presses) and re-presses on the cadence; a
        // manual press has no burst and disarms after this one press as before.
        if (spriteBurstLeft > 0)
            spriteBurstLeft--;
        if (spriteBurstLeft <= 0)
            pendingSpriteSlot = -1;
        spriteNextPressAt = now + SPRITE_BURST_INTERVAL_MS;
        try {
            java.lang.reflect.Method aq = findSpriteMethod(controller.getClass());
            if (aq == null) {
                if (debug) {
                    debugFile("[sprite] no aq(int,int) method on " + controller.getClass().getName());
                }
                return;
            }
            int key = SPRITE_KEY_BASE + slot;
            aq.invoke(controller, key, slot);
            spriteReleaseKey = key;
            spriteReleaseAtMs = now + SPRITE_HOLD_MS;
            if (debug) {
                debugFile("[sprite] pressed sprite slot " + slot);
            }
        } catch (Exception e) {
            if (debug) {
                debugFile("[sprite] error: " + e);
            }
        }
    }

    private static java.lang.reflect.Method findSpriteMethod(Class<?> cls) {
        if (cachedSpriteAqMethod != null)
            return cachedSpriteAqMethod;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(MappingsNames.SPRITE_PRESS_METHOD, int.class, int.class);
                if (m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    cachedSpriteAqMethod = m;
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                if (debug)
                    debugFile("sprite action queuer method method not found");
            }
        }
        return null;
    }

    private static java.lang.reflect.Method findSpriteReleaseMethod(Class<?> cls) {
        if (cachedSpriteDuMethod != null)
            return cachedSpriteDuMethod;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(MappingsNames.SPRITE_RELEASE_METHOD, int.class);
                if (m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    cachedSpriteDuMethod = m;
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                if (debug)
                    debugFile("sprite release method not found");
            }
        }
        return null;
    }

    /**
     * Uses a quickbar item by oid via the item service — the same service call
     * QuickbarPanel's slot widgets make when a quickslot key is pressed.
     */
    private static void useItem(Object itemService, java.lang.reflect.Method useMethod,
            Object item, int slot) throws Exception {
        long oid = Mappings.getItemOid(item);
        Class<?> listenerCls = Class.forName(Mappings.CONFIRM_LISTENER_CLASS);
        Object listener = java.lang.reflect.Proxy.newProxyInstance(
                SocketInputState.class.getClassLoader(),
                new Class<?>[] { listenerCls },
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        if (debug) {
                            if (args != null && args.length == 1 && args[0] instanceof String) {
                                debugFile("[consumable] use rejected: " + args[0]);
                            } else {
                                debugFile("[consumable] use confirmed");
                            }
                        }
                        return null;
                    }
                });
        if (debug) {
            debugFile("[consumable] using item slot=" + slot + " oid=" + oid);
        }
        useMethod.invoke(itemService, oid, null, listener);
    }

    /**
     * Finds the item-use service call on the item service: the unique method
     * with signature (long oid, Object arg, ConfirmListener). Cached until the
     * controller class changes.
     */
    private static java.lang.reflect.Method findItemUseMethod(Class<?> serviceClass) {
        if (cachedUseItemMethod != null)
            return cachedUseItemMethod;
        try {
            Class<?> listenerCls = Class.forName(Mappings.CONFIRM_LISTENER_CLASS);
            for (java.lang.reflect.Method m : serviceClass.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == long.class && p[1] == Object.class
                        && p[2] == listenerCls) {
                    cachedUseItemMethod = m;
                    if (debug) {
                        debugFile("[consumable] using item-use service method: " + m.getName());
                    }
                    return m;
                }
            }
        } catch (Exception e) {
            if (debug) {
                debugFile("[consumable] item-use method lookup failed: " + e);
            }
        }
        return null;
    }

    /**
     * Reads the local player's Health entry from PartyObject.health — a
     * DSet.Health; whose entries are keyed by _playerOid (== the
     * PlayerObject's _oid). The names health/current/maximum/_playerOid are
     * unobfuscated data fields, so no name-based method probing is needed.
     */
    private static Object getHealth(Object controller, Object playerObject) {
        try {
            Object partyObj = Reflect.partyObjectOf(controller);
            if (partyObj == null) {
                if (debug) {
                    debugFile("[consumable] no PartyObject on this controller");
                }
                return null;
            }
            Object healthSet = partyObj.getClass().getField("health").get(partyObj);
            if (healthSet == null) {
                if (debug) {
                    debugFile("[consumable] PartyObject.health is null");
                }
                return null;
            }
            int playerOid = Mappings.getPlayerOid(playerObject);
            for (Object entry : (Iterable<?>) healthSet) {
                Integer oid = Reflect.readIntFieldNullable(entry, "_playerOid");
                if (oid != null && oid.intValue() == playerOid) {
                    return entry;
                }
            }
            if (debug) {
                debugFile("[consumable] no health entry for playerOid " + playerOid);
            }
            return null;
        } catch (Exception e) {
            if (debug) {
                debugFile("[consumable] getHealth error: " + e);
            }
            return null;
        }
    }

    // Finds playerObject.dH(int) — the quickbar item getter.
    // Identified as the unique single-int-param method returning Item (not
    // LevelItem).
    private static java.lang.reflect.Method cachedGetQuickbarItemMethod = null;

    private static java.lang.reflect.Method findGetQuickbarItem(Class<?> poClass) {
        if (cachedGetQuickbarItemMethod != null)
            return cachedGetQuickbarItemMethod;
        try {
            String itemClass = "com.threerings.projectx.item.data.Item";
            String levelItemClass = "com.threerings.projectx.item.data.LevelItem";
            for (java.lang.reflect.Method m : poClass.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == int.class) {
                    String ret = m.getReturnType().getName();
                    if (ret.equals(itemClass) && !ret.equals(levelItemClass)) {
                        cachedGetQuickbarItemMethod = m;
                        return m;
                    }
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    // Gets the item's config class name; finds the config method structurally.
    private static java.lang.reflect.Method cachedItemConfigMethod = null;

    private static String getItemConfigClass(Object item, Object configMgr) {
        try {
            if (cachedItemConfigMethod == null) {
                Class<?> cfgMgrClass = Class.forName("com.threerings.config.ConfigManager");
                for (java.lang.reflect.Method m : item.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0].isAssignableFrom(cfgMgrClass)
                            && m.getReturnType().getName().contains("ItemConfig")) {
                        cachedItemConfigMethod = m;
                        break;
                    }
                }
            }
            if (cachedItemConfigMethod == null)
                return null;
            Object cfg = cachedItemConfigMethod.invoke(item, configMgr);
            return cfg == null ? null : cfg.getClass().getName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the item's config reference name, e.g.
     * "Pickup/Capsule/Health Capsule". This is the raw config-name string on
     * Item (returned by ITEM_NAME_METHOD, which Mappings.getItemName wraps).
     */
    private static String getItemConfigRefName(Object item) {
        try {
            return Mappings.getItemName(item);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAllowedKey(String key) {
        return key.equals("W")
                || key.equals("A")
                || key.equals("S")
                || key.equals("D")
                || key.equals("X")
                || key.equals("DODGE")
                || key.equals("MOD1")
                || key.equals("SPACE")
                || key.equals("SHIFT")
                || key.equals("CTRL");
    }

    /**
     * Maps a LOGICAL key name to the code currently bound to it. W/A/S/D name
     * directions (not letters) and X names the shield, so a rebound scheme keeps
     * working: the names are the mod's vocabulary, {@link KeyBinds} supplies the
     * codes. Returns -1 for anything unbound — the injected dispatcher skips those.
     */
    public static int getKeyCode(String key) {
        switch (key) {
            case "W":
                return bindMoveN;
            case "A":
                return bindMoveW;
            case "S":
                return bindMoveS;
            case "D":
                return bindMoveE;
            case "X":
                return bindDefendKey;
            case "DODGE":
                return bindDodgeKey;
            case "MOD1":
            case "SHIFT":
                return bindModifier1;
            case "SPACE": // not tied to a game action; kept for the legacy UDP key vocabulary
                return 32;
            case "CTRL":
                return 341;
            default:
                return -1;
        }
    }

    public static String getKeyName(int code) {
        if (code < 0)
            return null;
        if (code == bindMoveN)
            return "W";
        if (code == bindMoveW)
            return "A";
        if (code == bindMoveS)
            return "S";
        if (code == bindMoveE)
            return "D";
        if (code == bindDefendKey)
            return "X";
        if (code == bindDodgeKey)
            return "DODGE";
        if (code == bindModifier1)
            return "SHIFT";
        switch (code) {
            case 32:
                return "SPACE";
            case 341:
                return "CTRL";
            default:
                return null;
        }
    }
}

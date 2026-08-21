package com.threerings.opengl.gui;

/**
 * Automatic battle-sprite feeder — a per-lobby pass that keeps every character's EQUIPPED
 * sprite fed and leveled until level 100, mirroring the lobby forge pass exactly:
 *
 *   MAIN  — tickCampaign kicks it off once per mission lobby (broadcast "FEEDALL 1" +
 *           startPass), and the lobby hold waits for the main's own pass AND every alt's
 *           "FEEDDONE 1" before the routine descends (same backstop as the forge hold).
 *   ALTS  — the FEEDALL handler latches {@link #feedPassPending}; {@link #tick} (driven
 *           from SocketInputState.tickForgePass's injected-tick call) waits until the alt
 *           is standing in the lobby itself, then runs its pass. The latch survives scene
 *           changes, so an alt that missed a lobby self-heals at the next one.
 *
 * Feeding is only possible in lobbies (game rule), which is also the one place the whole
 * party idles — so the pass runs to completion there and nowhere else.
 *
 * WHAT A PASS DOES (loop, re-reading the sprite from the DSet each cycle):
 *   level >= 100                → done with this sprite (feeding is for leveling).
 *   heat full (_heatProgress≥1) → LEVEL UP with the required item (usually another copy of
 *                                 the bracket food; Evo Catalysts at the gate levels).
 *   appetite tick available     → FEED one bracket food. The server's gate is
 *                                 "now - _lastFull > 12 min" per tick, 5 ticks in the bar
 *                                 (confirmed in play 2026-08-04: feeds can BURST — the
 *                                 12 min is appetite refill per tick, not a per-feed
 *                                 cooldown). Level-up feeds do not consume appetite.
 *   otherwise                   → pass over (appetite spent / out of food).
 *
 * ITEM LOOKUP IS BY CONFIG NAME, EVERY TIME — item oids are not reliable across feeds
 * (user-directed), so each action re-scans PlayerObject.items for the wanted config name
 * and uses whatever oid it carries right now.
 *
 * OBFUSCATION RESILIENCE: sprite state comes from REAL-NAMED fields (_id, _level,
 * _heatProgress, _lastFull on BattleSprite; sprites/equippedSprite on PlayerObject); the
 * service is resolved through the stable BattleSpriteMarshaller class name → its projectx
 * interface → Mappings.getService, and the two calls are matched by SIGNATURE:
 *   FEED     = (long itemOid, int spriteId, ConfirmListener)          [dispatch index 6]
 *   LEVEL UP = (int spriteId, long itemOid, boolean force, ResultListener)  [index 7]
 * (both confirmed in play 2026-08-04 via the sprite-service diagnostic). Nothing here
 * names an obfuscated class or method.
 */
public class SpriteFeeder {

    private SpriteFeeder() {
    }

    // ── Cross-file state (SIS reads/writes these like the forge-pass flags) ────
    /** Alt-side: FEEDALL received; run a pass once this client stands in the lobby. */
    public static volatile boolean feedPassPending = false;
    /** This client's pass thread is active (main-side hold + re-entry guard). */
    private static volatile boolean feedPassRunning = false;
    /** FEEDDONE replies this lobby (main side; UDP thread only). */
    private static volatile int feedDoneAltCount = 0;

    // ── Tunables ───────────────────────────────────────────────────────────────
    static final int MAX_SPRITE_LEVEL = 100;      // stop feeding at this level
    private static final long APPETITE_TICK_MS = 720000L;  // server feed gate: 12 min of refill per tick
    private static final long APPETITE_SLACK_MS = 2000L;   // clock-skew margin before trusting a tick is ready
    private static final long CONFIRM_TIMEOUT_MS = 5000L;  // max wait for a feed/level-up reply
    private static final long STATE_SETTLE_MS = 1500L;     // max wait for the DSet to reflect an action
    private static final int MAX_ACTIONS_PER_PASS = 12;    // 5 appetite feeds + level-ups + slack

    // ── Food tables (user-supplied 2026-08-05) ─────────────────────────────────
    /** Bracket food: what the equipped sprite eats (and levels up with, off gate levels). */
    static String foodFor(int level) {
        if (level <= 14)
            return "Food/Power Mote";
        if (level <= 29)
            return "Food/Power Dust";
        if (level <= 49)
            return "Food/Power Stone";
        if (level <= 74)
            return "Food/Power Orb";
        return "Food/Power Star";
    }

    /** Level-up item at the CURRENT level: catalysts at the gates, bracket food otherwise. */
    static String levelUpItemFor(int level) {
        if (level == 14)
            return "Rarity/Evo Catalyst/Evo Catalyst";
        if (level == 49)
            return "Rarity/Evo Catalyst/Advanced Evo Catalyst";
        if (level == 89 || level == 94 || level == 99)
            return "Rarity/Evo Catalyst/Ultimate Evo Catalyst";
        return foodFor(level);
    }

    // ── Lobby-pass plumbing (mirrors forgePass*) ───────────────────────────────

    /** Main side, once per lobby, right before the FEEDALL broadcast. */
    static void resetLobby() {
        feedDoneAltCount = 0;
    }

    /** UDP FEEDDONE handler (main side). */
    static void noteAltDone() {
        feedDoneAltCount++;
    }

    static boolean running() {
        return feedPassRunning;
    }

    static boolean altsDone(int needed) {
        return feedDoneAltCount >= needed;
    }

    /**
     * Alt-side per-tick driver — piggybacks on the injected tick's existing
     * tickForgePass call, so the Patcher contract is unchanged. Same deferral as the
     * forge pass: hold the latch until this client is really standing in the lobby.
     */
    static void tick() {
        try {
            if (!feedPassPending || feedPassRunning)
                return;
            String fk = SocketInputState.resolveFloorKey();
            if (fk == null || !fk.contains(SocketInputState.CAMPAIGN_LOBBY_KEY))
                return; // not in the mission lobby yet — keep waiting
            feedPassPending = false;
            startPass(SocketInputState._cachedCtx);
        } catch (Exception e) {
            SocketInputState.debugFile("[feed] tick error: " + e);
        }
    }

    /** Runs one pass on its own thread (service calls block on replies, like the forge pass). */
    static void startPass(final Object ctx) {
        if (feedPassRunning || ctx == null)
            return;
        feedPassRunning = true;
        new Thread(() -> {
            try {
                runPass(ctx);
            } catch (Throwable t) {
                SocketInputState.debugFile("[feed] pass error: " + t);
            } finally {
                feedPassRunning = false;
                if (!SocketInputState.isMainAccount())
                    SocketInputState.sendToMain("FEEDDONE 1");
            }
        }, "SK SpriteFeeder").start();
    }

    // ── The pass ───────────────────────────────────────────────────────────────

    private static void runPass(Object ctx) throws Exception {
        Object po = Mappings.getPlayerObject(ctx);
        if (po == null)
            return;
        Object[] svcAndMethods = resolveService(ctx);
        Object svc = svcAndMethods[0];
        java.lang.reflect.Method feedM = (java.lang.reflect.Method) svcAndMethods[1];
        java.lang.reflect.Method lvlM = (java.lang.reflect.Method) svcAndMethods[2];

        int actions = 0;
        while (actions < MAX_ACTIONS_PER_PASS) {
            // Fresh read every cycle: the server REPLACES the DSet entry on every change,
            // so a held reference goes stale the moment a feed lands.
            Object sprite = equippedSprite(po);
            if (sprite == null) {
                SocketInputState.debugFile("[feed] no equipped sprite — nothing to do");
                break;
            }
            Integer id = Reflect.readIntFieldNullable(sprite, "_id");
            Integer level = Reflect.readIntFieldNullable(sprite, "_level");
            Float heat = Reflect.readFloatFieldNullable(sprite, "_heatProgress");
            if (id == null || level == null || heat == null) {
                SocketInputState.debugFile("[feed] sprite fields unreadable — aborting pass");
                break;
            }
            if (level.intValue() >= MAX_SPRITE_LEVEL) {
                SocketInputState.debugFile("[feed] sprite level " + level + " — maxed, done");
                break;
            }

            boolean ok;
            if (heat.floatValue() >= 1.0f) {
                // Heat bar full: level up with the required item. NOTE the arg order —
                // spriteId FIRST here, itemOid first on the feed (easy to get backwards).
                String want = levelUpItemFor(level.intValue());
                Long oid = findItemByConfigName(po, want);
                if (oid == null) {
                    warnOutOf(want, "level-up @" + level);
                    break;
                }
                ok = sendLevelUp(lvlM, svc, id.intValue(), oid.longValue(), level + "->" + (level.intValue() + 1));
            } else {
                // Appetite gate, computed the same way the client's own UI does: one tick
                // per 12 min since _lastFull (null = never stamped = feedable). Slack keeps
                // a small clock skew from firing a doomed request; a refusal
                // (e.not_enough_appetite) is the authoritative backstop either way.
                Long lastFull = Reflect.readLongObjFieldNullable(sprite, "_lastFull");
                long now = System.currentTimeMillis();
                if (lastFull != null && now - lastFull.longValue() < APPETITE_TICK_MS + APPETITE_SLACK_MS) {
                    SocketInputState.debugFile("[feed] appetite spent (lvl " + level + ", heat "
                            + String.format("%.2f", heat) + ") — done until a later lobby");
                    break;
                }
                String want = foodFor(level.intValue());
                Long oid = findItemByConfigName(po, want);
                if (oid == null) {
                    warnOutOf(want, "feed @" + level);
                    break;
                }
                ok = sendFeed(feedM, svc, oid.longValue(), id.intValue(), "lvl " + level);
            }
            if (!ok)
                break; // refusal/timeout: stop the pass — the next lobby retries from live state
            actions++;
            awaitStateChange(po, level, heat);
        }
        if (actions > 0)
            SocketInputState.debugFile("[feed] pass done — " + actions + " action(s)");
    }

    /** After a confirmed action, wait (bounded) for the DSet update so the next read isn't stale. */
    private static void awaitStateChange(Object po, Integer level0, Float heat0) {
        long deadline = System.currentTimeMillis() + STATE_SETTLE_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(100L);
                Object s = equippedSprite(po);
                if (s == null)
                    return;
                Integer lv = Reflect.readIntFieldNullable(s, "_level");
                Float ht = Reflect.readFloatFieldNullable(s, "_heatProgress");
                if (lv != null && !lv.equals(level0))
                    return;
                if (ht != null && ht.floatValue() != heat0.floatValue())
                    return;
                // _lastFull moving alone also counts (a feed at exactly-full heat edge)
            }
        } catch (InterruptedException ignored) {
        }
        // Timeout is fine: a stale re-read at worst sends a request the server refuses,
        // which ends the pass without consuming anything.
    }

    // ── Service plumbing ───────────────────────────────────────────────────────

    /**
     * {svc, feedMethod, levelUpMethod} — marshaller class name is stable; its projectx
     * interface and both method signatures are structural, so no obfuscated names.
     */
    private static Object[] resolveService(Object ctx) throws Exception {
        Class<?> marshaller = Class.forName("com.threerings.projectx.sprites.data.BattleSpriteMarshaller");
        Class<?> iface = null;
        for (Class<?> itf : marshaller.getInterfaces()) {
            if (itf.getName().startsWith("com.threerings.projectx.")) {
                iface = itf;
                break;
            }
        }
        if (iface == null)
            throw new RuntimeException("sprite service interface not found on " + marshaller.getName());
        Object svc = Mappings.getService(Mappings.getClientManager(ctx), iface);
        Class<?> confirmCls = Class.forName(MappingsNames.CONFIRM_LISTENER_CLASS);
        Class<?> resultCls = Class.forName(MappingsNames.RESULT_LISTENER_CLASS);
        java.lang.reflect.Method feedM = null, lvlM = null;
        for (java.lang.reflect.Method m : svc.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == long.class && p[1] == int.class && p[2] == confirmCls)
                feedM = m; // b(itemOid, spriteId, CL) — dispatch 6, the FEED
            else if (p.length == 4 && p[0] == int.class && p[1] == long.class
                    && p[2] == boolean.class && p[3] == resultCls)
                lvlM = m; // a(spriteId, itemOid, force, RL) — dispatch 7, the LEVEL UP
        }
        if (feedM == null || lvlM == null)
            throw new RuntimeException("sprite feed/level-up methods not found on " + svc.getClass().getName());
        return new Object[] { svc, feedM, lvlM };
    }

    private static boolean sendFeed(java.lang.reflect.Method feedM, Object svc, long itemOid,
            int spriteId, String what) throws Exception {
        boolean[] done = new boolean[1];
        String[] fail = new String[1];
        feedM.invoke(svc, Long.valueOf(itemOid), Integer.valueOf(spriteId), listenerProxy(feedM, done, fail));
        return await(done, fail, "feed (" + what + ")");
    }

    private static boolean sendLevelUp(java.lang.reflect.Method lvlM, Object svc, int spriteId,
            long itemOid, String what) throws Exception {
        boolean[] done = new boolean[1];
        String[] fail = new String[1];
        lvlM.invoke(svc, Integer.valueOf(spriteId), Long.valueOf(itemOid), Boolean.FALSE,
                listenerProxy(lvlM, done, fail));
        SocketInputState.displayChat("[feed] sprite level up " + what, "feedback");
        return await(done, fail, "level-up (" + what + ")");
    }

    /**
     * Listener proxy for the method's own listener parameter type (ConfirmListener or
     * ResultListener — both use "1 String param = failure, anything else = success").
     * Object methods are answered locally so a stray hashCode/toString can't complete it.
     */
    private static Object listenerProxy(java.lang.reflect.Method svcMethod, final boolean[] done,
            final String[] fail) {
        Class<?>[] params = svcMethod.getParameterTypes();
        Class<?> listenerCls = params[params.length - 1];
        return java.lang.reflect.Proxy.newProxyInstance(
                SpriteFeeder.class.getClassLoader(),
                new Class<?>[] { listenerCls },
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName()))
                                return Integer.valueOf(System.identityHashCode(proxy));
                            if ("equals".equals(method.getName()))
                                return Boolean.valueOf(proxy == args[0]);
                            return "SpriteFeederListener";
                        }
                        Class<?>[] p = method.getParameterTypes();
                        if (p.length == 1 && p[0] == String.class && args != null && args[0] != null)
                            fail[0] = String.valueOf(args[0]);
                        done[0] = true;
                        return null;
                    }
                });
    }

    private static boolean await(boolean[] done, String[] fail, String what) {
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        try {
            while (!done[0] && System.currentTimeMillis() < deadline)
                Thread.sleep(50L);
        } catch (InterruptedException ignored) {
        }
        if (!done[0]) {
            SocketInputState.debugFile("[feed] " + what + " timed out");
            return false;
        }
        if (fail[0] != null) {
            // e.not_enough_appetite here just means our clock ran slightly ahead of the
            // server's — normal end of a burst, not an error worth chat.
            SocketInputState.debugFile("[feed] " + what + " refused: " + fail[0]);
            return false;
        }
        return true;
    }

    // ── Player-object reading ──────────────────────────────────────────────────

    /** The equipped BattleSprite entry (matched by _id == PlayerObject.equippedSprite), or null. */
    private static Object equippedSprite(Object po) {
        try {
            int equippedId = po.getClass().getField("equippedSprite").getInt(po);
            if (equippedId <= 0)
                return null;
            Object dset = po.getClass().getField("sprites").get(po);
            if (!(dset instanceof Iterable))
                return null;
            for (Object s : (Iterable<?>) dset) {
                Integer id = Reflect.readIntFieldNullable(s, "_id");
                if (id != null && id.intValue() == equippedId)
                    return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Oid of the first inventory item whose CONFIG NAME equals {@code configName} —
     * re-scanned on every action because oids are not reliable across feeds. Exact match:
     * "Rarity/Evo Catalyst/Evo Catalyst" is a substring of the Advanced/Ultimate names,
     * so anything looser feeds the wrong catalyst.
     */
    private static Long findItemByConfigName(Object po, String configName) {
        try {
            for (Object item : Mappings.getPlayerItems(po)) {
                if (item == null)
                    continue;
                try {
                    String name = (String) item.getClass().getMethod(MappingsNames.ITEM_NAME_METHOD).invoke(item);
                    if (configName.equals(name))
                        return (Long) item.getClass().getMethod(MappingsNames.ITEM_OID_METHOD).invoke(item);
                } catch (Exception skip) {
                    // items without name/oid accessors (non-Item entries) — skip
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // One chat warning per missing item name per session; debug.log gets every miss.
    private static final java.util.Set<String> warnedOut = java.util.Collections
            .synchronizedSet(new java.util.HashSet<String>());

    private static void warnOutOf(String configName, String context) {
        SocketInputState.debugFile("[feed] out of '" + configName + "' (" + context + ")");
        if (warnedOut.add(configName))
            SocketInputState.displayChat("[feed] out of " + configName + " — sprite feeding paused", "attention");
    }

}

package com.threerings.opengl.gui;

/**
 * Blast Network auto-queue (multibox Ctrl+Q) — the full queue/suicide-feed/anti-idle
 * machinery, extracted from SocketInputState (same package; leans on its package-private
 * helpers). The injected-code CONTRACT stays on SIS: the toggle field {@code isAutoPvpQueue}
 * and the anti-idle key fields {@code antiIdleKeyCode}/{@code antiIdleUntil} (the patched
 * InputState.poll reads them directly), plus one-line delegates for the two tick entry
 * points ({@code tryPvpQueue}, {@code tickPvpAntiIdle}) so the Patcher stubs are unchanged.
 * The PVPBOOST UDP handler in SIS records into this class via {@link #recordBoost}.
 */
public class PvpAutoQueuer {

    private static volatile long pvpNextActionAt = 0L;
    private static volatile long pvpJoinCooldownUntil = 0L;
    private static volatile long pvpReturnCooldownUntil = 0L;
    private static final long PVP_TICK_INTERVAL_MS = 1500L;
    private static final long PVP_JOIN_COOLDOWN_MS = 4000L;
    private static final long PVP_RETURN_COOLDOWN_MS = 5000L;
    // Preferred Blast Network modes, in order; the first available in
    // PvpKey.bCL is queued (falling back to the first Blast Network key if none
    // match). Names are the unobfuscated PvpGame$Mode enum values. Blast Network
    // supports FREE_FOR_ALL, RANDOM_TEAM, and GUILD_TEAM.
    private static final String[] PVP_MODE_PREFERENCE = { "RANDOM_TEAM" };
    private static Class<?> cachedPvpServiceClass = null;
    private static java.lang.reflect.Method cachedPvpJoinMethod = null;

    // Krogmo Coin booster status of every character, keyed by playerOid, shared
    // via PVPBOOST broadcasts. Lets each client identify the "win-rigged" team
    // (both members boosted) and throw the match for the opposite team.
    private static final java.util.Map<Integer, Boolean> pvpBoostMap = new java.util.concurrent.ConcurrentHashMap<>();

    // PvP suicide-feed: once a Blast Network match reaches PLAYING, each account
    // on the losing team (opposite the win-rigged/boosted pair, or opposite the
    // main when no win-rigged team exists) places a bomb (right-click) after a
    // random 0-1s delay, waits for it to detonate (killing them and scoring for
    // the winning team), then quits to the ready room. The bomb reuses the
    // weapon-fire path (pendingWeaponPress → hold → pendingWeaponRelease).
    // Phases: 0 arm, 1 place bomb (press), 2 release, 3 wait then quit, 4 done.
    private static volatile int pvpSuicidePhase = 0;
    private static volatile long pvpAttackAt = 0L;
    private static volatile long pvpReleaseAt = 0L;
    private static volatile long pvpQuitAt = 0L;
    private static final long PVP_SUICIDE_MAX_DELAY_MS = 1000L;
    private static final long PVP_ATTACK_HOLD_MS = 150L;
    private static final long PVP_SUICIDE_WAIT_MS = 4300L;

    // Anti-idle: tickPvpAntiIdle arms SIS.antiIdleKeyCode/-Until on the match-start
    // edge; the patched InputState.poll dispatches the held key (fields on SIS).
    private static volatile boolean prevPvpPlaying = false;
    private static final long ANTI_IDLE_MIN_MS = 250L;
    private static final long ANTI_IDLE_MAX_MS = 600L;

    /** Records a PVPBOOST broadcast (SIS UDP handler calls this). */
    static void recordBoost(int playerOid, boolean boosted) {
        pvpBoostMap.put(playerOid, boosted);
    }

    /**
     * Called each tick on all accounts — main included — while auto-queue is on.
     * Two parts:
     * <ol>
     * <li>Suicide-feed orchestration (every tick, timer-sensitive): opposite-team
     * accounts bomb and quit — see {@link #handlePvpSuicide}.</li>
     * <li>Queue/return actions (throttled to PVP_TICK_INTERVAL_MS): in a match
     * that has ended (GAME_OVER/POST_GAME) → return to the ready room; in an
     * active match → wait; idle and not signed up → join the Blast Network
     * queue.</li>
     * </ol>
     * Reads PlayerObject.pvpSignup / pvpGame and ArenaPartyObject.state — all
     * unobfuscated fields.
     */
    static void tryPvpQueue(Object ctx) {
        if (ctx == null)
            return;
        long now = System.currentTimeMillis();
        try {
            Object po = Mappings.getPlayerObject(ctx);
            if (po == null)
                return;

            Object arena = getArenaParty(ctx);
            String stateName = null;
            if (arena != null) {
                Object state = Reflect.readObjectFieldNullable(arena, "state");
                if (state instanceof Enum) {
                    stateName = ((Enum<?>) state).name();
                }
            }

            // Timer-sensitive: check every tick, independent of the queue throttle.
            handlePvpSuicide(ctx, po, arena, stateName, now);

            if (now < pvpNextActionAt)
                return;
            pvpNextActionAt = now + PVP_TICK_INTERVAL_MS;

            // Announce this character's Krogmo Coin booster status so every
            // client can determine the win-rigged team. Also record it locally.
            int myOid = Mappings.getPlayerOid(po);
            if (myOid > 0) {
                boolean boosted = getPvpTokenBonus(po) > 0;
                pvpBoostMap.put(myOid, boosted);
                SocketInputState.broadcastAll("PVPBOOST " + myOid + " " + (boosted ? 1 : 0));
            }

            Object pvpGame = Reflect.readObjectFieldNullable(po, "pvpGame");
            Object pvpSignup = Reflect.readObjectFieldNullable(po, "pvpSignup");
            boolean matchEnded = "GAME_OVER".equals(stateName) || "POST_GAME".equals(stateName);
            boolean inActiveMatch = pvpGame != null
                    || "PRE_GAME".equals(stateName) || "PRE_COUNTDOWN".equals(stateName)
                    || "COUNTDOWN".equals(stateName) || "PLAYING".equals(stateName);

            if (matchEnded) {
                if (now >= pvpReturnCooldownUntil) {
                    pvpReturnCooldownUntil = now + PVP_RETURN_COOLDOWN_MS;
                    returnToReadyRoom(ctx);
                }
                return;
            }
            if (inActiveMatch)
                return;

            // Idle in the ready room. Re-queue if not already signed up.
            if (pvpSignup == null && now >= pvpJoinCooldownUntil) {
                pvpJoinCooldownUntil = now + PVP_JOIN_COOLDOWN_MS;
                joinBlastNetwork(ctx);
            }
        } catch (Exception e) {
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpqueue] error: " + e);
            }
        }
    }

    /**
     * Drives the suicide-feed state machine for this account. Only accounts on
     * the losing team act (see {@link #isOnLosingTeam}); the winning team does
     * nothing here. The losing team is the one opposite the win-rigged (fully
     * boosted) pair, or — when no team is fully boosted — the team opposite the
     * main. Any account can lose, main included. Runs only while in a Blast
     * Network match at PLAYING; resets to idle otherwise so the next match
     * re-arms. The bomb reuses the weapon-fire path (pendingWeaponPress, held
     * briefly, then pendingWeaponRelease — consumed in the patched
     * InputState.poll); the quit uses ctx.Gw().IT().
     */
    private static void handlePvpSuicide(Object ctx, Object po, Object arena, String stateName, long now) {
        boolean inPlaying = arena != null && "PLAYING".equals(stateName) && isBlastNetworkMatch(arena);
        if (!inPlaying) {
            pvpSuicidePhase = 0;
            return;
        }

        if (!isOnLosingTeam(arena, po))
            return;

        switch (pvpSuicidePhase) {
            case 0:
                pvpAttackAt = now + (long) (Math.random() * PVP_SUICIDE_MAX_DELAY_MS);
                pvpSuicidePhase = 1;
                if (SocketInputState.debug) {
                    SocketInputState.debugFile("[pvpsuicide] opposite-team match live; bombing in " + (pvpAttackAt - now) + "ms");
                }
                break;
            case 1:
                if (now >= pvpAttackAt) {
                    // Reuse the weapon-fire mechanic: press the attack (drops a
                    // bomb at the pawn), released a moment later in phase 2.
                    SocketInputState.pendingFireAngle = 0f;
                    SocketInputState.pendingWeaponPress = true;
                    pvpReleaseAt = now + PVP_ATTACK_HOLD_MS;
                    pvpQuitAt = now + PVP_SUICIDE_WAIT_MS;
                    pvpSuicidePhase = 2;
                    if (SocketInputState.debug) {
                        SocketInputState.debugFile("[pvpsuicide] bomb placed; quitting in " + PVP_SUICIDE_WAIT_MS + "ms");
                    }
                }
                break;
            case 2:
                if (now >= pvpReleaseAt) {
                    SocketInputState.pendingWeaponRelease = true;
                    pvpSuicidePhase = 3;
                }
                break;
            case 3:
                if (now >= pvpQuitAt) {
                    returnToReadyRoom(ctx);
                    pvpSuicidePhase = 4;
                    if (SocketInputState.debug) {
                        SocketInputState.debugFile("[pvpsuicide] quitting to ready room");
                    }
                }
                break;
            default:
                break; // phase 4: done — wait for the arena to tear down (resets above)
        }
    }

    /**
     * Called each tick on every account (from the patched TudeyController tick)
     * while auto-queue is on. On the transition into a live Blast Network match
     * (GameState PLAYING) it arms a random WASD key for a brief random hold; the
     * patched InputState.poll dispatches that key (via SIS.antiIdleKeyCode/-Until)
     * so the character moves and the server doesn't kick it for idling. Re-arms on
     * each new match. Runs on both the main and the alts (winning-team alts play
     * the match out and would otherwise idle-kick just like the main).
     */
    static void tickPvpAntiIdle(Object ctx) {
        try {
            Object arena = getArenaParty(ctx);
            String stateName = null;
            if (arena != null) {
                Object state = Reflect.readObjectFieldNullable(arena, "state");
                if (state instanceof Enum) {
                    stateName = ((Enum<?>) state).name();
                }
            }
            boolean playing = arena != null && "PLAYING".equals(stateName) && isBlastNetworkMatch(arena);
            if (playing && !prevPvpPlaying) {
                long now = System.currentTimeMillis();
                // A random movement key from the LIVE scheme (KeyBinds), not a
                // hardcoded WASD — a rebound layout must still jiggle the character.
                int[] moveKeys = { SocketInputState.bindMoveN, SocketInputState.bindMoveW,
                        SocketInputState.bindMoveS, SocketInputState.bindMoveE };
                SocketInputState.antiIdleKeyCode = moveKeys[new java.util.Random().nextInt(moveKeys.length)];
                SocketInputState.antiIdleUntil = now + ANTI_IDLE_MIN_MS
                        + (long) (Math.random() * (ANTI_IDLE_MAX_MS - ANTI_IDLE_MIN_MS));
                if (SocketInputState.debug) {
                    SocketInputState.debugFile("[pvpidle] match start; holding key " + SocketInputState.antiIdleKeyCode
                            + " for " + (SocketInputState.antiIdleUntil - now) + "ms");
                }
            }
            prevPvpPlaying = playing;
        } catch (Exception e) {
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpidle] error: " + e);
            }
        }
    }

    /** Reads PlayerObject.pvpTokenBonus — the count of active Krogmo Coin boosters (0-6). */
    private static int getPvpTokenBonus(Object po) {
        Integer v = Reflect.readIntFieldNullable(po, "pvpTokenBonus");
        return v == null ? 0 : v.intValue();
    }

    /**
     * True if this account is on the team that should throw the match.
     *
     * <p>Primary rule (win-rig): if exactly one team has <em>all</em> its members
     * boosted with Krogmo Coin boosters (both of a 2v2 pair), that team is the
     * winner and the opposite team throws. This happens whenever ≥3 of the 4
     * characters are boosted (the odd one out lands with a booster on the losing
     * team), or whenever 2 boosters happen to be paired.
     *
     * <p>Fallback: if no single team is fully boosted (fewer than 3 boosted, or
     * an ambiguous all-boosted split), the winner is the main's team — the
     * original behavior.
     *
     * <p>Returns false (do nothing / wait) if team or booster data is
     * incomplete, so a missing PVPBOOST broadcast never causes a mis-throw.
     */
    private static boolean isOnLosingTeam(Object arena, Object po) {
        int myTeam = teamForOid(arena, Mappings.getPlayerOid(po));
        if (myTeam != 0 && myTeam != 1)
            return false;

        int[] total = new int[2];
        int[] boosted = new int[2];
        try {
            Object teams = Reflect.readObjectFieldNullable(arena, "teams");
            if (teams == null)
                return false;
            for (Object ta : (Iterable<?>) teams) {
                Integer oid = Reflect.readIntFieldNullable(ta, "_playerOid");
                Integer team = Reflect.readIntFieldNullable(ta, "teamId");
                if (oid == null || team == null || (team != 0 && team != 1))
                    continue;
                Boolean b = pvpBoostMap.get(oid.intValue());
                if (b == null)
                    return false; // booster status not yet known — wait
                total[team]++;
                if (b.booleanValue())
                    boosted[team]++;
            }
        } catch (Exception e) {
            return false;
        }
        // Need a fully-populated 2v2 before deciding.
        if (total[0] < 2 || total[1] < 2)
            return false;

        // Win-rigged team = the unique team whose members are all boosted.
        int winTeam = -1;
        for (int t = 0; t < 2; t++) {
            if (boosted[t] == total[t]) {
                winTeam = (winTeam == -1) ? t : -2; // -2 = both teams fully boosted (ambiguous)
            }
        }

        if (winTeam < 0) {
            // No unique win-rigged team → fall back to "main's team wins".
            int mainOid = oidForName(arena, SKConfig.MAIN_ACCOUNT);
            if (mainOid < 0)
                return false;
            int mainTeam = teamForOid(arena, mainOid);
            if (mainTeam != 0 && mainTeam != 1)
                return false;
            winTeam = mainTeam;
        }

        return myTeam != winTeam;
    }

    /** teamId for a player oid from ArenaPartyObject.teams (DSet&lt;TeamAssignment&gt;), or -1. */
    private static int teamForOid(Object arena, int oid) {
        try {
            Object teams = Reflect.readObjectFieldNullable(arena, "teams");
            if (teams == null)
                return -1;
            for (Object ta : (Iterable<?>) teams) {
                Integer taOid = Reflect.readIntFieldNullable(ta, "_playerOid");
                if (taOid != null && taOid.intValue() == oid) {
                    Integer team = Reflect.readIntFieldNullable(ta, "teamId");
                    return team != null ? team.intValue() : -1;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** playerOid of the party member with the given knight name (PartyObject.members), or -1. */
    private static int oidForName(Object arena, String name) {
        if (name == null || name.isEmpty())
            return -1;
        try {
            Object members = Reflect.readObjectFieldNullable(arena, "members");
            if (members == null)
                return -1;
            for (Object m : (Iterable<?>) members) {
                Object n = Reflect.readObjectFieldNullable(m, "name");
                if (n != null && name.equals(n.toString())) {
                    Integer oid = Reflect.readIntFieldNullable(m, "_playerOid");
                    return oid != null ? oid.intValue() : -1;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** True if the arena's PvpKey game is Blast Network. */
    private static boolean isBlastNetworkMatch(Object arena) {
        try {
            Object key = Reflect.readObjectFieldNullable(arena, "key");
            if (key == null)
                return false;
            Object game = Reflect.readObjectFieldNullable(key, "_game");
            if (game == null)
                return false;
            Class<?> pvpGameCls = Class.forName("com.threerings.projectx.pvp.data.PvpGame");
            return game == pvpGameCls.getField("BLAST_NETWORK").get(null);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the local ArenaPartyObject (the active PvP match party) or null if
     * not in an arena. Mirrors Mappings.getStageHeatPool: ctx.GD() → dungeon
     * director S, then S.bY(ArenaPartyObject.class).
     */
    private static Object getArenaParty(Object ctx) {
        try {
            Object dungeonDir = null;
            for (java.lang.reflect.Method m : ctx.getClass().getMethods()) {
                if (m.getParameterCount() == 0 &&
                        m.getReturnType().getName().startsWith("com.threerings.projectx.dungeon.client.")) {
                    dungeonDir = m.invoke(ctx);
                    break;
                }
            }
            if (dungeonDir == null)
                return null;
            Class<?> apoClass = Class.forName("com.threerings.projectx.dungeon.arena.data.ArenaPartyObject");
            for (java.lang.reflect.Method m : dungeonDir.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Class.class) {
                    Object o = m.invoke(dungeonDir, apoClass);
                    return apoClass.isInstance(o) ? o : null;
                }
            }
        } catch (Exception e) {
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpqueue] getArenaParty error: " + e);
            }
        }
        return null;
    }

    /**
     * Enters the Blast Network queue via the PvP invocation service. The service
     * interface is found robustly through PvpMarshaller's interfaces (readable
     * class name); the join method is matched structurally as the unique
     * (PvpKey, int, ResultListener) call.
     */
    private static void joinBlastNetwork(Object ctx) {
        try {
            Object svc = findPvpService(ctx);
            if (svc == null) {
                if (SocketInputState.debug) {
                    SocketInputState.debugFile("[pvpqueue] no pvp service");
                }
                return;
            }
            if (cachedPvpJoinMethod == null || svc.getClass() != cachedPvpServiceClass) {
                cachedPvpServiceClass = svc.getClass();
                cachedPvpJoinMethod = findPvpJoinMethod(svc.getClass());
            }
            if (cachedPvpJoinMethod == null) {
                if (SocketInputState.debug) {
                    SocketInputState.debugFile("[pvpqueue] no pvp join method");
                }
                return;
            }
            Object key = buildBlastNetworkKey();
            if (key == null) {
                if (SocketInputState.debug) {
                    SocketInputState.debugFile("[pvpqueue] no Blast Network PvpKey available");
                }
                return;
            }
            boolean[] done = new boolean[] { false };
            Object listener = Mappings.createResultListenerProxy(done);
            cachedPvpJoinMethod.invoke(svc, key, 0, listener);
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpqueue] joining Blast Network queue: " + key);
            }
        } catch (Exception e) {
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpqueue] join error: " + e);
            }
        }
    }

    /** Resolves the obfuscated PvP client service via PvpMarshaller's interfaces. */
    private static Object findPvpService(Object ctx) {
        try {
            Class<?> marshaller = Class.forName("com.threerings.projectx.pvp.data.PvpMarshaller");
            Class<?> svcIface = null;
            for (Class<?> i : marshaller.getInterfaces()) {
                if (i.getName().startsWith("com.threerings.projectx.pvp.client")) {
                    svcIface = i;
                    break;
                }
            }
            if (svcIface == null)
                return null;
            return Mappings.getService(Mappings.getClientManager(ctx), svcIface);
        } catch (Exception e) {
            return null;
        }
    }

    /** Finds the join-queue call: unique (PvpKey, int, ResultListener) method. */
    private static java.lang.reflect.Method findPvpJoinMethod(Class<?> serviceClass) {
        for (java.lang.reflect.Method m : serviceClass.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0].getName().endsWith(".PvpKey") && p[1] == int.class
                    && p[2].getName().equals(Mappings.RESULT_LISTENER_CLASS)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Builds a Blast Network PvpKey by scanning the master key list for entries
     * whose _game is PvpGame.BLAST_NETWORK, preferring modes in
     * PVP_MODE_PREFERENCE order. The master list is PvpKey's only public static
     * collection field (ImmutableList&lt;PvpKey&gt;) — found by that shape, not
     * by its obfuscated name (bCL → bCk in the 2026-07-30 re-obfuscation, which
     * silently killed the queue while it was still a getField hardcode).
     */
    private static Object buildBlastNetworkKey() {
        try {
            Class<?> pvpGameCls = Class.forName("com.threerings.projectx.pvp.data.PvpGame");
            Object blast = pvpGameCls.getField("BLAST_NETWORK").get(null);
            Class<?> pvpKeyCls = Class.forName("com.threerings.projectx.pvp.data.PvpKey");
            Iterable<?> all = findPvpKeyMasterList(pvpKeyCls);
            if (all == null) {
                if (SocketInputState.debug) {
                    SocketInputState.debugFile("[pvpqueue] no PvpKey master-list field found");
                }
                return null;
            }

            java.util.List<Object> blastKeys = new java.util.ArrayList<>();
            for (Object k : all) {
                if (Reflect.readObjectFieldNullable(k, "_game") == blast) {
                    blastKeys.add(k);
                }
            }
            if (blastKeys.isEmpty())
                return null;

            for (String want : PVP_MODE_PREFERENCE) {
                for (Object k : blastKeys) {
                    Object mode = Reflect.readObjectFieldNullable(k, "_mode");
                    if (mode instanceof Enum && want.equals(((Enum<?>) mode).name())) {
                        return k;
                    }
                }
            }
            return blastKeys.get(0);
        } catch (Exception e) {
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpqueue] buildBlastNetworkKey error: " + e);
            }
            return null;
        }
    }

    /**
     * The master list of all valid PvpKeys: the unique public static Iterable
     * field on PvpKey whose elements are PvpKey instances. Survives
     * re-obfuscation — the field NAME is volatile but its static-Iterable-of-
     * PvpKey shape is not.
     */
    private static Iterable<?> findPvpKeyMasterList(Class<?> pvpKeyCls) {
        for (java.lang.reflect.Field f : pvpKeyCls.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    || !Iterable.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                Iterable<?> it = (Iterable<?>) f.get(null);
                if (it == null) {
                    continue;
                }
                // Generics are erased; verify by contents instead.
                java.util.Iterator<?> iter = it.iterator();
                if (iter.hasNext() && pvpKeyCls.isInstance(iter.next())) {
                    return it;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Silently returns to the ready room via ctx.Gw().IT() — the same call the
     * game's "/rr" command runs after its confirm dialog, invoked directly to
     * skip the dialog. Gw is found structurally (ctx method returning the
     * ready-room director dB); IT is an obfuscated method name that may change
     * between game versions (like aq/dU). PvP-only now — the mission cycle's
     * ready-room porting was removed when the invite-based lobby fill landed.
     */
    private static void returnToReadyRoom(Object ctx) {
        try {
            Object dir = null;
            for (java.lang.reflect.Method m : ctx.getClass().getMethods()) {
                if (m.getParameterCount() == 0 &&
                        m.getReturnType().getName().equals(MappingsNames.READY_ROOM_DIRECTOR_CLASS)) {
                    dir = m.invoke(ctx);
                    break;
                }
            }
            if (dir == null) {
                if (SocketInputState.debug) {
                    SocketInputState.debugFile("[pvpqueue] no ready-room director");
                }
                return;
            }
            dir.getClass().getMethod(MappingsNames.READY_ROOM_RETURN_METHOD).invoke(dir);
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpqueue] returning to ready room");
            }
        } catch (Exception e) {
            if (SocketInputState.debug) {
                SocketInputState.debugFile("[pvpqueue] return error: " + e);
            }
        }
    }
}

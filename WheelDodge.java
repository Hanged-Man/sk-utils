package com.threerings.opengl.gui;

import java.util.Locale;

/**
 * Siege-wheel dodge reflex — extracted verbatim from SocketInputState (2026-08-05,
 * decomposition phase 2). ZERO Patcher pins: nothing here is referenced by injected
 * bytecode. Entry points: SocketInputState.crumbFollowTarget (alt follow) and
 * driveAlongPath (main path driver) call updateWheelTracks/wheelHold/inWheelDanger/
 * wheelHoldCenterNudge each movement tick; routineStopCleanup calls reset().
 */
final class WheelDodge {

    private WheelDodge() {
    }

    // ── Siege-wheel dodge ────────────────────────────────────────────────────
    //
    // `Bullet | Dynamic/Traps and Hazards/Siege Wheel/Parts/Wheel`: 3x1 wheels that
    // roll fixed lanes at fixed speeds, lethal on their front AND back faces but
    // harmless from the side. They can't be pathed around — they move — so this is a
    // MOVEMENT REFLEX layered on top of pathing, not a grid change.
    //
    // The rule is LANE-ENTRY GATING: never step INTO a lane a wheel is about to sweep;
    // if we are somehow already inside one, keep moving and get out. Freezing inside a
    // lane is the one thing that reliably kills, so the gate only ever holds a
    // character that is currently SAFE.
    //
    // Velocity is measured empirically (finite difference between ticks) rather than
    // read from the actor: the wheels are Bullets, their speed is a per-dungeon
    // constant we don't know, and the measurement is self-correcting.
    // Match the ROLLING WHEEL ONLY: `Bullet | Dynamic/Traps and Hazards/Siege Wheel/Parts/Wheel`.
    // A bare "siege wheel" also catches `Monster | .../Siege Wheel/Wheel Launcher`, and a
    // launcher never moves — tracking one gave it a permanent danger bubble that held the
    // party forever wherever a route passed near it.
    private static final String WHEEL_CONFIG = "siege wheel/parts/wheel";
    // A wheel is 3x1: THREE TILES LONG ALONG ITS TRAVEL, one tile wide — it rolls down
    // 1-tile lanes. So the feared strip is NARROW across and LONG fore/aft. Getting
    // this backwards is not merely over-cautious: a half-width wide enough to cover the
    // neighbouring corridor makes a character standing safely beside a lane test as
    // "already in danger", and wheelHold then switches itself OFF (it refuses to freeze
    // anyone inside a lane) precisely when it is needed.
    private static final float WHEEL_LANE_HALF_W = 0.7f;    // half a tile of body + clearance, still < 1 so adjacent lanes stay usable
    // How far ahead along its travel a wheel is feared. Floor is the time to CLEAR the
    // lane from the hold point: (STEP_AHEAD + lane + clearance) / char speed + latency
    // ≈ 2.5/5 + 0.1 ≈ 0.6s at 5 tiles/s. 0.8 keeps ~33% margin, which the carry case
    // needs (hauling a key/statue is well under 5 tiles/s). Too LONG is the worse
    // failure: feared distance is speed*L + 1.5, and once that exceeds the gap between
    // consecutive wheels no crossing window ever opens and the run idles into an abort.
    private static final long WHEEL_LOOKAHEAD_MS = 1250L;    // 1.4s → 1.0s → 0.8s, user-tuned
    private static final float WHEEL_BODY_HALF_LEN = 1.5f;  // half of the 3-tile length: how far the faces sit from its centre
    private static final float WHEEL_BACK_MARGIN = 1.8f;    // body half-length + clearance; the BACK face kills too
    private static final float WHEEL_PARKED_RADIUS = 1.7f;  // parked/unmeasured wheel: fear its whole body
    private static final long WHEEL_HOLD_MAX_MS = 3000L;    // never wait longer than this for a lane to clear
    private static final long WHEEL_OVERRIDE_MS = 2000L;    // post-backstop: gate stays OFF this long (enough to cross, ~10 tiles of travel)
    private static final float WHEEL_LAUNCHER_ENGAGE_SQ = 36f; // (6 tiles)^2 — no holds this close to a LIVE launcher (it's a kill target)
    /** Live launcher positions (Monster | .../Siege Wheel/Wheel Launcher), refreshed with the tracks. */
    private static final java.util.ArrayList<float[]> wheelLaunchers = new java.util.ArrayList<float[]>();
    private static final float WHEEL_STEP_AHEAD = 1.0f;     // probe this far along our heading to decide "entering"
    private static final float WHEEL_MIN_SPEED = 0.05f;     // tiles/s below which a wheel counts as parked
    private static final long WHEEL_MOTIONLESS_DROP_MS = 1500L; // a "wheel" that hasn't moved this long is dead data, not a hazard
    /** actorId -> {x, y, vx, vy, lastSeenMs} in world units and tiles/second. */
    private static final java.util.HashMap<Integer, float[]> wheelTracks = new java.util.HashMap<Integer, float[]>();

    /**
     * Re-measures every siege wheel's position and velocity. Cheap enough to run each
     * tick; entries not refreshed within WHEEL_TRACK_STALE_MS are dropped so a
     * destroyed or off-scene wheel stops influencing movement.
     */
    private static Object wheelTrackView = null; // the view the tracks belong to — scene change wipes everything
    /** Ids dropped as motionless zombies, with the position they froze at (resurrect only if it MOVES). */
    private static final java.util.HashMap<Integer, float[]> wheelDeadIds = new java.util.HashMap<Integer, float[]>();

    static void updateWheelTracks(Object view, long now) {
        try {
            // Scene change = a different view object: every old track is garbage (actor
            // ids are per-scene and even restart from the same range). This is what let
            // "51 wheel(s) tracked" haunt the MISSION LOBBY: the alts read the previous
            // dungeon's FROZEN view through a stale dungeonClient, and frozen actors
            // are re-seen every pass, so a last-seen timestamp never expires.
            if (view != wheelTrackView) {
                wheelTrackView = view;
                wheelTracks.clear();
                wheelLaunchers.clear();
                wheelDeadIds.clear();
            }
            Object actorMap = Reflect.readObjectFieldNullable(view, MappingsNames.ACTOR_MAP_FIELD);
            if (actorMap == null)
                return;
            Object values = actorMap.getClass().getMethod("values").invoke(actorMap);
            wheelLaunchers.clear(); // refreshed every pass: a destroyed launcher must stop yielding the gate
            java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
            for (Object w : (Iterable<?>) values) {
                Object a = Reflect.getWrappedActor(w);
                if (a == null)
                    continue;
                String nm = Reflect.actorConfigName(a).toLowerCase(Locale.ROOT);
                if (nm.contains("wheel launcher")) {
                    float[] lp = Reflect.actorPos(a);
                    if (lp != null)
                        wheelLaunchers.add(lp);
                    continue;
                }
                if (!nm.contains(WHEEL_CONFIG))
                    continue;
                float[] p = Reflect.actorPos(a);
                Integer id = Reflect.readIntFieldNullable(a, "_id");
                if (p == null || id == null)
                    continue;
                // Dead-listed id (dropped as motionless): stays dead while it sits at
                // its frozen position. Without this, a zombie would be re-added as a
                // fresh zero-velocity track — parked-feared again — every single pass.
                float[] dead = wheelDeadIds.get(id);
                if (dead != null) {
                    float ddx = p[0] - dead[0], ddy = p[1] - dead[1];
                    if (ddx * ddx + ddy * ddy < 0.01f)
                        continue; // still frozen where it "died"
                    wheelDeadIds.remove(id); // it genuinely moved — track it again
                }
                seen.add(id);
                float[] t = wheelTracks.get(id);
                if (t == null) {
                    // {x, y, vx, vy, lastSampleMs, lastMovedMs}
                    wheelTracks.put(id, new float[] { p[0], p[1], 0f, 0f, now, now });
                    continue;
                }
                float dt = (now - t[4]) / 1000f;
                if (dt >= 0.05f) { // ignore sub-50ms gaps: the difference is mostly noise
                    float mdx = p[0] - t[0], mdy = p[1] - t[1];
                    if (mdx * mdx + mdy * mdy >= 0.01f)
                        t[5] = now; // it actually moved
                    float vx = mdx / dt, vy = mdy / dt;
                    // Light smoothing keeps a dropped frame from erasing a live lane.
                    // NOTE the flag is read BEFORE either component is written — testing
                    // t[2] on the second line would see the value just assigned.
                    boolean firstSample = (t[2] == 0f && t[3] == 0f);
                    t[2] = firstSample ? vx : (0.6f * t[2] + 0.4f * vx);
                    t[3] = firstSample ? vy : (0.6f * t[3] + 0.4f * vy);
                    t[0] = p[0];
                    t[1] = p[1];
                    t[4] = now;
                }
            }
            // PRESENCE prune (the "bullet id" liveness the tracker was missing): wheels
            // explode on walls and vanish from the actor map, so any tracked id NOT in
            // this pass is gone — drop it NOW, not after a grace period.
            java.util.Iterator<java.util.Map.Entry<Integer, float[]>> it = wheelTracks.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<Integer, float[]> e = it.next();
                if (!seen.contains(e.getKey())) {
                    it.remove();
                    continue;
                }
                // MOTIONLESS prune: a real wheel rolls constantly and a real spawn
                // launches within a second — anything sitting still this long is dead
                // data (typically a frozen stale view). Dead-list it so it cannot
                // return at zero velocity next pass.
                float[] t = e.getValue();
                if (now - t[5] > WHEEL_MOTIONLESS_DROP_MS) {
                    wheelDeadIds.put(e.getKey(), new float[] { t[0], t[1] });
                    it.remove();
                }
            }
        } catch (Exception e) {
        }
    }

    /** True if (px,py) lies in the strip a wheel is sweeping (or its footprint when parked). */
    static boolean inWheelDanger(float px, float py) {
        for (float[] t : wheelTracks.values()) {
            float rx = px - t[0], ry = py - t[1];
            float speed = (float) Math.sqrt(t[2] * t[2] + t[3] * t[3]);
            if (speed < WHEEL_MIN_SPEED) { // just spawned, or between measurements
                if (rx * rx + ry * ry <= WHEEL_PARKED_RADIUS * WHEEL_PARKED_RADIUS)
                    return true;
                continue;
            }
            float ux = t[2] / speed, uy = t[3] / speed;
            float along = rx * ux + ry * uy;          // distance along its travel
            float perp = Math.abs(rx * -uy + ry * ux); // distance across the lane
            float reach = speed * (WHEEL_LOOKAHEAD_MS / 1000f) + WHEEL_BODY_HALF_LEN;
            if (perp <= WHEEL_LANE_HALF_W && along >= -WHEEL_BACK_MARGIN && along <= reach)
                return true;
        }
        return false;
    }

    /**
     * Lane-entry gate: true when a character at (mp) heading toward (tx,ty) should HOLD
     * for a wheel. Holds only while currently safe — a character already inside a lane
     * must keep moving, so this returns false there and the pathing carries it out.
     */
    private static long wheelHoldSince = 0L;     // when the current uninterrupted hold began
    private static long wheelOverrideUntil = 0L; // gate suppressed until here after a stuck hold

    /**
     * While HOLDING for a wheel, drift onto the current tile's CENTRE. Launchers fire
     * down tile-centre lanes, so an off-centre hold straddles toward the adjacent lane:
     * safety needs >= 0.73 from a lane centreline (0.5 wheel half-width + 0.23 measured
     * hurtbox reach), a tile centre gives 1.0, but a knight parked 0.38 off-centre is
     * inside the clip zone of a lane the gate isn't even watching. Direct per-axis key
     * presses (threshold 0.1) because setMovementFromDelta's deadzone (~0.39) is wider
     * than the whole correction. No-op if the centre itself reads dangerous.
     */
    static void wheelHoldCenterNudge(float[] mp) {
        float cx = (float) Math.floor(mp[0]) + 0.5f;
        float cy = (float) Math.floor(mp[1]) + 0.5f;
        if (inWheelDanger(cx, cy))
            return; // centring would walk INTO a feared strip — stand where we are
        float dx = cx - mp[0], dy = cy - mp[1];
        if (dy > 0.1f)
            SocketInputState.DOWN.add("W");
        else if (dy < -0.1f)
            SocketInputState.DOWN.add("S");
        if (dx > 0.1f)
            SocketInputState.DOWN.add("D");
        else if (dx < -0.1f)
            SocketInputState.DOWN.add("A");
    }

    static boolean wheelHold(float[] mp, float tx, float ty) {
        // Post-backstop override: the gate is OFF for a whole window, not one tick.
        // Resetting-and-returning-false once freed a single movement tick, after which
        // the still-dangerous reading started a fresh 6s hold — one step every 6
        // seconds, i.e. standing still to the eye. Crossing the feared strip takes
        // ~0.5-1s of continuous movement, so suppress the gate long enough to DO it.
        long now0 = System.currentTimeMillis();
        if (now0 < wheelOverrideUntil) {
            wheelHoldSince = 0L;
            return false;
        }
        // LAUNCHERS ARE KILL TARGETS: within engage range of a live one the gate
        // YIELDS. The mouth is never clear — every fresh spawn sits a tick at zero
        // velocity (parked bubble) and each launch's feared strip sweeps the approach —
        // so gating there froze the party ~5 tiles out, exactly where the routine needs
        // to close in and destroy the source. Combat + shield-bump own survival inside.
        for (int i = 0; i < wheelLaunchers.size(); i++) {
            float[] L = wheelLaunchers.get(i);
            float ldx = mp[0] - L[0], ldy = mp[1] - L[1];
            if (ldx * ldx + ldy * ldy <= WHEEL_LAUNCHER_ENGAGE_SQ) {
                wheelHoldSince = 0L;
                return false;
            }
        }
        boolean want = false;
        if (!wheelTracks.isEmpty() && !inWheelDanger(mp[0], mp[1])) {
            // Already inside a lane => never freeze; the path carries us out. Otherwise
            // probe a step along our heading and hold if THAT lands in a lane.
            float dx = tx - mp[0], dy = ty - mp[1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len >= 0.001f) {
                float px = mp[0] + dx / len * WHEEL_STEP_AHEAD;
                float py = mp[1] + dy / len * WHEEL_STEP_AHEAD;
                want = inWheelDanger(px, py);
            }
        }
        long now = System.currentTimeMillis();
        if (!want) {
            wheelHoldSince = 0L;
            return false;
        }
        if (wheelHoldSince == 0L) {
            wheelHoldSince = now;
            SocketInputState.debugFile("[wheel] holding at (" + Reflect.fmt(mp[0]) + "," + Reflect.fmt(mp[1]) + ") — "
                    + wheelTracks.size() + " wheel(s) tracked");
            return true;
        }
        // BACKSTOP: a lane that never clears means the model is wrong (a mis-measured or
        // phantom wheel), and waiting forever just feeds the run to the no-progress
        // watchdog. Give up the wait and move — a wrong guess costs one hit, a stall
        // costs the run.
        if (now - wheelHoldSince >= WHEEL_HOLD_MAX_MS) {
            SocketInputState.writeLogAlways("[wheel] held " + (WHEEL_HOLD_MAX_MS / 1000) + "s at (" + Reflect.fmt(mp[0]) + ","
                    + Reflect.fmt(mp[1]) + ") without the lane clearing — gate OFF for "
                    + (WHEEL_OVERRIDE_MS / 1000f) + "s to push through");
            wheelHoldSince = 0L;
            wheelOverrideUntil = now + WHEEL_OVERRIDE_MS;
            return false;
        }
        return true;
    }

    /** Full state wipe (routine stop / new floor) — tracks must not survive across runs. */
    static void reset() {
        wheelTracks.clear();
        wheelLaunchers.clear();
        wheelDeadIds.clear();
        wheelTrackView = null;
        wheelHoldSince = 0L;
        wheelOverrideUntil = 0L;
    }
}

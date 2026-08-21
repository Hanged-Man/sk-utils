package com.threerings.opengl.gui;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/**
 * Mission stats logger for autopilot mode → ~/.sk-utils/mission_stats.jsonl (one JSON
 * line per attempt: ts/status/runtime_ms/crowns/deaths/floor/reason). MAIN-only; extracted
 * from SocketInputState (same package — leans on its package-private helpers). Lifecycle:
 * SIS's startRoutineForCurrentFloor calls missionStart() at the lobby and markAllOnFloor1()
 * when floor 1's routine begins (the runtime clock starts THERE — lobby time doesn't
 * count); routineFinish/campaignAbort call missionEnd(). The death watch stays wired
 * through SIS.tickDeathWatch (the Patcher-stubbed entry point), which delegates here.
 */
public class MissionStats {

    // Timing runs from floor 1's routine start (all characters loaded) to the boss
    // floor's completion; crowns = party wallet delta over the whole mission window
    // (per-alt balances gathered by UDP).
    private static volatile long missionStartMs = 0L;      // when tracking began (0 = no mission tracked)
    private static volatile int missionCrownsBefore = -1;  // party crown sum snapshotted at lobby start (-1 = pending/unread)
    private static volatile int missionDeaths = 0;         // party deaths (any member hp>0→0) during the tracked mission
    private static volatile int ownPlayerOid = -1;         // this client's PlayerObject._oid (its key in the health DSet)
    // Death watch (MAIN only): last-seen `current` per party member (by playerOid), for edge detection.
    private static final java.util.HashMap<Integer, Integer> deathLastHp = new java.util.HashMap<Integer, Integer>();
    // PARTY WIPE: every member at 0 hp at once, held for PARTY_WIPE_ABORT_MS. Nobody can
    // revive anybody when the whole party is down, so this state never recovers on its own —
    // it is the one failure the other watchdogs all missed (observed 2026-07-30: the party lay
    // dead ~6 hours with the combat loop still ticking and no abort). Held briefly rather than
    // fired instantly so a simultaneous knock-down that the game itself resolves is not
    // mistaken for a wipe.
    private static long partyWipeSince = 0L;               // when every member was first seen down (0 = not)
    private static final long PARTY_WIPE_ABORT_MS = 10000L; // all down this long => abort + relaunch (30s->10s, user-set)
    /**
     * One crown snapshot: the total, plus WHICH clients it came from (listener port →
     * balance; the main is port 0). Identities matter, not just the count — two
     * snapshots can hear from the same NUMBER of clients but a different SET (an alt
     * that restarted onto another port, a stale client answering one and a fresh one
     * the other), and the delta then shifts by the difference between those clients'
     * balances rather than by anything the party earned.
     */
    private static final class CrownSnap {
        final int total;
        final java.util.HashMap<Integer, Integer> byPort;

        CrownSnap(int total, java.util.HashMap<Integer, Integer> byPort) {
            this.total = total;
            this.byPort = byPort;
        }

        String describe() {
            java.util.TreeMap<Integer, Integer> sorted = new java.util.TreeMap<Integer, Integer>(byPort);
            return sorted.toString();
        }
    }

    // Per-mission, captured into a local at missionEnd — NOT read from a shared static
    // at snapshot time. On an abort the next mission's BEFORE snapshot runs concurrently
    // with this one's AFTER snapshot, and shared statics let one attribute the other's
    // numbers.
    private static volatile CrownSnap crownSnapBefore = null;
    // Identifies WHICH attempt a baseline belongs to. Counts and responder sets can both
    // look perfect while the baseline came from an EARLIER mission — the delta then spans
    // two runs and reads as roughly double. Nothing else in the checks can see that.
    private static volatile long missionSeq = 0L;
    private static volatile long crownSnapBeforeSeq = -1L;
    private static volatile int auctionSpentAtStart = 0; // AuctionBot spend at mission start
    private static final long CROWN_REPLY_WINDOW_MS = 2000L;
    private static long deathLastCheckAt = 0L;             // throttle: when the death watch last sampled the party health
    private static final long DEATH_CHECK_INTERVAL_MS = 1000L; // 1Hz — both abort triggers below are 10s DWELL timers, so edges no longer matter
    private static long mainDownSince = 0L;                // when the main was first seen down (0 = up)
    private static final long MAIN_DOWN_ABORT_MS = 10000L; // main at 0 hp this long => abort + relaunch
    private static final String MISSION_STATS_FILE = SocketInputState.DIR + "/mission_stats.jsonl";

    /** Begins tracking a mission attempt: stamps a provisional start time and snapshots the
     *  party's crown total (off-thread). Called from startRoutineForCurrentFloor when the
     *  mission-lobby routine starts; the runtime clock is then RE-stamped by
     *  markAllOnFloor1() so lobby time doesn't count. MAIN-only. */
    static void missionStart() {
        missionStartMs = System.currentTimeMillis();
        missionCrownsBefore = -1;
        missionDeaths = 0; // tickDeathWatch counts from here until missionEnd
        partyWipeSince = 0L;
        mainDownSince = 0L;
        ownPlayerOid = -1;
        auctionSpentAtStart = AuctionBot.spentThisSession; // auction buys come out of the same wallet
        SocketInputState.broadcastAll("DMGRESET 1"); // zero every client's HUD damage tally for the new mission
        crownSnapBefore = null;
        final long seq = ++missionSeq; // identifies THIS attempt's baseline
        new Thread(() -> {
            CrownSnap snap = snapshotPartyCrowns();
            crownSnapBefore = snap;
            crownSnapBeforeSeq = seq;
            missionCrownsBefore = snap.total;
            SocketInputState.debugFile("[mission] crowns before = " + snap.total + " from " + snap.describe());
        }, "SK CrownsBefore").start();
        SocketInputState.debugFile("[mission] START — tracking runtime + crowns + deaths");
    }

    /** Re-stamps the runtime clock: floor 1's routine begins only once the per-floor gate
     *  saw all characters loaded, so the run officially starts HERE. Lobby time (join wait,
     *  forge pass) is not part of the run; crowns/deaths tracking still begins at the lobby
     *  (nothing to earn there). No-op if no mission is tracked. */
    static void markAllOnFloor1() {
        if (missionStartMs <= 0L)
            return;
        missionStartMs = System.currentTimeMillis();
        SocketInputState.debugFile("[mission] clock started — all characters on floor 1");
    }

    /** Ends the tracked mission attempt: computes runtime, snapshots the party's crowns
     *  again (off-thread), and appends one JSONL line. No-op if no mission is being tracked.
     *  status = "SUCCESS" (boss floor completed) or "FAILURE" (aborted). MAIN-only. */
    static void missionEnd(String status, String floor, String reason) {
        long start = missionStartMs;
        if (start <= 0L)
            return; // nothing tracked (e.g. abort before the lobby, or a manual stop)
        missionStartMs = 0L; // stops tickDeathWatch from counting further
        final long runtimeMs = System.currentTimeMillis() - start;
        final int before = missionCrownsBefore;
        final int deaths = missionDeaths;
        final String st = status, fl = floor, rs = reason;
        final CrownSnap snapBefore = crownSnapBefore;
        final boolean baselineIsOurs = (crownSnapBeforeSeq == missionSeq);
        new Thread(() -> {
            CrownSnap snapAfter = snapshotPartyCrowns();
            // The auction bot spends from the MAIN's wallet and keeps sweeping right
            // through a mission, so its outgoings land inside this window and would be
            // read as negative loot. Add them back: this figure is meant to be what the
            // party EARNED. (Clamped at 0 because a live-mode toggle resets the counter.)
            int auctionSpent = Math.max(0, AuctionBot.spentThisSession - auctionSpentAtStart);
            Integer crowns = (before < 0 || !baselineIsOurs) ? null
                    : Integer.valueOf(snapAfter.total - before + auctionSpent);
            if (auctionSpent > 0)
                SocketInputState.writeLogAlways("[mission] auction bot spent " + auctionSpent
                        + "cr during this run — added back so crowns reflects LOOT");
            if (!baselineIsOurs)
                SocketInputState.writeLogAlways("[mission] WARNING crown baseline belongs to attempt "
                        + crownSnapBeforeSeq + ", not " + missionSeq + " — crowns logged as null");
            int srcBefore = (snapBefore == null) ? 0 : snapBefore.byPort.size();
            int srcAfter = snapAfter.byPort.size();
            SocketInputState.debugFile("[mission] crowns after = " + snapAfter.total + " from " + snapAfter.describe());
            // Per-client delta — the line that actually identifies an anomaly. Loot is
            // spread across the party, so a run where ONE client gained far more than
            // the others did not earn it by looting.
            if (snapBefore != null) {
                java.util.TreeMap<Integer, Integer> d = new java.util.TreeMap<Integer, Integer>();
                for (java.util.Map.Entry<Integer, Integer> e : snapAfter.byPort.entrySet()) {
                    Integer b = snapBefore.byPort.get(e.getKey());
                    if (b != null)
                        d.put(e.getKey(), Integer.valueOf(e.getValue().intValue() - b.intValue()));
                }
                SocketInputState.debugFile("[mission] crowns delta per client = " + d);
            }
            // Compare the SETS, not the counts: same number of responders drawn from a
            // different set of clients moves the delta by the difference between their
            // BALANCES, which is what a 5x-inflated row looks like.
            if (snapBefore != null && !snapBefore.byPort.keySet().equals(snapAfter.byPort.keySet()))
                SocketInputState.writeLogAlways("[mission] WARNING crown snapshot sets DIFFER —"
                        + " before " + snapBefore.describe() + " vs after " + snapAfter.describe()
                        + " — this run's crowns are NOT comparable");
            else if (srcBefore != srcAfter)
                SocketInputState.writeLogAlways("[mission] WARNING crown sources differ: " + srcBefore
                        + " at start vs " + srcAfter + " at end — this run's crowns are NOT comparable");
            writeMissionStat(st, runtimeMs, crowns, deaths, fl, rs, srcBefore, srcAfter, auctionSpent);
        }, "SK MissionLog").start();
    }

    /**
     * Party crown total: this client's own balance plus one reply per ALT, gathered by
     * a UDP CROWNQUERY/CROWNREPLY round trip. MUST run off the tick thread.
     *
     * <p>Replies are keyed by the replying client's listener port, so a duplicate can't
     * double-count and — the point — the SET of responders is known. {@link #lastCrownSources}
     * carries the responder count out so missionEnd can compare the two snapshots and flag it.
     * <p>Returns as soon as every expected alt has answered, so the common case is fast;
     * otherwise it waits out the deadline.
     */
    private static CrownSnap snapshotPartyCrowns() {
        int own = Mappings.getCrowns(SocketInputState._cachedCtx);
        java.util.HashMap<Integer, Integer> byPort = new java.util.HashMap<Integer, Integer>();
        byPort.put(Integer.valueOf(0), Integer.valueOf(own < 0 ? 0 : own)); // 0 = this client
        int expectedAlts = Math.max(0, SocketInputState.campaignPartySize() - 1);
        try {
            DatagramSocket ds = new DatagramSocket();
            ds.setSoTimeout(200);
            int returnPort = ds.getLocalPort();
            byte[] q = ("CROWNQUERY " + returnPort).getBytes(StandardCharsets.UTF_8);
            java.net.InetAddress lo = java.net.InetAddress.getByName("127.0.0.1");
            for (int p = SKConfig.BASE_PORT + 1; p <= SKConfig.BASE_PORT + SKConfig.MAX_ALTS; p++) // alt listener ports
                ds.send(new DatagramPacket(q, q.length, lo, p));
            byte[] buf = new byte[64];
            long deadline = System.currentTimeMillis() + CROWN_REPLY_WINDOW_MS;
            while (System.currentTimeMillis() < deadline) {
                // byPort INCLUDES this client (port 0), so compare the ALT count against
                // expectedAlts. Comparing the raw size stopped one alt early, and WHICH
                // alt got left out was a race — so the two snapshots could cover
                // different clients and the delta moved by the gap between two players'
                // balances instead of by what the party earned.
                if (expectedAlts > 0 && byPort.size() - 1 >= expectedAlts)
                    break; // every expected alt has answered
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    ds.receive(pkt);
                    String r = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
                    String[] pp = r.split("\\s+");
                    if (pp.length == 3 && "CROWNREPLY".equals(pp[0])) // port-identified
                        byPort.put(Integer.valueOf(Integer.parseInt(pp[1])),
                                Integer.valueOf(Integer.parseInt(pp[2])));
                } catch (java.net.SocketTimeoutException e) {
                    /* keep polling until the deadline */ }
            }
            ds.close();
        } catch (Exception e) {
            SocketInputState.debugFile("[mission] crown snapshot error: " + e);
        }
        int sum = 0;
        for (Integer v : byPort.values())
            sum += v.intValue();
        if (expectedAlts > 0 && byPort.size() - 1 < expectedAlts)
            SocketInputState.writeLogAlways("[mission] WARNING crown snapshot heard from only "
                    + (byPort.size() - 1) + " of " + expectedAlts + " alt(s) — the run's crowns will be short");
        return new CrownSnap(sum, byPort);
    }

    /**
     * Party-wide death watch (MAIN only, every tick while campaignActive — invoked via
     * SIS.tickDeathWatch, the Patcher-stubbed entry point). Reads every PartyObject.health
     * entry and counts a DEATH each time a member's `current` transitions from &gt;0 to &lt;=0
     * (edge-triggered per playerOid). Counts into missionDeaths only while a mission is
     * tracked (missionStartMs&gt;0); it keeps the per-member last-health baseline fresh at all
     * other times so no death is missed at the mission edges. No UDP needed — the main's
     * PartyObject.health DSet holds ALL members' health, keyed by playerOid.
     */
    static void tickDeathWatch(Object controller) {
        try {
            if (controller == null)
                return;
            long now = System.currentTimeMillis();
            if (now - deathLastCheckAt < DEATH_CHECK_INTERVAL_MS)
                return; // 1Hz — the abort triggers below are 10s dwell timers
            deathLastCheckAt = now;
            Object partyObj = Reflect.partyObjectOf(controller);
            if (partyObj == null)
                return;
            Object healthSet = partyObj.getClass().getField("health").get(partyObj);
            if (healthSet == null)
                return;
            boolean tracking = (missionStartMs > 0L);
            int mainOid = ownPlayerOid(); // resolved regardless of tracking, like the wipe tally
            boolean mainDown = false;
            int members = 0, down = 0; // party-wipe tally (this same pass — no extra scan)
            for (Object entry : (Iterable<?>) healthSet) {
                Integer oid = Reflect.readIntFieldNullable(entry, "_playerOid");
                Integer cur = Reflect.readIntFieldNullable(entry, "current");
                if (oid == null || cur == null)
                    continue;
                members++;
                if (cur.intValue() <= 0)
                    down++;
                Integer last = deathLastHp.get(oid);
                if (tracking && last != null && last.intValue() > 0 && cur.intValue() <= 0)
                    missionDeaths++; // this member just went down (revive doesn't re-trigger: 0→full isn't the edge)
                deathLastHp.put(oid, cur);
                if (mainOid > 0 && oid.intValue() == mainOid && cur.intValue() <= 0)
                    mainDown = true;
            }
            // PARTY WIPE — main + every alt at 0 hp continuously for PARTY_WIPE_ABORT_MS.
            // Unlike the other triggers this does not depend on the main MOVING or on a step
            // timing out: a wiped party sits in a state nothing recovers from, and the routine
            // (and its combat loop) keeps ticking as if nothing happened.
            if (members > 0 && down == members) {
                if (partyWipeSince == 0L) {
                    partyWipeSince = now;
                    SocketInputState.debugFile("[mission] all " + members + " knight(s) down — wipe timer started");
                } else if (now - partyWipeSince >= PARTY_WIPE_ABORT_MS) {
                    partyWipeSince = 0L;
                    mainDownSince = 0L;
                    SocketInputState.campaignAbort("party wipe — all " + members + " knight(s) at 0 hp for "
                            + (PARTY_WIPE_ABORT_MS / 1000) + "s");
                    return; // one abort per tick — don't let the main-down timer double-fire
                }
            } else {
                partyWipeSince = 0L; // someone is up — not a wipe
            }
            // MAIN DOWN — the wipe detector's shape, main's entry alone (checked AFTER the
            // wipe so a full wipe reports as a wipe). The main carries every objective:
            // 10s at 0 hp means nobody revived him and the run is going nowhere.
            if (mainOid > 0 && mainDown) {
                if (mainDownSince == 0L) {
                    mainDownSince = now;
                    SocketInputState.debugFile("[mission] main down — reboot timer started");
                } else if (now - mainDownSince >= MAIN_DOWN_ABORT_MS) {
                    mainDownSince = 0L;
                    SocketInputState.campaignAbort("main at 0 hp for " + (MAIN_DOWN_ABORT_MS / 1000)
                            + "s — rebooting the mission");
                }
            } else {
                mainDownSince = 0L; // main is up — reset
            }
        } catch (Exception e) {
        }
    }

    /** This client's PlayerObject._oid — the key its own entry uses in PartyObject.health.
     *  Cached: stable for the login session; resolved lazily since _cachedCtx is null pre-login. */
    private static int ownPlayerOid() {
        if (ownPlayerOid > 0)
            return ownPlayerOid;
        try {
            Object ctx = SocketInputState._cachedCtx;
            if (ctx != null)
                ownPlayerOid = Mappings.getPlayerOid(Mappings.getPlayerObject(ctx));
        } catch (Exception e) {
        }
        return ownPlayerOid;
    }

    /** Appends one mission attempt to the JSONL stats file. */
    private static void writeMissionStat(String status, long runtimeMs, Integer crowns, int deaths, String floor,
            String reason, int srcBefore, int srcAfter, int auctionSpent) {
        try {
            new File(SocketInputState.DIR).mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date());
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ts\":\"").append(ts).append("\"");
            // Which mission this run was, so the Ctrl+\ summary can report each
            // separately — runtimes and crowns aren't comparable across missions.
            String mission = SocketInputState.activeMissionLabel();
            if (mission != null)
                sb.append(",\"mission\":\"").append(jsonEsc(mission)).append("\"");
            sb.append(",\"status\":\"").append(status).append("\"");
            sb.append(",\"runtime_ms\":").append(runtimeMs);
            sb.append(",\"crowns\":").append(crowns == null ? "null" : crowns.toString());
            sb.append(",\"deaths\":").append(deaths);
            // How many clients each crown snapshot actually heard from (this client + alts).
            // Equal and == party size => the crowns figure is trustworthy.
            sb.append(",\"crown_src_before\":").append(srcBefore);
            sb.append(",\"crown_src_after\":").append(srcAfter);
            if (auctionSpent > 0) // omitted when the auction bot was idle, i.e. normally
                sb.append(",\"auction_spent\":").append(auctionSpent);
            if (floor != null)
                sb.append(",\"floor\":\"").append(jsonEsc(floor)).append("\"");
            if (reason != null)
                sb.append(",\"reason\":\"").append(jsonEsc(reason)).append("\"");
            sb.append("}\n");
            FileWriter fw = new FileWriter(MISSION_STATS_FILE, true);
            fw.write(sb.toString());
            fw.close();
            SocketInputState.debugFile("[mission] logged " + status + " runtime=" + runtimeMs + "ms crowns=" + crowns + " deaths=" + deaths);
        } catch (Exception e) {
        }
    }

    /** Minimal JSON string escaping for the free-text fields. */
    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

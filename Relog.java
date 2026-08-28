package com.threerings.opengl.gui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Auto-reconnect for the full-auto modes.
 *
 * <p>While the Ctrl+R mission cycle or the Ctrl+Q
 * PvP autoqueue are toggled, any client that is NOT logged on retries a logon every {@link #RETRY_MS}
 * until it is back in.
 *
 * <p>CREDENTIALS — the MAIN replays its retained credentials, rebuilding
 * them as ProjectXCredentials(SteamUser.getSteamID()) if ever lost. ALTS
 * pull credentials from {@code ~/.sk-utils/config.properties}.
 *
 * <p>If the discovered helper is missing (future reshuffle) the fallback is a plain
 * {@code Client.logon()} — real-named {@code isLoggedOn()} plus the unique public
 * SYNCHRONIZED 0-arg boolean — which gets the account back on but leaves the
 * knight-pick screen up; that degradation is logged loudly. The core detection/logon
 * path uses only real names, so it survives any re-obfuscation unaided.
 */
final class Relog {

    /** Spacing between logon attempts while disconnected and gated. */
    private static final long RETRY_MS = 15000L;
    /** Settle time after a logoff is first seen before the first attempt (lets the
     *  client finish tearing the session down and raise its logon screen). */
    private static final long GRACE_MS = 5000L;
    /** How often to refresh the cached knight name while logged on. */
    private static final long KNIGHT_CACHE_MS = 5000L;
    /** Coarse tick gate: nothing here needs better than ~1s resolution (grace 5s,
     *  retries 15s), so don't pay the reflective invokes every GUI frame. */
    private static final long TICK_GATE_MS = 250L;

    private static volatile String lastKnightName = null;
    private static volatile String lastAccountName = null; // alts: login name from the live creds
    private static long lastKnightCacheAt = 0L;
    private static long lastLoggedOnAt = 0L;
    private static long lastAttemptAt = 0L;
    private static long nextTickAt = 0L;
    private static boolean announcedOff = false;
    private static String lastConfigWarning = null; // one-shot latch for config diagnostics

    // Cached reflective handles — one Client class per JVM, so plain statics are safe.
    private static Method credsGetter, credsSetter, logonMethod, isLoggedOnMethod;
    // The presents Client is one stable object per ctx (and one ctx per JVM) — cached
    // so the tick never re-pays Mappings.getClientManager's uncached reflection.
    private static Object cachedClient, cachedClientCtx;

    private Relog() {
    }

    /**
     * Called every GUI frame from the patched poll — the ONLY host that still runs on
     * the logon screen (no scene ticks there; the same reasoning that put the idle
     * watchdog in the poll). Cheap when logged on: one real-named isLoggedOn() call.
     */
    static void tick() {
        long now = System.currentTimeMillis();
        if (now < nextTickAt)
            return;
        nextTickAt = now + TICK_GATE_MS;
        try {
            Object ctx = SocketInputState._cachedCtx;
            if (ctx == null)
                return; // never reached a scene this session — gates can't be armed either
            if (ctx != cachedClientCtx) {
                cachedClient = Mappings.getClientManager(ctx);
                cachedClientCtx = ctx;
            }
            Object client = cachedClient;
            if (client == null) {
                cachedClientCtx = null; // resolve again next gate
                return;
            }
            if (isLoggedOn(client)) {
                lastLoggedOnAt = now;
                announcedOff = false;
                lastConfigWarning = null; // a fresh outage re-logs config diagnostics
                if (now - lastKnightCacheAt >= KNIGHT_CACHE_MS) {
                    lastKnightCacheAt = now;
                    try {
                        String kn = Mappings.getKnightCharacterName(Mappings.getPlayerObject(ctx));
                        if (kn != null && !kn.isEmpty() && !"Unknown".equals(kn))
                            lastKnightName = kn;
                    } catch (Exception ignored) {
                    }
                    // Alts: also remember WHICH ACCOUNT this client is, off the live
                    // credentials — after a logoff they are gone (tested), and the
                    // account is what picks this client's relog_alts config pair.
                    if (!SocketInputState.isMainAccount()) {
                        try {
                            Object creds = getCredentials(client);
                            String acct = (creds == null) ? null : accountNameOf(creds);
                            if (acct != null && !acct.isEmpty())
                                lastAccountName = acct;
                        } catch (Exception ignored) {
                        }
                    }
                }
                return;
            }
            if (!SocketInputState.isFullAutoArmed())
                return;
            if (!announcedOff) {
                announcedOff = true;
                SocketInputState.writeLogAlways("[relog] logged OFF with a full-auto mode armed — "
                        + "retrying logon every " + (RETRY_MS / 1000) + "s (knight=" + lastKnightName + ")");
            }
            if (now - lastLoggedOnAt < GRACE_MS || now - lastAttemptAt < RETRY_MS)
                return;
            lastAttemptAt = now;
            attempt(ctx, client);
        } catch (Exception ignored) {
            // never let the poll die; attempt() logs its own failures
        }
    }

    private static void attempt(Object ctx, Object client) {
        try {
            Object creds;
            String knightForPick = lastKnightName;
            if (SocketInputState.isMainAccount()) {
                // MAIN: replay the retained credentials (confirmed in play 2026-08-25);
                // rebuild = bare steamID, no secrets.
                creds = getCredentials(client);
                if (creds == null)
                    creds = rebuildMainCredentials();
            } else {
                // ALTS: config-driven ALWAYS (user-directed 2026-08-25) — a logged-off
                // alt retains nothing recoverable in-process, so the account:knight
                // pairs + shared password come from ~/.sk-utils/config.properties.
                String[] entry = altConfigEntry();
                if (entry == null)
                    return; // altConfigEntry logged why
                creds = buildAltCredentials(entry[0], entry[2]);
                if (entry[1] != null && !entry[1].isEmpty())
                    knightForPick = entry[1]; // the pair pins WHICH of the 3 knights
            }
            if (creds == null) {
                SocketInputState.writeLogAlways(
                        "[relog] no usable credentials — cannot relog");
                return;
            }
            // Preferred: the game's own relogon helper — replays the creds AND re-picks
            // the knight through the app's auto-pick field.
            String helperCls = MappingsNames.RELOG_HELPER_CLASS;
            if (helperCls != null && !helperCls.isEmpty()) {
                try {
                    Class<?> dk = Class.forName(helperCls);
                    Constructor<?> ctor = null;
                    for (Constructor<?> c : dk.getDeclaredConstructors())
                        if (c.getParameterTypes().length == 4) {
                            ctor = c;
                            break;
                        }
                    if (ctor != null) {
                        ctor.setAccessible(true);
                        Object language = null;
                        try {
                            // Self-assignment via the helper: its (Client) method writes this
                            // value straight back into creds.language — the fetch exists only
                            // so the helper doesn't clobber the field with null.
                            language = creds.getClass().getField("language").get(creds);
                        } catch (Exception ignored) {
                        }
                        Object knight = null;
                        if (knightForPick != null)
                            knight = Class.forName("com.threerings.util.Name")
                                    .getConstructor(String.class).newInstance(knightForPick);
                        Object helper = ctor.newInstance(ctx, language, creds, knight);
                        Method h = null;
                        for (Method m : dk.getDeclaredMethods())
                            if (m.getName().equals(MappingsNames.RELOG_HELPER_METHOD)
                                    && m.getParameterTypes().length == 1) {
                                h = m;
                                break;
                            }
                        if (h != null) {
                            h.setAccessible(true);
                            h.invoke(helper, client);
                            SocketInputState.writeLogAlways("[relog] logon attempt via the game's"
                                    + " relogon helper (knight=" + knightForPick + ")");
                            return;
                        }
                    }
                } catch (Exception e) {
                    SocketInputState.writeLogAlways("[relog] helper path failed (" + cause(e)
                            + ") — falling back to plain logon");
                }
            }
            // Fallback: plain Client.logon(). Gets the account back on; the knight-pick
            // screen is NOT automated on this path — logged so the gap is visible.
            // The ONLY place Relog itself sets client credentials: the helper path's
            // invoked game method sets them, and the builders are pure.
            setCredentials(client, creds);
            Method logon = findLogon(client);
            if (logon == null) {
                SocketInputState.writeLogAlways("[relog] no logon method found on the Client");
                return;
            }
            Object ok = logon.invoke(client);
            SocketInputState.writeLogAlways("[relog] plain logon attempt -> " + ok
                    + " (knight pick NOT automated on this path)");
        } catch (Exception e) {
            SocketInputState.writeLogAlways("[relog] attempt failed: " + cause(e));
        }
    }

    /** Client.isLoggedOn() — REAL-NAMED (ProjectXApp calls it by name in 20260824). */
    private static boolean isLoggedOn(Object client) throws Exception {
        if (isLoggedOnMethod == null)
            isLoggedOnMethod = client.getClass().getMethod("isLoggedOn");
        return ((Boolean) isLoggedOnMethod.invoke(client)).booleanValue();
    }

    /** The 0-arg method returning exactly presents net.Credentials (wT in 20260824). */
    private static Object getCredentials(Object client) {
        try {
            if (credsGetter == null)
                for (Method m : client.getClass().getMethods())
                    if (m.getParameterTypes().length == 0 && m.getReturnType().getName()
                            .equals("com.threerings.presents.net.Credentials")) {
                        credsGetter = m;
                        break;
                    }
            return (credsGetter == null) ? null : credsGetter.invoke(client);
        } catch (Exception e) {
            return null;
        }
    }

    /** The (net.Credentials)->void method (a in 20260824). */
    private static void setCredentials(Object client, Object creds) {
        try {
            if (credsSetter == null)
                for (Method m : client.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1
                            && p[0].getName().equals("com.threerings.presents.net.Credentials")
                            && m.getReturnType() == void.class) {
                        credsSetter = m;
                        break;
                    }
                }
            if (credsSetter != null)
                credsSetter.invoke(client, creds);
        } catch (Exception ignored) {
        }
    }

    /** logon() = the unique public SYNCHRONIZED 0-arg boolean on the presents Client. */
    private static Method findLogon(Object client) {
        if (logonMethod != null)
            return logonMethod;
        for (Method m : client.getClass().getMethods())
            if (m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class
                    && Modifier.isSynchronized(m.getModifiers())) {
                logonMethod = m;
                return m;
            }
        return null;
    }

    /** The MAIN's rebuild: Steam creds are a bare steamID (ProjectXApp.init's own path).
     *  PURE — the caller's path (helper or fallback) owns setting them on the client. */
    private static Object rebuildMainCredentials() {
        try {
            Class<?> pxc = Class.forName("com.threerings.projectx.data.ProjectXCredentials");
            long steamId = ((Long) Class.forName("com.threerings.froth.SteamUser")
                    .getMethod("getSteamID").invoke(null)).longValue();
            Constructor<?> c = pxc.getDeclaredConstructor(long.class);
            c.setAccessible(true);
            Object creds = c.newInstance(Long.valueOf(steamId));
            SocketInputState.writeLogAlways("[relog] rebuilt Steam credentials (steamID)");
            return creds;
        } catch (Exception e) {
            SocketInputState.writeLogAlways("[relog] credential rebuild failed: " + cause(e));
            return null;
        }
    }

    /** One-shot latch for the config diagnostics below: an outage repeats the same
     *  message every attempt — log it on CHANGE only (announcedOff's pattern),
     *  reset when logged on so the next outage announces afresh. */
    private static void warnConfig(String msg) {
        if (msg.equals(lastConfigWarning))
            return;
        lastConfigWarning = msg;
        SocketInputState.writeLogAlways(msg);
    }

    /**
     * This alt's {account, knight, password} from config: {@code relog_password} +
     * {@code relog_alts=account:knight, account:knight, ...} — read FRESH per attempt
     * (SKConfig.freshRead), so the lines can be added or corrected while the clients
     * keep running. Matched by the cached ACCOUNT name first (exact identity), then by
     * the cached KNIGHT name (covers a client whose creds were never readable). Null
     * when unidentifiable — logged (once per message) with what was looked for, so a
     * config typo is diagnosable from one line.
     */
    private static String[] altConfigEntry() {
        java.util.Properties cfg = SKConfig.freshRead();
        String pw = cfg.getProperty("relog_password", "").trim();
        String alts = cfg.getProperty("relog_alts", "").trim();
        if (pw.isEmpty() || alts.isEmpty()) {
            warnConfig("[relog] alt relog needs relog_password= and "
                    + "relog_alts=account:knight,... in ~/.sk-utils/config.properties");
            return null;
        }
        // Parse every pair once (account required; a pair without one is unusable).
        java.util.ArrayList<String[]> pairs = new java.util.ArrayList<String[]>();
        for (String raw : alts.split(",")) {
            String[] kv = raw.split(":", 2);
            if (kv.length == 2 && !kv[0].trim().isEmpty())
                pairs.add(new String[] { kv[0].trim(), kv[1].trim() });
        }
        String[] hit = pairByField(pairs, lastAccountName, 0); // by ACCOUNT first
        if (hit == null)
            hit = pairByField(pairs, lastKnightName, 1);       // then by KNIGHT
        if (hit != null)
            return new String[] { hit[0], hit[1], pw };
        warnConfig("[relog] no relog_alts entry matches this client "
                + "(account=" + lastAccountName + ", knight=" + lastKnightName
                + ") — check the pairs in config.properties");
        return null;
    }

    /** The first pair whose field (0 = account, 1 = knight) equals the needle. */
    private static String[] pairByField(java.util.List<String[]> pairs, String needle, int field) {
        if (needle == null || needle.isEmpty())
            return null;
        for (int i = 0; i < pairs.size(); i++)
            if (pairs.get(i)[field].equalsIgnoreCase(needle))
                return pairs.get(i);
        return null;
    }

    /** Plaintext username/password credentials — the manual-login path's shape.
     *  PURE — the caller's path (helper or fallback) owns setting them on the client. */
    private static Object buildAltCredentials(String account, String password) {
        try {
            Class<?> pxc = Class.forName("com.threerings.projectx.data.ProjectXCredentials");
            Class<?> nameCls = Class.forName("com.threerings.util.Name");
            Object name = nameCls.getConstructor(String.class).newInstance(account);
            Constructor<?> c = pxc.getDeclaredConstructor(nameCls, String.class, boolean.class);
            c.setAccessible(true);
            Object creds = c.newInstance(name, password, Boolean.FALSE);
            SocketInputState.writeLogAlways(
                    "[relog] built alt credentials from config (account=" + account + ")");
            return creds;
        } catch (Exception e) {
            SocketInputState.writeLogAlways("[relog] alt credential build failed: " + cause(e));
            return null;
        }
    }

    /** The account (login) name inside username/password credentials — the REAL-NAMED
     *  _username field on presents UsernamePasswordCreds (ProjectXCredentials' parent). */
    private static String accountNameOf(Object creds) {
        try {
            Class<?> c = creds.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("_username");
                    f.setAccessible(true);
                    Object n = f.get(creds);
                    return (n == null) ? null : n.toString();
                } catch (NoSuchFieldException nf) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String cause(Throwable t) {
        return String.valueOf(Reflect.rootCause(t));
    }
}

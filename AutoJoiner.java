package com.threerings.projectx.social;

import com.threerings.opengl.gui.Mappings;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.data.PlayerEntry;
import com.threerings.projectx.social.data.Whereabouts;
import com.threerings.util.Name;

public class AutoJoiner {
    private static long lastCheck = 0;

    /**
     * Game's own join-eligibility check: a(PlayerObject, ServerObject, long, boolean).
     * Defined on Whereabouts.Party and inherited by Whereabouts.Mission — covers both.
     */
    private static boolean canJoin(Whereabouts.Party loc, PlayerObject player, Object serverObj) {
        try {
            for (java.lang.reflect.Method m : loc.getClass().getMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 4
                        && pt[0] == PlayerObject.class
                        && pt[2] == long.class
                        && pt[3] == boolean.class
                        && m.getReturnType() == boolean.class) {
                    return Boolean.TRUE.equals(
                        m.invoke(loc, player, serverObj, System.currentTimeMillis(), true));
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private static PlayerEntry findFriend(PlayerObject player, Name friendName) {
        java.util.Iterator it = player.friends.iterator();
        while (it.hasNext()) {
            PlayerEntry pe = (PlayerEntry) it.next();
            if (friendName.equals(pe.name)) return pe;
        }
        return null;
    }

    public static void checkAutoJoin(Object ctxObj, String targetFriendName) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 3000) return;
        lastCheck = now;

        try {
            PlayerObject player = (PlayerObject) Mappings.getPlayerObject(ctxObj);
            if (player == null || player.friends == null) return;

            Name friendName = new Name(targetFriendName);
            PlayerEntry friendEntry = findFriend(player, friendName);
            if (friendEntry == null) return;

            Whereabouts loc = friendEntry.whereabouts;
            if (loc == null || !(loc instanceof Whereabouts.Party)) return;

            Whereabouts.Party party = (Whereabouts.Party) loc;

            Object serverObj = Mappings.callNoArgCtxMethod(ctxObj, Mappings.CTX_SERVER_OBJECT_METHOD);
            if (!canJoin(party, player, serverObj)) return;

            if (loc instanceof Whereabouts.Mission) {
                Whereabouts.Mission mission = (Whereabouts.Mission) loc;
                if (player.whereabouts instanceof Whereabouts.Mission) {
                    Whereabouts.Mission myM = (Whereabouts.Mission) player.whereabouts;
                    if (myM.name.equals(mission.name) && myM.depth == mission.depth) return;
                }
                long delay = 500 + (long)(Math.random() * 3500);
                lastCheck = now + 10000;
                new Thread(() -> {
                    try {
                        Thread.sleep(delay);
                        PlayerObject p2 = (PlayerObject) Mappings.getPlayerObject(ctxObj);
                        PlayerEntry f2 = findFriend(p2, friendName);
                        if (f2 == null || !(f2.whereabouts instanceof Whereabouts.Mission)) return;
                        Whereabouts.Mission m2 = (Whereabouts.Mission) f2.whereabouts;
                        if (p2.whereabouts instanceof Whereabouts.Mission) {
                            Whereabouts.Mission myM2 = (Whereabouts.Mission) p2.whereabouts;
                            if (myM2.name.equals(m2.name) && myM2.depth == m2.depth) return;
                        }
                        Object so2 = Mappings.callNoArgCtxMethod(ctxObj, Mappings.CTX_SERVER_OBJECT_METHOD);
                        if (canJoin(m2, p2, so2)) {
                            Mappings.callSocialJoin(Mappings.getClientManager(ctxObj), friendName);
                        }
                    } catch (Exception e) {}
                }).start();
            } else {
                if (player.whereabouts instanceof Whereabouts.Party) return;
                lastCheck = now + 10000;
                Mappings.callSocialJoin(Mappings.getClientManager(ctxObj), friendName);
            }
        } catch (Exception e) {
        }
    }
}

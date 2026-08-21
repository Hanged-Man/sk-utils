package com.threerings.projectx.item.client;

import java.util.Iterator;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import com.threerings.opengl.gui.Mappings;
import com.threerings.opengl.gui.ForgeTracker;
import com.threerings.opengl.gui.SKConfig;
import com.threerings.opengl.gui.SocketInputState;
import java.util.Random;

public class ForgeAllAdapter extends Thread {

    private Object forgePanel; // Treat as Object to avoid compile-time dependencies on inner members

    public ForgeAllAdapter(Object forgePanel) {
        this.forgePanel = forgePanel;
    }

    public void run() {
        ForgeTracker.debug("ForgeAllAdapter.run started");
        autoForgeAll(Mappings.getCtx(forgePanel));
    }

    /**
     * The full forge-all pass from a bare ctx — no forge menu required (the menu was
     * only ever the source of ctx; the forge itself is a direct item-service call).
     * Heats every heat-ready item with max crystals (single crystal at in-game level
     * 8), then replaces any fully-leveled EQUIPPED item with a fresh (level 1) copy
     * of the same item from the inventory (any star level). Used by the forge-menu
     * Ctrl+click (via run()) and the mission cycle's automatic lobby pass.
     */
    public static void autoForgeAll(Object ctx) {
        try {
            SocketInputState.isAutoForging = true;
            Object po = Mappings.getPlayerObject(ctx);

            java.util.ArrayList itemsList = new java.util.ArrayList();
            for (Iterator iter = Mappings.getPlayerItems(po).iterator(); iter.hasNext();) {
                itemsList.add(iter.next());
            }
            Iterator iter = itemsList.iterator();
            Random rand = new Random();
            String knightName = Mappings.getKnightName(po);
            while (iter.hasNext()) {
                Object obj = iter.next();
                if (!Mappings.isLevelItem(obj))
                    continue;
                String itemName = Mappings.getItemName(obj);
                int rawLevel = Mappings.getItemRawLevel(obj);
                boolean heatReady = Mappings.isHeatReady(obj);
                if (rawLevel >= 9)
                    continue;
                if (!heatReady) {
                    continue;
                }
                Object cfgManager = Mappings.getConfigManager(ctx);
                int maxCrystals = Mappings.getForgeMaxCrystals(obj, cfgManager);
                if (maxCrystals <= 0) {
                    continue;
                }

                long itemOid = Mappings.getItemOid(obj);

                Object clientManager = Mappings.getClientManager(ctx);
                Object itemService = Mappings.getItemService(clientManager);
                final boolean[] responseReceived = { false };
                final Object[] forgeResult = { null };
                Object listener = Mappings.createForgeResultListenerProxy(responseReceived, forgeResult);
                // Heat with the maximum (3x) crystals, except at in-game level 8
                // (rawLevel 7), where a single crystal (1x) is used instead.
                int crystals = (rawLevel == 7) ? maxCrystals / 3 : maxCrystals;
                Mappings.callForgeServiceDirect(itemService, itemOid, crystals, rawLevel, listener);
                int waitLoops = 0;
                while (!responseReceived[0] && waitLoops < 60) {
                    Thread.sleep(50);
                    waitLoops++;
                }
                Thread.sleep(75 + rand.nextInt(75));

                Object result = forgeResult[0];
                ForgeTracker.recordForge(
                        knightName, itemName, itemOid, rawLevel + 1,
                        Mappings.extractLevelAfter(result, rawLevel + 1),
                        Mappings.extractForgeDoubleLevel(result),
                        Mappings.extractForgeHeatBonus(result),
                        Mappings.extractForgeBox(result),
                        crystals,
                        Mappings.getCrystalName(Mappings.getItemConfigRarity(Mappings.getItemConfig(obj, cfgManager))),
                        ForgeTracker.dumpResult(result));
            }
            try {
                Thread.sleep(1500);
            } catch (Exception e) {
            }

            autoRestock(ctx, po);
        } catch (Throwable t) {
            ForgeTracker.debug("FAA ERROR: " + t);
        } finally {
            SocketInputState.isAutoForging = false;
        }
    }

    public static void manualRestock(Object ctx) {
        new Thread(() -> {
            try {
                autoRestock(ctx, Mappings.getPlayerObject(ctx));
            } catch (Exception e) {
            }
        }).start();
    }

    public static void manualCoordEquip(Object ctx) {
        if (ctx == null) {
            ForgeTracker.debug("manualCoordEquip: ctx is null, aborting");
            return;
        }
        new Thread(() -> {
            try {
                Object po = Mappings.getPlayerObject(ctx);
                int ownMask = Mappings.computeAvailableLevelMask(ctx, po);
                ForgeTracker.debug("manualCoordEquip: ownMask=" + Integer.toBinaryString(ownMask));

                DatagramSocket ds = new DatagramSocket();
                ds.setSoTimeout(200);
                int returnPort = ds.getLocalPort();
                ForgeTracker.debug("manualCoordEquip: returnPort=" + returnPort + " sending COORD_QUERY");

                byte[] query = ("COORD_QUERY " + returnPort).getBytes(StandardCharsets.UTF_8);
                InetAddress lo = InetAddress.getByName("127.0.0.1");
                for (int p = SKConfig.BASE_PORT; p <= SKConfig.BASE_PORT + SKConfig.MAX_ALTS; p++) {
                    ds.send(new DatagramPacket(query, query.length, lo, p));
                }

                int combined = ownMask;
                int replies = 0;
                byte[] buf = new byte[32];
                long deadline = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        ds.receive(pkt);
                        String reply = new String(pkt.getData(), 0, pkt.getLength(),
                                StandardCharsets.UTF_8).trim();
                        String[] parts = reply.split("\\s+");
                        if (parts.length == 2 && "COORD_REPLY".equals(parts[0])) {
                            int m = Integer.parseInt(parts[1]);
                            combined &= m;
                            replies++;
                            ForgeTracker.debug("manualCoordEquip: reply#" + replies
                                    + " mask=" + Integer.toBinaryString(m)
                                    + " combined=" + Integer.toBinaryString(combined));
                        }
                    } catch (SocketTimeoutException e) {
                        /* no reply in 200ms window */ }
                }
                ds.close();

                int target = -1;
                for (int L = 8; L >= 0; L--) {
                    if ((combined & (1 << L)) != 0) {
                        target = L;
                        break;
                    }
                }
                ForgeTracker.debug("manualCoordEquip: replies=" + replies
                        + " combined=" + Integer.toBinaryString(combined) + " target=" + target);
                if (target < 0)
                    return;
                Mappings.equipTargetLevel(ctx, po, target);
            } catch (Exception e) {
                ForgeTracker.debug("manualCoordEquip ERROR: " + e);
            }
        }, "SK CoordEquip").start();
    }

    public static void autoRestock(Object ctx, Object po) {
        try {
            long[] equippedOids = Mappings.getPlayerEquipment(po);

            java.util.Set<Long> newlyEquippedOids = new java.util.HashSet<Long>();
            int[] allowedSlots = { 0, 1, 2, 6, 7, 8, 9 };

            for (int slot : allowedSlots) {
                long currentOid = equippedOids[slot];
                if (currentOid <= 0)
                    continue;

                Object eqLi = null;
                for (Object itemObj : Mappings.getPlayerItems(po)) {
                    if (Mappings.isLevelItem(itemObj)) {
                        if (Mappings.getItemOid(itemObj) == currentOid) {
                            eqLi = itemObj;
                            break;
                        }
                    }
                }
                if (eqLi == null)
                    continue;

                if (Mappings.getItemRawLevel(eqLi) < 9)
                    continue;

                // Any rarity: a same-NAME fresh copy is automatically the same star level.
                // (Was 0-star only; generalized for the mission cycle's auto pass.)
                for (Object obj : Mappings.getPlayerItems(po)) {
                    if (!Mappings.isLevelItem(obj))
                        continue;

                    if (Mappings.getItemName(obj).equals(Mappings.getItemName(eqLi))
                            && Mappings.getItemRawLevel(obj) == 0) {
                        boolean isEquipped = false;
                        if (newlyEquippedOids.contains(Mappings.getItemOid(obj))) {
                            isEquipped = true;
                        }

                        for (long eOid : Mappings.getPlayerEquipment(po)) {
                            if (eOid == Mappings.getItemOid(obj)) {
                                isEquipped = true;
                                break;
                            }
                        }

                        if (!isEquipped) {
                            newlyEquippedOids.add(Mappings.getItemOid(obj));

                            Object clientManager = Mappings.getClientManager(ctx);
                            Object itemService = Mappings.getItemService(clientManager);

                            final boolean[] done = { false };
                            Object listener = Mappings.createConfirmListenerProxy(done, "Equip");
                            Mappings.callEquipService(itemService, Mappings.getItemOid(obj), slot, listener);

                            int waitLoops = 0;
                            while (!done[0] && waitLoops < 60) {
                                try {
                                    Thread.sleep(50);
                                } catch (Exception e) {
                                }
                                waitLoops++;
                            }
                            try {
                                Thread.sleep(75 + new Random().nextInt(75));
                            } catch (Exception e) {
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
    }
}

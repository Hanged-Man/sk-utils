package com.threerings.opengl.gui;

import java.io.File;
import java.io.FileWriter;

public class ForgeTracker {

    private static final String DIR        = System.getProperty("user.home") + "/.sk-utils";
    private static final String FORGE_FILE = DIR + "/forges.jsonl";

    public static void recordForge(String knight, String itemName, long itemOid,
                                   int levelBefore, int levelAfter,
                                   boolean doubleLevel, boolean heatBonus,
                                   int forgeBoxes, int crystals, String crystalName,
                                   String resultRaw) {
        debug("recordForge: " + itemName + " oid=" + itemOid);
        // An UNPERFORMED forge (server no-op) must not create a row: it arrives with a
        // null result — so level_after falls back to level_before — and 11k+ such rows
        // had to be cleansed from the log because they poisoned every rate computed
        // from it (user-directed 2026-08-07). Both tells are checked, belt and braces:
        // the null result is the direct signal; an unchanged level can't be a real
        // forge either (a performed forge always gains at least one level).
        if (resultRaw == null || "null".equals(resultRaw) || levelAfter <= levelBefore) {
            debug("recordForge: SKIPPED no-op forge on " + itemName + " (null result / level unchanged)");
            return;
        }
        try {
            new File(DIR).mkdirs();
            String line = "{" +
                "\"ts\":"            + System.currentTimeMillis() + "," +
                "\"knight\":"        + jsonStr(knight)      + "," +
                "\"item_name\":"     + jsonStr(itemName)    + "," +
                "\"item_oid\":"      + itemOid              + "," +
                "\"level_before\":"  + levelBefore          + "," +
                "\"level_after\":"   + levelAfter           + "," +
                "\"double_level\":"  + doubleLevel          + "," +
                "\"heat_bonus\":"    + heatBonus            + "," +
                "\"forge_boxes\":"   + forgeBoxes           + "," +
                "\"crystals\":"      + crystals             + "," +
                "\"crystal_name\":"  + jsonStr(crystalName) + "," +
                "\"result_raw\":"    + jsonStr(resultRaw)   +
                "}";
            FileWriter fw = new FileWriter(FORGE_FILE, true);
            fw.write(line + "\n");
            fw.close();
            debug("recordForge: written ok");
        } catch (Throwable t) { debug("recordForge ERROR: " + t); }
    }

    public static void debug(String msg) {
        if (!SocketInputState.debug)
            return; // silent unless debug logging is enabled (shares SocketInputState's flag)
        try {
            new File(DIR).mkdirs();
            FileWriter fw = new FileWriter(DIR + "/debug.log", true);
            fw.write(new java.util.Date() + "  " + msg + "\n");
            fw.close();
        } catch (Exception e) {}
    }

    /** Reflection-dump of the forge result object — stored so we can discover the bonus schema. */
    public static String dumpResult(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return (String) obj;
        StringBuilder sb = new StringBuilder(obj.getClass().getName()).append("{");
        for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            try { sb.append(f.getName()).append("=").append(f.get(obj)).append(";"); }
            catch (Exception e) { sb.append(f.getName()).append("=ERR;"); }
        }
        return sb.append("}").toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}

package com.threerings.opengl.gui;

/**
 * Runtime configuration loaded from ~/.sk-utils/config.properties.
 * Compiled before Patcher runs so injected bytecode can reference it. Every
 * configurable field's initializer is a METHOD CALL on purpose — a constant
 * initializer would be inlined into injected code at patch time (the Shadow
 * Clone Jutsu SKConfig-stub bug) and into sibling classes at javac time,
 * freezing the value into the shipped zip.
 *
 * config.properties keys (all optional except main_account):
 *   main_account=YourMainKnightName   KNIGHT name of the controlling account
 *   party_size=4                      total accounts (main + alts) the cycle waits for
 *   udp_base_port=40000               first port of the loopback control plane
 *   install_dir=...                   build-time only (read by build.ps1, never the mod)
 *
 * If the file does not exist a commented template is seeded, so a zip-only
 * install (recipient never runs build.ps1) gets a file to edit.
 */
public class SKConfig {
    /** Alt listener ports = BASE_PORT+1 .. BASE_PORT+MAX_ALTS. */
    public static final int MAX_ALTS = 20;

    private static final java.util.Properties PROPS = load();

    public static final String MAIN_ACCOUNT = PROPS.getProperty("main_account", "").trim();
    public static final int PARTY_SIZE = intProp("party_size", 4);
    public static final int BASE_PORT = intProp("udp_base_port", 40000);

    private static int intProp(String key, int dflt) {
        try {
            String v = PROPS.getProperty(key);
            return (v == null || v.trim().isEmpty()) ? dflt : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static java.util.Properties load() {
        java.util.Properties p = new java.util.Properties();
        try {
            java.io.File cfg = new java.io.File(
                    System.getProperty("user.home") + "/.sk-utils/config.properties");
            if (!cfg.exists()) {
                seedTemplate(cfg);
                return p;
            }
            try (java.io.FileInputStream fis = new java.io.FileInputStream(cfg)) {
                p.load(fis);
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private static void seedTemplate(java.io.File cfg) {
        try {
            cfg.getParentFile().mkdirs();
            try (java.io.PrintWriter w = new java.io.PrintWriter(cfg, "UTF-8")) {
                w.println("# sk-utils configuration");
                w.println("# main_account: the KNIGHT (character) name of your main/controlling account.");
                w.println("main_account=");
                w.println();
                w.println("# party_size: total multibox accounts (main + alts). The mission cycle waits");
                w.println("# for this many knights on every floor before starting it. Default 4.");
                w.println("#party_size=4");
                w.println();
                w.println("# udp_base_port: first port of the mod's local (127.0.0.1) control plane. The");
                w.println("# main listens here, alts take the next " + MAX_ALTS + ". multibox.py reads this file");
                w.println("# too, so both sides stay in step. Change only on a port conflict. Default 40000.");
                w.println("#udp_base_port=40000");
                w.println();
                w.println("# install_dir: which game install build.ps1 patches against (build-time only).");
                w.println("#install_dir=C:\\path\\to\\Spiral Knights");
            }
        } catch (Exception ignored) {
        }
    }
}

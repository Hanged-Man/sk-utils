import javassist.*;
import javassist.expr.*;
import modutils.ClassFinder;
import modutils.MemberFinder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bytecode patcher for sk-utils-mod.
 *
 * Usage (modutils-compatible):
 * java -cp "javassist.jar:out:." Patcher <pcode_jar> <out_dir>
 *
 * FLOW:
 * 1. Resolve all volatile class names via ClassFinder (structural match, name
 * fallback).
 * 2. Discover all obfuscated field/method names via MemberFinder
 * (type/signature match).
 * 3. Patch game classes.
 * 4. Inject all discovered names into Mappings.<clinit> so runtime picks them
 * up.
 *
 * After a game reobfuscation the build should succeed without any manual
 * constant updates,
 * failing only if the game's actual structure changes (new/removed fields,
 * different signatures).
 */
public class Patcher {

        // ── Our own classes (stable, never obfuscated) ────────────────────────────
        private static final String SOCKET_INPUT_STATE = "com.threerings.opengl.gui.SocketInputState";
        private static final String SK_CONFIG = "com.threerings.opengl.gui.SKConfig";
        private static final String FORGE_ALL_ADAPTER = "com.threerings.projectx.item.client.ForgeAllAdapter";
        private static final String MAPPINGS = "com.threerings.opengl.gui.Mappings";
        private static final String FORGE_TRACKER = "com.threerings.opengl.gui.ForgeTracker";
        // ── Stable framework class names (@Keep / non-obfuscated) ─────────────────
        private static final String CONFIG_MANAGER_CLASS = "com.threerings.config.ConfigManager";
        private static final String PLACE_OBJECT_CLASS = "com.threerings.crowd.data.PlaceObject";
        private static final String ACTOR_CLASS = "com.threerings.tudey.data.actor.Actor";
        private static final String RESULT_LISTENER_CLASS = "com.threerings.presents.client.A$c";
        private static final String CONFIRM_LISTENER_CLASS = "com.threerings.presents.client.A$a";
        private static final String KEY_EVENT_CLASS = "com.threerings.opengl.gui.event.KeyEvent";
        // PartyNotification has a $ in its name (inner class) — its outer class name is
        // stable
        private static final String PARTY_NOTIFICATION = "com.threerings.projectx.social.data.Notification$PartyNotification";

        /**
         * projectx-pcode.jar under $SK_INSTALL_DIR, for hand-runs with no explicit
         * argument. Fails with the usage rather than guessing an install location.
         */
        private static String jarFromEnv() {
                String install = System.getenv("SK_INSTALL_DIR");
                if (install != null && !install.trim().isEmpty()) {
                        java.io.File jar = new java.io.File(new java.io.File(install.trim(), "code"),
                                        "projectx-pcode.jar");
                        if (jar.isFile())
                                return jar.getPath();
                        throw new IllegalStateException("SK_INSTALL_DIR has no code/projectx-pcode.jar: " + install);
                }
                throw new IllegalStateException("usage: Patcher <path to projectx-pcode.jar> [outDir]"
                                + "  (or set SK_INSTALL_DIR; build.ps1 normally passes the resolved path)");
        }

        public static void main(String[] args) throws Exception {
                // build.ps1 always passes the jar it resolved. Running Patcher by hand
                // falls back to SK_INSTALL_DIR; nothing is hardcoded to one machine.
                String pcodeJar = (args.length > 0) ? args[0] : jarFromEnv();
                String outDir = args.length > 1 ? args[1] : "out";

                // ── 1. Resolve all volatile obfuscated class names ────────────────────
                // findOrByName returns the known name if it still exists; otherwise the
                // structural
                // criteria narrow the search to one match. findUnique() requires criteria to
                // match
                // exactly one class.

                String CHAT_DIRECTOR = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.crowd.chat.client")
                                .findOrByName("com.threerings.crowd.chat.client.b");

                String CHAT_MESSAGE_CLASS = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.crowd.chat.client")
                                .findOrByName("com.threerings.crowd.chat.client.m");

                String INPUT_STATE_CLASS = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.opengl.gui")
                                .findOrByName("com.threerings.opengl.gui.t");

                // wasAdded/wasRemoved are stable interface methods — reliable structural anchor
                String FORGE_WINDOW = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.item.client")
                                .hasMethod("wasAdded", "()V")
                                .hasMethod("wasRemoved", "()V")
                                .findOrByName("com.threerings.projectx.item.client.n");

                String O_CLASS = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.opengl.gui")
                                .findOrByName("com.threerings.opengl.gui.o");

                // getConfigManager is @Keep — its exact JVM descriptor is a stable structural
                // anchor
                String CTX_CLASS = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.util")
                                .hasMethod("getConfigManager", "()Lcom/threerings/config/ConfigManager;")
                                .findOrByName("com.threerings.projectx.util.w");

                // tick(float) is the standard Tudey update loop entry point — stable descriptor
                String TUDEY_CONTROLLER = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.tudey.a")
                                .hasMethod("tick", "(F)V")
                                .findOrByName("com.threerings.tudey.a.a");

                String DUNGEON_CLIENT = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.dungeon.client")
                                .findOrByName("com.threerings.projectx.dungeon.client.m");

                // The READY-ROOM DIRECTOR (was client.dB, became client.dD in the 2026-07-30
                // re-obfuscation). Identified by the UNOBFUSCATED type of a field it holds:
                // whirled ZoneSummary. Return-type-name matching is what broke.
                String READY_ROOM_DIRECTOR = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.client")
                                .superClass("com.threerings.whirled.zone.a.b")
                                .hasMethodDescriptorFragment("Lcom/threerings/whirled/zone/data/ZoneSummary;")
                                .findUnique(); // NOT findOrByName: client.dB still exists post-rename, as another class

                // The class whose wasRemoved() spams "Dismissing left-open window" (was
                // client.dx, became client.dz). The LOG STRING is the fingerprint — it is
                // unobfuscated and unique jar-wide, in both old and new builds.
                String DISMISS_LOG_CLASS = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.client")
                                .hasStringConstant("Dismissing left-open window")
                                .findUnique(); // NOT findOrByName: client.dx still exists post-rename, as another class

                String TUDEY_VIEW = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.tudey.a")
                                .findOrByName("com.threerings.tudey.a.i");

                String ACTOR_WRAPPER = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.tudey.a.b")
                                .findOrByName("com.threerings.tudey.a.b.a");

                String LEVEL_ITEM_CLASS = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.item.data")
                                .classNameEndsWith("LevelItem")
                                .findOrByName("com.threerings.projectx.item.data.LevelItem");

                String FORGE_LISTENER = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.item.client")
                                .findOrByName("com.threerings.projectx.item.client.o");

                // ItemService: interface with both forge (long,int,ResultListener) and equip
                // (long,int,ConfirmListener) methods.
                // Descriptor fragments are name-independent so they survive method renames.
                String ITEM_SERVICE_CLASS = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.item.client")
                                .hasMethodDescriptorFragment("JIL" + RESULT_LISTENER_CLASS.replace('.', '/') + ";")
                                .hasMethodDescriptorFragment("JIL" + CONFIRM_LISTENER_CLASS.replace('.', '/') + ";")
                                .findOrByName("com.threerings.projectx.item.client.L");

                // HUD overlay: find the Window base class (aD) by structural anchors
                String GUI_WINDOW = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.opengl.gui")
                                .hasMethod("isOverlay", "()Z")
                                .hasMethod("pack", "()V")
                                .findOrByName("com.threerings.opengl.gui.aD");

                // ── 2. Build ClassPool and discover all obfuscated member names ───────

                ClassPool pool = ClassPool.getDefault();
                pool.insertClassPath(pcodeJar);

                // Register stub CtClasses for our mod classes so Javassist's compiler can
                // resolve
                // references to them in injection templates. The stubs are never written to
                // disk;
                // the real implementations are compiled in a later build phase.
                registerModClassStubs(pool);

                // Solid-color background class: the class in gui.a.* that has a Color4f field
                String GUI_COLOR_BG = null;
                for (String c : new String[] { "a", "b", "c", "d", "e", "f", "g", "h" }) {
                        String cls = "com.threerings.opengl.gui.a." + c;
                        try {
                                CtClass bg = pool.get(cls);
                                for (CtField f : bg.getDeclaredFields()) {
                                        try {
                                                if ("com.threerings.opengl.renderer.Color4f"
                                                                .equals(f.getType().getName())) {
                                                        GUI_COLOR_BG = cls;
                                                        break;
                                                }
                                        } catch (Exception ignored2) {
                                        }
                                }
                                if (GUI_COLOR_BG != null)
                                        break;
                        } catch (Exception ignored) {
                        }
                }
                if (GUI_COLOR_BG == null)
                        throw new RuntimeException("Cannot find solid-color background class in gui.a.*");

                // Create HeatHudWindow: a persistent overlay window for the gear HUD.
                // It extends the obfuscated aD (Window) class so it participates in the
                // normal Root rendering pipeline without any extra injection.
                CtClass hudWinClass = pool.makeClass("com.threerings.opengl.gui.HeatHudWindow");
                hudWinClass.setSuperclass(pool.get(GUI_WINDOW));
                hudWinClass.addConstructor(CtNewConstructor.make(
                                new CtClass[] { pool.get("com.threerings.opengl.util.d") },
                                new CtClass[0],
                                "{ super($1, null); }",
                                hudWinClass));
                hudWinClass.addMethod(CtNewMethod.make(
                                "public boolean isOverlay() { return true; }", hudWinClass));
                // Completely click-transparent: Root.updateHoverComponent calls
                // window.getHitComponent()
                // and only stops searching if it returns non-null. Returning null here causes
                // the Root
                // to skip this window and check the game's HUD windows behind it.
                hudWinClass.addMethod(CtNewMethod.make(
                                "public com.threerings.opengl.gui.o getHitComponent(int x, int y) { return null; }",
                                hudWinClass));
                hudWinClass.addMethod(CtNewMethod.make(
                                "public boolean hitTest(int x, int y) { return false; }", hudWinClass));
                hudWinClass.addMethod(CtNewMethod.make(
                                "protected void layoutWindow(int w, int h) {\n" +
                                                "    this.setBounds(0, h - 84, w, 84);\n" +
                                                "    com.threerings.opengl.gui.HeatHudPanel.onLayout(this, w, h);\n" +
                                                "}",
                                hudWinClass));
                hudWinClass.addMethod(CtNewMethod.make(
                                "protected void wasRemoved() {\n" +
                                                "    super.wasRemoved();\n" +
                                                "    com.threerings.opengl.gui.HeatHudPanel.onWindowRemoved();\n" +
                                                "}",
                                hudWinClass));
                hudWinClass.writeFile(outDir);

                // Create HeatSyncBtnWindow: tiny 68x21 overlay window that holds only the SYNC
                // button.
                // Its natural bounds equal the button area, so no custom hitTest is needed —
                // Clyde's
                // default Container.hitTest correctly covers only those 68x21 pixels.
                CtClass hudSyncWinClass = pool.makeClass("com.threerings.opengl.gui.HeatSyncBtnWindow");
                hudSyncWinClass.setSuperclass(pool.get(GUI_WINDOW));
                hudSyncWinClass.addConstructor(CtNewConstructor.make(
                                new CtClass[] { pool.get("com.threerings.opengl.util.d") },
                                new CtClass[0],
                                "{ super($1, null); }",
                                hudSyncWinClass));
                hudSyncWinClass.addMethod(CtNewMethod.make(
                                "public boolean isOverlay() { return true; }", hudSyncWinClass));
                hudSyncWinClass.addMethod(CtNewMethod.make(
                                "protected void layoutWindow(int w, int h) {\n" +
                                                "    int btnX = com.threerings.opengl.gui.SocketInputState.hudBtnX;\n" +
                                                "    if (btnX < 0) return;\n" +
                                                // Button row (yOff=0) in HeatHudWindow: screen Y-UP h-84 to h-42.
                                                // Button centered in that row: y = h-84 + (42-21)/2 = h-74.
                                                "    this.setBounds(btnX, h - 74, 68, 21);\n" +
                                                "}",
                                hudSyncWinClass));
                hudSyncWinClass.addMethod(CtNewMethod.make(
                                "protected void wasRemoved() {\n" +
                                                "    super.wasRemoved();\n" +
                                                "    com.threerings.opengl.gui.HeatHudPanel.onBtnWindowRemoved();\n" +
                                                "}",
                                hudSyncWinClass));
                hudSyncWinClass.writeFile(outDir);

                // DamageMeter stub (real class compiles later): referenced by the generated
                // DamageMeterWindow below AND by the DamageEvent instrumentation.
                CtClass dmStub = pool.makeClass("com.threerings.opengl.gui.DamageMeter");
                dmStub.addMethod(CtNewMethod.make(
                                "public static void recordDamageEvent(String name, int src, int actor, int amount) {}",
                                dmStub));
                dmStub.addMethod(CtNewMethod.make(
                                "public static void onLayout(Object win, int w, int h) {}", dmStub));
                dmStub.addMethod(CtNewMethod.make(
                                "public static void onWindowRemoved() {}", dmStub));

                // DamageMeterWindow: the DPS-breakdown card overlay (202px tooltip-style
                // panel). Click-transparent like HeatHudWindow; DamageMeter positions it.
                CtClass dmWinClass = pool.makeClass("com.threerings.opengl.gui.DamageMeterWindow");
                dmWinClass.setSuperclass(pool.get(GUI_WINDOW));
                dmWinClass.addConstructor(CtNewConstructor.make(
                                new CtClass[] { pool.get("com.threerings.opengl.util.d") },
                                new CtClass[0],
                                "{ super($1, null); }",
                                dmWinClass));
                dmWinClass.addMethod(CtNewMethod.make(
                                "public boolean isOverlay() { return true; }", dmWinClass));
                dmWinClass.addMethod(CtNewMethod.make(
                                "public com.threerings.opengl.gui.o getHitComponent(int x, int y) { return null; }",
                                dmWinClass));
                dmWinClass.addMethod(CtNewMethod.make(
                                "public boolean hitTest(int x, int y) { return false; }", dmWinClass));
                dmWinClass.addMethod(CtNewMethod.make(
                                "protected void layoutWindow(int w, int h) {"
                                                + " com.threerings.opengl.gui.DamageMeter.onLayout(this, w, h); }",
                                dmWinClass));
                dmWinClass.addMethod(CtNewMethod.make(
                                "protected void wasRemoved() { super.wasRemoved();"
                                                + " com.threerings.opengl.gui.DamageMeter.onWindowRemoved(); }",
                                dmWinClass));
                dmWinClass.writeFile(outDir);

                // Collect every resolved name so we can inject them all into Mappings at once.
                // We store both class names AND field/method name values here.
                Map<String, String> discovered = new LinkedHashMap<String, String>();

                // Class names (runtime Mappings reads these, not final so not inlined)
                discovered.put("CHAT_DIRECTOR", CHAT_DIRECTOR);
                discovered.put("CHAT_MESSAGE_CLASS", CHAT_MESSAGE_CLASS);
                discovered.put("INPUT_STATE_CLASS", INPUT_STATE_CLASS);
                discovered.put("FORGE_WINDOW", FORGE_WINDOW);
                discovered.put("O_CLASS", O_CLASS);
                discovered.put("PN_ACCEPT_METHOD_ARRAY_CLASS", "[L" + O_CLASS + ";");
                discovered.put("CTX_CLASS", CTX_CLASS);
                discovered.put("TUDEY_CONTROLLER", TUDEY_CONTROLLER);
                discovered.put("DUNGEON_CLIENT", DUNGEON_CLIENT);

                // ── Obfuscated METHOD names, discovered structurally (2026-07-30) ───────────
                // These were hardcoded ("aq"/"dU"/"dW"/"IT") and 3 of the 4 broke when the game
                // re-obfuscated: the dungeon client's d-block shifted dU/dV/dW -> dX/dY/dZ and
                // the ready-room director's IT -> IX. Each is now found by something SEMANTIC
                // that obfuscation cannot touch, and baked into MappingsNames at build time.
                //
                //   press   — the only (II)V on the dungeon client (queues a sprite action)
                //   release — the only PUBLIC (I)V on it (the press's dedup-key release)
                //   weapon  — the (I)V that touches DungeonInputFrame (an UNOBFUSCATED class);
                //             its sibling (I)V works on a Multimap, so the body ref separates them
                //   rr      — the ()V on the ready-room director that reads ZoneSummary.zoneId
                String SPRITE_PRESS_METHOD = uniqueMethodByDescriptor(pool, DUNGEON_CLIENT, "(II)V", false, "aq");
                String SPRITE_RELEASE_METHOD = uniqueMethodByDescriptor(pool, DUNGEON_CLIENT, "(I)V", true, "dU");
                String WEAPON_SELECT_METHOD = methodByBodyRef(pool, DUNGEON_CLIENT, "(I)V",
                                "DungeonInputFrame", "dW");
                String READY_ROOM_RETURN_METHOD = methodByBodyRef(pool, READY_ROOM_DIRECTOR, "()V",
                                "ZoneSummary", "IT");
                String[] dungeonDir = accessorByReturnPackage(pool, CTX_CLASS,
                                "com.threerings.projectx.dungeon.client",
                                new String[] { "GD", "com.threerings.projectx.dungeon.client.S" });
                discovered.put("SPRITE_PRESS_METHOD", SPRITE_PRESS_METHOD);
                discovered.put("SPRITE_RELEASE_METHOD", SPRITE_RELEASE_METHOD);
                discovered.put("WEAPON_SELECT_METHOD", WEAPON_SELECT_METHOD);
                discovered.put("READY_ROOM_DIRECTOR_CLASS", READY_ROOM_DIRECTOR);
                discovered.put("READY_ROOM_RETURN_METHOD", READY_ROOM_RETURN_METHOD);
                discovered.put("DUNGEON_DIRECTOR_METHOD", dungeonDir[0]);
                discovered.put("DUNGEON_DIRECTOR_CLASS", dungeonDir[1]);
                discovered.put("DISMISS_LOG_CLASS", DISMISS_LOG_CLASS);
                System.out.println("  sprite press/release = " + SPRITE_PRESS_METHOD + "/" + SPRITE_RELEASE_METHOD
                                + "   weapon select = " + WEAPON_SELECT_METHOD);
                System.out.println("  dungeon director = " + dungeonDir[1] + " via ctx." + dungeonDir[0] + "()");
                System.out.println("  ready room = " + READY_ROOM_DIRECTOR + "." + READY_ROOM_RETURN_METHOD
                                + "()   dismiss-log class = " + DISMISS_LOG_CLASS);
                discovered.put("TUDEY_VIEW", TUDEY_VIEW);
                discovered.put("ACTOR_WRAPPER", ACTOR_WRAPPER);
                discovered.put("LEVEL_ITEM_CLASS", LEVEL_ITEM_CLASS);
                discovered.put("FORGE_LISTENER", FORGE_LISTENER);
                discovered.put("ITEM_SERVICE_CLASS", ITEM_SERVICE_CLASS);
                discovered.put("GUI_WINDOW_CLASS", GUI_WINDOW);
                discovered.put("GUI_COLOR_BG_CLASS", GUI_COLOR_BG);

                // ── ForgeWindow members ───────────────────────────────────────────────
                CtClass forgeWindowClass = pool.get(FORGE_WINDOW);

                // Button field: type has doClick() — doClick is only on the button subclass,
                // not the base o.
                // isEnabled() is on o itself, which causes fieldByTypeMethod to match any
                // o-typed field first.
                String forgeBtnField = MemberFinder.resolveField("FORGE_BTN_FIELD", forgeWindowClass,
                                cls -> MemberFinder.fieldByTypeMethod(cls, "doClick", "()"));

                String forgeItemField = MemberFinder.resolveField("FORGE_ITEM_FIELD", forgeWindowClass,
                                cls -> MemberFinder.fieldByTypeSuffix(cls, "LevelItem"));

                // Upgrade method: void no-arg that isn't the lifecycle callbacks or lambda
                String forgeUpgradeMethod = MemberFinder.resolveMethod("FORGE_UPGRADE_METHOD", forgeWindowClass,
                                cls -> MemberFinder.noArgVoidMethod(cls, "wasAdded", "wasRemoved", "lambda$new$0"));

                // Level field: first int field, excluding the already-found reference fields
                String forgeLevelField = MemberFinder.resolveField("FORGE_LEVEL_FIELD", forgeWindowClass,
                                cls -> MemberFinder.firstIntField(cls, forgeBtnField, forgeItemField));

                String forgeBtnTypeName = forgeWindowClass.getDeclaredField(forgeBtnField).getType().getName();

                discovered.put("FORGE_BTN_FIELD", forgeBtnField);
                discovered.put("FORGE_ITEM_FIELD", forgeItemField);
                discovered.put("FORGE_UPGRADE_METHOD", forgeUpgradeMethod);
                discovered.put("FORGE_LEVEL_FIELD", forgeLevelField);

                // ForgeListener panel field: back-reference to the ForgeWindow
                CtClass forgeListenerClass = pool.get(FORGE_LISTENER);
                String forgeListenerPanelField = MemberFinder.resolveField("FORGE_LISTENER_PANEL_FIELD",
                                forgeListenerClass,
                                cls -> MemberFinder.fieldByType(cls, FORGE_WINDOW));
                discovered.put("FORGE_LISTENER_PANEL_FIELD", forgeListenerPanelField);

                // ── PartyNotification members ─────────────────────────────────────────
                CtClass pnClass = pool.get(PARTY_NOTIFICATION);
                // The base Notification class holds the accept-action method (not overridden in
                // subclass)
                CtClass notifBaseClass = pool.get("com.threerings.projectx.social.data.Notification");
                String oClassInternal = O_CLASS.replace('.', '/');
                String ctxClassInternal = CTX_CLASS.replace('.', '/');

                // Inviter method: no-arg, returns a Name-like object
                String pnInviterMethod = MemberFinder.resolveMethod("PN_INVITER_METHOD", pnClass,
                                cls -> MemberFinder.noArgMethodByReturnSuffix(cls, "Name"));

                // Accept method: lives on the base Notification class; takes (CTX, int, o[])
                String pnAcceptMethod = MemberFinder.resolveMethod("PN_ACCEPT_METHOD", notifBaseClass,
                                cls -> MemberFinder.methodByParamsDescriptor(cls,
                                                "(L" + ctxClassInternal + ";I[L" + oClassInternal + ";)"));

                // Update method (inject point): W(w) — takes CTX, returns o — called when
                // notification is displayed
                String pnUpdateMethod = MemberFinder.resolveMethod("PN_UPDATE_METHOD", pnClass,
                                cls -> MemberFinder.methodByDescriptorFragment(cls,
                                                "(L" + ctxClassInternal + ";)L" + oClassInternal + ";"));

                discovered.put("PN_INVITER_METHOD", pnInviterMethod);
                discovered.put("PN_ACCEPT_METHOD", pnAcceptMethod);
                discovered.put("PN_UPDATE_METHOD", pnUpdateMethod);

                // ── ChatDirector message handler ──────────────────────────────────────
                CtClass chatDirClass = pool.get(CHAT_DIRECTOR);
                String chatMsgInternal = CHAT_MESSAGE_CLASS.replace('.', '/');
                // Match on the exact parameter list — return type may vary but params are
                // distinctive
                String chatDirMsgMethod = MemberFinder.resolveMethod("CHAT_DIRECTOR_MSG_METHOD", chatDirClass,
                                cls -> MemberFinder.methodByParamsDescriptor(cls,
                                                "(L" + chatMsgInternal + ";Ljava/lang/String;Z)"));
                discovered.put("CHAT_DIRECTOR_MSG_METHOD", chatDirMsgMethod);

                // ── LevelItem methods ─────────────────────────────────────────────────
                CtClass levelItemClass = pool.get(LEVEL_ITEM_CLASS);
                CtClass configMgrClass = pool.get(CONFIG_MANAGER_CLASS);
                // Some methods (oid, name) live on the Item base class, not on LevelItem
                // directly
                CtClass itemBaseClass = pool.get("com.threerings.projectx.item.data.Item");

                String itemUpgradable = MemberFinder.resolveMethod("ITEM_UPGRADABLE_METHOD", levelItemClass,
                                cls -> MemberFinder.methodByReturnAndParams(cls, CtClass.booleanType, configMgrClass));
                String itemOid = MemberFinder.resolveMethod("ITEM_OID_METHOD", levelItemClass,
                                cls -> MemberFinder.methodByReturnAndParamsIncludingInherited(cls, CtClass.longType));
                // getName is on Item parent — search it directly for the no-arg String method
                String itemName = MemberFinder.resolveMethod("ITEM_NAME_METHOD", levelItemClass,
                                cls -> {
                                        for (CtMethod m : itemBaseClass.getDeclaredMethods()) {
                                                try {
                                                        if (m.getParameterTypes().length == 0
                                                                        && "java.lang.String".equals(
                                                                                        m.getReturnType().getName())
                                                                        && !"toString".equals(m.getName())) {
                                                                return m.getName();
                                                        }
                                                } catch (Exception ignored) {
                                                }
                                        }
                                        return null;
                                });
                String itemLevel = MemberFinder.resolveMethod("ITEM_LEVEL_METHOD", levelItemClass,
                                cls -> MemberFinder.methodReadingField(cls, "_level"));
                String itemConfig = MemberFinder.resolveMethod("ITEM_CONFIG_METHOD", levelItemClass,
                                cls -> MemberFinder.methodByParamTypes(cls, configMgrClass));

                discovered.put("ITEM_UPGRADABLE_METHOD", itemUpgradable);
                discovered.put("ITEM_OID_METHOD", itemOid);
                discovered.put("ITEM_NAME_METHOD", itemName);
                discovered.put("ITEM_LEVEL_METHOD", itemLevel);
                discovered.put("ITEM_CONFIG_METHOD", itemConfig);

                // ── Ctx class methods ─────────────────────────────────────────────────
                CtClass ctxClass = pool.get(CTX_CLASS);

                String playerObjMethod = MemberFinder.resolveMethod("PLAYER_OBJECT_METHOD", ctxClass,
                                cls -> MemberFinder.noArgMethodByReturnSuffix(cls, "PlayerObject"));
                // getClient() is on the PresentsContext parent interface — search inherited via
                // getMethods()
                // Return type is com.threerings.presents.client.Client (stable, non-obfuscated)
                String clientMgrMethod = MemberFinder.resolveMethod("CLIENT_MANAGER_METHOD", ctxClass,
                                cls -> MemberFinder.noArgMethodByReturnSuffix(cls, "presents.client.Client"));
                String ctxSteamDirMethod = MemberFinder.resolveMethod("CTX_STEAM_DIRECTOR_METHOD", ctxClass,
                                cls -> MemberFinder.noArgMethodByReturnSuffix(cls, "SteamDirector"));
                String ctxServerObjMethod = MemberFinder.resolveMethod("CTX_SERVER_OBJECT_METHOD", ctxClass,
                                cls -> MemberFinder.noArgMethodByReturnSuffix(cls, "ServerObject"));
                // Guild director: no-arg method returning a type in the guild.client package
                discovered.put("PLAYER_OBJECT_METHOD", playerObjMethod);
                discovered.put("CLIENT_MANAGER_METHOD", clientMgrMethod);
                discovered.put("CTX_STEAM_DIRECTOR_METHOD", ctxSteamDirMethod);
                discovered.put("CTX_SERVER_OBJECT_METHOD", ctxServerObjMethod);

                // Follow the return type of CLIENT_MANAGER_METHOD to find the service-provider
                // class.
                // The method is inherited (on PresentsContext), so search getMethods() not
                // getDeclaredMethod().
                CtClass clientMgrClass = null;
                for (CtMethod m : ctxClass.getMethods()) {
                        if (m.getName().equals(clientMgrMethod)) {
                                clientMgrClass = m.getReturnType();
                                break;
                        }
                }
                if (clientMgrClass == null)
                        throw new RuntimeException("Cannot find return type of " + clientMgrMethod);
                String svcProviderMethod = MemberFinder.resolveMethod("SERVICE_PROVIDER_METHOD", clientMgrClass,
                                cls -> MemberFinder.methodByParamTypes(cls, pool.get("java.lang.Class")));
                discovered.put("SERVICE_PROVIDER_METHOD", svcProviderMethod);

                // ── Social service class and join method ──────────────────────────────
                String SOCIAL_SERVICE = new ClassFinder(pcodeJar)
                                .packagePrefix("com.threerings.projectx.social.client")
                                .hasMethodDescriptorFragment(
                                                "(Lcom/threerings/util/Name;L"
                                                                + CONFIRM_LISTENER_CLASS.replace('.', '/') + ";)V")
                                .findUnique();

                discovered.put("SOCIAL_SERVICE_CLASS", SOCIAL_SERVICE);

                CtClass socialSvcClass = pool.get(SOCIAL_SERVICE);
                String confirmInt = CONFIRM_LISTENER_CLASS.replace('.', '/');
                // SocialMarshaller is stable-named; use its dispatch indices to tell join (low
                // index)
                // from unfriend (high index) — both share the same (Name, ConfirmListener)
                // signature.
                CtClass socialMarshallerClass = pool.get("com.threerings.projectx.social.data.SocialMarshaller");
                String namePlusConfirm = "(Lcom/threerings/util/Name;L" + confirmInt + ";)";
                String socialJoinMethod = MemberFinder.resolveMethod("SOCIAL_JOIN_METHOD", socialSvcClass,
                                cls -> MemberFinder.methodWithLowestFirstIntArg(socialMarshallerClass,
                                                namePlusConfirm));
                discovered.put("SOCIAL_JOIN_METHOD", socialJoinMethod);

                // ── Party invite method (invite-based lobby fill) ─────────────────────
                // PartyMarshaller is stable-named; its two (Name, ConfirmListener) methods
                // are boot (dispatch index 1) and INVITE (index 2) — verified via the UI
                // callers' string anchors ("m.booted" / "m.invited").
                CtClass partyMarshallerClass = pool.get("com.threerings.projectx.dungeon.data.PartyMarshaller");
                String partyInviteMethod = MemberFinder.resolveMethod("PARTY_INVITE_METHOD", partyMarshallerClass,
                                cls -> MemberFinder.methodWithFirstIntArg(partyMarshallerClass,
                                                namePlusConfirm, 2));
                discovered.put("PARTY_INVITE_METHOD", partyInviteMethod);

                // ── ItemService methods ───────────────────────────────────────────────
                CtClass itemSvcClass = pool.get(ITEM_SERVICE_CLASS);
                String resultInternal = RESULT_LISTENER_CLASS.replace('.', '/');
                String confirmInternal = CONFIRM_LISTENER_CLASS.replace('.', '/');

                // Forge: (long oid, int level, ResultListener)
                String svcForgeMethod = MemberFinder.resolveMethod("SERVICE_FORGE_METHOD", itemSvcClass,
                                cls -> MemberFinder.methodByDescriptorFragment(cls, "JIL" + resultInternal + ";"));
                // Equip: (long oid, int slot, ConfirmListener)
                String svcEquipMethod = MemberFinder.resolveMethod("SERVICE_EQUIP_METHOD", itemSvcClass,
                                cls -> MemberFinder.methodByDescriptorFragment(cls, "JIL" + confirmInternal + ";"));

                discovered.put("SERVICE_FORGE_METHOD", svcForgeMethod);
                discovered.put("SERVICE_EQUIP_METHOD", svcEquipMethod);

                // ── TudeyController fields ────────────────────────────────────────────
                CtClass tudeyCtrlClass = pool.get(TUDEY_CONTROLLER);

                String viewField = MemberFinder.resolveField("VIEW_FIELD", tudeyCtrlClass,
                                cls -> MemberFinder.fieldByType(cls, TUDEY_VIEW));
                // TudeySceneObject is a stable non-obfuscated subclass of PlaceObject
                String sceneObjField = MemberFinder.resolveField("SCENE_OBJ_FIELD", tudeyCtrlClass,
                                cls -> MemberFinder.fieldByTypeSuffix(cls, "TudeySceneObject"));
                // Pawn ID: first int field excluding the reference fields already found
                String pawnIdField = MemberFinder.resolveField("PAWN_ID_FIELD", tudeyCtrlClass,
                                cls -> MemberFinder.firstIntField(cls, viewField, sceneObjField));

                discovered.put("VIEW_FIELD", viewField);
                discovered.put("SCENE_OBJ_FIELD", sceneObjField);
                discovered.put("PAWN_ID_FIELD", pawnIdField);

                // ── TudeyView actor map field ─────────────────────────────────────────
                CtClass tudeyViewClass = pool.get(TUDEY_VIEW);
                // Actor map: HashIntMap<ActorWrapper> — match by generic signature
                String actorWrapperInternal = ACTOR_WRAPPER.replace('.', '/');
                String actorMapField = MemberFinder.resolveField("ACTOR_MAP_FIELD", tudeyViewClass,
                                cls -> MemberFinder.fieldByGenericSignatureFragment(cls, actorWrapperInternal));
                discovered.put("ACTOR_MAP_FIELD", actorMapField);

                // ── Actor class methods ───────────────────────────────────────────────
                CtClass actorClass = pool.get(ACTOR_CLASS);

                // getTranslation: only no-arg method returning a com.threerings.math type
                String getTransMethod = MemberFinder.resolveMethod("GET_TRANSLATION_METHOD", actorClass,
                                cls -> MemberFinder.noArgMethodByReturnPackage(cls, "com.threerings.math"));
                // getId: no-arg method returning int
                String getIdMethod = MemberFinder.resolveMethod("GET_ID_METHOD", actorClass,
                                cls -> MemberFinder.methodByReturnAndParams(cls, CtClass.intType));

                String transTypeName = actorClass.getDeclaredMethod(getTransMethod).getReturnType().getName();

                discovered.put("GET_TRANSLATION_METHOD", getTransMethod);
                discovered.put("GET_ID_METHOD", getIdMethod);

                // ── 3. Patch game classes ─────────────────────────────────────────────

                patchChatRestock(pool, CHAT_DIRECTOR, CHAT_MESSAGE_CLASS, chatDirMsgMethod);
                patchAutoJoin(pool, PARTY_NOTIFICATION, pnUpdateMethod, pnInviterMethod, pnAcceptMethod, O_CLASS);
                patchInputState(pool, INPUT_STATE_CLASS, forgeBtnTypeName);
                patchDungeonClientCache(pool, DUNGEON_CLIENT);
                patchController(pool, outDir, TUDEY_CONTROLLER, TUDEY_VIEW, ACTOR_WRAPPER, ACTOR_CLASS,
                                transTypeName, viewField, sceneObjField, pawnIdField,
                                actorMapField, getIdMethod, getTransMethod);
                patchForgeAdapter(pool, FORGE_WINDOW, forgeBtnField, forgeUpgradeMethod);
                patchDxDismissLog(pool, outDir, DISMISS_LOG_CLASS);
                patchDamageEvent(pool, outDir);

                // ── Relog helper (auto-reconnect, 2026-08-25) ─────────────────────────
                // The game's own "logoff -> logon -> re-pick the same knight" observer
                // ('dk' in 20260824), used by ProjectXApp.en for server moves. Its
                // (Client) method does the whole relogon: sets creds.language, sets the
                // client credentials, stores the knight name into the app's auto-pick
                // field, and starts logon through the logon window. Relog.java invokes it
                // after a disconnect. Fingerprint: the fully REAL-NAMED ctor signature +
                // the three field types — its sibling knight-SWITCH observer (dY) has no
                // String field and a different ctor, so both criteria discriminate.
                // SOFT-fail on purpose: a miss degrades Relog to plain Client.logon()
                // (no knight re-pick) instead of failing the build — the core relog path
                // is real-named-only and must survive any reshuffle.
                String RELOG_HELPER = "";
                String RELOG_HELPER_METHOD = "";
                try {
                        RELOG_HELPER = new ClassFinder(pcodeJar)
                                        .packagePrefix("com.threerings.projectx.client")
                                        .hasFieldType("Lcom/threerings/projectx/client/ProjectXApp;")
                                        .hasFieldType("Lcom/threerings/projectx/data/ProjectXCredentials;")
                                        .hasFieldType("Lcom/threerings/util/Name;")
                                        .hasFieldType("Ljava/lang/String;")
                                        .hasMethodDescriptorFragment(
                                                        "(Lcom/threerings/projectx/client/ProjectXApp;Ljava/lang/String;"
                                                                        + "Lcom/threerings/projectx/data/ProjectXCredentials;"
                                                                        + "Lcom/threerings/util/Name;)")
                                        .findUnique(); // never findOrByName — 'dk' is prime name-recycling bait
                        // Its two (Client)V callbacks: only the relogon one touches
                        // ProjectXCredentials (writes creds.language) — the other is window
                        // status only. Empty fallback = treat as undiscovered.
                        RELOG_HELPER_METHOD = methodByBodyRef(pool, RELOG_HELPER,
                                        "(Lcom/threerings/presents/client/Client;)V", "ProjectXCredentials", "");
                        if (RELOG_HELPER_METHOD.isEmpty())
                                RELOG_HELPER = "";
                } catch (Exception relogEx) {
                        System.out.println("  WARNING: relog helper NOT found (" + relogEx.getMessage()
                                        + ") — auto-reconnect degrades to plain logon without knight re-pick");
                        RELOG_HELPER = "";
                        RELOG_HELPER_METHOD = "";
                }
                discovered.put("RELOG_HELPER_CLASS", RELOG_HELPER);
                discovered.put("RELOG_HELPER_METHOD", RELOG_HELPER_METHOD);
                if (!RELOG_HELPER.isEmpty())
                        System.out.println("  relog helper = " + RELOG_HELPER + "."
                                        + RELOG_HELPER_METHOD + "(Client)");

                // ── 4. Write MappingsNames.java with all discovered constants ─────────
                // Patcher generates out/MappingsNames.java (static final Strings).
                // Mod classes compile against it in the next build phase — values are
                // inlined by javac into every caller, zero runtime overhead.

                writeMappingsNames(outDir, discovered);
        }

        // ── Mod class stubs ───────────────────────────────────────────────────────

        /**
         * Adds skeleton CtClass entries to the pool for each mod class referenced in
         * injection
         * templates. Javassist's compiler resolves class names when compiling
         * insertBefore/
         * insertAfter source strings, so it needs to find these classes — even though
         * the real
         * implementations don't exist yet (they're compiled in a later build phase).
         * The stubs are never written to disk.
         */
        private static void registerModClassStubs(ClassPool pool) throws Exception {
                // SocketInputState — fields and methods used in InputState patch template
                CtClass sis = pool.makeClass(SOCKET_INPUT_STATE);
                sis.addField(CtField.make("public static Object dungeonClient;", sis));
                sis.addField(CtField.make("public static Object _cachedCtx;", sis));
                sis.addField(CtField.make("public static Object currentForgeBtn;", sis));
                sis.addField(CtField.make("public static boolean isCtrlDown;", sis));
                sis.addField(CtField.make("public static boolean isAutoForging;", sis));
                sis.addField(CtField.make("public static boolean isAutoFollowing;", sis));
                sis.addField(CtField.make("public static boolean isBreadcrumbFollow;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static float[] crumbFollowTarget(float ax, float ay) { return null; }", sis));
                sis.addField(CtField.make("public static float mainBroadcastX;", sis));
                sis.addField(CtField.make("public static float mainBroadcastY;", sis));
                sis.addField(CtField.make("public static long mainBroadcastTime;", sis));
                sis.addMethod(CtNewMethod.make("public static void start() {}", sis));
                sis.addMethod(CtNewMethod.make("public static boolean isMainAccount() { return false; }", sis));
                sis.addMethod(CtNewMethod.make("public static String[] getDownKeys() { return null; }", sis));
                sis.addMethod(CtNewMethod.make("public static int getKeyCode(String k) { return 0; }", sis));
                sis.addMethod(CtNewMethod.make("public static void clearMovementKeys() {}", sis));
                sis.addMethod(CtNewMethod.make("public static void setMovementFromDelta(float dx, float dy) {}", sis));
                // Control scheme resolved from the game's own bindings (KeyBinds.java).
                // Dispatch targets: a key code, or a mouse button index when the action
                // is mouse-bound (the unused one is -1). Watch slots: every alternative
                // the human main might press (bit 0x10000000 = needs modifier_1,
                // 512+n = mouse button n).
                sis.addField(CtField.make("public static int bindMoveN;", sis));
                sis.addField(CtField.make("public static int bindMoveS;", sis));
                sis.addField(CtField.make("public static int bindMoveW;", sis));
                sis.addField(CtField.make("public static int bindMoveE;", sis));
                sis.addField(CtField.make("public static int bindModifier1;", sis));
                sis.addField(CtField.make("public static int bindDefendKey;", sis));
                sis.addField(CtField.make("public static int bindDefendMouse;", sis));
                sis.addField(CtField.make("public static int bindDodgeKey;", sis));
                sis.addField(CtField.make("public static int bindDodgeMouse;", sis));
                sis.addField(CtField.make("public static boolean bindDodgeMod;", sis));
                sis.addField(CtField.make("public static int bindActionKey;", sis));
                sis.addField(CtField.make("public static int bindActionMouse;", sis));
                sis.addField(CtField.make("public static int[] bindDefendSlots;", sis));
                sis.addField(CtField.make("public static int[] bindDodgeSlots;", sis));
                sis.addField(CtField.make("public static int[] bindActionSlots;", sis));
                sis.addField(CtField.make("public static int[] bindSprite1Slots;", sis));
                sis.addField(CtField.make("public static int[] bindSprite2Slots;", sis));
                sis.addField(CtField.make("public static int[] bindSprite3Slots;", sis));
                sis.addField(CtField.make("public static int screenCx;", sis));
                sis.addField(CtField.make("public static int screenCy;", sis));
                sis.addField(CtField.make("public static long screenCenterAt;", sis));
                sis.addField(CtField.make("public static boolean mainMouseLeft;", sis));
                sis.addField(CtField.make("public static boolean prevMainMouseLeft;", sis));
                sis.addField(CtField.make("public static boolean mainMouseRight;", sis));
                sis.addField(CtField.make("public static boolean prevMainMouseRight;", sis));
                sis.addField(CtField.make("public static boolean prevMainXShield;", sis));
                sis.addField(CtField.make("public static boolean prevMainDash;", sis));
                sis.addField(CtField.make("public static boolean prevMainBarrier;", sis));
                sis.addField(CtField.make("public static boolean prevMainVial;", sis));
                sis.addField(CtField.make("public static boolean pendingVialUse;", sis));
                sis.addField(CtField.make("public static boolean prevMainSprite1;", sis));
                sis.addField(CtField.make("public static boolean prevMainSprite2;", sis));
                sis.addField(CtField.make("public static boolean prevMainSprite3;", sis));
                sis.addField(CtField.make("public static boolean pendingBarrierUse;", sis));
                sis.addField(CtField.make("public static int pendingSpriteSlot;", sis));
                sis.addField(CtField.make("public static int spriteReleaseKey;", sis));
                sis.addField(CtField.make("public static float mainCursorAngle;", sis));
                sis.addField(CtField.make("public static long pendingFireAtMs;", sis));
                sis.addField(CtField.make("public static long pendingFireReleaseAtMs;", sis));
                sis.addField(CtField.make("public static float pendingFireAngle;", sis));
                sis.addField(CtField.make("public static int pendingFireX;", sis));
                sis.addField(CtField.make("public static int pendingFireY;", sis));
                sis.addField(CtField.make("public static boolean pendingShieldPress;", sis));
                sis.addField(CtField.make("public static boolean pendingShieldRelease;", sis));
                sis.addField(CtField.make("public static boolean pendingWeaponPress;", sis));
                sis.addField(CtField.make("public static boolean pendingWeaponRelease;", sis));
                sis.addField(CtField.make("public static boolean pendingWeaponAimDirty;", sis));
                sis.addField(CtField.make("public static boolean fireActive;", sis));
                sis.addField(CtField.make("public static boolean shieldActive;", sis));
                sis.addField(CtField.make("public static long shieldRepressAt;", sis));
                sis.addField(CtField.make("public static boolean dashActive;", sis));
                sis.addField(CtField.make("public static boolean pendingDashPress;", sis));
                sis.addField(CtField.make("public static float pendingDashAngle;", sis));
                sis.addField(CtField.make("public static boolean pendingDashHasAngle;", sis));
                sis.addField(CtField.make("public static boolean pendingDashRelease;", sis));
                sis.addField(CtField.make("public static float altFollowDx;", sis));
                sis.addField(CtField.make("public static float altFollowDy;", sis));
                sis.addMethod(CtNewMethod.make("public static void broadcast(String s) {}", sis));
                sis.addMethod(CtNewMethod.make("public static void broadcastPosition(float x, float y) {}", sis));
                sis.addMethod(CtNewMethod.make("public static void logFollowSource(boolean b) {}", sis));
                sis.addMethod(CtNewMethod.make("public static void triggerDash() {}", sis));
                sis.addField(CtField.make("public static volatile boolean windowDumpArmed;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickWindowDump(Object ctx) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tryUseConsumable(Object ctrl, Object ctx, Object actor) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tryUseBarrier(Object ctx) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tryUseVial(Object ctx) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tryUseSprite(Object ctrl) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tryPvpQueue(Object ctx) {}", sis));
                sis.addField(CtField.make("public static boolean isAutoPvpQueue;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickPvpAntiIdle(Object ctx) {}", sis));
                sis.addField(CtField.make("public static int antiIdleKeyCode;", sis));
                sis.addField(CtField.make("public static long antiIdleUntil;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickCombatBot(Object ctrl) {}", sis));
                sis.addField(CtField.make("public static boolean isCombatBot;", sis));
                sis.addField(CtField.make("public static boolean botFiring;", sis));
                sis.addField(CtField.make("public static float botFireAngle;", sis));
                sis.addField(CtField.make("public static boolean botFireHeld;", sis));
                sis.addField(CtField.make("public static long botTapReleaseAt;", sis));
                sis.addField(CtField.make("public static long botNextTapAt;", sis));
                sis.addField(CtField.make("public static boolean botShieldHold;", sis));
                sis.addField(CtField.make("public static boolean botShieldHeld;", sis));
                sis.addField(CtField.make("public static boolean routineShieldHold;", sis));
                sis.addField(CtField.make("public static boolean routineShieldHeld;", sis));
                sis.addField(CtField.make("public static boolean botWeapon2Mode;", sis));
                sis.addField(CtField.make("public static boolean botCycleActive;", sis));
                sis.addField(CtField.make("public static int botCyclePhase;", sis));
                sis.addField(CtField.make("public static int botTapIndex;", sis));
                sis.addField(CtField.make("public static boolean botCycleWeapon2;", sis));
                sis.addField(CtField.make("public static boolean botCycleBlaster;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void botSelectWeaponForCycle() {}", sis));
                sis.addField(CtField.make("public static boolean sceneScanPending;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void dumpScene(Object ctrl) {}", sis));
                sis.addField(CtField.make("public static boolean dumpPosPending;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void dumpPlayerPos(Object ctrl) {}", sis));
                sis.addField(CtField.make("public static boolean forgePassPending;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickForgePass() {}", sis));
                sis.addField(CtField.make("public static boolean isLootMode;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickLootMode(Object ctrl) {}", sis));
                sis.addField(CtField.make("public static boolean isPathTest;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickPathTest(Object ctrl) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static boolean mainMovementDriven() { return false; }", sis));
                sis.addField(CtField.make("public static boolean isRoutineActive;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickRoutine(Object ctrl) {}", sis));
                sis.addField(CtField.make("public static boolean campaignActive;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickCampaign(Object ctrl) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickMainIdleWatch(Object ctrl) {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickAutoAdvance(Object ctx) {}", sis));
                sis.addField(CtField.make("public static boolean autoAdvanceOn;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickAuctionBot() {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickRelog() {}", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickDeathWatch(Object ctrl) {}", sis));
                sis.addField(CtField.make("public static boolean routineShootActive;", sis));
                sis.addField(CtField.make("public static float routineShootAngle;", sis));
                sis.addField(CtField.make("public static boolean routineShootHeld;", sis));
                sis.addField(CtField.make("public static long routineShootReleaseAt;", sis));
                sis.addField(CtField.make("public static long routineShootNextAt;", sis));
                sis.addField(CtField.make("public static boolean isMineralGather;", sis));
                sis.addField(CtField.make("public static boolean mineralTapActive;", sis));
                sis.addField(CtField.make("public static boolean mineralTapHeld;", sis));
                sis.addField(CtField.make("public static long mineralTapReleaseAt;", sis));
                sis.addField(CtField.make("public static long mineralTapNextAt;", sis));
                sis.addField(CtField.make("public static float mineralTapAngle;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickMineralGatherAlt(Object view, float ax, float ay, int pawnId) {}", sis));
                sis.addField(CtField.make("public static boolean isRoutineShootAlt;", sis));
                sis.addField(CtField.make("public static float shootAltX;", sis));
                sis.addField(CtField.make("public static float shootAltY;", sis));
                sis.addMethod(CtNewMethod.make(
                                "public static void tickRoutineShootAlt(Object view, float ax, float ay, int pawnId) {}", sis));
                sis.addField(CtField.make("public static boolean keyTapFire;", sis));
                sis.addField(CtField.make("public static boolean keyTapHeld;", sis));
                sis.addField(CtField.make("public static long keyTapReleaseAt;", sis));
                sis.addField(CtField.make("public static float keyTapAngle;", sis));
                sis.addField(CtField.make("public static boolean pickupAimActive;", sis));
                sis.addField(CtField.make("public static float pickupAimAngle;", sis));
                sis.addField(CtField.make("public static long keyTapHoldMs;", sis));
                sis.addField(CtField.make("public static Object chatDirector;", sis));
                sis.addMethod(CtNewMethod.make("public static void displayChat(String msg, String type) {}", sis));
                sis.addMethod(CtNewMethod.make("public static void displayChat(String msg) {}", sis));
                sis.addField(CtField.make("public static int hudBtnX = -1;", sis));

                // ForgeAllAdapter — constructor + manualRestock/manualCoordEquip used in forge
                // patch templates
                CtClass faa = pool.makeClass(FORGE_ALL_ADAPTER);
                faa.setSuperclass(pool.get("java.lang.Thread"));
                faa.addConstructor(CtNewConstructor.make(
                                new CtClass[] { pool.get("java.lang.Object") }, new CtClass[0], faa));
                faa.addMethod(CtNewMethod.make("public static void manualRestock(Object ctx) {}", faa));
                faa.addMethod(CtNewMethod.make("public static void manualCoordEquip(Object ctx) {}", faa));

                // AutoJoiner — checkAutoJoin used in controller tick template
                CtClass aj = pool.makeClass("com.threerings.projectx.social.AutoJoiner");
                aj.addMethod(CtNewMethod.make(
                                "public static void checkAutoJoin(Object ctx, String name) {}", aj));

                // ForgeTracker — debug logging used in forge patch templates
                CtClass ft = pool.makeClass(FORGE_TRACKER);
                ft.addMethod(CtNewMethod.make("public static void debug(String msg) {}", ft));

                // Mappings — methods used in forge upgrade-method ExprEditor patch
                CtClass mappings = pool.makeClass(MAPPINGS);
                mappings.addMethod(CtNewMethod.make(
                                "public static Object createChainingForgeListener(Object orig, long oid, int lvl, Object ctx) { return null; }",
                                mappings));
                mappings.addMethod(CtNewMethod.make(
                                "public static void forgeWithTracking(Object svc, long oid, int crystals, int lvl, Object orig, Object ctx) {}",
                                mappings));

                // HeatHudPanel — init/tick/onLayout called from HeatHudWindow.layoutWindow and
                // the tick injection
                CtClass hp = pool.makeClass("com.threerings.opengl.gui.HeatHudPanel");
                hp.addMethod(CtNewMethod.make("public static void init(Object ctx) {}", hp));
                hp.addMethod(CtNewMethod.make("public static void tick(Object ctx) {}", hp));
                hp.addMethod(CtNewMethod.make("public static void onLayout(Object win, int w, int h) {}", hp));
                hp.addMethod(CtNewMethod.make("public static void onWindowRemoved() {}", hp));
                hp.addMethod(CtNewMethod.make("public static void onBtnWindowRemoved() {}", hp));
        }

        // ── Patch methods ─────────────────────────────────────────────────────────

        private static void patchChatRestock(ClassPool pool, String chatDirector,
                        String chatMessageClass, String msgMethod) throws Exception {
                CtClass bClass = pool.get(chatDirector);
                CtMethod aMethod = bClass.getDeclaredMethod(msgMethod, new CtClass[] {
                                pool.get(chatMessageClass),
                                pool.get("java.lang.String"),
                                CtClass.booleanType
                });
                aMethod.insertBefore(
                                "if (" + SOCKET_INPUT_STATE + ".chatDirector == null) {\n" +
                                                "    " + SOCKET_INPUT_STATE + ".chatDirector = this;\n" +
                                                "}");
                aMethod.insertBefore(
                                "if ($2 != null && $2.trim().equalsIgnoreCase(\"/restock\")) {\n" +
                                                "    " + FORGE_ALL_ADAPTER + ".manualRestock(this._ctx);\n" +
                                                "    return \"success\";\n" +
                                                "}");
                bClass.writeFile("out");
        }

        private static void patchAutoJoin(ClassPool pool, String partyNotification,
                        String updateMethod, String inviterMethod, String acceptMethod,
                        String oClass) throws Exception {
                CtClass pnClass = pool.get(partyNotification);
                pnClass.getDeclaredMethod(updateMethod).insertBefore(
                                "String inviterName = this." + inviterMethod + "() != null" +
                                                "    ? this." + inviterMethod + "().toString() : \"\";\n" +
                                                "if (inviterName.equals(" + SK_CONFIG + ".MAIN_ACCOUNT)) {\n" +
                                                "    this." + acceptMethod + "($1, 1, new " + oClass + "[0]);\n" +
                                                "}");
                pnClass.writeFile("out");
        }

        private static void patchDungeonClientCache(ClassPool pool, String dungeonClient) throws Exception {
                CtClass dcClass = pool.get(dungeonClient);

                CtConstructor[] ctors = dcClass.getDeclaredConstructors();
                for (int i = 0; i < ctors.length; i++) {
                        ctors[i].insertAfter(
                                        SOCKET_INPUT_STATE + ".dungeonClient = this;");
                }

                dcClass.writeFile("out");
        }

        private static void patchInputState(ClassPool pool, String inputStateClass,
                        String forgeBtnTypeName) throws Exception {
                CtClass cc = pool.get(inputStateClass);

                // NOTE: the old fixed-key probes (isShiftDown/isXDown/isMouseLeftDown/
                // isMouseRightDown) are gone — every input the mod reads or synthesises
                // now resolves through the player's live bindings (__skAnyHeld /
                // __skIsKeyDown / __skMouseHeldRaw below). isCtrlDown stays: Ctrl is a
                // MOD hotkey (forge-all), not a game action, so it is never rebound.
                CtMethod checkCtrl = CtNewMethod.make(
                                "public boolean isCtrlDown() {\n" +
                                                "    try {\n" +
                                                "        Class glfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method getKey = glfw.getMethod(\"glfwGetKey\","
                                                +
                                                "            new Class[]{long.class, int.class});\n" +
                                                "        int res1 = ((Integer) getKey.invoke(null," +
                                                "            new Object[]{new Long(this.getWindow()), new Integer(341)})).intValue();\n"
                                                +
                                                "        int res2 = ((Integer) getKey.invoke(null," +
                                                "            new Object[]{new Long(this.getWindow()), new Integer(345)})).intValue();\n"
                                                +
                                                "        return res1 == 1 || res2 == 1;\n" +
                                                "    } catch (Exception e) {\n" +
                                                "        return false;\n" +
                                                "    }\n" +
                                                "}",
                                cc);
                cc.addMethod(checkCtrl);

                CtMethod checkKeyCode = CtNewMethod.make(
                                "public boolean __skIsKeyDown(int code) {\n" +
                                                "    try {\n" +
                                                "        Class glfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method getKey = glfw.getMethod(\"glfwGetKey\",\n"
                                                +
                                                "            new Class[]{long.class, int.class});\n" +
                                                "        return ((Integer) getKey.invoke(null,\n" +
                                                "            new Object[]{new Long(this.getWindow()), new Integer(code)})).intValue() == 1;\n"
                                                +
                                                "    } catch (Exception e) {\n" +
                                                "        return false;\n" +
                                                "    }\n" +
                                                "}",
                                cc);
                cc.addMethod(checkKeyCode);

                // ── Control-scheme indirection ───────────────────────────────
                // Every synthesised input goes through these helpers instead of a
                // hardcoded key code, so the mod follows whatever the player has
                // bound (SocketInputState.bind*, resolved by KeyBinds from the
                // game's own prefs). Each action may be a KEY or a MOUSE button,
                // and dodge may additionally require modifier_1 — the branch lives
                // here, once, rather than at ~20 call sites.
                cc.addMethod(CtNewMethod.make(
                                "public boolean __skMouseHeldRaw(int btn) {\n" +
                                                "    if (btn < 0) { return false; }\n" +
                                                "    try {\n" +
                                                "        Class glfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method gm = glfw.getMethod(\"glfwGetMouseButton\",\n" +
                                                "            new Class[]{long.class, int.class});\n" +
                                                "        return ((Integer) gm.invoke(null,\n" +
                                                "            new Object[]{new Long(this.getWindow()), new Integer(btn)})).intValue() == 1;\n" +
                                                "    } catch (Exception e) { return false; }\n" +
                                                "}",
                                cc));

                // True if ANY alternative bound to an action is currently held. Used
                // on the main to mirror the human's inputs to the alts, so pressing
                // either binding (e.g. action = Z *or* right-click) registers.
                cc.addMethod(CtNewMethod.make(
                                "public boolean __skAnyHeld(int[] slots, boolean modHeld) {\n" +
                                                "    if (slots == null) { return false; }\n" +
                                                "    for (int i = 0; i < slots.length; i++) {\n" +
                                                "        int raw = slots[i];\n" +
                                                "        if (raw < 0) { continue; }\n" +
                                                "        int code = raw;\n" +
                                                "        if ((raw & 268435456) != 0) {\n" +
                                                "            if (!modHeld) { continue; }\n" +
                                                "            code = raw - 268435456;\n" +
                                                "        }\n" +
                                                "        if (code >= 512) {\n" +
                                                "            if (this.__skMouseHeldRaw(code - 512)) { return true; }\n" +
                                                "        } else if (this.__skIsKeyDown(code)) { return true; }\n" +
                                                "    }\n" +
                                                "    return false;\n" +
                                                "}",
                                cc));

                cc.addMethod(CtNewMethod.make(
                                "public void __skKeyDown(int code) {\n" +
                                                "    if (code < 0) { return; }\n" +
                                                "    try { this.dispatchKeyEvent(this.getFocus(), new " + KEY_EVENT_CLASS + "\n" +
                                                "        (this, this._tickStamp, this._modifiers, 0, '\\u0000', code, false)); } catch (Exception e) {}\n" +
                                                "}",
                                cc));
                cc.addMethod(CtNewMethod.make(
                                "public void __skKeyUp(int code) {\n" +
                                                "    if (code < 0) { return; }\n" +
                                                "    try { this.dispatchKeyEvent(this.getFocus(), new " + KEY_EVENT_CLASS + "\n" +
                                                "        (this, this._tickStamp, this._modifiers, 1, '\\u0000', code, false)); } catch (Exception e) {}\n" +
                                                "}",
                                cc));

                // Screen centre, refreshed at most every 2s: where a MOUSE-bound
                // shield/dash click lands (a click needs coordinates; a key hold
                // doesn't). Aimed attacks pass their own point instead.
                cc.addMethod(CtNewMethod.make(
                                "public void __skRefreshCenter() {\n" +
                                                "    long now = System.currentTimeMillis();\n" +
                                                "    if (now < " + SOCKET_INPUT_STATE + ".screenCenterAt) { return; }\n" +
                                                "    try {\n" +
                                                "        int[] w = new int[1]; int[] h = new int[1];\n" +
                                                "        Class glfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method f = glfw.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "            new Class[]{long.class, int[].class, int[].class});\n" +
                                                "        f.invoke(null, new Object[]{new Long(this.getWindow()), w, h});\n" +
                                                "        " + SOCKET_INPUT_STATE + ".screenCx = w[0] / 2;\n" +
                                                "        " + SOCKET_INPUT_STATE + ".screenCy = h[0] / 2;\n" +
                                                "        " + SOCKET_INPUT_STATE + ".screenCenterAt = now + 2000L;\n" +
                                                "    } catch (Exception e) {}\n" +
                                                "}",
                                cc));

                // ATTACK (the game's `action`): pressed at an AIMED point — the
                // caller has already parked the cursor there, so a mouse binding
                // clicks at it and a key binding just fires facing it.
                cc.addMethod(CtNewMethod.make(
                                "public void __skAttackPress(int x, int y) {\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindActionMouse >= 0) {\n" +
                                                "        try { this.mousePressed(this._tickStamp, " + SOCKET_INPUT_STATE + ".bindActionMouse, x, y, false); } catch (Exception e) {}\n" +
                                                "    } else { this.__skKeyDown(" + SOCKET_INPUT_STATE + ".bindActionKey); }\n" +
                                                "}",
                                cc));
                cc.addMethod(CtNewMethod.make(
                                "public void __skAttackRelease(int x, int y) {\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindActionMouse >= 0) {\n" +
                                                "        try { this.mouseReleased(this._tickStamp, " + SOCKET_INPUT_STATE + ".bindActionMouse, x, y, false); } catch (Exception e) {}\n" +
                                                "    } else { this.__skKeyUp(" + SOCKET_INPUT_STATE + ".bindActionKey); }\n" +
                                                "}",
                                cc));

                // SHIELD (`defend`) and DASH (`dodge`). Dash presses modifier_1
                // first and releases it last, mirroring the classic Shift+X order.
                cc.addMethod(CtNewMethod.make(
                                "public void __skShieldPress() {\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindDefendMouse >= 0) {\n" +
                                                "        this.__skRefreshCenter();\n" +
                                                "        try { this.mousePressed(this._tickStamp, " + SOCKET_INPUT_STATE + ".bindDefendMouse,\n" +
                                                "            " + SOCKET_INPUT_STATE + ".screenCx, " + SOCKET_INPUT_STATE + ".screenCy, false); } catch (Exception e) {}\n" +
                                                "    } else { this.__skKeyDown(" + SOCKET_INPUT_STATE + ".bindDefendKey); }\n" +
                                                "}",
                                cc));
                cc.addMethod(CtNewMethod.make(
                                "public void __skShieldRelease() {\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindDefendMouse >= 0) {\n" +
                                                "        try { this.mouseReleased(this._tickStamp, " + SOCKET_INPUT_STATE + ".bindDefendMouse,\n" +
                                                "            " + SOCKET_INPUT_STATE + ".screenCx, " + SOCKET_INPUT_STATE + ".screenCy, false); } catch (Exception e) {}\n" +
                                                "    } else { this.__skKeyUp(" + SOCKET_INPUT_STATE + ".bindDefendKey); }\n" +
                                                "}",
                                cc));
                // Parks the cursor on a bearing so the knight FACES that way (the knight
                // turns to follow the cursor). Y is negated because the angle was measured
                // in GLFW window coords (Y down) and lands in Clyde GUI coords (Y up).
                cc.addMethod(CtNewMethod.make(
                                "public void __skAimAt(float angle) {\n" +
                                                "    try {\n" +
                                                "        int[] w = new int[1]; int[] h = new int[1];\n" +
                                                "        Class glfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method f = glfw.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "            new Class[]{long.class, int[].class, int[].class});\n" +
                                                "        f.invoke(null, new Object[]{new Long(this.getWindow()), w, h});\n" +
                                                "        int x = w[0] / 2 + (int)(150.0f * (float)java.lang.Math.cos((double)angle));\n" +
                                                "        int y = h[0] / 2 - (int)(150.0f * (float)java.lang.Math.sin((double)angle));\n" +
                                                "        this.setMousePosition(x, y);\n" +
                                                "        this.mouseMoved(this._tickStamp, x, y, false);\n" +
                                                "    } catch (Exception e) {}\n" +
                                                "}",
                                cc));
                cc.addMethod(CtNewMethod.make(
                                "public void __skDashPress() {\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindDodgeMod) { this.__skKeyDown(" + SOCKET_INPUT_STATE + ".bindModifier1); }\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindDodgeMouse >= 0) {\n" +
                                                "        this.__skRefreshCenter();\n" +
                                                "        try { this.mousePressed(this._tickStamp, " + SOCKET_INPUT_STATE + ".bindDodgeMouse,\n" +
                                                "            " + SOCKET_INPUT_STATE + ".screenCx, " + SOCKET_INPUT_STATE + ".screenCy, false); } catch (Exception e) {}\n" +
                                                "    } else { this.__skKeyDown(" + SOCKET_INPUT_STATE + ".bindDodgeKey); }\n" +
                                                "}",
                                cc));
                cc.addMethod(CtNewMethod.make(
                                "public void __skDashRelease() {\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindDodgeMouse >= 0) {\n" +
                                                "        try { this.mouseReleased(this._tickStamp, " + SOCKET_INPUT_STATE + ".bindDodgeMouse,\n" +
                                                "            " + SOCKET_INPUT_STATE + ".screenCx, " + SOCKET_INPUT_STATE + ".screenCy, false); } catch (Exception e) {}\n" +
                                                "    } else { this.__skKeyUp(" + SOCKET_INPUT_STATE + ".bindDodgeKey); }\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".bindDodgeMod) { this.__skKeyUp(" + SOCKET_INPUT_STATE + ".bindModifier1); }\n" +
                                                "}",
                                cc));

                cc.getDeclaredMethod("poll").insertBefore(
                                SOCKET_INPUT_STATE + ".start();\n" +
                                                "if (" + SOCKET_INPUT_STATE + ".currentForgeBtn != null) {\n" +
                                                "    " + SOCKET_INPUT_STATE + ".isCtrlDown = this.isCtrlDown();\n" +
                                                "    if (this.isCtrlDown()) {\n" +
                                                "        ((" + forgeBtnTypeName + ") " + SOCKET_INPUT_STATE
                                                + ".currentForgeBtn).setText(\"Forge All\");\n" +
                                                "    } else {\n" +
                                                "        ((" + forgeBtnTypeName + ") " + SOCKET_INPUT_STATE
                                                + ".currentForgeBtn).setText(\"Forge\");\n" +
                                                "    }\n" +
                                                "}\n" +
                                                // Main: mirror the human's own attack / shield / dash to the alts.
                                                // Read through the LIVE bindings (any alternative counts), not fixed
                                                // keys. A dodge that rides on modifier_1 shares its key with defend,
                                                // so a dash must suppress the shield — same rule as the old
                                                // X-without-Shift test, now expressed in binding terms.
                                                // End-of-floor auto-advance: presses this client's own
                                                // ADVANCE NOW so the ~10s summary screen is skipped. Lives in
                                                // the POLL, not the scene tick: the screen is GUI state that
                                                // outlives the level. On EVERY account -- one client
                                                // dismissing its own screen does not move the party.
                                                "if (" + SOCKET_INPUT_STATE + ".autoAdvanceOn) {\n" +
                                                "    try { " + SOCKET_INPUT_STATE + ".tickAutoAdvance((Object) " + SOCKET_INPUT_STATE + "._cachedCtx); } catch (Exception _skAA) {}\n" +
                                                "}\n" +
                                                // Auto-reconnect (Relog.java): on EVERY account — main AND alts —
                                                // so it must sit OUTSIDE the isMainAccount block below (v1 sat
                                                // inside it and alts never even detected a logoff). In the POLL
                                                // because the logon screen has NO ticking scene — this is the only
                                                // host that runs there. Self-gated (full-auto modes only) and
                                                // self-throttled inside Relog.tick().
                                                "try { " + SOCKET_INPUT_STATE + ".tickRelog(); } catch (Exception _skRL) {}\n" +
                                                "if (" + SOCKET_INPUT_STATE + ".isMainAccount()) {\n" +
                                                // Campaign idle dead-man's switch: MUST live in the poll, not the
                                                // scene tick — the poll runs every GUI frame even with NO scene
                                                // (Haven, loading screens, a wedged transition), which is exactly
                                                // when the bot dies quietly. Self-gated on campaignActive.
                                                "    if (" + SOCKET_INPUT_STATE + ".campaignActive) {\n" +
                                                "        try { " + SOCKET_INPUT_STATE + ".tickMainIdleWatch((Object) " + SOCKET_INPUT_STATE + ".dungeonClient); } catch (Exception _skIW) {}\n" +
                                                "    }\n" +
                                                // Auction sweep (Ctrl+E): self-gated, and in the POLL so it runs
                                                // anywhere — the auction service is client-side, no scene needed.
                                                "    try { " + SOCKET_INPUT_STATE + ".tickAuctionBot(); } catch (Exception _skAB) {}\n" +
                                                "    boolean _skMod = this.__skIsKeyDown(" + SOCKET_INPUT_STATE + ".bindModifier1);\n" +
                                                "    boolean _skRmbFire = this.__skAnyHeld(" + SOCKET_INPUT_STATE + ".bindActionSlots, _skMod);\n" +
                                                "    boolean _skDefend = this.__skAnyHeld(" + SOCKET_INPUT_STATE + ".bindDefendSlots, _skMod);\n" +
                                                "    boolean _skDash = this.__skAnyHeld(" + SOCKET_INPUT_STATE + ".bindDodgeSlots, _skMod);\n" +
                                                "    boolean _skShield = _skDefend && !_skDash;\n" +
                                                "    try {\n" +
                                                "        int[] _skCw = new int[1]; int[] _skCh = new int[1];\n" +
                                                "        Class _skCGlfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method _skGfbs = _skCGlfw.getMethod(\"glfwGetFramebufferSize\",\n"
                                                +
                                                "            new Class[]{long.class, int[].class, int[].class});\n" +
                                                "        _skGfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skCw, _skCh});\n"
                                                +
                                                "        double[] _skMx = new double[1]; double[] _skMy = new double[1];\n"
                                                +
                                                "        java.lang.reflect.Method _skGcp = _skCGlfw.getMethod(\"glfwGetCursorPos\",\n"
                                                +
                                                "            new Class[]{long.class, double[].class, double[].class});\n"
                                                +
                                                "        _skGcp.invoke(null, new Object[]{new Long(this.getWindow()), _skMx, _skMy});\n"
                                                +
                                                "        double _skDx = _skMx[0] - _skCw[0] / 2.0;\n" +
                                                "        double _skDy = _skMy[0] - _skCh[0] / 2.0;\n" +
                                                "        " + SOCKET_INPUT_STATE + ".mainCursorAngle =\n" +
                                                "            (float) java.lang.Math.atan2(_skDy, _skDx);\n" +
                                                "    } catch (Exception _skCE) {}\n" +

                                                "    if (_skRmbFire != " + SOCKET_INPUT_STATE
                                                + ".prevMainMouseRight) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainMouseRight = _skRmbFire;\n"
                                                +
                                                "        if (_skRmbFire) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".broadcast(\"FIRE_DOWN \" + " +
                                                SOCKET_INPUT_STATE + ".mainCursorAngle);\n" +
                                                "        } else {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".broadcast(\"FIRE_UP \" + " +
                                                SOCKET_INPUT_STATE + ".mainCursorAngle);\n" +
                                                "        }\n" +
                                                "    } else if (_skRmbFire) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".broadcast(\"FIRE_AIM \" + " +
                                                SOCKET_INPUT_STATE + ".mainCursorAngle);\n" +
                                                "    }\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".prevMainXShield && !_skShield) {\n"
                                                +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainXShield = false;\n" +
                                                "        " + SOCKET_INPUT_STATE + ".broadcast(\"SHIELD 0\");\n" +
                                                "    }\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".prevMainDash && !_skDash) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainDash = false;\n" +
                                                "        " + SOCKET_INPUT_STATE + ".broadcast(\"DASH 0\");\n" +
                                                "    }\n" +
                                                "    if (!" + SOCKET_INPUT_STATE + ".prevMainDash && _skDash) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainDash = true;\n" +
                                                // Carry the aim angle so the alts face the same way before dashing.
                                                "        " + SOCKET_INPUT_STATE + ".broadcast(\"DASH 1 \" + " + SOCKET_INPUT_STATE + ".mainCursorAngle);\n" +
                                                "    }\n" +
                                                "    if (!" + SOCKET_INPUT_STATE + ".prevMainXShield && _skShield) {\n"
                                                +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainXShield = true;\n" +
                                                "        " + SOCKET_INPUT_STATE + ".broadcast(\"SHIELD 1\");\n" +
                                                "    }\n" +
                                                // G = barrier, 1/2/3 = sprite abilities — edge-detected like
                                                // SHIELD/DASH: broadcast to alts, flag locally for the main.
                                                "    boolean _skG = this.__skIsKeyDown(71);\n" +
                                                "    if (_skG != " + SOCKET_INPUT_STATE + ".prevMainBarrier) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainBarrier = _skG;\n" +
                                                "        if (_skG) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".broadcast(\"BARRIER 1\");\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingBarrierUse = true;\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "    boolean _skH = this.__skIsKeyDown(72);\n" +
                                                "    if (_skH != " + SOCKET_INPUT_STATE + ".prevMainVial) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainVial = _skH;\n" +
                                                "        if (_skH) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".broadcast(\"VIAL 1\");\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingVialUse = true;\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "    boolean _skS1 = this.__skAnyHeld(" + SOCKET_INPUT_STATE + ".bindSprite1Slots, _skMod);\n" +
                                                "    if (_skS1 != " + SOCKET_INPUT_STATE + ".prevMainSprite1) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainSprite1 = _skS1;\n" +
                                                "        if (_skS1) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".broadcast(\"SPRITE 0\");\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingSpriteSlot = 0;\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "    boolean _skS2 = this.__skAnyHeld(" + SOCKET_INPUT_STATE + ".bindSprite2Slots, _skMod);\n" +
                                                "    if (_skS2 != " + SOCKET_INPUT_STATE + ".prevMainSprite2) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainSprite2 = _skS2;\n" +
                                                "        if (_skS2) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".broadcast(\"SPRITE 1\");\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingSpriteSlot = 1;\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "    boolean _skS3 = this.__skAnyHeld(" + SOCKET_INPUT_STATE + ".bindSprite3Slots, _skMod);\n" +
                                                "    if (_skS3 != " + SOCKET_INPUT_STATE + ".prevMainSprite3) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".prevMainSprite3 = _skS3;\n" +
                                                "        if (_skS3) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".broadcast(\"SPRITE 2\");\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingSpriteSlot = 2;\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                // PvP suicide bomb on the MAIN: when handlePvpSuicide flags a bomb
                                                // (main landed on the losing team), place it via a synthetic
                                                // right-click at screen centre, mirroring the alt bomb path.
                                                "    if (" + SOCKET_INPUT_STATE + ".isAutoPvpQueue) {\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".pendingWeaponPress) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingWeaponPress = false;\n" +
                                                "            try {\n" +
                                                "                int[] _skBw = new int[1]; int[] _skBh = new int[1];\n" +
                                                "                Class _skBGlfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "                java.lang.reflect.Method _skBgfbs = _skBGlfw.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "                    new Class[]{long.class, int[].class, int[].class});\n" +
                                                "                _skBgfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skBw, _skBh});\n" +
                                                "                int _skBx = _skBw[0] / 2; int _skBy = _skBh[0] / 2;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".pendingFireX = _skBx;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".pendingFireY = _skBy;\n" +
                                                "                this.__skAttackPress(_skBx, _skBy);\n" +
                                                "                " + SOCKET_INPUT_STATE + ".fireActive = true;\n" +
                                                "            } catch (Exception _skBE) {}\n" +
                                                "        }\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".pendingWeaponRelease) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingWeaponRelease = false;\n" +
                                                "            if (" + SOCKET_INPUT_STATE + ".fireActive) {\n" +
                                                "                " + SOCKET_INPUT_STATE + ".fireActive = false;\n" +
                                                "                try { this.__skAttackRelease(\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".pendingFireX,\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".pendingFireY); } catch (Exception _skBRE) {}\n" +
                                                "            }\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "    " + SOCKET_INPUT_STATE + ".mainMouseLeft = false;\n" +
                                                "}\n" +
                                                // PvP anti-idle (all accounts): hold the armed WASD key (set on
                                                // match start by tickPvpAntiIdle) until its deadline, then release
                                                // it once. Runs for main and alts so winning-team characters move.
                                                "if (" + SOCKET_INPUT_STATE + ".isAutoPvpQueue) {\n" +
                                                "    int _skAiCode = " + SOCKET_INPUT_STATE + ".antiIdleKeyCode;\n" +
                                                "    if (_skAiCode != -1) {\n" +
                                                "        if (System.currentTimeMillis() < " + SOCKET_INPUT_STATE + ".antiIdleUntil) {\n" +
                                                "            try { this.dispatchKeyEvent(this.getFocus(), new " + KEY_EVENT_CLASS + "\n" +
                                                "                (this, this._tickStamp, this._modifiers, 0, '\\u0000', _skAiCode, false)); } catch (Exception _skAiE) {}\n" +
                                                "        } else {\n" +
                                                "            try { this.dispatchKeyEvent(this.getFocus(), new " + KEY_EVENT_CLASS + "\n" +
                                                "                (this, this._tickStamp, this._modifiers, 1, '\\u0000', _skAiCode, false)); } catch (Exception _skAiE2) {}\n" +
                                                "            " + SOCKET_INPUT_STATE + ".antiIdleKeyCode = -1;\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "}\n" +
                                                // Combat bot (all accounts): a firing-cycle state machine aimed at
                                                // botFireAngle. Two cadences, latched per cycle into botCycleBlaster
                                                // (per-slot mission_data config): Blaster = three 40ms taps 100ms
                                                // apart, Autogun = two 40ms taps 250ms apart, both + a 150ms reload.
                                                // The whole cycle runs to completion before the tick may swap weapon
                                                // (it is gated on botCycleActive), so weapon AND cadence are decided
                                                // ONCE per cycle and a swap can never land mid-animation. Screen is
                                                // pawn-centred; button 1 is the mod's weapon-fire button.
                                                "if (" + SOCKET_INPUT_STATE + ".isCombatBot || " + SOCKET_INPUT_STATE + ".botFireHeld) {\n" +
                                                "    long _skCn = System.currentTimeMillis();\n" +
                                                "    boolean _skCgo = " + SOCKET_INPUT_STATE + ".isCombatBot && " + SOCKET_INPUT_STATE + ".botFiring && !" + SOCKET_INPUT_STATE + ".botShieldHold;\n" +
                                                // Not cleared to fire (bot off / no target / shielding / weapon still
                                                // settling): release any press and reset the cycle to idle so the next
                                                // one starts clean and the button can never stick.
                                                "    if (!_skCgo) {\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".botFireHeld) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".botFireHeld = false;\n" +
                                                "            try { this.__skAttackRelease(\n" +
                                                "                " + SOCKET_INPUT_STATE + ".pendingFireX,\n" +
                                                "                " + SOCKET_INPUT_STATE + ".pendingFireY); } catch (Exception _skCRE) {}\n" +
                                                "        }\n" +
                                                "        " + SOCKET_INPUT_STATE + ".botCyclePhase = 0;\n" +
                                                "        " + SOCKET_INPUT_STATE + ".botCycleActive = false;\n" +
                                                "    } else {\n" +
                                                "        try {\n" +
                                                "            float _skCa = " + SOCKET_INPUT_STATE + ".botFireAngle;\n" +
                                                "            int[] _skCw = new int[1]; int[] _skCh = new int[1];\n" +
                                                "            Class _skCGlfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "            java.lang.reflect.Method _skCgfbs = _skCGlfw.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "                new Class[]{long.class, int[].class, int[].class});\n" +
                                                "            _skCgfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skCw, _skCh});\n" +
                                                "            int _skCx = _skCw[0] / 2 + (int)(150.0f * (float)java.lang.Math.cos((double)_skCa));\n" +
                                                "            int _skCy = _skCh[0] / 2 - (int)(150.0f * (float)java.lang.Math.sin((double)_skCa));\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireX = _skCx;\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireY = _skCy;\n" +
                                                "            this.setMousePosition(_skCx, _skCy);\n" +
                                                "            this.mouseMoved(this._tickStamp, _skCx, _skCy, false);\n" +
                                                "            int _skCph = " + SOCKET_INPUT_STATE + ".botCyclePhase;\n" +
                                                "            if (_skCph == 0) {\n" +
                                                // IDLE -> start a cycle. Latch the cadence, re-select the matching
                                                // weapon (so a weapon/cadence mismatch can never outlast one cycle),
                                                // and mark the cycle active so the tick won't swap mid-animation.
                                                "                " + SOCKET_INPUT_STATE + ".botCycleWeapon2 = " + SOCKET_INPUT_STATE + ".botWeapon2Mode;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".botSelectWeaponForCycle();\n" +
                                                "                " + SOCKET_INPUT_STATE + ".botCycleActive = true;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".botTapIndex = 0;\n" +
                                                "                this.__skAttackPress(_skCx, _skCy);\n" +
                                                "                " + SOCKET_INPUT_STATE + ".botFireHeld = true;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".botTapReleaseAt = _skCn + 40L;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".botCyclePhase = 1;\n" +
                                                "            } else if (_skCph == 1) {\n" +
                                                // PRESSED -> release after the 40ms tap. Cadence latched per cycle in
                                                // botCycleBlaster (per-slot mission_data config): Autogun = 2 taps
                                                // 250ms apart, Blaster = 3 taps 100ms apart, both + a 150ms reload.
                                                "                if (_skCn >= " + SOCKET_INPUT_STATE + ".botTapReleaseAt) {\n" +
                                                "                    this.__skAttackRelease(_skCx, _skCy);\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".botFireHeld = false;\n" +
                                                "                    int _skMax; long _skInter; long _skReload;\n" +
                                                "                    if (" + SOCKET_INPUT_STATE + ".botCycleBlaster) { _skMax = 3; _skInter = 100L; _skReload = 150L; }\n" +
                                                "                    else { _skMax = 2; _skInter = 250L; _skReload = 150L; }\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".botTapIndex = " + SOCKET_INPUT_STATE + ".botTapIndex + 1;\n" +
                                                "                    if (" + SOCKET_INPUT_STATE + ".botTapIndex >= _skMax) { " + SOCKET_INPUT_STATE + ".botNextTapAt = _skCn + _skReload; }\n" +
                                                "                    else { " + SOCKET_INPUT_STATE + ".botNextTapAt = _skCn + _skInter; }\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".botCyclePhase = 2;\n" +
                                                "                }\n" +
                                                "            } else {\n" +
                                                // WAITING -> fire the next tap, or end the cycle at a boundary.
                                                "                if (_skCn >= " + SOCKET_INPUT_STATE + ".botNextTapAt) {\n" +
                                                "                    int _skMaxT; if (" + SOCKET_INPUT_STATE + ".botCycleBlaster) { _skMaxT = 3; } else { _skMaxT = 2; }\n" +
                                                "                    if (" + SOCKET_INPUT_STATE + ".botTapIndex >= _skMaxT) {\n" +
                                                // Cycle done: hand back to the tick (free to re-check mode / swap now)
                                                // and clear botFiring so it must re-grant before the next cycle.
                                                "                        " + SOCKET_INPUT_STATE + ".botCyclePhase = 0;\n" +
                                                "                        " + SOCKET_INPUT_STATE + ".botCycleActive = false;\n" +
                                                "                        " + SOCKET_INPUT_STATE + ".botFiring = false;\n" +
                                                "                    } else {\n" +
                                                "                        this.__skAttackPress(_skCx, _skCy);\n" +
                                                "                        " + SOCKET_INPUT_STATE + ".botFireHeld = true;\n" +
                                                "                        " + SOCKET_INPUT_STATE + ".botTapReleaseAt = _skCn + 40L;\n" +
                                                "                        " + SOCKET_INPUT_STATE + ".botCyclePhase = 1;\n" +
                                                "                    }\n" +
                                                "                }\n" +
                                                "            }\n" +
                                                "        } catch (Exception _skCE) {}\n" +
                                                "    }\n" +
                                                "}\n" +
                                                // Combat bot (all accounts): hold shield (the `defend` binding) while
                                                // an enemy bullet is within a tile; release once it clears.
                                                "if (" + SOCKET_INPUT_STATE + ".isCombatBot) {\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".botShieldHold) {\n" +
                                                "        this.__skShieldPress();\n" +
                                                "        " + SOCKET_INPUT_STATE + ".botShieldHeld = true;\n" +
                                                "    } else if (" + SOCKET_INPUT_STATE + ".botShieldHeld) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".botShieldHeld = false;\n" +
                                                "        this.__skShieldRelease();\n" +
                                                "    }\n" +
                                                "}\n" +
                                                // Stage-routine shield-bump: hold shield while a bump is active
                                                // during NON-COMBAT movement (routine only — combat uses the
                                                // botShieldHold block above; alts shield via the SHIELD broadcast).
                                                "if (" + SOCKET_INPUT_STATE + ".routineShieldHold) {\n" +
                                                "    this.__skShieldPress();\n" +
                                                "    " + SOCKET_INPUT_STATE + ".routineShieldHeld = true;\n" +
                                                "} else if (" + SOCKET_INPUT_STATE + ".routineShieldHeld) {\n" +
                                                "    " + SOCKET_INPUT_STATE + ".routineShieldHeld = false;\n" +
                                                "    this.__skShieldRelease();\n" +
                                                "}\n" +
                                                // Stage-routine SHOOT: fire weapon 2 in quick taps toward
                                                // routineShootAngle at a fixed world target (independent of the
                                                // combat bot; the routine sets isCombatBot=false during SHOOT).
                                                "if (" + SOCKET_INPUT_STATE + ".routineShootActive || " + SOCKET_INPUT_STATE + ".routineShootHeld) {\n" +
                                                "    long _skRn = System.currentTimeMillis();\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".routineShootActive) {\n" +
                                                "        try {\n" +
                                                "            float _skRa = " + SOCKET_INPUT_STATE + ".routineShootAngle;\n" +
                                                "            int[] _skRw = new int[1]; int[] _skRh = new int[1];\n" +
                                                "            Class _skRG = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "            java.lang.reflect.Method _skRfbs = _skRG.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "                new Class[]{long.class, int[].class, int[].class});\n" +
                                                "            _skRfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skRw, _skRh});\n" +
                                                "            int _skRx = _skRw[0] / 2 + (int)(150.0f * (float)java.lang.Math.cos((double)_skRa));\n" +
                                                "            int _skRy = _skRh[0] / 2 - (int)(150.0f * (float)java.lang.Math.sin((double)_skRa));\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireX = _skRx;\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireY = _skRy;\n" +
                                                "            this.setMousePosition(_skRx, _skRy);\n" +
                                                "            this.mouseMoved(this._tickStamp, _skRx, _skRy, false);\n" +
                                                "            if (" + SOCKET_INPUT_STATE + ".routineShootHeld) {\n" +
                                                "                if (_skRn >= " + SOCKET_INPUT_STATE + ".routineShootReleaseAt) {\n" +
                                                "                    this.__skAttackRelease(_skRx, _skRy);\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".routineShootHeld = false;\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".routineShootNextAt = _skRn + 160L;\n" +
                                                "                }\n" +
                                                "            } else if (_skRn >= " + SOCKET_INPUT_STATE + ".routineShootNextAt) {\n" +
                                                "                this.__skAttackPress(_skRx, _skRy);\n" +
                                                "                " + SOCKET_INPUT_STATE + ".routineShootHeld = true;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".routineShootReleaseAt = _skRn + 40L;\n" +
                                                "            }\n" +
                                                "        } catch (Exception _skRE) {}\n" +
                                                "    } else if (" + SOCKET_INPUT_STATE + ".routineShootHeld) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".routineShootHeld = false;\n" +
                                                "        try { this.__skAttackRelease(\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireX,\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireY); } catch (Exception _skRE2) {}\n" +
                                                "    }\n" +
                                                "}\n" +
                                                // Pickup PRE-AIM (KEY_LIFT/LIFT/MINERALS): park the cursor toward
                                                // the object EVERY POLL while a pickup is pending — the knight
                                                // faces the cursor, so by tap time it is already facing the
                                                // object and the tap can't misregister as an attack-mid-turn.
                                                // Aim only, no clicks; the tap blocks below own the presses.
                                                "if (" + SOCKET_INPUT_STATE + ".pickupAimActive && !" + SOCKET_INPUT_STATE + ".keyTapHeld && !"
                                                + SOCKET_INPUT_STATE + ".mineralTapHeld) {\n" +
                                                "    try {\n" +
                                                "        int[] _skPw = new int[1]; int[] _skPh = new int[1];\n" +
                                                "        Class _skPG = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method _skPfbs = _skPG.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "            new Class[]{long.class, int[].class, int[].class});\n" +
                                                "        _skPfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skPw, _skPh});\n" +
                                                "        float _skPa = " + SOCKET_INPUT_STATE + ".pickupAimAngle;\n" +
                                                "        int _skPx = _skPw[0] / 2 + (int)(150.0f * (float)java.lang.Math.cos((double)_skPa));\n" +
                                                "        int _skPy = _skPh[0] / 2 - (int)(150.0f * (float)java.lang.Math.sin((double)_skPa));\n" +
                                                "        this.setMousePosition(_skPx, _skPy);\n" +
                                                "        this.mouseMoved(this._tickStamp, _skPx, _skPy, false);\n" +
                                                "    } catch (Exception _skPE) {}\n" +
                                                "}\n" +
                                                // Mineral pickup tap (main + alts): while gathering and in range
                                                // of an assigned drop, tap the attack button (LMB at screen
                                                // centre) to collect it. The gather driver sets mineralTapActive.
                                                "if (" + SOCKET_INPUT_STATE + ".isMineralGather) {\n" +
                                                "    long _skMn = System.currentTimeMillis();\n" +
                                                "    try {\n" +
                                                "        int[] _skMw = new int[1]; int[] _skMh = new int[1];\n" +
                                                "        Class _skMG = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method _skMfbs = _skMG.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "            new Class[]{long.class, int[].class, int[].class});\n" +
                                                "        _skMfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skMw, _skMh});\n" +
                                                "        float _skMa = " + SOCKET_INPUT_STATE + ".mineralTapAngle;\n" +
                                                "        int _skMcx = _skMw[0] / 2 + (int)(150.0f * (float)java.lang.Math.cos((double)_skMa));\n" +
                                                "        int _skMcy = _skMh[0] / 2 - (int)(150.0f * (float)java.lang.Math.sin((double)_skMa));\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".mineralTapActive) {\n" +
                                                "            if (" + SOCKET_INPUT_STATE + ".mineralTapHeld) {\n" +
                                                "                if (_skMn >= " + SOCKET_INPUT_STATE + ".mineralTapReleaseAt) {\n" +
                                                "                    this.__skAttackRelease(_skMcx, _skMcy);\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".mineralTapHeld = false;\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".mineralTapNextAt = _skMn + 160L;\n" +
                                                "                }\n" +
                                                "            } else if (_skMn >= " + SOCKET_INPUT_STATE + ".mineralTapNextAt) {\n" +
                                                "                this.setMousePosition(_skMcx, _skMcy);\n" +
                                                "                this.mouseMoved(this._tickStamp, _skMcx, _skMcy, false);\n" +
                                                "                this.__skAttackPress(_skMcx, _skMcy);\n" +
                                                "                " + SOCKET_INPUT_STATE + ".mineralTapHeld = true;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".mineralTapReleaseAt = _skMn + 40L;\n" +
                                                "            }\n" +
                                                "        } else if (" + SOCKET_INPUT_STATE + ".mineralTapHeld) {\n" +
                                                "            this.__skAttackRelease(_skMcx, _skMcy);\n" +
                                                "            " + SOCKET_INPUT_STATE + ".mineralTapHeld = false;\n" +
                                                "        }\n" +
                                                "    } catch (Exception _skME) {}\n" +
                                                "}\n" +
                                                // KEY pickup tap: a ONE-SHOT attack tap toward keyTapAngle. The
                                                // KEY step sets keyTapFire; this fires exactly one press+release
                                                // then clears it (attack toggles carry, so it must never repeat).
                                                "if (" + SOCKET_INPUT_STATE + ".keyTapFire || " + SOCKET_INPUT_STATE + ".keyTapHeld) {\n" +
                                                "    long _skKn = System.currentTimeMillis();\n" +
                                                "    try {\n" +
                                                "        int[] _skKw = new int[1]; int[] _skKh = new int[1];\n" +
                                                "        Class _skKG = Class.forName(\"org.lwjgl.glfw.GLFW\");\n" +
                                                "        java.lang.reflect.Method _skKfbs = _skKG.getMethod(\"glfwGetFramebufferSize\",\n" +
                                                "            new Class[]{long.class, int[].class, int[].class});\n" +
                                                "        _skKfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skKw, _skKh});\n" +
                                                "        float _skKa = " + SOCKET_INPUT_STATE + ".keyTapAngle;\n" +
                                                "        int _skKcx = _skKw[0] / 2 + (int)(150.0f * (float)java.lang.Math.cos((double)_skKa));\n" +
                                                "        int _skKcy = _skKh[0] / 2 - (int)(150.0f * (float)java.lang.Math.sin((double)_skKa));\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".keyTapHeld) {\n" +
                                                "            if (_skKn >= " + SOCKET_INPUT_STATE + ".keyTapReleaseAt) {\n" +
                                                "                this.__skAttackRelease(_skKcx, _skKcy);\n" +
                                                "                " + SOCKET_INPUT_STATE + ".keyTapHeld = false;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".keyTapFire = false;\n" +
                                                "            }\n" +
                                                "        } else if (" + SOCKET_INPUT_STATE + ".keyTapFire) {\n" +
                                                "            this.setMousePosition(_skKcx, _skKcy);\n" +
                                                "            this.mouseMoved(this._tickStamp, _skKcx, _skKcy, false);\n" +
                                                "            this.__skAttackPress(_skKcx, _skKcy);\n" +
                                                "            " + SOCKET_INPUT_STATE + ".keyTapHeld = true;\n" +
                                                "            " + SOCKET_INPUT_STATE + ".keyTapReleaseAt = _skKn + " + SOCKET_INPUT_STATE + ".keyTapHoldMs;\n" +
                                                "        }\n" +
                                                "    } catch (Exception _skKE) {}\n" +
                                                "}\n" +
                                                // Alt: dash via Shift+X.
                                                "if (!" + SOCKET_INPUT_STATE + ".isMainAccount()) {\n" +

                                                "    if (" + SOCKET_INPUT_STATE + ".pendingDashPress) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".pendingDashPress = false;\n" +
                                                // Follow (Ctrl+F) OR breadcrumb-follow (routine/SNARBY) — same widened
                                                // gate as the shield block, so bot-triggered dashes work in the cycle.
                                                "        if ((" + SOCKET_INPUT_STATE + ".isAutoFollowing || " + SOCKET_INPUT_STATE + ".isBreadcrumbFollow) && !" +
                                                SOCKET_INPUT_STATE + ".dashActive) {\n" +
                                                "            try {\n" +
                                                // Drop the shield first: with the classic scheme dodge shares the
                                                // defend key, so a held shield would swallow the dash edge.
                                                "                if (" + SOCKET_INPUT_STATE + ".shieldActive) {\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".shieldActive = false;\n"
                                                +
                                                "                    this.__skShieldRelease();\n" +
                                                "                }\n" +
                                                // Face the main's bearing FIRST (manual dashes carry an angle);
                                                // otherwise the alt dashes wherever it happened to be looking.
                                                // A bare "DASH 1" (SNARBY boss-dodge) sets no angle and is unchanged.
                                                "                if (" + SOCKET_INPUT_STATE + ".pendingDashHasAngle) {\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".pendingDashHasAngle = false;\n" +
                                                "                    this.__skAimAt(" + SOCKET_INPUT_STATE + ".pendingDashAngle);\n" +
                                                "                }\n" +
                                                "                this.__skDashPress();\n" +
                                                "                " + SOCKET_INPUT_STATE + ".dashActive = true;\n" +
                                                "            } catch (Exception _skDE) {}\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "    if (" + SOCKET_INPUT_STATE + ".pendingDashRelease) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".pendingDashRelease = false;\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".dashActive) {\n" +
                                                "            this.__skDashRelease();\n" +
                                                "            " + SOCKET_INPUT_STATE + ".dashActive = false;\n" +
                                                "        }\n" +
                                                "    }\n" +
                                                "}\n" +
                                                // Alt: shield via held X.
                                                "if (!" + SOCKET_INPUT_STATE + ".isMainAccount()) {\n" +

                                                "    if (" + SOCKET_INPUT_STATE + ".pendingShieldPress) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".pendingShieldPress = false;\n" +
                                                // Follow (Ctrl+F) OR breadcrumb-follow (routine/SNARBY) — so alts shield
                                                // during a campaign even without Ctrl+F held.
                                                "        if ((" + SOCKET_INPUT_STATE + ".isAutoFollowing || " + SOCKET_INPUT_STATE + ".isBreadcrumbFollow) && !" +
                                                SOCKET_INPUT_STATE + ".dashActive) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".shieldActive = true;\n" +
                                                "        }\n" +
                                                "    }\n" +

                                                "    if (" + SOCKET_INPUT_STATE + ".pendingShieldRelease) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".pendingShieldRelease = false;\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".shieldActive) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".shieldActive = false;\n" +
                                                "            this.__skShieldRelease();\n" +
                                                "        }\n" +
                                                "    }\n" +

                                                "    if (" + SOCKET_INPUT_STATE + ".shieldActive && (" +
                                                SOCKET_INPUT_STATE + ".isAutoFollowing || " + SOCKET_INPUT_STATE + ".isBreadcrumbFollow) && !" +
                                                SOCKET_INPUT_STATE + ".dashActive) {\n" +
                                                "        try {\n" +
                                                // Periodically release+re-press (both in this SAME poll -> no shield gap) to
                                                // refresh the DOWN edge. When an alt dies the game drops the shield, but a
                                                // continuously-held X won't re-raise it on revive (the game latches 'X held'
                                                // through the defeat) — it needs a fresh down-edge, which this supplies.
                                                "            if (System.currentTimeMillis() - " + SOCKET_INPUT_STATE + ".shieldRepressAt >= 250L) {\n" +
                                                "                this.__skShieldRelease();\n" +
                                                "                " + SOCKET_INPUT_STATE + ".shieldRepressAt = System.currentTimeMillis();\n" +
                                                "            }\n" +
                                                "            this.__skShieldPress();\n" +
                                                "        } catch (Exception _skSE) {}\n" +
                                                "    }\n" +

                                                "}\n" +
                                                // Alt: RMB fire hold, aim-update while held, release on main release.
                                                "if (!" + SOCKET_INPUT_STATE + ".isMainAccount()) {\n" +

                                                "    if (" + SOCKET_INPUT_STATE + ".pendingWeaponPress) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".pendingWeaponPress = false;\n" +
                                                // Honor the press while following (mirror the main's fire) or
                                                // during a PvP farm (the suicide-feed bomb placement).
                                                "        if (" + SOCKET_INPUT_STATE + ".isAutoFollowing || " + SOCKET_INPUT_STATE + ".isAutoPvpQueue) {\n"
                                                +
                                                "            try {\n" +
                                                "                float _skAng = " + SOCKET_INPUT_STATE
                                                + ".pendingFireAngle;\n" +
                                                "                int[] _skFbw = new int[1]; int[] _skFbh = new int[1];\n"
                                                +
                                                "                Class _skGlfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n"
                                                +
                                                "                java.lang.reflect.Method _skGfbs = _skGlfw.getMethod(\"glfwGetFramebufferSize\",\n"
                                                +
                                                "                    new Class[]{long.class, int[].class, int[].class});\n"
                                                +
                                                "                _skGfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skFbw, _skFbh});\n"
                                                +
                                                "                int _skFmx = _skFbw[0] / 2 +\n" +
                                                "                    (int)(150.0f * (float)java.lang.Math.cos((double)_skAng));\n"
                                                +
                                                "                int _skFmy = _skFbh[0] / 2 -\n" +
                                                "                    (int)(150.0f * (float)java.lang.Math.sin((double)_skAng));\n"
                                                +
                                                "                " + SOCKET_INPUT_STATE + ".pendingFireX = _skFmx;\n" +
                                                "                " + SOCKET_INPUT_STATE + ".pendingFireY = _skFmy;\n" +
                                                "                this.__skAttackPress(_skFmx, _skFmy);\n" +
                                                "                " + SOCKET_INPUT_STATE + ".fireActive = true;\n" +
                                                "            } catch (Exception _skFE) {}\n" +
                                                "        }\n" +
                                                "    }\n" +

                                                "    if (" + SOCKET_INPUT_STATE + ".fireActive && " +
                                                SOCKET_INPUT_STATE + ".pendingWeaponAimDirty) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".pendingWeaponAimDirty = false;\n" +
                                                "        try {\n" +
                                                "            float _skAng = " + SOCKET_INPUT_STATE
                                                + ".pendingFireAngle;\n" +
                                                "            int[] _skFbw = new int[1]; int[] _skFbh = new int[1];\n" +
                                                "            Class _skGlfw = Class.forName(\"org.lwjgl.glfw.GLFW\");\n"
                                                +
                                                "            java.lang.reflect.Method _skGfbs = _skGlfw.getMethod(\"glfwGetFramebufferSize\",\n"
                                                +
                                                "                new Class[]{long.class, int[].class, int[].class});\n"
                                                +
                                                "            _skGfbs.invoke(null, new Object[]{new Long(this.getWindow()), _skFbw, _skFbh}) ;\n"
                                                +
                                                "            int _skFmx = _skFbw[0] / 2 +\n" +
                                                "                (int)(150.0f * (float)java.lang.Math.cos((double)_skAng));\n"
                                                +
                                                "            int _skFmy = _skFbh[0] / 2 -\n" +
                                                "                (int)(150.0f * (float)java.lang.Math.sin((double)_skAng));\n"
                                                +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireX = _skFmx;\n" +
                                                "            " + SOCKET_INPUT_STATE + ".pendingFireY = _skFmy;\n" +
                                                "            this.setMousePosition(_skFmx, _skFmy);\n" +
                                                "            this.mouseMoved(this._tickStamp, _skFmx, _skFmy, false);\n"
                                                +
                                                "            this.__skAttackPress(_skFmx, _skFmy);\n" +
                                                "        } catch (Exception _skFAE) {}\n" +
                                                "    }\n" +

                                                "    if (" + SOCKET_INPUT_STATE + ".pendingWeaponRelease) {\n" +
                                                "        " + SOCKET_INPUT_STATE + ".pendingWeaponRelease = false;\n" +
                                                "        if (" + SOCKET_INPUT_STATE + ".fireActive) {\n" +
                                                "            " + SOCKET_INPUT_STATE + ".fireActive = false;\n" +
                                                "            try {\n" +
                                                "                this.__skAttackRelease(\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".pendingFireX,\n" +
                                                "                    " + SOCKET_INPUT_STATE + ".pendingFireY);\n" +
                                                "            } catch (Exception _skFRE) {}\n" +
                                                "        }\n" +
                                                "    }\n" +

                                                "}\n" +
                                                // Movement-key injection: alts always; the MAIN too while a bot
                                                // system drives it (loot mode / path test / routine) — they all
                                                // populate the same DOWN set as auto-follow.
                                                "if (!" + SOCKET_INPUT_STATE + ".isMainAccount() || " + SOCKET_INPUT_STATE + ".mainMovementDriven()) {\n" +
                                                "    String[] keys = " + SOCKET_INPUT_STATE + ".getDownKeys();\n" +
                                                "    for (int i = 0; i < keys.length; i++) {\n" +
                                                "        int mappedCode = " + SOCKET_INPUT_STATE
                                                + ".getKeyCode(keys[i]);\n" +
                                                "        if (mappedCode != -1) {\n" +
                                                "            this.dispatchKeyEvent(this.getFocus(), new "
                                                + KEY_EVENT_CLASS +
                                                "                (this, this._tickStamp, this._modifiers, 0, '\\u0000', mappedCode, false));\n"
                                                +
                                                "        }\n" +
                                                "    }\n" +
                                                "}");
                cc.writeFile("out");
        }

        private static void patchController(ClassPool pool, String outDir,
                        String tudeyController, String tudeyView, String actorWrapper,
                        String actorClass, String transTypeName,
                        String viewField, String sceneObjField, String pawnIdField,
                        String actorMapField, String getIdMethod, String getTransMethod) throws Exception {

                // Inject a public getter into ACTOR_WRAPPER to expose the private _actor field.
                // This avoids reflection overhead in every tick() invocation.
                CtClass wrapperClass = pool.get(actorWrapper);
                boolean hasGetter = false;
                for (CtMethod m : wrapperClass.getDeclaredMethods()) {
                        if ("__skGetActor".equals(m.getName())) {
                                hasGetter = true;
                                break;
                        }
                }
                if (!hasGetter) {
                        CtMethod getter = CtNewMethod.make(
                                        "public " + actorClass + " __skGetActor() { return this._actor; }",
                                        wrapperClass);
                        wrapperClass.addMethod(getter);
                }
                wrapperClass.writeFile(outDir);

                String SIS = SOCKET_INPUT_STATE;
                String AJ = "com.threerings.projectx.social.AutoJoiner";

                String template =
                                // Cache the game ctx so off-thread code (the UDP listener,
                                // e.g. floor resolution) can reach dungeon-party data.
                                "try { " + SIS + "._cachedCtx = this._ctx; } catch (Exception _skCtxE) {}\n" +
                                // Barrier hotkey (G): invoked only while a press is pending
                                // (flagged by the main's poll or an alt's BARRIER message).
                                "if (" + SIS + ".pendingBarrierUse) {\n" +
                                                "    try { " + SIS + ".tryUseBarrier((Object) this._ctx); } catch (Exception _skE3) {}\n"
                                                +
                                                "}\n" +
                                                // Vial hotkey (H): invoked only while a press is pending.
                                                "if (" + SIS + ".pendingVialUse) {\n" +
                                                "    try { " + SIS + ".tryUseVial((Object) this._ctx); } catch (Exception _skE7) {}\n"
                                                +
                                                "}\n" +
                                                // Sprite hotkeys (1/2/3): invoked while a press is pending or a
                                                // queued press still awaits its dU() release.
                                                "if (" + SIS + ".pendingSpriteSlot >= 0 || " + SIS
                                                + ".spriteReleaseKey != -1) {\n" +
                                                "    try { " + SIS + ".tryUseSprite((Object) " + SIS
                                                + ".dungeonClient); } catch (Exception _skE4) {}\n" +
                                                "}\n" +
                                                // Auto-queue Blast Network (Ctrl+Q): runs on every account while
                                                // enabled; the method self-throttles. Anti-idle arms on all
                                                // accounts so winning-team alts don't idle-kick either.
                                                "if (" + SIS + ".isAutoPvpQueue) {\n" +
                                                "    try { " + SIS + ".tryPvpQueue((Object) this._ctx); } catch (Exception _skE5) {}\n"
                                                +
                                                "    try { " + SIS + ".tickPvpAntiIdle((Object) this._ctx); } catch (Exception _skE6) {}\n"
                                                +
                                                "}\n" +
                                                // Combat bot (Ctrl+B): runs on every account while enabled;
                                                // self-throttled. Drives weapon select + target acquisition.
                                                "if (" + SIS + ".isCombatBot) {\n" +
                                                "    try { " + SIS + ".tickCombatBot((Object) " + SIS + ".dungeonClient); } catch (Exception _skE8) {}\n"
                                                +
                                                "}\n" +
                                                // Scene scan (Ctrl+D): one-shot dump of the loaded scene to
                                                // debug.log. Sent to the main only; edge-triggered per press.
                                                "if (" + SIS + ".sceneScanPending) {\n" +
                                                "    " + SIS + ".sceneScanPending = false;\n" +
                                                "    try { " + SIS + ".dumpScene((Object) " + SIS + ".dungeonClient); } catch (Exception _skE9) {}\n"
                                                +
                                                "}\n" +
                                                // Position dump (Ctrl+J): one-shot dump of the pawn's map coords.
                                                "if (" + SIS + ".dumpPosPending) {\n" +
                                                "    " + SIS + ".dumpPosPending = false;\n" +
                                                "    try { " + SIS + ".dumpPlayerPos((Object) " + SIS + ".dungeonClient); } catch (Exception _skE10) {}\n"
                                                +
                                                "}\n" +
                                                // Loot mode (Ctrl+K): MAIN only. Plans & drives a shortest loot
                                                // sweep of nearby heat/crowns, then returns to the start. Alts
                                                // trail via auto-follow.
                                                "if (" + SIS + ".isLootMode && " + SIS + ".isMainAccount()) {\n" +
                                                "    try { " + SIS + ".tickLootMode((Object) " + SIS + ".dungeonClient); } catch (Exception _skE11) {}\n"
                                                +
                                                "}\n" +
                                                // Path test (Ctrl+P): MAIN only. A*-walks the main to a fixed
                                                // target to validate pathfinding + movement.
                                                "if (" + SIS + ".isPathTest && " + SIS + ".isMainAccount()) {\n" +
                                                "    try { " + SIS + ".tickPathTest((Object) " + SIS + ".dungeonClient); } catch (Exception _skE12) {}\n"
                                                +
                                                "}\n" +
                                                // Stage routine (Ctrl+R): MAIN only. Runs the hardcoded step
                                                // machine (pathfinding + combat + loot + shoot).
                                                "if (" + SIS + ".isRoutineActive && " + SIS + ".isMainAccount()) {\n" +
                                                "    try { " + SIS + ".tickRoutine((Object) " + SIS + ".dungeonClient); } catch (Exception _skE13) {}\n"
                                                +
                                                "}\n" +
                                                // Campaign (Ctrl+R full run): MAIN only. Between floors, auto-starts
                                                // the next floor's routine once the whole party has loaded in.
                                                "if (" + SIS + ".isMainAccount()) {\n" +
                                                "    if (" + SIS + ".campaignActive) {\n" +
                                                "        try { " + SIS + ".tickCampaign((Object) " + SIS + ".dungeonClient); } catch (Exception _skE14) {}\n"
                                                +
                                                "    }\n" +
                                                // Mission logger death watch + PARTY-WIPE detector: MAIN reads the whole
                                                // party's health DSet, counts members hitting 0 hp, and aborts when the
                                                // WHOLE party has been down for 30s. Gated on campaignActive OR a live
                                                // routine — deliberately wider than tickCampaign: a wiped party leaves the
                                                // routine (and its combat loop) ticking, so if campaignActive were ever
                                                // false while a routine ran, the old gate would have silenced the very
                                                // watchdog meant to catch it. tickDeathWatch is ~1Hz and self-gated, so
                                                // the wider gate costs nothing.
                                                "    if (" + SIS + ".campaignActive || " + SIS + ".isRoutineActive) {\n" +
                                                "        try { " + SIS + ".tickDeathWatch((Object) " + SIS + ".dungeonClient); } catch (Exception _skEDW) {}\n"
                                                +
                                                "    }\n" +
                                                "}\n" +
                                                // Alt lobby forge pass: FORGEALL received — tickForgePass waits until
                                                // this client stands in the mission lobby, then forges (flag cleared
                                                // there, not here, so the wait survives scene changes).
                                                "if (" + SIS + ".forgePassPending) {\n" +
                                                "    try { " + SIS + ".tickForgePass(); } catch (Exception _skEFG) {}\n"
                                                +
                                                "}\n" +
                                                // DIAGNOSTIC (window dump): MAIN only. Armed at the Snarbolax floor's
                                                // completion; dumps the live GUI window tree so the reward screen that
                                                // pops up next can be identified. Uses this._ctx so it works in town too.
                                                "if (" + SIS + ".windowDumpArmed && " + SIS + ".isMainAccount()) {\n" +
                                                "    try { " + SIS + ".tickWindowDump((Object) this._ctx); } catch (Exception _skE15) {}\n"
                                                +
                                                "}\n" +
                                // Main: always broadcast pawn position — cheap loopback UDP, no reason to gate
                                // it.
                                "if (" + SIS + ".isMainAccount()) {\n" +
                                                "    try {\n" +
                                                "        " + tudeyView + " _skVm = this." + viewField + ";\n" +
                                                "        if (_skVm != null) {\n" +
                                                "            " + actorWrapper + " _skMw = (" + actorWrapper + ")" +
                                                "                _skVm." + actorMapField + ".get(this." + pawnIdField
                                                + ");\n" +
                                                "            if (_skMw != null) {\n" +
                                                "                " + actorClass + " _skMa = _skMw.__skGetActor();\n" +
                                                "                if (_skMa != null) {\n" +
                                                "                    " + transTypeName + " _skMp = _skMa."
                                                + getTransMethod + "();\n" +
                                                "                    if (_skMp != null) {\n" +
                                                "                        " + SIS
                                                + ".broadcastPosition(_skMp.x, _skMp.y);\n" +
                                                "                    }\n" +
                                                // Main auto-consumable (health/remedy) — ONLY while Ctrl+R campaign mode is
                                                // active. Reuses the alt path: heal below 1/3 HP, cure negative status. _skMa
                                                // is the main's own actor (for the status check).
                                                "                    if (" + SIS + ".campaignActive) {\n" +
                                                "                        try { " + SIS + ".tryUseConsumable((Object) " + SIS + ".dungeonClient, (Object) this._ctx, (Object) _skMa); } catch (Exception _skECons) {}\n" +
                                                "                    }\n" +
                                                "                }\n" +
                                                "            }\n" +
                                                "        }\n" +
                                                "    } catch (Exception _skE2) {}\n" +
                                                "}\n" +
                                                // Alt: follow the main. Breadcrumb-follow (routine) retraces the
                                                // main's A* path; otherwise naive auto-follow (Ctrl+F). The block
                                                // runs for either so the routine works without Ctrl+F; the target
                                                // computation below picks breadcrumb vs main-position.
                                                // AUTO-JOIN: only while a mode that wants the party together is ON —
                                                // Ctrl+F (isAutoFollowing) or the Ctrl+R cycle (user-reverted 2026-08-07
                                                // from unconditional; alts must not chase the main's instance otherwise).
                                                // Alts never see campaignActive (the ROUTINE toggle goes to the main
                                                // only), so the alt-side Ctrl+R mirror is autoAdvanceOn: latched on
                                                // EVERY client by the AUTOADVANCE 1/0 broadcast at cycle start/stop and
                                                // held through lobby fills + relaunch windows — exactly the windows
                                                // where isBreadcrumbFollow reads false and joining matters most (the
                                                // reason this was once unconditional). AutoJoiner still self-throttles
                                                // (3s) and no-ops when already joined.
                                                "if (!" + SIS + ".isMainAccount() && (" + SIS + ".isAutoFollowing || " + SIS + ".autoAdvanceOn)) {\n" +
                                                "    try { " + AJ + ".checkAutoJoin((java.lang.Object)this._ctx, " + SK_CONFIG + ".MAIN_ACCOUNT); } catch (Exception _skE0) {}\n" +
                                                "}\n" +
                                                "if ((" + SIS + ".isAutoFollowing || " + SIS + ".isBreadcrumbFollow) && !" + SIS + ".isMainAccount() && !" + SIS + ".isMineralGather && !" + SIS + ".isRoutineShootAlt) {\n" +
                                                "    try {\n" +
                                                "        " + tudeyView + " _skView = this." + viewField + ";\n" +
                                                "        if (_skView != null) {\n" +
                                                "            int _skMyId = this." + pawnIdField + ";\n" +
                                                "            com.threerings.crowd.data.PlaceObject _skSc = this."
                                                + sceneObjField + ";\n" +
                                                "            int _skTid = -1;\n" +
                                                "            if (_skSc != null && _skSc.occupantInfo != null) {\n" +
                                                "                java.util.Iterator _skOit = _skSc.occupantInfo.iterator();\n"
                                                +
                                                "                while (_skOit.hasNext()) {\n" +
                                                "                    Object _skOi = _skOit.next();\n" +
                                                "                    if (!(_skOi instanceof com.threerings.tudey.data.TudeyOccupantInfo)) continue;\n"
                                                +
                                                "                    com.threerings.tudey.data.TudeyOccupantInfo _skToi =\n"
                                                +
                                                "                        (com.threerings.tudey.data.TudeyOccupantInfo) _skOi;\n"
                                                +
                                                "                    if (" + SK_CONFIG
                                                + ".MAIN_ACCOUNT.equals(_skToi.username != null ? _skToi.username.toString() : \"\")) {\n"
                                                +
                                                "                        _skTid = _skToi.pawnId;\n" +
                                                "                        break;\n" +
                                                "                    }\n" +
                                                "                }\n" +
                                                "            }\n" +
                                                "            if (_skTid == -1) {\n" +
                                                "                " + SIS + ".clearMovementKeys();\n" +
                                                "            } else {\n" +
                                                "                " + actorWrapper + " _skMyW =\n" +
                                                "                    (" + actorWrapper + ") _skView." + actorMapField
                                                + ".get(_skMyId);\n" +
                                                "                " + actorWrapper + " _skTW =\n" +
                                                "                    (" + actorWrapper + ") _skView." + actorMapField
                                                + ".get(_skTid);\n" +
                                                "                if (_skMyW != null && _skTW != null) {\n" +
                                                "                    " + actorClass
                                                + " _skMyActor = _skMyW.__skGetActor();\n" +
                                                "                    " + actorClass
                                                + " _skTActor  = _skTW.__skGetActor();\n" +
                                                "                    if (_skMyActor != null && _skTActor != null) {\n" +
                                                "                        " + transTypeName + " _skMt = _skTActor."
                                                + getTransMethod + "();\n" +
                                                "                        " + transTypeName + " _skLt = _skMyActor."
                                                + getTransMethod + "();\n" +
                                                "                        if (_skMt != null && _skLt != null) {\n" +
                                                "                            " + SIS + ".clearMovementKeys();\n" +
                                                // Use broadcast position when fresh (<500ms), else fall back to
                                                // server-reported
                                                // position.
                                                "                            boolean _skFresh = (System.currentTimeMillis()\n"
                                                +
                                                "                                - " + SIS
                                                + ".mainBroadcastTime) < 500L;\n" +
                                                // Breadcrumb-follow: aim at the next point on the main's retraced
                                                // path. If the trail is empty (caught up) or off, fall back to the
                                                // main's live position — the naive Ctrl+F behaviour, unchanged.
                                                "                            float[] _skCrumb = null;\n" +
                                                "                            if (" + SIS + ".isBreadcrumbFollow) { _skCrumb = " + SIS
                                                + ".crumbFollowTarget(_skLt.x, _skLt.y); }\n" +
                                                "                            float _skFx; float _skFy;\n" +
                                                "                            if (_skCrumb != null) { _skFx = _skCrumb[0]; _skFy = _skCrumb[1]; }\n" +
                                                "                            else { _skFx = _skFresh ? " + SIS
                                                + ".mainBroadcastX : _skMt.x; _skFy = _skFresh ? " + SIS
                                                + ".mainBroadcastY : _skMt.y; }\n" +
                                                "                            " + SIS + ".logFollowSource(_skFresh);\n" +
                                                "                            float _skDx = _skFx - _skLt.x;\n" +
                                                "                            float _skDy = _skFy - _skLt.y;\n" +
                                                "                            " + SIS + ".altFollowDx = _skDx;\n" +
                                                "                            " + SIS + ".altFollowDy = _skDy;\n" +
                                                "                            " + SIS
                                                + ".setMovementFromDelta(_skDx, _skDy);\n" +
                                                "                            if (_skDx * _skDx + _skDy * _skDy > 25.0f)\n"
                                                +
                                                "                                " + SIS + ".triggerDash();\n" +
                                                // Auto-consumable (vitapod / remedy): actor is in scope here as
                                                // _skMyActor.
                                                "                            " + SIS + ".tryUseConsumable(\n" +
                                                "                                " + SIS + ".dungeonClient,\n" +
                                                "                                (Object) this._ctx,\n" +
                                                "                                (Object) _skMyActor);\n" +
                                                "                        }\n" +
                                                "                    }\n" +
                                                "                }\n" +
                                                "            }\n" +
                                                "        }\n" +
                                                "    } catch (Exception _skE1) {}\n" +
                                                "}";

                // Alt: mineral gather — drive this alt to its assigned drop (the follow
                // block above is gated off while gathering) and let the poll tap attack.
                template += "\nif (" + SIS + ".isMineralGather && !" + SIS + ".isMainAccount()) {\n" +
                                "    try {\n" +
                                "        " + tudeyView + " _skMgV = this." + viewField + ";\n" +
                                "        if (_skMgV != null) {\n" +
                                "            int _skMgId = this." + pawnIdField + ";\n" +
                                "            " + actorWrapper + " _skMgW = (" + actorWrapper + ") _skMgV." + actorMapField
                                + ".get(_skMgId);\n" +
                                "            if (_skMgW != null) {\n" +
                                "                " + actorClass + " _skMgA = _skMgW.__skGetActor();\n" +
                                "                if (_skMgA != null) {\n" +
                                "                    " + transTypeName + " _skMgT = _skMgA." + getTransMethod + "();\n" +
                                "                    if (_skMgT != null) {\n" +
                                "                        " + SIS + ".tickMineralGatherAlt((Object) _skMgV, _skMgT.x, _skMgT.y, _skMgId);\n" +
                                "                    }\n" +
                                "                }\n" +
                                "            }\n" +
                                "        }\n" +
                                "    } catch (Exception _skMgE) {}\n" +
                                "}\n";

                // Alt: SHOOT reroute — fire weapon 2 at the broadcast target from this
                // alt's own position (the follow block above is gated off while shooting).
                template += "\nif (" + SIS + ".isRoutineShootAlt && !" + SIS + ".isMainAccount()) {\n" +
                                "    try {\n" +
                                "        " + tudeyView + " _skSaV = this." + viewField + ";\n" +
                                "        if (_skSaV != null) {\n" +
                                "            int _skSaId = this." + pawnIdField + ";\n" +
                                "            " + actorWrapper + " _skSaW = (" + actorWrapper + ") _skSaV." + actorMapField
                                + ".get(_skSaId);\n" +
                                "            if (_skSaW != null) {\n" +
                                "                " + actorClass + " _skSaA = _skSaW.__skGetActor();\n" +
                                "                if (_skSaA != null) {\n" +
                                "                    " + transTypeName + " _skSaT = _skSaA." + getTransMethod + "();\n" +
                                "                    if (_skSaT != null) {\n" +
                                "                        " + SIS + ".tickRoutineShootAlt((Object) _skSaV, _skSaT.x, _skSaT.y, _skSaId);\n" +
                                "                    }\n" +
                                "                }\n" +
                                "            }\n" +
                                "        }\n" +
                                "    } catch (Exception _skSaE) {}\n" +
                                "}\n";

                // Gear HUD: init once (gated internally), update each tick
                template += "\ntry {\n" +
                                "    com.threerings.opengl.gui.HeatHudPanel.init((Object) this._ctx);\n" +
                                "    com.threerings.opengl.gui.HeatHudPanel.tick((Object) this._ctx);\n" +
                                "} catch (Throwable _skHe) {}\n";

                CtClass eqClass = pool.get(tudeyController);
                eqClass.getDeclaredMethod("tick", new CtClass[] { CtClass.floatType })
                                .insertBefore(template);
                eqClass.writeFile(outDir);
        }

        private static void patchForgeAdapter(ClassPool pool, String forgeWindow,
                        String btnField, String upgradeMethod) throws Exception {
                CtClass nClass = pool.get(forgeWindow);

                nClass.getDeclaredMethod("wasAdded").insertAfter(
                                SOCKET_INPUT_STATE + ".currentForgeBtn = this." + btnField + ";");
                nClass.getDeclaredMethod("wasRemoved").insertAfter(
                                SOCKET_INPUT_STATE + ".currentForgeBtn = null;");

                // Find the forge button's action listener: the (ActionEvent)V method that is
                // NOT lambda$new$0.
                // lambda$new$0 is the level-selector listener (calls SX); the forge button has
                // its own method.
                CtClass actionEventClass = pool.get("com.threerings.opengl.gui.event.ActionEvent");
                CtMethod forgeBtnListener = null;
                for (CtMethod m : nClass.getDeclaredMethods()) {
                        CtClass[] params = m.getParameterTypes();
                        if (params.length == 1
                                        && params[0].getName().equals("com.threerings.opengl.gui.event.ActionEvent")
                                        && m.getReturnType() == CtClass.voidType
                                        && !"lambda$new$0".equals(m.getName())) {
                                forgeBtnListener = m;
                                break;
                        }
                }
                if (forgeBtnListener == null)
                        throw new RuntimeException("Cannot find forge button action listener on " + forgeWindow);
                forgeBtnListener.insertBefore(
                                FORGE_TRACKER + ".debug(\"forgeBtnListener fired isCtrl=\" + " + SOCKET_INPUT_STATE
                                                + ".isCtrlDown);\n"
                                                +
                                                "if (" + SOCKET_INPUT_STATE + ".isCtrlDown) {\n" +
                                                "    new " + FORGE_ALL_ADAPTER + "(this).start();\n" +
                                                "    return;\n" +
                                                "}\n");

                // Intercept the forge service call inside the upgrade method so individual
                // (non-loop)
                // forges are also tracked. We wrap the game's own ResultListener with a
                // chaining one
                // that forwards to the original and then records to ForgeTracker.
                final String resultInternal = RESULT_LISTENER_CLASS.replace('.', '/');
                CtMethod ctUpgrade = nClass.getDeclaredMethod(upgradeMethod);
                ctUpgrade.instrument(new ExprEditor() {
                        public void edit(MethodCall mc) throws CannotCompileException {
                                String sig = mc.getSignature();
                                if (!sig.startsWith("(J") || !sig.contains("L" + resultInternal + ";)V"))
                                        return;
                                // 3-param (J I L) → $1=oid, $2=level, $3=listener
                                // 4-param (J I I L) → $1=oid, $3=level, $4=listener
                                // Route through forgeWithTracking so the chaining listener is typed correctly.
                                // $args[n]=$proceed($$) doesn't work — Javassist regenerates args from locals.
                                boolean fourParam = sig.startsWith("(JII");
                                String levelArg = fourParam ? "$3" : "$2";
                                String listenerArg = fourParam ? "$4" : "$3";
                                String call = fourParam
                                                ? MAPPINGS + ".forgeWithTracking($0,$1,$2,$3,$4,this._ctx);"
                                                : MAPPINGS + ".forgeWithTracking($0,$1,0,$2,$3,this._ctx);";
                                mc.replace("{ " + call + " }");
                        }
                });

                nClass.writeFile("out");
        }

        // Suppress "Dismissing left-open window" log spam in dx.wasRemoved().
        // That method walks all remaining Root windows on scene exit and logs+dismisses
        // each one.
        // Our overlay windows are intentionally persistent (reinstated on the next
        // tick), so the
        // log is noise. We intercept Samskivert's Z.e() call and skip it for our two
        // classes.
        /**
         * Per-player damage tap: PartyObject$DamageEvent is the NamedEvent behind the
         * floating damage numbers (_sourceId/_actorId/_amount + popup Performance), and
         * each client receives it only for its OWN damage. Instrumenting its
         * applyToObject hook (b(DObject) — runs once per received event) feeds
         * SocketInputState.recordDamageEvent, giving every client an exact running
         * total of its own damage with zero attribution heuristics.
         */
        private static void patchDamageEvent(ClassPool pool, String outDir) throws Exception {
                // (The DamageMeter stub is created early in main, beside the window classes.)
                CtClass de = pool.get("com.threerings.projectx.dungeon.data.PartyObject$DamageEvent");
                CtMethod apply = null;
                for (CtMethod m : de.getDeclaredMethods()) {
                        CtClass[] p = m.getParameterTypes();
                        if (m.getReturnType() == CtClass.booleanType && p.length == 1
                                        && p[0].getName().equals("com.threerings.presents.dobj.DObject")) {
                                apply = m;
                                break;
                        }
                }
                if (apply == null)
                        throw new RuntimeException("Cannot find DamageEvent.applyToObject(DObject)");
                apply.insertBefore("com.threerings.opengl.gui.DamageMeter"
                                + ".recordDamageEvent(this.getName(), this._sourceId, this._actorId, this._amount);");
                de.writeFile(outDir);
        }


        /**
         * The name of the one method on {@code cls} matching {@code descriptor} whose BODY
         * references {@code typeFragment} (a class name, e.g. "DungeonInputFrame"). Obfuscated
         * method names shift on every re-obfuscation pass — 2026-07-30 moved the dungeon
         * client's whole d-block (dU/dV/dW -> dX/dY/dZ) — but what a method TOUCHES is
         * semantic and survives. Returns fallback when no unique match is found.
         */
        private static String methodByBodyRef(ClassPool pool, String cls, String descriptor,
                        final String typeFragment, String fallback) {
                try {
                        CtClass cc = pool.get(cls);
                        String hit = null;
                        for (CtMethod m : cc.getDeclaredMethods()) {
                                if (!m.getMethodInfo().getDescriptor().equals(descriptor))
                                        continue;
                                final boolean[] found = { false };
                                m.instrument(new ExprEditor() {
                                        public void edit(MethodCall mc) {
                                                if (mc.getClassName().contains(typeFragment))
                                                        found[0] = true;
                                        }

                                        public void edit(FieldAccess fa) {
                                                if (fa.getClassName().contains(typeFragment))
                                                        found[0] = true;
                                        }
                                });
                                if (!found[0])
                                        continue;
                                if (hit != null)
                                        return fallback; // ambiguous — don't guess
                                hit = m.getName();
                        }
                        return (hit != null) ? hit : fallback;
                } catch (Exception e) {
                        return fallback;
                }
        }

        /**
         * The name of the ONLY method on {@code cls} with {@code descriptor} (optionally
         * requiring public access). Used where the signature alone is unique — e.g. the
         * dungeon client declares exactly one (II)V (sprite press) and exactly one PUBLIC
         * (I)V (sprite release). Returns fallback unless exactly one matches.
         */
        private static String uniqueMethodByDescriptor(ClassPool pool, String cls, String descriptor,
                        boolean mustBePublic, String fallback) {
                try {
                        CtClass cc = pool.get(cls);
                        String hit = null;
                        for (CtMethod m : cc.getDeclaredMethods()) {
                                if (!m.getMethodInfo().getDescriptor().equals(descriptor))
                                        continue;
                                if (mustBePublic && !javassist.Modifier.isPublic(m.getModifiers()))
                                        continue;
                                if (hit != null)
                                        return fallback; // more than one — don't guess
                                hit = m.getName();
                        }
                        return (hit != null) ? hit : fallback;
                } catch (Exception e) {
                        return fallback;
                }
        }

        /**
         * {name, returnClass} of the unique 0-arg method on {@code cls} whose return type lives
         * in {@code returnPackage}. How the DUNGEON DIRECTOR is found: ctx declares exactly one
         * accessor returning a com.threerings.projectx.dungeon.client type, so neither the
         * accessor's obfuscated name nor the director's class letter has to be hardcoded.
         */
        private static String[] accessorByReturnPackage(ClassPool pool, String cls, String returnPackage,
                        String[] fallback) {
                try {
                        CtClass cc = pool.get(cls);
                        String[] hit = null;
                        for (CtMethod m : cc.getDeclaredMethods()) {
                                if (m.getParameterTypes().length != 0)
                                        continue;
                                String rt = m.getReturnType().getName();
                                if (!rt.startsWith(returnPackage + "."))
                                        continue;
                                if (rt.substring(returnPackage.length() + 1).contains("."))
                                        continue; // a sub-package, not the package itself
                                if (hit != null)
                                        return fallback; // ambiguous
                                hit = new String[] { m.getName(), rt };
                        }
                        return (hit != null) ? hit : fallback;
                } catch (Exception e) {
                        return fallback;
                }
        }

        private static void patchDxDismissLog(ClassPool pool, String outDir, String dismissLogClass)
                        throws Exception {
                CtClass dx = pool.get(dismissLogClass);
                CtMethod wasRemoved = dx.getDeclaredMethod("wasRemoved");
                wasRemoved.instrument(new ExprEditor() {
                        public void edit(MethodCall mc) throws CannotCompileException {
                                if (!"e".equals(mc.getMethodName()))
                                        return;
                                if (!mc.getClassName().contains("samskivert"))
                                        return;
                                // $1 = log message (Object), $2 = vararg params (Object[])
                                // params[1] is the Class object of the window being dismissed.
                                mc.replace(
                                                "{ if (!($1 != null" +
                                                                "     && \"Dismissing left-open window\".equals($1.toString()))) {"
                                                                +
                                                                "    $proceed($$);" +
                                                                "} }");
                        }
                });
                dx.writeFile(outDir);
        }

        // ── MappingsNames source generation ──────────────────────────────────────

        private static void writeMappingsNames(String outDir, Map<String, String> discovered)
                        throws Exception {
                StringBuilder sb = new StringBuilder();
                sb.append("package com.threerings.opengl.gui;\n\n");
                sb.append("// Auto-generated by Patcher at build time. Do not edit — re-run build.sh.\n");
                sb.append("public class MappingsNames {\n\n");

                // Stable constants (non-obfuscated; never change between game updates)
                sb.append("    // Stable framework names\n");
                sb.append(
                                "    public static final String RESULT_LISTENER_CLASS     = \"com.threerings.presents.client.A$c\";\n");
                sb.append(
                                "    public static final String CONFIRM_LISTENER_CLASS    = \"com.threerings.presents.client.A$a\";\n");
                sb.append(
                                "    public static final String CONFIG_MANAGER_CLASS      = \"com.threerings.config.ConfigManager\";\n");
                sb.append(
                                "    public static final String PLACE_OBJECT_CLASS        = \"com.threerings.crowd.data.PlaceObject\";\n");
                sb.append(
                                "    public static final String OCCUPANT_INFO_CLASS       = \"com.threerings.crowd.data.OccupantInfo\";\n");
                sb.append(
                                "    public static final String TUDEY_OCCUPANT_INFO_CLASS = \"com.threerings.tudey.data.TudeyOccupantInfo\";\n");
                sb.append(
                                "    public static final String KNIGHT_CLASS              = \"com.threerings.projectx.data.actor.Knight\";\n");
                sb.append(
                                "    public static final String ACTOR_CLASS               = \"com.threerings.tudey.data.actor.Actor\";\n");
                sb.append(
                                "    public static final String KEY_EVENT_CLASS           = \"com.threerings.opengl.gui.event.KeyEvent\";\n");
                sb.append(
                                "    public static final String CONTAINER_CLASS           = \"com.threerings.opengl.gui.Container\";\n");
                sb.append("    public static final String CONFIG_MANAGER_METHOD     = \"getConfigManager\"; // @Keep\n");
                sb.append(
                                "    public static final String ACTOR_FIELD               = \"_actor\";          // Presents convention\n");
                sb.append(
                                "    public static final String FORGE_CTX_FIELD           = \"_ctx\";            // Presents convention\n");
                sb.append("\n");

                // Discovered constants (obfuscated — values found by Patcher scanning the game
                // jar)
                sb.append("    // Discovered names — regenerated each build from game jar\n");
                for (Map.Entry<String, String> e : discovered.entrySet()) {
                        if (e.getValue() == null)
                                continue;
                        String val = e.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
                        sb.append("    public static final String ").append(e.getKey())
                                        .append(" = \"").append(val).append("\";\n");
                }
                sb.append("}\n");

                java.io.File outFile = new java.io.File(outDir, "MappingsNames.java");
                java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(outFile));
                pw.print(sb.toString());
                pw.close();
        }
}

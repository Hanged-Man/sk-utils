package com.threerings.opengl.gui;

import java.util.Locale;

/**
 * Shared reflection + actor-perception primitives, extracted from SocketInputState
 * ONE copy for the whole mod: SocketInputState,
 * MissionStats, PvpAutoQueuer, DamageMeter and SpriteFeeder all read through here —
 * the per-file private copies these replaced are gone.
 *
 *
 */
final class Reflect {

    private Reflect() {
    }

    // ── Superclass-walking field readers (null on absence/error, never throw) ──

    /** Reads an Object field by name, walking the superclass chain. */
    static Object readObjectFieldNullable(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Reads an int field by name, walking the superclass chain. */
    static Integer readIntFieldNullable(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return Integer.valueOf(f.getInt(obj));
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Reads a float field by name, walking the superclass chain. */
    static Float readFloatFieldNullable(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return Float.valueOf(f.getFloat(obj));
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Reads a numeric OBJECT field (e.g. a boxed {@code Long} like BattleSprite's
     * {@code _lastFull}) as a Long; null when absent, null-valued, or non-numeric.
     */
    static Long readLongObjFieldNullable(Object obj, String name) {
        Object v = readObjectFieldNullable(obj, name);
        return (v instanceof Number) ? Long.valueOf(((Number) v).longValue()) : null;
    }

    // ── Actor perception ──────────────────────────────────────────────────────

    /** Returns the raw Actor from a tudey actor-wrapper via the injected getter. */
    static Object getWrappedActor(Object wrapper) {
        try {
            return wrapper.getClass().getMethod("__skGetActor").invoke(wrapper);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns {x, y} of an actor's translation, or null. */
    static float[] actorPos(Object actor) {
        try {
            Object v = actor.getClass().getMethod(MappingsNames.GET_TRANSLATION_METHOD).invoke(actor);
            if (v == null)
                return null;
            return new float[] {
                    v.getClass().getField("x").getFloat(v),
                    v.getClass().getField("y").getFloat(v) };
        } catch (Exception e) {
            return null;
        }
    }

    /** Actor config path (e.g. "Character/NPC/Monster/Undead/..."), read from the Actor `_config` field. */
    static String actorConfigName(Object actor) {
        try {
            // Read the actor's config reference directly from the `_config` field
            // (the ConfigReference<ActorConfig> on the Actor base class). Do NOT
            // search for "a 0-arg method returning ConfigReference" — Monster has a
            // second such getter (QU() = its item drop, usually null), and which one
            // getMethods() yields first is unspecified, so the search could grab the
            // item ref and read null (this made alts never detect gun families).
            Object ref = readObjectFieldNullable(actor, "_config");
            if (ref == null)
                return "null";
            Object nm = ref.getClass().getMethod("getName").invoke(ref);
            return (nm == null) ? "null" : nm.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /** {x,y} from a Vector2f (public x/y), or null. */
    static float[] vec2xy(Object v) {
        if (v == null)
            return null;
        try {
            return new float[] { v.getClass().getField("x").getFloat(v), v.getClass().getField("y").getFloat(v) };
        } catch (Exception e) {
            return null;
        }
    }

    static String fmt(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }

    // ── World→screen aim ──────────────────────────────────────────────────────

    static final float BOT_AIM_VERTICAL_SCALE = 0.70710677f; // sin(45°) — the gameplay camera's depth foreshortening

    /** World→screen aim angle to (tx,ty) (foreshortening + Y-flip; combat bot uses the same formula). */
    static float aimAngleTo(float[] mp, float tx, float ty) {
        return (float) Math.atan2(-(ty - mp[1]) * BOT_AIM_VERTICAL_SCALE, (tx - mp[0]));
    }

    // ── Live PartyObject off a controller (cached field scan) ─────────────────

    private static java.lang.reflect.Field cachedPartyField = null; // aDc : PartyObject

    /** The live PartyObject read off a controller (cached field scan), or null. */
    static Object partyObjectOf(Object controller) {
        if (controller == null)
            return null; // no dungeon client yet (login/town) — spare findPartyField the NPE spray
        try {
            if (cachedPartyField == null)
                cachedPartyField = findPartyField(controller);
            return (cachedPartyField == null) ? null : cachedPartyField.get(controller);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.lang.reflect.Field findPartyField(Object controller) {
        try {
            Class<?> c = controller.getClass();
            while (c != null) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().equals("com.threerings.projectx.dungeon.data.PartyObject")) {
                        f.setAccessible(true);
                        return f;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // ── Status conditions ─────────────────────────────────────────────────────

    private static java.lang.reflect.Method cachedCondMethod = null; // Qv() : ConfigReference[]

    static boolean hasNegativeStatus(Object actor) {
        try {
            if (cachedCondMethod == null) {
                for (java.lang.reflect.Method m : actor.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType().isArray()
                            && m.getReturnType().getComponentType().getName()
                                    .equals("com.threerings.config.ConfigReference")) {
                        cachedCondMethod = m;
                        break;
                    }
                }
            }
            if (cachedCondMethod == null)
                return false;
            Object[] conds = (Object[]) cachedCondMethod.invoke(actor);
            if (conds == null)
                return false;
            for (Object cond : conds) {
                if (cond == null)
                    continue;
                String name = (String) cond.getClass().getMethod("getName").invoke(cond);
                if (isNegativeStatusName(name))
                    return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNegativeStatusName(String name) {
        if (name == null)
            return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("poison") || n.contains("fire") || n.contains("burn")
                || n.contains("freeze") || n.contains("frozen") || n.contains("shock")
                || n.contains("sleep") || n.contains("stun") || n.contains("haunt")
                || n.contains("curse");
    }

    /**
     * DEBUG (SNARBY stun-visibility probe): every 0-arg ConfigReference[] getter on
     * the actor whose returned array is non-empty, rendered as "getter=[name,name] …"
     * (each name = ConfigReference.getName()). Status conditions (stun/poison/etc.)
     * ride in one of these arrays — this shows whether a stunned Snarbolax exposes a
     * readable condition and via which method, so SNARBY can gate on it. Per-actor
     * reflection (uncached) to dodge the cross-class method-caching gotcha.
     */
    static String actorConfigRefArrays(Object actor) {
        StringBuilder out = new StringBuilder();
        try {
            for (java.lang.reflect.Method m : actor.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || !m.getReturnType().isArray())
                    continue;
                if (!m.getReturnType().getComponentType().getName()
                        .equals("com.threerings.config.ConfigReference"))
                    continue;
                Object arr;
                try {
                    arr = m.invoke(actor);
                } catch (Exception ie) {
                    continue;
                }
                if (!(arr instanceof Object[]) || ((Object[]) arr).length == 0)
                    continue;
                StringBuilder names = new StringBuilder();
                for (Object cr : (Object[]) arr) {
                    if (cr == null)
                        continue;
                    try {
                        Object nm = cr.getClass().getMethod("getName").invoke(cr);
                        if (names.length() > 0)
                            names.append(",");
                        names.append(nm);
                    } catch (Exception ne) {
                    }
                }
                if (names.length() > 0) {
                    if (out.length() > 0)
                        out.append(" ");
                    out.append(m.getName()).append("=[").append(names).append("]");
                }
            }
        } catch (Exception e) {
        }
        return out.toString();
    }

    /**
     * Scene-transition cache invalidation (controller class changed) — called from
     * SocketInputState's consumable tick, which detects the transition.
     */
    static void invalidateCaches() {
        cachedPartyField = null;
        cachedCondMethod = null;
    }
}

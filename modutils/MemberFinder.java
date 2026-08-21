package modutils;

import javassist.*;
import javassist.bytecode.*;

import java.util.*;

/**
 * Finds fields and methods in a CtClass by structural signature rather than name.
 *
 * All finders return null (never throw) when nothing matches, so callers can chain
 * fallbacks or throw their own descriptive error.  The resolve*() helpers implement
 * the standard "try known name first, fall back to structural search" pattern that
 * lets the mod survive obfuscator renames without any manual constant updates.
 */
public class MemberFinder {

    // ── Field finders ────────────────────────────────────────────────────────

    /** First declared field whose type name exactly equals typeName. */
    public static String fieldByType(CtClass cls, String typeName) {
        for (CtField f : cls.getDeclaredFields()) {
            try { if (f.getType().getName().equals(typeName)) return f.getName(); }
            catch (NotFoundException ignored) {}
        }
        return null;
    }

    /** First declared field whose type name ends with suffix (e.g. ".LevelItem", ".o"). */
    public static String fieldByTypeSuffix(CtClass cls, String suffix) {
        for (CtField f : cls.getDeclaredFields()) {
            try { if (f.getType().getName().endsWith(suffix)) return f.getName(); }
            catch (NotFoundException ignored) {}
        }
        return null;
    }

    /**
     * First declared field whose type has a declared method matching methodName + descPrefix.
     * Useful when you know the field by what you can DO with it (e.g. the button field has isEnabled()).
     */
    public static String fieldByTypeMethod(CtClass cls, String methodName, String descPrefix) {
        for (CtField f : cls.getDeclaredFields()) {
            try {
                CtClass ft = f.getType();
                for (CtMethod m : ft.getMethods()) {
                    if (m.getName().equals(methodName)
                            && m.getMethodInfo().getDescriptor().startsWith(descPrefix)) {
                        return f.getName();
                    }
                }
            } catch (NotFoundException ignored) {}
        }
        return null;
    }

    /**
     * First declared primitive int field, excluding any field names already identified.
     * Used as a last resort for int fields that cannot be typed-matched uniquely.
     */
    public static String firstIntField(CtClass cls, String... excludeNames) {
        Set<String> excl = new HashSet<>(Arrays.asList(excludeNames));
        for (CtField f : cls.getDeclaredFields()) {
            if (excl.contains(f.getName())) continue;
            try { if (f.getType() == CtClass.intType) return f.getName(); }
            catch (NotFoundException ignored) {}
        }
        return null;
    }

    // ── Method finders ───────────────────────────────────────────────────────

    /** First declared method whose full JVM descriptor exactly equals descriptor. */
    public static String methodByDescriptor(CtClass cls, String descriptor) {
        for (CtMethod m : cls.getDeclaredMethods()) {
            if (m.getMethodInfo().getDescriptor().equals(descriptor)) return m.getName();
        }
        return null;
    }

    /**
     * First declared method whose descriptor contains fragment anywhere.
     * Handy for matching on a distinctive parameter type without knowing the full signature
     * (e.g. fragment = "[Lcom/threerings/opengl/gui/o;" to find the accept method).
     */
    public static String methodByDescriptorFragment(CtClass cls, String fragment) {
        for (CtMethod m : cls.getDeclaredMethods()) {
            if (m.getMethodInfo().getDescriptor().contains(fragment)) return m.getName();
        }
        return null;
    }

    /** First declared void no-arg method (descriptor "()V") not in the exclude list. */
    public static String noArgVoidMethod(CtClass cls, String... excludeNames) {
        Set<String> excl = new HashSet<>(Arrays.asList(excludeNames));
        for (CtMethod m : cls.getDeclaredMethods()) {
            if (!excl.contains(m.getName())
                    && m.getMethodInfo().getDescriptor().equals("()V")) {
                return m.getName();
            }
        }
        return null;
    }

    /**
     * First no-arg method (declared or inherited) whose return type name ends with suffix.
     * Good for finding getPlayerObject() (suffix "PlayerObject"), getClientManager() (suffix "ClientManager"), etc.
     * Searches declared methods first, then falls back to inherited (for methods on parent interfaces).
     */
    public static String noArgMethodByReturnSuffix(CtClass cls, String suffix) {
        for (CtMethod m : cls.getDeclaredMethods()) {
            try {
                if (m.getParameterTypes().length == 0
                        && m.getReturnType().getName().endsWith(suffix)) {
                    return m.getName();
                }
            } catch (NotFoundException ignored) {}
        }
        try {
            for (CtMethod m : cls.getMethods()) {
                if (m.getDeclaringClass().getName().equals(cls.getName())) continue;
                try {
                    if (m.getParameterTypes().length == 0
                            && m.getReturnType().getName().endsWith(suffix)) {
                        return m.getName();
                    }
                } catch (NotFoundException ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * First no-arg method (declared or inherited) whose return type starts with the given package prefix.
     * Useful for GET_TRANSLATION_METHOD which returns a com.threerings.math.* type.
     */
    public static String noArgMethodByReturnPackage(CtClass cls, String pkg) {
        // Search declared methods only first; if none, search inherited
        for (CtMethod m : cls.getDeclaredMethods()) {
            try {
                if (m.getParameterTypes().length == 0
                        && m.getReturnType().getName().startsWith(pkg)) {
                    return m.getName();
                }
            } catch (NotFoundException ignored) {}
        }
        try {
            for (CtMethod m : cls.getMethods()) {
                if (m.getDeclaringClass().equals(cls)) continue; // already covered
                try {
                    if (m.getParameterTypes().length == 0
                            && m.getReturnType().getName().startsWith(pkg)) {
                        return m.getName();
                    }
                } catch (NotFoundException ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * First declared method that returns the given primitive/reference type with the given param types.
     * Pass CtClass.booleanType, CtClass.intType, etc. for primitives.
     */
    public static String methodByReturnAndParams(CtClass cls, CtClass returnType, CtClass... paramTypes)
            throws NotFoundException {
        outer:
        for (CtMethod m : cls.getDeclaredMethods()) {
            try {
                if (!m.getReturnType().getName().equals(returnType.getName())) continue;
                CtClass[] pts = m.getParameterTypes();
                if (pts.length != paramTypes.length) continue;
                for (int i = 0; i < pts.length; i++) {
                    if (!pts[i].getName().equals(paramTypes[i].getName())) continue outer;
                }
                return m.getName();
            } catch (NotFoundException ignored) {}
        }
        return null;
    }

    /**
     * Like methodByReturnAndParams but also searches inherited methods.
     * Use when the target method may be defined on a superclass (e.g. Item.getOid() for LevelItem).
     */
    public static String methodByReturnAndParamsIncludingInherited(CtClass cls,
            CtClass returnType, CtClass... paramTypes) throws NotFoundException {
        String found = methodByReturnAndParams(cls, returnType, paramTypes);
        if (found != null) return found;
        try {
            outer:
            for (CtMethod m : cls.getMethods()) {
                if (m.getDeclaringClass().getName().equals(cls.getName())) continue;
                try {
                    if (!m.getReturnType().getName().equals(returnType.getName())) continue;
                    CtClass[] pts = m.getParameterTypes();
                    if (pts.length != paramTypes.length) continue;
                    for (int i = 0; i < pts.length; i++) {
                        if (!pts[i].getName().equals(paramTypes[i].getName())) continue outer;
                    }
                    return m.getName();
                } catch (NotFoundException ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Convenience: first method taking exactly the given params (any return type). */
    public static String methodByParamTypes(CtClass cls, CtClass... paramTypes) throws NotFoundException {
        outer:
        for (CtMethod m : cls.getDeclaredMethods()) {
            try {
                CtClass[] pts = m.getParameterTypes();
                if (pts.length != paramTypes.length) continue;
                for (int i = 0; i < pts.length; i++) {
                    if (!pts[i].getName().equals(paramTypes[i].getName())) continue outer;
                }
                return m.getName();
            } catch (NotFoundException ignored) {}
        }
        return null;
    }

    /**
     * First declared method whose descriptor params portion (everything up to and including ')')
     * exactly equals paramsDesc.  E.g. paramsDesc = "(Lcom/foo/Bar;Ljava/lang/String;Z)" matches
     * a method "(Lcom/foo/Bar;Ljava/lang/String;Z)Ljava/lang/String;" regardless of return type.
     */
    public static String methodByParamsDescriptor(CtClass cls, String paramsDesc) {
        for (CtMethod m : cls.getDeclaredMethods()) {
            String desc = m.getMethodInfo().getDescriptor();
            int closeParen = desc.indexOf(')');
            if (closeParen >= 0 && desc.substring(0, closeParen + 1).equals(paramsDesc)) return m.getName();
        }
        return null;
    }

    /**
     * Among all methods in implClass that match paramsDesc, return the name of the one
     * whose bytecode pushes the LOWEST integer constant as its first int operand.
     *
     * Used to distinguish two same-signature service methods in an InvocationMarshaller:
     * the marshaller calls c(methodIndex, Object[] args), and the method with the lowest
     * index is the primary/earlier-registered operation (e.g. join-scene vs. unfriend).
     */
    public static String methodWithLowestFirstIntArg(CtClass implClass, String paramsDesc) {
        int lowestIdx = Integer.MAX_VALUE;
        String result = null;
        for (CtMethod m : implClass.getDeclaredMethods()) {
            String desc = m.getMethodInfo().getDescriptor();
            int cp = desc.indexOf(')');
            if (cp < 0 || !desc.substring(0, cp + 1).equals(paramsDesc)) continue;
            int idx = firstIntConst(m);
            if (idx >= 0 && idx < lowestIdx) {
                lowestIdx = idx;
                result = m.getName();
            }
        }
        return result;
    }

    /**
     * Among declared methods matching paramsDesc (up to and including the ')'), the one
     * whose bytecode pushes exactly wantIdx as its first int constant — i.e. the service
     * method with a KNOWN dispatch index. Used when two same-signature marshaller methods
     * must be told apart and the wanted one is not the lowest (e.g. PartyMarshaller:
     * boot=1, invite=2).
     */
    public static String methodWithFirstIntArg(CtClass implClass, String paramsDesc, int wantIdx) {
        for (CtMethod m : implClass.getDeclaredMethods()) {
            String desc = m.getMethodInfo().getDescriptor();
            int cp = desc.indexOf(')');
            if (cp < 0 || !desc.substring(0, cp + 1).equals(paramsDesc)) continue;
            if (firstIntConst(m) == wantIdx) return m.getName();
        }
        return null;
    }

    private static int firstIntConst(CtMethod m) {
        try {
            CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
            if (ca == null) return -1;
            CodeIterator it = ca.iterator();
            while (it.hasNext()) {
                int pos = it.next();
                int op = it.byteAt(pos);
                if (op >= 0x02 && op <= 0x08) return op - 0x03; // iconst_m1..iconst_5
                if (op == 0x10) return it.byteAt(pos + 1);       // bipush
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * First declared field whose generic signature (Signature attribute) contains fragment.
     * Use the internal class name form (slashes, not dots), e.g. "com/example/Foo".
     * Useful for distinguishing two fields of the same raw type by their generic type parameter,
     * e.g. HashIntMap&lt;ActorWrapper&gt; vs HashIntMap&lt;Actor&gt;.
     */
    public static String fieldByGenericSignatureFragment(CtClass cls, String fragment) {
        for (CtField f : cls.getDeclaredFields()) {
            String sig = f.getGenericSignature();
            if (sig != null && sig.contains(fragment)) return f.getName();
        }
        return null;
    }

    /**
     * First declared no-arg method whose bytecode reads (getfield/getstatic) the named field.
     * Use to find a getter for a known field when the method name is obfuscated.
     */
    public static String methodReadingField(CtClass cls, String fieldName) {
        for (CtMethod m : cls.getDeclaredMethods()) {
            try {
                if (m.getParameterTypes().length != 0) continue;
                CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
                if (ca == null) continue;
                ConstPool cp = m.getMethodInfo().getConstPool();
                CodeIterator it = ca.iterator();
                while (it.hasNext()) {
                    int pos = it.next();
                    int op  = it.byteAt(pos);
                    if (op == 0xb4 || op == 0xb2) { // getfield, getstatic
                        int ref = it.u16bitAt(pos + 1);
                        String name = cp.getFieldrefName(ref);
                        if (fieldName.equals(name)) return m.getName();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Find a method that, in its bytecode, calls another method whose descriptor contains descFragment.
     * Methods whose OWN descriptor contains the fragment are skipped — they are the callee, not the caller.
     * Used to find PN_UPDATE_METHOD, which calls PN_ACCEPT_METHOD (identifiable by its o[] parameter).
     */
    public static String methodCallingDescFragment(CtClass cls, String descFragment) {
        for (CtMethod m : cls.getDeclaredMethods()) {
            // Skip the callee itself (its own descriptor contains the fragment)
            if (m.getMethodInfo().getDescriptor().contains(descFragment)) continue;
            try {
                CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
                if (ca == null) continue;
                ConstPool cp = m.getMethodInfo().getConstPool();
                CodeIterator it = ca.iterator();
                while (it.hasNext()) {
                    int pos = it.next();
                    int op  = it.byteAt(pos);
                    // invokevirtual=0xb6, invokespecial=0xb7, invokestatic=0xb8, invokeinterface=0xb9
                    if (op == 0xb6 || op == 0xb7 || op == 0xb8 || op == 0xb9) {
                        int ref  = it.u16bitAt(pos + 1);
                        String desc = (op == 0xb9)
                            ? cp.getInterfaceMethodrefType(ref)
                            : cp.getMethodrefType(ref);
                        if (desc != null && desc.contains(descFragment)) return m.getName();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Resolve helpers (try known name, fall back to structural search) ─────

    /**
     * Finds a method on cls by structural fallback. Always runs the finder —
     * never short-circuits on a cached name, which would silently pick the wrong
     * method if the obfuscator reuses an old name for a different member.
     * Throws with a clear diagnostic if the finder returns null.
     */
    public static String resolveMethod(String label, CtClass cls, Finder fallback) {
        try {
            String found = fallback.find(cls);
            if (found != null) return found;
        } catch (Exception ignored) {}
        throw new RuntimeException("[MemberFinder] Cannot resolve method " + label
            + " in " + cls.getName());
    }

    /**
     * Finds a field on cls by structural fallback. Always runs the finder.
     * Throws with a clear diagnostic if the finder returns null.
     */
    public static String resolveField(String label, CtClass cls, Finder fallback) {
        try {
            String found = fallback.find(cls);
            if (found != null) return found;
        } catch (Exception ignored) {}
        throw new RuntimeException("[MemberFinder] Cannot resolve field " + label
            + " in " + cls.getName());
    }

    @FunctionalInterface
    public interface Finder {
        String find(CtClass cls) throws Exception;
    }
}

package dev.yjs.test;

import dev.yjs.lib0.Prng;
import dev.yjs.lib0.RandomGen;
import dev.yjs.types.AbstractType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test assertions mirroring lib0/testing semantics (deep structural equality, value-equal
 * numbers across Long/Double, byte[] element-wise, Y types via toJSON).
 */
public final class T {
    private T() {}

    /** A test case carrying a deterministic PRNG (mirrors lib0/testing TestCase). */
    public static final class TestCase {
        public final int seed;
        public final RandomGen prng;

        public TestCase(int seed) {
            this.seed = seed;
            this.prng = Prng.create(seed);
        }
    }

    public static void assertTrue(boolean cond) {
        assertTrue(cond, "Assertion failed");
    }

    public static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    public static void fail(String message) {
        throw new AssertionError("Failure: " + message);
    }

    public static void compare(Object a, Object b) {
        compare(a, b, "Values must be equal");
    }

    public static void compare(Object a, Object b, String message) {
        if (!deepEqual(a, b)) {
            throw new AssertionError(message + " -- expected " + repr(a) + " === " + repr(b));
        }
    }

    public static void compareArrays(List<?> a, List<?> b) {
        compare(a, b, "Arrays must be equal");
    }

    public static void compareStrings(String a, String b) {
        if (!Objects.equals(a, b)) {
            throw new AssertionError("Strings must be equal: \"" + a + "\" !== \"" + b + "\"");
        }
    }

    public static boolean deepEqual(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof AbstractType<?> at && b instanceof AbstractType<?> bt) {
            return deepEqual(at.toJSON(), bt.toJSON());
        }
        if (a instanceof AbstractType<?> at) {
            return deepEqual(at.toJSON(), b);
        }
        if (b instanceof AbstractType<?> bt) {
            return deepEqual(a, bt.toJSON());
        }
        if (a instanceof Number na && b instanceof Number nb) {
            boolean aFloat = (a instanceof Double || a instanceof Float);
            boolean bFloat = (b instanceof Double || b instanceof Float);
            if (aFloat || bFloat) {
                return na.doubleValue() == nb.doubleValue();
            }
            return na.longValue() == nb.longValue();
        }
        if (a instanceof byte[] ba && b instanceof byte[] bb) {
            return Arrays.equals(ba, bb);
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!deepEqual(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) {
                    return false;
                }
                if (!deepEqual(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    static String repr(Object o) {
        if (o instanceof byte[] b) {
            return Arrays.toString(b);
        }
        return String.valueOf(o);
    }
}

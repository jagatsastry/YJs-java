package dev.yjs.lib0;

import java.util.List;

/**
 * PRNG helper functions. Port of lib0/prng.js.
 */
public final class Prng {
    private Prng() {}

    public static Xoroshiro128plus create(int seed) {
        return new Xoroshiro128plus(seed);
    }

    public static boolean bool(RandomGen gen) {
        return gen.next() >= 0.5;
    }

    /** Random integer in [min, max] with 53-bit resolution. */
    public static long int53(RandomGen gen, long min, long max) {
        return (long) Math.floor(gen.next() * (max + 1 - min) + min);
    }

    public static long uint53(RandomGen gen, long min, long max) {
        return Math.abs(int53(gen, min, max));
    }

    /** Random integer in [min, max] with 32-bit resolution. */
    public static int int32(RandomGen gen, int min, int max) {
        return (int) Math.floor(gen.next() * ((long) max + 1 - min) + min);
    }

    public static long uint32(RandomGen gen, int min, int max) {
        return int32(gen, min, max) & Binary.BITS32;
    }

    public static int int31(RandomGen gen, int min, int max) {
        return int32(gen, min, max);
    }

    public static double real53(RandomGen gen) {
        return gen.next();
    }

    /** A random character from char code 32-126. */
    public static String charr(RandomGen gen) {
        return String.valueOf((char) int31(gen, 32, 126));
    }

    /** A single letter a-z. */
    public static String letter(RandomGen gen) {
        return String.valueOf((char) int31(gen, 97, 122));
    }

    public static String word(RandomGen gen) {
        return word(gen, 0, 20);
    }

    public static String word(RandomGen gen, int minLen, int maxLen) {
        int len = int31(gen, minLen, maxLen);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(letter(gen));
        }
        return sb.toString();
    }

    public static String utf16Rune(RandomGen gen) {
        int codepoint = int31(gen, 0, 256);
        return new String(Character.toChars(codepoint));
    }

    public static String utf16String(RandomGen gen) {
        return utf16String(gen, 20);
    }

    public static String utf16String(RandomGen gen, int maxlen) {
        int len = int31(gen, 0, maxlen);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(utf16Rune(gen));
        }
        return sb.toString();
    }

    public static <T> T oneOf(RandomGen gen, List<T> array) {
        return array.get(int31(gen, 0, array.size() - 1));
    }

    public static <T> T oneOf(RandomGen gen, T[] array) {
        return array[int31(gen, 0, array.length - 1)];
    }

    public static byte[] uint8Array(RandomGen gen, int len) {
        byte[] buf = new byte[len];
        for (int i = 0; i < len; i++) {
            buf[i] = (byte) int32(gen, 0, Binary.BITS8);
        }
        return buf;
    }
}

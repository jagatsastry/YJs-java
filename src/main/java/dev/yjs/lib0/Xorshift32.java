package dev.yjs.lib0;

/**
 * Port of lib0/prng/Xorshift32.js. Period 2^32-1. Used to seed {@link Xoroshiro128plus}.
 */
public final class Xorshift32 implements RandomGen {
    private int state;
    public final int seed;
    private static final double TWO_POW_32 = 4294967296.0;

    public Xorshift32(int seed) {
        this.seed = seed;
        this.state = seed;
    }

    @Override
    public double next() {
        int x = state;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        state = x;
        return (x & Binary.BITS32) / TWO_POW_32;
    }
}

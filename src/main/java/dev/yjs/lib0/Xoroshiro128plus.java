package dev.yjs.lib0;

/**
 * Port of lib0/prng/Xoroshiro128plus.js (the JS-optimized 32-bit variant).
 *
 * <p>State is kept as four 32-bit ints; Java's {@code <<}, {@code >>}, {@code >>>} match JS
 * 32-bit semantics, and reading a value "unsigned" is {@code (state & 0xFFFFFFFFL)}.
 */
public final class Xoroshiro128plus implements RandomGen {
    private final int[] state = new int[4];
    private boolean fresh = true;
    public final int seed;

    private static final double TWO_POW_32 = 4294967296.0; // 2^32

    public Xoroshiro128plus(int seed) {
        this.seed = seed;
        Xorshift32 xorshift32 = new Xorshift32(seed);
        for (int i = 0; i < 4; i++) {
            // JS: this.state[i] = xorshift32.next() * binary.BITS32  (stored in Uint32Array)
            double product = xorshift32.next() * (double) Binary.BITS32; // BITS32 == 2^32-1
            state[i] = (int) (long) product; // ToUint32 truncation toward zero
        }
    }

    @Override
    public double next() {
        if (fresh) {
            fresh = false;
            long sum = ((state[0] & Binary.BITS32) + (state[2] & Binary.BITS32)) & Binary.BITS32;
            return sum / TWO_POW_32;
        } else {
            fresh = true;
            int s0 = state[0];
            int s1 = state[1];
            int s2 = state[2] ^ s0;
            int s3 = state[3] ^ s1;
            state[0] = (s1 << 23 | s0 >>> 9) ^ s2 ^ (s2 << 14 | s3 >>> 18);
            state[1] = (s0 << 23 | s1 >>> 9) ^ s3 ^ (s3 << 14);
            state[2] = s3 << 4 | s2 >>> 28;
            state[3] = s2 << 4 | s3 >>> 28;
            long sum = ((state[1] & Binary.BITS32) + (state[3] & Binary.BITS32)) & Binary.BITS32;
            return sum / TWO_POW_32;
        }
    }
}

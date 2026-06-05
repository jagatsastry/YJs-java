package dev.yjs.lib0;

/** A pseudo-random number generator producing doubles in [0,1). Port of lib0 prng.PRNG. */
public interface RandomGen {
    /** @return a random double in [0,1) */
    double next();
}

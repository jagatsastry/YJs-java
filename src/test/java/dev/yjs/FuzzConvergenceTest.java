package dev.yjs;

import dev.yjs.lib0.Prng;
import dev.yjs.test.T;
import dev.yjs.test.TestHelper;
import dev.yjs.types.YArray;
import dev.yjs.types.YMap;
import dev.yjs.types.YText;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Randomized multi-client convergence tests using the ported fuzz harness. Each runs many random
 * concurrent operations across 5 replicas with random (dis)connects, then asserts every replica
 * (and freshly merged replicas) converge to identical state. This exercises the YATA integration,
 * delete sets, encoding/sync, GC and merge paths under adversarial interleavings.
 */
public class FuzzConvergenceTest {

    static final TestHelper.RandomMod[] MAP_MODS = {
            // set a random key to a random small int
            (y, gen, o) -> y.transact(tr -> y.getMap("map").set("key" + Prng.int32(gen, 0, 4), Prng.int32(gen, 0, 100)), null),
            // set a key to a string
            (y, gen, o) -> y.transact(tr -> y.getMap("map").set("key" + Prng.int32(gen, 0, 4), Prng.word(gen, 1, 5)), null),
            // delete a random key
            (y, gen, o) -> y.transact(tr -> y.getMap("map").delete("key" + Prng.int32(gen, 0, 4)), null),
            // set a nested YMap
            (y, gen, o) -> y.transact(tr -> {
                YMap<Object> nested = new YMap<>();
                nested.set("n", Prng.int32(gen, 0, 100));
                y.getMap("map").set("key" + Prng.int32(gen, 0, 4), nested);
            }, null)
    };

    static final TestHelper.RandomMod[] ARRAY_MODS = {
            (y, gen, o) -> {
                YArray<Object> a = y.getArray("array");
                int len = a.length();
                int idx = Prng.int32(gen, 0, len);
                y.transact(tr -> a.insert(idx, List.of((long) Prng.int32(gen, 0, 1000))), null);
            },
            (y, gen, o) -> {
                YArray<Object> a = y.getArray("array");
                int len = a.length();
                int idx = Prng.int32(gen, 0, len);
                y.transact(tr -> a.insert(idx, List.of(Prng.word(gen, 1, 4))), null);
            },
            (y, gen, o) -> {
                YArray<Object> a = y.getArray("array");
                int len = a.length();
                if (len > 0) {
                    int idx = Prng.int32(gen, 0, len - 1);
                    int dl = Prng.int32(gen, 1, Math.min(2, len - idx));
                    y.transact(tr -> a.delete(idx, dl), null);
                }
            }
    };

    static final TestHelper.RandomMod[] TEXT_MODS = {
            (y, gen, o) -> {
                YText t = y.getText("text");
                int len = t.length();
                int idx = Prng.int32(gen, 0, len);
                y.transact(tr -> t.insert(idx, Prng.word(gen, 1, 4)), null);
            },
            (y, gen, o) -> {
                YText t = y.getText("text");
                int len = t.length();
                if (len > 0) {
                    int idx = Prng.int32(gen, 0, len - 1);
                    int dl = Prng.int32(gen, 1, Math.min(3, len - idx));
                    y.transact(tr -> t.delete(idx, dl), null);
                }
            }
    };

    @Test
    void mapConverges() {
        for (int seed = 0; seed < 20; seed++) {
            TestHelper.applyRandomTests(new T.TestCase(seed), MAP_MODS, 60, null);
        }
    }

    @Test
    void arrayConverges() {
        for (int seed = 0; seed < 20; seed++) {
            TestHelper.applyRandomTests(new T.TestCase(seed), ARRAY_MODS, 60, null);
        }
    }

    @Test
    void textConverges() {
        for (int seed = 0; seed < 20; seed++) {
            TestHelper.applyRandomTests(new T.TestCase(seed), TEXT_MODS, 60, null);
        }
    }

    @Test
    void mixedConverges() {
        TestHelper.RandomMod[] all = new TestHelper.RandomMod[MAP_MODS.length + ARRAY_MODS.length + TEXT_MODS.length];
        int i = 0;
        for (TestHelper.RandomMod m : MAP_MODS) all[i++] = m;
        for (TestHelper.RandomMod m : ARRAY_MODS) all[i++] = m;
        for (TestHelper.RandomMod m : TEXT_MODS) all[i++] = m;
        for (int seed = 0; seed < 30; seed++) {
            TestHelper.applyRandomTests(new T.TestCase(seed), all, 100, null);
        }
    }
}

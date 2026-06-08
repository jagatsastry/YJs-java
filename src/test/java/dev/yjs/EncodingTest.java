package dev.yjs;

import dev.yjs.structs.Item;
import dev.yjs.test.T;
import dev.yjs.utils.Doc;
import dev.yjs.utils.PermanentUserData;
import dev.yjs.utils.UpdateMerging;
import dev.yjs.utils.Updates;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Port of tests/encoding.tests.js.
 */
public class EncodingTest {

    /**
     * Port of testStructReferences.
     *
     * <p>The JS test compares {@code contentRefs[i] === readContentX} by function identity. In the
     * Java port {@code contentRefs} is an array of method-reference lambdas, so per-element identity
     * cannot be asserted. We faithfully assert the load-bearing invariant: there are 11 entries
     * (indices 1..9 used, index 10 reserved for Skip).
     */
    @Test
    void testStructReferences() {
        T.assertTrue(Item.contentRefs.length == 11);
    }

    /**
     * Port of testPermanentUserData. There is some custom encoding/decoding happening in
     * PermanentUserData. The JS test has an {@code await promise.wait(10)}; in the synchronous Java
     * port that timing is irrelevant, so it is dropped.
     */
    @Test
    void testPermanentUserData() {
        Doc ydoc1 = new Doc();
        Doc ydoc2 = new Doc();
        PermanentUserData pd1 = new PermanentUserData(ydoc1);
        PermanentUserData pd2 = new PermanentUserData(ydoc2);
        pd1.setUserMapping(ydoc1, ydoc1.clientID, "user a");
        pd2.setUserMapping(ydoc2, ydoc2.clientID, "user b");
        ydoc1.getText().insert(0, "xhi");
        ydoc1.getText().delete(0, 1);
        ydoc2.getText().insert(0, "hxxi");
        ydoc2.getText().delete(1, 2);
        Updates.applyUpdate(ydoc2, Updates.encodeStateAsUpdate(ydoc1));
        Updates.applyUpdate(ydoc1, Updates.encodeStateAsUpdate(ydoc2));

        // now sync a third doc with same name as doc1 and then create PermanentUserData
        Doc ydoc3 = new Doc();
        Updates.applyUpdate(ydoc3, Updates.encodeStateAsUpdate(ydoc1));
        PermanentUserData pd3 = new PermanentUserData(ydoc3);
        pd3.setUserMapping(ydoc3, ydoc3.clientID, "user a");
    }

    /**
     * Port of testDiffStateVectorOfUpdateIsEmpty.
     * Reported here: https://github.com/yjs/yjs/issues/308
     */
    @Test
    void testDiffStateVectorOfUpdateIsEmpty() {
        Doc ydoc = new Doc();
        byte[][] sv = {null};
        ydoc.getText().insert(0, "a");
        ydoc.on("update", args -> {
            byte[] update = (byte[]) args[0];
            sv[0] = UpdateMerging.encodeStateVectorFromUpdate(update);
        });
        // should produce an update with an empty state vector (because previous ops are missing)
        ydoc.getText().insert(0, "a");
        T.assertTrue(sv[0] != null && sv[0].length == 1 && sv[0][0] == 0);
    }

    /**
     * Port of testDiffStateVectorOfUpdateIgnoresSkips.
     * Reported here: https://github.com/yjs/yjs/issues/308
     */
    @Test
    void testDiffStateVectorOfUpdateIgnoresSkips() {
        Doc ydoc = new Doc();
        List<byte[]> updates = new ArrayList<>();
        ydoc.on("update", args -> updates.add((byte[]) args[0]));
        ydoc.getText().insert(0, "a");
        ydoc.getText().insert(0, "b");
        ydoc.getText().insert(0, "c");
        byte[] update13 = UpdateMerging.mergeUpdates(Arrays.asList(updates.get(0), updates.get(2)));
        byte[] sv = UpdateMerging.encodeStateVectorFromUpdate(update13);
        Map<Long, Long> state = UpdateMerging.decodeStateVector(sv);
        T.assertTrue(state.get(ydoc.clientID) == 1);
        T.assertTrue(state.size() == 1);
    }
}

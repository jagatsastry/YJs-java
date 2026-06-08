package dev.yjs;

import dev.yjs.types.YText;
import dev.yjs.utils.AbsolutePosition;
import dev.yjs.utils.Doc;
import dev.yjs.utils.RelativePosition;
import dev.yjs.utils.UndoManager;
import dev.yjs.utils.Updates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Port of tests/relativePositions.tests.js. Exercises RelativePosition encode/decode + undo. */
public class RelativePositionsTest {

    static void checkRelativePositions(YText ytext) {
        for (int i = 0; i < ytext.length(); i++) {
            for (int assoc = -1; assoc < 2; assoc++) {
                RelativePosition rpos = RelativePosition.createRelativePositionFromTypeIndex(ytext, i, assoc);
                byte[] encoded = RelativePosition.encodeRelativePosition(rpos);
                RelativePosition decoded = RelativePosition.decodeRelativePosition(encoded);
                AbsolutePosition absPos = RelativePosition.createAbsolutePositionFromRelativePosition(decoded, ytext.doc);
                assertNotNull(absPos);
                assertEquals(i, absPos.index, "index at i=" + i + " assoc=" + assoc);
                assertEquals(assoc, absPos.assoc, "assoc at i=" + i + " assoc=" + assoc);
            }
        }
    }

    @Test
    void case1() {
        Doc d = new Doc();
        YText t = d.getText();
        t.insert(0, "1");
        t.insert(0, "abc");
        t.insert(0, "z");
        t.insert(0, "y");
        t.insert(0, "x");
        checkRelativePositions(t);
    }

    @Test
    void case2() {
        Doc d = new Doc();
        YText t = d.getText();
        t.insert(0, "abc");
        checkRelativePositions(t);
    }

    @Test
    void case3() {
        Doc d = new Doc();
        YText t = d.getText();
        t.insert(0, "abc");
        t.insert(0, "1");
        t.insert(0, "xyz");
        checkRelativePositions(t);
    }

    @Test
    void case4() {
        Doc d = new Doc();
        YText t = d.getText();
        t.insert(0, "1");
        checkRelativePositions(t);
    }

    @Test
    void case5() {
        Doc d = new Doc();
        YText t = d.getText();
        t.insert(0, "2");
        t.insert(0, "1");
        checkRelativePositions(t);
    }

    @Test
    void case6() {
        Doc d = new Doc();
        YText t = d.getText();
        checkRelativePositions(t);
    }

    @Test
    void case7FollowVsNoFollow() {
        Doc docA = new Doc();
        YText textA = docA.getText("text");
        textA.insert(0, "abcde");
        RelativePosition rp = RelativePosition.createRelativePositionFromTypeIndex(textA, 2);
        AbsolutePosition withFollow = RelativePosition.createAbsolutePositionFromRelativePosition(rp, docA, true);
        AbsolutePosition withoutFollow = RelativePosition.createAbsolutePositionFromRelativePosition(rp, docA, false);
        assertNotNull(withFollow);
        assertNotNull(withoutFollow);
        assertEquals(2, withFollow.index);
        assertEquals(2, withoutFollow.index);
    }

    @Test
    void associationDifference() {
        Doc d = new Doc();
        YText t = d.getText();
        t.insert(0, "2");
        t.insert(0, "1");
        RelativePosition rposRight = RelativePosition.createRelativePositionFromTypeIndex(t, 1, 0);
        RelativePosition rposLeft = RelativePosition.createRelativePositionFromTypeIndex(t, 1, -1);
        t.insert(1, "x");
        AbsolutePosition posRight = RelativePosition.createAbsolutePositionFromRelativePosition(rposRight, d);
        AbsolutePosition posLeft = RelativePosition.createAbsolutePositionFromRelativePosition(rposLeft, d);
        assertNotNull(posRight);
        assertEquals(2, posRight.index);
        assertNotNull(posLeft);
        assertEquals(1, posLeft.index);
    }

    @Test
    void withUndo() {
        Doc d = new Doc();
        YText t = d.getText();
        t.insert(0, "hello world");
        RelativePosition rpos = RelativePosition.createRelativePositionFromTypeIndex(t, 1);
        UndoManager um = new UndoManager(t);
        t.delete(0, 6);
        assertEquals(0, RelativePosition.createAbsolutePositionFromRelativePosition(rpos, d).index);
        um.undo();
        assertEquals(1, RelativePosition.createAbsolutePositionFromRelativePosition(rpos, d).index);
        assertEquals(6, RelativePosition.createAbsolutePositionFromRelativePosition(rpos, d, false).index);
        Doc clone = new Doc();
        Updates.applyUpdate(clone, Updates.encodeStateAsUpdate(d));
        assertEquals(6, RelativePosition.createAbsolutePositionFromRelativePosition(rpos, clone).index);
        assertEquals(6, RelativePosition.createAbsolutePositionFromRelativePosition(rpos, clone, false).index);
    }
}

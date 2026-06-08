package dev.yjs;

import dev.yjs.types.YArray;
import dev.yjs.types.YMap;
import dev.yjs.types.YText;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Updates;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end smoke test of the core CRDT pipeline: types, transactions, encode/apply, convergence. */
public class SmokeTest {

    @Test
    void mapBasicAndSync() {
        Doc d1 = new Doc();
        d1.clientID = 1;
        YMap<Object> m1 = d1.getMap("m");
        m1.set("a", 1L);
        m1.set("b", "hello");
        assertEquals(1L, m1.get("a"));
        assertEquals("hello", m1.get("b"));
        assertTrue(m1.has("a"));
        assertEquals(2, m1.size());

        // sync to a fresh doc
        Doc d2 = new Doc();
        d2.clientID = 2;
        Updates.applyUpdate(d2, Updates.encodeStateAsUpdate(d1));
        YMap<Object> m2 = d2.getMap("m");
        assertEquals(1L, m2.get("a"));
        assertEquals("hello", m2.get("b"));
    }

    @Test
    void arrayBasicAndSync() {
        Doc d1 = new Doc();
        d1.clientID = 1;
        YArray<Object> a1 = d1.getArray("a");
        a1.insert(0, List.of(1L, 2L, 3L));
        a1.push(List.of("x"));
        assertEquals(List.of(1L, 2L, 3L, "x"), a1.toJSON());
        assertEquals(4, a1.length());

        Doc d2 = new Doc();
        d2.clientID = 2;
        Updates.applyUpdate(d2, Updates.encodeStateAsUpdate(d1));
        assertEquals(List.of(1L, 2L, 3L, "x"), d2.<Object>getArray("a").toJSON());
    }

    @Test
    void textBasicAndSync() {
        Doc d1 = new Doc();
        d1.clientID = 1;
        YText t1 = d1.getText("t");
        t1.insert(0, "Hello");
        t1.insert(5, " World");
        t1.delete(0, 1);
        assertEquals("ello World", t1.toString());

        Doc d2 = new Doc();
        d2.clientID = 2;
        Updates.applyUpdate(d2, Updates.encodeStateAsUpdate(d1));
        assertEquals("ello World", d2.getText("t").toString());
    }

    @Test
    void concurrentMapConvergence() {
        // Two clients edit concurrently, then exchange updates -> must converge.
        Doc d1 = new Doc();
        d1.clientID = 1;
        Doc d2 = new Doc();
        d2.clientID = 2;
        d1.getMap("m").set("k1", "v1");
        d2.getMap("m").set("k2", "v2");
        // both also set the same key concurrently
        d1.getMap("m").set("shared", "from1");
        d2.getMap("m").set("shared", "from2");

        byte[] u1 = Updates.encodeStateAsUpdate(d1);
        byte[] u2 = Updates.encodeStateAsUpdate(d2);
        Updates.applyUpdate(d1, u2);
        Updates.applyUpdate(d2, u1);

        Map<String, Object> j1 = (Map<String, Object>) d1.getMap("m").toJSON();
        Map<String, Object> j2 = (Map<String, Object>) d2.getMap("m").toJSON();
        assertEquals(j1, j2, "Docs must converge");
        assertEquals("v1", j1.get("k1"));
        assertEquals("v2", j1.get("k2"));
        // last-writer (deterministic by clientID) wins for shared key
        assertTrue(j1.get("shared").equals("from1") || j1.get("shared").equals("from2"));
    }

    @Test
    void concurrentTextConvergence() {
        Doc d1 = new Doc();
        d1.clientID = 1;
        Doc d2 = new Doc();
        d2.clientID = 2;
        // seed both with same base
        d1.getText("t").insert(0, "abc");
        Updates.applyUpdate(d2, Updates.encodeStateAsUpdate(d1));
        assertEquals("abc", d2.getText("t").toString());
        // concurrent inserts at the same position
        d1.getText("t").insert(1, "X");
        d2.getText("t").insert(1, "Y");
        byte[] u1 = Updates.encodeStateAsUpdate(d1);
        byte[] u2 = Updates.encodeStateAsUpdate(d2);
        Updates.applyUpdate(d1, u2);
        Updates.applyUpdate(d2, u1);
        assertEquals(d1.getText("t").toString(), d2.getText("t").toString(), "Text must converge");
        assertEquals(5, d1.getText("t").toString().length());
    }

    @Test
    void v2EncodingRoundTrip() {
        Doc d1 = new Doc();
        d1.clientID = 1;
        d1.getArray("a").insert(0, List.of(1L, "two", true, List.of(3L, 4L)));
        Doc d2 = new Doc();
        d2.clientID = 2;
        Updates.applyUpdateV2(d2, Updates.encodeStateAsUpdateV2(d1), null);
        assertEquals(d1.<Object>getArray("a").toJSON(), d2.<Object>getArray("a").toJSON());
    }
}

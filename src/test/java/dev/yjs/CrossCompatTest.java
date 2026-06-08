package dev.yjs;

import dev.yjs.lib0.Json;
import dev.yjs.test.T;
import dev.yjs.types.YArray;
import dev.yjs.types.YMap;
import dev.yjs.types.YText;
import dev.yjs.types.YXmlElement;
import dev.yjs.types.YXmlFragment;
import dev.yjs.types.YXmlText;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Updates;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies wire-compatibility with the reference Yjs v13.6.31 implementation:
 * (A) Java can apply real-Yjs-generated updates and reproduce the same state.
 * (B) Java produces byte-identical updates for the same operations.
 */
public class CrossCompatTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> golden;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = CrossCompatTest.class.getResourceAsStream("/yjs_golden.json")) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            golden = (Map<String, Object>) Json.parse(s);
        }
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sc(String name) {
        return (Map<String, Object>) golden.get(name);
    }

    // ---------- (A) READ: apply real Yjs updates ----------

    @Test
    void readMapV1() {
        Map<String, Object> s = sc("map");
        Doc d = new Doc();
        Updates.applyUpdate(d, unhex((String) s.get("v1")));
        T.compare(d.getMap("m").toJSON(), s.get("json"), "map v1 read");
    }

    @Test
    void readMapV2() {
        Map<String, Object> s = sc("map");
        Doc d = new Doc();
        Updates.applyUpdateV2(d, unhex((String) s.get("v2")), null);
        T.compare(d.getMap("m").toJSON(), s.get("json"), "map v2 read");
    }

    @Test
    void readArrayV1andV2() {
        Map<String, Object> s = sc("array");
        Doc d1 = new Doc();
        Updates.applyUpdate(d1, unhex((String) s.get("v1")));
        T.compare(d1.getArray("a").toJSON(), s.get("json"), "array v1 read");
        Doc d2 = new Doc();
        Updates.applyUpdateV2(d2, unhex((String) s.get("v2")), null);
        T.compare(d2.getArray("a").toJSON(), s.get("json"), "array v2 read");
    }

    @Test
    void readTextV1andV2() {
        Map<String, Object> s = sc("text");
        Doc d1 = new Doc();
        Updates.applyUpdate(d1, unhex((String) s.get("v1")));
        assertEquals(s.get("str"), d1.getText("t").toString(), "text v1 toString");
        T.compare(d1.getText("t").toDelta(), s.get("delta"), "text v1 delta");

        Doc d2 = new Doc();
        Updates.applyUpdateV2(d2, unhex((String) s.get("v2")), null);
        assertEquals(s.get("str"), d2.getText("t").toString(), "text v2 toString");
        T.compare(d2.getText("t").toDelta(), s.get("delta"), "text v2 delta");
    }

    @Test
    void readMergedV1() {
        Map<String, Object> s = sc("merged");
        Doc d = new Doc();
        Updates.applyUpdate(d, unhex((String) s.get("v1")));
        T.compare(d.getArray("a").toJSON(), s.get("json"), "merged array");
    }

    @Test
    void readXmlV1andV2() {
        Map<String, Object> s = sc("xml");
        Doc d1 = new Doc();
        Updates.applyUpdate(d1, unhex((String) s.get("v1")));
        assertEquals(s.get("str"), d1.getXmlFragment("f").toString(), "xml v1 toString");
        Doc d2 = new Doc();
        Updates.applyUpdateV2(d2, unhex((String) s.get("v2")), null);
        assertEquals(s.get("str"), d2.getXmlFragment("f").toString(), "xml v2 toString");
    }

    // ---------- (B) WRITE: produce byte-identical updates ----------

    @Test
    void writeArrayByteIdentical() {
        Doc d = new Doc();
        d.clientID = 100;
        YArray<Object> a = d.getArray("a");
        a.insert(0, List.of("a", "b", "c"));
        a.insert(1, List.of(42L));
        a.delete(3, 1);
        assertEquals(sc("array").get("v1"), hex(Updates.encodeStateAsUpdate(d)), "array v1 bytes");
    }

    @Test
    void writeMapByteIdentical() {
        Doc d = new Doc();
        d.clientID = 42;
        YMap<Object> m = d.getMap("m");
        m.set("int", 7L);
        m.set("neg", -123L);
        m.set("str", "hello world");
        m.set("bool", true);
        m.set("boolf", false);
        m.set("nul", null);
        m.set("float", 3.5);
        YMap<Object> nested = new YMap<>();
        m.set("nested", nested);
        nested.set("x", 1L);
        YArray<Object> arr = new YArray<>();
        m.set("arr", arr);
        arr.push(List.of(1L, 2L, 3L));
        assertEquals(sc("map").get("v1"), hex(Updates.encodeStateAsUpdate(d)), "map v1 bytes");
    }

    @Test
    void writeTextByteIdentical() {
        Doc d = new Doc();
        d.clientID = 7;
        YText t = d.getText("t");
        t.insert(0, "Hello World");
        java.util.Map<String, Object> bold = new java.util.LinkedHashMap<>();
        bold.put("bold", true);
        t.format(0, 5, bold);
        java.util.Map<String, Object> ital = new java.util.LinkedHashMap<>();
        ital.put("italic", true);
        ital.put("color", "red");
        t.format(6, 5, ital);
        assertEquals(sc("text").get("v1"), hex(Updates.encodeStateAsUpdate(d)), "text v1 bytes");
    }

    @Test
    void writeXmlByteIdentical() {
        Doc d = new Doc();
        d.clientID = 9;
        YXmlFragment f = d.getXmlFragment("f");
        YXmlElement el = new YXmlElement("div");
        el.setAttribute("class", "main");
        YXmlText txt = new YXmlText();
        txt.insert(0, "hi");
        el.insert(0, List.of(txt));
        f.insert(0, List.of(el));
        assertEquals(sc("xml").get("v1"), hex(Updates.encodeStateAsUpdate(d)), "xml v1 bytes");
    }
}

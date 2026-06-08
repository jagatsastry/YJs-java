package dev.yjs;

import dev.yjs.types.YArray;
import dev.yjs.types.YMap;
import dev.yjs.types.YText;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Snapshot;
import dev.yjs.utils.UndoManager;
import dev.yjs.utils.Updates;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the snippets shown in README.md so the documentation stays honest. */
public class ReadmeExamplesTest {

    @Test
    void quickStart() {
        Doc d1 = new Doc();
        d1.getArray("todos").insert(0, List.of("buy milk", "walk dog"));
        byte[] update = Updates.encodeStateAsUpdate(d1);
        Doc d2 = new Doc();
        Updates.applyUpdate(d2, update);
        assertEquals(List.of("buy milk", "walk dog"), d2.<Object>getArray("todos").toJSON());
    }

    @Test
    void mapArrayText() {
        Doc doc = new Doc();
        YMap<Object> map = doc.getMap("state");
        map.set("count", 1L);
        map.set("title", "hello");
        assertEquals(1L, map.get("count"));
        assertTrue(map.has("title"));

        YArray<Object> arr = doc.getArray("list");
        arr.insert(0, List.of(1L, 2L, 3L));
        arr.delete(1, 1);
        arr.push(List.of("end"));
        assertEquals(List.of(1L, 3L, "end"), arr.toJSON());

        YText text = doc.getText("body");
        text.insert(0, "Hello world");
        text.format(0, 5, Map.of("bold", true));
        assertEquals("Hello world", text.toString());
        // first delta op is the bold "Hello"
        Map<String, Object> first = text.toDelta().get(0);
        assertEquals("Hello", first.get("insert"));
        assertEquals(Map.of("bold", true), first.get("attributes"));
    }

    @Test
    void nesting() {
        Doc doc = new Doc();
        YMap<Object> root = doc.getMap("doc");
        YArray<Object> comments = new YArray<>();
        root.set("comments", comments);
        comments.push(List.of("first!"));
        assertEquals(Map.of("comments", List.of("first!")), root.toJSON());
    }

    @Test
    void stateVectorSync() {
        Doc d1 = new Doc();
        d1.getMap("m").set("a", 1L);
        Doc d2 = new Doc();
        byte[] sv2 = Updates.encodeStateVector(d2);
        byte[] missing = Updates.encodeStateAsUpdate(d1, sv2);
        Updates.applyUpdate(d2, missing);
        assertEquals(1L, d2.getMap("m").get("a"));
    }

    @Test
    void observing() {
        Doc doc = new Doc();
        YArray<Object> arr = doc.getArray("list");
        AtomicInteger fired = new AtomicInteger();
        arr.observe((event, transaction) -> fired.incrementAndGet());
        arr.insert(0, List.of("x"));
        assertEquals(1, fired.get());
    }

    @Test
    void undoRedo() {
        Doc doc = new Doc();
        YText text = doc.getText("body");
        UndoManager um = new UndoManager(text);
        text.insert(0, "draft");
        um.undo();
        assertEquals("", text.toString());
        um.redo();
        assertEquals("draft", text.toString());
    }

    @Test
    void snapshots() {
        Doc doc = new Doc(new Doc.Options().gc(false));
        YText t = doc.getText("t");
        t.insert(0, "v1");
        Snapshot snap = Snapshot.snapshot(doc);
        t.insert(2, " v2");
        // rendering at the snapshot shows only "v1"
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> op : t.toDelta(snap)) {
            if (op.get("insert") instanceof String s) sb.append(s);
        }
        assertEquals("v1", sb.toString());
        assertEquals("v1 v2", t.toString());
    }

    @Test
    void subdocuments() {
        Doc parent = new Doc();
        Doc child = new Doc();
        parent.getMap("docs").set("child", child);
        assertTrue(parent.getMap("docs").get("child") instanceof Doc);
    }
}

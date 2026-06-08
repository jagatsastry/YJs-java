package dev.yjs.structs;

import dev.yjs.utils.Doc;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content wrapping a sub-document. Port of src/structs/ContentDoc.js.
 */
public final class ContentDoc extends AbstractContent {
    /**
     * The wrapped sub-document. Mutable: {@link Doc#destroy()} replaces it with a fresh instance
     * carrying the same guid (mirroring the JS implementation).
     */
    public Doc doc;
    /**
     * The options captured from {@link #doc} at construction time. Only the fields that differ
     * from Yjs defaults are meaningful for encoding (gc=false, autoLoad=true, non-null meta);
     * {@code shouldLoad} is recomputed when a {@link Doc} is reconstructed.
     */
    public Doc.Options opts;

    public ContentDoc(Doc doc) {
        if (doc._item != null) {
            System.err.println("This document was already integrated as a sub-document. You should create a second instance instead with the same guid.");
        }
        this.doc = doc;
        Doc.Options opts = new Doc.Options();
        this.opts = opts;
        if (!doc.gc) {
            opts.gc = false;
        }
        if (doc.autoLoad) {
            opts.autoLoad = true;
        }
        if (doc.meta != null) {
            opts.meta = doc.meta;
        }
    }

    /**
     * Mirrors the JS {@code createDocFromOpts}: builds a {@link Doc} from a decoded options map,
     * computing {@code shouldLoad = opts.shouldLoad || opts.autoLoad || false}.
     */
    static Doc createDocFromOpts(String guid, Map<String, Object> opts) {
        Doc.Options options = new Doc.Options();
        options.guid = guid;
        if (opts.containsKey("gc")) {
            options.gc = isTruthy(opts.get("gc"));
        }
        if (opts.containsKey("autoLoad")) {
            options.autoLoad = isTruthy(opts.get("autoLoad"));
        }
        if (opts.containsKey("meta")) {
            options.meta = opts.get("meta");
        }
        boolean autoLoad = options.autoLoad;
        options.shouldLoad = isTruthy(opts.get("shouldLoad")) || autoLoad;
        return new Doc(options);
    }

    /** Variant used by {@link #copy()} which works from the stored {@link Doc.Options}. */
    static Doc createDocFromOpts(String guid, Doc.Options opts) {
        Doc.Options options = new Doc.Options();
        options.guid = guid;
        options.gc = opts.gc;
        options.autoLoad = opts.autoLoad;
        options.meta = opts.meta;
        options.shouldLoad = opts.shouldLoad || opts.autoLoad;
        return new Doc(options);
    }

    private static boolean isTruthy(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (o instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    @Override
    public int getLength() {
        return 1;
    }

    @Override
    public List<Object> getContent() {
        List<Object> list = new ArrayList<>();
        list.add(this.doc);
        return list;
    }

    @Override
    public boolean isCountable() {
        return true;
    }

    @Override
    public ContentDoc copy() {
        return new ContentDoc(createDocFromOpts(this.doc.guid, this.opts));
    }

    @Override
    public ContentDoc splice(int offset) {
        throw new UnsupportedOperationException("method unimplemented");
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        return false;
    }

    @Override
    public void integrate(Transaction transaction, Item item) {
        // this needs to be reflected in doc.destroy as well
        this.doc._item = item;
        transaction.subdocsAdded.add(this.doc);
        if (this.doc.shouldLoad) {
            transaction.subdocsLoaded.add(this.doc);
        }
    }

    @Override
    public void delete(Transaction transaction) {
        if (transaction.subdocsAdded.contains(this.doc)) {
            transaction.subdocsAdded.remove(this.doc);
        } else {
            transaction.subdocsRemoved.add(this.doc);
        }
    }

    @Override
    public void gc(StructStore store) {
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        encoder.writeString(this.doc.guid);
        encoder.writeAny(optsToMap());
    }

    @Override
    public int getRef() {
        return 9;
    }

    /**
     * Reconstructs the JS {@code opts} plain-object (only the keys that were present at
     * construction, in insertion order gc, autoLoad, meta) so {@code writeAny} produces
     * byte-identical output to Yjs.
     */
    private Map<String, Object> optsToMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!opts.gc) {
            map.put("gc", false);
        }
        if (opts.autoLoad) {
            map.put("autoLoad", true);
        }
        if (opts.meta != null) {
            map.put("meta", opts.meta);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static AbstractContent read(UpdateDecoder decoder) {
        String guid = decoder.readString();
        Object opts = decoder.readAny();
        Map<String, Object> optsMap = opts instanceof Map ? (Map<String, Object>) opts : new LinkedHashMap<>();
        return new ContentDoc(createDocFromOpts(guid, optsMap));
    }
}

package dev.yjs.structs;

import dev.yjs.lib0.Json;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Content holding a list of JSON-serializable values (legacy encoding; values may be {@code null}).
 * Port of src/structs/ContentJSON.js.
 */
public final class ContentJSON extends AbstractContent {
    public List<Object> arr;

    public ContentJSON(List<Object> arr) {
        this.arr = arr;
    }

    @Override
    public int getLength() {
        return this.arr.size();
    }

    @Override
    public List<Object> getContent() {
        return this.arr;
    }

    @Override
    public boolean isCountable() {
        return true;
    }

    @Override
    public ContentJSON copy() {
        return new ContentJSON(this.arr);
    }

    @Override
    public ContentJSON splice(int offset) {
        ContentJSON right = new ContentJSON(new ArrayList<>(this.arr.subList(offset, this.arr.size())));
        this.arr = new ArrayList<>(this.arr.subList(0, offset));
        return right;
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        List<Object> merged = new ArrayList<>(this.arr);
        merged.addAll(((ContentJSON) right).arr);
        this.arr = merged;
        return true;
    }

    @Override
    public void integrate(Transaction transaction, Item item) {
    }

    @Override
    public void delete(Transaction transaction) {
    }

    @Override
    public void gc(StructStore store) {
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        int len = this.arr.size();
        encoder.writeLen(len - offset);
        for (int i = offset; i < len; i++) {
            Object c = this.arr.get(i);
            encoder.writeString(c == null ? "undefined" : Json.stringify(c));
        }
    }

    @Override
    public int getRef() {
        return 2;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        int len = decoder.readLen();
        List<Object> cs = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            String c = decoder.readString();
            if (c.equals("undefined")) {
                cs.add(null);
            } else {
                cs.add(Json.parse(c));
            }
        }
        return new ContentJSON(cs);
    }
}

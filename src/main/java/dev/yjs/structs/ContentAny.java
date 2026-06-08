package dev.yjs.structs;

import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Content holding a list of arbitrary (lib0 "any"-encodable) values.
 * Port of src/structs/ContentAny.js.
 */
public final class ContentAny extends AbstractContent {
    public List<Object> arr;

    public ContentAny(List<Object> arr) {
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
    public ContentAny copy() {
        return new ContentAny(this.arr);
    }

    @Override
    public ContentAny splice(int offset) {
        ContentAny right = new ContentAny(new ArrayList<>(this.arr.subList(offset, this.arr.size())));
        this.arr = new ArrayList<>(this.arr.subList(0, offset));
        return right;
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        List<Object> merged = new ArrayList<>(this.arr);
        merged.addAll(((ContentAny) right).arr);
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
            encoder.writeAny(c);
        }
    }

    @Override
    public int getRef() {
        return 8;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        int len = decoder.readLen();
        List<Object> cs = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            cs.add(decoder.readAny());
        }
        return new ContentAny(cs);
    }
}

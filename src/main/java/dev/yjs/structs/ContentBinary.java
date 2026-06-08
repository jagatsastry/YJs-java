package dev.yjs.structs;

import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Content holding a single binary blob.
 * Port of src/structs/ContentBinary.js.
 */
public final class ContentBinary extends AbstractContent {
    public final byte[] content;

    public ContentBinary(byte[] content) {
        this.content = content;
    }

    @Override
    public int getLength() {
        return 1;
    }

    @Override
    public List<Object> getContent() {
        List<Object> list = new ArrayList<>();
        list.add(this.content);
        return list;
    }

    @Override
    public boolean isCountable() {
        return true;
    }

    @Override
    public ContentBinary copy() {
        return new ContentBinary(this.content);
    }

    @Override
    public ContentBinary splice(int offset) {
        throw new UnsupportedOperationException("method unimplemented");
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        return false;
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
        encoder.writeBuf(this.content);
    }

    @Override
    public int getRef() {
        return 3;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        return new ContentBinary(decoder.readBuf());
    }
}

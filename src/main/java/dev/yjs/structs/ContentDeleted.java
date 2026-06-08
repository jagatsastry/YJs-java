package dev.yjs.structs;

import dev.yjs.utils.DeleteSet;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Content that represents a range of deleted (garbage-collectable) items.
 * Port of src/structs/ContentDeleted.js.
 */
public final class ContentDeleted extends AbstractContent {
    public int len;

    public ContentDeleted(int len) {
        this.len = len;
    }

    @Override
    public int getLength() {
        return this.len;
    }

    @Override
    public List<Object> getContent() {
        return new ArrayList<>();
    }

    @Override
    public boolean isCountable() {
        return false;
    }

    @Override
    public ContentDeleted copy() {
        return new ContentDeleted(this.len);
    }

    @Override
    public ContentDeleted splice(int offset) {
        ContentDeleted right = new ContentDeleted(this.len - offset);
        this.len = offset;
        return right;
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        this.len += ((ContentDeleted) right).len;
        return true;
    }

    @Override
    public void integrate(Transaction transaction, Item item) {
        DeleteSet.addToDeleteSet(transaction.deleteSet, item.id.client, item.id.clock, this.len);
        item.markDeleted();
    }

    @Override
    public void delete(Transaction transaction) {
    }

    @Override
    public void gc(StructStore store) {
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        encoder.writeLen(this.len - offset);
    }

    @Override
    public int getRef() {
        return 1;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        return new ContentDeleted(decoder.readLen());
    }
}

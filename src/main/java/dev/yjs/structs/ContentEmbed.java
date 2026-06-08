package dev.yjs.structs;

import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Content holding a single embedded (opaque JSON) value, used by rich text.
 * Port of src/structs/ContentEmbed.js.
 */
public final class ContentEmbed extends AbstractContent {
    public final Object embed;

    public ContentEmbed(Object embed) {
        this.embed = embed;
    }

    @Override
    public int getLength() {
        return 1;
    }

    @Override
    public List<Object> getContent() {
        List<Object> list = new ArrayList<>();
        list.add(this.embed);
        return list;
    }

    @Override
    public boolean isCountable() {
        return true;
    }

    @Override
    public ContentEmbed copy() {
        return new ContentEmbed(this.embed);
    }

    @Override
    public ContentEmbed splice(int offset) {
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
        encoder.writeJSON(this.embed);
    }

    @Override
    public int getRef() {
        return 5;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        return new ContentEmbed(decoder.readJSON());
    }
}

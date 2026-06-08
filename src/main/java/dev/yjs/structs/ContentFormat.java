package dev.yjs.structs;

import dev.yjs.types.YText;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Meta-content describing a rich-text formatting attribute (key/value).
 * Not countable. Port of src/structs/ContentFormat.js.
 */
public final class ContentFormat extends AbstractContent {
    public final String key;
    public final Object value;

    public ContentFormat(String key, Object value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public int getLength() {
        return 1;
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
    public ContentFormat copy() {
        return new ContentFormat(this.key, this.value);
    }

    @Override
    public ContentFormat splice(int offset) {
        throw new UnsupportedOperationException("method unimplemented");
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        return false;
    }

    @Override
    public void integrate(Transaction transaction, Item item) {
        // @todo searchmarker are currently unsupported for rich text documents
        YText p = (YText) item.parent;
        p._searchMarker = null;
        p._hasFormatting = true;
    }

    @Override
    public void delete(Transaction transaction) {
    }

    @Override
    public void gc(StructStore store) {
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        encoder.writeKey(this.key);
        encoder.writeJSON(this.value);
    }

    @Override
    public int getRef() {
        return 6;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        return new ContentFormat(decoder.readKey(), decoder.readJSON());
    }
}

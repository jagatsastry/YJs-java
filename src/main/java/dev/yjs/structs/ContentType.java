package dev.yjs.structs;

import dev.yjs.types.AbstractType;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Content wrapping a nested {@link AbstractType} (a YArray/YMap/YText/YXml*).
 * Port of src/structs/ContentType.js.
 */
public final class ContentType extends AbstractContent {
    public static final int YArrayRefID = 0;
    public static final int YMapRefID = 1;
    public static final int YTextRefID = 2;
    public static final int YXmlElementRefID = 3;
    public static final int YXmlFragmentRefID = 4;
    public static final int YXmlHookRefID = 5;
    public static final int YXmlTextRefID = 6;

    /** Reads an {@link AbstractType} from an update decoder (mirrors a {@code readYX} free fn). */
    public interface TypeRefReader {
        AbstractType<?> read(UpdateDecoder decoder);
    }

    /**
     * Dispatch table indexed by type-ref number, mirroring the JS {@code typeRefs} array.
     * The referenced {@code read} methods live on the Y types in {@code dev.yjs.types}.
     */
    public static final TypeRefReader[] typeRefs = new TypeRefReader[]{
            dev.yjs.types.YArray::read,        // 0
            dev.yjs.types.YMap::read,          // 1
            dev.yjs.types.YText::read,         // 2
            dev.yjs.types.YXmlElement::read,   // 3
            dev.yjs.types.YXmlFragment::read,  // 4
            dev.yjs.types.YXmlHook::read,      // 5
            dev.yjs.types.YXmlText::read       // 6
    };

    public final AbstractType<?> type;

    public ContentType(AbstractType<?> type) {
        this.type = type;
    }

    @Override
    public int getLength() {
        return 1;
    }

    @Override
    public List<Object> getContent() {
        List<Object> list = new ArrayList<>();
        list.add(this.type);
        return list;
    }

    @Override
    public boolean isCountable() {
        return true;
    }

    @Override
    public ContentType copy() {
        return new ContentType(this.type._copy());
    }

    @Override
    public ContentType splice(int offset) {
        throw new UnsupportedOperationException("method unimplemented");
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        return false;
    }

    @Override
    public void integrate(Transaction transaction, Item item) {
        this.type._integrate(transaction.doc, item);
    }

    @Override
    public void delete(Transaction transaction) {
        Item item = this.type._start;
        while (item != null) {
            if (!item.deleted()) {
                item.delete(transaction);
            } else if (item.id.clock < transaction.beforeState.getOrDefault(item.id.client, 0L)) {
                // This will be gc'd later and we want to merge it if possible.
                // We try to merge all deleted items after each transaction, but we have no
                // knowledge that this needs to be merged since it is not in transaction.ds.
                // Hence we add it to transaction._mergeStructs.
                transaction._mergeStructs.add(item);
            }
            item = item.right;
        }
        for (Item mapItem : this.type._map.values()) {
            if (!mapItem.deleted()) {
                mapItem.delete(transaction);
            } else if (mapItem.id.clock < transaction.beforeState.getOrDefault(mapItem.id.client, 0L)) {
                // same as above
                transaction._mergeStructs.add(mapItem);
            }
        }
        transaction.changed.remove(this.type);
    }

    @Override
    public void gc(StructStore store) {
        Item item = this.type._start;
        while (item != null) {
            item.gc(store, true);
            item = item.right;
        }
        this.type._start = null;
        for (Item mapItem : this.type._map.values()) {
            Item cur = mapItem;
            while (cur != null) {
                cur.gc(store, true);
                cur = cur.left;
            }
        }
        this.type._map = new LinkedHashMap<>();
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        this.type._write(encoder);
    }

    @Override
    public int getRef() {
        return 7;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        return new ContentType(typeRefs[decoder.readTypeRef()].read(decoder));
    }
}

package dev.yjs.structs;

import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateEncoder;

import java.util.List;

/**
 * Base class for any {@link Item} content. Port of AbstractContent in src/structs/Item.js.
 * Do not implement this class directly; use one of the Content* subclasses.
 */
public abstract class AbstractContent {
    public abstract int getLength();

    public abstract List<Object> getContent();

    /**
     * Whether this item should be addressable via {@code yarray.get(i)} and counted in length.
     * False for meta content (e.g. formatting).
     */
    public abstract boolean isCountable();

    public abstract AbstractContent copy();

    public abstract AbstractContent splice(int offset);

    public abstract boolean mergeWith(AbstractContent right);

    public abstract void integrate(Transaction transaction, Item item);

    public abstract void delete(Transaction transaction);

    public abstract void gc(StructStore store);

    public abstract void write(UpdateEncoder encoder, int offset);

    public abstract int getRef();
}

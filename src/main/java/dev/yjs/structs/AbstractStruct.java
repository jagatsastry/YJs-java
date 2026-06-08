package dev.yjs.structs;

import dev.yjs.utils.ID;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateEncoder;

/**
 * Base of {@link Item}, {@link GC}, {@link Skip}. Port of src/structs/AbstractStruct.js.
 */
public abstract class AbstractStruct {
    public ID id;
    public int length;

    public AbstractStruct(ID id, int length) {
        this.id = id;
        this.length = length;
    }

    public abstract boolean deleted();

    /**
     * Merge this struct with the one to the right. Assumes
     * {@code this.id.clock + this.length === right.id.clock}. Does not remove from StructStore.
     */
    public boolean mergeWith(AbstractStruct right) {
        return false;
    }

    public abstract void write(UpdateEncoder encoder, int offset);

    public abstract void integrate(Transaction transaction, int offset);

    /** @return the client id of a missing dependency, or null if none. */
    public abstract Long getMissing(Transaction transaction, dev.yjs.utils.StructStore store);
}

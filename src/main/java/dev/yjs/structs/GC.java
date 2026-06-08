package dev.yjs.structs;

import dev.yjs.utils.ID;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateEncoder;

/**
 * A garbage-collected struct: stores only the length of removed content.
 * Port of src/structs/GC.js.
 */
public final class GC extends AbstractStruct {
    public static final int STRUCT_GC_REF_NUMBER = 0;

    public GC(ID id, int length) {
        super(id, length);
    }

    @Override
    public boolean deleted() {
        return true;
    }

    public void delete() {
        // nop
    }

    @Override
    public boolean mergeWith(AbstractStruct right) {
        if (this.getClass() != right.getClass()) {
            return false;
        }
        this.length += right.length;
        return true;
    }

    @Override
    public void integrate(Transaction transaction, int offset) {
        if (offset > 0) {
            this.id = ID.createID(this.id.client, this.id.clock + offset);
            this.length -= offset;
        }
        StructStore.addStruct(transaction.doc.store, this);
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        encoder.writeInfo(STRUCT_GC_REF_NUMBER);
        encoder.writeLen(this.length - offset);
    }

    public Long getMissing(Transaction transaction, StructStore store) {
        return null;
    }
}

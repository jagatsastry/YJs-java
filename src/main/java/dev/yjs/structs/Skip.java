package dev.yjs.structs;

import dev.yjs.lib0.Encoding;
import dev.yjs.utils.ID;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateEncoder;

/**
 * A Skip struct marks a gap in a client's clock range that we don't have. Port of src/structs/Skip.js.
 */
public final class Skip extends AbstractStruct {
    public static final int STRUCT_SKIP_REF_NUMBER = 10;

    public Skip(ID id, int length) {
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
        // skip structs cannot be integrated
        throw new IllegalStateException("Unexpected case");
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        encoder.writeInfo(STRUCT_SKIP_REF_NUMBER);
        // write as VarUint because Skips can't make use of predictable length-encoding
        Encoding.writeVarUint(encoder.restEncoder(), this.length - offset);
    }

    public Long getMissing(Transaction transaction, StructStore store) {
        return null;
    }
}

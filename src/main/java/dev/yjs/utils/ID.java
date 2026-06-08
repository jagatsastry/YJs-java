package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;
import dev.yjs.types.AbstractType;

import java.util.Map;

/**
 * A unique identifier: a (client, clock) Lamport timestamp. Port of src/utils/ID.js.
 */
public final class ID {
    /** Client id (uint32 stored in a long). */
    public final long client;
    /** Unique per client id, continuous number. */
    public final long clock;

    public ID(long client, long clock) {
        this.client = client;
        this.clock = clock;
    }

    public static ID createID(long client, long clock) {
        return new ID(client, clock);
    }

    /** Value equality that tolerates nulls (true iff both null, or same client+clock). */
    public static boolean compareIDs(ID a, ID b) {
        return a == b || (a != null && b != null && a.client == b.client && a.clock == b.clock);
    }

    public static void writeID(Encoder encoder, ID id) {
        Encoding.writeVarUint(encoder, id.client);
        Encoding.writeVarUint(encoder, id.clock);
    }

    public static ID readID(Decoder decoder) {
        return createID(Decoding.readVarUint(decoder), Decoding.readVarUint(decoder));
    }

    /**
     * Find the root-type key for a top-level type registered on its doc's {@code share} map.
     */
    public static String findRootTypeKey(AbstractType<?> type) {
        for (Map.Entry<String, AbstractType<?>> e : type.doc.share.entrySet()) {
            if (e.getValue() == type) {
                return e.getKey();
            }
        }
        throw new IllegalStateException("Unexpected case");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ID id)) return false;
        return client == id.client && clock == id.clock;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(client) * 31 + Long.hashCode(clock);
    }

    @Override
    public String toString() {
        return "ID(" + client + "," + clock + ")";
    }
}

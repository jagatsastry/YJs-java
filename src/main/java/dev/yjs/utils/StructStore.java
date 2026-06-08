package dev.yjs.utils;

import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.GC;
import dev.yjs.structs.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Stores all structs per client in insertion order. Port of src/utils/StructStore.js.
 */
public final class StructStore {
    /** Map from client to its structs (GC|Item) in clock order. */
    public final Map<Long, List<AbstractStruct>> clients = new LinkedHashMap<>();

    /** Pending structs that could not be applied yet (missing dependencies). */
    public PendingStructs pendingStructs = null;
    /** Pending delete-set update bytes. */
    public byte[] pendingDs = null;

    public static final class PendingStructs {
        public final Map<Long, Long> missing;
        public byte[] update;

        public PendingStructs(Map<Long, Long> missing, byte[] update) {
            this.missing = missing;
            this.update = update;
        }
    }

    /** Return the state vector Map&lt;client, nextClock&gt;. */
    public static Map<Long, Long> getStateVector(StructStore store) {
        Map<Long, Long> sm = new LinkedHashMap<>();
        store.clients.forEach((client, structs) -> {
            AbstractStruct struct = structs.get(structs.size() - 1);
            sm.put(client, struct.id.clock + struct.length);
        });
        return sm;
    }

    public static long getState(StructStore store, long client) {
        List<AbstractStruct> structs = store.clients.get(client);
        if (structs == null) {
            return 0;
        }
        AbstractStruct lastStruct = structs.get(structs.size() - 1);
        return lastStruct.id.clock + lastStruct.length;
    }

    public static void integrityCheck(StructStore store) {
        store.clients.forEach((client, structs) -> {
            for (int i = 1; i < structs.size(); i++) {
                AbstractStruct l = structs.get(i - 1);
                AbstractStruct r = structs.get(i);
                if (l.id.clock + l.length != r.id.clock) {
                    throw new RuntimeException("StructStore failed integrity check");
                }
            }
        });
    }

    public static void addStruct(StructStore store, AbstractStruct struct) {
        List<AbstractStruct> structs = store.clients.get(struct.id.client);
        if (structs == null) {
            structs = new ArrayList<>();
            store.clients.put(struct.id.client, structs);
        } else {
            AbstractStruct lastStruct = structs.get(structs.size() - 1);
            if (lastStruct.id.clock + lastStruct.length != struct.id.clock) {
                throw new IllegalStateException("Unexpected case");
            }
        }
        structs.add(struct);
    }

    /** Binary search (with pivot heuristic) for the struct containing {@code clock}. */
    public static int findIndexSS(List<AbstractStruct> structs, long clock) {
        int left = 0;
        int right = structs.size() - 1;
        AbstractStruct mid = structs.get(right);
        long midclock = mid.id.clock;
        if (midclock == clock) {
            return right;
        }
        int midindex = (int) Math.floor(((double) clock / (double) (midclock + mid.length - 1)) * right);
        while (left <= right) {
            mid = structs.get(midindex);
            midclock = mid.id.clock;
            if (midclock <= clock) {
                if (clock < midclock + mid.length) {
                    return midindex;
                }
                left = midindex + 1;
            } else {
                right = midindex - 1;
            }
            midindex = (int) Math.floor((left + right) / 2.0);
        }
        throw new IllegalStateException("Unexpected case");
    }

    public static AbstractStruct find(StructStore store, ID id) {
        List<AbstractStruct> structs = store.clients.get(id.client);
        return structs.get(findIndexSS(structs, id.clock));
    }

    /** Alias of {@link #find} that returns an Item (caller guarantees it is one). */
    public static Item getItem(StructStore store, ID id) {
        return (Item) find(store, id);
    }

    public static int findIndexCleanStart(Transaction transaction, List<AbstractStruct> structs, long clock) {
        int index = findIndexSS(structs, clock);
        AbstractStruct struct = structs.get(index);
        if (struct.id.clock < clock && struct instanceof Item item) {
            structs.add(index + 1, Item.splitItem(transaction, item, (int) (clock - struct.id.clock)));
            return index + 1;
        }
        return index;
    }

    public static Item getItemCleanStart(Transaction transaction, ID id) {
        List<AbstractStruct> structs = transaction.doc.store.clients.get(id.client);
        return (Item) structs.get(findIndexCleanStart(transaction, structs, id.clock));
    }

    /** Like {@link #getItemCleanStart} but may return a GC struct (used by getMissing). */
    public static AbstractStruct getStructCleanStart(Transaction transaction, ID id) {
        List<AbstractStruct> structs = transaction.doc.store.clients.get(id.client);
        return structs.get(findIndexCleanStart(transaction, structs, id.clock));
    }

    public static AbstractStruct getItemCleanEnd(Transaction transaction, StructStore store, ID id) {
        List<AbstractStruct> structs = store.clients.get(id.client);
        int index = findIndexSS(structs, id.clock);
        AbstractStruct struct = structs.get(index);
        if (id.clock != struct.id.clock + struct.length - 1 && !(struct instanceof GC)) {
            structs.add(index + 1, Item.splitItem(transaction, (Item) struct, (int) (id.clock - struct.id.clock + 1)));
        }
        return struct;
    }

    public static void replaceStruct(StructStore store, AbstractStruct struct, AbstractStruct newStruct) {
        List<AbstractStruct> structs = store.clients.get(struct.id.client);
        structs.set(findIndexSS(structs, struct.id.clock), newStruct);
    }

    /** Iterate over a range of structs, splitting at boundaries as needed. */
    public static void iterateStructs(Transaction transaction, List<AbstractStruct> structs, long clockStart, long len, Consumer<AbstractStruct> f) {
        if (len == 0) {
            return;
        }
        long clockEnd = clockStart + len;
        int index = findIndexCleanStart(transaction, structs, clockStart);
        AbstractStruct struct;
        do {
            struct = structs.get(index++);
            if (clockEnd < struct.id.clock + struct.length) {
                findIndexCleanStart(transaction, structs, clockEnd);
            }
            f.accept(struct);
        } while (index < structs.size() && structs.get(index).id.clock < clockEnd);
    }
}

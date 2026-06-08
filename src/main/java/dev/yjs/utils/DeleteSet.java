package dev.yjs.utils;

import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoding;
import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.GC;
import dev.yjs.structs.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A temporary representation of the set of deleted ids. Port of src/utils/DeleteSet.js.
 */
public final class DeleteSet {
    public final Map<Long, List<DeleteItem>> clients = new LinkedHashMap<>();

    public static final class DeleteItem {
        public long clock;
        public long len;

        public DeleteItem(long clock, long len) {
            this.clock = clock;
            this.len = len;
        }
    }

    public static DeleteSet createDeleteSet() {
        return new DeleteSet();
    }

    public static void iterateDeletedStructs(Transaction transaction, DeleteSet ds, Consumer<AbstractStruct> f) {
        ds.clients.forEach((clientid, deletes) -> {
            List<AbstractStruct> structs = transaction.doc.store.clients.get(clientid);
            if (structs != null) {
                AbstractStruct lastStruct = structs.get(structs.size() - 1);
                long clockState = lastStruct.id.clock + lastStruct.length;
                for (int i = 0; i < deletes.size() && deletes.get(i).clock < clockState; i++) {
                    DeleteItem del = deletes.get(i);
                    StructStore.iterateStructs(transaction, structs, del.clock, del.len, f);
                }
            }
        });
    }

    /** @return index into dis, or null. */
    public static Integer findIndexDS(List<DeleteItem> dis, long clock) {
        int left = 0;
        int right = dis.size() - 1;
        while (left <= right) {
            int midindex = (int) Math.floor((left + right) / 2.0);
            DeleteItem mid = dis.get(midindex);
            long midclock = mid.clock;
            if (midclock <= clock) {
                if (clock < midclock + mid.len) {
                    return midindex;
                }
                left = midindex + 1;
            } else {
                right = midindex - 1;
            }
        }
        return null;
    }

    public static boolean isDeleted(DeleteSet ds, ID id) {
        List<DeleteItem> dis = ds.clients.get(id.client);
        return dis != null && findIndexDS(dis, id.clock) != null;
    }

    public static void sortAndMergeDeleteSet(DeleteSet ds) {
        ds.clients.forEach((client, dels) -> {
            dels.sort(Comparator.comparingLong(a -> a.clock));
            int i, j;
            for (i = 1, j = 1; i < dels.size(); i++) {
                DeleteItem left = dels.get(j - 1);
                DeleteItem right = dels.get(i);
                if (left.clock + left.len >= right.clock) {
                    dels.set(j - 1, new DeleteItem(left.clock, Math.max(left.len, right.clock + right.len - left.clock)));
                } else {
                    if (j < i) {
                        dels.set(j, right);
                    }
                    j++;
                }
            }
            // truncate to length j
            while (dels.size() > j) {
                dels.remove(dels.size() - 1);
            }
        });
    }

    public static DeleteSet mergeDeleteSets(List<DeleteSet> dss) {
        DeleteSet merged = new DeleteSet();
        for (int dssI = 0; dssI < dss.size(); dssI++) {
            final int dssIf = dssI;
            dss.get(dssI).clients.forEach((client, delsLeft) -> {
                if (!merged.clients.containsKey(client)) {
                    List<DeleteItem> dels = new ArrayList<>(delsLeft);
                    for (int i = dssIf + 1; i < dss.size(); i++) {
                        List<DeleteItem> other = dss.get(i).clients.get(client);
                        if (other != null) {
                            dels.addAll(other);
                        }
                    }
                    merged.clients.put(client, dels);
                }
            });
        }
        sortAndMergeDeleteSet(merged);
        return merged;
    }

    public static void addToDeleteSet(DeleteSet ds, long client, long clock, long length) {
        ds.clients.computeIfAbsent(client, k -> new ArrayList<>()).add(new DeleteItem(clock, length));
    }

    /** @return merged and sorted DeleteSet built from the deleted structs in {@code ss}. */
    public static DeleteSet createDeleteSetFromStructStore(StructStore ss) {
        DeleteSet ds = createDeleteSet();
        ss.clients.forEach((client, structs) -> {
            List<DeleteItem> dsitems = new ArrayList<>();
            for (int i = 0; i < structs.size(); i++) {
                AbstractStruct struct = structs.get(i);
                if (struct.deleted()) {
                    long clock = struct.id.clock;
                    long len = struct.length;
                    while (i + 1 < structs.size() && structs.get(i + 1).deleted()) {
                        len += structs.get(i + 1).length;
                        i++;
                    }
                    dsitems.add(new DeleteItem(clock, len));
                }
            }
            if (!dsitems.isEmpty()) {
                ds.clients.put(client, dsitems);
            }
        });
        return ds;
    }

    public static void writeDeleteSet(DSEncoder encoder, DeleteSet ds) {
        Encoding.writeVarUint(encoder.restEncoder(), ds.clients.size());
        // Deterministic order: descending client id.
        List<Map.Entry<Long, List<DeleteItem>>> entries = new ArrayList<>(ds.clients.entrySet());
        entries.sort((a, b) -> Long.compare(b.getKey(), a.getKey()));
        for (Map.Entry<Long, List<DeleteItem>> e : entries) {
            encoder.resetDsCurVal();
            Encoding.writeVarUint(encoder.restEncoder(), e.getKey());
            List<DeleteItem> dsitems = e.getValue();
            int len = dsitems.size();
            Encoding.writeVarUint(encoder.restEncoder(), len);
            for (int i = 0; i < len; i++) {
                DeleteItem item = dsitems.get(i);
                encoder.writeDsClock(item.clock);
                encoder.writeDsLen(item.len);
            }
        }
    }

    public static DeleteSet readDeleteSet(DSDecoder decoder) {
        DeleteSet ds = new DeleteSet();
        long numClients = Decoding.readVarUint(decoder.restDecoder());
        for (long i = 0; i < numClients; i++) {
            decoder.resetDsCurVal();
            long client = Decoding.readVarUint(decoder.restDecoder());
            long numberOfDeletes = Decoding.readVarUint(decoder.restDecoder());
            if (numberOfDeletes > 0) {
                List<DeleteItem> dsField = ds.clients.computeIfAbsent(client, k -> new ArrayList<>());
                for (long d = 0; d < numberOfDeletes; d++) {
                    dsField.add(new DeleteItem(decoder.readDsClock(), decoder.readDsLen()));
                }
            }
        }
        return ds;
    }

    /**
     * @return a v2 update with the deletes that couldn't be applied yet, or null if all applied.
     */
    public static byte[] readAndApplyDeleteSet(DSDecoder decoder, Transaction transaction, StructStore store) {
        DeleteSet unappliedDS = new DeleteSet();
        long numClients = Decoding.readVarUint(decoder.restDecoder());
        for (long c = 0; c < numClients; c++) {
            decoder.resetDsCurVal();
            long client = Decoding.readVarUint(decoder.restDecoder());
            long numberOfDeletes = Decoding.readVarUint(decoder.restDecoder());
            List<AbstractStruct> structs = store.clients.getOrDefault(client, new ArrayList<>());
            long state = StructStore.getState(store, client);
            for (long d = 0; d < numberOfDeletes; d++) {
                long clock = decoder.readDsClock();
                long clockEnd = clock + decoder.readDsLen();
                if (clock < state) {
                    if (state < clockEnd) {
                        addToDeleteSet(unappliedDS, client, state, clockEnd - state);
                    }
                    int index = StructStore.findIndexSS(structs, clock);
                    AbstractStruct struct = structs.get(index);
                    if (!struct.deleted() && struct.id.clock < clock) {
                        structs.add(index + 1, Item.splitItem(transaction, (Item) struct, (int) (clock - struct.id.clock)));
                        index++;
                    }
                    while (index < structs.size()) {
                        struct = structs.get(index++);
                        if (struct.id.clock < clockEnd) {
                            if (!struct.deleted()) {
                                if (clockEnd < struct.id.clock + struct.length) {
                                    structs.add(index, Item.splitItem(transaction, (Item) struct, (int) (clockEnd - struct.id.clock)));
                                }
                                ((Item) struct).delete(transaction);
                            }
                        } else {
                            break;
                        }
                    }
                } else {
                    addToDeleteSet(unappliedDS, client, clock, clockEnd - clock);
                }
            }
        }
        if (!unappliedDS.clients.isEmpty()) {
            UpdateEncoderV2 ds = new UpdateEncoderV2();
            Encoding.writeVarUint(ds.restEncoder(), 0); // encode 0 structs
            writeDeleteSet(ds, unappliedDS);
            return ds.toUint8Array();
        }
        return null;
    }

    public static boolean equalDeleteSets(DeleteSet ds1, DeleteSet ds2) {
        if (ds1.clients.size() != ds2.clients.size()) return false;
        for (Map.Entry<Long, List<DeleteItem>> e : ds1.clients.entrySet()) {
            List<DeleteItem> deleteItems1 = e.getValue();
            List<DeleteItem> deleteItems2 = ds2.clients.get(e.getKey());
            if (deleteItems2 == null || deleteItems1.size() != deleteItems2.size()) return false;
            for (int i = 0; i < deleteItems1.size(); i++) {
                DeleteItem di1 = deleteItems1.get(i);
                DeleteItem di2 = deleteItems2.get(i);
                if (di1.clock != di2.clock || di1.len != di2.len) {
                    return false;
                }
            }
        }
        return true;
    }
}

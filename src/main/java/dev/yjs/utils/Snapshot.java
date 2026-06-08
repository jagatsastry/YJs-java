package dev.yjs.utils;

import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoding;
import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A snapshot captures the state of a document at a point in time: a delete-set together with a
 * state vector. Port of src/utils/Snapshot.js.
 */
public final class Snapshot {
    public final DeleteSet ds;
    /** State map: client -&gt; next clock. */
    public final Map<Long, Long> sv;

    public Snapshot(DeleteSet ds, Map<Long, Long> sv) {
        this.ds = ds;
        this.sv = sv;
    }

    public static boolean equalSnapshots(Snapshot snap1, Snapshot snap2) {
        Map<Long, List<DeleteSet.DeleteItem>> ds1 = snap1.ds.clients;
        Map<Long, List<DeleteSet.DeleteItem>> ds2 = snap2.ds.clients;
        Map<Long, Long> sv1 = snap1.sv;
        Map<Long, Long> sv2 = snap2.sv;
        if (sv1.size() != sv2.size() || ds1.size() != ds2.size()) {
            return false;
        }
        for (Map.Entry<Long, Long> e : sv1.entrySet()) {
            if (!java.util.Objects.equals(sv2.get(e.getKey()), e.getValue())) {
                return false;
            }
        }
        for (Map.Entry<Long, List<DeleteSet.DeleteItem>> e : ds1.entrySet()) {
            List<DeleteSet.DeleteItem> dsitems1 = e.getValue();
            List<DeleteSet.DeleteItem> dsitems2 = ds2.get(e.getKey());
            if (dsitems2 == null) {
                dsitems2 = new ArrayList<>();
            }
            if (dsitems1.size() != dsitems2.size()) {
                return false;
            }
            for (int i = 0; i < dsitems1.size(); i++) {
                DeleteSet.DeleteItem dsitem1 = dsitems1.get(i);
                DeleteSet.DeleteItem dsitem2 = dsitems2.get(i);
                if (dsitem1.clock != dsitem2.clock || dsitem1.len != dsitem2.len) {
                    return false;
                }
            }
        }
        return true;
    }

    public static byte[] encodeSnapshotV2(Snapshot snapshot, DSEncoder encoder) {
        DeleteSet.writeDeleteSet(encoder, snapshot.ds);
        Updates.writeStateVector(encoder, snapshot.sv);
        return encoder.toUint8Array();
    }

    public static byte[] encodeSnapshotV2(Snapshot snapshot) {
        return encodeSnapshotV2(snapshot, new DSEncoderV2());
    }

    public static byte[] encodeSnapshot(Snapshot snapshot) {
        return encodeSnapshotV2(snapshot, new DSEncoderV1());
    }

    public static Snapshot decodeSnapshotV2(byte[] buf, DSDecoder decoder) {
        return new Snapshot(DeleteSet.readDeleteSet(decoder), Updates.readStateVector(decoder));
    }

    public static Snapshot decodeSnapshotV2(byte[] buf) {
        return decodeSnapshotV2(buf, new DSDecoderV2(Decoding.createDecoder(buf)));
    }

    public static Snapshot decodeSnapshot(byte[] buf) {
        return decodeSnapshotV2(buf, new DSDecoderV1(Decoding.createDecoder(buf)));
    }

    public static Snapshot createSnapshot(DeleteSet ds, Map<Long, Long> sm) {
        return new Snapshot(ds, sm);
    }

    public static final Snapshot emptySnapshot = createSnapshot(DeleteSet.createDeleteSet(), new LinkedHashMap<>());

    public static Snapshot snapshot(Doc doc) {
        return createSnapshot(DeleteSet.createDeleteSetFromStructStore(doc.store), StructStore.getStateVector(doc.store));
    }

    /**
     * Whether {@code item} is visible in {@code snapshot}. If {@code snapshot} is {@code null} the
     * item is visible iff it is not deleted.
     */
    public static boolean isVisible(Item item, Snapshot snapshot) {
        return snapshot == null
                ? !item.deleted()
                : snapshot.sv.containsKey(item.id.client)
                && snapshot.sv.getOrDefault(item.id.client, 0L) > item.id.clock
                && !DeleteSet.isDeleted(snapshot.ds, item.id);
    }

    /** Stable key used to memoize {@link #splitSnapshotAffectedStructs} on a transaction. */
    private static final Object SPLIT_SNAPSHOT_META_KEY = new Object();

    @SuppressWarnings("unchecked")
    public static void splitSnapshotAffectedStructs(Transaction transaction, Snapshot snapshot) {
        Set<Snapshot> meta = (Set<Snapshot>) transaction.meta.computeIfAbsent(
                SPLIT_SNAPSHOT_META_KEY, k -> new LinkedHashSet<Snapshot>());
        StructStore store = transaction.doc.store;
        // check if we already split for this snapshot
        if (!meta.contains(snapshot)) {
            snapshot.sv.forEach((client, clock) -> {
                if (clock < StructStore.getState(store, client)) {
                    StructStore.getItemCleanStart(transaction, ID.createID(client, clock));
                }
            });
            DeleteSet.iterateDeletedStructs(transaction, snapshot.ds, _item -> {});
            meta.add(snapshot);
        }
    }

    /**
     * Restore a document to the state captured by {@code snapshot}.
     *
     * <pre>
     *  Doc ydoc = new Doc(new Doc.Options().gc(false));
     *  ydoc.getText().insert(0, "world!");
     *  Snapshot snap = Snapshot.snapshot(ydoc);
     *  ydoc.getText().insert(0, "hello ");
     *  Doc restored = Snapshot.createDocFromSnapshot(ydoc, snap);
     *  // restored.getText().toString().equals("world!")
     * </pre>
     *
     * @param originDoc the source document (must have gc disabled)
     * @param snapshot  the snapshot to restore
     * @param newDoc    optionally, the document that receives the data from originDoc
     * @return {@code newDoc}
     */
    public static Doc createDocFromSnapshot(Doc originDoc, Snapshot snapshot, Doc newDoc) {
        if (originDoc.gc) {
            // we should not try to restore a GC-ed document, because some of the restored items might have their content deleted
            throw new RuntimeException("Garbage-collection must be disabled in `originDoc`!");
        }
        Map<Long, Long> sv = snapshot.sv;
        DeleteSet ds = snapshot.ds;

        UpdateEncoderV2 encoder = new UpdateEncoderV2();
        originDoc.transact(transaction -> {
            int[] size = {0};
            sv.forEach((client, clock) -> {
                if (clock > 0) {
                    size[0]++;
                }
            });
            Encoding.writeVarUint(encoder.restEncoder, size[0]);
            // splitting the structs before writing them to the encoder
            for (Map.Entry<Long, Long> e : sv.entrySet()) {
                long client = e.getKey();
                long clock = e.getValue();
                if (clock == 0) {
                    continue;
                }
                if (clock < StructStore.getState(originDoc.store, client)) {
                    StructStore.getItemCleanStart(transaction, ID.createID(client, clock));
                }
                List<AbstractStruct> structs = originDoc.store.clients.getOrDefault(client, new ArrayList<>());
                int lastStructIndex = StructStore.findIndexSS(structs, clock - 1);
                // write # encoded structs
                Encoding.writeVarUint(encoder.restEncoder, lastStructIndex + 1);
                encoder.writeClient(client);
                // first clock written is 0
                Encoding.writeVarUint(encoder.restEncoder, 0);
                for (int i = 0; i <= lastStructIndex; i++) {
                    structs.get(i).write(encoder, 0);
                }
            }
            DeleteSet.writeDeleteSet(encoder, ds);
        });

        Updates.applyUpdateV2(newDoc, encoder.toUint8Array(), "snapshot");
        return newDoc;
    }

    public static Doc createDocFromSnapshot(Doc originDoc, Snapshot snapshot) {
        return createDocFromSnapshot(originDoc, snapshot, new Doc());
    }

    /**
     * Whether {@code update} (a v2 update) is fully contained in {@code snapshot}.
     */
    public static boolean snapshotContainsUpdateV2(Snapshot snapshot, byte[] update, UpdateDecoder updateDecoder) {
        List<AbstractStruct> structs = new ArrayList<>();
        UpdateMerging.LazyStructReader lazyDecoder = new UpdateMerging.LazyStructReader(updateDecoder, false);
        for (AbstractStruct curr = lazyDecoder.curr; curr != null; curr = lazyDecoder.next()) {
            structs.add(curr);
            if (snapshot.sv.getOrDefault(curr.id.client, 0L) < curr.id.clock + curr.length) {
                return false;
            }
        }
        List<DeleteSet> toMerge = new ArrayList<>();
        toMerge.add(snapshot.ds);
        toMerge.add(DeleteSet.readDeleteSet(updateDecoder));
        DeleteSet mergedDS = DeleteSet.mergeDeleteSets(toMerge);
        return DeleteSet.equalDeleteSets(snapshot.ds, mergedDS);
    }

    public static boolean snapshotContainsUpdateV2(Snapshot snapshot, byte[] update) {
        return snapshotContainsUpdateV2(snapshot, update, new UpdateDecoderV2(Decoding.createDecoder(update)));
    }

    public static boolean snapshotContainsUpdate(Snapshot snapshot, byte[] update) {
        return snapshotContainsUpdateV2(snapshot, update, new UpdateDecoderV1(Decoding.createDecoder(update)));
    }
}

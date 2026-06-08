package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoding;
import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.GC;
import dev.yjs.structs.Item;
import dev.yjs.structs.Skip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.yjs.lib0.Binary.BIT6;
import static dev.yjs.lib0.Binary.BIT7;
import static dev.yjs.lib0.Binary.BIT8;
import static dev.yjs.lib0.Binary.BITS5;
import static dev.yjs.utils.ID.createID;
import static dev.yjs.utils.StructStore.findIndexSS;
import static dev.yjs.utils.StructStore.getState;
import static dev.yjs.utils.StructStore.getStateVector;

/**
 * Update encode/apply pipeline + state vectors. Port of src/utils/encoding.js.
 * (Merge/diff/lazy-reader functions live in {@link UpdateMerging}, port of updates.js.)
 */
public final class Updates {
    private Updates() {}

    static void writeStructs(UpdateEncoder encoder, List<AbstractStruct> structs, long client, long clock) {
        clock = Math.max(clock, structs.get(0).id.clock);
        int startNewStructs = findIndexSS(structs, clock);
        Encoding.writeVarUint(encoder.restEncoder(), structs.size() - startNewStructs);
        encoder.writeClient(client);
        Encoding.writeVarUint(encoder.restEncoder(), clock);
        AbstractStruct firstStruct = structs.get(startNewStructs);
        firstStruct.write(encoder, (int) (clock - firstStruct.id.clock));
        for (int i = startNewStructs + 1; i < structs.size(); i++) {
            structs.get(i).write(encoder, 0);
        }
    }

    public static void writeClientsStructs(UpdateEncoder encoder, StructStore store, Map<Long, Long> _sm) {
        Map<Long, Long> sm = new LinkedHashMap<>();
        _sm.forEach((client, clock) -> {
            if (getState(store, client) > clock) {
                sm.put(client, clock);
            }
        });
        getStateVector(store).forEach((client, _clock) -> {
            if (!_sm.containsKey(client)) {
                sm.put(client, 0L);
            }
        });
        Encoding.writeVarUint(encoder.restEncoder(), sm.size());
        List<Map.Entry<Long, Long>> entries = new ArrayList<>(sm.entrySet());
        entries.sort((a, b) -> Long.compare(b.getKey(), a.getKey()));
        for (Map.Entry<Long, Long> e : entries) {
            writeStructs(encoder, store.clients.get(e.getKey()), e.getKey(), e.getValue());
        }
    }

    /** A client's pending struct refs during integration. */
    static final class StructRefs {
        int i;
        AbstractStruct[] refs;

        StructRefs(int i, AbstractStruct[] refs) {
            this.i = i;
            this.refs = refs;
        }
    }

    public static Map<Long, StructRefs> readClientsStructRefs(UpdateDecoder decoder, Doc doc) {
        Map<Long, StructRefs> clientRefs = new LinkedHashMap<>();
        long numOfStateUpdates = Decoding.readVarUint(decoder.restDecoder());
        for (long u = 0; u < numOfStateUpdates; u++) {
            int numberOfStructs = (int) Decoding.readVarUint(decoder.restDecoder());
            AbstractStruct[] refs = new AbstractStruct[numberOfStructs];
            long client = decoder.readClient();
            long clock = Decoding.readVarUint(decoder.restDecoder());
            clientRefs.put(client, new StructRefs(0, refs));
            for (int i = 0; i < numberOfStructs; i++) {
                int info = decoder.readInfo();
                switch (BITS5 & info) {
                    case 0: { // GC
                        int len = decoder.readLen();
                        refs[i] = new GC(createID(client, clock), len);
                        clock += len;
                        break;
                    }
                    case 10: { // Skip
                        int len = (int) Decoding.readVarUint(decoder.restDecoder());
                        refs[i] = new Skip(createID(client, clock), len);
                        clock += len;
                        break;
                    }
                    default: { // Item with content
                        boolean cantCopyParentInfo = (info & (BIT7 | BIT8)) == 0;
                        Item struct = new Item(
                                createID(client, clock),
                                null,
                                (info & BIT8) == BIT8 ? decoder.readLeftID() : null,
                                null,
                                (info & BIT7) == BIT7 ? decoder.readRightID() : null,
                                cantCopyParentInfo ? (decoder.readParentInfo() ? doc.get(decoder.readString()) : decoder.readLeftID()) : null,
                                cantCopyParentInfo && (info & BIT6) == BIT6 ? decoder.readString() : null,
                                Item.readItemContent(decoder, info)
                        );
                        refs[i] = struct;
                        clock += struct.length;
                    }
                }
            }
        }
        return clientRefs;
    }

    static StructStore.PendingStructs integrateStructs(Transaction transaction, StructStore store, Map<Long, StructRefs> clientsStructRefs) {
        List<AbstractStruct> stack = new ArrayList<>();
        List<Long> clientsStructRefsIds = new ArrayList<>(clientsStructRefs.keySet());
        clientsStructRefsIds.sort(Long::compare);
        if (clientsStructRefsIds.isEmpty()) {
            return null;
        }

        StructStore restStructs = new StructStore();
        Map<Long, Long> missingSV = new LinkedHashMap<>();
        java.util.function.BiConsumer<Long, Long> updateMissingSv = (client, clock) -> {
            Long mclock = missingSV.get(client);
            if (mclock == null || mclock > clock) {
                missingSV.put(client, clock);
            }
        };
        Map<Long, Long> state = new LinkedHashMap<>();

        // local mutable holder for curStructsTarget
        StructRefs[] curHolder = new StructRefs[1];
        // getNextStructTarget
        java.util.function.Supplier<StructRefs> getNextStructTarget = () -> {
            if (clientsStructRefsIds.isEmpty()) {
                return null;
            }
            StructRefs nextStructsTarget = clientsStructRefs.get(clientsStructRefsIds.get(clientsStructRefsIds.size() - 1));
            while (nextStructsTarget.refs.length == nextStructsTarget.i) {
                clientsStructRefsIds.remove(clientsStructRefsIds.size() - 1);
                if (!clientsStructRefsIds.isEmpty()) {
                    nextStructsTarget = clientsStructRefs.get(clientsStructRefsIds.get(clientsStructRefsIds.size() - 1));
                } else {
                    return null;
                }
            }
            return nextStructsTarget;
        };
        curHolder[0] = getNextStructTarget.get();
        if (curHolder[0] == null) {
            return null;
        }

        AbstractStruct stackHead = curHolder[0].refs[curHolder[0].i++];

        Runnable addStackToRestSS = () -> {
            for (AbstractStruct item : stack) {
                long client = item.id.client;
                StructRefs inapplicableItems = clientsStructRefs.get(client);
                if (inapplicableItems != null) {
                    inapplicableItems.i--;
                    restStructs.clients.put(client, new ArrayList<>(Arrays.asList(Arrays.copyOfRange(inapplicableItems.refs, inapplicableItems.i, inapplicableItems.refs.length))));
                    clientsStructRefs.remove(client);
                    inapplicableItems.i = 0;
                    inapplicableItems.refs = new AbstractStruct[0];
                } else {
                    List<AbstractStruct> single = new ArrayList<>();
                    single.add(item);
                    restStructs.clients.put(client, single);
                }
                clientsStructRefsIds.removeIf(c -> c == client);
            }
            stack.clear();
        };

        while (true) {
            if (!(stackHead instanceof Skip)) {
                final AbstractStruct sh = stackHead;
                long localClock = state.computeIfAbsent(sh.id.client, c -> getState(store, c));
                long offset = localClock - stackHead.id.clock;
                if (offset < 0) {
                    stack.add(stackHead);
                    updateMissingSv.accept(stackHead.id.client, stackHead.id.clock - 1);
                    addStackToRestSS.run();
                } else {
                    Long missing = stackHead.getMissing(transaction, store);
                    if (missing != null) {
                        stack.add(stackHead);
                        StructRefs structRefs = clientsStructRefs.getOrDefault(missing, new StructRefs(0, new AbstractStruct[0]));
                        if (structRefs.refs.length == structRefs.i) {
                            updateMissingSv.accept(missing, getState(store, missing));
                            addStackToRestSS.run();
                        } else {
                            stackHead = structRefs.refs[structRefs.i++];
                            continue;
                        }
                    } else if (offset == 0 || offset < stackHead.length) {
                        stackHead.integrate(transaction, (int) offset);
                        state.put(stackHead.id.client, stackHead.id.clock + stackHead.length);
                    }
                }
            }
            if (!stack.isEmpty()) {
                stackHead = stack.remove(stack.size() - 1);
            } else if (curHolder[0] != null && curHolder[0].i < curHolder[0].refs.length) {
                stackHead = curHolder[0].refs[curHolder[0].i++];
            } else {
                curHolder[0] = getNextStructTarget.get();
                if (curHolder[0] == null) {
                    break;
                } else {
                    stackHead = curHolder[0].refs[curHolder[0].i++];
                }
            }
        }
        if (!restStructs.clients.isEmpty()) {
            UpdateEncoderV2 encoder = new UpdateEncoderV2();
            writeClientsStructs(encoder, restStructs, new LinkedHashMap<>());
            Encoding.writeVarUint(encoder.restEncoder(), 0); // no deletes
            return new StructStore.PendingStructs(missingSV, encoder.toUint8Array());
        }
        return null;
    }

    public static void writeStructsFromTransaction(UpdateEncoder encoder, Transaction transaction) {
        writeClientsStructs(encoder, transaction.doc.store, transaction.beforeState);
    }

    public static void readUpdateV2(Decoder decoder, Doc ydoc, Object transactionOrigin, UpdateDecoder structDecoder) {
        Transaction.transact(ydoc, transaction -> {
            transaction.local = false; // note: local is final in our Transaction? handled below
            boolean[] retry = {false};
            Doc doc = transaction.doc;
            StructStore store = doc.store;
            Map<Long, StructRefs> ss = readClientsStructRefs(structDecoder, doc);
            StructStore.PendingStructs restStructs = integrateStructs(transaction, store, ss);
            StructStore.PendingStructs pending = store.pendingStructs;
            if (pending != null) {
                for (Map.Entry<Long, Long> e : pending.missing.entrySet()) {
                    if (e.getValue() < getState(store, e.getKey())) {
                        retry[0] = true;
                        break;
                    }
                }
                if (restStructs != null) {
                    for (Map.Entry<Long, Long> e : restStructs.missing.entrySet()) {
                        Long mclock = pending.missing.get(e.getKey());
                        if (mclock == null || mclock > e.getValue()) {
                            pending.missing.put(e.getKey(), e.getValue());
                        }
                    }
                    pending.update = UpdateMerging.mergeUpdatesV2(Arrays.asList(pending.update, restStructs.update));
                }
            } else {
                store.pendingStructs = restStructs;
            }
            byte[] dsRest = DeleteSet.readAndApplyDeleteSet(structDecoder, transaction, store);
            if (store.pendingDs != null) {
                UpdateDecoderV2 pendingDSUpdate = new UpdateDecoderV2(Decoding.createDecoder(store.pendingDs));
                Decoding.readVarUint(pendingDSUpdate.restDecoder()); // read 0 structs
                byte[] dsRest2 = DeleteSet.readAndApplyDeleteSet(pendingDSUpdate, transaction, store);
                if (dsRest != null && dsRest2 != null) {
                    store.pendingDs = UpdateMerging.mergeUpdatesV2(Arrays.asList(dsRest, dsRest2));
                } else {
                    store.pendingDs = dsRest != null ? dsRest : dsRest2;
                }
            } else {
                store.pendingDs = dsRest;
            }
            if (retry[0]) {
                byte[] update = store.pendingStructs.update;
                store.pendingStructs = null;
                applyUpdateV2(transaction.doc, update, null);
            }
        }, transactionOrigin, false);
    }

    public static void readUpdate(Decoder decoder, Doc ydoc, Object transactionOrigin) {
        readUpdateV2(decoder, ydoc, transactionOrigin, new UpdateDecoderV1(decoder));
    }

    public static void applyUpdateV2(Doc ydoc, byte[] update, Object transactionOrigin) {
        Decoder decoder = Decoding.createDecoder(update);
        readUpdateV2(decoder, ydoc, transactionOrigin, new UpdateDecoderV2(decoder));
    }

    public static void applyUpdate(Doc ydoc, byte[] update, Object transactionOrigin) {
        Decoder decoder = Decoding.createDecoder(update);
        readUpdateV2(decoder, ydoc, transactionOrigin, new UpdateDecoderV1(decoder));
    }

    public static void applyUpdate(Doc ydoc, byte[] update) {
        applyUpdate(ydoc, update, null);
    }

    public static void writeStateAsUpdate(UpdateEncoder encoder, Doc doc, Map<Long, Long> targetStateVector) {
        writeClientsStructs(encoder, doc.store, targetStateVector);
        DeleteSet.writeDeleteSet(encoder, DeleteSet.createDeleteSetFromStructStore(doc.store));
    }

    public static byte[] encodeStateAsUpdateV2(Doc doc, byte[] encodedTargetStateVector, UpdateEncoder encoder) {
        if (encodedTargetStateVector == null) {
            encodedTargetStateVector = new byte[]{0};
        }
        Map<Long, Long> targetStateVector = decodeStateVector(encodedTargetStateVector);
        writeStateAsUpdate(encoder, doc, targetStateVector);
        List<byte[]> updates = new ArrayList<>();
        updates.add(encoder.toUint8Array());
        if (doc.store.pendingDs != null) {
            updates.add(doc.store.pendingDs);
        }
        if (doc.store.pendingStructs != null) {
            updates.add(UpdateMerging.diffUpdateV2(doc.store.pendingStructs.update, encodedTargetStateVector));
        }
        if (updates.size() > 1) {
            if (encoder instanceof UpdateEncoderV1) {
                List<byte[]> conv = new ArrayList<>();
                for (int i = 0; i < updates.size(); i++) {
                    conv.add(i == 0 ? updates.get(i) : UpdateMerging.convertUpdateFormatV2ToV1(updates.get(i)));
                }
                return UpdateMerging.mergeUpdates(conv);
            } else {
                return UpdateMerging.mergeUpdatesV2(updates);
            }
        }
        return updates.get(0);
    }

    public static byte[] encodeStateAsUpdateV2(Doc doc, byte[] encodedTargetStateVector) {
        return encodeStateAsUpdateV2(doc, encodedTargetStateVector, new UpdateEncoderV2());
    }

    public static byte[] encodeStateAsUpdateV2(Doc doc) {
        return encodeStateAsUpdateV2(doc, null, new UpdateEncoderV2());
    }

    public static byte[] encodeStateAsUpdate(Doc doc, byte[] encodedTargetStateVector) {
        return encodeStateAsUpdateV2(doc, encodedTargetStateVector, new UpdateEncoderV1());
    }

    public static byte[] encodeStateAsUpdate(Doc doc) {
        return encodeStateAsUpdate(doc, null);
    }

    public static Map<Long, Long> readStateVector(DSDecoder decoder) {
        Map<Long, Long> ss = new LinkedHashMap<>();
        long ssLength = Decoding.readVarUint(decoder.restDecoder());
        for (long i = 0; i < ssLength; i++) {
            long client = Decoding.readVarUint(decoder.restDecoder());
            long clock = Decoding.readVarUint(decoder.restDecoder());
            ss.put(client, clock);
        }
        return ss;
    }

    public static Map<Long, Long> decodeStateVector(byte[] decodedState) {
        return readStateVector(new DSDecoderV1(Decoding.createDecoder(decodedState)));
    }

    public static DSEncoder writeStateVector(DSEncoder encoder, Map<Long, Long> sv) {
        Encoding.writeVarUint(encoder.restEncoder(), sv.size());
        List<Map.Entry<Long, Long>> entries = new ArrayList<>(sv.entrySet());
        entries.sort((a, b) -> Long.compare(b.getKey(), a.getKey()));
        for (Map.Entry<Long, Long> e : entries) {
            Encoding.writeVarUint(encoder.restEncoder(), e.getKey());
            Encoding.writeVarUint(encoder.restEncoder(), e.getValue());
        }
        return encoder;
    }

    public static DSEncoder writeDocumentStateVector(DSEncoder encoder, Doc doc) {
        return writeStateVector(encoder, getStateVector(doc.store));
    }

    public static byte[] encodeStateVectorV2(Doc doc, DSEncoder encoder) {
        writeDocumentStateVector(encoder, doc);
        return encoder.toUint8Array();
    }

    public static byte[] encodeStateVectorV2(Map<Long, Long> sv, DSEncoder encoder) {
        writeStateVector(encoder, sv);
        return encoder.toUint8Array();
    }

    public static byte[] encodeStateVector(Doc doc) {
        return encodeStateVectorV2(doc, new DSEncoderV1());
    }

    public static byte[] encodeStateVector(Map<Long, Long> sv) {
        return encodeStateVectorV2(sv, new DSEncoderV1());
    }
}

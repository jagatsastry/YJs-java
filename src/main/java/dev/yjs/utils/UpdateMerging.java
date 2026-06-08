package dev.yjs.utils;

import dev.yjs.lib0.Binary;
import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;
import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.GC;
import dev.yjs.structs.Item;
import dev.yjs.structs.Skip;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Update merging / diffing / format-conversion utilities. Port of src/utils/updates.js.
 *
 * <p>All free functions from updates.js are ported here as {@code public static} methods. The lazy
 * struct reader/writer are nested classes ({@link LazyStructReader}, {@link LazyStructWriter}).
 *
 * <p><b>Note on the {@code Updates} class:</b> {@code dev.yjs.utils.Updates} does not exist yet in
 * this tree, so {@link #decodeStateVector(byte[])} and {@link #readStateVector(DSDecoder)} are
 * replicated here (faithfully mirroring src/utils/encoding.js: {@code decodeStateVector} reads a
 * state vector via a {@code DSDecoderV1}). If/when {@code Updates} provides these, these copies can
 * be removed and delegated.
 */
public final class UpdateMerging {
    private UpdateMerging() {}

    /* =====================================================================
     * Lazy struct reader
     * ===================================================================== */

    /**
     * Streaming generator over the structs encoded in a single update. Mirrors
     * {@code lazyStructReaderGenerator} in updates.js (and {@code readClientsStructRefs} in
     * encoding.js), but yields structs one at a time.
     */
    public static Iterator<AbstractStruct> lazyStructReaderGenerator(UpdateDecoder decoder) {
        return new Iterator<AbstractStruct>() {
            final Decoder restDecoder = decoder.restDecoder();
            final long numOfStateUpdates = Decoding.readVarUint(restDecoder);
            long stateUpdateIdx = 0;

            // per-client iteration state
            long numberOfStructs = 0;
            long structIdx = 0;
            long client = 0;
            long clock = 0;
            boolean clientStarted = false;

            AbstractStruct nextStruct = computeNext();

            private AbstractStruct computeNext() {
                while (true) {
                    if (clientStarted && structIdx < numberOfStructs) {
                        return readStruct();
                    }
                    // need to advance to the next client (state update)
                    if (stateUpdateIdx >= numOfStateUpdates) {
                        return null;
                    }
                    stateUpdateIdx++;
                    numberOfStructs = Decoding.readVarUint(restDecoder);
                    client = decoder.readClient();
                    clock = Decoding.readVarUint(restDecoder);
                    structIdx = 0;
                    clientStarted = true;
                    if (numberOfStructs == 0) {
                        // empty client block, keep looping to find the next non-empty one
                        clientStarted = false;
                    }
                }
            }

            private AbstractStruct readStruct() {
                structIdx++;
                int info = decoder.readInfo();
                if (info == 10) {
                    int len = (int) Decoding.readVarUint(restDecoder);
                    Skip struct = new Skip(ID.createID(client, clock), len);
                    clock += len;
                    return struct;
                } else if ((Binary.BITS5 & info) != 0) {
                    boolean cantCopyParentInfo = (info & (Binary.BIT7 | Binary.BIT8)) == 0;
                    // If parent = null and neither left nor right are defined, then we know that
                    // `parent` is child of `y` and we read the next string as parentYKey.
                    // The lazy reader stores the parent *key string* directly into Item.parent
                    // (it is not resolved to a type here).
                    Object parent = null;
                    if (cantCopyParentInfo) {
                        parent = decoder.readParentInfo() ? decoder.readString() : decoder.readLeftID();
                    }
                    Item struct = new Item(
                            ID.createID(client, clock),
                            null, // left
                            (info & Binary.BIT8) == Binary.BIT8 ? decoder.readLeftID() : null, // origin
                            null, // right
                            (info & Binary.BIT7) == Binary.BIT7 ? decoder.readRightID() : null, // right origin
                            parent, // parent (String parentYKey | ID | null)
                            cantCopyParentInfo && (info & Binary.BIT6) == Binary.BIT6 ? decoder.readString() : null, // parentSub
                            Item.readItemContent(decoder, info) // item content
                    );
                    clock += struct.length;
                    return struct;
                } else {
                    int len = decoder.readLen();
                    GC struct = new GC(ID.createID(client, clock), len);
                    clock += len;
                    return struct;
                }
            }

            @Override
            public boolean hasNext() {
                return nextStruct != null;
            }

            @Override
            public AbstractStruct next() {
                if (nextStruct == null) {
                    throw new NoSuchElementException();
                }
                AbstractStruct ret = nextStruct;
                nextStruct = computeNext();
                return ret;
            }
        };
    }

    /**
     * Reads structs lazily from an {@link UpdateDecoder}. Port of {@code LazyStructReader}.
     */
    public static final class LazyStructReader {
        public final Iterator<AbstractStruct> gen;
        /** @type {null | Item | Skip | GC} */
        public AbstractStruct curr;
        public boolean done;
        public final boolean filterSkips;

        public LazyStructReader(UpdateDecoder decoder, boolean filterSkips) {
            this.gen = lazyStructReaderGenerator(decoder);
            this.curr = null;
            this.done = false;
            this.filterSkips = filterSkips;
            this.next();
        }

        /** @return the next struct (or {@code null} when exhausted). */
        public AbstractStruct next() {
            // ignore "Skip" structs
            do {
                this.curr = this.gen.hasNext() ? this.gen.next() : null;
            } while (this.filterSkips && this.curr != null && this.curr.getClass() == Skip.class);
            return this.curr;
        }
    }

    /* =====================================================================
     * Logging / decoding helpers
     * ===================================================================== */

    /** Port of {@code logUpdate}. */
    public static void logUpdate(byte[] update) {
        logUpdateV2(update, UpdateDecoderV1::new);
    }

    /** Port of {@code logUpdateV2} (defaults to {@code UpdateDecoderV2}). */
    public static void logUpdateV2(byte[] update) {
        logUpdateV2(update, UpdateDecoderV2::new);
    }

    /** Port of {@code logUpdateV2} parameterized by decoder factory. */
    public static void logUpdateV2(byte[] update, Function<Decoder, ? extends UpdateDecoder> yDecoder) {
        List<AbstractStruct> structs = new ArrayList<>();
        UpdateDecoder updateDecoder = yDecoder.apply(Decoding.createDecoder(update));
        LazyStructReader lazyDecoder = new LazyStructReader(updateDecoder, false);
        for (AbstractStruct curr = lazyDecoder.curr; curr != null; curr = lazyDecoder.next()) {
            structs.add(curr);
        }
        System.out.println("Structs: " + structs);
        DeleteSet ds = DeleteSet.readDeleteSet(updateDecoder);
        System.out.println("DeleteSet: " + ds);
    }

    /** Holder for the result of {@link #decodeUpdate}/{@link #decodeUpdateV2}. */
    public static final class DecodedUpdate {
        public final List<AbstractStruct> structs;
        public final DeleteSet ds;

        public DecodedUpdate(List<AbstractStruct> structs, DeleteSet ds) {
            this.structs = structs;
            this.ds = ds;
        }
    }

    /** Port of {@code decodeUpdate}. */
    public static DecodedUpdate decodeUpdate(byte[] update) {
        return decodeUpdateV2(update, UpdateDecoderV1::new);
    }

    /** Port of {@code decodeUpdateV2} (defaults to {@code UpdateDecoderV2}). */
    public static DecodedUpdate decodeUpdateV2(byte[] update) {
        return decodeUpdateV2(update, UpdateDecoderV2::new);
    }

    /** Port of {@code decodeUpdateV2} parameterized by decoder factory. */
    public static DecodedUpdate decodeUpdateV2(byte[] update, Function<Decoder, ? extends UpdateDecoder> yDecoder) {
        List<AbstractStruct> structs = new ArrayList<>();
        UpdateDecoder updateDecoder = yDecoder.apply(Decoding.createDecoder(update));
        LazyStructReader lazyDecoder = new LazyStructReader(updateDecoder, false);
        for (AbstractStruct curr = lazyDecoder.curr; curr != null; curr = lazyDecoder.next()) {
            structs.add(curr);
        }
        return new DecodedUpdate(structs, DeleteSet.readDeleteSet(updateDecoder));
    }

    /* =====================================================================
     * Lazy struct writer
     * ===================================================================== */

    /**
     * Writes structs lazily into an {@link UpdateEncoder}. Port of {@code LazyStructWriter}.
     */
    public static final class LazyStructWriter {
        public long currClient = 0;
        public long startClock = 0;
        public int written = 0;
        public final UpdateEncoder encoder;
        /**
         * We want to write operations lazily, but also we need to know beforehand how many
         * operations we want to write for each client.
         *
         * <p>This kind of meta-information (#clients, #structs-per-client-written) is written to the
         * restEncoder. We fragment the restEncoder and store a slice of it per-client until we know
         * how many clients there are. When we flush (toUint8Array) we write the restEncoder using
         * the fragments and the meta-information.
         */
        public final List<ClientStructs> clientStructs = new ArrayList<>();

        public LazyStructWriter(UpdateEncoder encoder) {
            this.encoder = encoder;
        }
    }

    /** A fragment of the rest-encoder belonging to a single client. */
    public static final class ClientStructs {
        public final int written;
        public final byte[] restEncoder;

        public ClientStructs(int written, byte[] restEncoder) {
            this.written = written;
            this.restEncoder = restEncoder;
        }
    }

    /* =====================================================================
     * Merge
     * ===================================================================== */

    /** Port of {@code mergeUpdates} (V1). */
    public static byte[] mergeUpdates(List<byte[]> updates) {
        return mergeUpdatesV2(updates, UpdateDecoderV1::new, UpdateEncoderV1::new);
    }

    /** Port of {@code mergeUpdatesV2} (V2). */
    public static byte[] mergeUpdatesV2(List<byte[]> updates) {
        return mergeUpdatesV2(updates, UpdateDecoderV2::new, UpdateEncoderV2::new);
    }

    /**
     * Port of {@code mergeUpdatesV2} parameterized by decoder/encoder factories. Works similarly to
     * {@code readUpdateV2}.
     */
    public static byte[] mergeUpdatesV2(List<byte[]> updates,
                                        Function<Decoder, ? extends UpdateDecoder> yDecoder,
                                        Supplier<? extends UpdateEncoder> yEncoder) {
        if (updates.size() == 1) {
            return updates.get(0);
        }
        List<UpdateDecoder> updateDecoders = new ArrayList<>();
        for (byte[] update : updates) {
            updateDecoders.add(yDecoder.apply(Decoding.createDecoder(update)));
        }
        List<LazyStructReader> lazyStructDecoders = new ArrayList<>();
        for (UpdateDecoder decoder : updateDecoders) {
            lazyStructDecoders.add(new LazyStructReader(decoder, true));
        }

        // @todo we don't need offset because we always slice before
        CurrWrite currWrite = null;

        UpdateEncoder updateEncoder = yEncoder.get();
        // write structs lazily
        LazyStructWriter lazyStructEncoder = new LazyStructWriter(updateEncoder);

        // Note: We need to ensure that all lazyStructDecoders are fully consumed
        // Note: Should merge document updates whenever possible - even from different updates
        // Note: Should handle that some operations cannot be applied yet ()

        while (true) {
            // Write higher clients first => sort by clientID & clock and remove decoders without content
            lazyStructDecoders.removeIf(dec -> dec.curr == null);
            lazyStructDecoders.sort((dec1, dec2) -> {
                if (dec1.curr.id.client == dec2.curr.id.client) {
                    long clockDiff = dec1.curr.id.clock - dec2.curr.id.clock;
                    if (clockDiff == 0) {
                        // @todo remove references to skip since the structDecoders must filter Skips.
                        return dec1.curr.getClass() == dec2.curr.getClass()
                                ? 0
                                : (dec1.curr.getClass() == Skip.class ? 1 : -1); // we are filtering skips anyway.
                    } else {
                        return clockDiff > 0 ? 1 : -1;
                    }
                } else {
                    // dec2.curr.id.client - dec1.curr.id.client (descending)
                    return Long.compare(dec2.curr.id.client, dec1.curr.id.client);
                }
            });
            if (lazyStructDecoders.isEmpty()) {
                break;
            }
            LazyStructReader currDecoder = lazyStructDecoders.get(0);
            // write from currDecoder until the next operation is from another client or if
            // filler-struct then we need to reorder the decoders and find the next operation to write
            long firstClient = currDecoder.curr.id.client;

            if (currWrite != null) {
                AbstractStruct curr = currDecoder.curr;
                boolean iterated = false;

                // iterate until we find something that we haven't written already
                // remember: first the high client-ids are written
                while (curr != null
                        && curr.id.clock + curr.length <= currWrite.struct.id.clock + currWrite.struct.length
                        && curr.id.client >= currWrite.struct.id.client) {
                    curr = currDecoder.next();
                    iterated = true;
                }
                if (curr == null // current decoder is empty
                        || curr.id.client != firstClient // is there another decoder with updates from `firstClient`?
                        || (iterated && curr.id.clock > currWrite.struct.id.clock + currWrite.struct.length) // we may be missing updates
                ) {
                    continue;
                }

                if (firstClient != currWrite.struct.id.client) {
                    writeStructToLazyStructWriter(lazyStructEncoder, currWrite.struct, currWrite.offset);
                    currWrite = new CurrWrite(curr, 0);
                    currDecoder.next();
                } else {
                    if (currWrite.struct.id.clock + currWrite.struct.length < curr.id.clock) {
                        // @todo write currStruct & set currStruct = Skip(...)
                        if (currWrite.struct.getClass() == Skip.class) {
                            // extend existing skip
                            currWrite.struct.length = (int) (curr.id.clock + curr.length - currWrite.struct.id.clock);
                        } else {
                            writeStructToLazyStructWriter(lazyStructEncoder, currWrite.struct, currWrite.offset);
                            int diff = (int) (curr.id.clock - currWrite.struct.id.clock - currWrite.struct.length);
                            Skip struct = new Skip(ID.createID(firstClient, currWrite.struct.id.clock + currWrite.struct.length), diff);
                            currWrite = new CurrWrite(struct, 0);
                        }
                    } else { // currWrite.struct.id.clock + currWrite.struct.length >= curr.id.clock
                        int diff = (int) (currWrite.struct.id.clock + currWrite.struct.length - curr.id.clock);
                        if (diff > 0) {
                            if (currWrite.struct.getClass() == Skip.class) {
                                // prefer to slice Skip because the other struct might contain more information
                                currWrite.struct.length -= diff;
                            } else {
                                curr = sliceStruct(curr, diff);
                            }
                        }
                        if (!currWrite.struct.mergeWith(curr)) {
                            writeStructToLazyStructWriter(lazyStructEncoder, currWrite.struct, currWrite.offset);
                            currWrite = new CurrWrite(curr, 0);
                            currDecoder.next();
                        }
                    }
                }
            } else {
                currWrite = new CurrWrite(currDecoder.curr, 0);
                currDecoder.next();
            }
            for (AbstractStruct next = currDecoder.curr;
                 next != null && next.id.client == firstClient
                         && next.id.clock == currWrite.struct.id.clock + currWrite.struct.length
                         && next.getClass() != Skip.class;
                 next = currDecoder.next()) {
                writeStructToLazyStructWriter(lazyStructEncoder, currWrite.struct, currWrite.offset);
                currWrite = new CurrWrite(next, 0);
            }
        }
        if (currWrite != null) {
            writeStructToLazyStructWriter(lazyStructEncoder, currWrite.struct, currWrite.offset);
            currWrite = null;
        }
        finishLazyStructWriting(lazyStructEncoder);

        List<DeleteSet> dss = new ArrayList<>();
        for (UpdateDecoder decoder : updateDecoders) {
            dss.add(DeleteSet.readDeleteSet(decoder));
        }
        DeleteSet ds = DeleteSet.mergeDeleteSets(dss);
        DeleteSet.writeDeleteSet(updateEncoder, ds);
        return updateEncoder.toUint8Array();
    }

    /** Mutable {@code { struct, offset }} pair used by the merger. */
    private static final class CurrWrite {
        AbstractStruct struct;
        int offset;

        CurrWrite(AbstractStruct struct, int offset) {
            this.struct = struct;
            this.offset = offset;
        }
    }

    /* =====================================================================
     * State vector from update
     * ===================================================================== */

    /** Port of {@code encodeStateVectorFromUpdate} (V1). */
    public static byte[] encodeStateVectorFromUpdate(byte[] update) {
        return encodeStateVectorFromUpdateV2(update, DSEncoderV1::new, UpdateDecoderV1::new);
    }

    /** Port of {@code encodeStateVectorFromUpdateV2} (defaults: DSEncoderV2 / UpdateDecoderV2). */
    public static byte[] encodeStateVectorFromUpdateV2(byte[] update) {
        return encodeStateVectorFromUpdateV2(update, DSEncoderV2::new, UpdateDecoderV2::new);
    }

    /** Port of {@code encodeStateVectorFromUpdateV2} parameterized by factories. */
    public static byte[] encodeStateVectorFromUpdateV2(byte[] update,
                                                       Supplier<? extends DSEncoder> yEncoder,
                                                       Function<Decoder, ? extends UpdateDecoder> yDecoder) {
        DSEncoder encoder = yEncoder.get();
        LazyStructReader updateDecoder = new LazyStructReader(yDecoder.apply(Decoding.createDecoder(update)), false);
        AbstractStruct curr = updateDecoder.curr;
        if (curr != null) {
            int size = 0;
            long currClient = curr.id.client;
            boolean stopCounting = curr.id.clock != 0; // must start at 0
            long currClock = stopCounting ? 0 : curr.id.clock + curr.length;
            for (; curr != null; curr = updateDecoder.next()) {
                if (currClient != curr.id.client) {
                    if (currClock != 0) {
                        size++;
                        // We found a new client; write what we have to the encoder.
                        Encoding.writeVarUint(encoder.restEncoder(), currClient);
                        Encoding.writeVarUint(encoder.restEncoder(), currClock);
                    }
                    currClient = curr.id.client;
                    currClock = 0;
                    stopCounting = curr.id.clock != 0;
                }
                // we ignore skips
                if (curr.getClass() == Skip.class) {
                    stopCounting = true;
                }
                if (!stopCounting) {
                    currClock = curr.id.clock + curr.length;
                }
            }
            // write what we have
            if (currClock != 0) {
                size++;
                Encoding.writeVarUint(encoder.restEncoder(), currClient);
                Encoding.writeVarUint(encoder.restEncoder(), currClock);
            }
            // prepend the size of the state vector
            Encoder enc = Encoding.createEncoder();
            Encoding.writeVarUint(enc, size);
            Encoding.writeBinaryEncoder(enc, encoder.restEncoder());
            setRestEncoder(encoder, enc);
            return encoder.toUint8Array();
        } else {
            Encoding.writeVarUint(encoder.restEncoder(), 0);
            return encoder.toUint8Array();
        }
    }

    /* =====================================================================
     * parseUpdateMeta
     * ===================================================================== */

    /** Holder for {@code { from, to }} state-vector pairs. */
    public static final class UpdateMeta {
        public final Map<Long, Long> from;
        public final Map<Long, Long> to;

        public UpdateMeta(Map<Long, Long> from, Map<Long, Long> to) {
            this.from = from;
            this.to = to;
        }
    }

    /** Port of {@code parseUpdateMeta} (V1). */
    public static UpdateMeta parseUpdateMeta(byte[] update) {
        return parseUpdateMetaV2(update, UpdateDecoderV1::new);
    }

    /** Port of {@code parseUpdateMetaV2} (defaults to {@code UpdateDecoderV2}). */
    public static UpdateMeta parseUpdateMetaV2(byte[] update) {
        return parseUpdateMetaV2(update, UpdateDecoderV2::new);
    }

    /** Port of {@code parseUpdateMetaV2} parameterized by decoder factory. */
    public static UpdateMeta parseUpdateMetaV2(byte[] update, Function<Decoder, ? extends UpdateDecoder> yDecoder) {
        Map<Long, Long> from = new LinkedHashMap<>();
        Map<Long, Long> to = new LinkedHashMap<>();
        LazyStructReader updateDecoder = new LazyStructReader(yDecoder.apply(Decoding.createDecoder(update)), false);
        AbstractStruct curr = updateDecoder.curr;
        if (curr != null) {
            long currClient = curr.id.client;
            long currClock = curr.id.clock;
            // write the beginning to `from`
            from.put(currClient, currClock);
            for (; curr != null; curr = updateDecoder.next()) {
                if (currClient != curr.id.client) {
                    // We found a new client
                    // write the end to `to`
                    to.put(currClient, currClock);
                    // write the beginning to `from`
                    from.put(curr.id.client, curr.id.clock);
                    // update currClient
                    currClient = curr.id.client;
                }
                currClock = curr.id.clock + curr.length;
            }
            // write the end to `to`
            to.put(currClient, currClock);
        }
        return new UpdateMeta(from, to);
    }

    /* =====================================================================
     * sliceStruct
     * ===================================================================== */

    /**
     * This method is intended to slice any kind of struct and retrieve the right part. It does not
     * handle side-effects, so it should only be used by the lazy-encoder. Port of {@code sliceStruct}.
     */
    private static AbstractStruct sliceStruct(AbstractStruct left, int diff) {
        if (left.getClass() == GC.class) {
            long client = left.id.client;
            long clock = left.id.clock;
            return new GC(ID.createID(client, clock + diff), left.length - diff);
        } else if (left.getClass() == Skip.class) {
            long client = left.id.client;
            long clock = left.id.clock;
            return new Skip(ID.createID(client, clock + diff), left.length - diff);
        } else {
            Item leftItem = (Item) left;
            long client = leftItem.id.client;
            long clock = leftItem.id.clock;
            return new Item(
                    ID.createID(client, clock + diff),
                    null,
                    ID.createID(client, clock + diff - 1),
                    null,
                    leftItem.rightOrigin,
                    leftItem.parent,
                    leftItem.parentSub,
                    leftItem.content.splice(diff)
            );
        }
    }

    /* =====================================================================
     * diffUpdate
     * ===================================================================== */

    /** Port of {@code diffUpdate} (V1). */
    public static byte[] diffUpdate(byte[] update, byte[] sv) {
        return diffUpdateV2(update, sv, UpdateDecoderV1::new, UpdateEncoderV1::new);
    }

    /** Port of {@code diffUpdateV2} (defaults: UpdateDecoderV2 / UpdateEncoderV2). */
    public static byte[] diffUpdateV2(byte[] update, byte[] sv) {
        return diffUpdateV2(update, sv, UpdateDecoderV2::new, UpdateEncoderV2::new);
    }

    /** Port of {@code diffUpdateV2} parameterized by factories. */
    public static byte[] diffUpdateV2(byte[] update, byte[] sv,
                                      Function<Decoder, ? extends UpdateDecoder> yDecoder,
                                      Supplier<? extends UpdateEncoder> yEncoder) {
        Map<Long, Long> state = decodeStateVector(sv);
        UpdateEncoder encoder = yEncoder.get();
        LazyStructWriter lazyStructWriter = new LazyStructWriter(encoder);
        UpdateDecoder decoder = yDecoder.apply(Decoding.createDecoder(update));
        LazyStructReader reader = new LazyStructReader(decoder, false);
        while (reader.curr != null) {
            AbstractStruct curr = reader.curr;
            long currClient = curr.id.client;
            long svClock = state.getOrDefault(currClient, 0L);
            if (reader.curr.getClass() == Skip.class) {
                // the first written struct shouldn't be a skip
                reader.next();
                continue;
            }
            if (curr.id.clock + curr.length > svClock) {
                writeStructToLazyStructWriter(lazyStructWriter, curr, (int) Math.max(svClock - curr.id.clock, 0));
                reader.next();
                while (reader.curr != null && reader.curr.id.client == currClient) {
                    writeStructToLazyStructWriter(lazyStructWriter, reader.curr, 0);
                    reader.next();
                }
            } else {
                // read until something new comes up
                while (reader.curr != null && reader.curr.id.client == currClient
                        && reader.curr.id.clock + reader.curr.length <= svClock) {
                    reader.next();
                }
            }
        }
        finishLazyStructWriting(lazyStructWriter);
        // write ds
        DeleteSet ds = DeleteSet.readDeleteSet(decoder);
        DeleteSet.writeDeleteSet(encoder, ds);
        return encoder.toUint8Array();
    }

    /* =====================================================================
     * Lazy struct writer internals
     * ===================================================================== */

    /** Port of {@code flushLazyStructWriter}. */
    private static void flushLazyStructWriter(LazyStructWriter lazyWriter) {
        if (lazyWriter.written > 0) {
            lazyWriter.clientStructs.add(new ClientStructs(lazyWriter.written, Encoding.toUint8Array(lazyWriter.encoder.restEncoder())));
            setRestEncoder(lazyWriter.encoder, Encoding.createEncoder());
            lazyWriter.written = 0;
        }
    }

    /** Port of {@code writeStructToLazyStructWriter}. */
    public static void writeStructToLazyStructWriter(LazyStructWriter lazyWriter, AbstractStruct struct, int offset) {
        // flush curr if we start another client
        if (lazyWriter.written > 0 && lazyWriter.currClient != struct.id.client) {
            flushLazyStructWriter(lazyWriter);
        }
        if (lazyWriter.written == 0) {
            lazyWriter.currClient = struct.id.client;
            // write next client
            lazyWriter.encoder.writeClient(struct.id.client);
            // write startClock
            Encoding.writeVarUint(lazyWriter.encoder.restEncoder(), struct.id.clock + offset);
        }
        struct.write(lazyWriter.encoder, offset);
        lazyWriter.written++;
    }

    /**
     * Call this function when we collected all parts and want to put all the parts together. After
     * calling this method, you can continue using the UpdateEncoder. Port of
     * {@code finishLazyStructWriting}.
     */
    public static void finishLazyStructWriting(LazyStructWriter lazyWriter) {
        flushLazyStructWriter(lazyWriter);

        // this is a fresh encoder because we called flushCurr
        Encoder restEncoder = lazyWriter.encoder.restEncoder();

        // Now we put all the fragments together. This works similarly to `writeClientsStructs`.

        // write # states that were updated - i.e. the clients
        Encoding.writeVarUint(restEncoder, lazyWriter.clientStructs.size());

        for (int i = 0; i < lazyWriter.clientStructs.size(); i++) {
            ClientStructs partStructs = lazyWriter.clientStructs.get(i);
            // Works similarly to `writeStructs`
            // write # encoded structs
            Encoding.writeVarUint(restEncoder, partStructs.written);
            // write the rest of the fragment
            Encoding.writeUint8Array(restEncoder, partStructs.restEncoder);
        }
    }

    /* =====================================================================
     * convertUpdateFormat
     * ===================================================================== */

    /** A struct transformer for {@link #convertUpdateFormat}. */
    public interface BlockTransformer {
        AbstractStruct apply(AbstractStruct block);
    }

    /** Identity transformer (mirrors {@code lib0/function.id}). */
    public static final BlockTransformer ID_TRANSFORMER = block -> block;

    /** Port of {@code convertUpdateFormat}. */
    public static byte[] convertUpdateFormat(byte[] update,
                                             BlockTransformer blockTransformer,
                                             Function<Decoder, ? extends UpdateDecoder> yDecoder,
                                             Supplier<? extends UpdateEncoder> yEncoder) {
        UpdateDecoder updateDecoder = yDecoder.apply(Decoding.createDecoder(update));
        LazyStructReader lazyDecoder = new LazyStructReader(updateDecoder, false);
        UpdateEncoder updateEncoder = yEncoder.get();
        LazyStructWriter lazyWriter = new LazyStructWriter(updateEncoder);
        for (AbstractStruct curr = lazyDecoder.curr; curr != null; curr = lazyDecoder.next()) {
            writeStructToLazyStructWriter(lazyWriter, blockTransformer.apply(curr), 0);
        }
        finishLazyStructWriting(lazyWriter);
        DeleteSet ds = DeleteSet.readDeleteSet(updateDecoder);
        DeleteSet.writeDeleteSet(updateEncoder, ds);
        return updateEncoder.toUint8Array();
    }

    /** Port of {@code convertUpdateFormatV1ToV2}. */
    public static byte[] convertUpdateFormatV1ToV2(byte[] update) {
        return convertUpdateFormat(update, ID_TRANSFORMER, UpdateDecoderV1::new, UpdateEncoderV2::new);
    }

    /** Port of {@code convertUpdateFormatV2ToV1}. */
    public static byte[] convertUpdateFormatV2ToV1(byte[] update) {
        return convertUpdateFormat(update, ID_TRANSFORMER, UpdateDecoderV2::new, UpdateEncoderV1::new);
    }

    /* =====================================================================
     * State vector decoding (replicated from src/utils/encoding.js)
     * ===================================================================== */

    /**
     * Read decodedState and return State as Map. Mirrors {@code decodeStateVector} in encoding.js
     * (uses a {@code DSDecoderV1}).
     */
    public static Map<Long, Long> decodeStateVector(byte[] decodedState) {
        return readStateVector(new DSDecoderV1(Decoding.createDecoder(decodedState)));
    }

    /** Mirrors {@code readStateVector} in encoding.js. */
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

    /* =====================================================================
     * helpers
     * ===================================================================== */

    /**
     * Replace the {@code restEncoder} field of a DS/Update encoder. In JS this is a plain field
     * assignment ({@code encoder.restEncoder = enc}); in Java the field is {@code final}, so we copy
     * the new encoder's bytes into the existing instance instead. The net effect is identical:
     * subsequent {@code toUint8Array()} sees exactly the supplied bytes.
     */
    private static void setRestEncoder(DSEncoder encoder, Encoder newEncoder) {
        Encoder rest = encoder.restEncoder();
        rest.cpos = 0; // clear (Encoder exposes cbuf/cpos directly)
        Encoding.writeBinaryEncoder(rest, newEncoder);
    }
}

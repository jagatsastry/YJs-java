package dev.yjs.test;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Updates;

/**
 * Minimal port of y-protocols/sync (used only by the test harness).
 */
public final class SyncProtocol {
    private SyncProtocol() {}

    public static final int MESSAGE_YJS_SYNC_STEP1 = 0;
    public static final int MESSAGE_YJS_SYNC_STEP2 = 1;
    public static final int MESSAGE_YJS_UPDATE = 2;

    public static void writeSyncStep1(Encoder encoder, Doc doc) {
        Encoding.writeVarUint(encoder, MESSAGE_YJS_SYNC_STEP1);
        byte[] sv = Updates.encodeStateVector(doc);
        Encoding.writeVarUint8Array(encoder, sv);
    }

    public static void writeSyncStep2(Encoder encoder, Doc doc, byte[] encodedStateVector) {
        Encoding.writeVarUint(encoder, MESSAGE_YJS_SYNC_STEP2);
        Encoding.writeVarUint8Array(encoder, Updates.encodeStateAsUpdate(doc, encodedStateVector));
    }

    public static void readSyncStep1(Decoder decoder, Encoder encoder, Doc doc) {
        writeSyncStep2(encoder, doc, Decoding.readVarUint8Array(decoder));
    }

    public static void readSyncStep2(Decoder decoder, Doc doc, Object transactionOrigin) {
        try {
            Updates.applyUpdate(doc, Decoding.readVarUint8Array(decoder), transactionOrigin);
        } catch (RuntimeException error) {
            System.err.println("Caught error while handling a Yjs update: " + error);
            throw error;
        }
    }

    public static void writeUpdate(Encoder encoder, byte[] update) {
        Encoding.writeVarUint(encoder, MESSAGE_YJS_UPDATE);
        Encoding.writeVarUint8Array(encoder, update);
    }

    public static void readUpdate(Decoder decoder, Doc doc, Object transactionOrigin) {
        readSyncStep2(decoder, doc, transactionOrigin);
    }

    public static int readSyncMessage(Decoder decoder, Encoder encoder, Doc doc, Object transactionOrigin) {
        int messageType = (int) Decoding.readVarUint(decoder);
        switch (messageType) {
            case MESSAGE_YJS_SYNC_STEP1:
                readSyncStep1(decoder, encoder, doc);
                break;
            case MESSAGE_YJS_SYNC_STEP2:
                readSyncStep2(decoder, doc, transactionOrigin);
                break;
            case MESSAGE_YJS_UPDATE:
                readUpdate(decoder, doc, transactionOrigin);
                break;
            default:
                throw new RuntimeException("Unknown message type");
        }
        return messageType;
    }
}

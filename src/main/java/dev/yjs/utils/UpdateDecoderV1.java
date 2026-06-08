package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Json;

/**
 * Update decoder, version 1. Port of {@code UpdateDecoderV1} in src/utils/UpdateDecoder.js.
 */
public class UpdateDecoderV1 extends DSDecoderV1 implements UpdateDecoder {
    public UpdateDecoderV1(Decoder decoder) {
        super(decoder);
    }

    @Override
    public ID readLeftID() {
        return ID.createID(Decoding.readVarUint(restDecoder), Decoding.readVarUint(restDecoder));
    }

    @Override
    public ID readRightID() {
        return ID.createID(Decoding.readVarUint(restDecoder), Decoding.readVarUint(restDecoder));
    }

    /**
     * Read the next client id. Use this in favor of readID whenever possible to reduce the number
     * of objects created.
     */
    @Override
    public long readClient() {
        return Decoding.readVarUint(restDecoder);
    }

    @Override
    public int readInfo() {
        return Decoding.readUint8(restDecoder);
    }

    @Override
    public String readString() {
        return Decoding.readVarString(restDecoder);
    }

    @Override
    public boolean readParentInfo() {
        return Decoding.readVarUint(restDecoder) == 1;
    }

    @Override
    public int readTypeRef() {
        return (int) Decoding.readVarUint(restDecoder);
    }

    /**
     * Write len of a struct - well suited for Opt RLE encoder.
     */
    @Override
    public int readLen() {
        return (int) Decoding.readVarUint(restDecoder);
    }

    @Override
    public Object readAny() {
        return Decoding.readAny(restDecoder);
    }

    @Override
    public byte[] readBuf() {
        // decoding.readVarUint8Array already returns a fresh copy.
        return Decoding.readVarUint8Array(restDecoder);
    }

    /**
     * Legacy implementation uses JSON parse. We use any-decoding in v2.
     */
    @Override
    public Object readJSON() {
        return Json.parse(Decoding.readVarString(restDecoder));
    }

    @Override
    public String readKey() {
        return Decoding.readVarString(restDecoder);
    }
}

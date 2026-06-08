package dev.yjs.utils;

import dev.yjs.lib0.Encoding;
import dev.yjs.lib0.Json;

/**
 * Update encoder, version 1. Port of {@code UpdateEncoderV1} in src/utils/UpdateEncoder.js.
 */
public class UpdateEncoderV1 extends DSEncoderV1 implements UpdateEncoder {
    @Override
    public void writeLeftID(ID id) {
        Encoding.writeVarUint(restEncoder, id.client);
        Encoding.writeVarUint(restEncoder, id.clock);
    }

    @Override
    public void writeRightID(ID id) {
        Encoding.writeVarUint(restEncoder, id.client);
        Encoding.writeVarUint(restEncoder, id.clock);
    }

    /**
     * Use writeClient and writeClock instead of writeID if possible.
     */
    @Override
    public void writeClient(long client) {
        Encoding.writeVarUint(restEncoder, client);
    }

    @Override
    public void writeInfo(int info) {
        Encoding.writeUint8(restEncoder, info);
    }

    @Override
    public void writeString(String s) {
        Encoding.writeVarString(restEncoder, s);
    }

    @Override
    public void writeParentInfo(boolean isYKey) {
        Encoding.writeVarUint(restEncoder, isYKey ? 1 : 0);
    }

    @Override
    public void writeTypeRef(int info) {
        Encoding.writeVarUint(restEncoder, info);
    }

    /**
     * Write len of a struct - well suited for Opt RLE encoder.
     */
    @Override
    public void writeLen(int len) {
        Encoding.writeVarUint(restEncoder, len);
    }

    @Override
    public void writeAny(Object any) {
        Encoding.writeAny(restEncoder, any);
    }

    @Override
    public void writeBuf(byte[] buf) {
        Encoding.writeVarUint8Array(restEncoder, buf);
    }

    @Override
    public void writeJSON(Object embed) {
        Encoding.writeVarString(restEncoder, Json.stringify(embed));
    }

    @Override
    public void writeKey(String key) {
        Encoding.writeVarString(restEncoder, key);
    }
}

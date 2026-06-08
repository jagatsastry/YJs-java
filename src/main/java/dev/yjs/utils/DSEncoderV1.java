package dev.yjs.utils;

import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;

/**
 * Delete-set encoder, version 1. Port of {@code DSEncoderV1} in src/utils/UpdateEncoder.js.
 */
public class DSEncoderV1 implements DSEncoder {
    public final Encoder restEncoder = new Encoder();

    @Override
    public Encoder restEncoder() {
        return restEncoder;
    }

    @Override
    public byte[] toUint8Array() {
        return Encoding.toUint8Array(restEncoder);
    }

    @Override
    public void resetDsCurVal() {
        // nop
    }

    @Override
    public void writeDsClock(long clock) {
        Encoding.writeVarUint(restEncoder, clock);
    }

    @Override
    public void writeDsLen(long len) {
        Encoding.writeVarUint(restEncoder, len);
    }
}

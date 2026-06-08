package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;

/**
 * Delete-set decoder, version 1. Port of {@code DSDecoderV1} in src/utils/UpdateDecoder.js.
 */
public class DSDecoderV1 implements DSDecoder {
    public final Decoder restDecoder;

    public DSDecoderV1(Decoder decoder) {
        this.restDecoder = decoder;
    }

    @Override
    public Decoder restDecoder() {
        return restDecoder;
    }

    @Override
    public void resetDsCurVal() {
        // nop
    }

    @Override
    public long readDsClock() {
        return Decoding.readVarUint(restDecoder);
    }

    @Override
    public long readDsLen() {
        return Decoding.readVarUint(restDecoder);
    }
}

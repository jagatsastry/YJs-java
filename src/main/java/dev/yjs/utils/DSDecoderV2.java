package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;

/**
 * Delete-set decoder, version 2. Port of {@code DSDecoderV2} in src/utils/UpdateDecoder.js.
 */
public class DSDecoderV2 implements DSDecoder {
    private int dsCurrVal = 0;
    public final Decoder restDecoder;

    public DSDecoderV2(Decoder decoder) {
        this.restDecoder = decoder;
    }

    @Override
    public Decoder restDecoder() {
        return restDecoder;
    }

    @Override
    public void resetDsCurVal() {
        dsCurrVal = 0;
    }

    @Override
    public long readDsClock() {
        dsCurrVal += (int) Decoding.readVarUint(restDecoder);
        return dsCurrVal;
    }

    @Override
    public long readDsLen() {
        int diff = (int) Decoding.readVarUint(restDecoder) + 1;
        dsCurrVal += diff;
        return diff;
    }
}

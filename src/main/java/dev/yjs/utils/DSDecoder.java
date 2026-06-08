package dev.yjs.utils;

import dev.yjs.lib0.Decoder;

/** Delete-set decoder contract. Port of DSDecoderV1/V2 common surface. */
public interface DSDecoder {
    Decoder restDecoder();

    void resetDsCurVal();

    long readDsClock();

    long readDsLen();
}

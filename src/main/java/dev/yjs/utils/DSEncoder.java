package dev.yjs.utils;

import dev.yjs.lib0.Encoder;

/** Delete-set encoder contract. Port of DSEncoderV1/V2 common surface. */
public interface DSEncoder {
    Encoder restEncoder();

    byte[] toUint8Array();

    void resetDsCurVal();

    void writeDsClock(long clock);

    void writeDsLen(long len);
}

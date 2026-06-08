package dev.yjs.utils;

import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;

/**
 * Delete-set encoder, version 2. Port of {@code DSEncoderV2} in src/utils/UpdateEncoder.js.
 *
 * <p>V2 encodes delete-set clocks as diffs against a running cursor ({@code dsCurrVal}) and
 * encodes lengths as {@code len - 1} (lengths are always {@code > 0}).
 */
public class DSEncoderV2 implements DSEncoder {
    /** Encodes all the rest / non-optimized. */
    public final Encoder restEncoder = new Encoder();
    public int dsCurrVal = 0;

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
        dsCurrVal = 0;
    }

    @Override
    public void writeDsClock(long clock) {
        long diff = clock - dsCurrVal;
        dsCurrVal = (int) clock;
        Encoding.writeVarUint(restEncoder, diff);
    }

    @Override
    public void writeDsLen(long len) {
        if (len == 0) {
            throw new IllegalStateException("Unexpected case");
        }
        Encoding.writeVarUint(restEncoder, len - 1);
        dsCurrVal += (int) len;
    }
}

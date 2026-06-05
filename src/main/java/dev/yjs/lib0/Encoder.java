package dev.yjs.lib0;

import java.util.Arrays;

/**
 * A growable binary encoder. Port of lib0/encoding.js {@code Encoder}.
 *
 * <p>The JS implementation uses a list of buffers as an optimization; we use a single
 * growable buffer which produces byte-identical output. Low-level primitives live in
 * {@link Encoding} as static methods that mutate this object (mirroring the JS module API).
 */
public class Encoder {
    public byte[] cbuf;
    public int cpos;

    public Encoder() {
        this.cbuf = new byte[128];
        this.cpos = 0;
    }

    /** Current length of encoded data. */
    public int length() {
        return cpos;
    }

    /** Whether anything has been written. */
    public boolean hasContent() {
        return cpos > 0;
    }

    /** Materialize the encoded bytes into a fresh array. */
    public byte[] toBytes() {
        return Arrays.copyOf(cbuf, cpos);
    }

    void ensureCapacity(int required) {
        if (cbuf.length < required) {
            int newLen = cbuf.length;
            while (newLen < required) {
                newLen *= 2;
            }
            cbuf = Arrays.copyOf(cbuf, newLen);
        }
    }
}

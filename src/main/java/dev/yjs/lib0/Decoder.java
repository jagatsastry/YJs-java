package dev.yjs.lib0;

/**
 * A binary decoder over a byte array. Port of lib0/decoding.js {@code Decoder}.
 *
 * <p>Bytes are interpreted as <b>unsigned</b>; use {@code arr[pos] & 0xFF}.
 */
public class Decoder {
    public final byte[] arr;
    public int pos;

    public Decoder(byte[] uint8Array) {
        this.arr = uint8Array;
        this.pos = 0;
    }
}

package dev.yjs.lib0;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Efficient schema-less binary encoding. Port of lib0/encoding.js.
 *
 * <p>Numbers in var-encodings are little-endian 7-bit groups. Fixed-width floats and
 * bigints are big-endian (matching {@code DataView.setFloat32(...,false)}).
 */
public final class Encoding {
    private Encoding() {}

    public static Encoder createEncoder() {
        return new Encoder();
    }

    public static int length(Encoder encoder) {
        return encoder.cpos;
    }

    public static boolean hasContent(Encoder encoder) {
        return encoder.cpos > 0;
    }

    public static byte[] toUint8Array(Encoder encoder) {
        return encoder.toBytes();
    }

    /** Ensure {@code len} more bytes can be written without checking. */
    public static void verifyLen(Encoder encoder, int len) {
        encoder.ensureCapacity(encoder.cpos + len);
    }

    /** Write one byte. */
    public static void write(Encoder encoder, int num) {
        if (encoder.cpos == encoder.cbuf.length) {
            encoder.ensureCapacity(encoder.cpos + 1);
        }
        encoder.cbuf[encoder.cpos++] = (byte) num;
    }

    /** Write one byte at a specific (already written) position. */
    public static void set(Encoder encoder, int pos, int num) {
        encoder.cbuf[pos] = (byte) num;
    }

    public static void writeUint8(Encoder encoder, int num) {
        write(encoder, num);
    }

    public static void setUint8(Encoder encoder, int pos, int num) {
        set(encoder, pos, num);
    }

    public static void writeUint16(Encoder encoder, int num) {
        write(encoder, num & Binary.BITS8);
        write(encoder, (num >>> 8) & Binary.BITS8);
    }

    public static void setUint16(Encoder encoder, int pos, int num) {
        set(encoder, pos, num & Binary.BITS8);
        set(encoder, pos + 1, (num >>> 8) & Binary.BITS8);
    }

    public static void writeUint32(Encoder encoder, long num) {
        for (int i = 0; i < 4; i++) {
            write(encoder, (int) (num & Binary.BITS8));
            num >>>= 8;
        }
    }

    public static void writeUint32BigEndian(Encoder encoder, long num) {
        for (int i = 3; i >= 0; i--) {
            write(encoder, (int) ((num >>> (8 * i)) & Binary.BITS8));
        }
    }

    public static void setUint32(Encoder encoder, int pos, long num) {
        for (int i = 0; i < 4; i++) {
            set(encoder, pos + i, (int) (num & Binary.BITS8));
            num >>>= 8;
        }
    }

    /**
     * Write a variable length unsigned integer. Max encodable integer is 2^53.
     * {@code num} must be non-negative.
     */
    public static void writeVarUint(Encoder encoder, long num) {
        while (num > Binary.BITS7) {
            write(encoder, Binary.BIT8 | (Binary.BITS7 & (int) num));
            num >>>= 7; // == Math.floor(num / 128) for non-negative num
        }
        write(encoder, Binary.BITS7 & (int) num);
    }

    /**
     * Write a variable length signed integer. The 7th bit of the first byte signals
     * the sign. (Java {@code long} cannot represent {@code -0}; use
     * {@link #writeVarIntSigned} when the {@code -0} flag is meaningful.)
     */
    public static void writeVarInt(Encoder encoder, long num) {
        boolean isNegative = num < 0;
        if (isNegative) {
            num = -num;
        }
        //               continue?                   negative?            value
        write(encoder, (num > Binary.BITS6 ? Binary.BIT8 : 0) | (isNegative ? Binary.BIT7 : 0) | (Binary.BITS6 & (int) num));
        num >>>= 6; // == Math.floor(num / 64)
        while (num > 0) {
            write(encoder, (num > Binary.BITS7 ? Binary.BIT8 : 0) | (Binary.BITS7 & (int) num));
            num >>>= 7;
        }
    }

    /**
     * Write a variable length integer given an explicit magnitude and sign. This faithfully
     * reproduces the JS "-0" trick: the OptRle encoders call this with {@code negative=true}
     * and {@code magnitude} possibly 0 to flag that a repeat-count follows.
     *
     * @param magnitude non-negative magnitude
     * @param negative  whether the sign bit should be set
     */
    public static void writeVarIntSigned(Encoder encoder, long magnitude, boolean negative) {
        long num = magnitude;
        write(encoder, (num > Binary.BITS6 ? Binary.BIT8 : 0) | (negative ? Binary.BIT7 : 0) | (Binary.BITS6 & (int) num));
        num >>>= 6;
        while (num > 0) {
            write(encoder, (num > Binary.BITS7 ? Binary.BIT8 : 0) | (Binary.BITS7 & (int) num));
            num >>>= 7;
        }
    }

    /** Write a variable length UTF-8 string. */
    public static void writeVarString(Encoder encoder, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarUint(encoder, bytes.length);
        writeUint8Array(encoder, bytes);
    }

    /** Append the bytes of another encoder. */
    public static void writeBinaryEncoder(Encoder encoder, Encoder append) {
        writeUint8Array(encoder, toUint8Array(append));
    }

    /** Append a fixed-length byte array. */
    public static void writeUint8Array(Encoder encoder, byte[] uint8Array) {
        writeUint8Array(encoder, uint8Array, 0, uint8Array.length);
    }

    public static void writeUint8Array(Encoder encoder, byte[] uint8Array, int offset, int len) {
        encoder.ensureCapacity(encoder.cpos + len);
        System.arraycopy(uint8Array, offset, encoder.cbuf, encoder.cpos, len);
        encoder.cpos += len;
    }

    /** Append a byte array prefixed by its length. */
    public static void writeVarUint8Array(Encoder encoder, byte[] uint8Array) {
        writeVarUint(encoder, uint8Array.length);
        writeUint8Array(encoder, uint8Array);
    }

    public static void writeFloat32(Encoder encoder, double num) {
        int bits = Float.floatToRawIntBits((float) num);
        for (int i = 3; i >= 0; i--) {
            write(encoder, (bits >>> (8 * i)) & 0xFF);
        }
    }

    public static void writeFloat64(Encoder encoder, double num) {
        long bits = Double.doubleToRawLongBits(num);
        for (int i = 7; i >= 0; i--) {
            write(encoder, (int) ((bits >>> (8 * i)) & 0xFF));
        }
    }

    public static void writeBigInt64(Encoder encoder, long num) {
        for (int i = 7; i >= 0; i--) {
            write(encoder, (int) ((num >>> (8 * i)) & 0xFF));
        }
    }

    private static boolean isFloat32(double num) {
        return (double) (float) num == num;
    }

    /**
     * Encode arbitrary data with the lib0 "any" binary format. See lib0/encoding.js writeAny.
     */
    @SuppressWarnings("unchecked")
    public static void writeAny(Encoder encoder, Object data) {
        if (data instanceof String s) {
            write(encoder, 119);
            writeVarString(encoder, s);
        } else if (data instanceof Number n) {
            if (data instanceof BigInteger bi) {
                write(encoder, 122);
                writeBigInt64(encoder, bi.longValue());
                return;
            }
            double d = n.doubleValue();
            if (isIntegerValued(d) && Math.abs(d) <= Binary.BITS31) {
                write(encoder, 125);
                writeVarInt(encoder, (long) d);
            } else if (isFloat32(d)) {
                write(encoder, 124);
                writeFloat32(encoder, d);
            } else {
                write(encoder, 123);
                writeFloat64(encoder, d);
            }
        } else if (data instanceof Boolean b) {
            write(encoder, b ? 120 : 121);
        } else if (data == null) {
            write(encoder, 126);
        } else if (data instanceof byte[] bytes) {
            write(encoder, 116);
            writeVarUint8Array(encoder, bytes);
        } else if (data instanceof List<?> list) {
            write(encoder, 117);
            writeVarUint(encoder, list.size());
            for (Object o : list) {
                writeAny(encoder, o);
            }
        } else if (data instanceof Map<?, ?> map) {
            write(encoder, 118);
            writeVarUint(encoder, map.size());
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                writeVarString(encoder, e.getKey());
                writeAny(encoder, e.getValue());
            }
        } else {
            // Functions, symbols, and everything else that cannot be identified → undefined.
            write(encoder, 127);
        }
    }

    private static boolean isIntegerValued(double d) {
        return !Double.isInfinite(d) && !Double.isNaN(d) && Math.floor(d) == d;
    }

    /* ===================== stateful encoders ===================== */

    /** A cell writer used by {@link RleEncoder}. */
    public interface CellWriter<T> {
        void write(Encoder encoder, T value);
    }

    /** Basic run-length encoder. Port of lib0 RleEncoder. */
    public static class RleEncoder<T> extends Encoder {
        private final CellWriter<T> w;
        private T s = null;
        private int count = 0;

        public RleEncoder(CellWriter<T> writer) {
            super();
            this.w = writer;
        }

        public void write(T v) {
            if (s != null && s.equals(v)) {
                count++;
            } else {
                if (count > 0) {
                    writeVarUint(this, count - 1);
                }
                count = 1;
                w.write(this, v);
                s = v;
            }
        }
    }

    /** Diff encoder using variable length encoding. Port of lib0 IntDiffEncoder. */
    public static class IntDiffEncoder extends Encoder {
        private long s;

        public IntDiffEncoder(long start) {
            super();
            this.s = start;
        }

        public void write(long v) {
            writeVarInt(this, v - s);
            s = v;
        }
    }

    /** Combination of IntDiff and Rle. Port of lib0 RleIntDiffEncoder. */
    public static class RleIntDiffEncoder extends Encoder {
        private long s;
        private int count = 0;

        public RleIntDiffEncoder(long start) {
            super();
            this.s = start;
        }

        public void write(long v) {
            if (s == v && count > 0) {
                count++;
            } else {
                if (count > 0) {
                    writeVarUint(this, count - 1);
                }
                count = 1;
                writeVarInt(this, v - s);
                s = v;
            }
        }
    }

    private static void flushUintOptRleEncoder(UintOptRleEncoder encoder) {
        if (encoder.count > 0) {
            // count == 1 → write s positive; count > 1 → write s negative (incl. "-0") then count.
            writeVarIntSigned(encoder.encoder, encoder.s, encoder.count != 1);
            if (encoder.count > 1) {
                writeVarUint(encoder.encoder, encoder.count - 2);
            }
        }
    }

    /** Optimized Rle encoder for unsigned ints. Port of lib0 UintOptRleEncoder. */
    public static class UintOptRleEncoder {
        final Encoder encoder = new Encoder();
        long s = 0;
        int count = 0;

        public void write(long v) {
            if (s == v) {
                count++;
            } else {
                flushUintOptRleEncoder(this);
                count = 1;
                s = v;
            }
        }

        public byte[] toUint8Array() {
            flushUintOptRleEncoder(this);
            return Encoding.toUint8Array(encoder);
        }
    }

    /** Increasing Uint optimized Rle encoder. Port of lib0 IncUintOptRleEncoder. */
    public static class IncUintOptRleEncoder {
        final Encoder encoder = new Encoder();
        long s = 0;
        int count = 0;

        public void write(long v) {
            if (s + count == v) {
                count++;
            } else {
                flushIncUintOptRleEncoder(this);
                count = 1;
                s = v;
            }
        }

        public byte[] toUint8Array() {
            flushIncUintOptRleEncoder(this);
            return Encoding.toUint8Array(encoder);
        }
    }

    private static void flushIncUintOptRleEncoder(IncUintOptRleEncoder encoder) {
        if (encoder.count > 0) {
            writeVarIntSigned(encoder.encoder, encoder.s, encoder.count != 1);
            if (encoder.count > 1) {
                writeVarUint(encoder.encoder, encoder.count - 2);
            }
        }
    }

    private static void flushIntDiffOptRleEncoder(IntDiffOptRleEncoder encoder) {
        if (encoder.count > 0) {
            long encodedDiff = encoder.diff * 2 + (encoder.count == 1 ? 0 : 1);
            writeVarInt(encoder.encoder, encodedDiff);
            if (encoder.count > 1) {
                writeVarUint(encoder.encoder, encoder.count - 2);
            }
        }
    }

    /** Combination of IntDiff and UintOptRle. Port of lib0 IntDiffOptRleEncoder. */
    public static class IntDiffOptRleEncoder {
        final Encoder encoder = new Encoder();
        long s = 0;
        int count = 0;
        long diff = 0;

        public void write(long v) {
            if (diff == v - s) {
                s = v;
                count++;
            } else {
                flushIntDiffOptRleEncoder(this);
                count = 1;
                diff = v - s;
                s = v;
            }
        }

        public byte[] toUint8Array() {
            flushIntDiffOptRleEncoder(this);
            return Encoding.toUint8Array(encoder);
        }
    }

    /** Optimized string encoder. Port of lib0 StringEncoder. */
    public static class StringEncoder {
        private final StringBuilder sarr = new StringBuilder();
        private final UintOptRleEncoder lensE = new UintOptRleEncoder();

        public void write(String string) {
            sarr.append(string);
            lensE.write(string.length());
        }

        public byte[] toUint8Array() {
            Encoder encoder = new Encoder();
            writeVarString(encoder, sarr.toString());
            writeUint8Array(encoder, lensE.toUint8Array());
            return Encoding.toUint8Array(encoder);
        }
    }
}

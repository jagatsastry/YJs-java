package dev.yjs.lib0;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Efficient schema-less binary decoding. Port of lib0/decoding.js.
 */
public final class Decoding {
    private Decoding() {}

    static final long MAX_SAFE_INTEGER = 9007199254740991L; // 2^53 - 1

    public static Decoder createDecoder(byte[] uint8Array) {
        return new Decoder(uint8Array);
    }

    public static boolean hasContent(Decoder decoder) {
        return decoder.pos != decoder.arr.length;
    }

    public static Decoder clone(Decoder decoder) {
        return clone(decoder, decoder.pos);
    }

    public static Decoder clone(Decoder decoder, int newPos) {
        Decoder d = createDecoder(decoder.arr);
        d.pos = newPos;
        return d;
    }

    /** Read {@code len} bytes (returns a copy). */
    public static byte[] readUint8Array(Decoder decoder, int len) {
        byte[] view = Arrays.copyOfRange(decoder.arr, decoder.pos, decoder.pos + len);
        decoder.pos += len;
        return view;
    }

    public static byte[] readVarUint8Array(Decoder decoder) {
        return readUint8Array(decoder, (int) readVarUint(decoder));
    }

    public static byte[] readTailAsUint8Array(Decoder decoder) {
        return readUint8Array(decoder, decoder.arr.length - decoder.pos);
    }

    public static int skip8(Decoder decoder) {
        return decoder.pos++;
    }

    public static int readUint8(Decoder decoder) {
        return decoder.arr[decoder.pos++] & 0xFF;
    }

    public static int readUint16(Decoder decoder) {
        int uint = (decoder.arr[decoder.pos] & 0xFF) + ((decoder.arr[decoder.pos + 1] & 0xFF) << 8);
        decoder.pos += 2;
        return uint;
    }

    public static long readUint32(Decoder decoder) {
        long uint = ((decoder.arr[decoder.pos] & 0xFFL)
                + ((decoder.arr[decoder.pos + 1] & 0xFFL) << 8)
                + ((decoder.arr[decoder.pos + 2] & 0xFFL) << 16)
                + ((decoder.arr[decoder.pos + 3] & 0xFFL) << 24)) & Binary.BITS32;
        decoder.pos += 4;
        return uint;
    }

    public static int peekUint8(Decoder decoder) {
        return decoder.arr[decoder.pos] & 0xFF;
    }

    /** Read an unsigned integer with variable length. */
    public static long readVarUint(Decoder decoder) {
        long num = 0;
        long mult = 1;
        int len = decoder.arr.length;
        while (decoder.pos < len) {
            int r = decoder.arr[decoder.pos++] & 0xFF;
            num = num + (r & Binary.BITS7) * mult;
            mult *= 128;
            if (r < Binary.BIT8) {
                return num;
            }
            if (num > MAX_SAFE_INTEGER) {
                throw new RuntimeException("Integer out of Range");
            }
        }
        throw new RuntimeException("Unexpected end of array");
    }

    /** A signed var-int read together with its sign bit (for the OptRle decoders). */
    public static final class VarIntResult {
        public long value; // signed magnitude (note: Java long cannot represent -0)
        public boolean sign; // whether the sign bit was set (true also for "-0")
    }

    static void readVarIntInto(Decoder decoder, VarIntResult out) {
        int r = decoder.arr[decoder.pos++] & 0xFF;
        long num = r & Binary.BITS6;
        long mult = 64;
        boolean sign = (r & Binary.BIT7) > 0;
        if ((r & Binary.BIT8) == 0) {
            out.value = sign ? -num : num;
            out.sign = sign;
            return;
        }
        int len = decoder.arr.length;
        while (decoder.pos < len) {
            r = decoder.arr[decoder.pos++] & 0xFF;
            num = num + (r & Binary.BITS7) * mult;
            mult *= 128;
            if (r < Binary.BIT8) {
                out.value = sign ? -num : num;
                out.sign = sign;
                return;
            }
            if (num > MAX_SAFE_INTEGER) {
                throw new RuntimeException("Integer out of Range");
            }
        }
        throw new RuntimeException("Unexpected end of array");
    }

    /** Read a signed integer with variable length. */
    public static long readVarInt(Decoder decoder) {
        VarIntResult r = new VarIntResult();
        readVarIntInto(decoder, r);
        return r.value;
    }

    public static long peekVarUint(Decoder decoder) {
        int pos = decoder.pos;
        long s = readVarUint(decoder);
        decoder.pos = pos;
        return s;
    }

    public static long peekVarInt(Decoder decoder) {
        int pos = decoder.pos;
        long s = readVarInt(decoder);
        decoder.pos = pos;
        return s;
    }

    public static String readVarString(Decoder decoder) {
        byte[] bytes = readVarUint8Array(decoder);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String peekVarString(Decoder decoder) {
        int pos = decoder.pos;
        String s = readVarString(decoder);
        decoder.pos = pos;
        return s;
    }

    public static double readFloat32(Decoder decoder) {
        int bits = 0;
        for (int i = 0; i < 4; i++) {
            bits = (bits << 8) | (decoder.arr[decoder.pos++] & 0xFF);
        }
        return Float.intBitsToFloat(bits);
    }

    public static double readFloat64(Decoder decoder) {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | (decoder.arr[decoder.pos++] & 0xFFL);
        }
        return Double.longBitsToDouble(bits);
    }

    public static long readBigInt64(Decoder decoder) {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | (decoder.arr[decoder.pos++] & 0xFFL);
        }
        return bits;
    }

    /**
     * Read an "any"-encoded value. Mirrors lib0/decoding.js readAny / readAnyLookupTable.
     * Index into the table is {@code 127 - tag}.
     */
    public static Object readAny(Decoder decoder) {
        int tag = readUint8(decoder);
        switch (tag) {
            case 127: // undefined
                return null;
            case 126: // null
                return null;
            case 125: // integer
                return readVarInt(decoder);
            case 124: // float32
                return readFloat32(decoder);
            case 123: // float64
                return readFloat64(decoder);
            case 122: // bigint
                return BigInteger.valueOf(readBigInt64(decoder));
            case 121: // false
                return Boolean.FALSE;
            case 120: // true
                return Boolean.TRUE;
            case 119: // string
                return readVarString(decoder);
            case 118: { // object
                int len = (int) readVarUint(decoder);
                Map<String, Object> obj = new LinkedHashMap<>();
                for (int i = 0; i < len; i++) {
                    String key = readVarString(decoder);
                    obj.put(key, readAny(decoder));
                }
                return obj;
            }
            case 117: { // array
                int len = (int) readVarUint(decoder);
                List<Object> arr = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    arr.add(readAny(decoder));
                }
                return arr;
            }
            case 116: // Uint8Array
                return readVarUint8Array(decoder);
            default:
                throw new IllegalStateException("Unknown any-tag: " + tag);
        }
    }

    /* ===================== stateful decoders ===================== */

    public interface CellReader<T> {
        T read(Decoder decoder);
    }

    /** Port of lib0 RleDecoder. */
    public static class RleDecoder<T> extends Decoder {
        private final CellReader<T> reader;
        private T s = null;
        private int count = 0;

        public RleDecoder(byte[] uint8Array, CellReader<T> reader) {
            super(uint8Array);
            this.reader = reader;
        }

        public T read() {
            if (count == 0) {
                s = reader.read(this);
                if (hasContent(this)) {
                    count = (int) readVarUint(this) + 1;
                } else {
                    count = -1; // read forever
                }
            }
            count--;
            return s;
        }
    }

    /** Port of lib0 IntDiffDecoder. */
    public static class IntDiffDecoder extends Decoder {
        private long s;

        public IntDiffDecoder(byte[] uint8Array, long start) {
            super(uint8Array);
            this.s = start;
        }

        public long read() {
            s += readVarInt(this);
            return s;
        }
    }

    /** Port of lib0 RleIntDiffDecoder. */
    public static class RleIntDiffDecoder extends Decoder {
        private long s;
        private int count = 0;

        public RleIntDiffDecoder(byte[] uint8Array, long start) {
            super(uint8Array);
            this.s = start;
        }

        public long read() {
            if (count == 0) {
                s += readVarInt(this);
                if (hasContent(this)) {
                    count = (int) readVarUint(this) + 1;
                } else {
                    count = -1;
                }
            }
            count--;
            return s;
        }
    }

    /** Port of lib0 UintOptRleDecoder. */
    public static class UintOptRleDecoder extends Decoder {
        long s = 0;
        int count = 0;
        private final VarIntResult tmp = new VarIntResult();

        public UintOptRleDecoder(byte[] uint8Array) {
            super(uint8Array);
        }

        public long read() {
            if (count == 0) {
                readVarIntInto(this, tmp);
                boolean isNegative = tmp.sign;
                s = tmp.value;
                count = 1;
                if (isNegative) {
                    s = -s;
                    count = (int) readVarUint(this) + 2;
                }
            }
            count--;
            return s;
        }
    }

    /** Port of lib0 IncUintOptRleDecoder. */
    public static class IncUintOptRleDecoder extends Decoder {
        long s = 0;
        int count = 0;
        private final VarIntResult tmp = new VarIntResult();

        public IncUintOptRleDecoder(byte[] uint8Array) {
            super(uint8Array);
        }

        public long read() {
            if (count == 0) {
                readVarIntInto(this, tmp);
                boolean isNegative = tmp.sign;
                s = tmp.value;
                count = 1;
                if (isNegative) {
                    s = -s;
                    count = (int) readVarUint(this) + 2;
                }
            }
            count--;
            return s++;
        }
    }

    /** Port of lib0 IntDiffOptRleDecoder. */
    public static class IntDiffOptRleDecoder extends Decoder {
        long s = 0;
        int count = 0;
        long diff = 0;

        public IntDiffOptRleDecoder(byte[] uint8Array) {
            super(uint8Array);
        }

        public long read() {
            if (count == 0) {
                long readDiff = readVarInt(this);
                boolean hasCount = (readDiff & 1) != 0;
                diff = Math.floorDiv(readDiff, 2);
                count = 1;
                if (hasCount) {
                    count = (int) readVarUint(this) + 2;
                }
            }
            s += diff;
            count--;
            return s;
        }
    }

    /** Port of lib0 StringDecoder. */
    public static class StringDecoder {
        private final UintOptRleDecoder decoder;
        private final String str;
        private int spos = 0;

        public StringDecoder(byte[] uint8Array) {
            this.decoder = new UintOptRleDecoder(uint8Array);
            this.str = readVarString(this.decoder);
        }

        public String read() {
            int end = spos + (int) decoder.read();
            String res = str.substring(spos, end);
            spos = end;
            return res;
        }
    }
}

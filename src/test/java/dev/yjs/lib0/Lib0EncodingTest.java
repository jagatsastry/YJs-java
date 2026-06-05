package dev.yjs.lib0;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the lib0 encoding port produces <b>byte-identical</b> output to lib0 v0.2.99
 * (golden vectors generated from the reference JS) and that decode round-trips.
 */
public class Lib0EncodingTest {

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    @Test
    void varUintGolden() {
        long[] vals = {0, 1, 127, 128, 255, 256, 16383, 16384, 100000, 1073741824L, 2147483648L, 4294967296L, 9007199254740991L};
        String[] exp = {"00", "01", "7f", "8001", "ff01", "8002", "ff7f", "808001", "a08d06", "8080808004", "8080808008", "8080808010", "ffffffffffffff0f"};
        for (int i = 0; i < vals.length; i++) {
            Encoder e = new Encoder();
            Encoding.writeVarUint(e, vals[i]);
            assertEquals(exp[i], hex(e.toBytes()), "varUint " + vals[i]);
            Decoder d = new Decoder(e.toBytes());
            assertEquals(vals[i], Decoding.readVarUint(d));
        }
    }

    @Test
    void varIntGolden() {
        long[] vals = {0, 1, -1, 63, 64, -64, -65, 127, -127, 128, 8191, -8191, 1000000, -1000000, 1073741824L, -1073741824L};
        String[] exp = {"00", "01", "41", "3f", "8001", "c001", "c101", "bf01", "ff01", "8002", "bf7f", "ff7f", "80897a", "c0897a", "8080808008", "c080808008"};
        for (int i = 0; i < vals.length; i++) {
            Encoder e = new Encoder();
            Encoding.writeVarInt(e, vals[i]);
            assertEquals(exp[i], hex(e.toBytes()), "varInt " + vals[i]);
            Decoder d = new Decoder(e.toBytes());
            assertEquals(vals[i], Decoding.readVarInt(d));
        }
    }

    @Test
    void varStringGolden() {
        String[] vals = {"", "a", "hello world", "unicode: éè你好😀", "tab\tnewline\n"};
        String[] exp = {"00", "0161", "0b68656c6c6f20776f726c64", "17756e69636f64653a20c3a9c3a8e4bda0e5a5bdf09f9880", "0c746162096e65776c696e650a"};
        for (int i = 0; i < vals.length; i++) {
            Encoder e = new Encoder();
            Encoding.writeVarString(e, vals[i]);
            assertEquals(exp[i], hex(e.toBytes()), "varString " + vals[i]);
            Decoder d = new Decoder(e.toBytes());
            assertEquals(vals[i], Decoding.readVarString(d));
        }
    }

    @Test
    void floatGolden() {
        double[] vals = {0, 1.5, -1.5, 3.14159, 1e10, -1e-10};
        String[] exp32 = {"00000000", "3fc00000", "bfc00000", "40490fd0", "501502f9", "aedbe6ff"};
        String[] exp64 = {"0000000000000000", "3ff8000000000000", "bff8000000000000", "400921f9f01b866e", "4202a05f20000000", "bddb7cdfd9d7bdbb"};
        for (int i = 0; i < vals.length; i++) {
            Encoder e32 = new Encoder();
            Encoding.writeFloat32(e32, vals[i]);
            assertEquals(exp32[i], hex(e32.toBytes()), "float32 " + vals[i]);
            assertEquals((float) vals[i], (float) Decoding.readFloat32(new Decoder(e32.toBytes())));

            Encoder e64 = new Encoder();
            Encoding.writeFloat64(e64, vals[i]);
            assertEquals(exp64[i], hex(e64.toBytes()), "float64 " + vals[i]);
            assertEquals(vals[i], Decoding.readFloat64(new Decoder(e64.toBytes())));
        }
    }

    @Test
    void anyGolden() {
        assertAny(null, "7e");
        assertAny(Boolean.TRUE, "78");
        assertAny(Boolean.FALSE, "79");
        assertAny(0L, "7d00");
        assertAny(1L, "7d01");
        assertAny(-1L, "7d41");
        assertAny(42L, "7d2a");
        assertAny(3.14, "7b40091eb851eb851f");
        assertAny(1.5, "7c3fc00000");
        assertAny("hello", "770568656c6c6f");
        assertAny(1099511627776L, "7c53800000"); // 2^40 -> float32
        assertAny(List.of(1L, 2L, 3L), "75037d017d027d03");

        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", 1L);
        obj.put("b", "two");
        List<Object> c = new ArrayList<>();
        c.add(true);
        c.add(null);
        obj.put("c", c);
        assertAny(obj, "760301617d010162770374776f01637502787e");
    }

    void assertAny(Object v, String expHex) {
        Encoder e = new Encoder();
        Encoding.writeAny(e, v);
        assertEquals(expHex, hex(e.toBytes()), "writeAny " + v);
        Object back = Decoding.readAny(new Decoder(e.toBytes()));
        // round-trip equality (numbers may switch Long/Double but value-equal)
        assertDeepEquals(v, back);
    }

    static void assertDeepEquals(Object a, Object b) {
        if (a == null) {
            assertNull(b);
        } else if (a instanceof Number na && b instanceof Number nb) {
            assertEquals(na.doubleValue(), nb.doubleValue(), 1e-12);
        } else if (a instanceof List<?> la && b instanceof List<?> lb) {
            assertEquals(la.size(), lb.size());
            for (int i = 0; i < la.size(); i++) assertDeepEquals(la.get(i), lb.get(i));
        } else if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            assertEquals(ma.size(), mb.size());
            for (Map.Entry<?, ?> en : ma.entrySet()) assertDeepEquals(en.getValue(), ((Map<?, ?>) mb).get(en.getKey()));
        } else {
            assertEquals(a, b);
        }
    }

    @Test
    void uintOptRleGolden() {
        long[][] seqs = {{1, 2, 3, 3, 3}, {0, 0, 0}, {0}, {5, 5, 5, 5, 5, 5}, {1, 1, 2, 2, 3, 3}, {0, 1, 2, 3, 4}};
        String[] exp = {"01024301", "4001", "00", "4504", "410042004300", "0001020304"};
        for (int i = 0; i < seqs.length; i++) {
            Encoding.UintOptRleEncoder enc = new Encoding.UintOptRleEncoder();
            for (long v : seqs[i]) enc.write(v);
            byte[] bytes = enc.toUint8Array();
            assertEquals(exp[i], hex(bytes), "uintOptRle " + java.util.Arrays.toString(seqs[i]));
            Decoding.UintOptRleDecoder dec = new Decoding.UintOptRleDecoder(bytes);
            for (long v : seqs[i]) assertEquals(v, dec.read());
        }
    }

    @Test
    void incUintOptRleGolden() {
        long[][] seqs = {{7, 8, 9, 10}, {1, 3, 5}, {0, 1, 2, 3, 4, 5}, {5, 5, 5}};
        String[] exp = {"4702", "010305", "4004", "050505"};
        for (int i = 0; i < seqs.length; i++) {
            Encoding.IncUintOptRleEncoder enc = new Encoding.IncUintOptRleEncoder();
            for (long v : seqs[i]) enc.write(v);
            byte[] bytes = enc.toUint8Array();
            assertEquals(exp[i], hex(bytes), "incUintOptRle " + java.util.Arrays.toString(seqs[i]));
            Decoding.IncUintOptRleDecoder dec = new Decoding.IncUintOptRleDecoder(bytes);
            for (long v : seqs[i]) assertEquals(v, dec.read());
        }
    }

    @Test
    void intDiffOptRleGolden() {
        long[][] seqs = {{1, 2, 3, 2}, {1, 1, 1, 2, 3, 4}, {10, 20, 30}, {-5, -5, -5}, {0, 0, 0, 0}};
        String[] exp = {"030142", "0201000301", "1501", "4a0100", "0102"};
        for (int i = 0; i < seqs.length; i++) {
            Encoding.IntDiffOptRleEncoder enc = new Encoding.IntDiffOptRleEncoder();
            for (long v : seqs[i]) enc.write(v);
            byte[] bytes = enc.toUint8Array();
            assertEquals(exp[i], hex(bytes), "intDiffOptRle " + java.util.Arrays.toString(seqs[i]));
            Decoding.IntDiffOptRleDecoder dec = new Decoding.IntDiffOptRleDecoder(bytes);
            for (long v : seqs[i]) assertEquals(v, dec.read());
        }
    }

    @Test
    void rleGolden() {
        long[][] seqs = {{1, 1, 1, 7}, {1, 2, 3}, {5, 5, 5, 5, 5}};
        String[] exp = {"010207", "0100020003", "05"};
        for (int i = 0; i < seqs.length; i++) {
            Encoding.RleEncoder<Long> enc = new Encoding.RleEncoder<>((e, v) -> Encoding.writeVarUint(e, v));
            for (long v : seqs[i]) enc.write(v);
            byte[] bytes = enc.toBytes();
            assertEquals(exp[i], hex(bytes), "rle " + java.util.Arrays.toString(seqs[i]));
            Decoding.RleDecoder<Long> dec = new Decoding.RleDecoder<>(bytes, Decoding::readVarUint);
            for (long v : seqs[i]) assertEquals(v, dec.read());
        }
    }

    @Test
    void stringEncGolden() {
        String[][] seqs = {{"a", "bb", "ccc"}, {"hello", "world"}, {"", "x", ""}};
        String[] exp = {"06616262636363010203", "0a68656c6c6f776f726c644500", "0178000100"};
        for (int i = 0; i < seqs.length; i++) {
            Encoding.StringEncoder enc = new Encoding.StringEncoder();
            for (String v : seqs[i]) enc.write(v);
            byte[] bytes = enc.toUint8Array();
            assertEquals(exp[i], hex(bytes), "stringEnc " + java.util.Arrays.toString(seqs[i]));
            Decoding.StringDecoder dec = new Decoding.StringDecoder(bytes);
            for (String v : seqs[i]) assertEquals(v, dec.read());
        }
    }
}

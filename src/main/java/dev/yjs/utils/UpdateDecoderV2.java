package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;

import java.util.ArrayList;
import java.util.List;

/**
 * Update decoder, version 2. Port of {@code UpdateDecoderV2} in src/utils/UpdateDecoder.js.
 *
 * <p>The constructor reads the per-field sub-streams in the exact order the V2 encoder writes
 * them (see {@code UpdateEncoderV2.toUint8Array}); changing this order would break binary
 * compatibility.
 */
public class UpdateDecoderV2 extends DSDecoderV2 implements UpdateDecoder {
    /**
     * List of cached keys. If {@code keys[id]} does not exist, we read a new key from
     * {@code stringDecoder} and push it to {@code keys}.
     */
    public final List<String> keys = new ArrayList<>();

    public final Decoding.IntDiffOptRleDecoder keyClockDecoder;
    public final Decoding.UintOptRleDecoder clientDecoder;
    public final Decoding.IntDiffOptRleDecoder leftClockDecoder;
    public final Decoding.IntDiffOptRleDecoder rightClockDecoder;
    public final Decoding.RleDecoder<Integer> infoDecoder;
    public final Decoding.StringDecoder stringDecoder;
    public final Decoding.RleDecoder<Integer> parentInfoDecoder;
    public final Decoding.UintOptRleDecoder typeRefDecoder;
    public final Decoding.UintOptRleDecoder lenDecoder;

    public UpdateDecoderV2(Decoder decoder) {
        super(decoder);
        Decoding.readVarUint(decoder); // read feature flag - currently unused
        keyClockDecoder = new Decoding.IntDiffOptRleDecoder(Decoding.readVarUint8Array(decoder));
        clientDecoder = new Decoding.UintOptRleDecoder(Decoding.readVarUint8Array(decoder));
        leftClockDecoder = new Decoding.IntDiffOptRleDecoder(Decoding.readVarUint8Array(decoder));
        rightClockDecoder = new Decoding.IntDiffOptRleDecoder(Decoding.readVarUint8Array(decoder));
        infoDecoder = new Decoding.RleDecoder<>(Decoding.readVarUint8Array(decoder), Decoding::readUint8);
        stringDecoder = new Decoding.StringDecoder(Decoding.readVarUint8Array(decoder));
        parentInfoDecoder = new Decoding.RleDecoder<>(Decoding.readVarUint8Array(decoder), Decoding::readUint8);
        typeRefDecoder = new Decoding.UintOptRleDecoder(Decoding.readVarUint8Array(decoder));
        lenDecoder = new Decoding.UintOptRleDecoder(Decoding.readVarUint8Array(decoder));
    }

    @Override
    public ID readLeftID() {
        return new ID(clientDecoder.read(), leftClockDecoder.read());
    }

    @Override
    public ID readRightID() {
        return new ID(clientDecoder.read(), rightClockDecoder.read());
    }

    /**
     * Read the next client id. Use this in favor of readID whenever possible to reduce the number
     * of objects created.
     */
    @Override
    public long readClient() {
        return clientDecoder.read();
    }

    @Override
    public int readInfo() {
        return infoDecoder.read();
    }

    @Override
    public String readString() {
        return stringDecoder.read();
    }

    @Override
    public boolean readParentInfo() {
        return parentInfoDecoder.read() == 1;
    }

    @Override
    public int readTypeRef() {
        return (int) typeRefDecoder.read();
    }

    /**
     * Write len of a struct - well suited for Opt RLE encoder.
     */
    @Override
    public int readLen() {
        return (int) lenDecoder.read();
    }

    @Override
    public Object readAny() {
        return Decoding.readAny(restDecoder);
    }

    @Override
    public byte[] readBuf() {
        return Decoding.readVarUint8Array(restDecoder);
    }

    /**
     * This is mainly here for legacy purposes.
     *
     * <p>Initially we encoded objects using JSON. Now we use the much faster lib0/any-encoder.
     * This method mainly exists for legacy purposes for the v1 encoder.
     */
    @Override
    public Object readJSON() {
        return Decoding.readAny(restDecoder);
    }

    @Override
    public String readKey() {
        int keyClock = (int) keyClockDecoder.read();
        if (keyClock < keys.size()) {
            return keys.get(keyClock);
        } else {
            String key = stringDecoder.read();
            keys.add(key);
            return key;
        }
    }
}

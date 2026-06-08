package dev.yjs.utils;

import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;

import java.util.HashMap;
import java.util.Map;

/**
 * Update encoder, version 2. Port of {@code UpdateEncoderV2} in src/utils/UpdateEncoder.js.
 *
 * <p>V2 uses specialized run-length / diff encoders per field and concatenates them with length
 * prefixes in {@link #toUint8Array()}. The {@code restEncoder} (holding "any"-encoded values and
 * buffers) is appended <b>without</b> a length prefix, as the trailing segment.
 */
public class UpdateEncoderV2 extends DSEncoderV2 implements UpdateEncoder {
    /** Caches reused property keys. */
    public final Map<String, Integer> keyMap = new HashMap<>();
    /**
     * Refers to the next unique key-identifier to be used. See {@link #writeKey} for details.
     */
    public int keyClock = 0;

    public final Encoding.IntDiffOptRleEncoder keyClockEncoder = new Encoding.IntDiffOptRleEncoder();
    public final Encoding.UintOptRleEncoder clientEncoder = new Encoding.UintOptRleEncoder();
    public final Encoding.IntDiffOptRleEncoder leftClockEncoder = new Encoding.IntDiffOptRleEncoder();
    public final Encoding.IntDiffOptRleEncoder rightClockEncoder = new Encoding.IntDiffOptRleEncoder();
    public final Encoding.RleEncoder<Long> infoEncoder =
            new Encoding.RleEncoder<>((e, v) -> Encoding.writeUint8(e, (int) (long) v));
    public final Encoding.StringEncoder stringEncoder = new Encoding.StringEncoder();
    public final Encoding.RleEncoder<Long> parentInfoEncoder =
            new Encoding.RleEncoder<>((e, v) -> Encoding.writeUint8(e, (int) (long) v));
    public final Encoding.UintOptRleEncoder typeRefEncoder = new Encoding.UintOptRleEncoder();
    public final Encoding.UintOptRleEncoder lenEncoder = new Encoding.UintOptRleEncoder();

    @Override
    public byte[] toUint8Array() {
        Encoder encoder = Encoding.createEncoder();
        Encoding.writeVarUint(encoder, 0); // this is a feature flag that we might use in the future
        Encoding.writeVarUint8Array(encoder, keyClockEncoder.toUint8Array());
        Encoding.writeVarUint8Array(encoder, clientEncoder.toUint8Array());
        Encoding.writeVarUint8Array(encoder, leftClockEncoder.toUint8Array());
        Encoding.writeVarUint8Array(encoder, rightClockEncoder.toUint8Array());
        Encoding.writeVarUint8Array(encoder, Encoding.toUint8Array(infoEncoder));
        Encoding.writeVarUint8Array(encoder, stringEncoder.toUint8Array());
        Encoding.writeVarUint8Array(encoder, Encoding.toUint8Array(parentInfoEncoder));
        Encoding.writeVarUint8Array(encoder, typeRefEncoder.toUint8Array());
        Encoding.writeVarUint8Array(encoder, lenEncoder.toUint8Array());
        // @note The rest encoder is appended! (note the missing var)
        Encoding.writeUint8Array(encoder, Encoding.toUint8Array(restEncoder));
        return Encoding.toUint8Array(encoder);
    }

    @Override
    public void writeLeftID(ID id) {
        clientEncoder.write(id.client);
        leftClockEncoder.write(id.clock);
    }

    @Override
    public void writeRightID(ID id) {
        clientEncoder.write(id.client);
        rightClockEncoder.write(id.clock);
    }

    @Override
    public void writeClient(long client) {
        clientEncoder.write(client);
    }

    @Override
    public void writeInfo(int info) {
        infoEncoder.write((long) info);
    }

    @Override
    public void writeString(String s) {
        stringEncoder.write(s);
    }

    @Override
    public void writeParentInfo(boolean isYKey) {
        parentInfoEncoder.write(isYKey ? 1L : 0L);
    }

    @Override
    public void writeTypeRef(int info) {
        typeRefEncoder.write(info);
    }

    /**
     * Write len of a struct - well suited for Opt RLE encoder.
     */
    @Override
    public void writeLen(int len) {
        lenEncoder.write(len);
    }

    @Override
    public void writeAny(Object any) {
        Encoding.writeAny(restEncoder, any);
    }

    @Override
    public void writeBuf(byte[] buf) {
        Encoding.writeVarUint8Array(restEncoder, buf);
    }

    /**
     * This is mainly here for legacy purposes.
     *
     * <p>Initially we encoded objects using JSON. Now we use the much faster lib0/any-encoder.
     * This method mainly exists for legacy purposes for the v1 encoder.
     */
    @Override
    public void writeJSON(Object embed) {
        Encoding.writeAny(restEncoder, embed);
    }

    /**
     * Property keys are often reused. For example, in y-prosemirror the key {@code bold} might
     * occur very often. For a 3d application, the key {@code position} might occur very often.
     *
     * <p>We cache these keys in a Map and refer to them via a unique number.
     *
     * <p>Note: the key-caching short-circuit is intentionally disabled here, mirroring the JS
     * source where {@code this.keyMap.set(...)} is commented out. As a result every key is always
     * written out in full (keyClock increments and the key string is emitted).
     */
    @Override
    public void writeKey(String key) {
        Integer clock = keyMap.get(key);
        if (clock == null) {
            // @todo uncomment to introduce this feature finally
            //
            // Background. The ContentFormat object was always encoded using writeKey, but the
            // decoder used to use readString. Furthermore, the keyclock was never set. So
            // everything was working fine.
            //
            // However, this feature here is basically useless as it is not being used (it actually
            // only consumes extra memory).
            //
            // Older clients won't be able to read updates when we reintroduce this feature. So this
            // should probably be done using a flag.
            //
            // this.keyMap.set(key, this.keyClock)
            keyClockEncoder.write(keyClock++);
            stringEncoder.write(key);
        } else {
            keyClockEncoder.write(clock);
        }
    }
}

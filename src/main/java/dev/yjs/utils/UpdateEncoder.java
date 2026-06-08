package dev.yjs.utils;

/** Struct/update encoder contract. Implemented by UpdateEncoderV1 and UpdateEncoderV2. */
public interface UpdateEncoder extends DSEncoder {
    void writeLeftID(ID id);

    void writeRightID(ID id);

    void writeClient(long client);

    void writeInfo(int info);

    void writeString(String s);

    void writeParentInfo(boolean isYKey);

    void writeTypeRef(int info);

    void writeLen(int len);

    void writeAny(Object any);

    void writeBuf(byte[] buf);

    void writeJSON(Object embed);

    void writeKey(String key);
}

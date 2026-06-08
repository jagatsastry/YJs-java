package dev.yjs.utils;

/** Struct/update decoder contract. Implemented by UpdateDecoderV1 and UpdateDecoderV2. */
public interface UpdateDecoder extends DSDecoder {
    ID readLeftID();

    ID readRightID();

    long readClient();

    int readInfo();

    String readString();

    boolean readParentInfo();

    int readTypeRef();

    int readLen();

    Object readAny();

    byte[] readBuf();

    Object readJSON();

    String readKey();
}

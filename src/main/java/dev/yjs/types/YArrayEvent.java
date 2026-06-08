package dev.yjs.types;

import dev.yjs.utils.Transaction;

/**
 * Event that describes the changes on a YArray. Port of YArrayEvent in src/types/YArray.js.
 *
 * @param <T> the element type of the YArray
 */
public class YArrayEvent<T> extends YEvent<YArray<T>> {
    public YArrayEvent(YArray<T> yarray, Transaction transaction) {
        super(yarray, transaction);
    }
}

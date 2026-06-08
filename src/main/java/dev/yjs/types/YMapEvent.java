package dev.yjs.types;

import dev.yjs.utils.Transaction;

import java.util.Set;

/**
 * Event that describes the changes on a YMap. Port of YMapEvent in src/types/YMap.js.
 *
 * @param <T> the value type of the YMap
 */
public class YMapEvent<T> extends YEvent<YMap<T>> {
    /** The keys that changed. */
    public Set<String> keysChanged;

    public YMapEvent(YMap<T> ymap, Transaction transaction, Set<String> subs) {
        super(ymap, transaction);
        this.keysChanged = subs;
    }
}

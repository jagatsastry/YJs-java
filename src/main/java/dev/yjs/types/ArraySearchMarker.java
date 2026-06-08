package dev.yjs.types;

import dev.yjs.structs.Item;

/** A cached position marker that speeds up index lookups. Port from src/types/AbstractType.js. */
public final class ArraySearchMarker {
    static long globalSearchMarkerTimestamp = 0;

    public Item p;
    public int index;
    public long timestamp;

    public ArraySearchMarker(Item p, int index) {
        p.setMarker(true);
        this.p = p;
        this.index = index;
        this.timestamp = globalSearchMarkerTimestamp++;
    }
}

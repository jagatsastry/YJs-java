package dev.yjs.utils;

import dev.yjs.types.AbstractType;

/**
 * An absolute position into a shared type. Port of {@code AbsolutePosition} in
 * src/utils/RelativePosition.js.
 */
public final class AbsolutePosition {
    public final AbstractType<?> type;
    public final int index;
    public final int assoc;

    public AbsolutePosition(AbstractType<?> type, int index, int assoc) {
        this.type = type;
        this.index = index;
        this.assoc = assoc;
    }

    public AbsolutePosition(AbstractType<?> type, int index) {
        this(type, index, 0);
    }

    public static AbsolutePosition createAbsolutePosition(AbstractType<?> type, int index, int assoc) {
        return new AbsolutePosition(type, index, assoc);
    }

    public static AbsolutePosition createAbsolutePosition(AbstractType<?> type, int index) {
        return new AbsolutePosition(type, index, 0);
    }
}

package dev.yjs.utils;

import dev.yjs.structs.Item;
import dev.yjs.types.AbstractType;

/**
 * Port of src/utils/isParentOf.js.
 */
public final class IsParentOf {
    private IsParentOf() {}

    /**
     * Check if {@code parent} is a parent of {@code child}.
     *
     * @param parent the potential ancestor type
     * @param child  the item to test (may be null)
     * @return whether {@code parent} is a parent of {@code child}
     */
    public static boolean isParentOf(AbstractType<?> parent, Item child) {
        while (child != null) {
            if (child.parent == parent) {
                return true;
            }
            child = ((AbstractType<?>) child.parent)._item;
        }
        return false;
    }
}

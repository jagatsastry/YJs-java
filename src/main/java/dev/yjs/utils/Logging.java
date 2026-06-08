package dev.yjs.utils;

import dev.yjs.structs.Item;
import dev.yjs.types.AbstractType;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of src/utils/logging.js.
 */
public final class Logging {
    private Logging() {}

    /**
     * Convenient helper to log type information.
     *
     * <p>Do not use in productive systems as the output can be immense!
     *
     * @param type the type whose children to log
     */
    public static void logType(AbstractType<?> type) {
        List<Item> res = new ArrayList<>();
        Item n = type._start;
        while (n != null) {
            res.add(n);
            n = n.right;
        }
        System.out.println("Children: " + res);
        List<Object> content = new ArrayList<>();
        for (Item m : res) {
            if (!m.deleted()) {
                content.add(m.content);
            }
        }
        System.out.println("Children content: " + content);
    }
}

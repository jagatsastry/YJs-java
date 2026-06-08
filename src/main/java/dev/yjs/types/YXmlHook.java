package dev.yjs.types;

import dev.yjs.structs.Item;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.Iterator;
import java.util.Map;

/**
 * You can manage binding to a custom type with YXmlHook. Port of src/types/YXmlHook.js.
 *
 * <p>Extends {@link YMap}.
 */
public class YXmlHook extends YMap<Object> {
    /** Y type ref id (YXmlHookRefID). */
    public static final int Y_XML_HOOK_REF_ID = 5;

    public String hookName;

    /**
     * @param hookName nodeName of the DOM Node
     */
    public YXmlHook(String hookName) {
        super();
        this.hookName = hookName;
    }

    @Override
    public YXmlHook _copy() {
        return new YXmlHook(this.hookName);
    }

    @Override
    public YXmlHook clone() {
        YXmlHook el = new YXmlHook(this.hookName);
        // JS: this.forEach((value, key) => el.set(key, value))
        Iterator<Map.Entry<String, Item>> it = createMapIterator(this);
        while (it.hasNext()) {
            Map.Entry<String, Item> entry = it.next();
            Item item = entry.getValue();
            Object value = item.content.getContent().get(item.length - 1);
            el.set(entry.getKey(), value);
        }
        return el;
    }

    @Override
    public void _write(UpdateEncoder encoder) {
        encoder.writeTypeRef(Y_XML_HOOK_REF_ID);
        encoder.writeKey(this.hookName);
    }

    public static YXmlHook read(UpdateDecoder decoder) {
        return new YXmlHook(decoder.readKey());
    }
}

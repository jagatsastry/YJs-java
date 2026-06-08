package dev.yjs.types;

import dev.yjs.structs.ContentType;
import dev.yjs.structs.Item;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents text in a DOM Element. Port of src/types/YXmlText.js.
 */
public class YXmlText extends YText {
    /** Y type ref id (YXmlTextRefID). */
    public static final int Y_XML_TEXT_REF_ID = 6;

    public YXmlText() {
        super();
    }

    /** Inherited from YText: initialize with text content. */
    public YXmlText(String initialText) {
        super(initialText);
    }

    /**
     * The next sibling of this text node, or {@code null}.
     */
    public Object nextSibling() {
        Item n = this._item != null ? this._item.next() : null;
        return n != null ? ((ContentType) n.content).type : null;
    }

    /**
     * The previous sibling of this text node, or {@code null}.
     */
    public Object prevSibling() {
        Item n = this._item != null ? this._item.prev() : null;
        return n != null ? ((ContentType) n.content).type : null;
    }

    @Override
    public YXmlText _copy() {
        return new YXmlText();
    }

    @Override
    public YXmlText clone() {
        YXmlText text = new YXmlText();
        text.applyDelta(this.toDelta());
        return text;
    }

    /** A nested formatting node (e.g. {@code <bold>}) used by {@link #toString()}. */
    private static final class NestedNode {
        final String nodeName;
        final List<String[]> attrs; // each entry is { key, value }

        NestedNode(String nodeName, List<String[]> attrs) {
            this.nodeName = nodeName;
            this.attrs = attrs;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (Map<String, Object> delta : this.toDelta()) {
            List<NestedNode> nestedNodes = new ArrayList<>();
            Object attributes = delta.get("attributes");
            if (attributes instanceof Map) {
                Map<String, Object> attrMap = (Map<String, Object>) attributes;
                for (Map.Entry<String, Object> e : attrMap.entrySet()) {
                    String nodeName = e.getKey();
                    List<String[]> attrs = new ArrayList<>();
                    Object inner = e.getValue();
                    if (inner instanceof Map) {
                        Map<String, Object> innerMap = (Map<String, Object>) inner;
                        for (Map.Entry<String, Object> ie : innerMap.entrySet()) {
                            attrs.add(new String[]{ie.getKey(), String.valueOf(ie.getValue())});
                        }
                    }
                    // sort attributes to get a unique order
                    attrs.sort((a, b) -> a[0].compareTo(b[0]));
                    nestedNodes.add(new NestedNode(nodeName, attrs));
                }
            }
            // sort node order to get a unique order
            nestedNodes.sort((a, b) -> a.nodeName.compareTo(b.nodeName));
            // now convert to dom string
            StringBuilder str = new StringBuilder();
            for (NestedNode node : nestedNodes) {
                str.append("<").append(node.nodeName);
                for (String[] attr : node.attrs) {
                    str.append(" ").append(attr[0]).append("=\"").append(attr[1]).append("\"");
                }
                str.append(">");
            }
            str.append(delta.get("insert"));
            for (int i = nestedNodes.size() - 1; i >= 0; i--) {
                str.append("</").append(nestedNodes.get(i).nodeName).append(">");
            }
            result.append(str);
        }
        return result.toString();
    }

    @Override
    public Object toJSON() {
        return this.toString();
    }

    @Override
    public void _write(UpdateEncoder encoder) {
        encoder.writeTypeRef(Y_XML_TEXT_REF_ID);
    }

    public static YXmlText read(UpdateDecoder decoder) {
        return new YXmlText();
    }
}

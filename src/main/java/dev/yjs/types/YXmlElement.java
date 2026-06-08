package dev.yjs.types;

import dev.yjs.structs.ContentType;
import dev.yjs.structs.Item;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Snapshot;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An YXmlElement imitates the behavior of a DOM Element. It has attributes (key/value pairs) and
 * childElements. Port of src/types/YXmlElement.js.
 */
public class YXmlElement extends YXmlFragment {
    /** Y type ref id (YXmlElementRefID). */
    public static final int Y_XML_ELEMENT_REF_ID = 3;

    public String nodeName;

    /**
     * Attributes stored before the type is integrated into a document. {@code null} afterwards.
     */
    public Map<String, String> _prelimAttrs;

    public YXmlElement() {
        this("UNDEFINED");
    }

    public YXmlElement(String nodeName) {
        super();
        this.nodeName = nodeName;
        this._prelimAttrs = new LinkedHashMap<>();
    }

    /**
     * The next sibling of this element, or {@code null}.
     */
    public Object nextSibling() {
        Item n = this._item != null ? this._item.next() : null;
        return n != null ? ((ContentType) n.content).type : null;
    }

    /**
     * The previous sibling of this element, or {@code null}.
     */
    public Object prevSibling() {
        Item n = this._item != null ? this._item.prev() : null;
        return n != null ? ((ContentType) n.content).type : null;
    }

    @Override
    public void _integrate(Doc y, Item item) {
        super._integrate(y, item);
        this._prelimAttrs.forEach(this::setAttribute);
        this._prelimAttrs = null;
    }

    @Override
    public YXmlElement _copy() {
        return new YXmlElement(this.nodeName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public YXmlElement clone() {
        YXmlElement el = new YXmlElement(this.nodeName);
        Map<String, Object> attrs = this.getAttributes();
        attrs.forEach((key, value) -> el.setAttribute(key, (String) value));
        List<Object> cloned = new ArrayList<>();
        for (Object v : this.toArray()) {
            cloned.add(v instanceof AbstractType ? ((AbstractType<?>) v).clone() : v);
        }
        el.insert(0, cloned);
        return el;
    }

    /**
     * Returns the XML serialization of this YXmlElement. The attributes are ordered by attribute
     * name, so you can easily use this method to compare YXmlElements.
     *
     * @return the string representation of this type
     */
    @Override
    public String toString() {
        Map<String, Object> attrs = this.getAttributes();
        List<String> stringBuilder = new ArrayList<>();
        List<String> keys = new ArrayList<>(attrs.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            stringBuilder.add(key + "=\"" + attrs.get(key) + "\"");
        }
        String nodeName = this.nodeName.toLowerCase();
        String attrsString = !stringBuilder.isEmpty() ? " " + String.join(" ", stringBuilder) : "";
        return "<" + nodeName + attrsString + ">" + super.toString() + "</" + nodeName + ">";
    }

    /**
     * Removes an attribute from this YXmlElement.
     *
     * @param attributeName the attribute name that is to be removed
     */
    public void removeAttribute(String attributeName) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction ->
                    typeMapDelete(transaction, this, attributeName), null, true);
        } else {
            this._prelimAttrs.remove(attributeName);
        }
    }

    /**
     * Sets or updates an attribute.
     *
     * @param attributeName  the attribute name that is to be set
     * @param attributeValue the attribute value that is to be set
     */
    public void setAttribute(String attributeName, String attributeValue) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction ->
                    typeMapSet(transaction, this, attributeName, attributeValue), null, true);
        } else {
            this._prelimAttrs.put(attributeName, attributeValue);
        }
    }

    /**
     * Returns an attribute value that belongs to the attribute name.
     *
     * @param attributeName the attribute name that identifies the queried value
     * @return the queried attribute value, or {@code null}
     */
    public Object getAttribute(String attributeName) {
        return typeMapGet(this, attributeName);
    }

    /**
     * Returns whether an attribute exists.
     *
     * @param attributeName the attribute name to check for existence
     * @return whether the attribute exists
     */
    public boolean hasAttribute(String attributeName) {
        return typeMapHas(this, attributeName);
    }

    /**
     * Returns all attribute name/value pairs in a Map.
     *
     * @return a Map that describes the attributes
     */
    public Map<String, Object> getAttributes() {
        return typeMapGetAll(this);
    }

    /**
     * Returns all attribute name/value pairs as they were at the given snapshot.
     *
     * @param snapshot the snapshot to read at
     * @return a Map that describes the attributes
     */
    public Map<String, Object> getAttributes(Snapshot snapshot) {
        return snapshot != null ? typeMapGetAllSnapshot(this, snapshot) : typeMapGetAll(this);
    }

    @Override
    public void _write(UpdateEncoder encoder) {
        encoder.writeTypeRef(Y_XML_ELEMENT_REF_ID);
        encoder.writeKey(this.nodeName);
    }

    public static YXmlElement read(UpdateDecoder decoder) {
        return new YXmlElement(decoder.readKey());
    }
}

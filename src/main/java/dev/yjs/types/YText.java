package dev.yjs.types;

import dev.yjs.structs.AbstractContent;
import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.ContentEmbed;
import dev.yjs.structs.ContentFormat;
import dev.yjs.structs.ContentString;
import dev.yjs.structs.ContentType;
import dev.yjs.structs.GC;
import dev.yjs.structs.Item;
import dev.yjs.utils.DeleteSet;
import dev.yjs.utils.Doc;
import dev.yjs.utils.ID;
import dev.yjs.utils.Snapshot;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.yjs.utils.ID.createID;
import static dev.yjs.utils.StructStore.getItemCleanStart;
import static dev.yjs.utils.StructStore.getState;

/**
 * Type that represents text with formatting information.
 *
 * <p>This type replaces y-richtext as this implementation is able to handle block formats (format
 * information on a paragraph), embeds (complex elements like pictures and videos), and text formats
 * (<b>bold</b>, <i>italic</i>).
 *
 * <p>Port of src/types/YText.js.
 *
 * @extends AbstractType<YTextEvent>
 */
public class YText extends AbstractType<YTextEvent> {
    /** Y type ref id (YTextRefID). */
    public static final int Y_TEXT_REF_ID = 2;

    /**
     * Array of pending operations on this type. {@code null} after integration.
     * JS uses {@code Array<function():void>}.
     */
    public List<Runnable> _pending;

    /**
     * Whether this YText contains formatting attributes. Updated when a formatting item is
     * integrated (see {@link ContentFormat#integrate}).
     */

    public YText() {
        super();
        this._pending = new ArrayList<>();
        this._searchMarker = new ArrayList<>();
    }

    public YText(String initialText) {
        super();
        this._pending = new ArrayList<>();
        if (initialText != null) {
            this._pending.add(() -> this.insert(0, initialText));
        }
        this._searchMarker = new ArrayList<>();
    }

    /**
     * Number of characters of this text type.
     *
     * @return the length
     */
    public int length() {
        if (this.doc == null) {
            warnPrematureAccess();
        }
        return this._length;
    }

    @Override
    public void _integrate(Doc y, Item item) {
        super._integrate(y, item);
        try {
            for (Runnable f : this._pending) {
                f.run();
            }
        } catch (Exception e) {
            System.err.println(e);
        }
        this._pending = null;
    }

    @Override
    public YText _copy() {
        return new YText();
    }

    /**
     * Makes a copy of this data type that can be included somewhere else.
     *
     * <p>Note that the content is only readable <i>after</i> it has been included somewhere in the
     * Ydoc.
     *
     * @return a clone
     */
    @Override
    public YText clone() {
        YText text = new YText();
        text.applyDelta(this.toDelta());
        return text;
    }

    /**
     * Creates YTextEvent and calls observers.
     *
     * @param transaction the transaction
     * @param parentSubs  keys changed on this type; {@code null} entry if list was modified
     */
    @Override
    public void _callObserver(Transaction transaction, Set<String> parentSubs) {
        super._callObserver(transaction, parentSubs);
        YTextEvent event = new YTextEvent(this, transaction, parentSubs);
        callTypeObservers(this, transaction, event);
        // If a remote change happened, we try to cleanup potential formatting duplicates.
        if (!transaction.local && this._hasFormatting) {
            transaction._needFormattingCleanup = true;
        }
    }

    /**
     * Returns the unformatted string representation of this YText type.
     *
     * @return the string
     */
    @Override
    public String toString() {
        if (this.doc == null) {
            warnPrematureAccess();
        }
        StringBuilder str = new StringBuilder();
        Item n = this._start;
        while (n != null) {
            if (!n.deleted() && n.countable() && n.content instanceof ContentString) {
                str.append(((ContentString) n.content).str);
            }
            n = n.right;
        }
        return str.toString();
    }

    /**
     * Returns the unformatted string representation of this YText type.
     *
     * @return the string
     */
    @Override
    public Object toJSON() {
        return this.toString();
    }

    /**
     * Apply a Delta on this shared YText type with default options (sanitize = true).
     *
     * @param delta the changes to apply
     */
    public void applyDelta(List<Map<String, Object>> delta) {
        this.applyDelta(delta, true);
    }

    /**
     * Apply a Delta on this shared YText type.
     *
     * @param delta    the changes to apply on this element
     * @param sanitize sanitize input delta; removes ending newlines if set to {@code true}
     */
    public void applyDelta(List<Map<String, Object>> delta, boolean sanitize) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                ItemTextListPosition currPos = new ItemTextListPosition(null, this._start, 0, new LinkedHashMap<>());
                for (int i = 0; i < delta.size(); i++) {
                    Map<String, Object> op = delta.get(i);
                    if (op.containsKey("insert") && op.get("insert") != null) {
                        // Quill assumes that the content starts with an empty paragraph.
                        // Yjs/Y.Text assumes that it starts empty. We always hide that there is a
                        // newline at the end of the content. If we omit this step, clients will see
                        // a different number of paragraphs, but nothing bad will happen.
                        Object insert = op.get("insert");
                        Object ins;
                        if (!sanitize && insert instanceof String && i == delta.size() - 1
                                && currPos.right == null && ((String) insert).endsWith("\n")) {
                            String s = (String) insert;
                            ins = s.substring(0, s.length() - 1);
                        } else {
                            ins = insert;
                        }
                        if (!(ins instanceof String) || ((String) ins).length() > 0) {
                            insertText(transaction, this, currPos, ins, attributesOrEmpty(op.get("attributes")));
                        }
                    } else if (op.containsKey("retain") && op.get("retain") != null) {
                        formatText(transaction, this, currPos, ((Number) op.get("retain")).intValue(),
                                attributesOrEmpty(op.get("attributes")));
                    } else if (op.containsKey("delete") && op.get("delete") != null) {
                        deleteText(transaction, currPos, ((Number) op.get("delete")).intValue());
                    }
                }
            }, null, true);
        } else {
            this._pending.add(() -> this.applyDelta(delta));
        }
    }

    /**
     * Returns the Delta representation of this YText type.
     *
     * @return the Delta representation of this type
     */
    public List<Map<String, Object>> toDelta() {
        return this.toDelta(null, null, null);
    }

    /**
     * Compute the value of a YText change for a snapshot diff.
     */
    public interface ComputeYChange {
        Object apply(String type, ID id);
    }

    /**
     * Returns the Delta representation of this YText type.
     *
     * @param snapshot       optional snapshot
     * @param prevSnapshot   optional previous snapshot
     * @param computeYChange optional change callback ({@code (type, ID) -> value})
     * @return the Delta representation of this type
     */
    public List<Map<String, Object>> toDelta(Snapshot snapshot, Snapshot prevSnapshot, ComputeYChange computeYChange) {
        if (this.doc == null) {
            warnPrematureAccess();
        }
        List<Map<String, Object>> ops = new ArrayList<>();
        Map<String, Object> currentAttributes = new LinkedHashMap<>();
        Doc doc = this.doc;
        // Use a 1-element array so packStr can be defined as a Runnable that mutates `str`.
        StringBuilder[] strHolder = {new StringBuilder()};
        Item[] nHolder = {this._start};

        Runnable packStr = () -> {
            if (strHolder[0].length() > 0) {
                // pack str with attributes to ops
                Map<String, Object> attributes = new LinkedHashMap<>();
                boolean[] addAttributes = {false};
                currentAttributes.forEach((key, value) -> {
                    addAttributes[0] = true;
                    attributes.put(key, value);
                });
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("insert", strHolder[0].toString());
                if (addAttributes[0]) {
                    op.put("attributes", attributes);
                }
                ops.add(op);
                strHolder[0] = new StringBuilder();
            }
        };

        Runnable computeDelta = () -> {
            Item n = nHolder[0];
            while (n != null) {
                if (Snapshot.isVisible(n, snapshot) || (prevSnapshot != null && Snapshot.isVisible(n, prevSnapshot))) {
                    if (n.content instanceof ContentString) {
                        Object cur = currentAttributes.get("ychange");
                        if (snapshot != null && !Snapshot.isVisible(n, snapshot)) {
                            if (cur == null || !sameYChange(cur, n.id.client, "removed")) {
                                packStr.run();
                                currentAttributes.put("ychange",
                                        computeYChange != null ? computeYChange.apply("removed", n.id) : ychange("removed"));
                            }
                        } else if (prevSnapshot != null && !Snapshot.isVisible(n, prevSnapshot)) {
                            if (cur == null || !sameYChange(cur, n.id.client, "added")) {
                                packStr.run();
                                currentAttributes.put("ychange",
                                        computeYChange != null ? computeYChange.apply("added", n.id) : ychange("added"));
                            }
                        } else if (cur != null) {
                            packStr.run();
                            currentAttributes.remove("ychange");
                        }
                        strHolder[0].append(((ContentString) n.content).str);
                    } else if (n.content instanceof ContentType || n.content instanceof ContentEmbed) {
                        packStr.run();
                        Map<String, Object> op = new LinkedHashMap<>();
                        op.put("insert", n.content.getContent().get(0));
                        if (!currentAttributes.isEmpty()) {
                            Map<String, Object> attrs = new LinkedHashMap<>();
                            op.put("attributes", attrs);
                            currentAttributes.forEach(attrs::put);
                        }
                        ops.add(op);
                    } else if (n.content instanceof ContentFormat) {
                        if (Snapshot.isVisible(n, snapshot)) {
                            packStr.run();
                            updateCurrentAttributes(currentAttributes, (ContentFormat) n.content);
                        }
                    }
                }
                n = n.right;
            }
            packStr.run();
        };

        if (snapshot != null || prevSnapshot != null) {
            // snapshots are merged again after the transaction, so we need to keep the transaction
            // alive until we are done
            Transaction.transact(doc, transaction -> {
                if (snapshot != null) {
                    Snapshot.splitSnapshotAffectedStructs(transaction, snapshot);
                }
                if (prevSnapshot != null) {
                    Snapshot.splitSnapshotAffectedStructs(transaction, prevSnapshot);
                }
                computeDelta.run();
            }, "cleanup", true);
        } else {
            computeDelta.run();
        }
        return ops;
    }

    /**
     * Insert text at a given index.
     *
     * @param index the index at which to start inserting
     * @param text  the text to insert at the specified position
     */
    public void insert(int index, String text) {
        this.insert(index, text, null);
    }

    /**
     * Insert text at a given index.
     *
     * @param index      the index at which to start inserting
     * @param text       the text to insert at the specified position
     * @param attributes optional formatting information to apply on the inserted text
     */
    public void insert(int index, String text, Map<String, Object> attributes) {
        if (text.length() <= 0) {
            return;
        }
        Doc y = this.doc;
        if (y != null) {
            final Map<String, Object>[] attrs = new Map[]{attributes};
            Transaction.transact(y, transaction -> {
                ItemTextListPosition pos = findPosition(transaction, this, index, attrs[0] == null);
                if (attrs[0] == null) {
                    attrs[0] = new LinkedHashMap<>();
                    pos.currentAttributes.forEach(attrs[0]::put);
                }
                insertText(transaction, this, pos, text, attrs[0]);
            }, null, true);
        } else {
            this._pending.add(() -> this.insert(index, text, attributes));
        }
    }

    /**
     * Inserts an embed at an index.
     *
     * @param index the index to insert the embed at
     * @param embed the Object (or {@link AbstractType}) that represents the embed
     */
    public void insertEmbed(int index, Object embed) {
        this.insertEmbed(index, embed, null);
    }

    /**
     * Inserts an embed at an index.
     *
     * @param index      the index to insert the embed at
     * @param embed      the Object (or {@link AbstractType}) that represents the embed
     * @param attributes attribute information to apply on the embed
     */
    public void insertEmbed(int index, Object embed, Map<String, Object> attributes) {
        Doc y = this.doc;
        if (y != null) {
            Transaction.transact(y, transaction -> {
                ItemTextListPosition pos = findPosition(transaction, this, index, attributes == null);
                insertText(transaction, this, pos, embed, attributesOrEmpty(attributes));
            }, null, true);
        } else {
            this._pending.add(() -> this.insertEmbed(index, embed, attributesOrEmpty(attributes)));
        }
    }

    /**
     * Deletes text starting from an index.
     *
     * @param index  index at which to start deleting
     * @param length the number of characters to remove
     */
    public void delete(int index, int length) {
        if (length == 0) {
            return;
        }
        Doc y = this.doc;
        if (y != null) {
            Transaction.transact(y, transaction ->
                    deleteText(transaction, findPosition(transaction, this, index, true), length), null, true);
        } else {
            this._pending.add(() -> this.delete(index, length));
        }
    }

    /**
     * Assigns properties to a range of text.
     *
     * @param index      the position where to start formatting
     * @param length     the amount of characters to assign properties to
     * @param attributes attribute information to apply on the text
     */
    public void format(int index, int length, Map<String, Object> attributes) {
        if (length == 0) {
            return;
        }
        Doc y = this.doc;
        if (y != null) {
            Transaction.transact(y, transaction -> {
                ItemTextListPosition pos = findPosition(transaction, this, index, false);
                if (pos.right == null) {
                    return;
                }
                formatText(transaction, this, pos, length, attributes);
            }, null, true);
        } else {
            this._pending.add(() -> this.format(index, length, attributes));
        }
    }

    /**
     * Removes an attribute.
     *
     * <p>Note: Xml-Text nodes don't have attributes. You can use this feature to assign properties
     * to complete text-blocks.
     *
     * @param attributeName the attribute name that is to be removed
     */
    public void removeAttribute(String attributeName) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction ->
                    typeMapDelete(transaction, this, attributeName), null, true);
        } else {
            this._pending.add(() -> this.removeAttribute(attributeName));
        }
    }

    /**
     * Sets or updates an attribute.
     *
     * <p>Note: Xml-Text nodes don't have attributes. You can use this feature to assign properties
     * to complete text-blocks.
     *
     * @param attributeName  the attribute name that is to be set
     * @param attributeValue the attribute value that is to be set
     */
    public void setAttribute(String attributeName, Object attributeValue) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction ->
                    typeMapSet(transaction, this, attributeName, attributeValue), null, true);
        } else {
            this._pending.add(() -> this.setAttribute(attributeName, attributeValue));
        }
    }

    /**
     * Returns an attribute value that belongs to the attribute name.
     *
     * @param attributeName the attribute name that identifies the queried value
     * @return the queried attribute value
     */
    public Object getAttribute(String attributeName) {
        return typeMapGet(this, attributeName);
    }

    /**
     * Returns all attribute name/value pairs in a Map.
     *
     * @return a Map that describes the attributes
     */
    public Map<String, Object> getAttributes() {
        return typeMapGetAll(this);
    }

    @Override
    public void _write(UpdateEncoder encoder) {
        encoder.writeTypeRef(Y_TEXT_REF_ID);
    }

    public static YText read(UpdateDecoder decoder) {
        return new YText();
    }

    /* ===================== attribute helpers ===================== */

    /**
     * Faithful port of {@code equalAttrs = (a, b) => a === b || (typeof a === 'object' && typeof b
     * === 'object' && a && b && object.equalFlat(a, b))}.
     */
    @SuppressWarnings("unchecked")
    static boolean equalAttrs(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            return equalFlat((Map<String, Object>) a, (Map<String, Object>) b);
        }
        return false;
    }

    /** Faithful port of lib0 {@code object.equalFlat}. */
    static boolean equalFlat(Map<String, Object> a, Map<String, Object> b) {
        if (a == b) {
            return true;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, Object> e : a.entrySet()) {
            Object val = e.getValue();
            if (val == null && !b.containsKey(e.getKey())) {
                return false;
            }
            if (!Objects.equals(b.get(e.getKey()), val)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOrEmpty(Object attributes) {
        return attributes != null ? (Map<String, Object>) attributes : new LinkedHashMap<>();
    }

    private static Map<String, Object> ychange(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static boolean sameYChange(Object cur, long client, String type) {
        if (!(cur instanceof Map)) {
            return false;
        }
        Map<String, Object> m = (Map<String, Object>) cur;
        Object user = m.get("user");
        Object t = m.get("type");
        return user != null && ((Number) user).longValue() == client && type.equals(t);
    }

    /* ===================== ItemTextListPosition ===================== */

    /**
     * Tracks a position inside the YText linked list together with the currently active formatting
     * attributes. Port of {@code ItemTextListPosition}.
     */
    public static final class ItemTextListPosition {
        public Item left;
        public Item right;
        public int index;
        public Map<String, Object> currentAttributes;

        public ItemTextListPosition(Item left, Item right, int index, Map<String, Object> currentAttributes) {
            this.left = left;
            this.right = right;
            this.index = index;
            this.currentAttributes = currentAttributes;
        }

        /** Only call this if you know that {@code this.right} is defined. */
        public void forward() {
            if (this.right == null) {
                throw new IllegalStateException("Unexpected case");
            }
            if (this.right.content instanceof ContentFormat) {
                if (!this.right.deleted()) {
                    updateCurrentAttributes(this.currentAttributes, (ContentFormat) this.right.content);
                }
            } else {
                if (!this.right.deleted()) {
                    this.index += this.right.length;
                }
            }
            this.left = this.right;
            this.right = this.right.right;
        }
    }

    /* ===================== position helpers ===================== */

    /**
     * @param count steps to move forward
     */
    static ItemTextListPosition findNextPosition(Transaction transaction, ItemTextListPosition pos, int count) {
        while (pos.right != null && count > 0) {
            if (pos.right.content instanceof ContentFormat) {
                if (!pos.right.deleted()) {
                    updateCurrentAttributes(pos.currentAttributes, (ContentFormat) pos.right.content);
                }
            } else {
                if (!pos.right.deleted()) {
                    if (count < pos.right.length) {
                        // split right
                        getItemCleanStart(transaction, createID(pos.right.id.client, pos.right.id.clock + count));
                    }
                    pos.index += pos.right.length;
                    count -= pos.right.length;
                }
            }
            pos.left = pos.right;
            pos.right = pos.right.right;
            // pos.forward() - we don't forward because that would halve the performance because we
            // already do the checks above
        }
        return pos;
    }

    static ItemTextListPosition findPosition(Transaction transaction, AbstractType<?> parent, int index, boolean useSearchMarker) {
        Map<String, Object> currentAttributes = new LinkedHashMap<>();
        ArraySearchMarker marker = useSearchMarker ? findMarker(parent, index) : null;
        if (marker != null) {
            ItemTextListPosition pos = new ItemTextListPosition(marker.p.left, marker.p, marker.index, currentAttributes);
            return findNextPosition(transaction, pos, index - marker.index);
        } else {
            ItemTextListPosition pos = new ItemTextListPosition(null, parent._start, 0, currentAttributes);
            return findNextPosition(transaction, pos, index);
        }
    }

    /**
     * Negate applied formats.
     */
    static void insertNegatedAttributes(Transaction transaction, AbstractType<?> parent, ItemTextListPosition currPos, Map<String, Object> negatedAttributes) {
        // check if we really need to remove attributes
        while (
                currPos.right != null && (
                        currPos.right.deleted()
                                || (currPos.right.content instanceof ContentFormat
                                && equalAttrs(negatedAttributes.get(((ContentFormat) currPos.right.content).key), ((ContentFormat) currPos.right.content).value))
                )
        ) {
            if (!currPos.right.deleted()) {
                negatedAttributes.remove(((ContentFormat) currPos.right.content).key);
            }
            currPos.forward();
        }
        Doc doc = transaction.doc;
        long ownClientId = doc.clientID;
        negatedAttributes.forEach((key, val) -> {
            Item left = currPos.left;
            Item right = currPos.right;
            Item nextFormat = new Item(createID(ownClientId, getState(doc.store, ownClientId)), left,
                    left != null ? left.lastId() : null, right, right != null ? right.id : null,
                    parent, null, new ContentFormat(key, val));
            nextFormat.integrate(transaction, 0);
            currPos.right = nextFormat;
            currPos.forward();
        });
    }

    static void updateCurrentAttributes(Map<String, Object> currentAttributes, ContentFormat format) {
        String key = format.key;
        Object value = format.value;
        if (value == null) {
            currentAttributes.remove(key);
        } else {
            currentAttributes.put(key, value);
        }
    }

    static void minimizeAttributeChanges(ItemTextListPosition currPos, Map<String, Object> attributes) {
        // go right while attributes[right.key] === right.value (or right is deleted)
        while (true) {
            if (currPos.right == null) {
                break;
            } else if (currPos.right.deleted()
                    || (currPos.right.content instanceof ContentFormat
                    && equalAttrs(attributes.get(((ContentFormat) currPos.right.content).key), ((ContentFormat) currPos.right.content).value))) {
                //
            } else {
                break;
            }
            currPos.forward();
        }
    }

    static Map<String, Object> insertAttributes(Transaction transaction, AbstractType<?> parent, ItemTextListPosition currPos, Map<String, Object> attributes) {
        Doc doc = transaction.doc;
        long ownClientId = doc.clientID;
        Map<String, Object> negatedAttributes = new LinkedHashMap<>();
        // insert format-start items
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            Object currentVal = currPos.currentAttributes.get(key);
            if (!equalAttrs(currentVal, val)) {
                // save negated attribute (set null if currentVal undefined)
                negatedAttributes.put(key, currentVal);
                Item left = currPos.left;
                Item right = currPos.right;
                currPos.right = new Item(createID(ownClientId, getState(doc.store, ownClientId)), left,
                        left != null ? left.lastId() : null, right, right != null ? right.id : null,
                        parent, null, new ContentFormat(key, val));
                currPos.right.integrate(transaction, 0);
                currPos.forward();
            }
        }
        return negatedAttributes;
    }

    static void insertText(Transaction transaction, AbstractType<?> parent, ItemTextListPosition currPos, Object text, Map<String, Object> attributes) {
        currPos.currentAttributes.forEach((key, val) -> {
            if (!attributes.containsKey(key)) {
                attributes.put(key, null);
            }
        });
        Doc doc = transaction.doc;
        long ownClientId = doc.clientID;
        minimizeAttributeChanges(currPos, attributes);
        Map<String, Object> negatedAttributes = insertAttributes(transaction, parent, currPos, attributes);
        // insert content
        AbstractContent content = text instanceof String
                ? new ContentString((String) text)
                : (text instanceof AbstractType ? new ContentType((AbstractType<?>) text) : new ContentEmbed(text));
        Item left = currPos.left;
        Item right = currPos.right;
        int index = currPos.index;
        if (parent._searchMarker != null) {
            updateMarkerChanges(parent._searchMarker, currPos.index, content.getLength());
        }
        right = new Item(createID(ownClientId, getState(doc.store, ownClientId)), left,
                left != null ? left.lastId() : null, right, right != null ? right.id : null,
                parent, null, content);
        right.integrate(transaction, 0);
        currPos.right = right;
        currPos.index = index;
        currPos.forward();
        insertNegatedAttributes(transaction, parent, currPos, negatedAttributes);
    }

    static void formatText(Transaction transaction, AbstractType<?> parent, ItemTextListPosition currPos, int length, Map<String, Object> attributes) {
        Doc doc = transaction.doc;
        long ownClientId = doc.clientID;
        minimizeAttributeChanges(currPos, attributes);
        Map<String, Object> negatedAttributes = insertAttributes(transaction, parent, currPos, attributes);
        // iterate until first non-format or null is found
        // delete all formats with attributes[format.key] != null
        // also check the attributes after the first non-format as we do not want to insert
        // redundant negated attributes there
        iterationLoop:
        while (
                currPos.right != null
                        && (length > 0
                        || (negatedAttributes.size() > 0
                        && (currPos.right.deleted() || currPos.right.content instanceof ContentFormat)))
        ) {
            if (!currPos.right.deleted()) {
                if (currPos.right.content instanceof ContentFormat) {
                    String key = ((ContentFormat) currPos.right.content).key;
                    Object value = ((ContentFormat) currPos.right.content).value;
                    boolean hasAttr = attributes.containsKey(key);
                    Object attr = attributes.get(key);
                    if (hasAttr) {
                        if (equalAttrs(attr, value)) {
                            negatedAttributes.remove(key);
                        } else {
                            if (length == 0) {
                                // no need to further extend negatedAttributes
                                break iterationLoop;
                            }
                            negatedAttributes.put(key, value);
                        }
                        currPos.right.delete(transaction);
                    } else {
                        currPos.currentAttributes.put(key, value);
                    }
                } else {
                    if (length < currPos.right.length) {
                        getItemCleanStart(transaction, createID(currPos.right.id.client, currPos.right.id.clock + length));
                    }
                    length -= currPos.right.length;
                }
            }
            currPos.forward();
        }
        // Quill just assumes that the editor starts with a newline and that it always ends with a
        // newline. We only insert that newline when a new newline is inserted - i.e when length is
        // bigger than type.length
        if (length > 0) {
            StringBuilder newlines = new StringBuilder();
            for (; length > 0; length--) {
                newlines.append('\n');
            }
            currPos.right = new Item(createID(ownClientId, getState(doc.store, ownClientId)), currPos.left,
                    currPos.left != null ? currPos.left.lastId() : null, currPos.right,
                    currPos.right != null ? currPos.right.id : null, parent, null, new ContentString(newlines.toString()));
            currPos.right.integrate(transaction, 0);
            currPos.forward();
        }
        insertNegatedAttributes(transaction, parent, currPos, negatedAttributes);
    }

    /**
     * Call this function after string content has been deleted in order to clean up formatting
     * Items.
     *
     * @param curr exclusive end, automatically iterates to the next Content Item
     * @return the amount of formatting Items deleted
     */
    static int cleanupFormattingGap(Transaction transaction, Item start, Item curr, Map<String, Object> startAttributes, Map<String, Object> currAttributes) {
        Item end = start;
        Map<String, ContentFormat> endFormats = new LinkedHashMap<>();
        while (end != null && (!end.countable() || end.deleted())) {
            if (!end.deleted() && end.content instanceof ContentFormat) {
                ContentFormat cf = (ContentFormat) end.content;
                endFormats.put(cf.key, cf);
            }
            end = end.right;
        }
        int cleanups = 0;
        boolean reachedCurr = false;
        while (start != end) {
            if (curr == start) {
                reachedCurr = true;
            }
            if (!start.deleted()) {
                AbstractContent content = start.content;
                if (content instanceof ContentFormat) {
                    String key = ((ContentFormat) content).key;
                    Object value = ((ContentFormat) content).value;
                    Object startAttrValue = startAttributes.get(key);
                    if (endFormats.get(key) != content || equalsJs(startAttrValue, value)) {
                        // Either this format is overwritten or it is not necessary because the
                        // attribute already existed.
                        start.delete(transaction);
                        cleanups++;
                        if (!reachedCurr && equalsJs(currAttributes.get(key), value) && !equalsJs(startAttrValue, value)) {
                            if (startAttrValue == null) {
                                currAttributes.remove(key);
                            } else {
                                currAttributes.put(key, startAttrValue);
                            }
                        }
                    }
                    if (!reachedCurr && !start.deleted()) {
                        updateCurrentAttributes(currAttributes, (ContentFormat) content);
                    }
                }
            }
            start = start.right;
        }
        return cleanups;
    }

    static void cleanupContextlessFormattingGap(Transaction transaction, Item item) {
        // iterate until item.right is null or content
        while (item != null && item.right != null && (item.right.deleted() || !item.right.countable())) {
            item = item.right;
        }
        Set<String> attrs = new LinkedHashSet<>();
        // iterate back until a content item is found
        while (item != null && (item.deleted() || !item.countable())) {
            if (!item.deleted() && item.content instanceof ContentFormat) {
                String key = ((ContentFormat) item.content).key;
                if (attrs.contains(key)) {
                    item.delete(transaction);
                } else {
                    attrs.add(key);
                }
            }
            item = item.left;
        }
    }

    /**
     * This function is experimental and subject to change / be removed.
     *
     * <p>Ideally, we don't need this function at all. Formatting attributes should be cleaned up
     * automatically after each change. This function iterates twice over the complete YText type and
     * removes unnecessary formatting attributes. This is also helpful for testing.
     *
     * @param type the YText type
     * @return how many formatting attributes have been cleaned up
     */
    public static int cleanupYTextFormatting(YText type) {
        int[] res = {0};
        Transaction.transact(type.doc, transaction -> {
            Item[] start = {type._start};
            Item[] end = {type._start};
            Map<String, Object>[] startAttributes = new Map[]{new LinkedHashMap<>()};
            Map<String, Object> currentAttributes = new LinkedHashMap<>(startAttributes[0]);
            while (end[0] != null) {
                if (!end[0].deleted()) {
                    if (end[0].content instanceof ContentFormat) {
                        updateCurrentAttributes(currentAttributes, (ContentFormat) end[0].content);
                    } else {
                        res[0] += cleanupFormattingGap(transaction, start[0], end[0], startAttributes[0], currentAttributes);
                        startAttributes[0] = new LinkedHashMap<>(currentAttributes);
                        start[0] = end[0];
                    }
                }
                end[0] = end[0].right;
            }
        }, null, true);
        return res[0];
    }

    /**
     * This will be called by the transaction once the event handlers are called to potentially
     * cleanup formatting attributes.
     *
     * @param transaction the transaction
     */
    public static void cleanupYTextAfterTransaction(Transaction transaction) {
        Set<YText> needFullCleanup = new LinkedHashSet<>();
        // check if another formatting item was inserted
        Doc doc = transaction.doc;
        for (Map.Entry<Long, Long> e : transaction.afterState.entrySet()) {
            long client = e.getKey();
            long afterClock = e.getValue();
            long clock = transaction.beforeState.getOrDefault(client, 0L);
            if (afterClock == clock) {
                continue;
            }
            StructStore.iterateStructs(transaction, doc.store.clients.get(client), clock, afterClock, item -> {
                if (!item.deleted() && item instanceof Item && ((Item) item).content instanceof ContentFormat && !(item instanceof GC)) {
                    needFullCleanup.add((YText) ((Item) item).parent);
                }
            });
        }
        // cleanup in a new transaction
        Transaction.transact(doc, t -> {
            DeleteSet.iterateDeletedStructs(transaction, transaction.deleteSet, item -> {
                if (item instanceof GC) {
                    return;
                }
                Item it = (Item) item;
                YText parent = (YText) it.parent;
                if (!parent._hasFormatting || needFullCleanup.contains(parent)) {
                    return;
                }
                if (it.content instanceof ContentFormat) {
                    needFullCleanup.add(parent);
                } else {
                    // If no formatting attribute was inserted or deleted, we can make due with
                    // contextless formatting cleanups.
                    // Contextless: it is not necessary to compute currentAttributes for the affected
                    // position.
                    cleanupContextlessFormattingGap(t, it);
                }
            });
            // If a formatting item was inserted, we simply clean the whole type.
            // We need to compute currentAttributes for the current position anyway.
            for (YText yText : needFullCleanup) {
                cleanupYTextFormatting(yText);
            }
        }, null, true);
    }

    static ItemTextListPosition deleteText(Transaction transaction, ItemTextListPosition currPos, int length) {
        int startLength = length;
        Map<String, Object> startAttrs = new LinkedHashMap<>(currPos.currentAttributes);
        Item start = currPos.right;
        while (length > 0 && currPos.right != null) {
            if (!currPos.right.deleted()) {
                if (currPos.right.content instanceof ContentType
                        || currPos.right.content instanceof ContentEmbed
                        || currPos.right.content instanceof ContentString) {
                    if (length < currPos.right.length) {
                        getItemCleanStart(transaction, createID(currPos.right.id.client, currPos.right.id.clock + length));
                    }
                    length -= currPos.right.length;
                    currPos.right.delete(transaction);
                }
            }
            currPos.forward();
        }
        if (start != null) {
            cleanupFormattingGap(transaction, start, currPos.right, startAttrs, currPos.currentAttributes);
        }
        AbstractType<?> parent = (AbstractType<?>) (currPos.left != null ? currPos.left : currPos.right).parent;
        if (parent._searchMarker != null) {
            updateMarkerChanges(parent._searchMarker, currPos.index, -startLength + length);
        }
        return currPos;
    }

    /**
     * Faithful port of JS {@code ===} for attribute values (NaN-insensitive value equality). Used by
     * {@code cleanupFormattingGap} where JS compares {@code startAttrValue === value}.
     */
    private static boolean equalsJs(Object a, Object b) {
        return Objects.equals(a, b);
    }
}

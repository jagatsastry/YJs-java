package dev.yjs.types;

import dev.yjs.structs.ContentEmbed;
import dev.yjs.structs.ContentFormat;
import dev.yjs.structs.ContentString;
import dev.yjs.structs.ContentType;
import dev.yjs.structs.Item;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Transaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Event that describes the changes on a YText type. Port of YTextEvent in src/types/YText.js.
 *
 * @extends YEvent<YText>
 */
public class YTextEvent extends YEvent<YText> {
    /** Whether the children changed. */
    public boolean childListChanged;

    /** Set of all changed attributes. */
    public Set<String> keysChanged;

    /**
     * @param ytext       the text type
     * @param transaction the transaction
     * @param subs        the keys that changed ({@code null} entry means the list changed)
     */
    public YTextEvent(YText ytext, Transaction transaction, Set<String> subs) {
        super(ytext, transaction);
        this.childListChanged = false;
        this.keysChanged = new LinkedHashSet<>();
        subs.forEach(sub -> {
            if (sub == null) {
                this.childListChanged = true;
            } else {
                this.keysChanged.add(sub);
            }
        });
    }

    @Override
    public Changes changes() {
        if (this._changes == null) {
            Changes changes = new Changes();
            changes.keys = this.keys();
            changes.delta = this.delta();
            changes.added = new LinkedHashSet<>();
            changes.deleted = new LinkedHashSet<>();
            this._changes = changes;
        }
        return this._changes;
    }

    /**
     * Compute the changes in the delta format. A Quill Delta that represents the changes on the
     * document.
     *
     * @return the delta
     */
    @Override
    public List<Map<String, Object>> delta() {
        if (this._delta == null) {
            Doc y = this.target.doc;
            List<Map<String, Object>> delta = new ArrayList<>();
            Transaction.transact(y, transaction -> {
                Map<String, Object> currentAttributes = new LinkedHashMap<>(); // saves all current attributes for insert
                Map<String, Object> oldAttributes = new LinkedHashMap<>();
                Item[] itemHolder = {this.target._start};
                // action: null | "delete" | "insert" | "retain"
                String[] action = {null};
                Map<String, Object> attributes = new LinkedHashMap<>(); // counts added or removed new attributes for retain
                Object[] insert = {""};
                int[] retain = {0};
                int[] deleteLen = {0};

                Runnable addOp = () -> {
                    if (action[0] != null) {
                        Map<String, Object> op = null;
                        switch (action[0]) {
                            case "delete":
                                if (deleteLen[0] > 0) {
                                    op = new LinkedHashMap<>();
                                    op.put("delete", deleteLen[0]);
                                }
                                deleteLen[0] = 0;
                                break;
                            case "insert":
                                if (!(insert[0] instanceof String) || ((String) insert[0]).length() > 0) {
                                    op = new LinkedHashMap<>();
                                    op.put("insert", insert[0]);
                                    if (currentAttributes.size() > 0) {
                                        Map<String, Object> attrs = new LinkedHashMap<>();
                                        op.put("attributes", attrs);
                                        currentAttributes.forEach((key, value) -> {
                                            if (value != null) {
                                                attrs.put(key, value);
                                            }
                                        });
                                    }
                                }
                                insert[0] = "";
                                break;
                            case "retain":
                                if (retain[0] > 0) {
                                    op = new LinkedHashMap<>();
                                    op.put("retain", retain[0]);
                                    if (!attributes.isEmpty()) {
                                        op.put("attributes", new LinkedHashMap<>(attributes));
                                    }
                                }
                                retain[0] = 0;
                                break;
                            default:
                                break;
                        }
                        if (op != null) {
                            delta.add(op);
                        }
                        action[0] = null;
                    }
                };

                Item item = itemHolder[0];
                while (item != null) {
                    if (item.content instanceof ContentType || item.content instanceof ContentEmbed) {
                        if (this.adds(item)) {
                            if (!this.deletes(item)) {
                                addOp.run();
                                action[0] = "insert";
                                insert[0] = item.content.getContent().get(0);
                                addOp.run();
                            }
                        } else if (this.deletes(item)) {
                            if (!"delete".equals(action[0])) {
                                addOp.run();
                                action[0] = "delete";
                            }
                            deleteLen[0] += 1;
                        } else if (!item.deleted()) {
                            if (!"retain".equals(action[0])) {
                                addOp.run();
                                action[0] = "retain";
                            }
                            retain[0] += 1;
                        }
                    } else if (item.content instanceof ContentString) {
                        if (this.adds(item)) {
                            if (!this.deletes(item)) {
                                if (!"insert".equals(action[0])) {
                                    addOp.run();
                                    action[0] = "insert";
                                }
                                insert[0] = "" + insert[0] + ((ContentString) item.content).str;
                            }
                        } else if (this.deletes(item)) {
                            if (!"delete".equals(action[0])) {
                                addOp.run();
                                action[0] = "delete";
                            }
                            deleteLen[0] += item.length;
                        } else if (!item.deleted()) {
                            if (!"retain".equals(action[0])) {
                                addOp.run();
                                action[0] = "retain";
                            }
                            retain[0] += item.length;
                        }
                    } else if (item.content instanceof ContentFormat) {
                        String key = ((ContentFormat) item.content).key;
                        Object value = ((ContentFormat) item.content).value;
                        if (this.adds(item)) {
                            if (!this.deletes(item)) {
                                Object curVal = currentAttributes.get(key);
                                if (!YText.equalAttrs(curVal, value)) {
                                    if ("retain".equals(action[0])) {
                                        addOp.run();
                                    }
                                    if (YText.equalAttrs(value, oldAttributes.get(key))) {
                                        attributes.remove(key);
                                    } else {
                                        attributes.put(key, value);
                                    }
                                } else if (value != null) {
                                    item.delete(transaction);
                                }
                            }
                        } else if (this.deletes(item)) {
                            oldAttributes.put(key, value);
                            Object curVal = currentAttributes.get(key);
                            if (!YText.equalAttrs(curVal, value)) {
                                if ("retain".equals(action[0])) {
                                    addOp.run();
                                }
                                attributes.put(key, curVal);
                            }
                        } else if (!item.deleted()) {
                            oldAttributes.put(key, value);
                            boolean hasAttr = attributes.containsKey(key);
                            Object attr = attributes.get(key);
                            if (hasAttr) {
                                if (!YText.equalAttrs(attr, value)) {
                                    if ("retain".equals(action[0])) {
                                        addOp.run();
                                    }
                                    if (value == null) {
                                        attributes.remove(key);
                                    } else {
                                        attributes.put(key, value);
                                    }
                                } else if (attr != null) {
                                    // this will be cleaned up automatically by the contextless
                                    // cleanup function
                                    item.delete(transaction);
                                }
                            }
                        }
                        if (!item.deleted()) {
                            if ("insert".equals(action[0])) {
                                addOp.run();
                            }
                            YText.updateCurrentAttributes(currentAttributes, (ContentFormat) item.content);
                        }
                    }
                    item = item.right;
                }
                addOp.run();
                while (delta.size() > 0) {
                    Map<String, Object> lastOp = delta.get(delta.size() - 1);
                    if (lastOp.containsKey("retain") && !lastOp.containsKey("attributes")) {
                        // retain delta's if they don't assign attributes
                        delta.remove(delta.size() - 1);
                    } else {
                        break;
                    }
                }
            }, null, true);
            this._delta = delta;
        }
        return this._delta;
    }
}

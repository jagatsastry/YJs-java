package dev.yjs.types;

import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.Item;
import dev.yjs.utils.DeleteSet;
import dev.yjs.utils.Transaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes the changes on a YType. Port of src/utils/YEvent.js.
 *
 * @param <T> the target type
 */
public class YEvent<T extends AbstractType<?>> {
    private static final String ERROR_COMPUTE_CHANGES = "You must not compute changes after the event-handler fired.";

    public T target;
    public AbstractType<?> currentTarget;
    public final Transaction transaction;
    public Changes _changes = null;
    public Map<String, Change> _keys = null;
    public List<Map<String, Object>> _delta = null;
    public List<Object> _path = null;

    public YEvent(T target, Transaction transaction) {
        this.target = target;
        this.currentTarget = target;
        this.transaction = transaction;
    }

    /** A single key change in a map-like type. */
    public static final class Change {
        public String action; // 'add' | 'update' | 'delete'
        public Object oldValue;

        public Change(String action, Object oldValue) {
            this.action = action;
            this.oldValue = oldValue;
        }
    }

    /** The aggregated change description. */
    public static final class Changes {
        public Set<Item> added;
        public Set<Item> deleted;
        public Map<String, Change> keys;
        public List<Map<String, Object>> delta;
    }

    public List<Object> path() {
        if (_path == null) {
            _path = getPathTo(currentTarget, target);
        }
        return _path;
    }

    public boolean deletes(AbstractStruct struct) {
        return DeleteSet.isDeleted(transaction.deleteSet, struct.id);
    }

    public boolean adds(AbstractStruct struct) {
        return struct.id.clock >= transaction.beforeState.getOrDefault(struct.id.client, 0L);
    }

    public Map<String, Change> keys() {
        if (_keys == null) {
            if (transaction.doc._transactionCleanups.isEmpty()) {
                throw new RuntimeException(ERROR_COMPUTE_CHANGES);
            }
            Map<String, Change> keys = new LinkedHashMap<>();
            AbstractType<?> target = this.target;
            Set<String> changed = transaction.changed.get(target);
            for (String key : changed) {
                if (key != null) {
                    Item item = target._map.get(key);
                    String action;
                    Object oldValue;
                    if (adds(item)) {
                        Item prev = item.left;
                        while (prev != null && adds(prev)) {
                            prev = prev.left;
                        }
                        if (deletes(item)) {
                            if (prev != null && deletes(prev)) {
                                action = "delete";
                                oldValue = last(prev.content.getContent());
                            } else {
                                continue;
                            }
                        } else {
                            if (prev != null && deletes(prev)) {
                                action = "update";
                                oldValue = last(prev.content.getContent());
                            } else {
                                action = "add";
                                oldValue = null;
                            }
                        }
                    } else {
                        if (deletes(item)) {
                            action = "delete";
                            oldValue = last(item.content.getContent());
                        } else {
                            continue;
                        }
                    }
                    keys.put(key, new Change(action, oldValue));
                }
            }
            _keys = keys;
        }
        return _keys;
    }

    public List<Map<String, Object>> delta() {
        return changes().delta;
    }

    public Changes changes() {
        Changes changes = _changes;
        if (changes == null) {
            if (transaction.doc._transactionCleanups.isEmpty()) {
                throw new RuntimeException(ERROR_COMPUTE_CHANGES);
            }
            AbstractType<?> target = this.target;
            Set<Item> added = new LinkedHashSet<>();
            Set<Item> deleted = new LinkedHashSet<>();
            List<Map<String, Object>> delta = new ArrayList<>();
            changes = new Changes();
            changes.added = added;
            changes.deleted = deleted;
            changes.delta = delta;
            changes.keys = keys();
            Set<String> changed = transaction.changed.get(target);
            if (changed.contains(null)) {
                Map<String, Object>[] lastOp = new Map[]{null};
                Runnable packOp = () -> {
                    if (lastOp[0] != null) {
                        delta.add(lastOp[0]);
                    }
                };
                for (Item item = target._start; item != null; item = item.right) {
                    if (item.deleted()) {
                        if (deletes(item) && !adds(item)) {
                            if (lastOp[0] == null || !lastOp[0].containsKey("delete")) {
                                packOp.run();
                                lastOp[0] = new LinkedHashMap<>();
                                lastOp[0].put("delete", 0);
                            }
                            lastOp[0].put("delete", (Integer) lastOp[0].get("delete") + item.length);
                            deleted.add(item);
                        }
                    } else {
                        if (adds(item)) {
                            if (lastOp[0] == null || !lastOp[0].containsKey("insert")) {
                                packOp.run();
                                lastOp[0] = new LinkedHashMap<>();
                                lastOp[0].put("insert", new ArrayList<>());
                            }
                            @SuppressWarnings("unchecked")
                            List<Object> ins = (List<Object>) lastOp[0].get("insert");
                            ins.addAll(item.content.getContent());
                            added.add(item);
                        } else {
                            if (lastOp[0] == null || !lastOp[0].containsKey("retain")) {
                                packOp.run();
                                lastOp[0] = new LinkedHashMap<>();
                                lastOp[0].put("retain", 0);
                            }
                            lastOp[0].put("retain", (Integer) lastOp[0].get("retain") + item.length);
                        }
                    }
                }
                if (lastOp[0] != null && !lastOp[0].containsKey("retain")) {
                    packOp.run();
                }
            }
            _changes = changes;
        }
        return changes;
    }

    private static Object last(List<Object> l) {
        return l.get(l.size() - 1);
    }

    private static List<Object> getPathTo(AbstractType<?> parent, AbstractType<?> child) {
        List<Object> path = new ArrayList<>();
        while (child._item != null && child != parent) {
            if (child._item.parentSub != null) {
                path.add(0, child._item.parentSub);
            } else {
                int i = 0;
                Item c = ((AbstractType<?>) child._item.parent)._start;
                while (c != child._item && c != null) {
                    if (!c.deleted() && c.countable()) {
                        i += c.length;
                    }
                    c = c.right;
                }
                path.add(0, i);
            }
            child = (AbstractType<?>) child._item.parent;
        }
        return path;
    }
}

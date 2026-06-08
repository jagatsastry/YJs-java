package dev.yjs.structs;

import dev.yjs.lib0.Binary;
import dev.yjs.types.AbstractType;
import dev.yjs.types.ArraySearchMarker;
import dev.yjs.utils.DeleteSet;
import dev.yjs.utils.Doc;
import dev.yjs.utils.ID;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UndoManager;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.yjs.utils.ID.compareIDs;
import static dev.yjs.utils.ID.createID;

/**
 * The core CRDT list node implementing the YATA integration algorithm.
 * Port of src/structs/Item.js.
 */
public final class Item extends AbstractStruct {
    /** ID originally to the left (origin). */
    public ID origin;
    /** Item currently to the left. */
    public Item left;
    /** Item currently to the right. */
    public Item right;
    /** ID originally to the right. */
    public ID rightOrigin;
    /** AbstractType (integrated), ID (pre-integration search), String (diff-update edge), or null. */
    public Object parent;
    /** Key for map-style parents, else null. */
    public String parentSub;
    /** If this item's effect was redone, the id of the item that did so. */
    public ID redone;
    public AbstractContent content;
    /** bit1: keep, bit2: countable, bit3: deleted, bit4: marker. */
    public int info;

    public Item(ID id, Item left, ID origin, Item right, ID rightOrigin, Object parent, String parentSub, AbstractContent content) {
        super(id, content.getLength());
        this.origin = origin;
        this.left = left;
        this.right = right;
        this.rightOrigin = rightOrigin;
        this.parent = parent;
        this.parentSub = parentSub;
        this.redone = null;
        this.content = content;
        this.info = this.content.isCountable() ? Binary.BIT2 : 0;
    }

    public void setMarker(boolean isMarked) {
        if (((this.info & Binary.BIT4) > 0) != isMarked) {
            this.info ^= Binary.BIT4;
        }
    }

    public boolean marker() {
        return (this.info & Binary.BIT4) > 0;
    }

    public boolean keep() {
        return (this.info & Binary.BIT1) > 0;
    }

    public void setKeep(boolean doKeep) {
        if (keep() != doKeep) {
            this.info ^= Binary.BIT1;
        }
    }

    public boolean countable() {
        return (this.info & Binary.BIT2) > 0;
    }

    @Override
    public boolean deleted() {
        return (this.info & Binary.BIT3) > 0;
    }

    public void setDeleted(boolean doDelete) {
        if (deleted() != doDelete) {
            this.info ^= Binary.BIT3;
        }
    }

    public void markDeleted() {
        this.info |= Binary.BIT3;
    }

    /**
     * Return the creator clientID of the missing op, or define missing items and return null.
     */
    public Long getMissing(Transaction transaction, StructStore store) {
        if (origin != null && origin.client != id.client && origin.clock >= StructStore.getState(store, origin.client)) {
            return origin.client;
        }
        if (rightOrigin != null && rightOrigin.client != id.client && rightOrigin.clock >= StructStore.getState(store, rightOrigin.client)) {
            return rightOrigin.client;
        }
        if (parent instanceof ID pid && id.client != pid.client && pid.clock >= StructStore.getState(store, pid.client)) {
            return pid.client;
        }

        // We have all missing ids, now find the items
        AbstractStruct leftStruct = null;
        if (origin != null) {
            leftStruct = StructStore.getItemCleanEnd(transaction, store, origin);
            if (leftStruct instanceof Item li) {
                this.left = li;
                this.origin = li.lastId();
            } else {
                this.left = null;
            }
        }
        AbstractStruct rightStruct = null;
        if (rightOrigin != null) {
            rightStruct = StructStore.getStructCleanStart(transaction, rightOrigin);
            if (rightStruct instanceof Item ri) {
                this.right = ri;
                this.rightOrigin = ri.id;
            } else {
                this.right = null;
            }
        }
        boolean leftGC = leftStruct instanceof GC;
        boolean rightGC = rightStruct instanceof GC;
        if (leftGC || rightGC) {
            this.parent = null;
        } else if (this.parent == null) {
            if (this.left != null) {
                this.parent = this.left.parent;
                this.parentSub = this.left.parentSub;
            } else if (this.right != null) {
                this.parent = this.right.parent;
                this.parentSub = this.right.parentSub;
            }
        } else if (this.parent instanceof ID pid2) {
            AbstractStruct parentStruct = StructStore.find(store, pid2);
            if (parentStruct instanceof GC) {
                this.parent = null;
            } else {
                AbstractContent c = ((Item) parentStruct).content;
                // If the parent type was deleted its content is ContentDeleted (no `.type`);
                // JS yields `undefined` here, so the item becomes parent-less (integrates as GC).
                this.parent = (c instanceof ContentType ct) ? ct.type : null;
            }
        }
        return null;
    }

    @Override
    public void integrate(Transaction transaction, int offset) {
        if (offset > 0) {
            this.id = createID(this.id.client, this.id.clock + offset);
            this.left = (Item) StructStore.getItemCleanEnd(transaction, transaction.doc.store, createID(this.id.client, this.id.clock - 1));
            this.origin = this.left.lastId();
            this.content = this.content.splice(offset);
            this.length -= offset;
        }

        if (this.parent != null) {
            if ((this.left == null && (this.right == null || this.right.left != null)) || (this.left != null && this.left.right != this.right)) {
                Item left = this.left;
                Item o;
                // set o to the first conflicting item
                if (left != null) {
                    o = left.right;
                } else if (this.parentSub != null) {
                    o = ((AbstractType<?>) this.parent)._map.get(this.parentSub);
                    while (o != null && o.left != null) {
                        o = o.left;
                    }
                } else {
                    o = ((AbstractType<?>) this.parent)._start;
                }
                Set<Item> conflictingItems = new LinkedHashSet<>();
                Set<Item> itemsBeforeOrigin = new LinkedHashSet<>();
                while (o != null && o != this.right) {
                    itemsBeforeOrigin.add(o);
                    conflictingItems.add(o);
                    if (compareIDs(this.origin, o.origin)) {
                        // case 1
                        if (o.id.client < this.id.client) {
                            left = o;
                            conflictingItems.clear();
                        } else if (compareIDs(this.rightOrigin, o.rightOrigin)) {
                            break;
                        }
                    } else if (o.origin != null && itemsBeforeOrigin.contains(StructStore.getItem(transaction.doc.store, o.origin))) {
                        // case 2
                        if (!conflictingItems.contains(StructStore.getItem(transaction.doc.store, o.origin))) {
                            left = o;
                            conflictingItems.clear();
                        }
                    } else {
                        break;
                    }
                    o = o.right;
                }
                this.left = left;
            }
            // reconnect left/right + update parent map/start if necessary
            if (this.left != null) {
                Item right = this.left.right;
                this.right = right;
                this.left.right = this;
            } else {
                Item r;
                if (this.parentSub != null) {
                    r = ((AbstractType<?>) this.parent)._map.get(this.parentSub);
                    while (r != null && r.left != null) {
                        r = r.left;
                    }
                } else {
                    r = ((AbstractType<?>) this.parent)._start;
                    ((AbstractType<?>) this.parent)._start = this;
                }
                this.right = r;
            }
            if (this.right != null) {
                this.right.left = this;
            } else if (this.parentSub != null) {
                // set as current parent value if right === null and this is parentSub
                ((AbstractType<?>) this.parent)._map.put(this.parentSub, this);
                if (this.left != null) {
                    this.left.delete(transaction);
                }
            }
            // adjust length of parent
            if (this.parentSub == null && countable() && !deleted()) {
                ((AbstractType<?>) this.parent)._length += this.length;
            }
            StructStore.addStruct(transaction.doc.store, this);
            this.content.integrate(transaction, this);
            Transaction.addChangedTypeToTransaction(transaction, (AbstractType<?>) this.parent, this.parentSub);
            AbstractType<?> parentType = (AbstractType<?>) this.parent;
            if ((parentType._item != null && parentType._item.deleted()) || (this.parentSub != null && this.right != null)) {
                this.delete(transaction);
            }
        } else {
            // parent is not defined. Integrate GC struct instead
            new GC(this.id, this.length).integrate(transaction, 0);
        }
    }

    /** Next non-deleted item. */
    public Item next() {
        Item n = this.right;
        while (n != null && n.deleted()) {
            n = n.right;
        }
        return n;
    }

    /** Previous non-deleted item. */
    public Item prev() {
        Item n = this.left;
        while (n != null && n.deleted()) {
            n = n.left;
        }
        return n;
    }

    /** Last content address of this Item. */
    public ID lastId() {
        return this.length == 1 ? this.id : createID(this.id.client, this.id.clock + this.length - 1);
    }

    @Override
    public boolean mergeWith(AbstractStruct rightStruct) {
        if (!(rightStruct instanceof Item right)) {
            return false;
        }
        if (this.getClass() == right.getClass()
                && compareIDs(right.origin, this.lastId())
                && this.right == right
                && compareIDs(this.rightOrigin, right.rightOrigin)
                && this.id.client == right.id.client
                && this.id.clock + this.length == right.id.clock
                && this.deleted() == right.deleted()
                && this.redone == null
                && right.redone == null
                && this.content.getClass() == right.content.getClass()
                && this.content.mergeWith(right.content)) {
            List<ArraySearchMarker> searchMarker = ((AbstractType<?>) this.parent)._searchMarker;
            if (searchMarker != null) {
                for (ArraySearchMarker marker : searchMarker) {
                    if (marker.p == right) {
                        marker.p = this;
                        if (!this.deleted() && this.countable()) {
                            marker.index -= this.length;
                        }
                    }
                }
            }
            if (right.keep()) {
                this.setKeep(true);
            }
            this.right = right.right;
            if (this.right != null) {
                this.right.left = this;
            }
            this.length += right.length;
            return true;
        }
        return false;
    }

    /** Mark this Item as deleted. */
    public void delete(Transaction transaction) {
        if (!deleted()) {
            AbstractType<?> parent = (AbstractType<?>) this.parent;
            if (countable() && this.parentSub == null) {
                parent._length -= this.length;
            }
            markDeleted();
            DeleteSet.addToDeleteSet(transaction.deleteSet, this.id.client, this.id.clock, this.length);
            Transaction.addChangedTypeToTransaction(transaction, parent, this.parentSub);
            this.content.delete(transaction);
        }
    }

    public void gc(StructStore store, boolean parentGCd) {
        if (!deleted()) {
            throw new IllegalStateException("Unexpected case");
        }
        this.content.gc(store);
        if (parentGCd) {
            StructStore.replaceStruct(store, this, new GC(this.id, this.length));
        } else {
            this.content = new ContentDeleted(this.length);
        }
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        ID origin = offset > 0 ? createID(this.id.client, this.id.clock + offset - 1) : this.origin;
        ID rightOrigin = this.rightOrigin;
        String parentSub = this.parentSub;
        int info = (this.content.getRef() & Binary.BITS5)
                | (origin == null ? 0 : Binary.BIT8)
                | (rightOrigin == null ? 0 : Binary.BIT7)
                | (parentSub == null ? 0 : Binary.BIT6);
        encoder.writeInfo(info);
        if (origin != null) {
            encoder.writeLeftID(origin);
        }
        if (rightOrigin != null) {
            encoder.writeRightID(rightOrigin);
        }
        if (origin == null && rightOrigin == null) {
            Object parent = this.parent;
            if (parent instanceof AbstractType<?> at) {
                Item parentItem = at._item;
                if (parentItem == null) {
                    String ykey = ID.findRootTypeKey(at);
                    encoder.writeParentInfo(true);
                    encoder.writeString(ykey);
                } else {
                    encoder.writeParentInfo(false);
                    encoder.writeLeftID(parentItem.id);
                }
            } else if (parent instanceof String s) {
                encoder.writeParentInfo(true);
                encoder.writeString(s);
            } else if (parent instanceof ID pid) {
                encoder.writeParentInfo(false);
                encoder.writeLeftID(pid);
            } else {
                throw new IllegalStateException("Unexpected case");
            }
            if (parentSub != null) {
                encoder.writeString(parentSub);
            }
        }
        this.content.write(encoder, offset);
    }

    /* ===================== static helpers ===================== */

    public static FollowRedoneResult followRedone(StructStore store, ID id) {
        ID nextID = id;
        int diff = 0;
        AbstractStruct item;
        do {
            if (diff > 0) {
                nextID = createID(nextID.client, nextID.clock + diff);
            }
            item = StructStore.getItem(store, nextID);
            diff = (int) (nextID.clock - item.id.clock);
            nextID = (item instanceof Item it) ? it.redone : null;
        } while (nextID != null && item instanceof Item);
        return new FollowRedoneResult((Item) item, diff);
    }

    public static final class FollowRedoneResult {
        public final Item item;
        public final int diff;

        public FollowRedoneResult(Item item, int diff) {
            this.item = item;
            this.diff = diff;
        }
    }

    /** Ensure neither item nor any parent is ever deleted (transient flag). */
    public static void keepItem(Item item, boolean keep) {
        while (item != null && item.keep() != keep) {
            item.setKeep(keep);
            item = ((AbstractType<?>) item.parent)._item;
        }
    }

    /** Split leftItem into two items at {@code diff}. */
    public static Item splitItem(Transaction transaction, Item leftItem, int diff) {
        long client = leftItem.id.client;
        long clock = leftItem.id.clock;
        Item rightItem = new Item(
                createID(client, clock + diff),
                leftItem,
                createID(client, clock + diff - 1),
                leftItem.right,
                leftItem.rightOrigin,
                leftItem.parent,
                leftItem.parentSub,
                leftItem.content.splice(diff)
        );
        if (leftItem.deleted()) {
            rightItem.markDeleted();
        }
        if (leftItem.keep()) {
            rightItem.setKeep(true);
        }
        if (leftItem.redone != null) {
            rightItem.redone = createID(leftItem.redone.client, leftItem.redone.clock + diff);
        }
        leftItem.right = rightItem;
        if (rightItem.right != null) {
            rightItem.right.left = rightItem;
        }
        transaction._mergeStructs.add(rightItem);
        if (rightItem.parentSub != null && rightItem.right == null) {
            ((AbstractType<?>) rightItem.parent)._map.put(rightItem.parentSub, rightItem);
        }
        leftItem.length = diff;
        return rightItem;
    }

    private static boolean isDeletedByUndoStack(List<UndoManager.StackItem> stack, ID id) {
        for (UndoManager.StackItem s : stack) {
            if (DeleteSet.isDeleted(s.deletions, id)) {
                return true;
            }
        }
        return false;
    }

    /** Redoes the effect of an operation. */
    public static Item redoItem(Transaction transaction, Item item, Set<Item> redoitems, DeleteSet itemsToDelete, boolean ignoreRemoteMapChanges, UndoManager um) {
        Doc doc = transaction.doc;
        StructStore store = doc.store;
        long ownClientID = doc.clientID;
        ID redone = item.redone;
        if (redone != null) {
            return StructStore.getItemCleanStart(transaction, redone);
        }
        Item parentItem = ((AbstractType<?>) item.parent)._item;
        Item left = null;
        Item right;
        // make sure that parent is redone
        if (parentItem != null && parentItem.deleted()) {
            if (parentItem.redone == null && (!redoitems.contains(parentItem) || redoItem(transaction, parentItem, redoitems, itemsToDelete, ignoreRemoteMapChanges, um) == null)) {
                return null;
            }
            while (parentItem.redone != null) {
                parentItem = StructStore.getItemCleanStart(transaction, parentItem.redone);
            }
        }
        AbstractType<?> parentType = parentItem == null ? (AbstractType<?>) item.parent : ((ContentType) parentItem.content).type;

        if (item.parentSub == null) {
            // Array item. Insert at the old position.
            left = item.left;
            right = item;
            // find next cloned_redo items
            while (left != null) {
                Item leftTrace = left;
                while (leftTrace != null && ((AbstractType<?>) leftTrace.parent)._item != parentItem) {
                    leftTrace = leftTrace.redone == null ? null : StructStore.getItemCleanStart(transaction, leftTrace.redone);
                }
                if (leftTrace != null && ((AbstractType<?>) leftTrace.parent)._item == parentItem) {
                    left = leftTrace;
                    break;
                }
                left = left.left;
            }
            while (right != null) {
                Item rightTrace = right;
                while (rightTrace != null && ((AbstractType<?>) rightTrace.parent)._item != parentItem) {
                    rightTrace = rightTrace.redone == null ? null : StructStore.getItemCleanStart(transaction, rightTrace.redone);
                }
                if (rightTrace != null && ((AbstractType<?>) rightTrace.parent)._item == parentItem) {
                    right = rightTrace;
                    break;
                }
                right = right.right;
            }
        } else {
            right = null;
            if (item.right != null && !ignoreRemoteMapChanges) {
                left = item;
                while (left != null && left.right != null && (left.right.redone != null || DeleteSet.isDeleted(itemsToDelete, left.right.id) || isDeletedByUndoStack(um.undoStack, left.right.id) || isDeletedByUndoStack(um.redoStack, left.right.id))) {
                    left = left.right;
                    while (left.redone != null) {
                        left = StructStore.getItemCleanStart(transaction, left.redone);
                    }
                }
                if (left != null && left.right != null) {
                    return null;
                }
            } else {
                left = parentType._map.get(item.parentSub);
            }
            // drop cross-parent left so origin doesn't mislead the remote (#757)
            if (left != null && ((AbstractType<?>) left.parent)._item != parentItem) {
                left = parentType._map.get(item.parentSub);
            }
        }
        long nextClock = StructStore.getState(store, ownClientID);
        ID nextId = createID(ownClientID, nextClock);
        Item redoneItem = new Item(
                nextId,
                left, left != null ? left.lastId() : null,
                right, right != null ? right.id : null,
                parentType,
                item.parentSub,
                item.content.copy()
        );
        item.redone = nextId;
        keepItem(redoneItem, true);
        redoneItem.integrate(transaction, 0);
        return redoneItem;
    }

    /** A reader of item content from an update decoder. */
    public interface ContentReader {
        AbstractContent read(UpdateDecoder decoder);
    }

    public static AbstractContent readItemContent(UpdateDecoder decoder, int info) {
        return contentRefs[info & Binary.BITS5].read(decoder);
    }

    /** Lookup table indexed by content ref number. */
    public static final ContentReader[] contentRefs = new ContentReader[]{
            decoder -> {
                throw new IllegalStateException("Unexpected case");
            }, // 0 - GC is not ItemContent
            ContentDeleted::read,   // 1
            ContentJSON::read,      // 2
            ContentBinary::read,    // 3
            ContentString::read,    // 4
            ContentEmbed::read,     // 5
            ContentFormat::read,    // 6
            ContentType::read,      // 7
            ContentAny::read,       // 8
            ContentDoc::read,       // 9
            decoder -> {
                throw new IllegalStateException("Unexpected case");
            }  // 10 - Skip is not ItemContent
    };
}

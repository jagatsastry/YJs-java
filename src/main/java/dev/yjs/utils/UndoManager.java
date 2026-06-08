package dev.yjs.utils;

import dev.yjs.lib0.Observable;
import dev.yjs.structs.Item;
import dev.yjs.types.AbstractType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Manages undo/redo of changes on a set of types (or a whole document).
 * Port of src/utils/UndoManager.js.
 *
 * <p>Extends {@link Observable}. Fires the events {@code stack-item-added},
 * {@code stack-item-popped}, {@code stack-item-updated} and {@code stack-cleared}.
 * For the first three, listeners receive {@code [UndoEvent, UndoManager]}; for
 * {@code stack-cleared} they receive {@code [StackClearedEvent]}.
 */
public class UndoManager extends Observable {

    /**
     * A single undo/redo step. Holds the delete-set of structs deleted in the captured
     * interval ({@code deletions}) and a delete-set covering the structs inserted in the
     * interval ({@code insertions}). {@code meta} is free-form metadata (e.g. selection).
     */
    public static final class StackItem {
        public DeleteSet deletions;
        public DeleteSet insertions;
        /** Use this to save and restore metadata like selection range. */
        public final Map<String, Object> meta = new java.util.LinkedHashMap<>();

        public StackItem(DeleteSet deletions, DeleteSet insertions) {
            this.insertions = insertions;
            this.deletions = deletions;
        }
    }

    /**
     * Event payload for {@code stack-item-added}, {@code stack-item-popped} and
     * {@code stack-item-updated}.
     */
    public static final class UndoEvent {
        public final StackItem stackItem;
        public final Object origin;
        /** {@code "undo"} or {@code "redo"}. */
        public final String type;
        public final Map<AbstractType<?>, List<dev.yjs.types.YEvent<?>>> changedParentTypes;

        public UndoEvent(StackItem stackItem, Object origin, String type,
                         Map<AbstractType<?>, List<dev.yjs.types.YEvent<?>>> changedParentTypes) {
            this.stackItem = stackItem;
            this.origin = origin;
            this.type = type;
            this.changedParentTypes = changedParentTypes;
        }
    }

    /** Event payload for {@code stack-cleared}. */
    public static final class StackClearedEvent {
        public final boolean undoStackCleared;
        public final boolean redoStackCleared;

        public StackClearedEvent(boolean undoStackCleared, boolean redoStackCleared) {
            this.undoStackCleared = undoStackCleared;
            this.redoStackCleared = redoStackCleared;
        }
    }

    /** Configuration options for an {@link UndoManager}. */
    public static final class Options {
        public int captureTimeout = 500;
        public Function<Transaction, Boolean> captureTransaction = tr -> true;
        public Predicate<Item> deleteFilter = item -> true;
        /** Origins to track. Defaults to {@code {null}}. The UndoManager always adds itself. */
        public Set<Object> trackedOrigins = new LinkedHashSet<>(java.util.Collections.singletonList(null));
        public boolean ignoreRemoteMapChanges = false;
        /** The document this UndoManager operates on. Only needed if typeScope is empty. */
        public Doc doc = null;

        public Options captureTimeout(int t) { this.captureTimeout = t; return this; }
        public Options captureTransaction(Function<Transaction, Boolean> f) { this.captureTransaction = f; return this; }
        public Options deleteFilter(Predicate<Item> f) { this.deleteFilter = f; return this; }
        public Options trackedOrigins(Set<Object> s) { this.trackedOrigins = s; return this; }
        public Options ignoreRemoteMapChanges(boolean b) { this.ignoreRemoteMapChanges = b; return this; }
        public Options doc(Doc d) { this.doc = d; return this; }
    }

    /** Limits the scope of the UndoManager (types and/or the doc itself). */
    public final List<Object> scope = new ArrayList<>();
    public Doc doc;
    public Predicate<Item> deleteFilter;
    public Set<Object> trackedOrigins;
    public Function<Transaction, Boolean> captureTransaction;
    public List<StackItem> undoStack = new ArrayList<>();
    public List<StackItem> redoStack = new ArrayList<>();
    /** Whether the client is currently undoing (calling {@link #undo()}). */
    public boolean undoing = false;
    public boolean redoing = false;
    /** The currently popped stack item if {@link #undoing} or {@link #redoing}. */
    public StackItem currStackItem = null;
    public long lastChange = 0;
    public boolean ignoreRemoteMapChanges;
    public int captureTimeout;
    public final Consumer<Object[]> afterTransactionHandler;
    private final Consumer<Object[]> destroyHandler;

    public UndoManager(AbstractType<?> typeScope) {
        this(typeScope, new Options());
    }

    public UndoManager(AbstractType<?> typeScope, Options options) {
        this(scopeOf(typeScope), options, defaultDocForScope(scopeOf(typeScope), options, typeScope, null));
    }

    public UndoManager(Doc docScope) {
        this(docScope, new Options());
    }

    public UndoManager(Doc docScope, Options options) {
        this(scopeOf(docScope), options, defaultDocForScope(scopeOf(docScope), options, null, docScope));
    }

    public UndoManager(List<AbstractType<?>> typeScopes) {
        this(typeScopes, new Options());
    }

    public UndoManager(List<AbstractType<?>> typeScopes, Options options) {
        this(new ArrayList<Object>(typeScopes), options,
                defaultDocForScope(new ArrayList<Object>(typeScopes), options, null, null));
    }

    private static List<Object> scopeOf(Object single) {
        List<Object> l = new ArrayList<>();
        l.add(single);
        return l;
    }

    private static Doc defaultDocForScope(List<Object> scope, Options options, AbstractType<?> typeScope, Doc docScope) {
        if (options.doc != null) {
            return options.doc;
        }
        if (docScope != null) {
            return docScope;
        }
        if (typeScope != null) {
            return typeScope.doc;
        }
        // typeScopes list: use first scope's doc
        Object first = scope.isEmpty() ? null : scope.get(0);
        if (first instanceof AbstractType<?> at) {
            return at.doc;
        } else if (first instanceof Doc d) {
            return d;
        }
        return null;
    }

    /** Shared constructor: {@code rawScope} is a list whose elements are {@code AbstractType} or {@code Doc}. */
    private UndoManager(List<Object> rawScope, Options options, Doc doc) {
        super();
        this.doc = doc;
        this.addToScope(rawScope);
        this.deleteFilter = options.deleteFilter;
        Set<Object> trackedOrigins = options.trackedOrigins;
        trackedOrigins.add(this);
        this.trackedOrigins = trackedOrigins;
        this.captureTransaction = options.captureTransaction;
        this.undoStack = new ArrayList<>();
        this.redoStack = new ArrayList<>();
        this.undoing = false;
        this.redoing = false;
        this.currStackItem = null;
        this.lastChange = 0;
        this.ignoreRemoteMapChanges = options.ignoreRemoteMapChanges;
        this.captureTimeout = options.captureTimeout;

        this.afterTransactionHandler = args -> {
            Transaction transaction = (Transaction) args[0];
            // Only track certain transactions
            if (
                    !this.captureTransaction.apply(transaction)
                            || !scopeMatchesChangedParents(transaction)
                            || (!this.trackedOrigins.contains(transaction.origin)
                            && (transaction.origin == null
                            || !this.trackedOrigins.contains(transaction.origin.getClass())))
            ) {
                return;
            }
            boolean undoing = this.undoing;
            boolean redoing = this.redoing;
            List<StackItem> stack = undoing ? this.redoStack : this.undoStack;
            if (undoing) {
                this.stopCapturing(); // next undo should not be appended to last stack item
            } else if (!redoing) {
                // neither undoing nor redoing: delete redoStack
                this.clear(false, true);
            }
            DeleteSet insertions = new DeleteSet();
            transaction.afterState.forEach((client, endClock) -> {
                long startClock = transaction.beforeState.getOrDefault(client, 0L);
                long len = endClock - startClock;
                if (len > 0) {
                    DeleteSet.addToDeleteSet(insertions, client, startClock, len);
                }
            });
            long now = System.currentTimeMillis();
            boolean didAdd = false;
            if (this.lastChange > 0 && now - this.lastChange < this.captureTimeout && !stack.isEmpty() && !undoing && !redoing) {
                // append change to last stack op
                StackItem lastOp = stack.get(stack.size() - 1);
                lastOp.deletions = DeleteSet.mergeDeleteSets(Arrays.asList(lastOp.deletions, transaction.deleteSet));
                lastOp.insertions = DeleteSet.mergeDeleteSets(Arrays.asList(lastOp.insertions, insertions));
            } else {
                // create a new stack op
                stack.add(new StackItem(transaction.deleteSet, insertions));
                didAdd = true;
            }
            if (!undoing && !redoing) {
                this.lastChange = now;
            }
            // make sure that deleted structs are not gc'd
            DeleteSet.iterateDeletedStructs(transaction, transaction.deleteSet, struct -> {
                if (struct instanceof Item item && this.scope.stream().anyMatch(type ->
                        type == transaction.doc || IsParentOf.isParentOf((AbstractType<?>) type, item))) {
                    Item.keepItem(item, true);
                }
            });
            UndoEvent changeEvent = new UndoEvent(
                    stack.get(stack.size() - 1),
                    transaction.origin,
                    undoing ? "redo" : "undo",
                    transaction.changedParentTypes);
            if (didAdd) {
                this.emit("stack-item-added", new Object[]{changeEvent, this});
            } else {
                this.emit("stack-item-updated", new Object[]{changeEvent, this});
            }
        };
        this.doc.on("afterTransaction", this.afterTransactionHandler);
        this.destroyHandler = args -> this.destroy();
        this.doc.on("destroy", this.destroyHandler);
    }

    /** JS: {@code this.scope.some(type => transaction.changedParentTypes.has(type) || type === this.doc)}. */
    private boolean scopeMatchesChangedParents(Transaction transaction) {
        for (Object type : this.scope) {
            if (type == this.doc) {
                return true;
            }
            if (type instanceof AbstractType<?> at && transaction.changedParentTypes.containsKey(at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extend the scope.
     *
     * @param ytypes a list whose elements are {@code AbstractType} or {@code Doc}
     */
    public void addToScope(List<Object> ytypes) {
        Set<Object> tmpSet = new LinkedHashSet<>(this.scope);
        for (Object ytype : ytypes) {
            if (!tmpSet.contains(ytype)) {
                tmpSet.add(ytype);
                boolean notSameDoc = ytype instanceof AbstractType<?> at ? at.doc != this.doc : ytype != this.doc;
                if (notSameDoc) {
                    System.err.println("[yjs#509] Not same Y.Doc"); // use MultiDocUndoManager instead
                }
                this.scope.add(ytype);
            }
        }
    }

    /** Extend the scope with a single type. */
    public void addToScope(AbstractType<?> ytype) {
        addToScope(scopeOf(ytype));
    }

    /** Extend the scope with the document itself. */
    public void addToScope(Doc ydoc) {
        addToScope(scopeOf(ydoc));
    }

    public void addTrackedOrigin(Object origin) {
        this.trackedOrigins.add(origin);
    }

    public void removeTrackedOrigin(Object origin) {
        this.trackedOrigins.remove(origin);
    }

    public void clear() {
        clear(true, true);
    }

    public void clear(boolean clearUndoStack, boolean clearRedoStack) {
        if ((clearUndoStack && this.canUndo()) || (clearRedoStack && this.canRedo())) {
            this.doc.transact((Transaction tr) -> {
                if (clearUndoStack) {
                    for (StackItem item : this.undoStack) {
                        clearUndoManagerStackItem(tr, this, item);
                    }
                    this.undoStack = new ArrayList<>();
                }
                if (clearRedoStack) {
                    for (StackItem item : this.redoStack) {
                        clearUndoManagerStackItem(tr, this, item);
                    }
                    this.redoStack = new ArrayList<>();
                }
                this.emit("stack-cleared", new Object[]{new StackClearedEvent(clearUndoStack, clearRedoStack)});
            });
        }
    }

    /**
     * UndoManager merges Undo-StackItems if they are created within a time-gap smaller than
     * {@code options.captureTimeout}. Call {@code stopCapturing()} so that the next StackItem
     * won't be merged.
     */
    public void stopCapturing() {
        this.lastChange = 0;
    }

    /**
     * Undo last changes on type.
     *
     * @return the popped StackItem if a change was applied, otherwise {@code null}
     */
    public StackItem undo() {
        this.undoing = true;
        StackItem res;
        try {
            res = popStackItem(this, this.undoStack, "undo");
        } finally {
            this.undoing = false;
        }
        return res;
    }

    /**
     * Redo last undo operation.
     *
     * @return the popped StackItem if a change was applied, otherwise {@code null}
     */
    public StackItem redo() {
        this.redoing = true;
        StackItem res;
        try {
            res = popStackItem(this, this.redoStack, "redo");
        } finally {
            this.redoing = false;
        }
        return res;
    }

    /** Are undo steps available? */
    public boolean canUndo() {
        return !this.undoStack.isEmpty();
    }

    /** Are redo steps available? */
    public boolean canRedo() {
        return !this.redoStack.isEmpty();
    }

    @Override
    public void destroy() {
        this.trackedOrigins.remove(this);
        this.doc.off("afterTransaction", this.afterTransactionHandler);
        this.doc.off("destroy", this.destroyHandler);
        super.destroy();
    }

    /* ===================== static helpers ===================== */

    private static void clearUndoManagerStackItem(Transaction tr, UndoManager um, StackItem stackItem) {
        DeleteSet.iterateDeletedStructs(tr, stackItem.deletions, item -> {
            if (item instanceof Item it && um.scope.stream().anyMatch(type ->
                    type == tr.doc || IsParentOf.isParentOf((AbstractType<?>) type, it))) {
                Item.keepItem(it, false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static StackItem popStackItem(UndoManager undoManager, List<StackItem> stack, String eventType) {
        // Keep a reference to the transaction so we can fire the event with the changedParentTypes.
        Transaction[] _tr = {null};
        Doc doc = undoManager.doc;
        List<Object> scope = undoManager.scope;
        Transaction.transact(doc, (Transaction transaction) -> {
            while (!stack.isEmpty() && undoManager.currStackItem == null) {
                StructStore store = doc.store;
                StackItem stackItem = stack.remove(stack.size() - 1);
                Set<Item> itemsToRedo = new LinkedHashSet<>();
                List<Item> itemsToDelete = new ArrayList<>();
                boolean[] performedChange = {false};
                DeleteSet.iterateDeletedStructs(transaction, stackItem.insertions, structIn -> {
                    dev.yjs.structs.AbstractStruct struct = structIn;
                    if (struct instanceof Item) {
                        if (((Item) struct).redone != null) {
                            Item.FollowRedoneResult fr = Item.followRedone(store, struct.id);
                            Item item = fr.item;
                            int diff = fr.diff;
                            if (diff > 0) {
                                item = StructStore.getItemCleanStart(transaction,
                                        ID.createID(item.id.client, item.id.clock + diff));
                            }
                            struct = item;
                        }
                        Item structItem = (Item) struct;
                        if (!structItem.deleted() && scope.stream().anyMatch(type ->
                                type == transaction.doc || IsParentOf.isParentOf((AbstractType<?>) type, structItem))) {
                            itemsToDelete.add(structItem);
                        }
                    }
                });
                DeleteSet.iterateDeletedStructs(transaction, stackItem.deletions, struct -> {
                    if (struct instanceof Item structItem
                            && scope.stream().anyMatch(type ->
                            type == transaction.doc || IsParentOf.isParentOf((AbstractType<?>) type, structItem))
                            // Never redo structs in stackItem.insertions because they were created and
                            // deleted in the same capture interval.
                            && !DeleteSet.isDeleted(stackItem.insertions, struct.id)) {
                        itemsToRedo.add(structItem);
                    }
                });
                for (Item struct : itemsToRedo) {
                    performedChange[0] = Item.redoItem(transaction, struct, itemsToRedo, stackItem.insertions,
                            undoManager.ignoreRemoteMapChanges, undoManager) != null || performedChange[0];
                }
                // We want to delete in reverse order so that children are deleted before parents, so we
                // have more information available when items are filtered.
                for (int i = itemsToDelete.size() - 1; i >= 0; i--) {
                    Item item = itemsToDelete.get(i);
                    if (undoManager.deleteFilter.test(item)) {
                        item.delete(transaction);
                        performedChange[0] = true;
                    }
                }
                undoManager.currStackItem = performedChange[0] ? stackItem : null;
            }
            transaction.changed.forEach((type, subProps) -> {
                // destroy search marker if necessary
                if (subProps.contains(null) && type._searchMarker != null) {
                    type._searchMarker.clear();
                }
            });
            _tr[0] = transaction;
        }, undoManager, true);
        StackItem res = undoManager.currStackItem;
        if (res != null) {
            Map<AbstractType<?>, List<dev.yjs.types.YEvent<?>>> changedParentTypes = _tr[0].changedParentTypes;
            undoManager.emit("stack-item-popped", new Object[]{
                    new UndoEvent(res, undoManager, eventType, changedParentTypes), undoManager});
            undoManager.currStackItem = null;
        }
        return res;
    }
}

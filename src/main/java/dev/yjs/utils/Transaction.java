package dev.yjs.utils;

import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.GC;
import dev.yjs.structs.Item;
import dev.yjs.types.AbstractType;
import dev.yjs.types.YEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A transaction bundles all changes on the Yjs model. Port of src/utils/Transaction.js.
 */
public final class Transaction {
    public final Doc doc;
    public DeleteSet deleteSet = new DeleteSet();
    public final Map<Long, Long> beforeState;
    public Map<Long, Long> afterState = new LinkedHashMap<>();
    /** Types directly modified -> set of changed parentSubs (null entry means list modified). */
    public final Map<AbstractType<?>, Set<String>> changed = new LinkedHashMap<>();
    /** Events for observeDeep, per type. */
    public final Map<AbstractType<?>, List<YEvent<?>>> changedParentTypes = new LinkedHashMap<>();
    public final List<AbstractStruct> _mergeStructs = new ArrayList<>();
    public final Object origin;
    public final Map<Object, Object> meta = new LinkedHashMap<>();
    public boolean local;
    public final Set<Doc> subdocsAdded = new LinkedHashSet<>();
    public final Set<Doc> subdocsRemoved = new LinkedHashSet<>();
    public final Set<Doc> subdocsLoaded = new LinkedHashSet<>();
    public boolean _needFormattingCleanup = false;

    public Transaction(Doc doc, Object origin, boolean local) {
        this.doc = doc;
        this.beforeState = StructStore.getStateVector(doc.store);
        this.origin = origin;
        this.local = local;
    }

    /** @return whether data was written. */
    public static boolean writeUpdateMessageFromTransaction(UpdateEncoder encoder, Transaction transaction) {
        boolean stateChanged = false;
        for (Map.Entry<Long, Long> e : transaction.afterState.entrySet()) {
            Long before = transaction.beforeState.get(e.getKey());
            if (before == null || !before.equals(e.getValue())) {
                stateChanged = true;
                break;
            }
        }
        if (transaction.deleteSet.clients.isEmpty() && !stateChanged) {
            return false;
        }
        DeleteSet.sortAndMergeDeleteSet(transaction.deleteSet);
        Updates.writeStructsFromTransaction(encoder, transaction);
        DeleteSet.writeDeleteSet(encoder, transaction.deleteSet);
        return true;
    }

    public static ID nextID(Transaction transaction) {
        Doc y = transaction.doc;
        return ID.createID(y.clientID, StructStore.getState(y.store, y.clientID));
    }

    public static void addChangedTypeToTransaction(Transaction transaction, AbstractType<?> type, String parentSub) {
        Item item = type._item;
        if (item == null) {
            transaction.changed.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(parentSub);
        } else if (item.id.clock < transaction.beforeState.getOrDefault(item.id.client, 0L) && !item.deleted()) {
            transaction.changed.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(parentSub);
        }
    }

    /** @return number of merged structs. */
    private static int tryToMergeWithLefts(List<AbstractStruct> structs, int pos) {
        AbstractStruct right = structs.get(pos);
        AbstractStruct left = structs.get(pos - 1);
        int i = pos;
        for (; i > 0; ) {
            boolean didMerge = false;
            if (left.deleted() == right.deleted() && left.getClass() == right.getClass()) {
                if (left.mergeWith(right)) {
                    if (right instanceof Item ri && ri.parentSub != null && ((AbstractType<?>) ri.parent)._map.get(ri.parentSub) == ri) {
                        ((AbstractType<?>) ri.parent)._map.put(ri.parentSub, (Item) left);
                    }
                    didMerge = true;
                }
            }
            if (!didMerge) {
                break;
            }
            // update (matches JS `right = left, left = structs[--i - 1]`)
            right = left;
            i--;
            if (i > 0) {
                left = structs.get(i - 1);
            }
        }
        int merged = pos - i;
        if (merged > 0) {
            // remove all merged structs from the array
            int from = pos + 1 - merged;
            for (int k = 0; k < merged; k++) {
                structs.remove(from);
            }
        }
        return merged;
    }

    private static void tryGcDeleteSet(DeleteSet ds, StructStore store, Predicate<Item> gcFilter) {
        for (Map.Entry<Long, List<DeleteSet.DeleteItem>> e : ds.clients.entrySet()) {
            List<AbstractStruct> structs = store.clients.get(e.getKey());
            List<DeleteSet.DeleteItem> deleteItems = e.getValue();
            for (int di = deleteItems.size() - 1; di >= 0; di--) {
                DeleteSet.DeleteItem deleteItem = deleteItems.get(di);
                long endDeleteItemClock = deleteItem.clock + deleteItem.len;
                for (int si = StructStore.findIndexSS(structs, deleteItem.clock); si < structs.size(); si++) {
                    AbstractStruct struct = structs.get(si);
                    if (struct.id.clock >= endDeleteItemClock) {
                        break;
                    }
                    if (deleteItem.clock + deleteItem.len <= struct.id.clock) {
                        break;
                    }
                    if (struct instanceof Item item && struct.deleted() && !item.keep() && gcFilter.test(item)) {
                        item.gc(store, false);
                    }
                }
            }
        }
    }

    private static void tryMergeDeleteSet(DeleteSet ds, StructStore store) {
        ds.clients.forEach((client, deleteItems) -> {
            List<AbstractStruct> structs = store.clients.get(client);
            for (int di = deleteItems.size() - 1; di >= 0; di--) {
                DeleteSet.DeleteItem deleteItem = deleteItems.get(di);
                int mostRightIndexToCheck = (int) Math.min(structs.size() - 1, 1 + StructStore.findIndexSS(structs, deleteItem.clock + deleteItem.len - 1));
                for (int si = mostRightIndexToCheck; si > 0 && structs.get(si).id.clock >= deleteItem.clock; ) {
                    si -= 1 + tryToMergeWithLefts(structs, si);
                }
            }
        });
    }

    public static void tryGc(DeleteSet ds, StructStore store, Predicate<Item> gcFilter) {
        tryGcDeleteSet(ds, store, gcFilter);
        tryMergeDeleteSet(ds, store);
    }

    private static void callAll(List<Runnable> fs, int i) {
        try {
            for (; i < fs.size(); i++) {
                fs.get(i).run();
            }
        } finally {
            if (i < fs.size()) {
                callAll(fs, i + 1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void cleanupTransactions(List<Transaction> transactionCleanups, int i) {
        if (i >= transactionCleanups.size()) {
            return;
        }
        Transaction transaction = transactionCleanups.get(i);
        Doc doc = transaction.doc;
        StructStore store = doc.store;
        DeleteSet ds = transaction.deleteSet;
        List<AbstractStruct> mergeStructs = transaction._mergeStructs;
        try {
            DeleteSet.sortAndMergeDeleteSet(ds);
            transaction.afterState = StructStore.getStateVector(transaction.doc.store);
            doc.emit("beforeObserverCalls", new Object[]{transaction, doc});
            List<Runnable> fs = new ArrayList<>();
            transaction.changed.forEach((itemtype, subs) ->
                    fs.add(() -> {
                        if (itemtype._item == null || !itemtype._item.deleted()) {
                            itemtype._callObserver(transaction, subs);
                        }
                    }));
            fs.add(() -> {
                transaction.changedParentTypes.forEach((type, eventsIn) -> {
                    if (!type._dEH.l.isEmpty() && (type._item == null || !type._item.deleted())) {
                        List<YEvent<?>> events = new ArrayList<>();
                        for (YEvent<?> event : eventsIn) {
                            if (event.target._item == null || !event.target._item.deleted()) {
                                events.add(event);
                            }
                        }
                        for (YEvent<?> event : events) {
                            event.currentTarget = type;
                            event._path = null;
                        }
                        events.sort((e1, e2) -> e1.path().size() - e2.path().size());
                        fs.add(() -> EventHandler.callEventHandlerListeners(type._dEH, events, transaction));
                    }
                });
                fs.add(() -> doc.emit("afterTransaction", new Object[]{transaction, doc}));
                fs.add(() -> {
                    if (transaction._needFormattingCleanup) {
                        dev.yjs.types.YText.cleanupYTextAfterTransaction(transaction);
                    }
                });
            });
            callAll(fs, 0);
        } finally {
            if (doc.gc) {
                tryGcDeleteSet(ds, store, doc.gcFilter);
            }
            tryMergeDeleteSet(ds, store);

            transaction.afterState.forEach((client, clock) -> {
                long beforeClock = transaction.beforeState.getOrDefault(client, 0L);
                if (beforeClock != clock) {
                    List<AbstractStruct> structs = store.clients.get(client);
                    int firstChangePos = Math.max(StructStore.findIndexSS(structs, beforeClock), 1);
                    for (int k = structs.size() - 1; k >= firstChangePos; ) {
                        k -= 1 + tryToMergeWithLefts(structs, k);
                    }
                }
            });
            for (int mi = mergeStructs.size() - 1; mi >= 0; mi--) {
                ID id = mergeStructs.get(mi).id;
                long client = id.client;
                long clock = id.clock;
                List<AbstractStruct> structs = store.clients.get(client);
                int replacedStructPos = StructStore.findIndexSS(structs, clock);
                if (replacedStructPos + 1 < structs.size()) {
                    if (tryToMergeWithLefts(structs, replacedStructPos + 1) > 1) {
                        continue;
                    }
                }
                if (replacedStructPos > 0) {
                    tryToMergeWithLefts(structs, replacedStructPos);
                }
            }
            if (!transaction.local
                    && !java.util.Objects.equals(transaction.afterState.get(doc.clientID), transaction.beforeState.get(doc.clientID))) {
                System.err.println("[yjs] Changed the client-id because another client seems to be using it.");
                doc.clientID = Doc.generateNewClientId();
            }
            doc.emit("afterTransactionCleanup", new Object[]{transaction, doc});
            if (doc.hasObserver("update")) {
                UpdateEncoderV1 encoder = new UpdateEncoderV1();
                boolean hasContent = writeUpdateMessageFromTransaction(encoder, transaction);
                if (hasContent) {
                    doc.emit("update", new Object[]{encoder.toUint8Array(), transaction.origin, doc, transaction});
                }
            }
            if (doc.hasObserver("updateV2")) {
                UpdateEncoderV2 encoder = new UpdateEncoderV2();
                boolean hasContent = writeUpdateMessageFromTransaction(encoder, transaction);
                if (hasContent) {
                    doc.emit("updateV2", new Object[]{encoder.toUint8Array(), transaction.origin, doc, transaction});
                }
            }
            Set<Doc> subdocsAdded = transaction.subdocsAdded;
            Set<Doc> subdocsLoaded = transaction.subdocsLoaded;
            Set<Doc> subdocsRemoved = transaction.subdocsRemoved;
            if (!subdocsAdded.isEmpty() || !subdocsRemoved.isEmpty() || !subdocsLoaded.isEmpty()) {
                subdocsAdded.forEach(subdoc -> {
                    subdoc.clientID = doc.clientID;
                    if (subdoc.collectionid == null) {
                        subdoc.collectionid = doc.collectionid;
                    }
                    doc.subdocs.add(subdoc);
                });
                subdocsRemoved.forEach(doc.subdocs::remove);
                doc.emit("subdocs", new Object[]{new SubdocsEvent(subdocsLoaded, subdocsAdded, subdocsRemoved), doc, transaction});
                subdocsRemoved.forEach(Doc::destroy);
            }

            if (transactionCleanups.size() <= i + 1) {
                doc._transactionCleanups = new ArrayList<>();
                doc.emit("afterAllTransactions", new Object[]{doc, transactionCleanups});
            } else {
                cleanupTransactions(transactionCleanups, i + 1);
            }
        }
    }

    /** Event payload for the {@code subdocs} event. */
    public static final class SubdocsEvent {
        public final Set<Doc> loaded;
        public final Set<Doc> added;
        public final Set<Doc> removed;

        public SubdocsEvent(Set<Doc> loaded, Set<Doc> added, Set<Doc> removed) {
            this.loaded = loaded;
            this.added = added;
            this.removed = removed;
        }
    }

    public static <T> T transactWithResult(Doc doc, Function<Transaction, T> f, Object origin, boolean local) {
        List<Transaction> transactionCleanups = doc._transactionCleanups;
        boolean initialCall = false;
        T result = null;
        if (doc._transaction == null) {
            initialCall = true;
            doc._transaction = new Transaction(doc, origin, local);
            transactionCleanups.add(doc._transaction);
            if (transactionCleanups.size() == 1) {
                doc.emit("beforeAllTransactions", new Object[]{doc});
            }
            doc.emit("beforeTransaction", new Object[]{doc._transaction, doc});
        }
        try {
            result = f.apply(doc._transaction);
        } finally {
            if (initialCall) {
                boolean finishCleanup = doc._transaction == transactionCleanups.get(0);
                doc._transaction = null;
                if (finishCleanup) {
                    cleanupTransactions(transactionCleanups, 0);
                }
            }
        }
        return result;
    }

    public static void transact(Doc doc, java.util.function.Consumer<Transaction> f, Object origin, boolean local) {
        transactWithResult(doc, (Transaction t) -> {
            f.accept(t);
            return null;
        }, origin, local);
    }
}

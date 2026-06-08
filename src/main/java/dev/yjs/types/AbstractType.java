package dev.yjs.types;

import dev.yjs.structs.ContentAny;
import dev.yjs.structs.ContentBinary;
import dev.yjs.structs.ContentDoc;
import dev.yjs.structs.ContentType;
import dev.yjs.structs.Item;
import dev.yjs.utils.Doc;
import dev.yjs.utils.EventHandler;
import dev.yjs.utils.ID;
import dev.yjs.utils.Snapshot;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;

import static dev.yjs.utils.ID.createID;
import static dev.yjs.utils.StructStore.getItemCleanStart;
import static dev.yjs.utils.StructStore.getState;

/**
 * Abstract Yjs type — base of all shared types. Port of src/types/AbstractType.js.
 *
 * <p>This class is intentionally concrete (not {@code abstract}): {@link Doc#get(String)} uses a
 * bare {@code AbstractType} as a placeholder until it is re-typed.
 *
 * @param <E> the event type produced by this type
 */
public class AbstractType<E> {
    static final int MAX_SEARCH_MARKER = 80;

    public Item _item = null;
    public Map<String, Item> _map = new LinkedHashMap<>();
    public Item _start = null;
    public Doc doc = null;
    public int _length = 0;
    public EventHandler<E, Transaction> _eH = EventHandler.createEventHandler();
    public EventHandler<List<YEvent<?>>, Transaction> _dEH = EventHandler.createEventHandler();
    public List<ArraySearchMarker> _searchMarker = null;
    /** Set when a ContentFormat is integrated under this type (YText rich-text marker). */
    public boolean _hasFormatting = false;

    public static void warnPrematureAccess() {
        System.err.println("[yjs] Invalid access: Add Yjs type to a document before reading data.");
    }

    public AbstractType<?> parent() {
        return _item != null ? (AbstractType<?>) _item.parent : null;
    }

    public void _integrate(Doc y, Item item) {
        this.doc = y;
        this._item = item;
    }

    public AbstractType<E> _copy() {
        throw new UnsupportedOperationException("method unimplemented");
    }

    public AbstractType<E> clone() {
        throw new UnsupportedOperationException("method unimplemented");
    }

    public void _write(UpdateEncoder encoder) {
    }

    /** The first non-deleted item. */
    public Item _first() {
        Item n = this._start;
        while (n != null && n.deleted()) {
            n = n.right;
        }
        return n;
    }

    /**
     * Creates the type's event and calls type observers. Subtypes override and call super.
     */
    public void _callObserver(Transaction transaction, Set<String> parentSubs) {
        if (!transaction.local && this._searchMarker != null) {
            this._searchMarker.clear();
        }
    }

    public void observe(BiConsumer<E, Transaction> f) {
        EventHandler.addEventHandlerListener(this._eH, f);
    }

    public void observeDeep(BiConsumer<List<YEvent<?>>, Transaction> f) {
        EventHandler.addEventHandlerListener(this._dEH, f);
    }

    public void unobserve(BiConsumer<E, Transaction> f) {
        EventHandler.removeEventHandlerListener(this._eH, f);
    }

    public void unobserveDeep(BiConsumer<List<YEvent<?>>, Transaction> f) {
        EventHandler.removeEventHandlerListener(this._dEH, f);
    }

    public Object toJSON() {
        return null;
    }

    /* ===================== search markers ===================== */

    static void refreshMarkerTimestamp(ArraySearchMarker marker) {
        marker.timestamp = ArraySearchMarker.globalSearchMarkerTimestamp++;
    }

    static void overwriteMarker(ArraySearchMarker marker, Item p, int index) {
        marker.p.setMarker(false);
        marker.p = p;
        p.setMarker(true);
        marker.index = index;
        marker.timestamp = ArraySearchMarker.globalSearchMarkerTimestamp++;
    }

    static ArraySearchMarker markPosition(List<ArraySearchMarker> searchMarker, Item p, int index) {
        if (searchMarker.size() >= MAX_SEARCH_MARKER) {
            ArraySearchMarker marker = searchMarker.get(0);
            for (ArraySearchMarker m : searchMarker) {
                if (m.timestamp < marker.timestamp) {
                    marker = m;
                }
            }
            overwriteMarker(marker, p, index);
            return marker;
        } else {
            ArraySearchMarker pm = new ArraySearchMarker(p, index);
            searchMarker.add(pm);
            return pm;
        }
    }

    public static ArraySearchMarker findMarker(AbstractType<?> yarray, int index) {
        if (yarray._start == null || index == 0 || yarray._searchMarker == null) {
            return null;
        }
        ArraySearchMarker marker = null;
        if (!yarray._searchMarker.isEmpty()) {
            marker = yarray._searchMarker.get(0);
            for (ArraySearchMarker b : yarray._searchMarker) {
                if (Math.abs(index - b.index) < Math.abs(index - marker.index)) {
                    marker = b;
                }
            }
        }
        Item p = yarray._start;
        int pindex = 0;
        if (marker != null) {
            p = marker.p;
            pindex = marker.index;
            refreshMarkerTimestamp(marker);
        }
        while (p.right != null && pindex < index) {
            if (!p.deleted() && p.countable()) {
                if (index < pindex + p.length) {
                    break;
                }
                pindex += p.length;
            }
            p = p.right;
        }
        while (p.left != null && pindex > index) {
            p = p.left;
            if (!p.deleted() && p.countable()) {
                pindex -= p.length;
            }
        }
        while (p.left != null && p.left.id.client == p.id.client && p.left.id.clock + p.left.length == p.id.clock) {
            p = p.left;
            if (!p.deleted() && p.countable()) {
                pindex -= p.length;
            }
        }
        if (marker != null && Math.abs(marker.index - pindex) < ((AbstractType<?>) p.parent)._length / (double) MAX_SEARCH_MARKER) {
            overwriteMarker(marker, p, pindex);
            return marker;
        } else {
            return markPosition(yarray._searchMarker, p, pindex);
        }
    }

    public static void updateMarkerChanges(List<ArraySearchMarker> searchMarker, int index, int len) {
        for (int i = searchMarker.size() - 1; i >= 0; i--) {
            ArraySearchMarker m = searchMarker.get(i);
            if (len > 0) {
                Item p = m.p;
                p.setMarker(false);
                while (p != null && (p.deleted() || !p.countable())) {
                    p = p.left;
                    if (p != null && !p.deleted() && p.countable()) {
                        m.index -= p.length;
                    }
                }
                if (p == null || p.marker()) {
                    searchMarker.remove(i);
                    continue;
                }
                m.p = p;
                p.setMarker(true);
            }
            if (index < m.index || (len > 0 && index == m.index)) {
                m.index = Math.max(index, m.index + len);
            }
        }
    }

    /* ===================== list helpers ===================== */

    public static List<Item> getTypeChildren(AbstractType<?> t) {
        if (t.doc == null) {
            warnPrematureAccess();
        }
        Item s = t._start;
        List<Item> arr = new ArrayList<>();
        while (s != null) {
            arr.add(s);
            s = s.right;
        }
        return arr;
    }

    public static void callTypeObservers(AbstractType<?> type, Transaction transaction, YEvent<?> event) {
        AbstractType<?> changedType = type;
        Map<AbstractType<?>, List<YEvent<?>>> changedParentTypes = transaction.changedParentTypes;
        while (true) {
            changedParentTypes.computeIfAbsent(type, k -> new ArrayList<>()).add(event);
            if (type._item == null) {
                break;
            }
            type = (AbstractType<?>) type._item.parent;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        EventHandler eh = changedType._eH;
        EventHandler.callEventHandlerListeners(eh, event, transaction);
    }

    public static List<Object> typeListSlice(AbstractType<?> type, int start, int end) {
        if (type.doc == null) {
            warnPrematureAccess();
        }
        if (start < 0) {
            start = type._length + start;
        }
        if (end < 0) {
            end = type._length + end;
        }
        int len = end - start;
        List<Object> cs = new ArrayList<>();
        Item n = type._start;
        while (n != null && len > 0) {
            if (n.countable() && !n.deleted()) {
                List<Object> c = n.content.getContent();
                if (c.size() <= start) {
                    start -= c.size();
                } else {
                    for (int i = start; i < c.size() && len > 0; i++) {
                        cs.add(c.get(i));
                        len--;
                    }
                    start = 0;
                }
            }
            n = n.right;
        }
        return cs;
    }

    public static List<Object> typeListToArray(AbstractType<?> type) {
        if (type.doc == null) {
            warnPrematureAccess();
        }
        List<Object> cs = new ArrayList<>();
        Item n = type._start;
        while (n != null) {
            if (n.countable() && !n.deleted()) {
                cs.addAll(n.content.getContent());
            }
            n = n.right;
        }
        return cs;
    }

    public static List<Object> typeListToArraySnapshot(AbstractType<?> type, Snapshot snapshot) {
        List<Object> cs = new ArrayList<>();
        Item n = type._start;
        while (n != null) {
            if (n.countable() && Snapshot.isVisible(n, snapshot)) {
                cs.addAll(n.content.getContent());
            }
            n = n.right;
        }
        return cs;
    }

    /** Callback for {@link #typeListForEach}. */
    public interface ListForEachFn {
        void apply(Object value, int index, AbstractType<?> type);
    }

    /** Callback for {@link #typeListMap}. */
    public interface ListMapFn<R> {
        R apply(Object value, int index, AbstractType<?> type);
    }

    public static void typeListForEach(AbstractType<?> type, ListForEachFn f) {
        int index = 0;
        Item n = type._start;
        if (type.doc == null) {
            warnPrematureAccess();
        }
        while (n != null) {
            if (n.countable() && !n.deleted()) {
                List<Object> c = n.content.getContent();
                for (Object o : c) {
                    f.apply(o, index++, type);
                }
            }
            n = n.right;
        }
    }

    public static <R> List<R> typeListMap(AbstractType<?> type, ListMapFn<R> f) {
        List<R> result = new ArrayList<>();
        typeListForEach(type, (c, i, t) -> result.add(f.apply(c, i, t)));
        return result;
    }

    public static Iterator<Object> typeListCreateIterator(AbstractType<?> type) {
        return new Iterator<>() {
            Item n = type._start;
            List<Object> currentContent = null;
            int currentContentIndex = 0;
            Object nextValue;
            boolean computed = false;
            boolean done = false;

            private void advance() {
                if (computed) {
                    return;
                }
                computed = true;
                if (currentContent == null) {
                    while (n != null && n.deleted()) {
                        n = n.right;
                    }
                    if (n == null) {
                        done = true;
                        return;
                    }
                    currentContent = n.content.getContent();
                    currentContentIndex = 0;
                    n = n.right;
                }
                nextValue = currentContent.get(currentContentIndex++);
                if (currentContent.size() <= currentContentIndex) {
                    currentContent = null;
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return !done;
            }

            @Override
            public Object next() {
                advance();
                if (done) {
                    throw new NoSuchElementException();
                }
                computed = false;
                return nextValue;
            }
        };
    }

    public static void typeListForEachSnapshot(AbstractType<?> type, ListForEachFn f, Snapshot snapshot) {
        int index = 0;
        Item n = type._start;
        while (n != null) {
            if (n.countable() && Snapshot.isVisible(n, snapshot)) {
                List<Object> c = n.content.getContent();
                for (Object o : c) {
                    f.apply(o, index++, type);
                }
            }
            n = n.right;
        }
    }

    public static Object typeListGet(AbstractType<?> type, int index) {
        if (type.doc == null) {
            warnPrematureAccess();
        }
        ArraySearchMarker marker = findMarker(type, index);
        Item n = type._start;
        if (marker != null) {
            n = marker.p;
            index -= marker.index;
        }
        for (; n != null; n = n.right) {
            if (!n.deleted() && n.countable()) {
                if (index < n.length) {
                    return n.content.getContent().get(index);
                }
                index -= n.length;
            }
        }
        return null;
    }

    public static void typeListInsertGenericsAfter(Transaction transaction, AbstractType<?> parent, Item referenceItem, List<Object> content) {
        final Item[] left = {referenceItem};
        Doc doc = transaction.doc;
        long ownClientId = doc.clientID;
        StructStore store = doc.store;
        Item right = referenceItem == null ? parent._start : referenceItem.right;
        final List<Object>[] jsonContent = new List[]{new ArrayList<>()};
        Runnable packJsonContent = () -> {
            if (!jsonContent[0].isEmpty()) {
                Item it = new Item(createID(ownClientId, getState(store, ownClientId)), left[0],
                        left[0] != null ? left[0].lastId() : null, right, right != null ? right.id : null,
                        parent, null, new ContentAny(jsonContent[0]));
                it.integrate(transaction, 0);
                left[0] = it;
                jsonContent[0] = new ArrayList<>();
            }
        };
        for (Object c : content) {
            if (c == null) {
                jsonContent[0].add(null);
            } else if (c instanceof byte[] bytes) {
                packJsonContent.run();
                Item it = new Item(createID(ownClientId, getState(store, ownClientId)), left[0],
                        left[0] != null ? left[0].lastId() : null, right, right != null ? right.id : null,
                        parent, null, new ContentBinary(bytes));
                it.integrate(transaction, 0);
                left[0] = it;
            } else if (c instanceof Doc d) {
                packJsonContent.run();
                Item it = new Item(createID(ownClientId, getState(store, ownClientId)), left[0],
                        left[0] != null ? left[0].lastId() : null, right, right != null ? right.id : null,
                        parent, null, new ContentDoc(d));
                it.integrate(transaction, 0);
                left[0] = it;
            } else if (c instanceof AbstractType<?> at) {
                packJsonContent.run();
                Item it = new Item(createID(ownClientId, getState(store, ownClientId)), left[0],
                        left[0] != null ? left[0].lastId() : null, right, right != null ? right.id : null,
                        parent, null, new ContentType(at));
                it.integrate(transaction, 0);
                left[0] = it;
            } else {
                // Number, Boolean, String, List, Map, etc.
                jsonContent[0].add(c);
            }
        }
        packJsonContent.run();
    }

    public static void typeListInsertGenerics(Transaction transaction, AbstractType<?> parent, int index, List<Object> content) {
        if (index > parent._length) {
            throw new RuntimeException("Length exceeded!");
        }
        if (index == 0) {
            if (parent._searchMarker != null) {
                updateMarkerChanges(parent._searchMarker, index, content.size());
            }
            typeListInsertGenericsAfter(transaction, parent, null, content);
            return;
        }
        int startIndex = index;
        ArraySearchMarker marker = findMarker(parent, index);
        Item n = parent._start;
        if (marker != null) {
            n = marker.p;
            index -= marker.index;
            if (index == 0) {
                n = n.prev();
                index += (n != null && n.countable() && !n.deleted()) ? n.length : 0;
            }
        }
        for (; n != null; n = n.right) {
            if (!n.deleted() && n.countable()) {
                if (index <= n.length) {
                    if (index < n.length) {
                        getItemCleanStart(transaction, createID(n.id.client, n.id.clock + index));
                    }
                    break;
                }
                index -= n.length;
            }
        }
        if (parent._searchMarker != null) {
            updateMarkerChanges(parent._searchMarker, startIndex, content.size());
        }
        typeListInsertGenericsAfter(transaction, parent, n, content);
    }

    public static void typeListPushGenerics(Transaction transaction, AbstractType<?> parent, List<Object> content) {
        Item startP = parent._start;
        int maxIndex = 0;
        Item markerP = startP;
        if (parent._searchMarker != null) {
            for (ArraySearchMarker m : parent._searchMarker) {
                if (m.index > maxIndex) {
                    maxIndex = m.index;
                    markerP = m.p;
                }
            }
        }
        Item n = markerP;
        if (n != null) {
            while (n.right != null) {
                n = n.right;
            }
        }
        typeListInsertGenericsAfter(transaction, parent, n, content);
    }

    public static void typeListDelete(Transaction transaction, AbstractType<?> parent, int index, int length) {
        if (length == 0) {
            return;
        }
        int startIndex = index;
        int startLength = length;
        ArraySearchMarker marker = findMarker(parent, index);
        Item n = parent._start;
        if (marker != null) {
            n = marker.p;
            index -= marker.index;
        }
        for (; n != null && index > 0; n = n.right) {
            if (!n.deleted() && n.countable()) {
                if (index < n.length) {
                    getItemCleanStart(transaction, createID(n.id.client, n.id.clock + index));
                }
                index -= n.length;
            }
        }
        while (length > 0 && n != null) {
            if (!n.deleted()) {
                if (length < n.length) {
                    getItemCleanStart(transaction, createID(n.id.client, n.id.clock + length));
                }
                n.delete(transaction);
                length -= n.length;
            }
            n = n.right;
        }
        if (length > 0) {
            throw new RuntimeException("Length exceeded!");
        }
        if (parent._searchMarker != null) {
            updateMarkerChanges(parent._searchMarker, startIndex, -startLength + length);
        }
    }

    /* ===================== map helpers ===================== */

    public static void typeMapDelete(Transaction transaction, AbstractType<?> parent, String key) {
        Item c = parent._map.get(key);
        if (c != null) {
            c.delete(transaction);
        }
    }

    public static void typeMapSet(Transaction transaction, AbstractType<?> parent, String key, Object value) {
        Item left = parent._map.get(key);
        Doc doc = transaction.doc;
        long ownClientId = doc.clientID;
        dev.yjs.structs.AbstractContent content;
        if (value == null) {
            List<Object> single = new ArrayList<>();
            single.add(null);
            content = new ContentAny(single);
        } else if (value instanceof byte[] bytes) {
            content = new ContentBinary(bytes);
        } else if (value instanceof Doc d) {
            content = new ContentDoc(d);
        } else if (value instanceof AbstractType<?> at) {
            content = new ContentType(at);
        } else {
            List<Object> single = new ArrayList<>();
            single.add(value);
            content = new ContentAny(single);
        }
        new Item(createID(ownClientId, getState(doc.store, ownClientId)), left,
                left != null ? left.lastId() : null, null, null, parent, key, content)
                .integrate(transaction, 0);
    }

    public static Object typeMapGet(AbstractType<?> parent, String key) {
        if (parent.doc == null) {
            warnPrematureAccess();
        }
        Item val = parent._map.get(key);
        return val != null && !val.deleted() ? val.content.getContent().get(val.length - 1) : null;
    }

    public static Map<String, Object> typeMapGetAll(AbstractType<?> parent) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (parent.doc == null) {
            warnPrematureAccess();
        }
        parent._map.forEach((key, value) -> {
            if (!value.deleted()) {
                res.put(key, value.content.getContent().get(value.length - 1));
            }
        });
        return res;
    }

    public static boolean typeMapHas(AbstractType<?> parent, String key) {
        if (parent.doc == null) {
            warnPrematureAccess();
        }
        Item val = parent._map.get(key);
        return val != null && !val.deleted();
    }

    public static Object typeMapGetSnapshot(AbstractType<?> parent, String key, Snapshot snapshot) {
        Item v = parent._map.get(key);
        while (v != null && (!snapshot.sv.containsKey(v.id.client) || v.id.clock >= snapshot.sv.getOrDefault(v.id.client, 0L))) {
            v = v.left;
        }
        return v != null && Snapshot.isVisible(v, snapshot) ? v.content.getContent().get(v.length - 1) : null;
    }

    public static Map<String, Object> typeMapGetAllSnapshot(AbstractType<?> parent, Snapshot snapshot) {
        Map<String, Object> res = new LinkedHashMap<>();
        parent._map.forEach((key, value) -> {
            Item v = value;
            while (v != null && (!snapshot.sv.containsKey(v.id.client) || v.id.clock >= snapshot.sv.getOrDefault(v.id.client, 0L))) {
                v = v.left;
            }
            if (v != null && Snapshot.isVisible(v, snapshot)) {
                res.put(key, v.content.getContent().get(v.length - 1));
            }
        });
        return res;
    }

    public static Iterator<Map.Entry<String, Item>> createMapIterator(AbstractType<?> type) {
        if (type.doc == null) {
            warnPrematureAccess();
        }
        Iterator<Map.Entry<String, Item>> base = type._map.entrySet().iterator();
        return new Iterator<>() {
            Map.Entry<String, Item> nextEntry;
            boolean fetched = false;

            private void fetch() {
                if (fetched) {
                    return;
                }
                fetched = true;
                nextEntry = null;
                while (base.hasNext()) {
                    Map.Entry<String, Item> e = base.next();
                    if (!e.getValue().deleted()) {
                        nextEntry = e;
                        break;
                    }
                }
            }

            @Override
            public boolean hasNext() {
                fetch();
                return nextEntry != null;
            }

            @Override
            public Map.Entry<String, Item> next() {
                fetch();
                if (nextEntry == null) {
                    throw new NoSuchElementException();
                }
                fetched = false;
                return nextEntry;
            }
        };
    }
}

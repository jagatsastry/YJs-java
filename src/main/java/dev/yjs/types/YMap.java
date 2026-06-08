package dev.yjs.types;

import dev.yjs.structs.Item;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A shared Map implementation. Port of src/types/YMap.js.
 *
 * @param <T> the value type
 */
public class YMap<T> extends AbstractType<YMapEvent<T>> implements Iterable<Map.Entry<String, T>> {
    /** Y type ref id (YMapRefID). */
    public static final int Y_MAP_REF_ID = 1;

    /**
     * Content stored before the type is integrated into a document. {@code null} after integration.
     * Preserves insertion order.
     */
    public Map<String, Object> _prelimContent;

    public YMap() {
        super();
        this._prelimContent = new LinkedHashMap<>();
    }

    /**
     * Construct a new YMap initialized from the given entries (insertion order preserved).
     *
     * @param entries an iterable of [key, value] entries to initialize the YMap
     */
    public YMap(Iterable<? extends Map.Entry<String, ?>> entries) {
        super();
        this._prelimContent = new LinkedHashMap<>();
        if (entries != null) {
            for (Map.Entry<String, ?> e : entries) {
                this._prelimContent.put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Construct a new YMap initialized from the given map (insertion order preserved).
     *
     * @param entries a map to initialize the YMap
     */
    public YMap(Map<String, ?> entries) {
        super();
        this._prelimContent = new LinkedHashMap<>();
        if (entries != null) {
            this._prelimContent.putAll(entries);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void _integrate(Doc y, Item item) {
        super._integrate(y, item);
        this._prelimContent.forEach((key, value) -> this.set(key, (T) value));
        this._prelimContent = null;
    }

    @Override
    public YMap<T> _copy() {
        return new YMap<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public YMap<T> clone() {
        YMap<T> map = new YMap<>();
        this.forEach((value, key, m) ->
                map.set(key, value instanceof AbstractType ? (T) ((AbstractType<?>) value).clone() : value));
        return map;
    }

    @Override
    public void _callObserver(Transaction transaction, Set<String> parentSubs) {
        callTypeObservers(this, transaction, new YMapEvent<>(this, transaction, parentSubs));
    }

    @Override
    public Object toJSON() {
        if (this.doc == null) {
            warnPrematureAccess();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        this._map.forEach((key, item) -> {
            if (!item.deleted()) {
                Object v = item.content.getContent().get(item.length - 1);
                map.put(key, v instanceof AbstractType ? ((AbstractType<?>) v).toJSON() : v);
            }
        });
        return map;
    }

    /**
     * Returns the size of the YMap (count of key/value pairs).
     *
     * @return the number of non-deleted entries
     */
    public int size() {
        int count = 0;
        Iterator<Map.Entry<String, Item>> it = createMapIterator(this);
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }

    /**
     * Returns the keys for each element in the YMap.
     *
     * @return an iterator over the keys
     */
    public Iterator<String> keys() {
        return mapIterator(createMapIterator(this), e -> e.getKey());
    }

    /**
     * Returns the values for each element in the YMap.
     *
     * @return an iterator over the values
     */
    @SuppressWarnings("unchecked")
    public Iterator<T> values() {
        return mapIterator(createMapIterator(this),
                e -> (T) e.getValue().content.getContent().get(e.getValue().length - 1));
    }

    /**
     * Returns an iterator of [key, value] pairs.
     *
     * @return an iterator over the entries
     */
    @SuppressWarnings("unchecked")
    public Iterator<Map.Entry<String, T>> entries() {
        return mapIterator(createMapIterator(this),
                e -> new AbstractMap.SimpleImmutableEntry<>(e.getKey(),
                        (T) e.getValue().content.getContent().get(e.getValue().length - 1)));
    }

    /** Callback for {@link #forEach}. */
    public interface MapForEachFn<T> {
        void apply(T value, String key, YMap<T> map);
    }

    /**
     * Executes a provided function once on every key-value pair.
     *
     * @param f a function to execute on every element of this YMap
     */
    @SuppressWarnings("unchecked")
    public void forEach(MapForEachFn<T> f) {
        if (this.doc == null) {
            warnPrematureAccess();
        }
        this._map.forEach((key, item) -> {
            if (!item.deleted()) {
                f.apply((T) item.content.getContent().get(item.length - 1), key, this);
            }
        });
    }

    @Override
    public Iterator<Map.Entry<String, T>> iterator() {
        return this.entries();
    }

    /**
     * Removes a specified element from this YMap.
     *
     * @param key the key of the element to remove
     */
    public void delete(String key) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                typeMapDelete(transaction, this, key);
            }, null, true);
        } else {
            this._prelimContent.remove(key);
        }
    }

    /**
     * Adds or updates an element with a specified key and value.
     *
     * @param key   the key of the element to add to this YMap
     * @param value the value of the element to add
     * @return the value
     */
    public T set(String key, T value) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                typeMapSet(transaction, this, key, value);
            }, null, true);
        } else {
            this._prelimContent.put(key, value);
        }
        return value;
    }

    /**
     * Returns a specified element from this YMap.
     *
     * @param key the key
     * @return the value, or {@code null} if absent
     */
    @SuppressWarnings("unchecked")
    public T get(String key) {
        return (T) typeMapGet(this, key);
    }

    /**
     * Returns whether the specified key exists.
     *
     * @param key the key to test
     * @return {@code true} if the key exists and is not deleted
     */
    public boolean has(String key) {
        return typeMapHas(this, key);
    }

    /**
     * Removes all elements from this YMap.
     */
    public void clear() {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                this.forEach((value, key, map) -> typeMapDelete(transaction, map, key));
            }, null, true);
        } else {
            this._prelimContent.clear();
        }
    }

    @Override
    public void _write(UpdateEncoder encoder) {
        encoder.writeTypeRef(Y_MAP_REF_ID);
    }

    public static YMap<?> read(UpdateDecoder decoder) {
        return new YMap<>();
    }

    /* ===================== helpers ===================== */

    private static <R> Iterator<R> mapIterator(Iterator<Map.Entry<String, Item>> base,
                                               Function<Map.Entry<String, Item>, R> mapper) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return base.hasNext();
            }

            @Override
            public R next() {
                return mapper.apply(base.next());
            }
        };
    }
}

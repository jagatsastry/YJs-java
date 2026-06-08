package dev.yjs.types;

import dev.yjs.structs.Item;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A shared Array implementation. Port of src/types/YArray.js.
 *
 * @param <T> the element type
 */
public class YArray<T> extends AbstractType<YArrayEvent<T>> implements Iterable<T> {
    /** Y type ref id (YArrayRefID). */
    public static final int Y_ARRAY_REF_ID = 0;

    /**
     * Content stored before the type is integrated into a document. {@code null} after integration.
     */
    public List<Object> _prelimContent;

    public YArray() {
        super();
        this._prelimContent = new ArrayList<>();
        this._searchMarker = new ArrayList<>();
    }

    /**
     * Construct a new YArray containing the specified items.
     *
     * @param items the items to insert
     * @return a new YArray
     */
    public static <T> YArray<T> from(List<? extends T> items) {
        YArray<T> a = new YArray<>();
        a.push(new ArrayList<Object>(items));
        return a;
    }

    @Override
    public void _integrate(Doc y, Item item) {
        super._integrate(y, item);
        this.insert(0, this._prelimContent);
        this._prelimContent = null;
    }

    @Override
    public YArray<T> _copy() {
        return new YArray<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public YArray<T> clone() {
        YArray<T> arr = new YArray<>();
        List<Object> cloned = new ArrayList<>();
        for (Object el : this.toArray()) {
            cloned.add(el instanceof AbstractType ? ((AbstractType<?>) el).clone() : el);
        }
        arr.insert(0, cloned);
        return arr;
    }

    /**
     * The number of elements in this YArray.
     */
    public int length() {
        if (this.doc == null) {
            warnPrematureAccess();
        }
        return this._length;
    }

    @Override
    public void _callObserver(Transaction transaction, Set<String> parentSubs) {
        super._callObserver(transaction, parentSubs);
        callTypeObservers(this, transaction, new YArrayEvent<>(this, transaction));
    }

    /**
     * Inserts new content at an index.
     *
     * <p>Important: this function expects a list of content, not a single content object. Inserting
     * several elements is very efficient when done as a single operation.
     *
     * @param index   the index to insert content at
     * @param content the list of content
     */
    public void insert(int index, List<? extends Object> content) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                typeListInsertGenerics(transaction, this, index, new ArrayList<>(content));
            }, null, true);
        } else {
            this._prelimContent.addAll(index, content);
        }
    }

    /**
     * Appends content to this YArray.
     *
     * @param content list of content to append
     */
    public void push(List<? extends Object> content) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                typeListPushGenerics(transaction, this, new ArrayList<>(content));
            }, null, true);
        } else {
            this._prelimContent.addAll(content);
        }
    }

    /**
     * Prepends content to this YArray.
     *
     * @param content list of content to prepend
     */
    public void unshift(List<? extends Object> content) {
        this.insert(0, content);
    }

    /**
     * Deletes elements starting from an index.
     *
     * @param index  index at which to start deleting elements
     * @param length the number of elements to remove
     */
    public void delete(int index, int length) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                typeListDelete(transaction, this, index, length);
            }, null, true);
        } else {
            // splice(index, length): remove `length` elements starting at `index`
            int end = Math.min(index + length, this._prelimContent.size());
            for (int i = end - 1; i >= index; i--) {
                this._prelimContent.remove(i);
            }
        }
    }

    /**
     * Deletes a single element at the given index.
     *
     * @param index index of the element to remove
     */
    public void delete(int index) {
        this.delete(index, 1);
    }

    /**
     * Returns the i-th element from this YArray.
     *
     * @param index the index of the element to return
     * @return the element at the given index
     */
    @SuppressWarnings("unchecked")
    public T get(int index) {
        return (T) typeListGet(this, index);
    }

    /**
     * Transforms this YArray to a Java List.
     *
     * @return a list with all elements
     */
    @SuppressWarnings("unchecked")
    public List<T> toArray() {
        return (List<T>) (List<?>) typeListToArray(this);
    }

    /**
     * Returns a portion of this YArray as a Java List selected from start to end (end not included).
     *
     * @param start the start index
     * @param end   the end index (exclusive)
     * @return the sublist
     */
    @SuppressWarnings("unchecked")
    public List<T> slice(int start, int end) {
        return (List<T>) (List<?>) typeListSlice(this, start, end);
    }

    /**
     * Returns a portion of this YArray as a Java List, from start to the end of the array.
     *
     * @param start the start index
     * @return the sublist
     */
    public List<T> slice(int start) {
        return this.slice(start, this.length());
    }

    /**
     * Returns a copy of this YArray as a Java List.
     *
     * @return the full list
     */
    public List<T> slice() {
        return this.slice(0, this.length());
    }

    @Override
    public Object toJSON() {
        return this.map((c, i, t) -> c instanceof AbstractType ? ((AbstractType<?>) c).toJSON() : c);
    }

    /**
     * Returns a List with the result of calling a provided function on every element of this YArray.
     *
     * @param f   function that produces an element of the new list
     * @param <M> the result element type
     * @return a new list with each element being the result of the callback function
     */
    public <M> List<M> map(ListMapFn<M> f) {
        return typeListMap(this, f);
    }

    /**
     * Executes a provided function once on every element of this YArray.
     *
     * @param f a function to execute on every element of this YArray
     */
    public void forEach(ListForEachFn f) {
        typeListForEach(this, f);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<T> iterator() {
        return (Iterator<T>) typeListCreateIterator(this);
    }

    @Override
    public void _write(UpdateEncoder encoder) {
        encoder.writeTypeRef(Y_ARRAY_REF_ID);
    }

    public static YArray<?> read(UpdateDecoder decoder) {
        return new YArray<>();
    }
}

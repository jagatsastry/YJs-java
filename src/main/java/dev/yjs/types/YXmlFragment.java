package dev.yjs.types;

import dev.yjs.structs.Item;
import dev.yjs.utils.Doc;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Represents a list of {@link YXmlElement} and {@link YXmlText} types. A YXmlFragment is similar to
 * a {@link YXmlElement}, but it does not have a nodeName and it does not have attributes.
 * Port of src/types/YXmlFragment.js.
 */
public class YXmlFragment extends AbstractType<YXmlEvent> {
    /** Y type ref id (YXmlFragmentRefID). */
    public static final int Y_XML_FRAGMENT_REF_ID = 4;

    /**
     * Content stored before the type is integrated into a document. {@code null} after integration.
     */
    public List<Object> _prelimContent;

    public YXmlFragment() {
        super();
        this._prelimContent = new ArrayList<>();
    }

    /**
     * The first child of this fragment, or {@code null} if it is empty.
     */
    public Object firstChild() {
        Item first = this._first();
        return first != null ? first.content.getContent().get(0) : null;
    }

    @Override
    public void _integrate(Doc y, Item item) {
        super._integrate(y, item);
        this.insert(0, this._prelimContent);
        this._prelimContent = null;
    }

    @Override
    public YXmlFragment _copy() {
        return new YXmlFragment();
    }

    @Override
    @SuppressWarnings("unchecked")
    public YXmlFragment clone() {
        YXmlFragment el = new YXmlFragment();
        List<Object> cloned = new ArrayList<>();
        for (Object item : this.toArray()) {
            cloned.add(item instanceof AbstractType ? ((AbstractType<?>) item).clone() : item);
        }
        el.insert(0, cloned);
        return el;
    }

    /**
     * The number of children in this fragment.
     */
    public int length() {
        if (this.doc == null) {
            warnPrematureAccess();
        }
        return this._prelimContent == null ? this._length : this._prelimContent.size();
    }

    /**
     * Create a subtree of childNodes.
     *
     * @param filter function that is called on each child element and returns a Boolean indicating
     *               whether the child is to be included in the subtree
     * @return a subtree and a position within it
     */
    public YXmlTreeWalker createTreeWalker(Predicate<AbstractType<?>> filter) {
        return new YXmlTreeWalker(this, filter);
    }

    /**
     * Returns the first YXmlElement that matches the query. Similar to DOM's querySelector.
     *
     * <p>Query support: tagname.
     *
     * @param query the query on the children
     * @return the first element that matches the query or {@code null}
     */
    public Object querySelector(String query) {
        final String q = query.toUpperCase();
        YXmlTreeWalker iterator = new YXmlTreeWalker(this, element -> {
            String nodeName = nodeNameOf(element);
            return nodeName != null && nodeName.toUpperCase().equals(q);
        });
        if (iterator.hasNext()) {
            return iterator.next();
        } else {
            return null;
        }
    }

    /**
     * Returns all YXmlElements that match the query. Similar to DOM's querySelectorAll.
     *
     * @param query the query on the children
     * @return the elements that match this query
     */
    public List<Object> querySelectorAll(String query) {
        final String q = query.toUpperCase();
        List<Object> result = new ArrayList<>();
        YXmlTreeWalker iterator = new YXmlTreeWalker(this, element -> {
            String nodeName = nodeNameOf(element);
            return nodeName != null && nodeName.toUpperCase().equals(q);
        });
        for (AbstractType<?> el : iterator) {
            result.add(el);
        }
        return result;
    }

    /** JS reads {@code element.nodeName}; only YXmlElement defines it (truthy check in JS). */
    private static String nodeNameOf(AbstractType<?> element) {
        return element instanceof YXmlElement ? ((YXmlElement) element).nodeName : null;
    }

    @Override
    public void _callObserver(Transaction transaction, Set<String> parentSubs) {
        callTypeObservers(this, transaction, new YXmlEvent(this, parentSubs, transaction));
    }

    /**
     * Get the string representation of all the children of this YXmlFragment.
     *
     * @return the string representation of all children
     */
    @Override
    public String toString() {
        return String.join("", typeListMap(this, (xml, i, t) -> xml.toString()));
    }

    @Override
    public Object toJSON() {
        return this.toString();
    }

    /**
     * Inserts new content at an index.
     *
     * @param index   the index to insert content at
     * @param content the array of content
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
     * Inserts new content after a reference item.
     *
     * @param ref     the reference item (an Item, a YXml type, or {@code null} to insert at the start)
     * @param content the array of content
     */
    public void insertAfter(Object ref, List<? extends Object> content) {
        if (this.doc != null) {
            Transaction.transact(this.doc, transaction -> {
                Item refItem = (ref instanceof AbstractType) ? ((AbstractType<?>) ref)._item : (Item) ref;
                typeListInsertGenericsAfter(transaction, this, refItem, new ArrayList<>(content));
            }, null, true);
        } else {
            List<Object> pc = this._prelimContent;
            int index = ref == null ? 0 : pc.indexOf(ref) + 1;
            if (index == 0 && ref != null) {
                throw new RuntimeException("Reference item not found");
            }
            pc.addAll(index, content);
        }
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
     * Transforms this fragment to a Java List.
     *
     * @return a list with all children
     */
    public List<Object> toArray() {
        return typeListToArray(this);
    }

    /**
     * Appends content to this fragment.
     *
     * @param content list of content to append
     */
    public void push(List<? extends Object> content) {
        this.insert(this.length(), content);
    }

    /**
     * Prepends content to this fragment.
     *
     * @param content list of content to prepend
     */
    public void unshift(List<? extends Object> content) {
        this.insert(0, content);
    }

    /**
     * Returns the i-th child of this fragment.
     *
     * @param index the index of the child to return
     * @return the child at the given index
     */
    public Object get(int index) {
        return typeListGet(this, index);
    }

    /**
     * Returns a portion of this fragment as a Java List selected from start to end (end not
     * included).
     *
     * @param start the start index
     * @param end   the end index (exclusive)
     * @return the sublist
     */
    public List<Object> slice(int start, int end) {
        return typeListSlice(this, start, end);
    }

    /**
     * Returns a portion of this fragment from start to the end.
     *
     * @param start the start index
     * @return the sublist
     */
    public List<Object> slice(int start) {
        return this.slice(start, this.length());
    }

    /**
     * Returns a copy of this fragment as a Java List.
     *
     * @return the full list
     */
    public List<Object> slice() {
        return this.slice(0, this.length());
    }

    /**
     * Executes a provided function once on every child element.
     *
     * @param f a function to execute on every child of this fragment
     */
    public void forEach(ListForEachFn f) {
        typeListForEach(this, f);
    }

    @Override
    public void _write(UpdateEncoder encoder) {
        encoder.writeTypeRef(Y_XML_FRAGMENT_REF_ID);
    }

    public static YXmlFragment read(UpdateDecoder decoder) {
        return new YXmlFragment();
    }
}

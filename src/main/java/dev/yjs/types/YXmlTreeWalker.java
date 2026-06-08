package dev.yjs.types;

import dev.yjs.structs.ContentType;
import dev.yjs.structs.Item;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * Represents a subset of the nodes of a {@link YXmlElement} / {@link YXmlFragment} and a position
 * within them. Port of {@code YXmlTreeWalker} in src/types/YXmlFragment.js.
 *
 * <p>Can be created with {@link YXmlFragment#createTreeWalker(Predicate)}.
 */
public class YXmlTreeWalker implements Iterable<AbstractType<?>>, Iterator<AbstractType<?>> {
    private final Predicate<AbstractType<?>> _filter;
    private final AbstractType<?> _root;
    private Item _currentNode;
    private boolean _firstCall;

    /**
     * @param root the root fragment or element to walk
     */
    public YXmlTreeWalker(AbstractType<?> root) {
        this(root, t -> true);
    }

    /**
     * @param root the root fragment or element to walk
     * @param f    a filter applied to each child type
     */
    public YXmlTreeWalker(AbstractType<?> root, Predicate<AbstractType<?>> f) {
        this._filter = f == null ? (t -> true) : f;
        this._root = root;
        this._currentNode = root._start;
        this._firstCall = true;
        if (root.doc == null) {
            AbstractType.warnPrematureAccess();
        }
    }

    @Override
    public Iterator<AbstractType<?>> iterator() {
        return this;
    }

    private AbstractType<?> _nextValue = null;
    private boolean _nextComputed = false;
    private boolean _done = false;

    private void compute() {
        if (_nextComputed) {
            return;
        }
        _nextComputed = true;
        Item n = this._currentNode;
        AbstractType<?> type = (n != null && n.content != null) ? ((ContentType) n.content).type : null;
        if (n != null && (!this._firstCall || n.deleted() || !this._filter.test(type))) { // if first call, we check if we can use the first item
            do {
                type = ((ContentType) n.content).type;
                if (!n.deleted() && (type.getClass() == YXmlElement.class || type.getClass() == YXmlFragment.class) && type._start != null) {
                    // walk down in the tree
                    n = type._start;
                } else {
                    // walk right or up in the tree
                    while (n != null) {
                        Item nxt = n.next();
                        if (nxt != null) {
                            n = nxt;
                            break;
                        } else if (n.parent == this._root) {
                            n = null;
                        } else {
                            n = ((AbstractType<?>) n.parent)._item;
                        }
                    }
                }
            } while (n != null && (n.deleted() || !this._filter.test(((ContentType) n.content).type)));
        }
        this._firstCall = false;
        if (n == null) {
            this._done = true;
            this._nextValue = null;
            return;
        }
        this._currentNode = n;
        this._nextValue = ((ContentType) n.content).type;
    }

    @Override
    public boolean hasNext() {
        compute();
        return !_done;
    }

    @Override
    public AbstractType<?> next() {
        compute();
        if (_done) {
            throw new NoSuchElementException();
        }
        _nextComputed = false;
        return _nextValue;
    }
}

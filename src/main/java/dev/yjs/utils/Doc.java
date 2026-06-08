package dev.yjs.utils;

import dev.yjs.lib0.Observable;
import dev.yjs.structs.ContentDoc;
import dev.yjs.structs.Item;
import dev.yjs.types.AbstractType;
import dev.yjs.types.YArray;
import dev.yjs.types.YMap;
import dev.yjs.types.YText;
import dev.yjs.types.YXmlElement;
import dev.yjs.types.YXmlFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A Yjs document: the root of all shared state. Port of src/utils/Doc.js.
 */
public class Doc extends Observable {
    public static long generateNewClientId() {
        return (long) (Math.random() * 4294967296.0);
    }

    public boolean gc;
    public Predicate<Item> gcFilter;
    public long clientID;
    public String guid;
    public String collectionid;
    public final Map<String, AbstractType<?>> share = new LinkedHashMap<>();
    public final StructStore store = new StructStore();
    public Transaction _transaction = null;
    public List<Transaction> _transactionCleanups = new ArrayList<>();
    public final Set<Doc> subdocs = new LinkedHashSet<>();
    /** Defined iff this document is a subdocument. */
    public Item _item = null;
    public boolean shouldLoad;
    public boolean autoLoad;
    public Object meta;
    public boolean isLoaded = false;
    public boolean isSynced = false;
    public boolean isDestroyed = false;

    public static final class Options {
        public String guid = null;
        public String collectionid = null;
        public boolean gc = true;
        public Predicate<Item> gcFilter = item -> true;
        public Object meta = null;
        public boolean autoLoad = false;
        public boolean shouldLoad = true;

        public Options guid(String g) { this.guid = g; return this; }
        public Options gc(boolean g) { this.gc = g; return this; }
        public Options gcFilter(Predicate<Item> f) { this.gcFilter = f; return this; }
        public Options collectionid(String c) { this.collectionid = c; return this; }
        public Options meta(Object m) { this.meta = m; return this; }
        public Options autoLoad(boolean a) { this.autoLoad = a; return this; }
        public Options shouldLoad(boolean s) { this.shouldLoad = s; return this; }
    }

    public Doc() {
        this(new Options());
    }

    public Doc(Options opts) {
        this.gc = opts.gc;
        this.gcFilter = opts.gcFilter;
        this.clientID = generateNewClientId();
        this.guid = opts.guid != null ? opts.guid : UUID.randomUUID().toString();
        this.collectionid = opts.collectionid;
        this.shouldLoad = opts.shouldLoad;
        this.autoLoad = opts.autoLoad;
        this.meta = opts.meta;
        this.on("sync", args -> {
            Object isSyncedArg = args.length > 0 ? args[0] : null;
            boolean syncedVal = isSyncedArg == null || Boolean.TRUE.equals(isSyncedArg);
            this.isSynced = syncedVal;
            if (this.isSynced && !this.isLoaded) {
                this.emit("load", new Object[]{this});
            }
        });
        this.on("load", args -> this.isLoaded = true);
    }

    public void load() {
        Item item = this._item;
        if (item != null && !this.shouldLoad) {
            Transaction.transact(((AbstractType<?>) item.parent).doc, transaction -> {
                transaction.subdocsLoaded.add(this);
            }, null, true);
        }
        this.shouldLoad = true;
    }

    public Set<Doc> getSubdocs() {
        return subdocs;
    }

    public Set<String> getSubdocGuids() {
        Set<String> s = new LinkedHashSet<>();
        for (Doc d : subdocs) {
            s.add(d.guid);
        }
        return s;
    }

    public <T> T transactWithResult(Function<Transaction, T> f, Object origin) {
        return Transaction.transactWithResult(this, f, origin, true);
    }

    public <T> T transactWithResult(Function<Transaction, T> f) {
        return transactWithResult(f, null);
    }

    public void transact(Consumer<Transaction> f, Object origin) {
        Transaction.transact(this, f, origin, true);
    }

    public void transact(Consumer<Transaction> f) {
        transact(f, null);
    }

    /**
     * Define / fetch a shared type. Repeated calls with the same name return the same instance.
     */
    @SuppressWarnings("unchecked")
    public <T extends AbstractType<?>> T get(String name, Supplier<T> constructor, Class<? extends AbstractType> klass) {
        AbstractType<?> type = share.get(name);
        if (type == null) {
            T t = constructor.get();
            t._integrate(this, null);
            share.put(name, t);
            return t;
        }
        Class<?> constr = type.getClass();
        if (klass != AbstractType.class && constr != klass) {
            if (constr == AbstractType.class) {
                T t = constructor.get();
                t._map = type._map;
                type._map.forEach((k, n) -> {
                    for (Item nn = n; nn != null; nn = nn.left) {
                        nn.parent = t;
                    }
                });
                t._start = type._start;
                for (Item n = t._start; n != null; n = n.right) {
                    n.parent = t;
                }
                t._length = type._length;
                share.put(name, t);
                t._integrate(this, null);
                return t;
            } else {
                throw new RuntimeException("Type with the name " + name + " has already been defined with a different constructor");
            }
        }
        return (T) type;
    }

    public AbstractType<?> get(String name) {
        return get(name, AbstractType::new, AbstractType.class);
    }

    @SuppressWarnings("unchecked")
    public <T> YArray<T> getArray(String name) {
        return (YArray<T>) get(name, YArray::new, YArray.class);
    }

    public <T> YArray<T> getArray() {
        return getArray("");
    }

    public YText getText(String name) {
        return get(name, YText::new, YText.class);
    }

    public YText getText() {
        return getText("");
    }

    @SuppressWarnings("unchecked")
    public <T> YMap<T> getMap(String name) {
        return (YMap<T>) get(name, YMap::new, YMap.class);
    }

    public <T> YMap<T> getMap() {
        return getMap("");
    }

    public YXmlElement getXmlElement(String name) {
        return get(name, YXmlElement::new, YXmlElement.class);
    }

    public YXmlElement getXmlElement() {
        return getXmlElement("");
    }

    public YXmlFragment getXmlFragment(String name) {
        return get(name, YXmlFragment::new, YXmlFragment.class);
    }

    public YXmlFragment getXmlFragment() {
        return getXmlFragment("");
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> doc = new LinkedHashMap<>();
        share.forEach((key, value) -> doc.put(key, value.toJSON()));
        return doc;
    }

    @Override
    public void destroy() {
        this.isDestroyed = true;
        new ArrayList<>(subdocs).forEach(Doc::destroy);
        Item item = this._item;
        if (item != null) {
            this._item = null;
            ContentDoc content = (ContentDoc) item.content;
            Doc.Options opts = content.opts != null ? content.opts : new Doc.Options();
            opts.guid = this.guid;
            opts.shouldLoad = false;
            content.doc = new Doc(opts);
            content.doc._item = item;
            Transaction.transact(((AbstractType<?>) item.parent).doc, transaction -> {
                Doc d = content.doc;
                if (!item.deleted()) {
                    transaction.subdocsAdded.add(d);
                }
                transaction.subdocsRemoved.add(this);
            }, null, true);
        }
        this.emit("destroy", new Object[]{this});
        super.destroy();
    }
}

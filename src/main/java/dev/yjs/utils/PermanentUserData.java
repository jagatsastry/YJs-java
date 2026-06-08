package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.types.YArray;
import dev.yjs.types.YArrayEvent;
import dev.yjs.types.YMap;
import dev.yjs.types.YMapEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Maps clientIDs to persistent user descriptions and tracks which delete-sets belong to whom.
 * Port of src/utils/PermanentUserData.js.
 *
 * <p><b>Note:</b> The original implementation relies on {@code setTimeout(fn, 0)} to defer work
 * onto the JS macrotask queue. There is no equivalent event loop here, so deferred work is
 * scheduled on a shared daemon executor (see {@link #scheduler}). Tests that rely on this timing
 * should wait briefly (mirroring the original {@code await promise.wait(10)}).
 */
public class PermanentUserData {

    /** Shared daemon scheduler used to emulate {@code setTimeout(fn, 0)}. */
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "yjs-permanent-user-data");
                t.setDaemon(true);
                return t;
            });

    public final YMap<Object> yusers;
    public final Doc doc;
    /** Maps from clientid to userDescription. */
    public final Map<Long, String> clients = new LinkedHashMap<>();
    /** Maps from userDescription to its accumulated DeleteSet. */
    public final Map<String, DeleteSet> dss = new LinkedHashMap<>();

    public PermanentUserData(Doc doc) {
        this(doc, doc.getMap("users"));
    }

    @SuppressWarnings("unchecked")
    public PermanentUserData(Doc doc, YMap<?> storeType) {
        this.yusers = (YMap<Object>) storeType;
        this.doc = doc;

        // observe users
        this.yusers.observe((YMapEvent<Object> event, Transaction tr) -> {
            event.keysChanged.forEach(userDescription ->
                    initUser((YMap<Object>) this.yusers.get(userDescription), userDescription));
        });
        // add initial data
        this.yusers.forEach((value, key, map) -> initUser((YMap<Object>) value, key));
    }

    @SuppressWarnings("unchecked")
    private void initUser(YMap<Object> user, String userDescription) {
        YArray<Object> ds = (YArray<Object>) user.get("ds");
        YArray<Object> ids = (YArray<Object>) user.get("ids");
        java.util.function.Consumer<Object> addClientId = clientid ->
                this.clients.put(((Number) clientid).longValue(), userDescription);
        ds.observe((YArrayEvent<Object> event, Transaction tr) -> {
            event.changes().added.forEach(item ->
                    item.content.getContent().forEach(encodedDs -> {
                        if (encodedDs instanceof byte[] bytes) {
                            this.dss.put(userDescription, DeleteSet.mergeDeleteSets(Arrays.asList(
                                    this.dss.getOrDefault(userDescription, DeleteSet.createDeleteSet()),
                                    DeleteSet.readDeleteSet(new DSDecoderV1(new Decoder(bytes))))));
                        }
                    }));
        });
        List<DeleteSet> decoded = ds.map((c, i, t) ->
                DeleteSet.readDeleteSet(new DSDecoderV1(new Decoder((byte[]) c))));
        this.dss.put(userDescription, DeleteSet.mergeDeleteSets(decoded));
        ids.observe((YArrayEvent<Object> event, Transaction tr) ->
                event.changes().added.forEach(item -> item.content.getContent().forEach(addClientId)));
        ids.forEach((value, index, type) -> addClientId.accept(value));
    }

    /** Configuration for {@link #setUserMapping}. */
    public static final class Conf {
        /** Filter that decides whether a transaction's delete-set should be persisted. */
        public BiFunction<Transaction, DeleteSet, Boolean> filter = (tr, ds) -> true;

        public Conf filter(BiFunction<Transaction, DeleteSet, Boolean> f) { this.filter = f; return this; }
    }

    public void setUserMapping(Doc doc, long clientid, String userDescription) {
        setUserMapping(doc, clientid, userDescription, new Conf());
    }

    @SuppressWarnings("unchecked")
    public void setUserMapping(Doc doc, long clientid, String userDescription, Conf conf) {
        BiFunction<Transaction, DeleteSet, Boolean> filter = conf.filter;
        YMap<Object> users = this.yusers;
        // `user` is reassigned when the underlying YMap entry is overwritten; hold it in a 1-element array.
        final YMap<Object>[] user = new YMap[]{(YMap<Object>) users.get(userDescription)};
        if (user[0] == null) {
            YMap<Object> u = new YMap<>();
            u.set("ids", new YArray<Object>());
            u.set("ds", new YArray<Object>());
            users.set(userDescription, u);
            user[0] = u;
        }
        ((YArray<Object>) user[0].get("ids")).push(Arrays.asList((Object) clientid));
        users.observe((YMapEvent<Object> event, Transaction tr) -> scheduler.schedule(() -> {
            YMap<Object> userOverwrite = (YMap<Object>) users.get(userDescription);
            if (userOverwrite != user[0]) {
                // user was overwritten, port all data over to the next user object
                user[0] = userOverwrite;
                this.clients.forEach((cid, ud) -> {
                    if (userDescription.equals(ud)) {
                        ((YArray<Object>) user[0].get("ids")).push(Arrays.asList((Object) cid));
                    }
                });
                DSEncoderV1 encoder = new DSEncoderV1();
                DeleteSet ds = this.dss.get(userDescription);
                if (ds != null) {
                    DeleteSet.writeDeleteSet(encoder, ds);
                    ((YArray<Object>) user[0].get("ds")).push(Arrays.asList((Object) encoder.toUint8Array()));
                }
            }
        }, 0, TimeUnit.MILLISECONDS));
        doc.on("afterTransaction", args -> {
            Transaction transaction = (Transaction) args[0];
            scheduler.schedule(() -> {
                YArray<Object> yds = (YArray<Object>) user[0].get("ds");
                DeleteSet ds = transaction.deleteSet;
                if (transaction.local && ds.clients.size() > 0 && filter.apply(transaction, ds)) {
                    DSEncoderV1 encoder = new DSEncoderV1();
                    DeleteSet.writeDeleteSet(encoder, ds);
                    yds.push(Arrays.asList((Object) encoder.toUint8Array()));
                }
            }, 0, TimeUnit.MILLISECONDS);
        });
    }

    public Object getUserByClientId(long clientid) {
        String d = this.clients.get(clientid);
        return d != null ? d : null;
    }

    public String getUserByDeletedId(ID id) {
        for (Map.Entry<String, DeleteSet> e : this.dss.entrySet()) {
            if (DeleteSet.isDeleted(e.getValue(), id)) {
                return e.getKey();
            }
        }
        return null;
    }
}

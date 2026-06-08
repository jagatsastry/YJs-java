package dev.yjs.test;

import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;
import dev.yjs.lib0.Prng;
import dev.yjs.lib0.RandomGen;
import dev.yjs.structs.AbstractStruct;
import dev.yjs.structs.Item;
import dev.yjs.types.AbstractType;
import dev.yjs.types.YArray;
import dev.yjs.types.YMap;
import dev.yjs.types.YText;
import dev.yjs.types.YXmlElement;
import dev.yjs.utils.DeleteSet;
import dev.yjs.utils.Doc;
import dev.yjs.utils.ID;
import dev.yjs.utils.Snapshot;
import dev.yjs.utils.StructStore;
import dev.yjs.utils.Updates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Multi-client test harness. Port of tests/testHelper.js (+ the V1-only sync behavior it
 * effectively uses). The {@link #compare} convergence check is the core correctness assertion
 * for the random/fuzz tests.
 */
public final class TestHelper {
    private TestHelper() {}

    static void broadcastMessage(TestYInstance y, byte[] m) {
        if (y.tc.onlineConns.contains(y)) {
            for (TestYInstance remote : new ArrayList<>(y.tc.onlineConns)) {
                if (remote != y) {
                    remote._receive(m, y);
                }
            }
        }
    }

    /** A Doc participating in a {@link TestConnector}. */
    public static final class TestYInstance extends Doc {
        public long userID;
        public final TestConnector tc;
        public Map<TestYInstance, List<byte[]>> receiving = new LinkedHashMap<>();
        public final List<byte[]> updates = new ArrayList<>();

        public TestYInstance(TestConnector testConnector, long clientID) {
            super();
            this.userID = clientID;
            this.tc = testConnector;
            testConnector.allConns.add(this);
            this.on("update", args -> {
                byte[] update = (byte[]) args[0];
                Object origin = args.length > 1 ? args[1] : null;
                if (origin != testConnector) {
                    Encoder encoder = Encoding.createEncoder();
                    SyncProtocol.writeUpdate(encoder, update);
                    broadcastMessage(this, Encoding.toUint8Array(encoder));
                }
                this.updates.add(update);
            });
            this.connect();
        }

        public void disconnect() {
            this.receiving = new LinkedHashMap<>();
            this.tc.onlineConns.remove(this);
        }

        public void connect() {
            if (!this.tc.onlineConns.contains(this)) {
                this.tc.onlineConns.add(this);
                Encoder encoder = Encoding.createEncoder();
                SyncProtocol.writeSyncStep1(encoder, this);
                broadcastMessage(this, Encoding.toUint8Array(encoder));
                for (TestYInstance remote : new ArrayList<>(this.tc.onlineConns)) {
                    if (remote != this) {
                        Encoder e2 = Encoding.createEncoder();
                        SyncProtocol.writeSyncStep1(e2, remote);
                        this._receive(Encoding.toUint8Array(e2), remote);
                    }
                }
            }
        }

        public void _receive(byte[] message, TestYInstance remoteClient) {
            this.receiving.computeIfAbsent(remoteClient, k -> new ArrayList<>()).add(message);
        }
    }

    /** Routes messages between {@link TestYInstance}s under control of a PRNG. */
    public static final class TestConnector {
        public final java.util.Set<TestYInstance> allConns = new LinkedHashSet<>();
        public final java.util.Set<TestYInstance> onlineConns = new LinkedHashSet<>();
        public final RandomGen prng;

        public TestConnector(RandomGen gen) {
            this.prng = gen;
        }

        public TestYInstance createY(long clientID) {
            return new TestYInstance(this, clientID);
        }

        public boolean flushRandomMessage() {
            RandomGen gen = this.prng;
            List<TestYInstance> conns = new ArrayList<>();
            for (TestYInstance conn : onlineConns) {
                if (!conn.receiving.isEmpty()) {
                    conns.add(conn);
                }
            }
            if (!conns.isEmpty()) {
                TestYInstance receiver = Prng.oneOf(gen, conns);
                List<Map.Entry<TestYInstance, List<byte[]>>> recvEntries = new ArrayList<>(receiver.receiving.entrySet());
                Map.Entry<TestYInstance, List<byte[]>> picked = Prng.oneOf(gen, recvEntries);
                TestYInstance sender = picked.getKey();
                List<byte[]> messages = picked.getValue();
                byte[] m = messages.remove(0);
                if (messages.isEmpty()) {
                    receiver.receiving.remove(sender);
                }
                Encoder encoder = Encoding.createEncoder();
                SyncProtocol.readSyncMessage(Decoding.createDecoder(m), encoder, receiver, receiver.tc);
                if (Encoding.length(encoder) > 0) {
                    sender._receive(Encoding.toUint8Array(encoder), receiver);
                }
                return true;
            }
            return false;
        }

        public boolean flushAllMessages() {
            boolean didSomething = false;
            while (flushRandomMessage()) {
                didSomething = true;
            }
            return didSomething;
        }

        public void reconnectAll() {
            for (TestYInstance conn : new ArrayList<>(allConns)) {
                conn.connect();
            }
        }

        public void disconnectAll() {
            for (TestYInstance conn : new ArrayList<>(allConns)) {
                conn.disconnect();
            }
        }

        public void syncAll() {
            reconnectAll();
            flushAllMessages();
        }

        public boolean disconnectRandom() {
            if (onlineConns.isEmpty()) {
                return false;
            }
            Prng.oneOf(prng, new ArrayList<>(onlineConns)).disconnect();
            return true;
        }

        public boolean reconnectRandom() {
            List<TestYInstance> reconnectable = new ArrayList<>();
            for (TestYInstance conn : allConns) {
                if (!onlineConns.contains(conn)) {
                    reconnectable.add(conn);
                }
            }
            if (reconnectable.isEmpty()) {
                return false;
            }
            Prng.oneOf(prng, reconnectable).connect();
            return true;
        }
    }

    /** Result of {@link #init}: the connector, the user docs, and their shared types. */
    public static final class Init {
        public TestConnector testConnector;
        public List<TestYInstance> users = new ArrayList<>();
        public List<YArray<Object>> arrays = new ArrayList<>();
        public List<YMap<Object>> maps = new ArrayList<>();
        public List<YText> texts = new ArrayList<>();
        public List<YXmlElement> xmls = new ArrayList<>();
        public List<Object> testObjects = new ArrayList<>();

        public YArray<Object> array(int i) { return arrays.get(i); }
        public YMap<Object> map(int i) { return maps.get(i); }
        public YText text(int i) { return texts.get(i); }
        public YXmlElement xml(int i) { return xmls.get(i); }
    }

    public interface InitTestObject {
        Object apply(TestYInstance y);
    }

    public static Init init(T.TestCase tc, int users) {
        return init(tc, users, null);
    }

    public static Init init(T.TestCase tc, int users, InitTestObject initTestObject) {
        Init result = new Init();
        RandomGen gen = tc.prng;
        Prng.bool(gen); // mirror JS: choose encoding (always falls back to V1)
        TestConnector testConnector = new TestConnector(gen);
        result.testConnector = testConnector;
        for (int i = 0; i < users; i++) {
            TestYInstance y = testConnector.createY(i);
            y.clientID = i;
            result.users.add(y);
            result.arrays.add(y.getArray("array"));
            result.maps.add(y.getMap("map"));
            result.xmls.add(y.getXmlElement("xml"));
            result.texts.add(y.getText("text"));
        }
        testConnector.syncAll();
        for (TestYInstance y : result.users) {
            result.testObjects.add(initTestObject == null ? null : initTestObject.apply(y));
        }
        return result;
    }

    /**
     * Reconnect, flush, merge updates into fresh docs, and assert all users converge to the same
     * array/map/xml/text values, state vectors, delete sets, struct stores and snapshots.
     */
    public static void compare(List<TestYInstance> users) {
        for (TestYInstance u : users) {
            u.connect();
        }
        while (users.get(0).tc.flushAllMessages()) { /* drain */ }

        List<Doc> allDocs = new ArrayList<>(users);
        for (TestYInstance user : users) {
            Doc ydoc = new Doc();
            Updates.applyUpdate(ydoc, dev.yjs.utils.UpdateMerging.mergeUpdates(user.updates), null);
            allDocs.add(ydoc);
        }
        for (Doc u : allDocs) {
            T.assertTrue(u.store.pendingDs == null, "pendingDs must be null");
            T.assertTrue(u.store.pendingStructs == null, "pendingStructs must be null");
        }

        // Array iterator parity on user 0
        List<Object> u0arr = users.get(0).<Object>getArray("array").toArray();
        List<Object> u0iter = new ArrayList<>();
        for (Object o : users.get(0).<Object>getArray("array")) {
            u0iter.add(o);
        }
        T.compare(u0arr, u0iter, "array iterator parity");

        List<Object> userArrayValues = new ArrayList<>();
        List<Object> userMapValues = new ArrayList<>();
        List<Object> userXmlValues = new ArrayList<>();
        List<Object> userTextValues = new ArrayList<>();
        for (Doc u : allDocs) {
            userArrayValues.add(u.<Object>getArray("array").toJSON());
            userMapValues.add(u.<Object>getMap("map").toJSON());
            userXmlValues.add(u.getXmlElement("xml").toString());
            userTextValues.add(u.getText("text").toDelta());
        }

        for (int i = 0; i < allDocs.size() - 1; i++) {
            T.compare(((List<?>) userArrayValues.get(i)).size(), allDocs.get(i).<Object>getArray("array").length());
            T.compare(userArrayValues.get(i), userArrayValues.get(i + 1), "array values converge");
            T.compare(userMapValues.get(i), userMapValues.get(i + 1), "map values converge");
            T.compare(userXmlValues.get(i), userXmlValues.get(i + 1), "xml values converge");
            T.compare(userTextValues.get(i), userTextValues.get(i + 1), "text deltas converge");
            T.compare(Updates.encodeStateVector(allDocs.get(i)), Updates.encodeStateVector(allDocs.get(i + 1)), "state vectors converge");
            T.assertTrue(DeleteSet.equalDeleteSets(
                    DeleteSet.createDeleteSetFromStructStore(allDocs.get(i).store),
                    DeleteSet.createDeleteSetFromStructStore(allDocs.get(i + 1).store)), "delete sets converge");
            compareStructStores(allDocs.get(i).store, allDocs.get(i + 1).store);
            T.compare(Snapshot.encodeSnapshot(Snapshot.snapshot(allDocs.get(i))),
                    Snapshot.encodeSnapshot(Snapshot.snapshot(allDocs.get(i + 1))), "snapshots converge");
        }
        for (Doc u : allDocs) {
            u.destroy();
        }
    }

    static boolean compareItemIDs(Item a, Item b) {
        return a == b || (a != null && b != null && ID.compareIDs(a.id, b.id));
    }

    public static void compareStructStores(StructStore ss1, StructStore ss2) {
        T.assertTrue(ss1.clients.size() == ss2.clients.size(), "client count");
        for (Map.Entry<Long, List<AbstractStruct>> e : ss1.clients.entrySet()) {
            List<AbstractStruct> structs1 = e.getValue();
            List<AbstractStruct> structs2 = ss2.clients.get(e.getKey());
            T.assertTrue(structs2 != null && structs1.size() == structs2.size(), "struct list size");
            for (int i = 0; i < structs1.size(); i++) {
                AbstractStruct s1 = structs1.get(i);
                AbstractStruct s2 = structs2.get(i);
                if (s1.getClass() != s2.getClass()
                        || !ID.compareIDs(s1.id, s2.id)
                        || s1.deleted() != s2.deleted()
                        || s1.length != s2.length) {
                    T.fail("Structs dont match");
                }
                if (s1 instanceof Item it1) {
                    Item it2 = (Item) s2;
                    boolean leftOk = (it1.left == null && it2.left == null)
                            || (it1.left != null && it2.left != null && ID.compareIDs(it1.left.lastId(), it2.left.lastId()));
                    if (!leftOk
                            || !compareItemIDs(it1.right, it2.right)
                            || !ID.compareIDs(it1.origin, it2.origin)
                            || !ID.compareIDs(it1.rightOrigin, it2.rightOrigin)
                            || !java.util.Objects.equals(it1.parentSub, it2.parentSub)) {
                        T.fail("Items dont match");
                    }
                    T.assertTrue(it1.left == null || it1.left.right == it1, "left.right link");
                    T.assertTrue(it1.right == null || it1.right.left == it1, "right.left link");
                    T.assertTrue(it2.left == null || it2.left.right == it2, "left.right link 2");
                    T.assertTrue(it2.right == null || it2.right.left == it2, "right.left link 2");
                }
            }
        }
    }

    public interface RandomMod {
        void apply(TestYInstance y, RandomGen gen, Object testObject);
    }

    public static Init applyRandomTests(T.TestCase tc, RandomMod[] mods, int iterations, InitTestObject initTestObject) {
        RandomGen gen = tc.prng;
        Init result = init(tc, 5, initTestObject);
        TestConnector testConnector = result.testConnector;
        List<TestYInstance> users = result.users;
        for (int i = 0; i < iterations; i++) {
            if (Prng.int32(gen, 0, 100) <= 2) {
                if (Prng.bool(gen)) {
                    testConnector.disconnectRandom();
                } else {
                    testConnector.reconnectRandom();
                }
            } else if (Prng.int32(gen, 0, 100) <= 1) {
                testConnector.flushAllMessages();
            } else if (Prng.int32(gen, 0, 100) <= 50) {
                testConnector.flushRandomMessage();
            }
            int user = Prng.int32(gen, 0, users.size() - 1);
            RandomMod test = Prng.oneOf(gen, mods);
            test.apply(users.get(user), gen, result.testObjects.get(user));
        }
        compare(users);
        return result;
    }
}

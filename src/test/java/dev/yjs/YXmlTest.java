package dev.yjs;

import dev.yjs.test.T;
import dev.yjs.test.TestHelper;
import dev.yjs.types.YEvent;
import dev.yjs.types.YMap;
import dev.yjs.types.YXmlElement;
import dev.yjs.types.YXmlEvent;
import dev.yjs.types.YXmlFragment;
import dev.yjs.types.YXmlText;
import dev.yjs.utils.Doc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of tests/y-xml.tests.js (Yjs v13.6.31).
 */
public class YXmlTest {

    private static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * testCustomTypings is a TypeScript typing-only test (no runtime assertions, just console.log of
     * inferred types). Nothing to port.
     */
    @Test
    void testCustomTypings() {
        // PORT-SKIP: TypeScript typings-only test, no runtime behavior.
    }

    @Test
    void testSetProperty() {
        TestHelper.Init init = TestHelper.init(new T.TestCase(0), 2);
        TestHelper.TestConnector testConnector = init.testConnector;
        List<TestHelper.TestYInstance> users = init.users;
        YXmlElement xml0 = init.xml(0);
        YXmlElement xml1 = init.xml(1);
        xml0.setAttribute("height", "10");
        T.assertTrue("10".equals(xml0.getAttribute("height")), "Simple set+get works");
        testConnector.flushAllMessages();
        T.assertTrue("10".equals(xml1.getAttribute("height")), "Simple set+get works (remote)");
        TestHelper.compare(users);
    }

    @Test
    void testHasProperty() {
        TestHelper.Init init = TestHelper.init(new T.TestCase(0), 2);
        TestHelper.TestConnector testConnector = init.testConnector;
        List<TestHelper.TestYInstance> users = init.users;
        YXmlElement xml0 = init.xml(0);
        YXmlElement xml1 = init.xml(1);
        xml0.setAttribute("height", "10");
        T.assertTrue(xml0.hasAttribute("height"), "Simple set+has works");
        testConnector.flushAllMessages();
        T.assertTrue(xml1.hasAttribute("height"), "Simple set+has works (remote)");

        xml0.removeAttribute("height");
        T.assertTrue(!xml0.hasAttribute("height"), "Simple set+remove+has works");
        testConnector.flushAllMessages();
        T.assertTrue(!xml1.hasAttribute("height"), "Simple set+remove+has works (remote)");
        TestHelper.compare(users);
    }

    @Test
    void testEvents() {
        TestHelper.Init init = TestHelper.init(new T.TestCase(0), 2);
        TestHelper.TestConnector testConnector = init.testConnector;
        List<TestHelper.TestYInstance> users = init.users;
        YXmlElement xml0 = init.xml(0);
        YXmlElement xml1 = init.xml(1);
        final YXmlEvent[] event = {null};
        final YXmlEvent[] remoteEvent = {null};
        xml0.observe((e, tr) -> event[0] = e);
        xml1.observe((e, tr) -> remoteEvent[0] = e);
        xml0.setAttribute("key", "value");
        T.assertTrue(event[0].attributesChanged.contains("key"), "YXmlEvent.attributesChanged on updated key");
        testConnector.flushAllMessages();
        T.assertTrue(remoteEvent[0].attributesChanged.contains("key"), "YXmlEvent.attributesChanged on updated key (remote)");
        // check attributeRemoved
        xml0.removeAttribute("key");
        T.assertTrue(event[0].attributesChanged.contains("key"), "YXmlEvent.attributesChanged on removed attribute");
        testConnector.flushAllMessages();
        T.assertTrue(remoteEvent[0].attributesChanged.contains("key"), "YXmlEvent.attributesChanged on removed attribute (remote)");
        xml0.insert(0, List.of(new YXmlText("some text")));
        T.assertTrue(event[0].childListChanged, "YXmlEvent.childListChanged on inserted element");
        testConnector.flushAllMessages();
        T.assertTrue(remoteEvent[0].childListChanged, "YXmlEvent.childListChanged on inserted element (remote)");
        // test childRemoved
        xml0.delete(0);
        T.assertTrue(event[0].childListChanged, "YXmlEvent.childListChanged on deleted element");
        testConnector.flushAllMessages();
        T.assertTrue(remoteEvent[0].childListChanged, "YXmlEvent.childListChanged on deleted element (remote)");
        TestHelper.compare(users);
    }

    @Test
    void testTreewalker() {
        TestHelper.Init init = TestHelper.init(new T.TestCase(0), 3);
        List<TestHelper.TestYInstance> users = init.users;
        YXmlElement xml0 = init.xml(0);
        YXmlElement paragraph1 = new YXmlElement("p");
        YXmlElement paragraph2 = new YXmlElement("p");
        YXmlText text1 = new YXmlText("init");
        YXmlText text2 = new YXmlText("text");
        paragraph1.insert(0, Arrays.asList(text1, text2));
        xml0.insert(0, Arrays.asList(paragraph1, paragraph2, new YXmlElement("img")));
        List<Object> allParagraphs = xml0.querySelectorAll("p");
        T.assertTrue(allParagraphs.size() == 2, "found exactly two paragraphs");
        T.assertTrue(allParagraphs.get(0) == paragraph1, "querySelectorAll found paragraph1");
        T.assertTrue(allParagraphs.get(1) == paragraph2, "querySelectorAll found paragraph2");
        T.assertTrue(xml0.querySelector("p") == paragraph1, "querySelector found paragraph1");
        TestHelper.compare(users);
    }

    @Test
    void testYtextAttributes() {
        Doc ydoc = new Doc();
        YXmlText ytext = ydoc.get("", YXmlText::new, YXmlText.class);
        final AssertionError[] err = {null};
        ytext.observe((event, tr) -> {
            try {
                YEvent.Change change = event.keys().get("test");
                T.assertTrue(change != null, "change present");
                T.compare(change.action, "add");
                T.compare(change.oldValue, null);
            } catch (AssertionError e) {
                err[0] = e;
            }
        });
        ytext.setAttribute("test", 42L);
        if (err[0] != null) {
            throw err[0];
        }
        T.compare(ytext.getAttribute("test"), 42L);
        T.compare(ytext.getAttributes(), attrs("test", 42L));
    }

    @Test
    void testSiblings() {
        Doc ydoc = new Doc();
        YXmlFragment yxml = ydoc.getXmlFragment();
        YXmlText first = new YXmlText();
        YXmlElement second = new YXmlElement("p");
        yxml.insert(0, Arrays.asList(first, second));
        T.assertTrue(first.nextSibling() == second);
        T.assertTrue(second.prevSibling() == first);
        T.assertTrue(first.parent() == yxml);
        T.assertTrue(yxml.parent() == null);
        T.assertTrue(yxml.firstChild() == first);
    }

    @Test
    void testInsertafter() {
        Doc ydoc = new Doc();
        YXmlFragment yxml = ydoc.getXmlFragment();
        YXmlText first = new YXmlText();
        YXmlElement second = new YXmlElement("p");
        YXmlElement third = new YXmlElement("p");

        YXmlElement deepsecond1 = new YXmlElement("span");
        YXmlText deepsecond2 = new YXmlText();
        second.insertAfter(null, List.of(deepsecond1));
        second.insertAfter(deepsecond1, List.of(deepsecond2));

        yxml.insertAfter(null, Arrays.asList(first, second));
        yxml.insertAfter(second, List.of(third));

        T.assertTrue(yxml.length() == 3);
        T.assertTrue(second.get(0) == deepsecond1);
        T.assertTrue(second.get(1) == deepsecond2);

        T.compareArrays(yxml.toArray(), Arrays.asList(first, second, third));

        boolean threw = false;
        try {
            YXmlElement el = new YXmlElement("p");
            el.insertAfter(deepsecond1, List.of(new YXmlText()));
        } catch (RuntimeException e) {
            threw = true;
        }
        T.assertTrue(threw, "insertAfter with foreign reference throws");
    }

    @Test
    void testClone() {
        Doc ydoc = new Doc();
        YXmlFragment yxml = ydoc.getXmlFragment();
        YXmlText first = new YXmlText("text");
        YXmlElement second = new YXmlElement("p");
        YXmlElement third = new YXmlElement("p");
        yxml.push(Arrays.asList(first, second, third));
        T.compareArrays(yxml.toArray(), Arrays.asList(first, second, third));
        YXmlFragment cloneYxml = yxml.clone();
        ydoc.<Object>getArray("copyarr").insert(0, List.of(cloneYxml));
        T.assertTrue(cloneYxml.length() == 3);
        T.compare(cloneYxml.toJSON(), yxml.toJSON());
    }

    @Test
    void testFormattingBug() {
        Doc ydoc = new Doc();
        YXmlText yxml = ydoc.get("", YXmlText::new, YXmlText.class);
        List<Map<String, Object>> delta = new ArrayList<>();
        delta.add(attrs("insert", "A", "attributes", attrs("em", new LinkedHashMap<>(), "strong", new LinkedHashMap<>())));
        delta.add(attrs("insert", "B", "attributes", attrs("em", new LinkedHashMap<>())));
        delta.add(attrs("insert", "C", "attributes", attrs("em", new LinkedHashMap<>(), "strong", new LinkedHashMap<>())));
        yxml.applyDelta(delta);
        T.compare(yxml.toDelta(), delta);
    }

    @Test
    void testElement() {
        Doc ydoc = new Doc();
        YXmlElement yxmlel = ydoc.getXmlElement();
        YXmlText text1 = new YXmlText("text1");
        YXmlText text2 = new YXmlText("text2");
        yxmlel.insert(0, Arrays.asList(text1, text2));
        T.compareArrays(yxmlel.toArray(), Arrays.asList(text1, text2));
    }
}

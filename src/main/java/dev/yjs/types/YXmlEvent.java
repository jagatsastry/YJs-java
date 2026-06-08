package dev.yjs.types;

import dev.yjs.utils.Transaction;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An Event that describes changes on a YXml Element or YXml Fragment.
 * Port of src/types/YXmlEvent.js.
 */
public class YXmlEvent extends YEvent<AbstractType<?>> {
    /** Whether the children changed. */
    public boolean childListChanged = false;

    /** Set of all changed attributes. */
    public Set<String> attributesChanged = new LinkedHashSet<>();

    /**
     * @param target      the target on which the event is created
     * @param subs        the set of changed attributes; {@code null} is included if the child list
     *                    changed
     * @param transaction the transaction instance with which the change was created
     */
    public YXmlEvent(AbstractType<?> target, Set<String> subs, Transaction transaction) {
        super(target, transaction);
        for (String sub : subs) {
            if (sub == null) {
                this.childListChanged = true;
            } else {
                this.attributesChanged.add(sub);
            }
        }
    }
}

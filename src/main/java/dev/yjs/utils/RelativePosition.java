package dev.yjs.utils;

import dev.yjs.lib0.Decoder;
import dev.yjs.lib0.Decoding;
import dev.yjs.lib0.Encoder;
import dev.yjs.lib0.Encoding;
import dev.yjs.structs.ContentType;
import dev.yjs.structs.Item;
import dev.yjs.types.AbstractType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A relative position is based on the Yjs model and is not affected by document changes.
 * Port of src/utils/RelativePosition.js.
 *
 * <p>E.g. if you place a relative position before a certain character, it will always point to this
 * character. If you place a relative position at the end of a type, it will always point to the end
 * of the type. One of the properties must be defined.
 */
public final class RelativePosition {
    public final ID type;
    public final String tname;
    public final ID item;
    /**
     * A relative position is associated to a specific character. By default {@code assoc >= 0}, the
     * relative position is associated to the character after the meant position. I.e. position 1 in
     * 'ab' is associated to character 'b'. If {@code assoc < 0}, then the relative position is
     * associated to the character before the meant position.
     */
    public final int assoc;

    public RelativePosition(ID type, String tname, ID item, int assoc) {
        this.type = type;
        this.tname = tname;
        this.item = item;
        this.assoc = assoc;
    }

    public RelativePosition(ID type, String tname, ID item) {
        this(type, tname, item, 0);
    }

    /**
     * @return a JSON-ish map mirroring relativePositionToJSON. {@code type}/{@code item} are the
     * {@link ID} objects; {@code assoc} is an {@link Integer}.
     */
    public static Map<String, Object> relativePositionToJSON(RelativePosition rpos) {
        Map<String, Object> json = new LinkedHashMap<>();
        if (rpos.type != null) {
            json.put("type", rpos.type);
        }
        if (rpos.tname != null) {
            json.put("tname", rpos.tname);
        }
        if (rpos.item != null) {
            json.put("item", rpos.item);
        }
        // JS checks `rpos.assoc != null`; assoc is always defined in Java.
        json.put("assoc", rpos.assoc);
        return json;
    }

    @SuppressWarnings("unchecked")
    public static RelativePosition createRelativePositionFromJSON(Map<String, Object> json) {
        Object typeObj = json.get("type");
        ID type = typeObj == null ? null : idFromJSON(typeObj);
        Object tnameObj = json.get("tname");
        String tname = tnameObj == null ? null : (String) tnameObj;
        Object itemObj = json.get("item");
        ID item = itemObj == null ? null : idFromJSON(itemObj);
        Object assocObj = json.get("assoc");
        int assoc = assocObj == null ? 0 : ((Number) assocObj).intValue();
        return new RelativePosition(type, tname, item, assoc);
    }

    @SuppressWarnings("unchecked")
    private static ID idFromJSON(Object o) {
        if (o instanceof ID id) {
            return id;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        return ID.createID(((Number) m.get("client")).longValue(), ((Number) m.get("clock")).longValue());
    }

    public static RelativePosition createRelativePosition(AbstractType<?> type, ID item, int assoc) {
        ID typeid = null;
        String tname = null;
        if (type._item == null) {
            tname = ID.findRootTypeKey(type);
        } else {
            typeid = ID.createID(type._item.id.client, type._item.id.clock);
        }
        return new RelativePosition(typeid, tname, item, assoc);
    }

    /**
     * Create a relativePosition based on an absolute position.
     *
     * @param type  the base type (e.g. YText or YArray)
     * @param index the absolute position
     * @param assoc the association
     * @return the relative position
     */
    public static RelativePosition createRelativePositionFromTypeIndex(AbstractType<?> type, int index, int assoc) {
        Item t = type._start;
        if (assoc < 0) {
            // associated to the left character or the beginning of a type, increment index if possible.
            if (index == 0) {
                return createRelativePosition(type, null, assoc);
            }
            index--;
        }
        while (t != null) {
            if (!t.deleted() && t.countable()) {
                if (t.length > index) {
                    // case 1: found position somewhere in the linked list
                    return createRelativePosition(type, ID.createID(t.id.client, t.id.clock + index), assoc);
                }
                index -= t.length;
            }
            if (t.right == null && assoc < 0) {
                // left-associated position, return last available id
                return createRelativePosition(type, t.lastId(), assoc);
            }
            t = t.right;
        }
        return createRelativePosition(type, null, assoc);
    }

    public static RelativePosition createRelativePositionFromTypeIndex(AbstractType<?> type, int index) {
        return createRelativePositionFromTypeIndex(type, index, 0);
    }

    public static Encoder writeRelativePosition(Encoder encoder, RelativePosition rpos) {
        ID type = rpos.type;
        String tname = rpos.tname;
        ID item = rpos.item;
        int assoc = rpos.assoc;
        if (item != null) {
            Encoding.writeVarUint(encoder, 0);
            ID.writeID(encoder, item);
        } else if (tname != null) {
            // case 2: found position at the end of the list and type is stored in y.share
            Encoding.writeUint8(encoder, 1);
            Encoding.writeVarString(encoder, tname);
        } else if (type != null) {
            // case 3: found position at the end of the list and type is attached to an item
            Encoding.writeUint8(encoder, 2);
            ID.writeID(encoder, type);
        } else {
            throw new IllegalStateException("Unexpected case");
        }
        Encoding.writeVarInt(encoder, assoc);
        return encoder;
    }

    public static byte[] encodeRelativePosition(RelativePosition rpos) {
        Encoder encoder = Encoding.createEncoder();
        writeRelativePosition(encoder, rpos);
        return Encoding.toUint8Array(encoder);
    }

    public static RelativePosition readRelativePosition(Decoder decoder) {
        ID type = null;
        String tname = null;
        ID itemID = null;
        switch ((int) Decoding.readVarUint(decoder)) {
            case 0:
                // case 1: found position somewhere in the linked list
                itemID = ID.readID(decoder);
                break;
            case 1:
                // case 2: found position at the end of the list and type is stored in y.share
                tname = Decoding.readVarString(decoder);
                break;
            case 2:
                // case 3: found position at the end of the list and type is attached to an item
                type = ID.readID(decoder);
                break;
            default:
                break;
        }
        int assoc = Decoding.hasContent(decoder) ? (int) Decoding.readVarInt(decoder) : 0;
        return new RelativePosition(type, tname, itemID, assoc);
    }

    public static RelativePosition decodeRelativePosition(byte[] uint8Array) {
        return readRelativePosition(Decoding.createDecoder(uint8Array));
    }

    private static final class ItemWithOffset {
        final Item item;
        final int diff;

        ItemWithOffset(Item item, int diff) {
            this.item = item;
            this.diff = diff;
        }
    }

    private static ItemWithOffset getItemWithOffset(StructStore store, ID id) {
        Item item = StructStore.getItem(store, id);
        int diff = (int) (id.clock - item.id.clock);
        return new ItemWithOffset(item, diff);
    }

    /**
     * Transform a relative position to an absolute position.
     *
     * <p>If you want to share the relative position with other users, you should set
     * {@code followUndoneDeletions} to false to get consistent results across all clients.
     *
     * @param rpos                  the relative position
     * @param doc                   the document
     * @param followUndoneDeletions whether to follow undone deletions
     * @return the absolute position, or null
     */
    public static AbsolutePosition createAbsolutePositionFromRelativePosition(RelativePosition rpos, Doc doc, boolean followUndoneDeletions) {
        StructStore store = doc.store;
        ID rightID = rpos.item;
        ID typeID = rpos.type;
        String tname = rpos.tname;
        int assoc = rpos.assoc;
        AbstractType<?> type = null;
        int index = 0;
        if (rightID != null) {
            if (StructStore.getState(store, rightID.client) <= rightID.clock) {
                return null;
            }
            Item right;
            int diff;
            if (followUndoneDeletions) {
                Item.FollowRedoneResult res = Item.followRedone(store, rightID);
                right = res.item;
                diff = res.diff;
            } else {
                ItemWithOffset res = getItemWithOffset(store, rightID);
                right = res.item;
                diff = res.diff;
            }
            // right is always an Item here, but mirror the JS instanceof guard.
            if (right == null) {
                return null;
            }
            type = (AbstractType<?>) right.parent;
            if (type._item == null || !type._item.deleted()) {
                index = (right.deleted() || !right.countable()) ? 0 : (diff + (assoc >= 0 ? 0 : 1)); // adjust position based on left association if necessary
                Item n = right.left;
                while (n != null) {
                    if (!n.deleted() && n.countable()) {
                        index += n.length;
                    }
                    n = n.left;
                }
            }
        } else {
            if (tname != null) {
                type = doc.get(tname);
            } else if (typeID != null) {
                if (StructStore.getState(store, typeID.client) <= typeID.clock) {
                    // type does not exist yet
                    return null;
                }
                Item item;
                if (followUndoneDeletions) {
                    item = Item.followRedone(store, typeID).item;
                } else {
                    item = StructStore.getItem(store, typeID);
                }
                if (item != null && item.content instanceof ContentType ct) {
                    type = ct.type;
                } else {
                    // struct is garbage collected
                    return null;
                }
            } else {
                throw new IllegalStateException("Unexpected case");
            }
            if (assoc >= 0) {
                index = type._length;
            } else {
                index = 0;
            }
        }
        return AbsolutePosition.createAbsolutePosition(type, index, rpos.assoc);
    }

    public static AbsolutePosition createAbsolutePositionFromRelativePosition(RelativePosition rpos, Doc doc) {
        return createAbsolutePositionFromRelativePosition(rpos, doc, true);
    }

    public static boolean compareRelativePositions(RelativePosition a, RelativePosition b) {
        return a == b || (
                a != null && b != null
                        && java.util.Objects.equals(a.tname, b.tname)
                        && ID.compareIDs(a.item, b.item)
                        && ID.compareIDs(a.type, b.type)
                        && a.assoc == b.assoc
        );
    }
}

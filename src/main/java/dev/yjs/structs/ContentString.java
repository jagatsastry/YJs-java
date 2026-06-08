package dev.yjs.structs;

import dev.yjs.utils.StructStore;
import dev.yjs.utils.Transaction;
import dev.yjs.utils.UpdateDecoder;
import dev.yjs.utils.UpdateEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * Content holding a string. Length and offsets are UTF-16 code units (JS {@code String.length}).
 * Port of src/structs/ContentString.js.
 */
public final class ContentString extends AbstractContent {
    public String str;

    public ContentString(String str) {
        this.str = str;
    }

    @Override
    public int getLength() {
        return this.str.length();
    }

    @Override
    public List<Object> getContent() {
        List<Object> list = new ArrayList<>(this.str.length());
        for (int i = 0; i < this.str.length(); i++) {
            list.add(String.valueOf(this.str.charAt(i)));
        }
        return list;
    }

    @Override
    public boolean isCountable() {
        return true;
    }

    @Override
    public ContentString copy() {
        return new ContentString(this.str);
    }

    @Override
    public ContentString splice(int offset) {
        ContentString right = new ContentString(this.str.substring(offset));
        this.str = this.str.substring(0, offset);

        // Prevent encoding invalid documents because of splitting of surrogate pairs:
        // https://github.com/yjs/yjs/issues/248
        // JS: const firstCharCode = this.str.charCodeAt(offset - 1)
        // (charCodeAt out of range returns NaN in JS, which fails the surrogate test.)
        if (offset - 1 >= 0 && offset - 1 < this.str.length()) {
            char firstCharCode = this.str.charAt(offset - 1);
            if (firstCharCode >= 0xD800 && firstCharCode <= 0xDBFF) {
                // Last character of the left split is the start of a surrogate utf16/ucs2 pair.
                // We don't support splitting of surrogate pairs because this may lead to invalid
                // documents. Replace the invalid character with a unicode replacement character
                // (U+FFFD).
                this.str = this.str.substring(0, offset - 1) + '�';
                // replace right as well
                right.str = '�' + right.str.substring(1);
            }
        }
        return right;
    }

    @Override
    public boolean mergeWith(AbstractContent right) {
        this.str += ((ContentString) right).str;
        return true;
    }

    @Override
    public void integrate(Transaction transaction, Item item) {
    }

    @Override
    public void delete(Transaction transaction) {
    }

    @Override
    public void gc(StructStore store) {
    }

    @Override
    public void write(UpdateEncoder encoder, int offset) {
        encoder.writeString(offset == 0 ? this.str : this.str.substring(offset));
    }

    @Override
    public int getRef() {
        return 4;
    }

    public static AbstractContent read(UpdateDecoder decoder) {
        return new ContentString(decoder.readString());
    }
}

package dev.yjs.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * General event handler. Port of src/utils/EventHandler.js.
 *
 * @param <A> first callback arg type
 * @param <B> second callback arg type
 */
public final class EventHandler<A, B> {
    public List<BiConsumer<A, B>> l = new ArrayList<>();

    public static <A, B> EventHandler<A, B> createEventHandler() {
        return new EventHandler<>();
    }

    public static <A, B> void addEventHandlerListener(EventHandler<A, B> eventHandler, BiConsumer<A, B> f) {
        eventHandler.l.add(f);
    }

    public static <A, B> void removeEventHandlerListener(EventHandler<A, B> eventHandler, BiConsumer<A, B> f) {
        int len = eventHandler.l.size();
        eventHandler.l.removeIf(g -> g == f);
        if (len == eventHandler.l.size()) {
            System.err.println("[yjs] Tried to remove event handler that doesn't exist.");
        }
    }

    public static <A, B> void removeAllEventHandlerListeners(EventHandler<A, B> eventHandler) {
        eventHandler.l.clear();
    }

    public static <A, B> void callEventHandlerListeners(EventHandler<A, B> eventHandler, A arg0, B arg1) {
        // Iterate over a copy so listeners may modify the handler list.
        for (BiConsumer<A, B> f : new ArrayList<>(eventHandler.l)) {
            f.accept(arg0, arg1);
        }
    }
}

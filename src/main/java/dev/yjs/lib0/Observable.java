package dev.yjs.lib0;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Simple observable. Port of lib0/observable ObservableV2 (runtime behavior).
 *
 * <p>Listeners receive the emitted argument array. Event handlers may add/remove listeners
 * during emit (we iterate over a snapshot).
 */
public class Observable {
    public final Map<String, List<Consumer<Object[]>>> observers = new LinkedHashMap<>();

    public void on(String name, Consumer<Object[]> f) {
        observers.computeIfAbsent(name, k -> new ArrayList<>()).add(f);
    }

    public void once(String name, Consumer<Object[]> f) {
        Consumer<Object[]>[] holder = new Consumer[1];
        holder[0] = args -> {
            off(name, holder[0]);
            f.accept(args);
        };
        on(name, holder[0]);
    }

    public void off(String name, Consumer<Object[]> f) {
        List<Consumer<Object[]>> listeners = observers.get(name);
        if (listeners != null) {
            listeners.removeIf(g -> g == f);
            if (listeners.isEmpty()) {
                observers.remove(name);
            }
        }
    }

    public void emit(String name, Object[] args) {
        List<Consumer<Object[]>> listeners = observers.get(name);
        if (listeners == null) {
            return;
        }
        for (Consumer<Object[]> f : new ArrayList<>(listeners)) {
            f.accept(args);
        }
    }

    /** Whether there is at least one observer registered for {@code name}. */
    public boolean hasObserver(String name) {
        List<Consumer<Object[]>> listeners = observers.get(name);
        return listeners != null && !listeners.isEmpty();
    }

    public void destroy() {
        observers.clear();
    }
}

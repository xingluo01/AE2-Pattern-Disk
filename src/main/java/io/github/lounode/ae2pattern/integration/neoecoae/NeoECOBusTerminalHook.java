package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import appeng.api.networking.IGrid;

/**
 * The bus's terminal view hook, as seen from this side: a handler for the interface the bus declared.
 *
 * <p>Reached through {@link Proxy} rather than by implementing the interface, so that a bus build without the
 * hook simply fails the lookup and leaves the integration running - the same treatment the auxiliary store
 * gets, and the reason the movable-row count travels as an {@code int} instead of as a second interface.</p>
 *
 * <p>No view is kept here. The bus caches what it is handed against the containers' revision, which is where
 * the view wants to live anyway: one view per open session, and released with the bus rather than by this
 * integration. A cache on this side would have to key on buses - keeping every one it was ever asked about
 * alive - and would still need the same revision signal to decide when to rebuild.</p>
 */
final class NeoECOBusTerminalHook implements InvocationHandler {

    private final NeoECOBusAccess.BusHandles handles;

    private NeoECOBusTerminalHook(NeoECOBusAccess.BusHandles handles) {
        this.handles = handles;
    }

    /** @return the handler, or {@code null} when the interface cannot be proxied */
    static Object create(Class<?> hookInterface, NeoECOBusAccess.BusHandles handles) {
        try {
            return Proxy.newProxyInstance(hookInterface.getClassLoader(), new Class<?>[] { hookInterface },
                    new NeoECOBusTerminalHook(handles));
        } catch (IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "NeoECOBusTerminalHook";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }
        Object bus = args == null || args.length == 0 ? null : args[0];
        NeoECOBusTerminalView view = view(bus);
        return switch (method.getName()) {
            // A null view leaves the bus on its own, display-only one, which is the right way to lose this
            // feature: the recipes still show up, they just cannot be taken.
            case "view" -> view;
            // Asked separately by the bus, and it has to answer for the view the terminal will be given: this
            // count is what keeps the appended rows out of the terminal's move-all.
            case "writableRows" -> view == null ? 0 : view.movableRows();
            default -> null;
        };
    }

    private NeoECOBusTerminalView view(Object bus) {
        if (bus == null) {
            return null;
        }
        var slots = NeoECOBusAccess.patternInventory(handles, bus);
        IGrid grid = gridOf(bus);
        if (slots == null || grid == null) {
            return null;
        }
        return new NeoECOBusTerminalView(slots, grid, bus);
    }

    /** @return the bus's grid, or {@code null} while it is unloaded or not yet placed */
    static IGrid gridOf(Object bus) {
        try {
            Object value = bus.getClass().getMethod("getGrid").invoke(bus);
            return value instanceof IGrid grid ? grid : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A bus mid-teardown answers with nothing useful here; standing down beats failing the query.
            return null;
        }
    }
}

package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;

/**
 * The auxiliary pattern store the FD Smart Pattern Bus consults for its pattern disks.
 *
 * <p>NEO ECO's {@code AuxiliaryPatternStore} interface cannot be implemented directly here: its method
 * signatures name the bus block entity, and the bus reaches into LDLib, which this mod deliberately
 * keeps off its compile classpath. A dynamic proxy keeps the compile side clear of the bus while still
 * presenting NEO ECO with a real implementation of its API.</p>
 *
 * <p>A call this integration cannot make sense of answers with its return type's default rather than
 * throwing. The bus invokes the store from inside its insertion and pattern-index paths, where an
 * escaping exception reads as a broken crafting provider instead of as a missing disk feature; an
 * unrecognised method means a NEO ECO newer than this integration.</p>
 */
final class NeoECOAuxiliaryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    /** Callbacks already reported as failing, keyed by callback and cause, so a per-tick callback cannot
     * flood the log while a distinct second fault still shows up. */
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private final NeoECOBusAccess.BusHandles handles;

    private NeoECOAuxiliaryStore(NeoECOBusAccess.BusHandles handles) {
        this.handles = handles;
    }

    /**
     * @return a proxy implementing {@code storeInterface}, or {@code null} when the insertion-result
     *         enum is not shaped the way this integration expects
     */
    @Nullable
    static Object create(Class<?> storeInterface, NeoECOBusAccess.BusHandles handles) {
        if (NeoECOBusAccess.insertionResult("INSERTED") == null
                || NeoECOBusAccess.insertionResult("NO_SPACE") == null) {
            return null;
        }
        NeoECOAuxiliaryStore handler = new NeoECOAuxiliaryStore(handles);
        return Proxy.newProxyInstance(storeInterface.getClassLoader(), new Class<?>[] { storeInterface },
                handler::invoke);
    }

    private Object invoke(Object proxy, Method method, Object[] args) {
        try {
            if (method.getDeclaringClass() == Object.class) {
                return NeoECOBusAccess.handleObjectMethod(proxy, method, args);
            }
            return dispatch(method, args);
        } catch (RuntimeException e) {
            // Answering a default silently here hides a whole class of wiring breakage: the bus asks, gets
            // a default back, and quietly changes behaviour. Report the first failure of each
            // (callback, cause) pair, so a second, unrelated fault stays visible; repeats stay out of the
            // log because the bus polls some callbacks every tick. The set lives per JVM, so the next game
            // launch reports again.
            if (REPORTED_FAILURES.add(method.getName() + ":" + e.getClass().getSimpleName())) {
                LOGGER.warn("[AE2-Pattern-Disk] auxiliary store callback {} threw; it answers with a default "
                        + "for this call, so NEO ECO degrades along that callback's own fallback path",
                        method.getName(), e);
            }
            return typeDefault(method.getReturnType());
        }
    }

    private Object dispatch(Method method, Object[] args) {
        return switch (method.getName()) {
            case "owns" -> NeoECOBusDisks.ownsDisk(itemAt(args, 1));
            case "canAccept" -> NeoECOBusDisks.anyDiskAccepts(handles, objectAt(args, 0), itemAt(args, 1));
            case "hasRoom" -> NeoECOBusDisks.anyDiskHasRoom(handles, objectAt(args, 0));
            case "insert" -> insert(objectAt(args, 0), objectAt(args, 1));
            case "encodedPatterns" -> NeoECOBusDisks.collectEncodedPatterns(handles, objectAt(args, 0));
            case "revision" -> NeoECOBusDisks.diskRevision(handles, objectAt(args, 0));
            // Asked for by the pattern management screens when a container's recipe is taken back out: the
            // store removes it and settles the cost in one step, which is the only place that can.
            case "remove" -> remove(objectAt(args, 0), intAt(args, 1), itemAt(args, 2));
            default -> typeDefault(method.getReturnType());
        };
    }

    /** @return what NEO ECO should see; anything other than INSERTED falls back to the bus's slots. */
    private Object insert(@Nullable Object bus, @Nullable Object prepared) {
        if (bus == null) {
            return NeoECOBusAccess.insertionResult("NO_SPACE");
        }
        ItemStack pattern = NeoECOBusAccess.preparedStack(prepared);
        String outcome = pattern != null && !pattern.isEmpty()
                && NeoECOBusDisks.insertIntoDisk(handles, bus, pattern)
                ? "INSERTED"
                : "NO_SPACE";
        return NeoECOBusAccess.insertionResult(outcome);
    }

    /** @return whether a container gave the pattern back, having settled what that cost */
    private boolean remove(@Nullable Object bus, int diskSlot, ItemStack pattern) {
        if (bus == null || diskSlot < 0 || pattern.isEmpty()) {
            return false;
        }
        return NeoECOBusDisks.removeFromDisk(handles, bus, diskSlot, pattern, NeoECOBusTerminalHook.gridOf(bus));
    }

    @Nullable
    private static Object objectAt(@Nullable Object[] args, int index) {
        return args != null && index >= 0 && index < args.length ? args[index] : null;
    }

    private static ItemStack itemAt(@Nullable Object[] args, int index) {
        return objectAt(args, index) instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static int intAt(@Nullable Object[] args, int index) {
        return objectAt(args, index) instanceof Integer value ? value : -1;
    }

    private static Object typeDefault(Class<?> returnType) {
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (List.class.isAssignableFrom(returnType)) {
            // An empty list reads as "nothing here"; null would make a caller that iterates it fail.
            return List.of();
        }
        return null;
    }
}

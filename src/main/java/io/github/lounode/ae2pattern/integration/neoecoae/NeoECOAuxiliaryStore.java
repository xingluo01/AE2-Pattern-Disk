package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.jetbrains.annotations.Nullable;

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
            // Includes the argument-shape failures a newer NEO ECO could hand us; see the class doc.
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

    @Nullable
    private static Object objectAt(@Nullable Object[] args, int index) {
        return args != null && index >= 0 && index < args.length ? args[index] : null;
    }

    private static ItemStack itemAt(@Nullable Object[] args, int index) {
        return objectAt(args, index) instanceof ItemStack stack ? stack : ItemStack.EMPTY;
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

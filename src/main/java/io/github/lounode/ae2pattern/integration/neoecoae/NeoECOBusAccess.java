package io.github.lounode.ae2pattern.integration.neoecoae;

import java.lang.reflect.Method;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.inventories.InternalInventory;

/**
 * Reflection boundary onto the FD Smart Pattern Bus (NEO ECO {@code v21.1.2} line).
 *
 * <p>The bus block entity reaches into LDLib, which this mod deliberately keeps off its compile
 * classpath, so every call into the bus goes through reflection here. That also lets the integration
 * target one NEO ECO line without pinning the whole mod to it: on a build whose API differs the lookups
 * return {@code null} and the integration logs why it stood down.</p>
 *
 * <p>Handles are resolved once into {@link BusHandles} and passed around, because the store callbacks
 * run inside the bus's own insertion and pattern-index paths - a {@code getMethod} lookup per slot there
 * would dominate the cost of the scan itself.</p>
 */
final class NeoECOBusAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    /** 与客户端能力门控共用同一份名字，免得两侧漂移；见 {@link NeoECOTypes}。 */
    static final String BUS_CLASS = NeoECOTypes.BUS;

    static final String AUXILIARY_STORE_CLASS = NeoECOTypes.AUXILIARY_STORE;

    static final String INSERTION_RESULT_CLASS = NeoECOTypes.INSERTION_RESULT;

    /**
     * The bus methods this integration needs, resolved once.
     *
     * @param patternInventory {@code getTerminalPatternInventory} - the bus's pattern slots, which is
     *                         where its pattern disks live
     * @param level            {@code getLevel} - needed to resolve a disk's locked pattern type
     */
    record BusHandles(Method patternInventory, Method level) {
    }

    private NeoECOBusAccess() {
    }

    /** @return the named class, or {@code null} when this NEO ECO build does not have it. */
    @Nullable
    static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (LinkageError broken) {
            // 类在、但链接不起来：这不是「版本旧」而是「构建坏了」，退场之外还得留一个原因，
            // 否则日志里这两种情形长得一样。
            LOGGER.warn("[AE2-Pattern-Disk] NEO ECO class {} could not be linked", name, broken);
            return null;
        }
    }

    /** @return the named public method, or {@code null} when this NEO ECO build does not have it. */
    @Nullable
    static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** @return the bus's pattern slots, or {@code null} when the accessor is unusable on this bus. */
    @Nullable
    static InternalInventory patternInventory(BusHandles handles, Object bus) {
        return invoke(handles.patternInventory(), bus, InternalInventory.class);
    }

    /** @return the bus's world, or {@code null} while unloaded or not yet placed. */
    @Nullable
    static Level levelOf(BusHandles handles, Object bus) {
        return invoke(handles.level(), bus, Level.class);
    }

    /** @return the encoded pattern inside a prepared pattern, or an empty stack when unavailable. */
    @Nullable
    static ItemStack preparedStack(@Nullable Object prepared) {
        if (prepared == null) {
            return null;
        }
        return invoke(method(prepared.getClass(), "stack"), prepared, ItemStack.class);
    }

    /**
     * @return the insertion-result constant of the given name, or {@code null} when this build has no
     *         such constant
     */
    @Nullable
    static Object insertionResult(String name) {
        Class<?> resultClass = findClass(INSERTION_RESULT_CLASS);
        if (resultClass == null || !resultClass.isEnum()) {
            return null;
        }
        for (Object constant : resultClass.getEnumConstants()) {
            if (constant instanceof Enum<?> value && value.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    /**
     * Answers the {@link Object} methods a proxy may receive (identity, equality, logging) so a hook
     * object behaves sanely if it is ever printed or put into a collection.
     */
    static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            case "toString" -> "ae2_pattern_disk:neoecoae_auxiliary_store";
            default -> null;
        };
    }

    @Nullable
    private static <T> T invoke(@Nullable Method method, @Nullable Object target, Class<T> type) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return type.cast(method.invoke(target));
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}

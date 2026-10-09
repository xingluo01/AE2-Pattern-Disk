package io.github.lounode.ae2pattern.integration.extendedae;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * EAE（ExtendedAE，{@code extendedae}）那枚 ME 虚空元件的软依赖入口：只认字符串与反射，不 import 它的任何类型。
 *
 * <p>与 {@code MegaCellsCompat} 同一做法：EAE 在本模组的构建里只进 {@code localRuntime}（可选邻居），
 * 编译面里不能出现它的名字。</p>
 *
 * <p><b>物质聚合模式是什么</b>：虚空元件把存进去的东西销毁，但可以按模式改产物——销毁、
 * 或按电价聚成物质球、或聚成奇点。模式存在物品的数据组件 {@code EAESingletons.VOID_MODE} 上
 * （枚举 {@code VoidMode}，读写与 EAE 自己的界面一致：{@code ItemStack.set/getOrDefault} + {@code VoidMode.values()[i]}），
 * 所以切换它只要改元件栈本身、再让宿主落盘即可，不涉及它的方块或网络。</p>
 */
public final class VoidCellCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.extendedae");

    public static final String MOD_ID = "extendedae";

    private static final String SINGLETONS = "com.glodblock.github.extendedae.common.EAESingletons";
    private static final String VOID_MODE = "com.glodblock.github.extendedae.api.VoidMode";
    private static final String DATA_COMPONENT_TYPE = "net.minecraft.core.component.DataComponentType";

    private static volatile Boolean present;

    private static volatile boolean resolved;
    @Nullable
    private static Object voidCellItem;
    @Nullable
    private static Object modeComponent;
    private static Object[] modes = new Object[0];
    @Nullable
    private static Method componentGetter;
    @Nullable
    private static Method componentSetter;

    private VoidCellCompat() {}

    /** EAE 是否在场。按钮建不建、刷新要不要跑，都以它为前提。 */
    public static boolean isLoaded() {
        var cached = present;
        if (cached == null) {
            cached = ModList.get().isLoaded(MOD_ID);
            present = cached;
        }
        return cached;
    }

    /**
     * 这张元件是不是 EAE 的 ME 虚空元件。
     *
     * <p>比的是 EAE 自己注册的那个物品实例（{@code EAESingletons.VOID_CELL}）——它是个单例，引用相等就是
     * 最准的判据，也省得去认类名（那个类还可能被附属包一层）。</p>
     */
    public static boolean isVoidCell(ItemStack stack) {
        if (stack.isEmpty() || !isLoaded()) {
            return false;
        }
        resolve();
        return voidCellItem != null && stack.getItem() == voidCellItem;
    }

    /** 模式档数（EAE 那个枚举的长度）；拿不到给 0。 */
    public static int modeCount() {
        resolve();
        return modes.length;
    }

    /** 当前模式在枚举里的序号；不是虚空元件或取不到时给 0（枚举第一档）。 */
    public static int modeOrdinal(ItemStack stack) {
        resolve();
        if (componentGetter == null || modeComponent == null || modes.length == 0) {
            return 0;
        }
        try {
            Object value = componentGetter.invoke(stack, modeComponent, modes[0]);
            return value instanceof Enum<?> mode ? mode.ordinal() : 0;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            LOGGER.debug("[extendedae] void mode read unavailable", unavailable);
            return 0;
        }
    }

    /**
     * 模式的短名（枚举常量的名，转小写）：语言键用它取名，也让「EAE 加了第四档」这种情况自动有键可查
     * （少了哪个键会显示键本身，不会被静默当成别的档）。
     */
    public static String modeKey(int ordinal) {
        resolve();
        if (ordinal < 0 || ordinal >= modes.length) {
            return "unknown";
        }
        return modes[ordinal] instanceof Enum<?> mode ? mode.name().toLowerCase(Locale.ROOT) : "unknown";
    }

    /**
     * 把模式往前推一格（{@code reverse} 为真是往回），写回元件栈。
     *
     * <p>返回是否<b>写成功</b>；没成功时调用方别落盘。写成功不等于客户端下一帧一定画得出新图标——
     * 那要靠随后的广播把新栈发回去。</p>
     */
    public static boolean cycleMode(ItemStack stack, boolean reverse) {
        resolve();
        if (componentSetter == null || modeComponent == null || modes.length == 0 || !isVoidCell(stack)) {
            return false;
        }
        int next = Math.floorMod(modeOrdinal(stack) + (reverse ? -1 : 1), modes.length);
        try {
            componentSetter.invoke(stack, modeComponent, modes[next]);
            return true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            LOGGER.debug("[extendedae] void mode write unavailable", unavailable);
            return false;
        }
    }

    /** 一次把需要的类、字段、方法都取齐；任何一步失败就保持空值，各入口自行短路。 */
    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> singletons = Class.forName(SINGLETONS);
            // 两个字段都解一层包装：现版本（EAE 1.21-2.2.x）它们是裸的 Item / DataComponentType，
            // 而上游主线已改成 glodium 的 Deferred*（那东西实现 Supplier）。加这一层对两种形态都对，
            // 也不用把 NeoForge/glodium 的类型写进编译面。
            voidCellItem = unwrap(staticField(singletons, "VOID_CELL"));
            modeComponent = unwrap(staticField(singletons, "VOID_MODE"));
            modes = Class.forName(VOID_MODE).getEnumConstants();
            Class<?> componentType = Class.forName(DATA_COMPONENT_TYPE);
            componentGetter = ItemStack.class.getMethod("getOrDefault", componentType, Object.class);
            componentSetter = ItemStack.class.getMethod("set", componentType, Object.class);
        } catch (Throwable unavailable) {
            // 类名对不上（EAE 改了名字/版本不符）就当这个功能不在：按钮不存在，其它屏不受影响。
            LOGGER.debug("[extendedae] void cell unavailable", unavailable);
        }
    }

    /**
     * 延迟注册的字段拿到的是个 Supplier（注册前取会拿 null 或抛），把它解开；裸值原样返回。
     *
     * <p>{@code Item} 与 {@code DataComponentType} 都不会是 Supplier，所以解错的可能性不存在。</p>
     */
    private static Object unwrap(@Nullable Object value) {
        return value instanceof java.util.function.Supplier<?> supplier ? supplier.get() : value;
    }

    @Nullable
    private static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getField(name);
        return field.get(null);
    }
}

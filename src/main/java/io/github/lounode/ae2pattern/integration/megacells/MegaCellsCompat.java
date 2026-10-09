package io.github.lounode.ae2pattern.integration.megacells;

import java.lang.reflect.Method;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import appeng.api.storage.StorageCells;

/**
 * MEGA Cells（{@code megacells}）的软依赖入口：只认字符串与反射，不 import 它的任何类型。
 *
 * <p>与 {@code AecsSoftDep} / 万象构序那几个同一做法：MEGA 是运行时才可能存在的邻居（构建里只进
 * {@code localRuntime}），所以编译面里不能出现它的名字。这里要的那几件事都在
 * {@code gripe._90.megacells.item.cell.BulkCellInventory} 上——那是它给「大宗存储元件」用的库存实现，
 * 压缩链的开关与截断物的读写都在它身上（MEGA 自己的元件工作台按钮也是这么调的）。</p>
 *
 * <p><b>压缩截断是什么</b>：装了压缩卡的大宗元件可以启用压缩链，把「压缩后的形态」也当作可存的东西；
 * 「截断」指链上算到哪一级为止。这个按钮就是切那一个值。它需要元件本身在链上（{@code hasCompressionChain}），
 * 否则那个按钮在 MEGA 自己的屏上也是隐藏的。</p>
 */
public final class MegaCellsCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.megacells");

    public static final String MOD_ID = "megacells";

    private static final String BULK_CELL_INVENTORY = "gripe._90.megacells.item.cell.BulkCellInventory";

    private static volatile Boolean present;
    private static Class<?> bulkCellInventory;

    private MegaCellsCompat() {}

    /** 目标模组是否在场。按钮建不建、刷新要不要跑，都以它为前提。 */
    public static boolean isLoaded() {
        var cached = present;
        if (cached == null) {
            cached = ModList.get().isLoaded(MOD_ID);
            present = cached;
        }
        return cached;
    }

    /**
     * 这张元件对应的「大宗库存」；不是 MEGA 的大宗元件、或模组不在场时返回 null。
     *
     * <p>{@code StorageCells.getCellInventory(stack, null)} 是 AE2 自己的入口，第二参传 null 表示调用方
     * 不需要被回调保存——这里只借它拿到那个库存对象，写回由我们的宿主自己落盘。</p>
     */
    @Nullable
    public static Object bulkInventoryOf(ItemStack stack) {
        if (stack.isEmpty() || !isLoaded()) {
            return null;
        }
        var type = bulkType();
        if (type == null) {
            return null;
        }
        try {
            Object cell = StorageCells.getCellInventory(stack, null);
            return type.isInstance(cell) ? cell : null;
        } catch (Throwable absent) {
            return null;
        }
    }

    /** 这个大宗元件是否接在压缩链上；不在链上时那个按钮在 MEGA 自己屏上也是隐藏的。 */
    public static boolean hasCompressionChain(@Nullable Object inventory) {
        return callBoolean(inventory, "hasCompressionChain");
    }

    /** 压缩链是否已启用。 */
    public static boolean isCompressionEnabled(@Nullable Object inventory) {
        return callBoolean(inventory, "isCompressionEnabled");
    }

    /** 当前截断到哪一级（那一级的物品，按钮就画它）。取不到给空栈。 */
    public static ItemStack cutoffItem(@Nullable Object inventory) {
        Object value = call(inventory, "getCutoffItem");
        return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    /**
     * 把截断物往前推一格（{@code reverse} 为真是往回）。
     *
     * <p>返回的是「<b>调用</b>是否成功」（方法存在且没抛），不等于截断物真的换了：调用方拿它 gate 的只是
     * 一次落盘 + 一次广播，那两步幂等，多跑一次无副作用。真要改成「没动就不落盘」得前后比 {@code getCutoffItem}，
     * 而那个 getter 一旦不可用（拿到空栈）就会把「其实动了」误判成「没动」，反而漏掉广播。</p>
     */
    public static boolean switchCutoff(@Nullable Object inventory, boolean reverse) {
        if (inventory == null) {
            return false;
        }
        try {
            Method method = inventory.getClass().getMethod("switchCompressionCutoff", boolean.class);
            method.invoke(inventory, reverse);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            LOGGER.debug("[megacells] switchCompressionCutoff unavailable", absent);
            return false;
        }
    }

    @Nullable
    private static Class<?> bulkType() {
        var cached = bulkCellInventory;
        if (cached == null) {
            try {
                cached = Class.forName(BULK_CELL_INVENTORY);
                bulkCellInventory = cached;
            } catch (Throwable absent) {
                return null;
            }
        }
        return cached;
    }

    private static boolean callBoolean(@Nullable Object target, String name) {
        Object value = call(target, name);
        return value instanceof Boolean flag && flag;
    }

    @Nullable
    private static Object call(@Nullable Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }
}

package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.item.ItemStack;

import appeng.menu.slot.FakeSlot;
import appeng.util.ConfigInventory;
import appeng.api.stacks.GenericStack;

/**
 * 处理样板输入/输出的数量与顺序运算：轮换、倍增、整除。
 *
 * <p>这三个动作的形态一样：只改「已经编码在处理样板槽里的内容」，不碰样板定义、不碰网络。所以它们是纯函数，
 * 跑在服务端那一半（客户端那一半仍留在菜单里发动作包——发包是菜单的事，不是算术的事）。</p>
 *
 * <p><b>倍除是全有或全无</b>：任一非空堆叠除不干净就整体不动，否则玩家会得到一份输入输出对不上的样板——
 * 那种样板比不执行更糟，它会在合成链上以残缺的数量跑起来。</p>
 */
public final class ProcessingOutputMath {

    private ProcessingOutputMath() {
    }

    /** 是否值得轮换：只有一个（或零个）非空项时轮换等于把它清空，不做。输入与输出共用这一判据。 */
    public static boolean canCycle(FakeSlot[] slots) {
        return canCycle(itemsOf(slots));
    }

    /**
     * 轮换已编码的处理槽位：每个位置取它后面第一个非空槽的内容（绕回开头），空槽保持空。
     *
     * <p>输出侧用它把主产物拨回主产物位；输入侧用它调整样板推给机器的先后顺序。两侧共用这一套算术。</p>
     *
     * <p>配方转移把主输出放错槽位时，玩家用它把顺序拨回来，而不是重编码一张样板。</p>
     */
    public static void cycle(FakeSlot[] slots) {
        var next = rotated(itemsOf(slots));
        for (int i = 0; i < next.length; i++) {
            slots[i].set(next[i]);
        }
    }

    /** {@link FakeSlot} 版判据的算术本体：非空项多于一个才值得轮换。 */
    public static boolean canCycle(ItemStack[] current) {
        int filled = 0;
        for (var stack : current) {
            if (!stack.isEmpty()) {
                filled++;
            }
        }
        return filled > 1;
    }

    /**
     * 轮换的算术本体，纯函数（不改入参）：每个非空位置取它后面第一个非空项的内容（绕回开头），空位保持空。
     *
     * <p>槽位视图与样板实际携带的内容并不一致——编码时同物品合并、空槽丢弃，所以这里只保证「非空项之间的
     * 相对顺序」，不试图保留格位。</p>
     */
    public static ItemStack[] rotated(ItemStack[] current) {
        var next = new ItemStack[current.length];
        for (int i = 0; i < current.length; i++) {
            next[i] = ItemStack.EMPTY;
            if (current[i].isEmpty()) {
                continue;
            }
            // 往后找第一个非空项（绕回开头），找不到就留空——这与“空槽不动”是同一件事。
            for (int j = 1; j < current.length; j++) {
                var candidate = current[(i + j) % current.length];
                if (!candidate.isEmpty()) {
                    next[i] = candidate;
                    break;
                }
            }
        }
        return next;
    }

    private static ItemStack[] itemsOf(FakeSlot[] slots) {
        var current = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            current[i] = slots[i].getItem();
        }
        return current;
    }

    /** 按槽位遍历倍乘全部非空堆叠，避免压缩索引错位。 */
    public static void multiply(ConfigInventory inventory, int factor) {
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (stack != null) {
                inventory.setStack(i, new GenericStack(stack.what(), stack.amount() * factor));
            }
        }
    }

    /** 按槽位遍历倍除全部非空堆叠，避免压缩索引错位（调用方需先用 {@link #allDivisible} 确认除得干净）。 */
    public static void divide(ConfigInventory inventory, int factor) {
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (stack != null) {
                inventory.setStack(i, new GenericStack(stack.what(), stack.amount() / factor));
            }
        }
    }

    /** 这些库存里的每一个非空堆叠都能被 {@code factor} 整除。 */
    public static boolean allDivisible(int factor, ConfigInventory... inventories) {
        for (var inventory : inventories) {
            for (int i = 0; i < inventory.size(); i++) {
                var stack = inventory.getStack(i);
                if (stack == null) {
                    continue;
                }
                if (stack.amount() <= 0 || stack.amount() % factor != 0) {
                    return false;
                }
            }
        }
        return true;
    }
}

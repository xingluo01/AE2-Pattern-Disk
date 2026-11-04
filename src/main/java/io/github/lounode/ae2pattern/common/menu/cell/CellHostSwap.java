package io.github.lounode.ae2pattern.common.menu.cell;

import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * 网络里一个元件格的「取出 → 改副本 → 放回」，两个管理域（压缩 / 标记）共用这一条。
 *
 * <p>抽出来是因为两处的收尾写法必须完全一致，而它们曾经各写一遍：写回失败要回填原件，原件也放不回去时
 * <b>掉在玩家脚下</b>——任何一步静默吞掉元件都是丢东西。容器、宿主、可信度的判定顺序也固定在这里：</p>
 *
 * <ol>
 *   <li>宿主键先落到本体（可能在两次点击之间被拆了）；</li>
 *   <li>再问这套宿主收不收这一类元件（ECO 的矩阵躺在 AE2 驱动器里就是这样被挡下的）；</li>
 *   <li>取出原件，改的是 {@code copy()}，原件留着回填；</li>
 *   <li>改完放回；任一步失败都回填原件，回填失败就掉落。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>在入口问收集阶段那份 {@code View#trusted()}：读名单会触发上游 probe，失败时那份视图会被标成
 * 不可信，本来能写的站点就整批被拒（表现为「按了按钮、矩阵还是 0 / 5」）。可信度由写回那一层重新开句柄去判。</p>
 */
public final class CellHostSwap {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger(CellHostSwap.class);

    private CellHostSwap() {
    }

    /**
     * 改写一格元件。
     *
     * @param what   日志里怎么称呼这次改写（「快捷标记」「大宗矩阵」）
     * @param mutate 在副本上写标记；返回 false 表示这次改写不成立（上游拒收、格式不认），原件照原样放回
     * @return 是否真的把改好的元件放了回去
     */
    public static boolean rewrite(CellManagementTermMenu menu, String hostKey, int hostSlot,
            @Nullable ItemStack stack, String what, Predicate<ItemStack> mutate) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Object owner = menu.ownerOf(hostKey);
        if (owner == null || !CellDriveScanner.canAcceptCell(owner, hostSlot, stack)) {
            return false;
        }
        ItemStack taken = CellDriveScanner.extractCell(owner, hostSlot, null);
        if (taken.isEmpty()) {
            // 这台现在不让动（无限存储迁移中、矩阵还没成型），跳过它而不是硬来。
            return false;
        }
        ItemStack modified = taken.copy();
        if (mutate.test(modified) && CellDriveScanner.insertCell(owner, hostSlot, modified)) {
            return true;
        }
        if (!CellDriveScanner.insertCell(owner, hostSlot, taken)) {
            LOGGER.info("[cell-mgmt] {} 写回失败且原件放不回去，已掉在玩家脚下：{}", what, stack.getItem());
            menu.getPlayer().drop(taken, false);
        }
        return false;
    }
}

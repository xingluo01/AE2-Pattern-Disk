package io.github.lounode.ae2pattern.common.menu.slot;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.RestrictedInputSlot;

/**
 * 样板输出栏的槽：编码区与样板磁盘之间的中转，产物能取出来。
 *
 * <p>这一格曾经禁止拿起。理由是整理类 mod 的快捷键直接发 {@code ServerboundContainerClickPacket}
 * （QUICK_MOVE / PICKUP 等），服务端只经 {@code AbstractContainerMenu#clicked} 落到 {@link #mayPickup}，
 * 屏幕拦不住，于是当时用「取不走」来避免整理把产物搬到光标上。</p>
 *
 * <p>现在整理模组的登记已经覆盖本模组全部四个终端屏（{@code InventoryProfilesIntegration} 按菜单类登记 +
 * 四个屏上的 {@code @IPNPlayerSideOnly}），这道闸不必再关着：玩家把自己刚编出来的样板拿走是正常玩法，
 * 与 AE2 原版编码终端一致。</p>
 *
 * <p>编码流程自己不走玩家交互——它直接写 {@code encodingLogic} 持有的 inventory，所以这里开闸不影响
 * 编码与清空。</p>
 */
public class PatternOutputSlot extends RestrictedInputSlot {

    public PatternOutputSlot(InternalInventory inventory, int slot) {
        super(PlacableItemType.ENCODED_PATTERN, inventory, slot);
    }
}

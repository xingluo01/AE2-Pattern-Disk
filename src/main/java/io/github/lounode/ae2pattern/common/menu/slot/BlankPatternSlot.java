package io.github.lounode.ae2pattern.common.menu.slot;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.RestrictedInputSlot;

/**
 * 空白样板槽：终端自己的一格实物存货，最多 {@link #CAPACITY} 张。
 *
 * <p>它曾经是「只读的网络镜像」——槽里什么都不存，只把 ME 网络里有多少空白样板画出来。现在网络只是它的
 * <b>来源</b>：菜单每个 tick 把它补满（见 {@code PatternDiskEncodingTermMenu#refillBlankPatternSlot}），
 * 编码与「从磁盘取回样板」都先扣这一格，槽空了才回头向网络要；玩家也可以直接把自己的空白样板放进来、
 * 或把里面的拿走。</p>
 *
 * <p>为什么要有这层实物：网络里的空白样板摸不到，玩家既看不见「这一刻扣的是哪一份」，也没法给终端备一小份
 * 常用的量。上限因此刻意压低——它是缓冲，不是仓库；备得越多，它越像替玩家囤货的仓库，网络补料与退回的
 * 节奏也就越难讲清。</p>
 */
public class BlankPatternSlot extends RestrictedInputSlot {

    /**
     * 一格最多几张。只约束<b>菜单槽</b>的插入与补料（{@code setStackLimit} 决定 {@code Slot#getMaxStackSize}），
     * 不裁 {@code AppEngInternalInventory} 里已经存着的内容：旧版本存下的整叠会原样留着，玩家取走、或编码
     * 消耗到本上限以下之后，才回到新的补料口径。这样改上限不会顺手销毁玩家已有的空白样板。
     */
    public static final int CAPACITY = 4;

    public BlankPatternSlot(InternalInventory inventory, int slot) {
        super(PlacableItemType.BLANK_PATTERN, inventory, slot);
        setStackLimit(CAPACITY);
    }
}

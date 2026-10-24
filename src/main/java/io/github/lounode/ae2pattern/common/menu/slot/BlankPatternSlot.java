package io.github.lounode.ae2pattern.common.menu.slot;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.RestrictedInputSlot;

/**
 * 空白样板槽：终端自己的一格实体存货，最多一叠。
 *
 * <p>它曾经是「只读的网络镜像」——槽里什么都不存，只把 ME 网络里有多少空白样板画出来。现在网络只是它的
 * <b>来源</b>：菜单每个 tick 把它补满（见 {@code PatternDiskEncodingTermMenu#refillBlankPatternSlot}），
 * 编码与「从磁盘取回样板」都先扣这一格，槽空了才回头向网络要；玩家也可以直接把自己的空白样板放进来、
 * 或把里面的拿走。</p>
 *
 * <p>为什么要有这层实物：网络里的空白样板摸不到，玩家既看不见「这一刻扣的是哪一份」，也没法给终端备一小份
 * 常用的量。一格一叠正好——它是缓冲，不是仓库；满了不会再往里堆。</p>
 */
public class BlankPatternSlot extends RestrictedInputSlot {

    /** 一格一叠：空白样板就是普通可堆叠物品，拆成更小的数只会让玩家多按几下。 */
    public static final int CAPACITY = 64;

    public BlankPatternSlot(InternalInventory inventory, int slot) {
        super(PlacableItemType.BLANK_PATTERN, inventory, slot);
        setStackLimit(CAPACITY);
    }
}

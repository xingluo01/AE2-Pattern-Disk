package io.github.lounode.ae2pattern.common.menu.slot;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.core.definitions.AEItems;

/**
 * 只读的空白样板槽：本身不存东西，只显示 ME 网络里有多少空白样板。
 *
 * <p>数量由菜单在服务端每 tick 写进内部容器，客户端读到的就是同步过来的那一份——槽自己不去查网络，
 * 因为客户端没有网格。玩家既放不进也拿不出；编码时消耗的是网络里的样板，见
 * {@code PatternDiskEncodingTermMenu#encode}。</p>
 */
public class NetworkBlankPatternSlot extends Slot {

    /** 一个格子最多画到 99：再多也只是个数字，没有必要。 */
    private static final int MAX_SHOWN = 99;

    public NetworkBlankPatternSlot() {
        super(new SimpleContainer(1), 0, 0, 0);
    }

    /** 服务端：把当前网络数量写进镜像；客户端靠容器同步读到同一份。 */
    public void updateMirror(long count) {
        var shown = count <= 0
                ? ItemStack.EMPTY
                : AEItems.BLANK_PATTERN.stack((int) Math.min(count, MAX_SHOWN));
        if (!ItemStack.matches(shown, getItem())) {
            set(shown);
        }
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }
}

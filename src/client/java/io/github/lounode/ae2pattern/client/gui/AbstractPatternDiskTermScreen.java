package io.github.lounode.ae2pattern.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.slot.DisabledSlot;

import io.github.lounode.ae2pattern.common.menu.AbstractPatternDiskTermMenu;
import org.jetbrains.annotations.Nullable;

/**
 * 本模组终端的公共屏幕基座：背包里的 Shift+左键只有一个出口。
 *
 * <p>AE2 给这条手势的默认含义是「把物品送进 ME 网络」。本模组的终端分两类：编码终端有物品网格，照旧能收
 * （网络、样板编辑槽都算去处）；管理终端没有网络物品栏，菜单侧那条出口（{@link
 * AbstractPatternDiskTermMenu#transferStackToMenu}）已经关掉。屏幕这层的作用是：物品确实没地方去时给一句
 * 提示，而不是让玩家点了没反应。</p>
 *
 * <p>子类只需回答一个问题：这件物品有没有自己的去处。有（例如存进当前选中的那台驱动器）就接过去并返回
 * {@code true}；返回 {@code false} 时基类再看菜单里有没有真目标槽（或终端本来就接网络），实在没有才提示。</p>
 */
public abstract class AbstractPatternDiskTermScreen<T extends AbstractPatternDiskTermMenu>
        extends MEStorageScreen<T> {

    protected AbstractPatternDiskTermScreen(T menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Override
    protected void slotClicked(@Nullable Slot slot, int slotIdx, int mouseButton, ClickType clickType) {
        if (clickType == ClickType.QUICK_MOVE && slot != null && !(slot instanceof DisabledSlot)
                && !slot.getItem().isEmpty() && getMenu().isPlayerSideSlot(slot)) {
            if (onPlayerInventoryQuickMove(slot)) {
                return; // 子类给这件物品找了去处（例如存进选中的驱动器）
            }
            if (getMenu().wouldHandleQuickMove(slot.getItem())) {
                // 菜单里有真去处（真目标槽，或终端本来就接 ME 网络）：照常走原路，由服务端分配。
                super.slotClicked(slot, slotIdx, mouseButton, clickType);
                return;
            }
            showLocalNotice("gui.ae2_pattern_disk.notice.no_network_quick_move");
            return;
        }
        super.slotClicked(slot, slotIdx, mouseButton, clickType);
    }

    /**
     * 背包里 Shift+左键点到了某件物品：本屏给这件物品找了去处就在这里接过去。
     *
     * @return true 表示已接手（不再提示）；false 表示没地方去，由基类提示一句。
     */
    protected boolean onPlayerInventoryQuickMove(Slot slot) {
        return false;
    }

    /**
     * 一句只给自己看的提示。
     *
     * <p>落聊天栏（{@code false}）而不是动作栏：这类拒绝要能回头看见，动作栏三秒就没了。
     */
    protected static void showLocalNotice(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key), false);
        }
    }
}

package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.storage.ITerminalHost;
import appeng.menu.me.common.MEStorageMenu;

/**
 * 本模组终端的公共菜单基座：快捷移动（Shift+左键）的口径只有这一份。
 *
 * <p>原先每个终端各写一遍「背包里的东西别被塞进 ME 网络」，现在收在这里；编码终端也挂在它下面，只是把
 * 网络出口接了回去。规矩就两条：</p>
 *
 * <ul>
 *   <li><b>只认菜单里的真目标槽。</b>玩家背包的物品先按 AE2 的槽位语义分派（编码槽、升级槽照旧能收）；
 *       一个真槽都分不到时只走 {@link #transferStackToMenu}，不让 AE2 父类那条「塞进第一个空
 *       {@code FakeSlot}」的回退生效——本模组的 FakeSlot 是合成格与处理槽，写进去是一份不消耗原物的鬼影，
 *       还会落到当前编码模式并不在用的槽里。</li>
 *   <li><b>网络出口默认关掉。</b>{@link #hasNetworkQuickMoveTarget} 默认是 false，于是「物品被送进 ME
 *       网络」这条路径在管理终端上根本不存在——Shift+左键背包里的东西不会凭空消失。编码终端要网络
 *       （它本来就有物品网格，送进去看得见），把那两个方法接回父类即可，见
 *       {@link PatternDiskEncodingTermMenu}。</li>
 * </ul>
 */
public abstract class AbstractPatternDiskTermMenu extends MEStorageMenu {

    protected AbstractPatternDiskTermMenu(MenuType<?> menuType, int id, Inventory playerInventory,
            ITerminalHost host) {
        super(menuType, id, playerInventory, host, true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (isClientSide()) {
            return ItemStack.EMPTY; // 与服务端同一口径：这一下不由客户端自己算，等包回来（父类也这么拦）
        }
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY; // 越界的槽号（改造过的客户端）什么都不做
        }
        var source = this.slots.get(index);
        if (!isPlayerSideSlot(source) || source.getItem().isEmpty()) {
            return super.quickMoveStack(player, index);
        }
        if (!source.mayPickup(player)) {
            return ItemStack.EMPTY;
        }

        var stack = source.getItem();
        if (!getQuickMoveDestinationSlots(stack, true).isEmpty()) {
            return super.quickMoveStack(player, index); // 菜单里有真目标槽：交给父类按槽位语义的优先级分配
        }

        // 没有真目标槽：别走父类那条往空 FakeSlot 塞的回退，只走 transferStackToMenu 这条看得见的去处。
        int transferred = transferStackToMenu(stack.copy());
        if (transferred > 0) {
            source.remove(transferred);
        }
        return ItemStack.EMPTY;
    }

    /**
     * 「快捷移动无处可去」的出口：默认什么都不收。管理终端没有网络物品栏，收进去等于把物品从玩家视线里
     * 拿走、又没地方拿回来，也没提示——所以宁可原地不动（屏幕侧会补一句提示）。
     */
    @Override
    protected int transferStackToMenu(ItemStack input) {
        return hasNetworkQuickMoveTarget() ? super.transferStackToMenu(input) : 0;
    }

    /**
     * 本终端的快捷移动有没有 ME 网络出口：管理终端没有（默认 false，物品哪儿也不去），编码终端要
     * （覆盖成 true，让 {@link #transferStackToMenu} 落回 {@code MEStorageMenu} 那条写网络的实现）。
     */
    protected boolean hasNetworkQuickMoveTarget() {
        return false;
    }

    /**
     * 这件物品在菜单里有没有去处（屏幕侧用它决定「背包里 Shift+左键要不要发包」）。
     *
     * <p>有去处就照常发包，让服务端去分配（真目标槽，或本终端本来就接的 ME 网络）；没有去处就别发包，
     * 屏幕侧只提示一句，免得玩家点了没反应。编码终端这个开关是开的，于是永远走前者——它本来就什么都收。</p>
     *
     * <p><b>它会在客户端被调用</b>，所以判定只许用两侧都拿得到的数据（槽位语义、物品类型这类），不能读只有
     * 服务端才有的状态。</p>
     */
    public boolean wouldHandleQuickMove(ItemStack stack) {
        return hasNetworkQuickMoveTarget() || !getQuickMoveDestinationSlots(stack, true).isEmpty();
    }
}

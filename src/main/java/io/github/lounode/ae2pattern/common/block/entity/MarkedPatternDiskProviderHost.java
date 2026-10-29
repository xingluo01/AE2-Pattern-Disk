package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.world.entity.player.Player;

import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;

import io.github.lounode.ae2pattern.common.menu.MarkedPatternDiskProviderMenu;

/**
 * 标记式样板磁盘供应器两种形态共用的宿主契约。
 *
 * <p>与 {@link PatternDiskProviderHost} 同构，只换菜单类型：它自己没有磁盘槽，但对样板访问终端而言仍要
 * 报一个磁盘视图（空），所以 {@link IPatternDiskHost} 一并实现。</p>
 */
public interface MarkedPatternDiskProviderHost extends PatternDiskProviderHost {

    @Override
    default void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(MarkedPatternDiskProviderMenu.TYPE, player, subMenu.getLocator());
    }

    @Override
    default void openMenu(Player player, MenuHostLocator locator) {
        MenuOpener.open(MarkedPatternDiskProviderMenu.TYPE, player, locator);
    }

}

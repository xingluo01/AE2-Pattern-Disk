package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.world.entity.player.Player;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;

import io.github.lounode.ae2pattern.common.menu.pattern.MeteoritePatternProviderMenu;

/**
 * 自装配样板磁盘供应器的宿主契约。
 *
 * <p>
 * 除菜单指向自己的那份、以及把升级库存转出来之外，别处与 {@link PatternDiskProviderHost} 完全相同：磁盘栏、返回栏、
 * 终端可见性都沿用同一套口径。升级库存要在这里转发，是因为 AE2 的 {@code PatternProviderLogicHost} 并不带升级概念
 * （那台机器的 {@code IUpgradeableObject} 默认实现返回的是空库存），而菜单要按它建升级槽。
 * </p>
 */
public interface MeteoritePatternProviderHost extends PatternDiskProviderHost, IUpgradeableObject {

    /**
     * 升级槽归供应器逻辑所有——自装配要读速度卡与陨石超频卡的数量，库存就存在逻辑里；宿主只是把它转出来。
     */
    @Override
    default IUpgradeInventory getUpgrades() {
        if (getLogic() instanceof IUpgradeableObject upgradeable) {
            return upgradeable.getUpgrades();
        }
        return UpgradeInventories.empty();
    }

    @Override
    default void openMenu(Player player, MenuHostLocator locator) {
        MenuOpener.open(MeteoritePatternProviderMenu.TYPE, player, locator);
    }

    @Override
    default void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(MeteoritePatternProviderMenu.TYPE, player, subMenu.getLocator());
    }
}

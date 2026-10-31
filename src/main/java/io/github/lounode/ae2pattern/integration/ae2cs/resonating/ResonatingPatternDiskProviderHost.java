package io.github.lounode.ae2pattern.integration.ae2cs.resonating;

import net.minecraft.world.entity.player.Player;

import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;

import io.github.lounode.ae2cs.common.me.logic.ResonatingPatternProviderHost;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;

/**
 * 谐振样板磁盘供应器的宿主契约：同时满足本模组的磁盘供应器接口与 AECS 的谐振供应器接口。
 *
 * <p>
 * 两边都自带 {@code openMenu}/{@code returnToMainMenu} 的默认实现，签名相同但目标菜单不同，所以这里必须显式
 * 覆写一次——否则编译期就是「两个默认方法冲突」。升级库存沿用 AECS 那份默认实现（转发
 * {@code ResonatingPatternProviderLogic#getUpgrades()}）；磁盘栏、返回栏与终端可见性沿用
 * {@link PatternDiskProviderHost}。
 * </p>
 *
 * <p>
 * 绑定器（{@code ae2cs:resonating_linker}）只认 {@link ResonatingPatternProviderHost}，不认具体方块实体类；
 * 让本接口继承它，绑定、144 槽默认目标与选中槽就都落在同一套公开方法上。
 * </p>
 */
public interface ResonatingPatternDiskProviderHost extends ResonatingPatternProviderHost, PatternDiskProviderHost {

    /** 协变收窄到本设备的逻辑类型：菜单要读本类接管的抽取三档。 */
    @Override
    ResonatingPatternDiskProviderLogic getResonatingLogic();

    @Override
    default void openMenu(Player player, MenuHostLocator locator) {
        MenuOpener.open(ResonatingPatternDiskProviderMenu.TYPE, player, locator);
    }

    @Override
    default void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(ResonatingPatternDiskProviderMenu.TYPE, player, subMenu.getLocator());
    }
}

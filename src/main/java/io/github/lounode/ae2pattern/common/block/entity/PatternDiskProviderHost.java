package io.github.lounode.ae2pattern.common.block.entity;

import net.minecraft.world.entity.player.Player;

import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * The host contract shared by both forms of the ME pattern disk provider: the in-world block
 * ({@link PatternDiskProviderBlockEntity}) and the cable-attached panel part
 * ({@code io.github.lounode.ae2pattern.common.part.PatternDiskProviderPart}).
 *
 * <p>AE2's own pattern provider menu is typed against the interface
 * {@link PatternProviderLogicHost}, which is what lets one menu serve both the block and the part.
 * Doing the same here means {@code PatternDiskProviderMenu} works for both hosts instead of being
 * duplicated, with {@link IPatternDiskHost} adding the disk inventory the menu and the encoding
 * terminal need.</p>
 */
public interface PatternDiskProviderHost extends PatternProviderLogicHost, IPatternDiskHost {

    /**
     * AE2's default returns the player to <em>its</em> pattern provider menu, which lays out the
     * provider's pattern inventory as a real pattern slot grid. That inventory is only a mirror of the
     * disks here, so a player could take patterns off it without touching a disk (a free copy) or put
     * patterns in that no disk backs. Both entry points therefore lead back to this mod's own menu,
     * which matters beyond AE2 itself: other mods (a mirror pattern provider, for one) call
     * {@code openMenu} on any host that implements this interface.
     */
    @Override
    default void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(PatternDiskProviderMenu.TYPE, player, subMenu.getLocator());
    }

    @Override
    default void openMenu(Player player, MenuHostLocator locator) {
        MenuOpener.open(PatternDiskProviderMenu.TYPE, player, locator);
    }

    /**
     * AE2 的 {@code PatternProviderLogicHost} 已经是 {@code PatternContainer}，直接转发它自己的实现——即 AE2 在
     * 供应器界面上那个「在样板访问终端中显示」开关，本屏的「显示可见供应器」读的就是这个值。
     */
    @Override
    default boolean isVisibleInPatternAccessTerminal() {
        return isVisibleInTerminal();
    }
}

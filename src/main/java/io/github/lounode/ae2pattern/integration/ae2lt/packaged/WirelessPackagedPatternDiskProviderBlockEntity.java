package io.github.lounode.ae2pattern.integration.ae2lt.packaged;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.stacks.AEItemKey;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;

import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ProviderMode;

/**
 * 无线封包样板磁盘供应器：与有线版同一套逻辑，只换成无线模式与自己的图标/标题/菜单。
 *
 * <p>不继承闪电科技那个无线实体：它的构造器写死了父类的方块实体类型，子类无法注册自己的类型。
 * 无线能力来自父类的 {@link ProviderMode#WIRELESS}，差异只有图标与标题四个方法。</p>
 */
public class WirelessPackagedPatternDiskProviderBlockEntity extends PackagedPatternDiskProviderBlockEntity {

    public WirelessPackagedPatternDiskProviderBlockEntity(BlockPos pos, BlockState state) {
        super(PackagedPatternDiskProviderRegistrations.BE_WIRELESS.get(), pos, state, ProviderMode.WIRELESS);
    }

    @Override
    public void openMenu(Player player, MenuHostLocator locator) {
        if (level instanceof net.minecraft.server.level.ServerLevel) {
            clearInvalidConnections();
        }
        MenuOpener.open(WirelessPackagedPatternDiskProviderMenu.TYPE, player, locator);
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(WirelessPackagedPatternDiskProviderMenu.TYPE, player, subMenu.getLocator());
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(PackagedPatternDiskProviderRegistrations.ITEM_WIRELESS.get());
    }

    @Override
    public net.minecraft.world.item.ItemStack getMainMenuIcon() {
        return new net.minecraft.world.item.ItemStack(PackagedPatternDiskProviderRegistrations.ITEM_WIRELESS.get());
    }

    @Override
    protected Item getItemFromBlockEntity() {
        return PackagedPatternDiskProviderRegistrations.ITEM_WIRELESS.get().asItem();
    }

    @Override
    protected Item iconItem() {
        return PackagedPatternDiskProviderRegistrations.ITEM_WIRELESS.get();
    }

    @Override
    public String ae2lt$titleTranslationKey() {
        return "gui.ae2_pattern_disk.wireless_packaged_pattern_disk_provider";
    }
}
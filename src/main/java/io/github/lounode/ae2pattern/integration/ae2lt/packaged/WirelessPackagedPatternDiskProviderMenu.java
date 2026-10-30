package io.github.lounode.ae2pattern.integration.ae2lt.packaged;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.implementations.MenuTypeBuilder;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;

/** 无线封包样板磁盘供应器的菜单：槽位与有线版一致，只有类型不同。 */
public class WirelessPackagedPatternDiskProviderMenu extends PackagedPatternDiskProviderMenu {

    public static final MenuType<WirelessPackagedPatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create((id, playerInventory, host) ->
                            new WirelessPackagedPatternDiskProviderMenu(id, playerInventory, host),
                    PatternDiskProviderHost.class)
            .buildUnregistered(ResourceLocation.parse(
                    "ae2_pattern_disk:wireless_packaged_pattern_disk_provider"));

    public WirelessPackagedPatternDiskProviderMenu(int id, Inventory playerInventory, PatternDiskProviderHost host) {
        super(TYPE, id, playerInventory, host);
    }
}
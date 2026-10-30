package io.github.lounode.ae2pattern.client.integration.ae2lt;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.style.StyleManager;

import io.github.lounode.ae2pattern.integration.ae2lt.packaged.WirelessPackagedPatternDiskProviderMenu;

/**
 * 无线封包样板磁盘供应器的屏幕：与有线版同一套按钮，只换布局文档（无线标题 + 无线连接提示位置）。
 */
public class WirelessPackagedPatternDiskProviderScreen
        extends PackagedPatternDiskProviderScreen<WirelessPackagedPatternDiskProviderMenu> {

    public WirelessPackagedPatternDiskProviderScreen(
            WirelessPackagedPatternDiskProviderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, StyleManager.loadStyleDoc(
                "/screens/ae2_pattern_disk/wireless_packaged_pattern_disk_provider.json"));
    }
}
package io.github.lounode.ae2pattern.integration.ae2wtlib;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import net.minecraft.world.item.ItemStack;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.menu.locator.ItemMenuHostLocator;

import de.mari_023.ae2wtlib.api.terminal.ItemWT;

import io.github.lounode.ae2pattern.common.item.ExcludedUpgradeInventory;

/**
 * 无线版样板磁盘终端的物品。两个无线终端（编码 / 管理）共用这一个类——它们差的只有菜单类型，而那由构造
 * 传入；宿主、逻辑、菜单内容都在 {@link WirelessPatternDiskTerminalHost} 与各自的菜单里。
 *
 * <p>物品属性（堆叠数为 1、电池能量）由 AE2WTLib 的 {@link ItemWT} 定死，本类不参与，所以注册时那个
 * {@code Properties} 参数用不上。</p>
 */
public class WirelessPatternDiskTerminalItem extends ItemWT {

    /**
     * 管理终端要剔除的升级卡：磁卡。按当前上游的登记表它本来就不会挂到本终端（wtlib 只把量子桥卡统一挂给
     * 所有终端，磁卡只登记给自家的 WCT 与通用终端），这层剔除是防第三方 blanket 挂卡的保险，当前不可达。
     * 用 id 而不是物品对象判定：本模组编译期只依赖 {@code ae2wtlib_api}，wtlib 的物品常量不可见。
     */
    private static final java.util.Set<net.minecraft.resources.ResourceLocation> MANAGEMENT_EXCLUDED = java.util.Set.of(
            net.minecraft.resources.ResourceLocation.parse("ae2wtlib:magnet_card"));

    private final MenuType<?> menuType;
    private final String descriptionId;

    public WirelessPatternDiskTerminalItem(MenuType<?> menuType, String descriptionId) {
        this.menuType = menuType;
        this.descriptionId = descriptionId;
    }

    /**
     * AE2WTLib 的 {@link ItemWT} 在无参构造里自建 {@code Item.Properties}，拿不到 NeoForge 注册时注入的物品 id，
     * 于是描述键会停在 {@code Item.Properties} 的默认值 {@code item.minecraft.air}——所有界面（背包、创造栏、
     * tooltip）都会把本物品显示成“空气”。注册时传入 NeoForge 那个 props 也用不上（ItemWT 不接受它），
     * 所以这里显式给出描述键。
     */
    @Override
    public String getDescriptionId() {
        return this.descriptionId;
    }

    @Override
    public MenuType<?> getMenuType(ItemMenuHostLocator locator, Player player) {
        return this.menuType;
    }

    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        var upgrades = super.getUpgrades(stack);
        // 管理终端不该收磁卡，按 id 剔除；编码终端保持原样。按当前上游，wtlib 不会给它挂磁卡，所以这里
        // 通常不生效——留着是为了拦住第三方把卡 blanket 挂到所有无线终端的情形。
        // 包装层只改上限判定，NBT 与变更回调仍走 AE2 的实现。
        boolean exclude = this.menuType == PatternDiskWirelessManagementTermMenu.TYPE;
        if (exclude) {
            return new ExcludedUpgradeInventory(upgrades, MANAGEMENT_EXCLUDED);
        }
        return upgrades;
    }
}

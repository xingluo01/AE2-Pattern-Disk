package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import appeng.menu.implementations.MenuTypeBuilder;

import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderHost;

/**
 * 自装配样板磁盘供应器的菜单：槽位与交互全部继承 {@link PatternDiskProviderMenu}，只换自己的类型与标题。
 *
 * <p>
 * 之所以要独立一份而非共用，是因为 AE2 的菜单标题与布局文档绑在菜单类型上：共用会让这台设备在界面上自称
 * 「ME样板磁盘供应器」。槽位定义一行没改——磁盘栏、物品返回栏、三个工具栏开关都照原样（本类甚至不碰它们）。
 * </p>
 */
public class MeteoritePatternProviderMenu extends PatternDiskProviderMenu {

    public static final net.minecraft.world.inventory.MenuType<MeteoritePatternProviderMenu> TYPE = MenuTypeBuilder
            .create(MeteoritePatternProviderMenu::new, MeteoritePatternProviderHost.class)
            .buildUnregistered(ResourceLocation.parse("ae2_pattern_disk:meteorite_pattern_provider"));

    public MeteoritePatternProviderMenu(int id, Inventory playerInv, MeteoritePatternProviderHost host) {
        super(TYPE, id, playerInv, host);
        // 升级槽：自装配要吃速度卡与陨石超频卡，槽位得在界面上露出来（布局在界面文档的 UPGRADE 节点）。
        // 用带去重的那份：宿主自己的 getUpgrades() 会转发到同一个 AECS 库存，而父类构造里已经因为
        // 应用通量的集成反射拿到过它一次了。
        this.setupUpgradesOnce(host.getUpgrades());
    }
}

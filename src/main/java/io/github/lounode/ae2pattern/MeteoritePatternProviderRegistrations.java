package io.github.lounode.ae2pattern;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.api.parts.PartModels;
import appeng.items.parts.PartItem;

import io.github.lounode.ae2pattern.common.block.MeteoritePatternProviderBlock;
import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderBlockEntity;
import io.github.lounode.ae2pattern.common.menu.MeteoritePatternProviderMenu;
import io.github.lounode.ae2pattern.common.part.MeteoritePatternProviderPart;

/**
 * 自装配样板磁盘供应器的注册。整套注册只有在 AE2 Crystal Science 在场时才执行，所以它不在
 * {@link AEPatternRegistries} 的静态字段里——那边的字段一律无条件注册。
 *
 * <p>
 * 用自己的一组 {@link DeferredRegister}（同一命名空间）而不是往那边加字段，是为了让「装不装 AE2CS」这件事只
 * 影响这一个类：未装时 {@link #register(IEventBus)} 根本不会被调用，这个类的静态初始化也就不会发生，方块、物品、
 * 面板物品、方块实体与菜单在游戏里都不存在。
 * </p>
 *
 * <p>
 * <b>副作用</b>：方块不存在意味着先装后卸 AE2CS 会让存档里的这台设备变成缺失方块。这是这条「只在 AE2CS
 * 安装时出现」策略本身带着的代价，不是可修的缺陷。
 * </p>
 */
public final class MeteoritePatternProviderRegistrations {

    private static boolean registered;

    public static DeferredBlock<MeteoritePatternProviderBlock> BLOCK;
    public static DeferredItem<BlockItem> ITEM;
    public static DeferredItem<PartItem<MeteoritePatternProviderPart>> ITEM_PART;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<MeteoritePatternProviderBlockEntity>> BE;
    public static DeferredHolder<MenuType<?>, MenuType<MeteoritePatternProviderMenu>> MENU;

    private MeteoritePatternProviderRegistrations() {}

    /** 设备是否已注册。创造页与客户端屏幕注册读它，好在未装 AE2CS 时安静跳过。 */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * 注册整套条目。只在 AE2 Crystal Science 在场时由 {@link AE2PatternDisk} 调用，重复调用无副作用。
     */
    public static void register(IEventBus modBus) {
        if (registered) {
            return;
        }
        registered = true;

        var blocks = DeferredRegister.createBlocks(AE2PatternDisk.MOD_ID);
        var items = DeferredRegister.createItems(AE2PatternDisk.MOD_ID);
        var blockEntities = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AE2PatternDisk.MOD_ID);
        var menus = DeferredRegister.create(Registries.MENU, AE2PatternDisk.MOD_ID);

        BLOCK = blocks.register("meteorite_pattern_provider", MeteoritePatternProviderBlock::new);
        ITEM = items.registerSimpleBlockItem("meteorite_pattern_provider", BLOCK);

        // 面板（贴线缆）形态：与方块共享同一份宿主契约、自装配逻辑与菜单，只多一个部件类。
        // 部件模型要先登记进 AE2 的模型表，否则运行时找不到这个资源。
        PartModels.registerModels(MeteoritePatternProviderPart.MODEL);
        ITEM_PART = items.registerItem("cable_meteorite_pattern_provider",
                props -> new PartItem<>(props, MeteoritePatternProviderPart.class,
                        MeteoritePatternProviderPart::new));

        BE = blockEntities.register("meteorite_pattern_provider", () -> BlockEntityType.Builder
                .of(MeteoritePatternProviderBlockEntity::new, BLOCK.get())
                .build(null));

        // 菜单类型本身在 MeteoritePatternProviderMenu 里就带好了（未注册的 MenuType），这里只是把它挂进注册表。
        MENU = menus.register("meteorite_pattern_provider", () -> MeteoritePatternProviderMenu.TYPE);

        // 面板形态的自装配回送要每 tick 一次节拍，而部件没有方块实体那种 tick 回调、网格的 tick 服务槽又归 AE2
        // 的供应器逻辑（见 MeteoritePatternProviderPart 的类注释），所以由服务端的世界 tick 事件驱动。
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                MeteoritePatternProviderPart.pumpCraftedContents(level);
            }
        });

        blocks.register(modBus);
        items.register(modBus);
        blockEntities.register(modBus);
        menus.register(modBus);
    }
}

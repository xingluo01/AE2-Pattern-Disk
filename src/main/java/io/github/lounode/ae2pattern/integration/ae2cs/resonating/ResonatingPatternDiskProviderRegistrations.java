package io.github.lounode.ae2pattern.integration.ae2cs.resonating;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.api.parts.PartModels;
import appeng.items.parts.PartItem;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.integration.ae2cs.AecsSoftDep;

/**
 * 谐振样板磁盘供应器的注册。整套注册只有在 AECS 在场且版本不低于 1.2.1 时才执行；未满足时这个类不会被
 * 主类调用，方块、物品、面板、方块实体与菜单在游戏里都不存在。
 *
 * <p>
 * 与自装配那台同一套「独立 DeferredRegister」策略：装不装 AECS 只影响这一个类。副作用也一样——先装后卸
 * AECS 会让存档里的这台设备变成缺失方块。
 * </p>
 */
public final class ResonatingPatternDiskProviderRegistrations {

    private static boolean registered;

    public static DeferredBlock<ResonatingPatternDiskProviderBlock> BLOCK;
    public static DeferredItem<BlockItem> ITEM;
    public static DeferredItem<PartItem<ResonatingPatternDiskProviderPart>> ITEM_PART;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<ResonatingPatternDiskProviderBlockEntity>> BE;
    public static DeferredHolder<MenuType<?>, MenuType<ResonatingPatternDiskProviderMenu>> MENU;

    private ResonatingPatternDiskProviderRegistrations() {}

    /** 设备是否已注册。创造页、能力与客户端屏幕注册读它，好在未装 AECS 时安静跳过。 */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * 注册整套条目。只在 AECS 在场且带谐振供应器时由 {@link AE2PatternDisk} 调用，重复调用无副作用。
     */
    public static void register(IEventBus modBus) {
        if (registered || !AecsSoftDep.supportsResonatingProvider()) {
            return;
        }
        registered = true;

        var blocks = DeferredRegister.createBlocks(AE2PatternDisk.MOD_ID);
        var items = DeferredRegister.createItems(AE2PatternDisk.MOD_ID);
        var blockEntities = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AE2PatternDisk.MOD_ID);
        var menus = DeferredRegister.create(Registries.MENU, AE2PatternDisk.MOD_ID);

        BLOCK = blocks.register("resonating_pattern_disk_provider", ResonatingPatternDiskProviderBlock::new);
        ITEM = items.registerSimpleBlockItem("resonating_pattern_disk_provider", BLOCK);

        // 面板形态：与方块共享宿主契约、谐振逻辑与菜单，只多一个部件类。模型要先登记进 AE2 的模型表。
        PartModels.registerModels(ResonatingPatternDiskProviderPart.MODEL);
        ITEM_PART = items.registerItem("cable_resonating_pattern_disk_provider",
                props -> new PartItem<>(props, ResonatingPatternDiskProviderPart.class,
                        ResonatingPatternDiskProviderPart::new));

        BE = blockEntities.register("resonating_pattern_disk_provider", () -> BlockEntityType.Builder
                .of(ResonatingPatternDiskProviderBlockEntity::new, BLOCK.get())
                .build(null));

        MENU = menus.register("resonating_pattern_disk_provider", () -> ResonatingPatternDiskProviderMenu.TYPE);

        blocks.register(modBus);
        items.register(modBus);
        blockEntities.register(modBus);
        menus.register(modBus);

        // AE2 的 AEBaseBlockEntity 要显式绑一次代表物品；否则内存卡的机器名比对拿不到设备名，设置会走 generic
        // 分支，绑定目标那一段就搬不过去。
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(
                () -> appeng.blockentity.AEBaseBlockEntity.registerBlockEntityItem(BE.get(), ITEM.get())));
    }
}

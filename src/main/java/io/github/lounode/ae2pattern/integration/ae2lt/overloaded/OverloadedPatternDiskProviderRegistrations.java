package io.github.lounode.ae2pattern.integration.ae2lt.overloaded;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.AE2PatternDisk;

/**
 * 过载样板磁盘供应器的注册入口。只在闪电科技在场时调用；缺席时这些类不会被加载。
 */
public final class OverloadedPatternDiskProviderRegistrations {

    private OverloadedPatternDiskProviderRegistrations() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AE2PatternDisk.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AE2PatternDisk.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AE2PatternDisk.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AE2PatternDisk.MOD_ID);

    public static final DeferredBlock<OverloadedPatternDiskProviderBlock> BLOCK = BLOCKS.register(
            "overloaded_pattern_disk_provider", OverloadedPatternDiskProviderBlock::new);

    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(
            "overloaded_pattern_disk_provider", BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OverloadedPatternDiskProviderBlockEntity>> BE =
            BLOCK_ENTITIES.register(
                    "overloaded_pattern_disk_provider",
                    () -> BlockEntityType.Builder.of(
                            OverloadedPatternDiskProviderBlockEntity::new,
                            BLOCK.get())
                            .build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<OverloadedPatternDiskProviderMenu>> MENU =
            MENUS.register("overloaded_pattern_disk_provider",
                    () -> OverloadedPatternDiskProviderMenu.TYPE);

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);

        // AE2 的 AEBaseEntityBlock 要显式绑一次方块实体类型，否则放置时 newBlockEntity 拿到 null。
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            BLOCK.get().setBlockEntity(
                    OverloadedPatternDiskProviderBlockEntity.class,
                    BE.get(),
                    null,
                    com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity::serverTick);
            appeng.blockentity.AEBaseBlockEntity.registerBlockEntityItem(BE.get(), ITEM.get());
        }));
    }
}
package io.github.lounode.ae2pattern.integration.ae2lt.packaged;

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
 * 封包与无线封包样板磁盘供应器的注册入口。只在闪电科技封包（ae2ltpp）在场时调用。
 */
public final class PackagedPatternDiskProviderRegistrations {

    private PackagedPatternDiskProviderRegistrations() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AE2PatternDisk.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AE2PatternDisk.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AE2PatternDisk.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AE2PatternDisk.MOD_ID);

    // ---- 有线 -----------------------------------------------------------------

    public static final DeferredBlock<PackagedPatternDiskProviderBlock> BLOCK = BLOCKS.register(
            "packaged_pattern_disk_provider", PackagedPatternDiskProviderBlock::new);

    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(
            "packaged_pattern_disk_provider", BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PackagedPatternDiskProviderBlockEntity>> BE =
            BLOCK_ENTITIES.register(
                    "packaged_pattern_disk_provider",
                    () -> BlockEntityType.Builder.of(
                            PackagedPatternDiskProviderBlockEntity::new,
                            BLOCK.get())
                            .build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<PackagedPatternDiskProviderMenu>> MENU =
            MENUS.register("packaged_pattern_disk_provider",
                    () -> PackagedPatternDiskProviderMenu.TYPE);

    // ---- 无线 -----------------------------------------------------------------

    public static final DeferredBlock<PackagedPatternDiskProviderBlock> BLOCK_WIRELESS = BLOCKS.register(
            "wireless_packaged_pattern_disk_provider", PackagedPatternDiskProviderBlock::new);

    public static final DeferredItem<BlockItem> ITEM_WIRELESS = ITEMS.registerSimpleBlockItem(
            "wireless_packaged_pattern_disk_provider", BLOCK_WIRELESS);

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<WirelessPackagedPatternDiskProviderBlockEntity>> BE_WIRELESS =
            BLOCK_ENTITIES.register(
                    "wireless_packaged_pattern_disk_provider",
                    () -> BlockEntityType.Builder.of(
                            WirelessPackagedPatternDiskProviderBlockEntity::new,
                            BLOCK_WIRELESS.get())
                            .build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<WirelessPackagedPatternDiskProviderMenu>> MENU_WIRELESS =
            MENUS.register("wireless_packaged_pattern_disk_provider",
                    () -> WirelessPackagedPatternDiskProviderMenu.TYPE);

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);

        // 同过载：AE2 的方块实体类型要显式绑定，ticker 用上游封包的静态方法。
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            // 方块继承的是上游的封包方块，泛型绑定父类实体类型；实体本身是子类，按原始类型传入。
            @SuppressWarnings({ "unchecked", "rawtypes" })
            var wiredBind = (appeng.block.AEBaseEntityBlock) BLOCK.get();
            wiredBind.setBlockEntity(
                    (Class) PackagedPatternDiskProviderBlockEntity.class,
                    (net.minecraft.world.level.block.entity.BlockEntityType) BE.get(),
                    null,
                    (net.minecraft.world.level.block.entity.BlockEntityTicker) (level, pos, state, be) ->
                            com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.serverTick(
                                    level, pos, state,
                                    (com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity) be));
            appeng.blockentity.AEBaseBlockEntity.registerBlockEntityItem(BE.get(), ITEM.get());

            // 无线方块的泛型绑定的是父类实体类型，而实体本身是子类；运行时由 BE 类型自己的工厂创建，
            // 这里只能按原始类型传入。
            @SuppressWarnings({ "unchecked", "rawtypes" })
            var wirelessBind = (appeng.block.AEBaseEntityBlock) BLOCK_WIRELESS.get();
            wirelessBind.setBlockEntity(
                    (Class) WirelessPackagedPatternDiskProviderBlockEntity.class,
                    (net.minecraft.world.level.block.entity.BlockEntityType) BE_WIRELESS.get(),
                    null,
                    (net.minecraft.world.level.block.entity.BlockEntityTicker) (level, pos, state, be) ->
                            com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.serverTick(
                                    level, pos, state,
                                    (com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity) be));
            appeng.blockentity.AEBaseBlockEntity.registerBlockEntityItem(BE_WIRELESS.get(), ITEM_WIRELESS.get());
        }));
    }
}
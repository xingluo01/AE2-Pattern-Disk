package io.github.lounode.ae2pattern;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.items.parts.PartItem;

import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.api.parts.PartModels;

import io.github.lounode.ae2pattern.common.block.PatternDiskAssemblerBlock;
import io.github.lounode.ae2pattern.common.block.PatternDiskProviderBlock;
import io.github.lounode.ae2pattern.common.block.PatternTransfererBlock;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.PatternDiskAssemblerMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;
import io.github.lounode.ae2pattern.common.menu.PatternTransfererMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTier;

/**
 * Single registration entry point for the AE2 Pattern Disk addon: every {@link DeferredRegister}
 * (items, blocks, block entities, menus, data components, creative tab) and the custom AE2 slot
 * semantics live here so the mod entry only needs one registration call.
 *
 * <p>Prefixes disambiguate the otherwise colliding {@code PATTERN_TRANSFERER / PATTERN_DISK_PROVIDER /
 * PATTERN_DISK_ASSEMBLER} names across the item, block, block-entity and menu registries.</p>
 */
public final class AEPatternRegistries {

    private AEPatternRegistries() {
    }

    // ---- Items ---------------------------------------------------------------

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AE2PatternDisk.MOD_ID);

    /** Pattern disk items, one per capacity tier. */
    public static final DeferredItem<PatternDiskItem> ITEM_DISK_1K = disk(PatternDiskTier.SIZE_1K);
    public static final DeferredItem<PatternDiskItem> ITEM_DISK_4K = disk(PatternDiskTier.SIZE_4K);
    public static final DeferredItem<PatternDiskItem> ITEM_DISK_16K = disk(PatternDiskTier.SIZE_16K);
    public static final DeferredItem<PatternDiskItem> ITEM_DISK_64K = disk(PatternDiskTier.SIZE_64K);
    public static final DeferredItem<PatternDiskItem> ITEM_DISK_256K = disk(PatternDiskTier.SIZE_256K);

    public static final DeferredItem<PartItem<PatternDiskEncodingTerminalPart>> ITEM_PATTERN_DISK_ENCODING_TERMINAL = createEncodingTerminal();

    private static DeferredItem<PartItem<PatternDiskEncodingTerminalPart>> createEncodingTerminal() {
        PartModels.registerModels(
                PatternDiskEncodingTerminalPart.MODEL_OFF,
                PatternDiskEncodingTerminalPart.MODEL_ON);
        return ITEMS.registerItem("pattern_disk_encoding_terminal",
                props -> new PartItem<>(props, PatternDiskEncodingTerminalPart.class,
                        PatternDiskEncodingTerminalPart::new));
    }

    /** Reference to AE2's blank pattern, exposed for the transferer's network return. */
    public static ItemStack blankPattern() {
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:blank_pattern")));
    }

    private static DeferredItem<PatternDiskItem> disk(PatternDiskTier tier) {
        return ITEMS.registerItem("pattern_disk_" + tier.pathSuffix(),
                props -> new PatternDiskItem(props, tier));
    }

    // ---- Blocks --------------------------------------------------------------

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AE2PatternDisk.MOD_ID);

    public static final DeferredBlock<PatternTransfererBlock> BLOCK_TRANSFERER = BLOCKS.register(
            "pattern_transferer",
            PatternTransfererBlock::new);

    public static final DeferredBlock<PatternDiskProviderBlock> BLOCK_PROVIDER = BLOCKS.register(
            "pattern_disk_provider",
            PatternDiskProviderBlock::new);

    public static final DeferredBlock<PatternDiskAssemblerBlock> BLOCK_ASSEMBLER = BLOCKS.register(
            "pattern_disk_assembler",
            PatternDiskAssemblerBlock::new);

    /** Block items (registered after the blocks they reference). */
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ITEM_TRANSFERER = ITEMS
            .registerSimpleBlockItem("pattern_transferer", BLOCK_TRANSFERER);
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ITEM_PROVIDER = ITEMS
            .registerSimpleBlockItem("pattern_disk_provider", BLOCK_PROVIDER);
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ITEM_ASSEMBLER = ITEMS
            .registerSimpleBlockItem("pattern_disk_assembler", BLOCK_ASSEMBLER);

    // ---- Block entities ------------------------------------------------------

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            Registries.BLOCK_ENTITY_TYPE, AE2PatternDisk.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternTransfererBlockEntity>> BE_TRANSFERER = BLOCK_ENTITIES
            .register(
                    "pattern_transferer",
                    () -> BlockEntityType.Builder.of(
                            PatternTransfererBlockEntity::new,
                            BLOCK_TRANSFERER.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternDiskProviderBlockEntity>> BE_PROVIDER = BLOCK_ENTITIES
            .register(
                    "pattern_disk_provider",
                    () -> BlockEntityType.Builder.of(
                            PatternDiskProviderBlockEntity::new,
                            BLOCK_PROVIDER.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternDiskAssemblerBlockEntity>> BE_ASSEMBLER = BLOCK_ENTITIES
            .register(
                    "pattern_disk_assembler",
                    () -> BlockEntityType.Builder.of(
                            PatternDiskAssemblerBlockEntity::new,
                            BLOCK_ASSEMBLER.get())
                            .build(null));

    // ---- Menus ---------------------------------------------------------------

    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU,
            AE2PatternDisk.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<PatternTransfererMenu>> MENU_TRANSFERER = MENUS
            .register("pattern_transferer", () -> PatternTransfererMenu.TYPE);

    public static final DeferredHolder<MenuType<?>, MenuType<PatternDiskProviderMenu>> MENU_PROVIDER = MENUS
            .register("pattern_disk_provider", () -> PatternDiskProviderMenu.TYPE);

    public static final DeferredHolder<MenuType<?>, MenuType<PatternDiskAssemblerMenu>> MENU_ASSEMBLER = MENUS
            .register("pattern_disk_assembler", () -> PatternDiskAssemblerMenu.TYPE);

    public static final DeferredHolder<MenuType<?>, MenuType<PatternDiskEncodingTermMenu>> MENU_PATTERN_DISK_ENCODING_TERMINAL = MENUS
            .register("pattern_disk_encoding_terminal", () -> PatternDiskEncodingTermMenu.TYPE);

    // ---- Data components -----------------------------------------------------

    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(
            Registries.DATA_COMPONENT_TYPE, AE2PatternDisk.MOD_ID);

    /** Whether a disk is locked to a single pattern type. Type value is the AE2 encoded pattern item id. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DISK_TYPE = COMPONENTS
            .registerComponentType(
                    "disk_type",
                    builder -> builder.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /** Capacity tier of the disk (number of patterns it can hold). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> DISK_CAPACITY = COMPONENTS
            .registerComponentType(
                    "disk_capacity",
                    builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** The typed, capacity-bounded list of encoded patterns stored on a disk. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternDiskContents>> DISK_CONTENTS = COMPONENTS
            .registerComponentType(
                    "disk_contents",
                    builder -> builder.persistent(PatternDiskContents.CODEC)
                            .networkSynchronized(PatternDiskContents.STREAM_CODEC));

    /** Custom prefix bound to a disk (disk renamed to this prefix, used for prefix-filtering). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DISK_PREFIX = COMPONENTS
            .registerComponentType(
                    "disk_prefix",
                    builder -> builder.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    // ---- Creative tab --------------------------------------------------------

    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB, AE2PatternDisk.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2_pattern_disk"))
                    .icon(() -> new ItemStack(ITEM_DISK_1K.get()))
                    .displayItems((params, output) -> {
                        output.accept(ITEM_DISK_1K.get());
                        output.accept(ITEM_DISK_4K.get());
                        output.accept(ITEM_DISK_16K.get());
                        output.accept(ITEM_DISK_64K.get());
                        output.accept(ITEM_DISK_256K.get());
                        output.accept(ITEM_TRANSFERER.get());
                        output.accept(ITEM_PROVIDER.get());
                        output.accept(ITEM_ASSEMBLER.get());
                        output.accept(ITEM_PATTERN_DISK_ENCODING_TERMINAL.get());
                    })
                    .build());

    // ---- Slot semantics ------------------------------------------------------

    /**
     * Custom slot semantics, using our mod-id prefix as required by AE2's
     * {@link SlotSemantics#register(String, boolean)} contract.
     */
    /** Six input slots: accept encoded patterns or populated pattern disks. */
    public static final SlotSemantic TRANSFER_INPUT = SlotSemantics.register("ae2_pattern_disk:transfer_input", false);

    /** The target pattern disk slot that receives transferred patterns. */
    public static final SlotSemantic TRANSFER_APPLICATION = SlotSemantics.register(
            "ae2_pattern_disk:transfer_application", false);

    /** Temporary output slot for blank patterns before they are returned to the ME network. */
    public static final SlotSemantic TRANSFER_BLANK_OUTPUT = SlotSemantics.register(
            "ae2_pattern_disk:transfer_blank_output", false);

    /** Disk slots on the pattern disk provider. */
    public static final SlotSemantic PROVIDER_DISK = SlotSemantics.register(
            "ae2_pattern_disk:provider_disk", false);

    /**
     * Crafting grid slots of each assembler unit (each unit gets its own semantic so the ScreenStyle
     * JSON can lay out every unit's 3x3 grid at the shared EAE page position).
     */
    public static final SlotSemantic[] ASSEMBLER_GRID = new SlotSemantic[8];

    /**
     * Per-unit encoded-pattern display slot (mirrors what the page is currently assembling), one slot per
     * CraftUnit so each page can show its own sample pattern independently.
     */
    public static final SlotSemantic[] ASSEMBLER_PATTERN = new SlotSemantic[8];

    static {
        for (int i = 0; i < 8; i++) {
            ASSEMBLER_GRID[i] = SlotSemantics.register("ae2_pattern_disk:assembler_grid_" + i, false);
            ASSEMBLER_PATTERN[i] = SlotSemantics.register("ae2_pattern_disk:assembler_pattern_" + i, false);
        }
    }

    /** Registers every DeferredRegister on the given mod event bus. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        COMPONENTS.register(modBus);
        TABS.register(modBus);
    }
}

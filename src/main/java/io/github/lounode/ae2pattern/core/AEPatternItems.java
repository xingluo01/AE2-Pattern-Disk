package io.github.lounode.ae2pattern.core;

import java.util.function.Function;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTier;

/**
 * Item registration for the mod. Pattern disk items are added here.
 */
public final class AEPatternItems {

    private AEPatternItems() {
    }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AE2PatternDisk.MOD_ID);

    // Pattern disk items, one per capacity tier.
    public static final DeferredItem<PatternDiskItem> PATTERN_DISK_1K = disk(PatternDiskTier.SIZE_1K);
    public static final DeferredItem<PatternDiskItem> PATTERN_DISK_4K = disk(PatternDiskTier.SIZE_4K);
    public static final DeferredItem<PatternDiskItem> PATTERN_DISK_16K = disk(PatternDiskTier.SIZE_16K);
    public static final DeferredItem<PatternDiskItem> PATTERN_DISK_64K = disk(PatternDiskTier.SIZE_64K);

    // Block items.
    public static final DeferredItem<net.minecraft.world.item.BlockItem> PATTERN_TRANSFERER = ITEMS
            .registerSimpleBlockItem("pattern_transferer", AEPatternBlocks.PATTERN_TRANSFERER);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> PATTERN_DISK_PROVIDER = ITEMS
            .registerSimpleBlockItem("pattern_disk_provider", AEPatternBlocks.PATTERN_DISK_PROVIDER);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> PATTERN_DISK_ASSEMBLER = ITEMS
            .registerSimpleBlockItem("pattern_disk_assembler", AEPatternBlocks.PATTERN_DISK_ASSEMBLER);

    // Reference to AE2's blank pattern, exposed for the transferer's network return.
    public static ItemStack blankPattern() {
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:blank_pattern")));
    }

    private static DeferredItem<PatternDiskItem> disk(PatternDiskTier tier) {
        return ITEMS.registerItem("pattern_disk_" + tier.pathSuffix(),
                props -> new PatternDiskItem(props, tier));
    }
}

package io.github.lounode.ae2pattern.core;

import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.common.block.PatternDiskAssemblerBlock;
import io.github.lounode.ae2pattern.common.block.PatternDiskProviderBlock;
import io.github.lounode.ae2pattern.common.block.PatternTransfererBlock;

/**
 * Block registration for the mod. The pattern transferer and pattern disk provider blocks are added here.
 */
public final class AEPatternBlocks {

    private AEPatternBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AE2PatternDisk.MOD_ID);

    public static final DeferredBlock<PatternTransfererBlock> PATTERN_TRANSFERER = BLOCKS.register(
            "pattern_transferer",
            PatternTransfererBlock::new);

    public static final DeferredBlock<PatternDiskProviderBlock> PATTERN_DISK_PROVIDER = BLOCKS.register(
            "pattern_disk_provider",
            PatternDiskProviderBlock::new);

    public static final DeferredBlock<PatternDiskAssemblerBlock> PATTERN_DISK_ASSEMBLER = BLOCKS.register(
            "pattern_disk_assembler",
            PatternDiskAssemblerBlock::new);
}

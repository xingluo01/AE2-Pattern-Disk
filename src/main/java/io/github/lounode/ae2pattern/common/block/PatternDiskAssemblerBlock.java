package io.github.lounode.ae2pattern.common.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;

/**
 * In-world block hosting the parallel pattern disk assembler: executes up to {@link PatternDiskAssemblerBlockEntity#THREADS}
 * crafting patterns concurrently, being driven by any AE2 provider (pattern disk provider, standard provider, etc).
 */
public class PatternDiskAssemblerBlock extends AEBaseEntityBlock<PatternDiskAssemblerBlockEntity> {

    public PatternDiskAssemblerBlock() {
        super(AEBaseBlock.metalProps());
    }
}

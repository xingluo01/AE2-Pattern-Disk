package io.github.lounode.ae2pattern.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.util.InteractionUtil;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.menu.PatternDiskAssemblerMenu;

/**
 * In-world block hosting the pattern disk assembler (高效分子装配室): executes up to
 * {@link PatternDiskAssemblerBlockEntity#THREADS} crafting patterns concurrently, being driven by any AE2
 * provider (pattern disk provider, standard provider, etc).
 */
public class PatternDiskAssemblerBlock extends AEBaseEntityBlock<PatternDiskAssemblerBlockEntity> {

    public PatternDiskAssemblerBlock() {
        super(AEBaseBlock.metalProps());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (InteractionUtil.isInAlternateUseMode(player)) {
            return InteractionResult.PASS;
        }
        var be = getBlockEntity(level, pos);
        if (be != null) {
            if (!level.isClientSide()) {
                MenuOpener.open(PatternDiskAssemblerMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

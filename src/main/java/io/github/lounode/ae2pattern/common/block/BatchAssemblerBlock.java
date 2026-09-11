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

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.menu.BatchAssemblerMenu;

/**
 * In-world block hosting the batch molecular assembler (批处理分子装配室): buffers materials pushed by
 * AE2 crafting CPUs inside private storage-cell slots and executes them in batches. Crafting-table,
 * smithing-table and stonecutting recipes are all supported, but each job is executed independently -
 * intermediate products always go back to the ME network instead of being chained inside the machine.
 */
public class BatchAssemblerBlock extends AEBaseEntityBlock<BatchAssemblerBlockEntity> {

    public BatchAssemblerBlock() {
        // Reuses the molecular assembler's transparent (cutout) look for now.
        super(AEBaseBlock.metalProps().noOcclusion());
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
                MenuOpener.open(BatchAssemblerMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

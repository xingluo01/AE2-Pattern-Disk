package io.github.lounode.ae2pattern.common.block;

import org.jetbrains.annotations.Nullable;

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

import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;
import io.github.lounode.ae2pattern.core.AEPatternMenus;

/**
 * In-world block hosting the pattern transferer. Two pattern disks can be swapped/transferred between,
 * and encoded patterns can be extracted into a disk while producing a blank pattern that is returned to
 * the ME network.
 */
public class PatternTransfererBlock extends AEBaseEntityBlock<PatternTransfererBlockEntity> {

    public PatternTransfererBlock() {
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
                be.openMenu(player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

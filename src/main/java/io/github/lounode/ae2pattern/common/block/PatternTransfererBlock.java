package io.github.lounode.ae2pattern.common.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.OrientationStrategies;
import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.util.InteractionUtil;

import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;

/**
 * In-world block hosting the pattern transferer. Two pattern disks can be swapped/transferred between,
 * and encoded patterns can be extracted into a disk while producing a blank pattern that is returned to
 * the ME network.
 *
 * <p>The model is oriented (its front face is the north face of
 * {@code ae2_pattern_disk:block/pattern_transferer}), so the block needs a horizontal facing. Without an
 * orientation strategy, {@link AEBaseBlock} defaults to {@code OrientationStrategies.none()} and the front
 * face would be stuck pointing north.</p>
 */
public class PatternTransfererBlock extends AEBaseEntityBlock<PatternTransfererBlockEntity> {

    /**
     * True while the transferer's ME node is online; switches the block between its off and on
     * models. Mirrors AE2's {@code IOPortBlock.POWERED}.
     */
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");

    public PatternTransfererBlock() {
        super(AEBaseBlock.metalProps());
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POWERED);
    }

    /**
     * Keeps the block state in sync with the block entity: AE2 calls this from
     * {@code AEBaseBlockEntity.markForUpdate()}, which the block entity triggers whenever its main
     * node changes state.
     */
    @Override
    protected BlockState updateBlockStateFromBlockEntity(BlockState currentState, PatternTransfererBlockEntity be) {
        return currentState.setValue(POWERED, be.isActive());
    }

    @Override
    public IOrientationStrategy getOrientationStrategy() {
        return OrientationStrategies.horizontalFacing();
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

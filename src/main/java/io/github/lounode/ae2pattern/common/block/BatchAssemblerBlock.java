package io.github.lounode.ae2pattern.common.block;

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

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.menu.BatchAssemblerMenu;

/**
 * In-world block hosting the batch assembler (批处理装配室): buffers materials pushed by
 * AE2 crafting CPUs inside private storage-cell slots and executes them in batches. Crafting-table,
 * smithing-table and stonecutting recipes are all supported, but each job is executed independently -
 * intermediate products always go back to the ME network instead of being chained inside the machine.
 *
 * <p>Oriented and lit like {@link PatternTransfererBlock}: placed towards the player, rotatable with a
 * wrench, and switching between the off/on models with the ME node (see that class for why the
 * orientation strategy must be declared).</p>
 */
public class BatchAssemblerBlock extends AEBaseEntityBlock<BatchAssemblerBlockEntity> {

    /**
     * True while the machine's ME node is online; switches between the off and on models.
     * Mirrors {@link PatternTransfererBlock} and AE2's IO port.
     */
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");

    public BatchAssemblerBlock() {
        super(AEBaseBlock.metalProps());
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POWERED);
    }

    /**
     * AE2 calls this from {@code AEBaseBlockEntity.markForUpdate()}, which the block entity triggers
     * whenever its main node changes state (same wiring as {@link PatternTransfererBlock}).
     */
    @Override
    protected BlockState updateBlockStateFromBlockEntity(BlockState currentState, BatchAssemblerBlockEntity be) {
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
                MenuOpener.open(BatchAssemblerMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

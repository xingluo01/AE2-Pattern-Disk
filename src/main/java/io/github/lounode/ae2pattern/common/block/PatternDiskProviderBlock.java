package io.github.lounode.ae2pattern.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.PatternProviderBlock;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.util.InteractionUtil;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * In-world block hosting the pattern disk provider: exposes disk contents to the ME autocrafting service.
 *
 * <p>While its {@link PatternDiskProviderBlockEntity} extends AE2's {@code PatternProviderBlockEntity},
 * this block must expose the same {@code PUSH_DIRECTION} state property that the parent block entity
 * reads on construction (otherwise opening the GUI / placing the block throws).</p>
 */
public class PatternDiskProviderBlock extends AEBaseEntityBlock<PatternDiskProviderBlockEntity> {

    // Use AE2's own PUSH_DIRECTION constant so the parent pattern-provider block entity
    // (which reads PatternProviderBlock.PUSH_DIRECTION on construction) finds it.
    private static final EnumProperty<appeng.block.crafting.PushDirection> PUSH_DIRECTION = PatternProviderBlock.PUSH_DIRECTION;

    public PatternDiskProviderBlock() {
        super(AEBaseBlock.metalProps());
        registerDefaultState(defaultBlockState().setValue(PUSH_DIRECTION, appeng.block.crafting.PushDirection.ALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PUSH_DIRECTION);
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
                MenuOpener.open(PatternDiskProviderMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

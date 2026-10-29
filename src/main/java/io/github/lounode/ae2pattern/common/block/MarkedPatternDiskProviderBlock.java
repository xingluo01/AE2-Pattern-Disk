package io.github.lounode.ae2pattern.common.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.crafting.PushDirection;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.util.InteractionUtil;
import appeng.util.Platform;

import io.github.lounode.ae2pattern.common.block.entity.MarkedPatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.menu.MarkedPatternDiskProviderMenu;

/**
 * 标记式样板磁盘供应器的方块形态。
 *
 * <p>推入方向、红石更新与开屏方式都与 {@link PatternDiskProviderBlock} 同源：父方块实体会读
 * {@code PUSH_DIRECTION} 状态，缺了它放置和开界面都会抛。</p>
 */
public class MarkedPatternDiskProviderBlock extends AEBaseEntityBlock<MarkedPatternDiskProviderBlockEntity> {

    private static final EnumProperty<PushDirection> PUSH_DIRECTION = PatternProviderBlock.PUSH_DIRECTION;

    public MarkedPatternDiskProviderBlock() {
        super(AEBaseBlock.metalProps());
        registerDefaultState(defaultBlockState().setValue(PUSH_DIRECTION, PushDirection.ALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PUSH_DIRECTION);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos fromPos, boolean movedByPiston) {
        var be = getBlockEntity(level, pos);
        if (be != null) {
            // 与 AE2 的方块供应器同一接线：少了它，被脉冲锁住的供应器永远不解锁。
            be.getLogic().updateRedstoneState();
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (InteractionUtil.canWrenchRotate(heldItem)) {
            cyclePushDirection(level, pos, state, hit.getDirection());
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.useItemOn(heldItem, state, level, pos, player, hand, hit);
    }

    private static void cyclePushDirection(Level level, BlockPos pos, BlockState state, Direction clickedFace) {
        if (level.isClientSide()) {
            // 方块状态只写一次：这个方法客户端也会跑，两边同时写会打架。
            return;
        }

        var pushing = state.getValue(PUSH_DIRECTION).getDirection();
        PushDirection next;
        if (pushing == null) {
            next = PushDirection.fromDirection(clickedFace.getOpposite());
        } else if (pushing == clickedFace.getOpposite()) {
            next = PushDirection.fromDirection(clickedFace);
        } else if (pushing == clickedFace) {
            next = PushDirection.ALL;
        } else {
            next = PushDirection.fromDirection(Platform.rotateAround(pushing, clickedFace));
        }
        level.setBlockAndUpdate(pos, state.setValue(PUSH_DIRECTION, next));
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
                MenuOpener.open(MarkedPatternDiskProviderMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

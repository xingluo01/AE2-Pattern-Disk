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
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos fromPos, boolean movedByPiston) {
        var be = getBlockEntity(level, pos);
        if (be != null) {
            // Same wiring as AE2's own block pattern provider: without it a provider locked until a
            // redstone pulse never unlocks, and the while-low/high modes keep a stale signal value.
            be.getLogic().updateRedstoneState();
        }
    }

    /**
     * 扳手切「全向 / 定向」，与 AE2 样板供应器的方块形态同一套手法（拿点击的那一面做参照）：
     * 全向时点某面 ⇒ 改成朝它的反向推；点它正推着的那一面 ⇒ 回到全向；点别的面 ⇒ 绕着转一格。
     *
     * <p>非扳手的右键（含手里拿着东西）原样交给父类，与之前一致。</p>
     */
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
            // 写方块状态只做一次：这个方法客户端也会跑（拿它回执出手感），两边同时写会打架。
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
                MenuOpener.open(PatternDiskProviderMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

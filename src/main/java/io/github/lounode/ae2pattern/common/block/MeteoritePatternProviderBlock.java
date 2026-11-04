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

import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderBlockEntity;
import io.github.lounode.ae2pattern.common.menu.pattern.MeteoritePatternProviderMenu;

/**
 * 自装配样板磁盘供应器的方块：与 ME样板磁盘供应器同一套摆放与朝向手法，指向自己的菜单。
 *
 * <p>
 * 方块实体继承自 AE2 的供应器方块实体，所以这里必须暴露同一个 {@code PUSH_DIRECTION} 状态属性——父类在构造
 * 时会读它，缺了它放置方块或开界面都会抛异常。
 * </p>
 */
public class MeteoritePatternProviderBlock extends AEBaseEntityBlock<MeteoritePatternProviderBlockEntity> {

    // 用 AE2 自己的那个属性常量：父类供应器方块实体读的就是它。
    private static final EnumProperty<PushDirection> PUSH_DIRECTION = PatternProviderBlock.PUSH_DIRECTION;

    public MeteoritePatternProviderBlock() {
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
            // 与 AE2 自己的方块供应器同一套接线：少了它，被红石脉冲锁住再解锁的供应器会一直不解锁。
            be.getLogic().updateRedstoneState();
        }
    }

    /**
     * 扳手切「全向 / 定向」，与 AE2 样板供应器同一套手法：拿点击的那一面做参照，全向时点某面改成朝它的反向推，
     * 点它正推着的那一面回到全向，点别的面绕着转一格。
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
            // 只写一次方块状态：这个方法客户端也会跑（拿它出手感），两边同时写会打架。
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
                MenuOpener.open(MeteoritePatternProviderMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

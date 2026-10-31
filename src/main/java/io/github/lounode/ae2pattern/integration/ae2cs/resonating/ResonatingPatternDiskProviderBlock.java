package io.github.lounode.ae2pattern.integration.ae2cs.resonating;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.crafting.PushDirection;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.util.InteractionUtil;
import appeng.util.Platform;

/**
 * 谐振样板磁盘供应器的方块：摆放/朝向/扳手手法照自装配那台，开自己的菜单。
 *
 * <p>
 * 与 AECS 原方块相比，这里多补两段绑定数据的搬运：放置时从物品读默认输入目标，掉落时写回物品。AECS 的
 * {@code ResonatingPatternProviderBlock} 做的是同一件事；本设备不继承它的方块实体，所以不能靠父类拿。
 * 少了这两段，绑定器设好的目标会在拆装后丢失。
 * </p>
 */
public class ResonatingPatternDiskProviderBlock extends AEBaseEntityBlock<ResonatingPatternDiskProviderBlockEntity> {

    // 用 AE2 自己的那个属性常量：父类供应器方块实体读的就是它。
    private static final EnumProperty<PushDirection> PUSH_DIRECTION = PatternProviderBlock.PUSH_DIRECTION;

    public ResonatingPatternDiskProviderBlock() {
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
     * 放置时把物品上的默认输入目标带进设备。绑定器可以先把目标存到供应器物品上，再摆下来。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ResonatingPatternDiskProviderHost host) {
            host.getResonatingLogic().readDefaultsFromItem(stack);
            host.getResonatingLogic().saveChanges();
        }
    }

    /**
     * 掉落时把当前默认输入目标写回物品；不写的话绑定数据只活在方块实体里，拆一次就没了。
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        List<ItemStack> drops = super.getDrops(state, builder);
        if (builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof ResonatingPatternDiskProviderHost host) {
            host.getResonatingLogic().writeDefaultsToDrops(drops, state.getBlock().asItem());
        }
        return drops;
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
                MenuOpener.open(ResonatingPatternDiskProviderMenu.TYPE, player, MenuLocators.forBlockEntity(be));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
    }
}

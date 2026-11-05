package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.network.AssemblerAnimationStatus;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * A parallel molecular assembler that accepts many crafting patterns (from a pattern disk provider or any
 * AE2 provider) and executes up to {@link #THREADS} of them concurrently.
 *
 * <p>Design mirrors AE2's {@code MolecularAssemblerBlockEntity} but scales the single crafting pipeline
 * into N independent execution units. Each unit owns a 3x3 grid + a progress counter; {@code pushPattern}
 * dispatches a pattern to the first idle unit; the grid tick advances all busy units.</p>
 *
 * <p>执行单元、产物投递、动画汇总与存档/掉落整块搬进了同包的 {@link AssemblerCrafting}；本类只留
 * {@code @Override} 入口、网络视图状态与一格电源/动画字段，逐行转调过去。</p>
 */
public class PatternDiskAssemblerBlockEntity extends AENetworkedBlockEntity
        implements InternalInventoryHost, IUpgradeableObject, IGridTickable,
        appeng.api.implementations.blockentities.ICraftingMachine {

    public static final int THREADS = 8;
    public static final int GRID_SIZE = 9; // 3x3

    private final IUpgradeInventory upgrades;

    /** 合成执行（八个单元、投递、动画与存档/掉落）：本类只持有它并转调。 */
    private final AssemblerCrafting crafting;

    /** Client-synced power state. */
    private boolean isPowered = false;

    @OnlyIn(Dist.CLIENT)
    private AssemblerAnimationStatus animationStatus;

    public PatternDiskAssemblerBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_ASSEMBLER.get(), pos, blockState);
        this.upgrades = UpgradeInventories.forMachine(AEPatternRegistries.BLOCK_ASSEMBLER.get(), 5,
                this::onUpgradesChanged);
        // 环境全是快照与回调：网格、世界取调用时刻实况（节点晚于构造器建立、level 可能为空），位置是快照。
        this.crafting = new AssemblerCrafting(new AssemblerCrafting.CraftingEnv() {
            @Override
            public IGrid grid() {
                return PatternDiskAssemblerBlockEntity.this.getMainNode().getGrid();
            }

            @Override
            public Level level() {
                return PatternDiskAssemblerBlockEntity.this.level;
            }

            @Override
            public BlockPos pos() {
                return PatternDiskAssemblerBlockEntity.this.worldPosition;
            }

            @Override
            public boolean isClientSide() {
                return PatternDiskAssemblerBlockEntity.this.isClientSide();
            }

            @Override
            public void saveChanges() {
                PatternDiskAssemblerBlockEntity.this.saveChanges();
            }

            @Override
            public void alertDevice() {
                PatternDiskAssemblerBlockEntity.this.alertTicker();
            }
        }, this, new MachineSource(this), upgrades);
        // 与 AE2 分子装配室一致：它只做被推送的合成，不占频道（仍从网格取电）。
        this.getMainNode()
                .setIdlePowerUsage(0)
                .setInWorldNode(true)
                .setExposedOnSides(java.util.Set.of(Direction.values()))
                .addService(IGridTickable.class, this);
    }

    private void onUpgradesChanged() {
        saveChanges();
    }

    /**
     * The output slots of all units, exposed to external item handlers. Only extraction is allowed.
     */
    public InternalInventory getExposedOutputInventory() {
        return crafting.getExposedOutputInventory();
    }

    /** Total crafting progress across all threads (0..THREADS*100). */
    public int getCraftingProgressAcrossThreads() {
        return crafting.getCraftingProgressAcrossThreads();
    }

    /** Number of threads currently executing a plan (busy). */
    public int getRunningThreads() {
        return crafting.getRunningThreads();
    }

    /**
     * Read-only view of one unit's crafting grid (3x3 input + output at index 9). The returned
     * inventory is a live view of the unit; the client uses it to render the per-unit grid slot
     * contents while the machine runs. Never modify it from the client.
     */
    public InternalInventory getUnitGrid(int index) {
        return crafting.getUnitGrid(index);
    }

    /**
     * Read-only view of one unit's manual pattern slot (encoded pattern drives this unit's execution).
     */
    public InternalInventory getUnitPatternInv(int index) {
        return crafting.getUnitPatternInv(index);
    }

    /** Current crafting progress of one unit, in percent (0..100). */
    public int getUnitProgress(int index) {
        return crafting.getUnitProgress(index);
    }

    /** True while a unit executes a plan; the client uses it to keep provider-driven pages undimmed. */
    public boolean isUnitExecuting(int index) {
        return crafting.isUnitExecuting(index);
    }

    /** Current supported crafting pattern for one unit, or {@code null} when the page is idle. */
    public @Nullable IMolecularAssemblerSupportedPattern getCurrentPattern(int index) {
        return crafting.getCurrentPattern(index);
    }

    /**
     * World-render helper: returns the first output of the earliest active unit, or {@link ItemStack#EMPTY}
     * when no unit is currently assembling. The renderer draws this inside the machine to mirror the
     * original molecular assembler's in-world item depiction.
     */
    public ItemStack getRenderedAssemblyItem() {
        return crafting.getRenderedAssemblyItem();
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        final boolean c = super.readFromStream(data);
        final boolean oldPower = this.isPowered;
        this.isPowered = data.readBoolean();
        return this.isPowered != oldPower || c;
    }

    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(this.isPowered);
    }

    /** True while the device is active on the grid (powered and booted - it takes no channel). */
    public boolean isPowered() {
        return isPowered;
    }

    @OnlyIn(Dist.CLIENT)
    public void setAnimationStatus(@org.jetbrains.annotations.Nullable AssemblerAnimationStatus status) {
        this.animationStatus = status;
    }

    @OnlyIn(Dist.CLIENT)
    @org.jetbrains.annotations.Nullable
    public AssemblerAnimationStatus getAnimationStatus() {
        return animationStatus;
    }

    /** True if one unit is currently executing a plan or holding leftover output. */
    public boolean isUnitBusy(int index) {
        return crafting.isUnitBusy(index);
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return AECableType.SMART;
    }

    /**
     * Accepts a pattern from any AE2 provider and dispatches it to the first idle unit.
     */
    @Override
    public boolean pushPattern(appeng.api.crafting.IPatternDetails patternDetails, KeyCounter[] table,
            Direction where) {
        return crafting.pushPattern(patternDetails, table, where);
    }

    @Override
    public boolean acceptsPlans() {
        return crafting.acceptsPlans();
    }

    @Override
    public appeng.api.implementations.blockentities.PatternContainerGroup getCraftingMachineInfo() {
        return new appeng.api.implementations.blockentities.PatternContainerGroup(
                AEItemKey.of(AEPatternRegistries.ITEM_ASSEMBLER.get()),
                AEPatternRegistries.ITEM_ASSEMBLER.get().getDescription(),
                List.of(Component.translatable("ae2_pattern_disk.assembler.threads", THREADS)));
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return crafting.getTickingRequest(node);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        return crafting.tickingRequest(node, ticksSinceLastCall);
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        boolean newState = getMainNode() != null && getMainNode().isActive();
        if (newState != this.isPowered) {
            this.isPowered = newState;
            markForUpdate();
        }
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        saveChanges();
        // AE2 parks this device through TickRateModulation.SLEEP once no unit is busy and only resumes
        // it via the tick manager, so inventory changes (a pattern inserted into a page, grid items to
        // return) must nudge the ticker or the new work would never be picked up.
        alertTicker();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    private void alertTicker() {
        this.getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    @Override
    public boolean isClientSide() {
        return level != null && level.isClientSide();
    }

    @Override
    public void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        crafting.writeToNbt(tag, registries);
    }

    @Override
    public void loadTag(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        crafting.readFromNbt(tag, registries);
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        crafting.addDrops(drops);
    }

    @Override
    public void clearContent() {
        super.clearContent();
        crafting.clear();
    }
}

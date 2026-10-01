package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.logic.TransfererOperations;
import io.github.lounode.ae2pattern.common.pattern.TransferMode;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Block entity for the pattern transferer.
 *
 * <p>Inventory layout (6 input + 6 application + 1 output):</p>
 * <ul>
 *   <li>6 input slots (index 0..5): accept encoded patterns or populated pattern disks.</li>
 *   <li>6 application slots (index 6..11): target pattern disks that receive transferred patterns.</li>
 *   <li>1 output slot (index 12): temporary storage for blank patterns produced when patterns are extracted.</li>
 * </ul>
 *
 * <p>Each server tick it performs one work cycle:</p>
 * <ol>
 *   <li>Blank patterns in the output slot are returned to the connected ME network (if any).</li>
 *   <li>For each input slot: STORE mode transfers encoded patterns into application disks (extract) or
 *       moves patterns from a populated source disk into the application disks (source disk drains);
 *       COPY mode copies patterns from a populated source disk into the application disks while keeping
 *       the source disk's contents.</li>
 * </ol>
 */
public class PatternTransfererBlockEntity extends AENetworkedBlockEntity
        implements InternalInventoryHost, IUpgradeableObject, appeng.blockentity.ServerTickingBlockEntity {

    public static final int INPUT_SLOT_COUNT = 6;
    public static final int INPUT_START = 0;

    public static final int APPLICATION_SLOT_COUNT = 6;
    public static final int APPLICATION_START = INPUT_SLOT_COUNT;

    public static final int OUTPUT_SLOT = INPUT_SLOT_COUNT + APPLICATION_SLOT_COUNT;
    public static final int TOTAL_SLOTS = OUTPUT_SLOT + 1;

    /** Number of upgrade slots (accelerator/speed cards). */
    public static final int UPGRADE_SLOT_COUNT = 4;

    /** 转存器工作模式（样板存储 / 样板复写）。 */
    private TransferMode mode = TransferMode.STORE;

    public TransferMode getMode() {
        return mode;
    }

    public void setMode(TransferMode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            saveChanges();
        }
    }

    /** 客户端侧 ME 节点状态的镜像；经方块实体流同步。 */
    private boolean isActive = false;

    private final AppEngInternalInventory inventory = new AppEngInternalInventory(this, TOTAL_SLOTS);
    private final IActionSource actionSource = new MachineSource(this);
    private final IUpgradeInventory upgrades;

    /** 搬运操作、速率累积与空样板回送都在 {@link TransfererOperations} 里，这里只持有它。 */
    private final TransfererOperations ops;

    public PatternTransfererBlockEntity(BlockPos pos, BlockState blockState) {
        super(AEPatternRegistries.BE_TRANSFERER.get(), pos, blockState);
        this.upgrades = UpgradeInventories.forMachine(
                AEPatternRegistries.BLOCK_TRANSFERER.get(),
                UPGRADE_SLOT_COUNT,
                this::onUpgradesChanged);
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.5)
                // In-world node so adjacent AE2 cables can connect (getGridNode returns non-null).
                .setInWorldNode(true)
                .setExposedOnSides(java.util.Set.of(Direction.values()));
        // 网格与世界都是拿调用时刻的实况，所以给 Supplier：节点晚于构造器建立，level 则可能为空。
        this.ops = new TransfererOperations(inventory, upgrades, actionSource,
                () -> getMainNode().getGrid(), () -> this.level);
    }

    private void onUpgradesChanged() {
        saveChanges();
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode().setVisualRepresentation(AEPatternRegistries.ITEM_TRANSFERER.get());
    }

    @Override
    public appeng.api.util.AECableType getCableConnectionType(Direction direction) {
        return appeng.api.util.AECableType.SMART;
    }

    @Override
    public java.util.Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        // The transferer can be connected to cables from all sides.
        return java.util.EnumSet.allOf(Direction.class);
    }

    @Override
    public void onMainNodeStateChanged(appeng.api.networking.IGridNodeListener.State state) {
        // Mark for client update on grid changes so the (re)connected state is reflected.
        // GRID_BOOT is skipped like AE2's IO port: the node state is not settled yet, and
        // AENetworkedBlockEntity.onReady() aligns the block state once the node exists.
        if (state != appeng.api.networking.IGridNodeListener.State.GRID_BOOT) {
            markForUpdate();
        }
    }

    /**
     * Whether the ME node is online, which drives the block's powered state (off/on model).
     *
     * <p>The server reads the live node state; the client uses the value pushed through
     * {@link #writeToStream}/{@link #readFromStream}, because a client-side grid node is not
     * authoritative. Same approach as AE2's IO port.</p>
     */
    public boolean isActive() {
        if (level != null && !level.isClientSide()) {
            return this.getMainNode().isOnline();
        }
        return this.isActive;
    }

    @Override
    protected void writeToStream(net.minecraft.network.RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(this.isActive());
    }

    @Override
    protected boolean readFromStream(net.minecraft.network.RegistryFriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);

        boolean active = data.readBoolean();
        changed = active != this.isActive || changed;
        this.isActive = active;

        return changed;
    }

    public AppEngInternalInventory getInventory() {
        return inventory;
    }

    public static int inputSlot(int index) {
        return INPUT_START + index;
    }

    public static int applicationSlot(int index) {
        return APPLICATION_START + index;
    }

    /**
     * Server tick: perform one work cycle per tick.
     */
    @Override
    public void serverTick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        tickTransfer();
    }

    /**
     * 跑一个搬运周期。速率（每 tick 几个）、空样板回送与两种模式的搬运都在 {@link TransfererOperations}。
     */
    private void tickTransfer() {
        if (ops.tick(mode)) {
            saveChanges();
        }
    }
    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        saveChanges();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    @Override
    public boolean isClientSide() {
        return level != null && level.isClientSide();
    }

    @Override
    public void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        inventory.writeToNBT(tag, "inv", registries);
        upgrades.writeToNBT(tag, "upgrades", registries);
        tag.putString("mode", mode.name());
        ops.writeToNbt(tag, "patternAccumulator");
    }

    @Override
    public void loadTag(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        inventory.readFromNBT(tag, "inv", registries);
        upgrades.readFromNBT(tag, "upgrades", registries);
        try {
            mode = TransferMode.valueOf(tag.getString("mode"));
        } catch (IllegalArgumentException e) {
            mode = TransferMode.STORE;
        }
        ops.readFromNbt(tag, "patternAccumulator");
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        for (int i = 0; i < inventory.size(); i++) {
            drops.add(inventory.getStackInSlot(i));
        }
        for (int i = 0; i < upgrades.size(); i++) {
            drops.add(upgrades.getStackInSlot(i));
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        inventory.clear();
    }

    public void openMenu(net.minecraft.world.entity.player.Player player, appeng.menu.locator.MenuHostLocator locator) {
        appeng.menu.MenuOpener.open(io.github.lounode.ae2pattern.common.menu.PatternTransfererMenu.TYPE, player, locator);
    }
}

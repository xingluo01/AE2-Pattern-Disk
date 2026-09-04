package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.PatternClassifier;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;
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

    /** Ticks per second (Minecraft). */
    private static final double TICKS_PER_SECOND = 20.0;

    /** Accumulated fractional patterns to transfer (for smooth per-tick rate). */
    private double patternAccumulator = 0;

    private final AppEngInternalInventory inventory = new AppEngInternalInventory(this, TOTAL_SLOTS);
    private final IActionSource actionSource = new MachineSource(this);
    private final IUpgradeInventory upgrades;

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
        markForUpdate();
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
     * Runs the transfer at the speed-card rate: {@code 2^(2x+1)} patterns per second, where x is the
     * number of installed speed cards. Spread across 20 ticks/second with an accumulator.
     */
    private void tickTransfer() {
        int speedCards = installedSpeedCards();
        double patternsPerSecond = Math.pow(2, 2 * speedCards + 1);
        double perTick = patternsPerSecond / TICKS_PER_SECOND;

        patternAccumulator += perTick;
        int toProcess = (int) patternAccumulator;
        if (toProcess <= 0) {
            return;
        }
        patternAccumulator -= toProcess;

        boolean changed = returnBlanksToNetwork();
        changed |= processInputs(toProcess);
        if (changed) {
            saveChanges();
        }
    }

    private int installedSpeedCards() {
        var speedCard = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:speed_card"));
        return speedCard == null ? 0 : upgrades.getInstalledUpgrades(speedCard);
    }

    private int countNonEmptyInputs() {
        int c = 0;
        for (int i = 0; i < INPUT_SLOT_COUNT; i++) {
            if (!inventory.getStackInSlot(inputSlot(i)).isEmpty()) {
                c++;
            }
        }
        return c;
    }

    /**
     * Moves blank patterns from the output slot back into the connected ME network.
     */
    private boolean returnBlanksToNetwork() {
        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        if (output.isEmpty() || !PatternClassifier.isBlankPattern(output)) {
            return false;
        }
        var grid = this.getMainNode().getGrid();
        if (grid == null) {
            return false;
        }
        var storage = grid.getStorageService();
        if (storage == null) {
            return false;
        }
        var blankKey = AEItemKey.of(output);
        long toSend = output.getCount();
        long inserted = storage.getInventory().insert(blankKey, toSend, Actionable.MODULATE, actionSource);
        if (inserted > 0) {
            output.shrink((int) inserted);
            if (output.isEmpty()) {
                inventory.setItemDirect(OUTPUT_SLOT, ItemStack.EMPTY);
            }
            return true;
        }
        return false;
    }

    /**
     * Processes the six input slots, distributing patterns across the application disks.
     *
     * <p>Mode dispatch:</p>
     * <ul>
     *   <li>{@link TransferMode#STORE}：编码样板提取到磁盘；磁盘配方<b>移动</b>到目标盘（源盘被清空）。</li>
     *   <li>{@link TransferMode#COPY}：磁盘配方<b>复制</b>到目标盘（源盘内容保留）。</li>
     * </ul>
     *
     * @param limit maximum number of patterns to transfer this tick.
     */
    private boolean processInputs(int limit) {
        int processed = 0;
        for (int i = 0; i < INPUT_SLOT_COUNT && processed < limit; i++) {
            ItemStack input = inventory.getStackInSlot(inputSlot(i));
            if (input.isEmpty()) {
                continue;
            }

            int transferred = 0;
            if (input.getItem() instanceof PatternDiskItem disk) {
                if (mode == TransferMode.STORE) {
                    // 样板存储：磁盘 → 磁盘（移动：源盘配方转出，目标盘接收）
                    transferred = storeDiskInto(input, disk, limit - processed);
                } else if (mode == TransferMode.COPY) {
                    // 样板复写：磁盘 → 磁盘（复制：源盘内容保留）
                    transferred = copyDiskInto(input, disk, limit - processed);
                }
            } else if (PatternClassifier.isEncodedPatternStack(input)) {
                if (mode == TransferMode.STORE) {
                    // 样板存储：编码样板 → 磁盘
                    transferred = extractPatternInto(input) ? 1 : 0;
                }
            }
            processed += transferred;
        }
        return processed > 0;
    }

    /**
     * 移动语义：将源磁盘中的配方依次移入目标应用磁盘，源盘已移出的配方被移除。
     * 每个配方优先写入第一个满足条件的目标盘：类型匹配（未定型或同类型）、未满、
     * 且不含产生相同主输出的配方（同输出互斥）。
     * 无法安置的配方（类型不匹配/全满/互斥）保留在源盘中，不丢弃。
     *
     * @param limit 本次最多移动的配方数量
     * @return 本次实际移动的配方数
     */
    private int storeDiskInto(ItemStack source, PatternDiskItem sourceDisk, int limit) {
        var sourceContents = sourceDisk.contents(source);
        if (sourceContents.isEmpty() || limit <= 0) {
            return 0;
        }
        var sourceType = sourceContents.type();
        // 损坏数据防御：有配方却未定型（type=null）的源盘无法安全移动，跳过
        if (sourceType == null) {
            return 0;
        }

        boolean changed = false;
        int moved = 0;
        var remaining = new java.util.ArrayList<ItemStack>();
        for (var candidate : sourceContents.patterns()) {
            boolean placed = false;
            if (moved < limit) {
                for (int slot = 0; slot < APPLICATION_SLOT_COUNT; slot++) {
                    ItemStack appDisk = inventory.getStackInSlot(applicationSlot(slot));
                    if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                        continue;
                    }
                    var appContents = app.contents(appDisk);
                    if (appContents.isFull()) {
                        continue;
                    }
                    if (appContents.isTyped() && !appContents.type().equals(sourceType)) {
                        continue; // 类型不匹配的目标盘
                    }
                    if (hasConflictingOutput(appContents, candidate, this.level)) {
                        continue; // 同主产物互斥
                    }
                    var updated = appContents.add(candidate, sourceType);
                    if (updated == null) {
                        continue;
                    }
                    appDisk.set(AEPatternRegistries.DISK_CONTENTS.get(), updated);
                    moved++;
                    changed = true;
                    placed = true;
                    break; // 该配方已移出，处理下一个
                }
            }
            if (!placed) {
                remaining.add(candidate); // 本次未安置：保留在源盘
            }
        }

        if (changed) {
            if (remaining.isEmpty()) {
                source.remove(AEPatternRegistries.DISK_CONTENTS.get());
            } else {
                source.set(AEPatternRegistries.DISK_CONTENTS.get(),
                        new PatternDiskContents(sourceType, sourceContents.capacity(), remaining));
            }
        }
        return moved;
    }

    /**
     * 复制语义：将源磁盘中的每个配方复制到目标应用磁盘，源磁盘内容保持不变。
     * 每个配方优先写入第一个满足条件的目标盘：类型匹配（未定型或同类型）、未满、
     * 且不含产生相同主输出的配方（同输出互斥，天然避免重复复制）。
     *
     * @param limit 本次最多复制的配方数量
     * @return 本次实际复制的配方数
     */
    private int copyDiskInto(ItemStack source, PatternDiskItem sourceDisk, int limit) {
        var sourceContents = sourceDisk.contents(source);
        if (sourceContents.isEmpty() || limit <= 0) {
            return 0;
        }

        int copied = 0;
        // 只读遍历源盘配方，绝不修改源盘（复制而非移动）
        for (var candidate : sourceContents.patterns()) {
            if (copied >= limit) {
                break;
            }
            for (int slot = 0; slot < APPLICATION_SLOT_COUNT; slot++) {
                ItemStack appDisk = inventory.getStackInSlot(applicationSlot(slot));
                if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                    continue;
                }
                var appContents = app.contents(appDisk);
                if (appContents.isFull()) {
                    continue;
                }
                if (appContents.isTyped() && !appContents.type().equals(sourceContents.type())) {
                    continue; // 类型不匹配的目标盘
                }
                // 目标盘已含同输出配方（此前已复制或已存在）则跳过该盘
                if (hasConflictingOutput(appContents, candidate, this.level)) {
                    continue;
                }
                var updated = appContents.add(candidate, sourceContents.type());
                if (updated == null) {
                    continue;
                }
                appDisk.set(AEPatternRegistries.DISK_CONTENTS.get(), updated);
                copied++;
                break; // 该配方已复制，处理下一个配方
            }
        }
        return copied;
    }

    /**
     * Extracts an encoded pattern from an input slot into an application disk, producing a blank pattern
     * into the output slot.
     */
    private boolean extractPatternInto(ItemStack input) {
        var level = this.level;
        if (level == null) {
            return false;
        }
        String type = PatternClassifier.typeOf(input, level);
        if (type == null) {
            return false;
        }

        int targetSlot = -1;
        PatternDiskContents targetContents = null;
        PatternDiskItem targetDisk = null;
        for (int slot = 0; slot < APPLICATION_SLOT_COUNT; slot++) {
            ItemStack appDisk = inventory.getStackInSlot(applicationSlot(slot));
            if (appDisk.isEmpty() || !(appDisk.getItem() instanceof PatternDiskItem app)) {
                continue;
            }
            var contents = app.contents(appDisk);
            if (contents.isFull()) {
                continue;
            }
            if (!contents.isTyped() || contents.type().equals(type)) {
                targetSlot = slot;
                targetContents = contents;
                targetDisk = app;
                break;
            }
        }
        if (targetSlot < 0) {
            return false;
        }

        // Same-result exclusivity: reject if any existing pattern on the target disk produces the
        // same output as the incoming pattern (mutual exclusion among different crafting routes).
        if (hasConflictingOutput(targetContents, input, level)) {
            return false;
        }

        var updated = targetContents.add(input, type);
        if (updated == null) {
            return false;
        }

        ItemStack appDisk = inventory.getStackInSlot(applicationSlot(targetSlot));
        appDisk.set(AEPatternRegistries.DISK_CONTENTS.get(), updated);
        inventory.setItemDirect(inputSlotOf(input), ItemStack.EMPTY);

        // Produce a blank pattern into the output slot.
        ItemStack blank = AEPatternRegistries.blankPattern();
        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        if (output.isEmpty()) {
            inventory.setItemDirect(OUTPUT_SLOT, blank);
        } else if (output.is(blank.getItem())) {
            output.grow(1);
        }
        return true;
    }

    private int inputSlotOf(ItemStack input) {
        for (int i = 0; i < INPUT_SLOT_COUNT; i++) {
            if (inventory.getStackInSlot(inputSlot(i)) == input) {
                return inputSlot(i);
            }
        }
        return INPUT_START;
    }

    /**
     * Returns true if any pattern already on the disk produces the same primary output as {@code candidate}.
     * Used to enforce same-result mutual exclusion (only one crafting route per output may be stored).
     */
    private boolean hasConflictingOutput(PatternDiskContents contents, ItemStack candidate, Level level) {
        return PatternClassifier.hasSamePrimaryOutput(contents.patterns(), candidate, level);
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
        tag.putDouble("patternAccumulator", patternAccumulator);
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
        patternAccumulator = tag.getDouble("patternAccumulator");
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

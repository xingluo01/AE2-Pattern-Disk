package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.BaseInternalInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.core.definitions.AEItems;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.network.AssemblerAnimationPayload;

/**
 * 高效分子装配室的合成执行：八个独立的 {@link CraftUnit} 并发推进，外加动画汇总与存档/掉落。
 *
 * <p>这块状态整体归本类所有：{@code units} 数组（每个单元的 3x3 网格、手动样板、进度、回送方向）、
 * 暴露给外部物品处理器的输出槽视图、本 tick 待发的动画。单个单元的网格装填/清理/投递在
 * {@link AssemblerGridLogistics}。BlockEntity 只留 {@code @Override} 入口、网络视图状态与电源/动画字段，
 * 逐行转调到这里。宿主的网格、世界、位置、存档/唤醒与客户端判据通过 {@link CraftingEnv} 这份窄契约取，
 * 本类不持有 BlockEntity、也不去摸它的一堆成员。</p>
 */
final class AssemblerCrafting {

    private static final int THREADS = PatternDiskAssemblerBlockEntity.THREADS;
    private static final int OUTPUT_SLOT = 9; // gridInv is 10 slots (9 grid + 1 output)

    /**
     * 每 tick 推进的进度，按已装加速卡数取：与扩展分子装配室同一档（AE2 分子装配室的两倍）。
     * 100 进度一件，所以空卡 5 tick 一件、五卡 1 tick 一件。
     */
    private static final int[] SPEED_STEPS = { 20, 26, 34, 40, 50, 100 };

    /** 与 {@link #SPEED_STEPS} 一一对应的税率：每推进 1 点进度要付这么多 AE。 */
    private static final double[] SPEED_TAXES = { 1.0, 1.3, 1.7, 2.0, 2.5, 5.0 };

    /**
     * 合成执行向宿主取的环境，全是快照与回调：网格、世界、位置、客户端判据、存档与唤醒。
     * 本类与 {@link AssemblerGridLogistics} 都只认这份窄契约，不认得 BlockEntity。
     */
    interface CraftingEnv {
        /** 调用时刻的网格（可能为 null）。 */
        IGrid grid();
        /** 调用时刻的世界（可能为 null）。 */
        Level level();
        /** 机器位置（发包与日志定位）。 */
        BlockPos pos();
        boolean isClientSide();
        void saveChanges();
        void alertDevice();
    }

    private final CraftingEnv env;
    private final IUpgradeInventory upgrades;

    private final CraftUnit[] units = new CraftUnit[THREADS];

    /**
     * Read-only view of the eight output slots, handed out to external item handlers (pipes, hoppers,
     * AE2 storage buses). AE2's molecular assembler does the same: its crafting-grid filter only lets
     * extraction through for the output slot. Without this view a product that the neighbouring return
     * node refuses and the ME network will not take has no way out of the machine at all.
     */
    private final InternalInventory exposedOutputs = new BaseInternalInventory() {
        @Override
        public int size() {
            return THREADS;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= THREADS) {
                return ItemStack.EMPTY;
            }
            return units[slot].grid.getStackInSlot(OUTPUT_SLOT);
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            if (slot < 0 || slot >= THREADS) {
                return;
            }
            units[slot].grid.setItemDirect(OUTPUT_SLOT, stack);
            // Defensive redundancy: the unit grid already notifies its host on a direct set, which saves
            // the change and nudges the ticker (see onChangeInventory). Reached only by external
            // extraction, since insertion is refused below.
            env.saveChanges();
            env.alertDevice();
        }

        @Override
        public int getSlotLimit(int slot) {
            // The grid slots hold one item each, and the output slot is no different.
            return slot < 0 || slot >= THREADS ? 0 : units[slot].grid.getSlotLimit(OUTPUT_SLOT);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // Nothing goes in, and refusing here also keeps a caller that assumes AE2's ten-slot crafting
            // grid from tripping the range check in the default implementation.
            return stack;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // Products leave through this view; letting anything in would fight the crafting grid.
            return false;
        }
    };

    /** 单个单元的网格装填、清理与产物投递（含它自己的投递记账）。 */
    private final AssemblerGridLogistics logistics;

    /** 本 tick 要发的合成动画（最多一条），由 {@link #tickingRequest} 在遍历完线程后统一发出。 */
    private @Nullable AEItemKey pendingAnimation;
    private byte pendingAnimationSpeed;

    AssemblerCrafting(CraftingEnv env, InternalInventoryHost inventoryHost,
            IActionSource actionSource, IUpgradeInventory upgrades) {
        this.env = env;
        this.upgrades = upgrades;
        for (int i = 0; i < THREADS; i++) {
            units[i] = new CraftUnit(inventoryHost);
        }
        this.logistics = new AssemblerGridLogistics(env, actionSource);
    }

    /**
     * The output slots of all units, exposed to external item handlers. Only extraction is allowed.
     */
    public InternalInventory getExposedOutputInventory() {
        return exposedOutputs;
    }

    /** Total crafting progress across all threads (0..THREADS*100). */
    public int getCraftingProgressAcrossThreads() {
        int total = 0;
        for (var unit : units) {
            total += (int) Math.min(unit.progress, 100);
        }
        return total;
    }

    /** Number of threads currently executing a plan (busy). */
    public int getRunningThreads() {
        int busy = 0;
        for (var unit : units) {
            if (unit.plan != null || !unit.grid.isEmpty()) {
                busy++;
            }
        }
        return busy;
    }

    /**
     * Read-only view of one unit's crafting grid (3x3 input + output at index 9). The returned
     * inventory is a live view of the unit; the client uses it to render the per-unit grid slot
     * contents while the machine runs. Never modify it from the client.
     */
    public InternalInventory getUnitGrid(int index) {
        if (index < 0 || index >= THREADS) {
            return InternalInventory.empty();
        }
        return units[index].grid;
    }

    /**
     * Read-only view of one unit's manual pattern slot (encoded pattern drives this unit's execution).
     */
    public InternalInventory getUnitPatternInv(int index) {
        if (index < 0 || index >= THREADS) {
            return InternalInventory.empty();
        }
        return units[index].patternInv;
    }

    /** Current crafting progress of one unit, in percent (0..100). */
    public int getUnitProgress(int index) {
        if (index < 0 || index >= THREADS) {
            return 0;
        }
        return (int) Math.min(units[index].progress, 100);
    }

    /** True while a unit executes a plan; the client uses it to keep provider-driven pages undimmed. */
    public boolean isUnitExecuting(int index) {
        if (index < 0 || index >= THREADS) {
            return false;
        }
        return units[index].plan != null;
    }

    /** Current supported crafting pattern for one unit, or {@code null} when the page is idle. */
    public @Nullable IMolecularAssemblerSupportedPattern getCurrentPattern(int index) {
        if (index < 0 || index >= THREADS) {
            return null;
        }
        var unit = units[index];
        if (env.isClientSide()) {
            // The execution plan is server-side state and is never mirrored to the client. Decode the
            // manual pattern from the (synced) pattern slot instead, so slot validation and the
            // disabled-slot overlay match the server-side state.
            return decodeManualPattern(unit);
        }
        if (unit.plan == null && !unit.patternInv.isEmpty()) {
            // Decode the manually inserted pattern on demand so slot validation works immediately.
            var supported = decodeManualPattern(unit);
            if (supported != null) {
                unit.plan = supported;
                unit.planSource = unit.patternInv.getStackInSlot(0).copy();
            }
        }
        return unit.plan;
    }

    /** Decodes the encoded pattern manually inserted into a unit's slot, or {@code null} if unusable. */
    private @Nullable IMolecularAssemblerSupportedPattern decodeManualPattern(CraftUnit unit) {
        var level = env.level();
        if (level == null || unit.patternInv.isEmpty()) {
            return null;
        }
        var item = unit.patternInv.getStackInSlot(0);
        if (item.isEmpty()) {
            return null;
        }
        return PatternDetailsHelper.decodePattern(item, level) instanceof IMolecularAssemblerSupportedPattern supported
                ? supported
                : null;
    }

    /**
     * World-render helper: returns the first output of the earliest active unit, or {@link ItemStack#EMPTY}
     * when no unit is currently assembling. The renderer draws this inside the machine to mirror the
     * original molecular assembler's in-world item depiction.
     */
    public ItemStack getRenderedAssemblyItem() {
        for (int i = 0; i < THREADS; i++) {
            if (!isUnitBusy(i)) {
                continue;
            }
            var pattern = units[i].plan;
            if (pattern == null) {
                continue;
            }
            var primary = pattern.getPrimaryOutput();
            if (primary != null && primary.what() instanceof AEItemKey itemKey) {
                return itemKey.toStack((int) Math.max(1, primary.amount()));
            }
        }
        return ItemStack.EMPTY;
    }

    /** True if one unit is currently executing a plan or holding leftover output. */
    public boolean isUnitBusy(int index) {
        if (index < 0 || index >= THREADS) {
            return false;
        }
        return units[index].plan != null || !units[index].grid.isEmpty();
    }

    /**
     * Accepts a pattern from any AE2 provider and dispatches it to the first idle unit.
     */
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] table,
            Direction where) {
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern pattern)) {
            return false;
        }
        for (var unit : units) {
            // A unit holding a manually inserted pattern executes that pattern and does not
            // accept provider-pushed jobs (mirrors AE2's pattern-inventory gating).
            if (unit.plan == null && unit.grid.isEmpty() && unit.patternInv.isEmpty()) {
                unit.plan = pattern;
                unit.planSource = ItemStack.EMPTY;
                unit.progress = 0;
                unit.pushDirection = where;
                logistics.fillGrid(unit, table, pattern);
                env.alertDevice();
                return true;
            }
        }
        return false; // all units busy
    }

    public boolean acceptsPlans() {
        // A unit running a manually inserted pattern does not take provider-pushed jobs, so the
        // machine only accepts new plans while at least one unit is completely idle.
        for (var unit : units) {
            if (unit.plan == null && unit.grid.isEmpty() && unit.patternInv.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, false);
    }

    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        boolean anyBusy = false;
        for (var unit : units) {
            anyBusy |= advanceUnit(unit, ticksSinceLastCall);
        }
        // 一台机器只留一份动画状态，按线程逐次发包只会互相覆盖；每 tick 汇总成最多一个包（EAE 同做法）。
        var animation = pendingAnimation;
        if (animation != null) {
            pendingAnimation = null;
            PacketDistributor.sendToPlayersNear(
                    node.getLevel(), null,
                    env.pos().getX(), env.pos().getY(), env.pos().getZ(),
                    32,
                    new AssemblerAnimationPayload(env.pos(), pendingAnimationSpeed, animation));
        }
        return anyBusy ? TickRateModulation.FASTER : TickRateModulation.SLEEP;
    }

    /**
     * Advances one crafting unit; returns true if it is still busy.
     */
    private boolean advanceUnit(CraftUnit unit, int ticksSinceLastCall) {
        // Keep retrying both finished output and container remainders until all leftovers are exported.
        ItemStack output = unit.grid.getStackInSlot(OUTPUT_SLOT);
        if (!output.isEmpty()) {
            logistics.pushOut(unit, output);
            if (!unit.grid.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
                return true;
            }
            // The output slot is free again: move any grid item that is no longer valid for the
            // current pattern into it, so stale inputs cannot block the unit forever.
            logistics.ejectHeldItems(unit);
        }
        // Keep the plan in sync with the manual pattern slot: while the slot holds an item it is
        // authoritative, so a newly inserted or swapped pattern takes over immediately and a removed
        // pattern releases the grid (mirrors AE2's recalculatePlan).
        var slotPattern = unit.patternInv.getStackInSlot(0);
        if (slotPattern.isEmpty()) {
            if (!unit.planSource.isEmpty()) {
                unit.plan = null;
                unit.planSource = ItemStack.EMPTY;
                unit.progress = 0;
            }
            if (unit.plan == null) {
                boolean pending = logistics.drainRemainders(unit);
                // Release the push direction only once the whole job is out - grid drained and the
                // output slot empty - so a product that is still on its way cannot slip onto the
                // network-only path. A page only counts as self-executing while the direction is null,
                // and a stale one would keep the next manual pattern on this page on the provider
                // return path instead of the network-only one.
                if (!pending && unit.grid.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
                    unit.pushDirection = null;
                }
                return pending;
            }
        } else if (unit.plan == null || !ItemStack.isSameItemSameComponents(slotPattern, unit.planSource)) {
            var supported = decodeManualPattern(unit);
            if (supported == null) {
                // The inserted pattern cannot be decoded: release the grid instead of stalling.
                unit.plan = null;
                unit.planSource = ItemStack.EMPTY;
                unit.progress = 0;
                return logistics.drainRemainders(unit);
            }
            // The manual pattern takes this page over. Whatever is still on the grid belongs to the
            // previous job, so it goes back under that job's contract (return node first) and the push
            // direction is released - otherwise a page that is self-executing from now on would keep
            // returning to the neighbour instead of the network. A provider job can also be taken over
            // while its plan is still running, which is why this is not covered by the idle branch above.
            logistics.drainRemainders(unit);
            unit.pushDirection = null;
            unit.plan = supported;
            unit.planSource = slotPattern.copy();
            unit.progress = 0;
        }

        // Self-executing page guard: never accumulate progress unless the grid can actually be
        // assembled, so a material shortage can not consume progress or destroy partial inputs.
        // tryFillGridFromNetwork 的填充是原子的（要么补齐、要么一格不放），见那里的注释。
        if (!unit.patternInv.isEmpty() && !logistics.tryFillGridFromNetwork(unit)) {
            return true;
        }

        int speedCards = Math.min(upgrades.getInstalledUpgrades(AEItems.SPEED_CARD), SPEED_STEPS.length - 1);
        int speed = SPEED_STEPS[speedCards];
        double gathered = gatherPower(ticksSinceLastCall, speed, SPEED_TAXES[speedCards]);
        if (gathered <= 0) {
            // 没电就停在原地：进度不动，页面保持忙碌，等电网回电后接着走。
            return true;
        }
        unit.progress += gathered;
        if (unit.progress >= 100) {
            // 只扣一件的量：余数已经付过电，tick 尖峰时留给下一 tick 继续做。
            unit.progress -= 100;
            var level = env.level();
            if (level != null) {
                var positioned = logistics.craftGrid(unit);
                var craftInput = positioned.input();
                var plan = unit.plan;
                ItemStack result = plan.assemble(craftInput, level);
                if (result.isEmpty()) {
                    // A failed recipe must release its inputs instead of retrying forever or
                    // treating ordinary inputs as container remainders.
                    unit.plan = null;
                    return logistics.drainRemainders(unit);
                }

                // 产物是样板对象内部缓存的那个 ItemStack 实例：AECraftingPattern.assemble 每次都返回
                // 同一个实例（构造期算一次并缓存在 output 字段，普通路径直接 return 它）。不复制就放进
                // 输出槽并回送，回送时的 shrink 会把这个共享实例削到数量 0——样板对象的产物就变成了空的，
                // 此后 canAssemble 恒假、机器永远不再合成（且供应器/CPU 长期持有同一个样板实例，污染会跨机）。
                // AE2 自己的分子装配室同理：它回送前先 output.copy()。
                unit.grid.setItemDirect(OUTPUT_SLOT, result.copy());

                // 动画包在这一 tick 末尾由 tickingRequest 统一发，见那里的注释。
                var itemKey = AEItemKey.of(result);
                if (itemKey != null && pendingAnimation == null) {
                    pendingAnimation = itemKey;
                    pendingAnimationSpeed = (byte) speed;
                }

                // A successful craft consumes the input grid; put container remainders back into the
                // exact slots of the recipe bounding box inside the 3x3 grid.
                var remainders = plan.getRemainingItems(craftInput);
                for (int i = 0; i < PatternDiskAssemblerBlockEntity.GRID_SIZE; i++) {
                    unit.grid.setItemDirect(i, ItemStack.EMPTY);
                }
                for (int row = 0; row < craftInput.height(); row++) {
                    for (int col = 0; col < craftInput.width(); col++) {
                        var remainder = remainders.get(col + row * craftInput.width());
                        if (!remainder.isEmpty()) {
                            unit.grid.setItemDirect(col + positioned.left() + (row + positioned.top()) * 3, remainder);
                        }
                    }
                }
                // A manually inserted pattern stays in the unit and drives the next craft; a
                // provider-pushed plan is consumed with this craft.
                if (unit.planSource.isEmpty()) {
                    unit.plan = null;
                }
                logistics.ejectHeldItems(unit);
                return true;
            }
        }
        return true;
    }

    /**
     * 本 tick 能推进多少进度：先按「档位速率 × 税率」向网络提电，再按税率折回进度。
     *
     * <p>与 AE2 分子装配室、扩展分子装配室同一口径：电不够时只推进提到的那一部分，而不是整 tick 白跑；
     * 加速卡买的是速率，代价是每点进度更贵。单次提取封顶 5000 AE，与上游一致。</p>
     */
    private double gatherPower(int ticksSinceLastCall, int speed, double tax) {
        var grid = env.grid();
        if (grid == null) {
            return 0;
        }
        var energy = grid.getEnergyService();
        if (energy == null) {
            return 0;
        }
        double wanted = Math.min(ticksSinceLastCall * speed * tax, 5000);
        return energy.extractAEPower(wanted, Actionable.MODULATE, PowerMultiplier.CONFIG) / tax;
    }

    /** 存档：八个单元的两份库存、进度与升级卡；{@code super} 那半段留在 BlockEntity。 */
    public void writeToNbt(CompoundTag tag, HolderLookup.Provider registries) {
        for (int i = 0; i < THREADS; i++) {
            units[i].grid.writeToNBT(tag, "unit" + i, registries);
            units[i].patternInv.writeToNBT(tag, "unitPattern" + i, registries);
            tag.putDouble("unitProgress" + i, units[i].progress);
        }
        upgrades.writeToNBT(tag, "upgrades", registries);
    }

    /** 读档：与 {@link #writeToNbt} 一一对应。 */
    public void readFromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        for (int i = 0; i < THREADS; i++) {
            units[i].grid.readFromNBT(tag, "unit" + i, registries);
            units[i].patternInv.readFromNBT(tag, "unitPattern" + i, registries);
            units[i].progress = tag.getDouble("unitProgress" + i);
        }
        upgrades.readFromNBT(tag, "upgrades", registries);
    }

    /** 掉落：八个单元的网格/样板槽与升级卡；{@code super} 那半段留在 BlockEntity。 */
    public void addDrops(List<ItemStack> drops) {
        for (int i = 0; i < THREADS; i++) {
            for (int s = 0; s < units[i].grid.size(); s++) {
                if (!units[i].grid.getStackInSlot(s).isEmpty()) {
                    drops.add(units[i].grid.getStackInSlot(s));
                }
            }
            if (!units[i].patternInv.isEmpty()) {
                drops.add(units[i].patternInv.getStackInSlot(0));
            }
        }
        for (int i = 0; i < upgrades.size(); i++) {
            ItemStack u = upgrades.getStackInSlot(i);
            if (!u.isEmpty()) {
                drops.add(u);
            }
        }
    }

    /** 清空：八个单元与升级卡；{@code super} 那半段留在 BlockEntity。 */
    public void clear() {
        for (var unit : units) {
            unit.grid.clear();
            unit.patternInv.clear();
            unit.plan = null;
            unit.progress = 0;
        }
        upgrades.clear();
    }
}

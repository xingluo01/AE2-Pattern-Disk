package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.me.helpers.MachineSource;
import appeng.me.storage.CompositeStorage;
import appeng.parts.automation.StackWorldBehaviors;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.DiskSlotVersions;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTier;
import io.github.lounode.ae2pattern.api.PatternDiskContents;
import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;

/**
 * A {@link PatternProviderLogic} whose available patterns are expanded from the contents of inserted
 * pattern disks, instead of a fixed slot-per-pattern inventory.
 *
 * <p>We do NOT touch AE2's private {@code patterns}/{@code patternInputs} fields. Instead we write the
 * disk patterns into the parent's own {@code patternInventory} (via {@link #getPatternInv()}) and let the
 * parent's {@link #updatePatterns()} decode and register them. This stays within public API.</p>
 *
 * <p>The disk inventory is supplied lazily via a {@link Supplier} so this logic may be constructed during
 * the block-entity's parent constructor before the disk inventory field is initialized.</p>
 */
public class PatternDiskProviderLogic extends PatternProviderLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternDiskProviderLogic.class);

    private final Supplier<AppEngInternalInventory> diskInventorySupplier;

    /** 宿主与节点自己留一份：父类那两个字段是 private，而多面投递要靠它们定位邻居。 */
    private final PatternProviderLogicHost host;
    private final IManagedGridNode mainNode;
    /** 与父类同源的动作源（同一个 {@code MachineSource} 形态，挂在同一个网格节点上）。 */
    private final IActionSource actionSource;

    /**
     * 上次重建时的磁盘内容快照（每槽一项），由 {@link DiskSlotVersions#ofInventory} 生成、用
     * {@link DiskSlotVersions#sameAs} 比较；{@code null} 表示还没重建过。
     *
     * <p>跳过未变的重建很重要——重建会整体重写镜像——而这项检查本身的成本必须低于它跳过的工作；快照为什么
     * 能用引用比较、以及它对「原地修改内容」看不见，见 {@link DiskSlotVersions} 的类注释。</p>
     */
    private List<Object> lastDiskVersions;

    public PatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            int diskSlots, Supplier<AppEngInternalInventory> diskInventorySupplier) {
        super(mainNode, host, mirrorCapacity(diskSlots));
        this.diskInventorySupplier = diskInventorySupplier;
        this.lastDiskVersions = null;
        this.host = host;
        this.mainNode = mainNode;
        this.actionSource = new MachineSource(mainNode::getNode);
    }

    /**
     * 高级处理样板的「多面输入」：一张样板若给每个输入各指定了一个接入面，投递时就按那张表决定「这份材料
     * 从哪一面接进邻居」。
     *
     * <p><b>面选的是接入方式，不是邻居</b>——邻居照旧由候选方向决定，与普通投递同一套。AdvancedAE 自己也
     * 就是这么做的（它的 {@code AdvPatternProviderLogic.pushPattern} 遍历候选方向、对每个方向试一次方向
     * 投递，失败再走普通投递），而它的 {@code AdvPatternProviderTargetCache.find} 也只是把查 ME 能力留给
     * 「邻居对着我」的那一面、把声明面交给外部存储策略查询。声明面对 AE2 自己的容器与机器没有影响，它真正
     * 起作用的地方是那些按面注册了外部存储策略的机器（管道、带面限定的接入器）。</p>
     *
     * <p>探不到 AdvancedAE、样板没声明面、或这个方向上整批投不进去，都原样交给父类——退回默认行为，不少投
     * 也不崩。</p>
     *
     * <p><b>一处已知取舍</b>：父类的 {@code onPushPatternSuccess} 是 private，这条路（含机器分支）成功后
     * 调不到它，于是「锁定合成」的解锁（直到脉冲 / 直到结果）不发生——开着锁的供应器推完一张多面样板会保持
     * 锁定，直到玩家手动解开。要补上只能等 AE2 把它露出来；在子类重写一遍锁语义会与父类那几个同样 private
     * 的字段脱节。</p>
     */
    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!AdvPatternSupport.hasDirectionalInputs(patternDetails)) {
            return super.pushPattern(patternDetails, inputHolder);
        }
        // 与父类同一批前置守卫。方向投递是另一条路，不是绕过它们的特权：这四条里「锁定合成」最要紧——
        // AE2 全仓只在父类的 pushPattern 里读它，漏掉这一条就等于这类样板上的锁完全失效，开着锁也照推。
        if (isBusy() || !mainNode.isActive()
                || getCraftingLockedReason() != LockCraftingMode.NONE
                || !getAvailablePatterns().contains(patternDetails)) {
            return false;
        }
        if (pushDirectionally(patternDetails, inputHolder)) {
            return true;
        }
        return super.pushPattern(patternDetails, inputHolder);
    }

    /**
     * AdvancedAE 式的方向投递：邻居照旧按候选方向挑，声明面只决定「从哪一面接入它」。
     *
     * <p>两趟：先跨所有方向把机器试完，再按面把整批投进某个方向上的邻居。机器优先是父类的规矩——能整张收下
     * 样板的机器自己会用这张样板，材料不该塞进它的存储。</p>
     *
     * <p>先模拟后实投，且要求每个输入都能被它那个面全量收下，否则整批放弃。上游只要求「收得下一点」而把残量
     * 丢进发送队列慢慢补，本模组没有那份记账能力：父类的 {@code addToSendList} 是 private，投出去却收不下的
     * 部分就是净丢。要求全额是拿「这一轮不投」换「一件不丢」。实投短少仍会记日志。</p>
     */
    private boolean pushDirectionally(IPatternDetails details, KeyCounter[] inputHolder) {
        var be = host.getBlockEntity();
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        var sides = activeSides();

        // 机器那一趟要跨完所有方向才轮到别的：边试机器边投存储的话，A 方向的机器拒收、B 方向的机器会收时，
        // 样板会被 A 的存储抢走。
        var takenByMachine = EnumSet.noneOf(Direction.class);
        for (var facing : sides) {
            var adjacentPos = be.getBlockPos().relative(facing);
            var fromUs = facing.getOpposite();
            var machine = ICraftingMachine.of(level, adjacentPos, fromUs);
            if (machine != null && machine.acceptsPlans()) {
                if (machine.pushPattern(details, inputHolder, fromUs)) {
                    return true;
                }
                // 拒收：这个方向本轮不再当普通邻居用（与父类同一处理）。
                takenByMachine.add(facing);
            }
        }

        // 样板自己说了不让往普通库存里推（分子装配室类）就不要推，与父类同一判据。
        if (!details.supportsPushInputsToExternalInventory()) {
            return false;
        }

        var patternInputs = isBlocking() ? declaredInputs(details) : null;

        for (var facing : sides) {
            if (takenByMachine.contains(facing)) {
                continue;
            }
            var adjacentPos = be.getBlockPos().relative(facing);
            var adjacentBe = level.getBlockEntity(adjacentPos);
            // 邻居对着我们的那一面：查它的 ME 能力用这个，与样板声明无关。
            var fromUs = facing.getOpposite();

            var targets = new HashMap<AEKey, PatternProviderTarget>();
            var usable = true;
            for (var counter : inputHolder) {
                var key = counter.getFirstKey();
                if (key == null) {
                    continue;
                }
                // 面按输入槽的首个 key 取（一套输入一个面，同上游），但整槽的 key 都要映到同一个接入点：
                // sink 会把槽里每个 key 都推出来，只登记首 key 会让其余的在下面查不到而静默消失。
                var declared = AdvPatternSupport.accessSide(details, key);
                var target = targetAt(level, adjacentPos, adjacentBe, fromUs,
                        declared != null ? declared : fromUs, actionSource);
                if (target == null || !accepts(target, counter)
                        || (patternInputs != null && target.containsPatternInput(patternInputs))) {
                    usable = false;
                    break;
                }
                for (var slotKey : counter.keySet()) {
                    targets.put(slotKey, target);
                }
            }
            if (!usable || targets.isEmpty()) {
                continue;
            }

            // 交给样板自己派发：它按内部顺序把材料交给 sink，本模组只负责把每个 key 送到它那个接入点。
            details.pushInputsToExternalInventory(inputHolder, (what, amount) -> {
                var target = targets.get(what);
                if (target == null) {
                    // 样板整理/合并过输入时可能冒出新 key：按它自己的声明面现取一个，不丢。
                    var declared = AdvPatternSupport.accessSide(details, what);
                    target = targetAt(level, adjacentPos, adjacentBe, fromUs,
                            declared != null ? declared : fromUs, actionSource);
                    if (target != null) {
                        // 同一个 key 可能被重复推好几次，记下来省后面的查询。
                        targets.put(what, target);
                    }
                }
                long inserted = target == null ? 0 : target.insert(what, amount, Actionable.MODULATE);
                if (inserted < amount) {
                    LOGGER.warn("Pattern disk provider at {} handed over {} x{} but its target took only {}; "
                            + "the shortfall is not recoverable", be.getBlockPos(), what, amount, inserted);
                }
            });
            return true;
        }
        return false;
    }

    /**
     * 自己组装一个接入点，因为 AE2 的 {@code PatternProviderTarget.get} 只接受一个面。
     *
     * <p>那一个面在它内部要兼任两件事：查 ME 能力、查外部存储策略。高级样板的按面接入正要求这两件事分开——
     * 能力按「邻居对着我」那一面查（本来就该如此），策略按样板声明的面查（让按面注册策略的机器能在它自己认的
     * 那个面上被接进来）。公开 API 表达不了这个组合，所以这里自己走一遍它的两个分支。声明面对 AE2 的容器与
     * 机器没有影响，差异只在带面限定的机器上。</p>
     *
     * <p>不缓存：父类与 AdvancedAE 都按面缓存了这一层，但这条路径一次投递最多问几个目标，而缓存要连带处理
     * 失效语义（方块被换掉、能力被重注册）。省下的这点查询换不来一份需要维护的失效逻辑。</p>
     */
    @Nullable
    private static PatternProviderTarget targetAt(ServerLevel level, BlockPos pos, @Nullable BlockEntity be,
            Direction meSide, Direction strategySide, IActionSource src) {
        var storage = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, be.getBlockPos(), be.getBlockState(), be, meSide)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, meSide);
        if (storage != null) {
            return wrap(storage, src);
        }

        var strategies = StackWorldBehaviors.createExternalStorageStrategies(level, pos, strategySide);
        var external = new IdentityHashMap<AEKeyType, MEStorage>(2);
        for (var entry : strategies.entrySet()) {
            var wrapper = entry.getValue().createWrapper(false, () -> {});
            if (wrapper != null) {
                external.put(entry.getKey(), wrapper);
            }
        }
        return external.isEmpty() ? null : wrap(new CompositeStorage(external), src);
    }

    private static PatternProviderTarget wrap(MEStorage storage, IActionSource src) {
        return new PatternProviderTarget() {
            @Override
            public long insert(AEKey what, long amount, Actionable type) {
                return storage.insert(what, amount, type, src);
            }

            @Override
            public boolean containsPatternInput(Set<AEKey> patternInputs) {
                for (var stack : storage.getAvailableStacks()) {
                    if (patternInputs.contains(stack.getKey().dropSecondary())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static boolean accepts(PatternProviderTarget target, KeyCounter counter) {
        for (var key : counter.keySet()) {
            long amount = counter.get(key);
            if (target.insert(key, amount, Actionable.SIMULATE) < amount) {
                return false;
            }
        }
        return true;
    }

    /**
     * 自己的可用面：与 AE2 同一判据——宿主声明的目标面，减去连着同网其他供应器与接口的那些面。
     *
     * <p>排除很重要：那些面背面坐着的是另一台拿样板干活的机器，把输入推过去等于把活派给它。</p>
     */
    private Set<Direction> activeSides() {
        var declared = host.getTargets();
        var sides = declared.isEmpty() ? EnumSet.noneOf(Direction.class) : EnumSet.copyOf(declared);
        var node = mainNode.getNode();
        if (node == null) {
            return sides;
        }
        for (var entry : node.getInWorldConnections().entrySet()) {
            var other = entry.getValue().getOtherSide(node);
            if (other.getOwner() instanceof PatternProviderLogicHost
                    || (other.getOwner() instanceof InterfaceLogicHost
                            && other.getGrid().equals(mainNode.getGrid()))) {
                sides.remove(entry.getKey());
            }
        }
        return sides;
    }

    /** 这张样板声明的输入 key 集合（与父类同一口径：丢掉二级变体）。 */
    private static Set<AEKey> declaredInputs(IPatternDetails details) {
        var keys = new HashSet<AEKey>();
        for (var input : details.getInputs()) {
            for (var candidate : input.getPossibleInputs()) {
                if (candidate != null) {
                    keys.add(candidate.what().dropSecondary());
                }
            }
        }
        return keys;
    }

    /**
     * How many slots the mirror needs: every disk slot the machine has, times the largest disk tier.
     *
     * <p>Derived rather than fixed, because a fixed number has to be right for every machine and every
     * tier at once. The one that used to be here - 1024 - was exactly one full disk, so a provider
     * holding nine of them exposed only the first and silently dropped the rest.</p>
     *
     * <p>The slot count is a parameter rather than a read of the disk inventory: this logic is built
     * inside the host's own {@code super()} constructor, before that inventory field exists.</p>
     *
     * <p>The upper bound comes from this mod's own tiers, which is what these machines' disks can hold -
     * every write path in the mod clamps to a tier capacity. A component edited out of band to claim more
     * than the largest tier would not be covered; covering that would mean deriving the bound from the
     * disks at runtime, and this constructor runs before the disks can be read.</p>
     */
    private static int mirrorCapacity(int diskSlots) {
        int perDisk = 0;
        for (PatternDiskTier tier : PatternDiskTier.values()) {
            perDisk = Math.max(perDisk, tier.capacity());
        }
        return diskSlots * perDisk;
    }

    /**
     * Rebinds the parent's pattern inventory to the encoded patterns on all inserted disks, then lets the
     * parent rebuild its pattern list.
     *
     * <p>Strategy 2 (static resolver): take the disk state again; if it is unchanged since the last rebuild
     * we short-circuit and skip the expensive {@code clear + rewrite + updatePatterns()} so a burst of
     * same-content invocations coalesces into a single rebuild. A content change (slot in/out or disk
     * repattern) rebuilds fresh in one pass.</p>
     */
    public boolean refreshPatternsFromDisks() {
        return refreshPatternsFromDisks(false);
    }

    /**
     * @param force rebuild even when the disks look unchanged. The mirror can be rewritten from under us
     *              while the disks stay where they are - importing a memory card clears it and then lets
     *              AE2 write into it - and the state check cannot see that, so those callers pass true.
     */
    public boolean refreshPatternsFromDisks(boolean force) {
        var diskInventory = diskInventorySupplier.get();
        if (diskInventory == null) {
            return false;
        }

        var versions = DiskSlotVersions.ofInventory(diskInventory);
        if (!force && DiskSlotVersions.sameAs(lastDiskVersions, versions)) {
            return false; // unchanged: coalesce, skip full rebuild
        }

        InternalInventory patternInv = getPatternInv();
        if (patternInv == null) {
            return false;
        }

        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < diskInventory.size(); i++) {
            ItemStack diskStack = diskInventory.getStackInSlot(i);
            if (diskStack.isEmpty() || !(diskStack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            PatternDiskContents contents = disk.contents(diskStack);
            all.addAll(contents.patterns());
        }

        rebuildingMirror = true;
        try {
            patternInv.clear();
            for (int i = 0; i < all.size() && i < patternInv.size(); i++) {
                // copy(): the stacks come straight out of the disks' components, and a holder of the mirror
                // must not be able to mutate what the disk shows.
                patternInv.setItemDirect(i, all.get(i).copy());
            }
        } finally {
            rebuildingMirror = false;
        }

        // Parent decodes patternInventory into its patterns list and requests a grid update.
        updatePatterns();

        lastDiskVersions = versions;
        return true;
    }

    // The parent's updatePatterns re-reads patternInventory; our refresh already filled it.
    @Override
    public void updatePatterns() {
        super.updatePatterns();
    }

    /**
     * True while {@link #refreshPatternsFromDisks} is rewriting the mirror.
     *
     * <p>Every write into the mirror notifies the host, and the host's answer is a full
     * {@code updatePatterns()} - which walks every slot and decodes every pattern. Left alone, one rebuild
     * runs that once per written pattern instead of once at the end: a nine-disk mirror holds 9216, so that
     * is thousands of full passes against one. The flag collapses the burst, and the rebuild calls
     * {@code updatePatterns()} itself once the mirror is whole.</p>
     */
    private boolean rebuildingMirror;

    /**
     * Empties the mirror with a single parent update instead of one per slot.
     *
     * <p>{@code clear()} empties slot by slot and every slot notifies, so the plain call this replaces ran
     * a full {@code updatePatterns()} per slot - over the whole mirror each time. The callers only need the
     * empty end state, so the burst is collapsed the same way a rebuild collapses it. Only the mirror is
     * written while that flag is up; the disk inventory is not touched.</p>
     */
    public void clearMirror() {
        InternalInventory patternInv = getPatternInv();
        if (patternInv == null) {
            return;
        }
        rebuildingMirror = true;
        try {
            patternInv.clear();
        } finally {
            rebuildingMirror = false;
        }
        updatePatterns();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (rebuildingMirror) {
            // Keep the parent's change-is-persisted semantics, drop the redundant re-decode.
            saveChanges();
            return;
        }
        super.onChangeInventory(inv, slot);
    }
}

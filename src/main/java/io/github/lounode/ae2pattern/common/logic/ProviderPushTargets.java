package io.github.lounode.ae2pattern.common.logic;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.me.storage.CompositeStorage;
import appeng.parts.automation.StackWorldBehaviors;

import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;

/**
 * 协作类与宿主之间的窄接口：推送块只需要这几项宿主能力，不持有整个 logic 对象。
 */
interface PushHost {

    @Nullable
    BlockEntity blockEntity();

    IActionSource actionSource();

    Set<Direction> activeSides();

    Iterable<IPatternDetails> availablePatterns();

    void resetCraftingLock();

    boolean isBlocking();
}

/**
 * 推送与目标解析：方向投递、按声明面解析接入点、智能阻挡 / 原生阻挡判据，以及输入集合缓存。
 *
 * <p>「一次投递的结果」「哪些输入属于已装样板」「部分投出的纸条」这些状态与它们的不变量整体在这里；
 * 宿主只在 {@code pushPattern} / {@code pushWithOwnRules} / 样板重建时转调进来。</p>
 */
final class ProviderPushTargets {

    /** 沿用宿主的 logger 名，日志分类不变。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternDiskProviderLogic.class);

    /** 一次投递的结果：完全投出、只投出一部分（余量净丢、不得重投）、完全未投。 */
    private enum Delivery { NONE, PARTIAL, ALL }

    private final PushHost host;
    private final ProviderSettings settings;

    /** 全部已装样板的输入集合缓存（阻挡档判据用）；样板重建时作废。 */
    private Set<AEKey> cachedPatternInputs;

    /** 只报一次的「部分投出」纸条，避免同一样板长期短少时刷屏。 */
    private final Set<IPatternDetails> partialWarned = new HashSet<>();

    ProviderPushTargets(PushHost host, ProviderSettings settings) {
        this.host = host;
        this.settings = settings;
    }

    /** 样板集合变了：阻挡档的全量输入缓存得重建。 */
    void invalidatePatternInputs() {
        cachedPatternInputs = null;
    }

    /** 一次投进目标。{@code inventoryOnly} 为真时跳过机器（机器只认「一份」语义，合并批不能塞给它）。
     *
     * @return 是否算已交付（{@code Delivery != NONE}） */
    boolean deliver(IPatternDetails details, KeyCounter[] inputs, Set<AEKey> ownInputs,
            boolean inventoryOnly) {
        var be = host.blockEntity();
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        var sides = host.activeSides();
        var takenByMachine = EnumSet.noneOf(Direction.class);

        if (!inventoryOnly) {
            for (var facing : sides) {
                var adjacentPos = be.getBlockPos().relative(facing);
                var fromUs = facing.getOpposite();
                var machine = ICraftingMachine.of(level, adjacentPos, fromUs);
                if (machine != null && machine.acceptsPlans()) {
                    if (machine.pushPattern(details, inputs, fromUs)) {
                        host.resetCraftingLock();
                        return true;
                    }
                    takenByMachine.add(facing);
                }
            }
        }

        if (!details.supportsPushInputsToExternalInventory()) {
            return false;
        }

        for (var facing : sides) {
            if (takenByMachine.contains(facing)) {
                continue;
            }
            var adjacentPos = be.getBlockPos().relative(facing);
            var adjacentBe = level.getBlockEntity(adjacentPos);
            var fromUs = facing.getOpposite();

            if (settings.blockingMode() == ProviderBlockingMode.SMART) {
                if (hasForeignInput(level, adjacentPos, adjacentBe, fromUs, ownInputs)) {
                    // 目标里已经有别的配方的原料：换一个目标，这才是「智能阻挡」。
                    continue;
                }
            } else if (host.isBlocking() && containsAnyPatternInput(level, adjacentPos, adjacentBe, fromUs)) {
                // 阻挡档（AE2 原生）：目标已有任一已注册样板的输入就换目标。延着 boost 走这条路径时
                // 父类不会替我们做这个判定，所以在这里补上，与原生口径一致。
                continue;
            }

            // 预检与实投共用同一份 key→target 映射：两处若按不同面解析，会预检出 A 目标、实投去 B 目标。
            var targets = resolveTargets(details, inputs, level, adjacentPos, adjacentBe, fromUs);
            if (targets.isEmpty() || !acceptsAll(targets, inputs)) {
                continue;
            }

            var result = pushToTarget(details, inputs, targets);
            if (result != Delivery.NONE) {
                // 全额与部分都算已交付：材料已经进了目标，重投就是复制。
                // 锁定合成只能清、不能重新武装（父类 onPushPatternSuccess 是 private），但清锁仍要做。
                host.resetCraftingLock();
                if (result == Delivery.PARTIAL && notePartialWarning(details)) {
                    LOGGER.warn("Pattern disk provider at {} handed over part of {}; the shortfall is not "
                            + "recoverable and will not be retried",
                            be.getBlockPos(), details.getClass().getSimpleName());
                }
                return true;
            }
        }
        return false;
    }

    /** 目标里是否有任何一张已注册样板的输入（与父类 {@code patternInputs} 同口径，按样板集合缓存）。 */
    private boolean containsAnyPatternInput(ServerLevel level, BlockPos pos, @Nullable BlockEntity be,
            Direction meSide) {
        var all = allPatternInputs();
        if (all.isEmpty()) {
            return false;
        }
        var target = targetAt(level, pos, be, meSide, meSide, host.actionSource());
        return target != null && target.containsPatternInput(all);
    }

    /** 全部已装样板的输入集合；样板一变就重建。 */
    private Set<AEKey> allPatternInputs() {
        if (cachedPatternInputs == null) {
            var all = new HashSet<AEKey>();
            for (var pattern : host.availablePatterns()) {
                all.addAll(declaredInputs(pattern));
            }
            cachedPatternInputs = all;
        }
        return cachedPatternInputs;
    }

    /** 按样板声明的接入面把每个 key 映到一个目标；任一 key 解析不到目标就整份放弃。 */
    private Map<AEKey, PatternProviderTarget> resolveTargets(IPatternDetails details, KeyCounter[] inputs,
            ServerLevel level, BlockPos adjacentPos, @Nullable BlockEntity adjacentBe, Direction fromUs) {
        var targets = new HashMap<AEKey, PatternProviderTarget>();
        for (var counter : inputs) {
            if (counter == null) {
                continue;
            }
            for (var key : counter.keySet()) {
                var declared = AdvPatternSupport.accessSide(details, key);
                var target = targetAt(level, adjacentPos, adjacentBe, fromUs,
                        declared != null ? declared : fromUs, host.actionSource());
                if (target == null) {
                    return Map.of();
                }
                targets.put(key, target);
            }
        }
        return targets;
    }

    /** @return true 表示这张样板还没报过，应当打一条 warn */
    private boolean notePartialWarning(IPatternDetails details) {
        if (partialWarned.size() > 64) {
            // 有界：被喂进无数种样板时宁可重报，也不能无限增长。
            partialWarned.clear();
        }
        return partialWarned.add(details);
    }

    /** 用同一份 key→target 映射做全额模拟。
     *
     * <p>逐 key 独立模拟，同一个目标上多个 key 的总量可能被高估（模拟不累计）——因此 PARTIAL 仍可能发生，
     * 且必须由调用方按「已交付、不重投」处理。这条近似换来的是不依赖目标内部的剩余容量接口。</p>
     */
    private static boolean acceptsAll(Map<AEKey, PatternProviderTarget> targets, KeyCounter[] inputs) {
        for (var counter : inputs) {
            if (counter == null) {
                continue;
            }
            for (var key : counter.keySet()) {
                var amount = counter.get(key);
                var target = targets.get(key);
                if (target == null || target.insert(key, amount, Actionable.SIMULATE) < amount) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 把输入交给样板自己派发（按声明的接入面找 sink），与方向投递同一套。 */
    private Delivery pushToTarget(IPatternDetails details, KeyCounter[] inputs,
            Map<AEKey, PatternProviderTarget> targets) {
        boolean[] complete = { true };
        boolean[] any = { false };
        details.pushInputsToExternalInventory(inputs, (what, amount) -> {
            var target = targets.get(what);
            if (target == null) {
                complete[0] = false;
                return;
            }
            any[0] = true;
            long inserted = target.insert(what, amount, Actionable.MODULATE);
            if (inserted < amount) {
                complete[0] = false;
                LOGGER.warn("Pattern disk provider at {} handed over {} x{} but its target took only {}",
                        host.blockEntity() == null ? "?" : host.blockEntity().getBlockPos(), what, amount,
                        inserted);
            }
        });
        if (!any[0]) {
            return Delivery.NONE;
        }
        return complete[0] ? Delivery.ALL : Delivery.PARTIAL;
    }

    /** 目标内容快照里是否存在与本样板无关的原料。读不到存储（无 ME 能力）时按「无外来物」处理。 */
    private static boolean hasForeignInput(ServerLevel level, BlockPos pos, @Nullable BlockEntity be, Direction meSide,
            Set<AEKey> ownInputs) {
        var storage = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, be.getBlockPos(), be.getBlockState(), be, meSide)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, meSide);
        if (storage == null) {
            return false;
        }
        return ProviderBlockingRules.hasForeignInput(storage.getAvailableStacks(), ownInputs);
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
    boolean pushDirectionally(IPatternDetails details, KeyCounter[] inputHolder) {
        var be = host.blockEntity();
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        var sides = host.activeSides();

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

        var patternInputs = host.isBlocking() ? declaredInputs(details) : null;

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
                        declared != null ? declared : fromUs, host.actionSource());
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
                            declared != null ? declared : fromUs, host.actionSource());
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

    /** 这张样板声明的输入 key 集合（与父类同一口径：丢掉二级变体）。 */
    static Set<AEKey> declaredInputs(IPatternDetails details) {
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
     * 自己的可用面：与 AE2 同一判据——宿主声明的目标面，减去连着同网其他供应器与接口的那些面。
     *
     * <p>排除很重要：那些面背面坐着的是另一台拿样板干活的机器，把输入推过去等于把活派给它。</p>
     */
    static Set<Direction> activeSides(PatternProviderLogicHost host, IManagedGridNode mainNode) {
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
}

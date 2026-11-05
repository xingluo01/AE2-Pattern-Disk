package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
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
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.me.storage.CompositeStorage;
import appeng.parts.automation.StackWorldBehaviors;

import io.github.lounode.ae2pattern.api.PatternDiskApi;

/**
 * 协作类与宿主之间的窄接口：抽取块只需要这几项宿主能力，不持有整个 logic 对象。
 */
interface ExtractionHost {

    @Nullable
    BlockEntity blockEntity();

    long gameTime();

    IActionSource actionSource();

    /** 网络存储；网络未接上（节点 / 网格为空）时返回 {@code null}。 */
    @Nullable
    MEStorage networkInventory();

    Set<Direction> activeSides();

    Iterable<IPatternDetails> availablePatterns();

    InternalInventory mirror();
}

/**
 * 产物抽取：节拍、白名单缓存、目标存储视图与单视图抽取，连同它们的退避与诊断状态。
 *
 * <p>「哪些 key 是样板产物」「多久抽一次」「侧视图粘性全扫」这些状态与它们的不变量整体在这里；
 * 宿主只在 {@code tickServer} / 换档 / 样板重建 / 读档时转调进来。</p>
 */
final class ProviderExtraction {

    /** 沿用宿主的 logger 名，日志分类不变。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternDiskProviderLogic.class);

    /** 连续多少次抽取都没抽到任何东西时打一条诊断（含面数 / 可读存储 / 白名单大小）。 */
    private static final int EXTRACTION_DIAGNOSE_AFTER = 64;
    /** 每隔这么多 tick 做一次全视图扫描（其余轮只查正对面）：既省 cache 注册，又不漏「产物在别的面」。 */
    private static final long FULL_SWEEP_INTERVAL = 40L;
    private static final Direction[] DIRECTIONS = Direction.values();
    /** 侧视图连续这么多轮没产出就停掉粘性全扫。 */
    private static final int STICKY_IDLE_LIMIT = 20;

    private final ExtractionHost host;
    private final ProviderSettings settings;

    /** 全部已装样板的输出集合缓存（产物抽取白名单）；样板重建时作废。 */
    private Set<AEKey> cachedPatternOutputs;

    /** 只报一次的「抽取后塞不回目标」纸条，避免目标长期拒收时刷屏。 */
    private final Set<AEKey> extractionReturnWarned = new HashSet<>();

    /** 还要等多少 tick 才再抽一次；0 表示下一次 tick 就抽。 */
    private int extractionCooldown;
    /** 快速档当前间隔（退避用，抽到就回落到 1）。 */
    private int extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;

    private int extractionMissStreak;
    private long lastFullSweep;
    /** 换档后首轮要立刻做一次全视图扫描（否则最长要等全扫间隔才看得到别的面）。 */
    private boolean extractionSweepDue = true;
    /** 已报过异常的视图（"坐标:标签" 字符串键：视图对象每轮新建，用身份做键既拦不住重复又会吊住 cache）。 */
    private final Set<String> extractionViewWarned = new HashSet<>();
    /** 侧视图上一轮抽到过东西：下一轮仍做全视图扫描，否则 FAST 档对「产物只在侧面」的机器会退化成 2 秒一次。 */
    private boolean sweepSticky;
    private int sweepStickyIdle;
    /** 本轮是否需要收集「目标里看得到什么」这类贵信息（只在首轮与 64 次那轮开）。 */
    /** 每个面拿到了哪些 AEKeyType 的存储包装（判断「没暴露物品」还是「暴露了但槽空」的关键证据）。 */
    /** 每个面的 IItemHandler 到底存不存在、有几个槽、槽里是什么——区分「没暴露」与「暴露了但看不到产物」。 */
    /** 本轮在目标里看到的、恰好属于白名单的 key 数量（>0 说明「容器里确实有样板产物」）。 */
    /** 上一次打周期性诊断的 tick。 */

    ProviderExtraction(ExtractionHost host, ProviderSettings settings) {
        this.host = host;
        this.settings = settings;
    }

    /** 样板集合变了：输出白名单与「退回失败」纸条都得重建。 */
    void invalidateOutputs() {
        cachedPatternOutputs = null;
        extractionReturnWarned.clear();
    }

    /** 读档：退避进度是运行时状态，不落盘，从最快档重新开始。 */
    void resetPacingOnLoad() {
        extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;
        extractionCooldown = 0;
    }

    /** 换档后立即生效：不把上一档的退避 / 空抽计数带过来。 */
    void resetForModeChange() {
        extractionCooldown = 0;
        extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;
        extractionMissStreak = 0;
        extractionSweepDue = true;
    }

    /**
     * 每 tick 的抽取节拍：由方块形态的 {@code serverTick} 与面板形态的世界 tick 事件驱动。
     *
     * <p>冷却递减与实际抽取都放在这里，是因为只有这两个入口知道「这个世界在跑」；逻辑自己不持有 tick 源。</p>
     */
    void tick() {
        var mode = settings.extractionMode();
        if (mode == ProviderExtractionMode.OFF) {
            extractionCooldown = 0;
            return;
        }
        if (allPatternOutputs().isEmpty()) {
            extractionCooldown = 0;
            extractionMissStreak = 0;
            return;
        }
        var sides = host.activeSides();
        if (sides.isEmpty()) {
            // 这台供应器无事可做：磁盘里没有样板产物，或没有可用的目标面。不计空抽、不告警——
            // 否则空机器每 64 轮刷一条「抽不到」，把真正的故障埋掉。
            extractionCooldown = 0;
            extractionMissStreak = 0;
            return;
        }
        if (extractionCooldown > 0) {
            extractionCooldown = ProviderExtractionPacing.advanceCooldown(extractionCooldown);
            if (extractionCooldown > 0) {
                return;
            }
        }
        boolean extracted = extractProducts(sides);
        extractionInterval = ProviderExtractionPacing.nextInterval(mode, extractionInterval, extracted);
        extractionCooldown = extractionInterval;
        if (extracted) {
            extractionMissStreak = 0;
        } else if (++extractionMissStreak == EXTRACTION_DIAGNOSE_AFTER) {
            // 开着抽取却连续抽不到：通常是目标没有把样板产物暴露在任何一面，报一次便于定位。
            LOGGER.warn("Pattern disk provider at {} has extraction on ({}) but nothing was extracted in the last "
                    + "{} passes; check that the target exposes the pattern outputs on one of its faces",
                    host.blockEntity() == null ? "?" : host.blockEntity().getBlockPos(), mode,
                    EXTRACTION_DIAGNOSE_AFTER);
        }
    }

    /**
     * 从活跃面把「属于已装样板输出」的物品抽进 ME 网络。
     *
     * <p>白名单是样板自己的 {@code getOutputs()}：目标里剩的原料、工具、其它物品都不动，否则抽取会变成
     * 「把机器拆了」。每个视图各自跑一遍，能抽多少抽多少，网络装不下就留在目标里。</p>
     *
     * @return 是否真的抽到了东西（快速档靠它决定要不要退避）
     */
    private boolean extractProducts(Set<Direction> sides) {
        var be = host.blockEntity();
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        var outputs = allPatternOutputs();
        if (outputs.isEmpty()) {
            return false;
        }
        var network = host.networkInventory();
        if (network == null) {
            return false;
        }

        boolean extractedAny = false;
        long now = host.gameTime();
        boolean fullSweep = extractionSweepDue || sweepSticky || now - lastFullSweep >= FULL_SWEEP_INTERVAL;
        if (fullSweep) {
            lastFullSweep = now;
            extractionSweepDue = false;
        }
        for (var facing : sides) {
            var adjacentPos = be.getBlockPos().relative(facing);
            var storages = extractableStorages(level, adjacentPos, facing.getOpposite(), fullSweep);
            int viewIndex = 0;
            boolean sideProduced = false;
            for (var storage : storages) {
                int viewIdx = viewIndex++;
                boolean sideView = viewIdx > 0;
                try {
                    boolean got = extractFromStorage(storage, outputs, network, host.actionSource(), be.getBlockPos());
                    extractedAny |= got;
                    if (got && sideView) {
                        sideProduced = true;
                    }
                } catch (RuntimeException e) {
                    // 视图来源各不相同：一个第三方实现抛异常不该让整轮抽取停摆，只跳过它并限流告警。
                    var warnKey = adjacentPos.toShortString() + ":" + viewIdx;
                    if (extractionViewWarned.size() > 64) {
                        extractionViewWarned.clear();
                    }
                    if (extractionViewWarned.add(warnKey)) {
                        LOGGER.warn("Pattern disk provider at {}: storage view {} threw while extracting; "
                                + "skipping it this pass", be.getBlockPos(), warnKey, e);
                    }
                }
            }
            if (sideProduced) {
                sweepSticky = true;
                sweepStickyIdle = 0;
            } else if (sweepSticky && ++sweepStickyIdle >= STICKY_IDLE_LIMIT) {
                // 侧视图连续这么多个抽取轮都没产出，就退回只查正对面。
                sweepSticky = false;
                sweepStickyIdle = 0;
            }
        }
        return extractedAny;
    }

    /**
     * 目标这一面实际持有的「去组件 key → 实际 key」索引，只收<b>无组件且唯一</b>的条目。
     *
     * <p>两道限定的理由：带组件的变体不是样板声明的那一种，回退到它会抽走别的物品；而同一个去组件 key
     * 对应两个变体时，留哪个取决于 {@code getAvailableStacks()} 的迭代顺序，抽到的东西会随 tick 摇摆。
     * 两者都宁可判「抽不到」，交给玩家把样板编码成精确变体。</p>
     */
    private static Map<AEKey, AEKey> indexByNormalizedKey(MEStorage storage) {
        var index = new HashMap<AEKey, AEKey>();
        var ambiguous = new HashSet<AEKey>();
        for (var stack : storage.getAvailableStacks()) {
            var key = stack.getKey();
            if (key == null) {
                continue;
            }
            var normalized = key.dropSecondary();
            if (!key.equals(normalized) || ambiguous.contains(normalized)) {
                continue;
            }
            if (index.putIfAbsent(normalized, key) != null) {
                // 多义：两个「干净」变体共享同一个去组件 key，宁可不抽。
                index.remove(normalized);
                ambiguous.add(normalized);
            }
        }
        return index;
    }

    /** 在单个存储视图上跑一轮「白名单 → 网络」的抽取。 */
    private boolean extractFromStorage(MEStorage storage, Set<AEKey> outputs, MEStorage network,
            IActionSource actionSource, BlockPos bePos) {
        boolean extractedAny = false;
        Map<AEKey, AEKey> byNormalized = null;
        for (var key : outputs) {
            long available = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
            var target = key;
            if (available <= 0) {
                if (byNormalized == null) {
                    byNormalized = indexByNormalizedKey(storage);
                }
                var actual = byNormalized.get(key.dropSecondary());
                if (actual == null) {
                    continue;
                }
                target = actual;
                available = storage.extract(target, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
            }
            if (available <= 0) {
                continue;
            }
            // 先问网络收多少，再按那个量抽：顺序反过来的话，网络装不下的部分要退回目标，而退不回就是净丢。
            long accepted = network.insert(target, available, Actionable.SIMULATE, actionSource);
            if (accepted <= 0) {
                continue;
            }
            long taken = storage.extract(target, accepted, Actionable.MODULATE, actionSource);
            if (taken <= 0) {
                continue;
            }
            long inserted = network.insert(target, taken, Actionable.MODULATE, actionSource);
            // 只认「真的进了网络」：目标能抽、网络却拒收（SIMULATE 后被别人占满）时，抽出来又要退回，
            // 把它算成成功会让快速档掉回每 tick，变成抽出再塞回的空转。
            extractedAny |= inserted > 0;
            if (inserted < taken) {
                // 抽出来之后网络才满（同一 tick 里别的东西占了位置）：多出来的塞回目标。
                long leftover = taken - inserted;
                long returned = storage.insert(target, leftover, Actionable.MODULATE, actionSource);
                if (returned < leftover && noteExtractionReturnWarning(target)) {
                    LOGGER.warn(
                            "Pattern disk provider at {} extracted {} x{} but the target took back only {}{}; "
                                    + "the shortfall is not recoverable",
                            bePos, target, leftover, returned,
                            target == key ? "" : " (via normalized-key fallback, the target face may be output-only)");
                }
            }
        }
        return extractedAny;
    }

    /** 全部已装样板的输出集合（抽取白名单）；样板一变就重建。
     *
     * <p>存的是样板的<b>原型 key</b>，不是 {@code dropSecondary()} 后的爪化版：{@code extract} 要的是精确 key
     * （爪化只适合比较），用爪化版去抽会把「带组件的产物」漏掉或抽错变体。</p>
     *
     * <p><b>主来源是父类解码好的 {@code patterns} 列表</b>（零额外解码）；列表为空时退回直接解码镜像栏
     * 本身（镜像就是磁盘内容的派生态）作为兜底。这两个来源读的是同一份库存、用的是同一个
     * AE2 helper，结果等价——所以这里的兜底只防「父类还没解码过」这一种边界，不要把它当成修了什么同步问题。
     * 缓存仍按样板重建失效，不在每 tick 的抽取路径上重解。</p>
     *
     * <p><b>不剔除「同时也是输入」的 key</b>：早先为了避免与投递抢料做过一次全局剔除，结果链式生产
     * （A 的产物正是 B 的原料）里那个中间产物永远抽不出来，看起来就像功能没生效。现在的取舍是按用户口径
     * 只认输出：料被抽回网络后如果还要用，推送会再取一次。</p>
     */
    private Set<AEKey> allPatternOutputs() {
        if (cachedPatternOutputs == null) {
            var outputs = new HashSet<AEKey>();
            for (var details : host.availablePatterns()) {
                collectOutputs(details, outputs);
            }
            if (outputs.isEmpty()) {
                // 兜底：父类还没解码过（刚加载、或列表被清空的那一瞬）时直接解码镜像栏。
                var be = host.blockEntity();
                var mirror = host.mirror();
                if (mirror != null && be != null && be.getLevel() != null) {
                    for (int slot = 0; slot < mirror.size(); slot++) {
                        var details = PatternDiskApi.decodePattern(mirror.getStackInSlot(slot), be.getLevel());
                        collectOutputs(details, outputs);
                    }
                }
            }
            cachedPatternOutputs = outputs;
        }
        return cachedPatternOutputs;
    }

    private static void collectOutputs(@Nullable IPatternDetails details, Set<AEKey> into) {
        if (details == null) {
            return;
        }
        for (var stack : details.getOutputs()) {
            if (stack != null && stack.what() != null) {
                into.add(stack.what());
            }
        }
    }

    /** @return true 表示这个 key 还没报过退回失败，应当打一条 warn */
    private boolean noteExtractionReturnWarning(AEKey key) {
        if (extractionReturnWarned.size() > 64) {
            extractionReturnWarned.clear();
        }
        return extractionReturnWarned.add(key);
    }

    /**
     * 一次抽取轮要查的存储视图（调用内不缓存）。Mekanism 等机器按面暴露槽位，正对的那一面可能根本不含
     * 产物槽，所以除正对面之外还查无面视图与其余五面——见方法体里的说明。
     *
     * <p>AE2 的 ME 能力（{@link AECapabilities#ME_STORAGE}）若存在则视为权威视图（它自己代表机器的完整
     * 库存），不再叠平台能力，否则同一库存会被抽两次。</p>
     */
    private List<MEStorage> extractableStorages(ServerLevel level, BlockPos pos, Direction meSide,
            boolean fullSweep) {
        var be = level.getBlockEntity(pos);
        var direct = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, be.getBlockPos(), be.getBlockState(), be, meSide)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, meSide);
        if (direct != null) {
            // ME 能力是权威视图，它自己就代表机器的完整库存，不再叠平台能力（叠了会抽同一个库存两次）。
            return List.of(direct);
        }
        var storages = new ArrayList<MEStorage>(7);
        collectExternal(level, pos, meSide, storages);
        if (!fullSweep) {
            return storages;
        }
        // 全视图扫描：产物槽不一定在正对的那一面。无面视图（side = null）在多数实现里是未按面过滤的全槽，
        // 其余五面则覆盖「输出面开在侧面」的机器。白名单仍只认样板输出，多查几个视图不会多抽东西。
        collectExternal(level, pos, null, storages);
        for (var side : DIRECTIONS) {
            if (side != meSide) {
                collectExternal(level, pos, side, storages);
            }
        }
        return storages;
    }

    /** 把某个面（{@code side} 可为 null）上的平台存储能力包成一个 {@link MEStorage} 加进 {@code into}。 */
    private void collectExternal(ServerLevel level, BlockPos pos, @Nullable Direction side,
            List<MEStorage> into) {
        var strategies = StackWorldBehaviors.createExternalStorageStrategies(level, pos, side);
        var external = new IdentityHashMap<AEKeyType, MEStorage>(2);
        for (var entry : strategies.entrySet()) {
            var wrapper = entry.getValue().createWrapper(false, () -> {
            });
            if (wrapper != null) {
                external.put(entry.getKey(), wrapper);
            }
        }
        if (!external.isEmpty()) {
            into.add(new CompositeStorage(external));
        }
    }
}

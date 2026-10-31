package io.github.lounode.ae2pattern.integration.ae2cs.resonating;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.helpers.MachineSource;
import appeng.me.storage.CompositeStorage;
import appeng.parts.automation.StackWorldBehaviors;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2cs.common.me.logic.ResonatingPatternProviderLogic;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.DiskProviderTicker;
import io.github.lounode.ae2pattern.common.logic.PatternsFromDisks;
import io.github.lounode.ae2pattern.common.logic.ProviderExtractionMode;
import io.github.lounode.ae2pattern.common.logic.ProviderExtractionPacing;
import io.github.lounode.ae2pattern.common.logic.ReadOnlyPatternInventory;
import io.github.lounode.ae2pattern.common.pattern.DiskSlotVersions;

/**
 * 谐振样板磁盘供应器的逻辑：在 AECS 的谐振供应器逻辑之上，把磁盘内容镜像进它自己的样板栏。
 *
 * <p>
 * 与过载那台同一套做法：上游的 {@code updatePatterns()} 读的是样板栏，所以这里写栏、再让它自己重建；本类不碰
 * 任何私有字段。AECS 的 {@code pushPattern} 只按样板栏建立的 {@code getAvailablePatterns()} 工作，镜像写入后
 * 谐振样板与普通处理样板都走它自己的分流（谐振样板按其目标投递，普通样板回退原版）。
 * </p>
 *
 * <p>
 * 产物抽取由本类接管：父类的 {@code doPullWork} 会把相邻容器里的所有资源都搬进返回仓，而本设备要的是「只抽
 * 样板输出」并按样板磁盘供应器的 OFF/NORMAL/FAST 三档节拍走。{@code isEnablePull()} 关掉父类那条路，三档由
 * {@link #tickExtraction()} 驱动；方块形态走方块实体的 serverTick，面板形态走世界 tick。
 * </p>
 *
 * <p>
 * 默认输入目标（绑定器的 144 槽）与跨维度待发送队列都在父类里，随 {@code writeToNBT}/{@code readFromNBT}
 * 自动持久化；本类不复制它们。
 * </p>
 */
public class ResonatingPatternDiskProviderLogic extends ResonatingPatternProviderLogic
        implements PatternsFromDisks, DiskProviderTicker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResonatingPatternDiskProviderLogic.class);

    private static final String TAG_EXTRACTION_MODE = "extraction_mode";
    private static final int EXTRACTION_DIAGNOSE_AFTER = 64;
    private static final int MAX_KEYS_PER_PASS = 32;
    private static final long MAX_REQUEST_PER_KEY = 4000L;
    private static final long FULL_SWEEP_INTERVAL = 40L;
    private static final int STICKY_IDLE_LIMIT = 8;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final IManagedGridNode mainNode;
    private final ResonatingPatternDiskProviderHost host;
    private final IActionSource actionSource;
    private final Supplier<AppEngInternalInventory> diskInventorySupplier;

    private List<Object> lastDiskVersions;

    /** 真正的样板栏原件：重建时写这里，getPatternInv() 对外只交只读视图。 */
    private final InternalInventory patternInventory;
    private final InternalInventory readOnlyPatternInv;

    /** 重建样板栏期间为真：逐槽写入不再触发父类的全量 updatePatterns。 */
    private boolean rebuildingMirror;

    private ProviderExtractionMode extractionMode = ProviderExtractionMode.OFF;
    private int extractionCooldown;
    private int extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;
    private int extractionMissStreak;
    private long lastFullSweep;
    private boolean extractionSweepDue = true;
    private boolean sweepSticky;
    private int sweepStickyIdle;
    private final Set<AEKey> extractionReturnWarned = new HashSet<>();
    private final Set<String> extractionViewWarned = new HashSet<>();

    /**
     * 样板输出白名单；{@link #updatePatterns()} 时作废。列表排序稳定，配 {@link #extractionOutputCursor}
     * 跨轮轮转，避免 key 超过 32 时永远只扫同一批。
     */
    private List<AEKey> cachedOutputs = List.of();
    private int extractionOutputCursor;

    public ResonatingPatternDiskProviderLogic(
            IManagedGridNode mainNode,
            ResonatingPatternDiskProviderHost host,
            int capacity,
            Supplier<AppEngInternalInventory> diskInventorySupplier) {
        super(mainNode, host, capacity);
        this.mainNode = mainNode;
        this.host = host;
        this.actionSource = new MachineSource(mainNode::getNode);
        this.diskInventorySupplier = diskInventorySupplier;
        this.patternInventory = super.getPatternInv();
        this.readOnlyPatternInv = new ReadOnlyPatternInventory(this.patternInventory);
    }

    // ---- 产物抽取（本类接管，父类的 doPullWork 不用） -------------------------

    /** 父类的抽取会搬走相邻容器的所有资源；本设备只抽样板输出，所以把父类那条路关掉。 */
    @Override
    public boolean isEnablePull() {
        return false;
    }

    @Override
    public void updatePatterns() {
        super.updatePatterns();
        cachedOutputs = null;
        extractionOutputCursor = 0;
        extractionSweepDue = true;
    }

    public ProviderExtractionMode getExtractionMode() {
        return extractionMode;
    }

    public void setExtractionMode(ProviderExtractionMode mode) {
        if (mode == null || mode == extractionMode) {
            return;
        }
        extractionMode = mode;
        resetExtractionPass();
        saveChanges();
    }

    public void cycleExtractionMode() {
        setExtractionMode(extractionMode.next());
    }

    /** 方块 serverTick 与面板世界 tick 都走这里。 */
    @Override
    public void tickServer() {
        tickExtraction();
    }

    /**
     * 每 tick 的抽取节拍：OFF 不做；NORMAL 每 20 tick；FAST 每 tick，抽不到就翻倍退避到 20。
     */
    public void tickExtraction() {
        if (extractionMode == ProviderExtractionMode.OFF) {
            extractionCooldown = 0;
            extractionMissStreak = 0;
            return;
        }
        if (!mainNode.isActive()) {
            return;
        }
        if (extractionCooldown > 0) {
            extractionCooldown = ProviderExtractionPacing.advanceCooldown(extractionCooldown);
            if (extractionCooldown > 0) {
                return;
            }
        }

        var outputs = patternOutputs();
        if (outputs.isEmpty()) {
            extractionCooldown = 0;
            extractionMissStreak = 0;
            extractionOutputCursor = 0;
            return;
        }
        var sides = activeSides();
        if (sides.isEmpty()) {
            extractionCooldown = 0;
            extractionMissStreak = 0;
            return;
        }

        boolean extracted = extractProducts(sides, outputs);
        extractionInterval = ProviderExtractionPacing.nextInterval(extractionMode, extractionInterval, extracted);
        extractionCooldown = extractionInterval;
        if (extracted) {
            extractionMissStreak = 0;
        } else if (++extractionMissStreak == EXTRACTION_DIAGNOSE_AFTER) {
            LOGGER.warn("Resonating pattern disk provider at {} has extraction on ({}) but nothing was extracted "
                            + "in the last {} passes; check that the target exposes pattern outputs on one of its faces",
                    blockPos(), extractionMode, EXTRACTION_DIAGNOSE_AFTER);
        }
    }

    private List<AEKey> patternOutputs() {
        if (cachedOutputs == null) {
            var seen = new HashSet<AEKey>();
            for (var details : getAvailablePatterns()) {
                for (var stack : details.getOutputs()) {
                    if (stack != null && stack.what() != null) {
                        seen.add(stack.what());
                    }
                }
            }
            var outputs = new ArrayList<>(seen);
            // HashSet 只保证同一集合未变时稳定；这里排序让游标轮转覆盖可预测。
            outputs.sort(Comparator.comparing(AEKey::toString));
            cachedOutputs = List.copyOf(outputs);
            extractionOutputCursor = 0;
        }
        return cachedOutputs;
    }

    /**
     * 只扫宿主声明、且没连着同网另一台供应器/接口的面。与 AE2 和本模组普通供应器同一判据：背面坐着接口的面
     * 不是抽取出口，否则会把准备喂给机器的原料抽回网络。
     */
    private Set<Direction> activeSides() {
        var declared = host.getTargets();
        var sides = declared.isEmpty() ? EnumSet.noneOf(Direction.class) : EnumSet.copyOf(declared);
        var node = mainNode.getNode();
        if (node == null) {
            return sides;
        }
        var ourGrid = mainNode.getGrid();
        for (var entry : node.getInWorldConnections().entrySet()) {
            var other = entry.getValue().getOtherSide(node);
            if (other.getOwner() instanceof PatternProviderLogicHost
                    || (other.getOwner() instanceof InterfaceLogicHost
                            && ourGrid != null && ourGrid.equals(other.getGrid()))) {
                sides.remove(entry.getKey());
            }
        }
        return sides;
    }

    /**
     * 从可用面把「已装样板声明的输出」抽进 ME 网络。白名单之外的东西（原料、工具）一概不动。
     *
     * <p>每轮最多查 32 个输出 key、单个 key 最多要 4000 个；游标跨轮前移，保证 key 集合大于 32 时靠后的面与
     * key 也会轮到。只按样板声明的精确 key 抽，不做 {@code dropSecondary()} 回退；机器若用带组件变体作为产物，
     * 应把样板编成对应的精确输出。</p>
     */
    private boolean extractProducts(Set<Direction> sides, List<AEKey> outputs) {
        var be = host.getBlockEntity();
        if (be == null || !(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        var node = mainNode.getNode();
        var grid = node == null ? null : node.getGrid();
        if (grid == null) {
            return false;
        }
        var network = grid.getStorageService().getInventory();

        long now = level.getGameTime();
        boolean fullSweep = extractionSweepDue || sweepSticky || now - lastFullSweep >= FULL_SWEEP_INTERVAL;
        if (fullSweep) {
            lastFullSweep = now;
            extractionSweepDue = false;
        }

        var views = new ArrayList<SideViews>(sides.size());
        for (var facing : sides) {
            var adjacentPos = be.getBlockPos().relative(facing);
            if (!level.hasChunkAt(adjacentPos)) {
                continue;
            }
            var storages = extractableStorages(level, adjacentPos, facing.getOpposite(), fullSweep);
            if (!storages.isEmpty()) {
                views.add(new SideViews(adjacentPos, storages));
            }
        }
        if (views.isEmpty()) {
            // 即使没有可查的视图，也要给 sticky 一次退场机会；否则相邻存储临时消失后它会永远保持全视图扫描。
            if (sweepSticky && ++sweepStickyIdle >= STICKY_IDLE_LIMIT) {
                sweepSticky = false;
                sweepStickyIdle = 0;
            }
            return false;
        }

        int outputCount = outputs.size();
        int start = Math.floorMod(extractionOutputCursor, outputCount);
        int pass = Math.min(MAX_KEYS_PER_PASS, outputCount);
        boolean extractedAny = false;
        boolean sideProduced = false;

        for (int offset = 0; offset < pass; offset++) {
            var key = outputs.get((start + offset) % outputCount);
            for (var view : views) {
                List<MEStorage> storages = view.storages();
                for (int viewIndex = 0; viewIndex < storages.size(); viewIndex++) {
                    boolean nonDirectView = viewIndex > 0;
                    try {
                        boolean got = extractKey(storages.get(viewIndex), key, network, be.getBlockPos(), view.pos());
                        extractedAny |= got;
                        sideProduced |= got && nonDirectView;
                    } catch (RuntimeException e) {
                        String warnKey = view.pos().toShortString() + ":" + viewIndex;
                        if (noteExtractionViewWarning(warnKey)) {
                            LOGGER.warn("Resonating pattern disk provider at {}: storage view {} threw while extracting; "
                                            + "skipping it this pass", be.getBlockPos(), warnKey, e);
                        }
                    }
                }
            }
        }
        extractionOutputCursor = (start + pass) % outputCount;

        // sticky 按整轮统计；AE2 的 ME 权威视图永远只有一个，因此它不参与「侧视图产出」判定。
        if (sideProduced) {
            sweepSticky = true;
            sweepStickyIdle = 0;
        } else if (sweepSticky && ++sweepStickyIdle >= STICKY_IDLE_LIMIT) {
            sweepSticky = false;
            sweepStickyIdle = 0;
        }
        return extractedAny;
    }

    private boolean extractKey(MEStorage storage, AEKey key, MEStorage network, BlockPos bePos, BlockPos targetPos) {
        long available = storage.extract(key, MAX_REQUEST_PER_KEY, Actionable.SIMULATE, actionSource);
        if (available <= 0) {
            return false;
        }
        // 先问网络收多少再抽：反过来，网络装不下的部分要退回目标，退不回就是净丢。
        long accepted = network.insert(key, available, Actionable.SIMULATE, actionSource);
        if (accepted <= 0) {
            return false;
        }
        long taken = storage.extract(key, accepted, Actionable.MODULATE, actionSource);
        if (taken <= 0) {
            return false;
        }
        long inserted;
        try {
            inserted = network.insert(key, taken, Actionable.MODULATE, actionSource);
        } catch (RuntimeException e) {
            // 网络调用炸了，无法可靠判断它是否已部分入账；不按入账额回捞，否则可能误抽网络中同 key 的别人的账。
            // 先按全量退回，最坏只可能把异常前已入账的部分复制一份，至少不净丢。
            placeBack(storage, key, taken, bePos, targetPos, "network-insert-failed");
            throw e;
        }
        long leftover = taken - inserted;
        if (leftover > 0) {
            placeBack(storage, key, leftover, bePos, targetPos, "network-leftover");
        }
        return inserted > 0;
    }

    private void placeBack(MEStorage target, AEKey key, long amount, BlockPos bePos, BlockPos targetPos, String phase) {
        if (amount <= 0) {
            return;
        }
        long returned = 0;
        RuntimeException targetError = null;
        try {
            returned = target.insert(key, amount, Actionable.MODULATE, actionSource);
        } catch (RuntimeException e) {
            targetError = e;
            String warnKey = phase + ':' + targetPos.toShortString() + ':' + key;
            if (noteExtractionViewWarning(warnKey)) {
                LOGGER.warn("Resonating pattern disk provider at {} could not return {} from {} to {}; "
                                + "trying the return inventory",
                        bePos, key, targetPos, phase, e);
            }
        }
        long missing = amount - returned;
        if (missing <= 0) {
            return;
        }
        try {
            long buffered = getReturnInv().insert(key, missing, Actionable.MODULATE, actionSource);
            if (buffered < missing && noteExtractionReturnWarning(key)) {
                LOGGER.warn("Resonating pattern disk provider at {} could not buffer {} of {} extracted from {} "
                                + "(return inventory accepted {})",
                        bePos, missing - buffered, key, targetPos, buffered);
            }
        } catch (RuntimeException e) {
            if (noteExtractionReturnWarning(key)) {
                LOGGER.warn("Resonating pattern disk provider at {} could not buffer {} of {} extracted from {}",
                        bePos, missing, key, targetPos, e);
            }
        }
    }

    private List<MEStorage> extractableStorages(ServerLevel level, BlockPos pos, Direction meSide,
            boolean fullSweep) {
        var targetBe = level.getBlockEntity(pos);
        MEStorage direct = targetBe != null
                ? level.getCapability(AECapabilities.ME_STORAGE, targetBe.getBlockPos(), targetBe.getBlockState(),
                        targetBe, meSide)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, meSide);
        if (direct != null) {
            // AE2 的 ME 能力是权威视图；再叠平台能力会把同一个库存抽两次。
            return List.of(direct);
        }

        var storages = new ArrayList<MEStorage>(7);
        collectExternal(level, pos, meSide, storages);
        if (!fullSweep) {
            return storages;
        }
        collectExternal(level, pos, null, storages);
        for (var side : DIRECTIONS) {
            if (side != meSide) {
                collectExternal(level, pos, side, storages);
            }
        }
        return storages;
    }

    private void collectExternal(ServerLevel level, BlockPos pos, @Nullable Direction side, List<MEStorage> into) {
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

    private void resetExtractionPass() {
        extractionCooldown = 0;
        extractionInterval = ProviderExtractionPacing.FAST_START_INTERVAL;
        extractionMissStreak = 0;
        lastFullSweep = 0;
        extractionSweepDue = true;
        extractionOutputCursor = 0;
        sweepSticky = false;
        sweepStickyIdle = 0;
    }

    private boolean noteExtractionReturnWarning(AEKey key) {
        if (extractionReturnWarned.size() > 64) {
            extractionReturnWarned.clear();
        }
        return extractionReturnWarned.add(key);
    }

    private boolean noteExtractionViewWarning(String warnKey) {
        if (extractionViewWarned.size() > 64) {
            extractionViewWarned.clear();
        }
        return extractionViewWarned.add(warnKey);
    }

    private String blockPos() {
        var be = host.getBlockEntity();
        return be == null ? "?" : be.getBlockPos().toShortString();
    }

    private record SideViews(BlockPos pos, List<MEStorage> storages) {
    }

    // ---- 存档 ---------------------------------------------------------------

    /**
     * 方块与面板共用这条写档路径：镜像只是磁盘的副本，写进去会随磁盘容量放大（9216 格）。读档由
     * {@code disks.load} 按磁盘重建，所以在这里把父类写下的样板段摘掉。
     */
    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag, registries);
        tag.remove(NBT_MEMORY_CARD_PATTERNS);
        tag.putString(TAG_EXTRACTION_MODE, extractionMode.name());
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.readFromNBT(tag, registries);
        extractionMode = parseExtractionMode(tag.getString(TAG_EXTRACTION_MODE));
        cachedOutputs = null;
        resetExtractionPass();
    }

    private static ProviderExtractionMode parseExtractionMode(String name) {
        for (var mode : ProviderExtractionMode.values()) {
            if (mode.name().equals(name)) {
                return mode;
            }
        }
        return ProviderExtractionMode.OFF;
    }

    // ---- 磁盘镜像 -----------------------------------------------------------

    @Override
    public boolean refreshPatternsFromDisks(boolean force) {
        var diskInventory = diskInventorySupplier.get();
        if (diskInventory == null) {
            return false;
        }

        var versions = DiskSlotVersions.ofInventory(diskInventory);
        if (!force && DiskSlotVersions.sameAs(lastDiskVersions, versions)) {
            return false;
        }

        List<ItemStack> patterns = new ArrayList<>();
        for (int i = 0; i < diskInventory.size(); i++) {
            var diskStack = diskInventory.getStackInSlot(i);
            if (diskStack.isEmpty() || !(diskStack.getItem() instanceof PatternDiskItem disk)) {
                continue;
            }
            patterns.addAll(disk.contents(diskStack).patterns());
        }

        InternalInventory patternInv = patternInventory;
        rebuildingMirror = true;
        try {
            patternInv.clear();
            int limit = Math.min(patterns.size(), patternInv.size());
            for (int i = 0; i < limit; i++) {
                patternInv.setItemDirect(i, patterns.get(i).copy());
            }
        } finally {
            rebuildingMirror = false;
        }

        updatePatterns();
        saveChanges();
        lastDiskVersions = versions;
        return true;
    }

    @Override
    public void clearMirror() {
        InternalInventory patternInv = patternInventory;
        rebuildingMirror = true;
        try {
            for (int i = 0; i < patternInv.size(); i++) {
                patternInv.setItemDirect(i, ItemStack.EMPTY);
            }
        } finally {
            rebuildingMirror = false;
        }
        // 镜像已非磁盘内容的映像：不把快照作废的话，下一个非强制的 refresh 会拿旧快照比出「没变」而跳过重建。
        lastDiskVersions = null;
        updatePatterns();
        saveChanges();
    }

    /**
     * 拆机/重置时清空镜像。父实现逐槽清，每槽一次通知就直接跑一次 9216 槽的 {@code updatePatterns()}；
     * 这里用与重建同一个抑制开关把它压成一次，末尾统一重建。
     */
    @Override
    public void clearContent() {
        rebuildingMirror = true;
        try {
            super.clearContent();
        } finally {
            rebuildingMirror = false;
        }
        lastDiskVersions = null;
        updatePatterns();
    }

    /**
     * 重建期间逐槽写入不触发父类的 updatePatterns：那会按槽数全量重建一次，9216 槽的样板栏会变成
     * 上千万次解码。重建结束后由 refreshPatternsFromDisks 调一次。
     */
    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (rebuildingMirror) {
            saveChanges();
            return;
        }
        super.onChangeInventory(inv, slot);
    }

    @Override
    public InternalInventory getPatternInv() {
        return readOnlyPatternInv != null ? readOnlyPatternInv : super.getPatternInv();
    }
}

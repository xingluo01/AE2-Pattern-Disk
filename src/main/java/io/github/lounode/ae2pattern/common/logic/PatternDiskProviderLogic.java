package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.LockCraftingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.pattern.DiskSlotVersions;
import io.github.lounode.ae2pattern.api.PatternDiskContents;
import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;

/**
 * A {@link PatternProviderLogic} whose available patterns are expanded from the contents of inserted
 * pattern disks, instead of a fixed slot-per-pattern inventory.
 *
 * <p>We do NOT touch AE2's private {@code patterns}/{@code patternInputs} fields. Instead we write the
 * disk patterns into the parent's own {@code patternInventory} (the {@code mirror} field below) and let the
 * parent's {@link #updatePatterns()} decode and register them. This stays within public API.</p>
 *
 * <p><b>镜像栏对外只读</b>（{@link #getPatternInv()}）：那栏只是磁盘的派生副本，外部写进去的样板不会落到
 * 任何磁盘上，却会被 `updatePatterns()` 当成可用样板注册进网络，于是成为「网络看得见、磁盘里没有」的幻影
 * 样板——磁盘一变就被抹掉，拆机也不掉落。写样板必须走 {@code getTerminalPatternInventory()}。</p>
 *
 * <p>The disk inventory is supplied lazily via a {@link Supplier} so this logic may be constructed during
 * the block-entity's parent constructor before the disk inventory field is initialized.</p>
 */
public class PatternDiskProviderLogic extends PatternProviderLogic implements PatternsFromDisks {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternDiskProviderLogic.class);

    private final Supplier<AppEngInternalInventory> diskInventorySupplier;

    /** 宿主与节点自己留一份：父类那两个字段是 private，而多面投递要靠它们定位邻居。 */
    private final PatternProviderLogicHost host;

    /** 与父类同源的动作源（同一个 {@code MachineSource} 形态，挂在同一个网格节点上）。 */
    private final IActionSource actionSource;

    /**
     * 镜像样板栏的原件（磁盘展开出的样板副本，<b>不是真身</b>）：真身在磁盘物品组件里，终端的读写走
     * {@code getTerminalPatternInventory()}。只在本类与子类内部用（重建与清空）；不对外开访问器，那等于
     * 给只读化留一个绕过口。
     *
     * <p>标记式镜像设备的样板来自网格上别的机器，但写的是同一份镜像、同一套只读语义。</p>
     */
    protected final InternalInventory mirror;

    /** 主节点：子类要扫整张网格（标记式镜像设备从别人的磁盘取样板）。 */
    protected final IManagedGridNode mainNode;

    /** {@link #getPatternInv()} 交出去的那份只读视图，构造一次、长期复用。 */
    private final InternalInventory readOnlyMirror;

    /**
     * 上次重建时的磁盘内容快照（每槽一项），由 {@link DiskSlotVersions#ofInventory} 生成、用
     * {@link DiskSlotVersions#sameAs} 比较；{@code null} 表示还没重建过。
     *
     * <p>跳过未变的重建很重要——重建会整体重写镜像——而这项检查本身的成本必须低于它跳过的工作；快照为什么
     * 能用引用比较、以及它对「原地修改内容」看不见，见 {@link DiskSlotVersions} 的类注释。</p>
     */
    private List<Object> lastDiskVersions;

    // ---- 本模组的供应器设置（AE2 的 Settings 枚举不可扩展，这几项自带 NBT 与菜单同步） ----

    public static final String NBT_BLOCKING_MODE = "blockingMode";
    public static final String NBT_BOOST_ENABLED = "boostEnabled";
    public static final String NBT_BOOST_LIMIT = "boostLimit";
    public static final String NBT_EXTRACTION_MODE = "extractionMode";

    /** 上限输入栏的可接受范围：真值在 {@link BoostMergeLimit#MAX_LIMIT}。保留这个别名是因为界面侧仍按机器类取它。 */
    public static final int MAX_BOOST_LIMIT = BoostMergeLimit.MAX_LIMIT;

    /** 本模组的挡位配置：与抽取 / 推送两块共享同一份状态。 */
    private final ProviderSettings settings;
    /** 待处理批次与存档：攒批、退回、落地、掉落。 */
    private final ProviderPendingBatches pending;
    /** 产物抽取：节拍、白名单、目标视图。 */
    private final ProviderExtraction extraction;
    /** 推送与目标解析：方向投递、阻挡判据。 */
    private final ProviderPushTargets pushTargets;
    /** 协作类看到的窄接口实现：集中封装它们需要的宿主能力。 */
    private final Host hostServices;

    public PatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            int diskSlots, Supplier<AppEngInternalInventory> diskInventorySupplier) {
        this(mainNode, host, diskSlots, diskInventorySupplier, null);
    }

    /**
     * 让调用方直接定镜像槽数：镜像设备没有自己的磁盘槽，容量没法按槽数推。
     *
     * @param mirrorSlots 显式镜像槽数；{@code null} 时仍按磁盘槽数推导
     */
    protected PatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            int diskSlots, Supplier<AppEngInternalInventory> diskInventorySupplier,
            @Nullable Integer mirrorSlots) {
        super(mainNode, host, mirrorSlots != null ? mirrorSlots : ProviderMirrorSupport.mirrorCapacity(diskSlots));
        this.diskInventorySupplier = diskInventorySupplier;
        this.lastDiskVersions = null;
        this.host = host;
        this.mainNode = mainNode;
        this.actionSource = new MachineSource(mainNode::getNode);
        // 父构造已经把镜像栏建好，这里抓住原件：覆写后的 getPatternInv() 只读，重建与清空要用原件。
        this.mirror = super.getPatternInv();
        this.readOnlyMirror = new ProviderMirrorView(mirror, () -> host.getBlockEntity());
        this.settings = new ProviderSettings();
        this.hostServices = new Host();
        this.pushTargets = new ProviderPushTargets(hostServices, settings);
        this.pending = new ProviderPendingBatches(hostServices);
        this.extraction = new ProviderExtraction(hostServices, settings);
    }

    /**
     * 镜像栏的只读视图，见 {@link ProviderMirrorView}。
     *
     * <p>这条口子必须自己堵：AE2 的 {@code PatternProviderLogicHost#getTerminalPatternInventory()} 只是默认
     * 实现（返回 {@code getLogic().getPatternInv()}），第三方上传器完全可以绕过它直接写镜像。ExtendedAE Plus
     * 的「上传到装配矩阵」就是按类名匹配到本设备后这么写的（它的 {@code findAllMatrixPatternInventories}）。
     * 写进来的样板会进合成清单、却没有任何磁盘背书：磁盘一变就被重建抹掉，拆机也不掉落。</p>
     */
    @Override
    public InternalInventory getPatternInv() {
        // 构造期（父构造尚未返回）还没有建出这份视图；那时只有本类自己会问到，原样交回原件。
        return readOnlyMirror != null ? readOnlyMirror : super.getPatternInv();
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
        if (settings.boostEnabled() || settings.blockingMode() == ProviderBlockingMode.SMART) {
            return pushWithOwnRules(patternDetails, inputHolder);
        }
        if (!AdvPatternSupport.hasDirectionalInputs(patternDetails)) {
            return super.pushPattern(patternDetails, inputHolder);
        }
        // 与父类同一批前置守卫。方向投递是另一条路，不是绕过它们的特权：这四条里「锁定合成」最要紧——
        // AE2 全仓只在父类的 pushPattern 里读它，漏掉这一条就等于这类样板上的锁完全失效，开着锁也照推。
        if (!canPush(patternDetails)) {
            return false;
        }
        if (pushTargets.pushDirectionally(patternDetails, inputHolder)) {
            return true;
        }
        return super.pushPattern(patternDetails, inputHolder);
    }

    /** 父类 {@code pushPattern} 开头那四条守卫，自己的投递路径必须逐条重放。 */
    private boolean canPush(IPatternDetails patternDetails) {
        return !isBusy() && mainNode.isActive()
                && getCraftingLockedReason() == LockCraftingMode.NONE
                && getAvailablePatterns().contains(patternDetails);
    }

    // ---- 智能阻挡 / 智能倍增：自己的投递路径 ---------------------------------

    /**
     * 智能阻挡与发配倍增走这里。
     *
     * <p>倍增时合并交接：CPU 交来的多份先存着，等它停交（或攒满上限、或攒满一个静默窗口）再一次性投给目标。
     * 停交没有公开信号：方块形态由宿主 {@code serverTick} 每 tick 逼一次，面板形态没有 {@code IGridTickable}
     * 服务槽（被 AE2 供应器逻辑占着），走 {@code LevelTickEvent.Post} → {@code pumpLogic} 汇合到同一个
     * {@code tickServer()}，所以两种形态的攒批与落地时机完全一致。</p>
     */
    private boolean pushWithOwnRules(IPatternDetails details, KeyCounter[] inputs) {
        long now = gameTime();
        // 上一 tick 留下的批先发：CPU 停交后不一定会再给我们一次 pushPattern。
        pending.flushIfQuiet(now);
        if (!canPush(details)) {
            return false;
        }
        if (!settings.boostEnabled()) {
            return hostServices.deliver(details, inputs, ProviderPushTargets.declaredInputs(details), false);
        }

        // 没设份数上限（isDue 恒 false）时这一段完全跳过：落地交给下面的停手判据，
        // 不能用「pendingCount >= 0」这种比较——那会每次都 flushAll 并拒收。
        if (settings.mergeLimit().isDue(pending.pendingCount())) {
            pending.flushAll(now);
            if (settings.mergeLimit().isDue(pending.pendingCount())) {
                // 目标一直收不下：这一份还没收下，不返回 true，让 CPU 下个 tick 重投（材料仍在网络里）。
                return false;
            }
        }

        int batchSize = pending.addBatch(details, inputs, now);
        // 这份数是该样板攒了多少份合成：AE2 的 pushPattern 一次一份，所以 list 的长度就是份数。
        if (settings.mergeLimit().isDue(batchSize)) {
            // flush 会把没投出去的份留在 pending（材料所有权已在本机），所以这里一律回答「已收下」，
            // 不能把 false 交给 CPU——那样它会拿同一份材料重投，pending 里就会出现两份。
            //
            // 目标持续收不下时 batch.size() 会一直超过额度，于是每次 push 都重试一次落地。这是既有行为，
            // 不存在丢料或叠份，只是失败重试比停手窗口那 10 tick 的节奏密。
            pending.flush(details);
            pending.setLastBatchTick(now);
        }
        return true;
    }

    /** 由宿主的服务端 tick 调用：只在发送方停手时把攒着的批交出去，不做每 tick 无条件清空。 */
    public void flushPending() {
        pending.flushPending();
    }

    private long gameTime() {
        var be = host.getBlockEntity();
        var level = be == null ? null : be.getLevel();
        return level == null ? 0L : level.getGameTime();
    }

    /** 深拷与合并都在 {@link BoostMerge}。 */

    /** 同槽同 key 求和与深拷都在 {@link BoostMerge}。 */

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

        InternalInventory patternInv = mirror;
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

        rewriteMirror(all);

        // Parent decodes patternInventory into its patterns list and requests a grid update.
        updatePatterns();

        lastDiskVersions = versions;
        return true;
    }

    /**
     * 用一组样板整体替换镜像栏：抑制逐槽通知，由调用方在末尾跑一次 {@link #updatePatterns()}。
     *
     * <p>只在镜像容量不足时截断并记一条日志——镜像设备的样板来自整张网格，容量是按「满配磁盘」估的上界，
     * 正常玩法碰不到；碰得到也不该静默少掉几张。</p>
     */
    protected final void rewriteMirror(List<ItemStack> patterns) {
        rebuildingMirror = true;
        try {
            mirror.clear();
            int limit = Math.min(patterns.size(), mirror.size());
            for (int i = 0; i < limit; i++) {
                // copy(): 样板出自磁盘组件，镜像的持有者不能借此改到磁盘看到的内容。
                mirror.setItemDirect(i, patterns.get(i).copy());
            }
            if (patterns.size() > limit && LOGGER.isWarnEnabled()) {
                LOGGER.warn("样板磁盘供应器的镜像容量不足：{} 张样板只能放入前 {} 张，其余被截断",
                        patterns.size(), limit);
            }
        } finally {
            rebuildingMirror = false;
        }
    }

    // The parent's updatePatterns re-reads patternInventory; our refresh already filled it.
    @Override
    public void updatePatterns() {
        super.updatePatterns();
        // 样板集合变了，阻挡档的全量输入缓存与抽取白名单都得重建。
        pushTargets.invalidatePatternInputs();
        extraction.invalidateOutputs();
        pending.dropOrphanBatches();
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
        InternalInventory patternInv = mirror;
        if (patternInv == null) {
            return;
        }
        rebuildingMirror = true;
        try {
            patternInv.clear();
        } finally {
            rebuildingMirror = false;
        }
        // 镜像已非磁盘内容的映像：不把快照作废的话，下一个非强制的 refresh 会拿旧快照比出「没变」而跳过重建。
        lastDiskVersions = null;
        updatePatterns();
    }

    /**
     * 拆机/重置时清空镜像。父实现逐槽清，每槽一次通知就直接跑一次 9216 槽的 {@code updatePatterns()}——
     * 通知量会被放大成几千万次槽访问。这里用与重建同一个抑制开关（{@link #rebuildingMirror}）把它压成一次，
     * 末尾统一重建。
     *
     * <p>与 {@link #clearMirror()} 同一套思路：两个入口都只关心「空终态」。</p>
     */
    @Override
    public void clearContent() {
        rebuildingMirror = true;
        try {
            super.clearContent();
        } finally {
            rebuildingMirror = false;
        }
        // 镜像内容不再是磁盘内容的映像，快照得作废，否则下一个非强制刷新会拿旧快照比出「没变」。
        lastDiskVersions = null;
        // 合并中的材料已经由 addDrops 掉出去了，这里只清账，避免拆机后再被 flush 一遍。
        pending.clearBatches();
        updatePatterns();
    }

    // ---- 内存卡：本模组自己的设置 ------------------------------------------

    /**
     * 把本模组的设置写进内存卡。AE2 的 {@code Settings} 枚举不可扩展，阻挡三档/发配倍增/产物抽取只能
     * 自带一段组件，否则粘贴时这三项会悄悄丢掉。
     */
    public void exportCustomSettings(DataComponentMap.Builder builder) {
        var tag = new CompoundTag();
        settings.writeCustomTag(tag);
        writeExtraSettings(tag);
        builder.set(AEPatternRegistries.MEMORY_CARD_SETTINGS, tag);
    }

    /**
     * 应用内存卡里的本模组设置。卡上没有这一段（旧卡、或由别的机器写出的卡）时保持原样。
     */
    public void importCustomSettings(DataComponentMap input) {
        var tag = input.get(AEPatternRegistries.MEMORY_CARD_SETTINGS.get());
        if (tag == null) {
            return;
        }
        settings.readCustomTag(tag);
        readExtraSettings(tag);
        // 与读档同一条对齐：AE2 那边的开关得跟着本模组的三档走。
        getConfigManager().putSetting(Settings.BLOCKING_MODE,
                settings.blockingMode() == ProviderBlockingMode.ALL ? YesNo.YES : YesNo.NO);
        saveChanges();
    }

    /** 子类追加要随卡走的设置（标记式设备的选中标记）。 */
    protected void writeExtraSettings(CompoundTag tag) {
    }

    /** 子类读回自己追加的设置。 */
    protected void readExtraSettings(CompoundTag tag) {
    }

    // ---- 供应器设置：阻挡三档与发配倍增 -----------------------------------

    public ProviderBlockingMode getBlockingMode() {
        return settings.blockingMode();
    }

    /**
     * 切换阻挡档位。{@link ProviderBlockingMode#ALL} 交回父类，其余两档在父类眼里都是「关」——
     * 智能阻挡的判据在本类，父类只要不插手。
     */
    public void setBlockingMode(@Nullable ProviderBlockingMode mode) {
        if (!settings.setBlockingMode(mode)) {
            return;
        }
        getConfigManager().putSetting(Settings.BLOCKING_MODE,
                mode == ProviderBlockingMode.ALL ? YesNo.YES : YesNo.NO);
        saveChanges();
    }

    public void cycleBlockingMode() {
        setBlockingMode(settings.nextBlockingMode());
    }

    // ---- 产物抽取 -------------------------------------------------------------

    public ProviderExtractionMode getExtractionMode() {
        return settings.extractionMode();
    }

    /**
     * 切换产物抽取模式。切档后立即生效（下一 tick 就抽一次），不把上一档的退避带过来。
     */
    public void setExtractionMode(@Nullable ProviderExtractionMode mode) {
        if (!settings.setExtractionMode(mode)) {
            return;
        }
        extraction.resetForModeChange();
        saveChanges();
    }

    public void cycleExtractionMode() {
        setExtractionMode(settings.nextExtractionMode());
    }

    /**
     * 服务端每 tick 的唯一入口：发配倍增的停交兜底 + 产物抽取节拍。
     *
     * <p>方块形态由 {@code serverTick} 调，面板形态由 {@code pumpLogic} 调——两条驱动通道只在这里汇合，
     * 想改「每 tick 做什么」只改这一处，不用在两个宿主里各改一遍。</p>
     */
    public void tickServer() {
        // 读档时攒批只存了原始 Tag（没有 level 解不出样板），到第一次 tick 才还原。没事时是一次空列表检查。
        pending.restorePendingBatches();
        pending.retryReturns(gameTime());
        flushPending();
        tickExtraction();
    }

    /**
     * 每 tick 的抽取节拍：由方块形态的 {@code serverTick} 与面板形态的世界 tick 事件驱动。
     *
     * <p>冷却递减与实际抽取都放在这里，是因为只有这两个入口知道「这个世界在跑」；逻辑自己不持有 tick 源。</p>
     */
    public void tickExtraction() {
        extraction.tick();
    }

    public boolean isBoostEnabled() {
        return settings.boostEnabled();
    }

    public void setBoostEnabled(boolean enabled) {
        if (enabled == settings.boostEnabled()) {
            return;
        }
        settings.setBoostEnabled(enabled);
        if (!enabled) {
            // 关掉时把攒着的批立即发出去，不让材料滞在机器里。
            pending.flushAll(gameTime());
        }
        saveChanges();
    }

    public int getBoostLimit() {
        return settings.boostLimit();
    }

    public void setBoostLimit(int limit) {
        if (settings.setBoostLimit(limit)) {
            saveChanges();
        }
    }

    /**
     * 攒到玩家设的份数上限时对外算忙：CPU 见到忙就不再交付，超出的材料留在网络里。
     *
     * <p>攒批期间（未达上限）<b>不算忙</b>：材料已经收进本机内存，让 CPU 继续交付才能攒得更深。由此有一条
     * 硬约束——攒批期间必须有人把批捅出去。玩家填了数字时由 {@code isDue} 负责；填 0（不设限）时就只剩
     * 停手判据，发送方若永不停手批就会一直攒着。那是有意的取舍：要上界就填数字。</p>
     *
     * <p>同时这里也是「CPU 停交」的兵家必争之处：CPU 每 tick 都会问一次，到期的那批就在这一步发出。</p>
     */
    @Override
    public boolean isBusy() {
        if (!pending.isEmpty()) {
            // 兜底：正常路径已由 tickServer()（方块 serverTick / 面板 pumpLogic）每 tick 覆盖，
            // 这里只防两条驱动通道都不可用的极端情形。
            pending.flushIfQuiet(gameTime());
        }
        return super.isBusy() || settings.mergeLimit().isDue(pending.pendingCount());
    }

    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag, registries);
        pending.writePendingBatches(tag, registries);
        // 退回缓冲与攒批表分开写：它不依赖 level，也不受「盘里没有样板」这些早退影响，
        // 而「只压着退回材料、没攒批」恰恰是最常见的状态，两者必须各自独立进入存档。
        pending.writePendingReturns(tag, registries);
        settings.writeNbt(tag);
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.readFromNBT(tag, registries);
        settings.readNbt(tag);
        // 之前漏了这一步：档位写进存档却从不读回，读档后总是回落 OFF。
        // 退避进度是运行时状态，不落盘：快速档的初始间隔永远是 1 tick（读档后从最快档重新开始）。
        extraction.resetPacingOnLoad();
        // 读档后把 AE2 的设置对齐一次：内存卡/旧存档里那个开关可能与本模组的三档不一致。
        getConfigManager().putSetting(Settings.BLOCKING_MODE,
                settings.blockingMode() == ProviderBlockingMode.ALL ? YesNo.YES : YesNo.NO);
        pending.readPendingBatches(tag);
        pending.readPendingReturns(tag, registries);
    }

    /** 拆机时把合并中的物品掉出去，与父类处理发送队列同口径。 */
    @Override
    public void addDrops(List<ItemStack> drops) {
        super.addDrops(drops);
        pending.addDrops(drops);
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

    // ---- 协作类与宿主之间的窄接口实现 ---------------------------------------

    /** 协作类要回落到父类 {@code pushPattern} 时走这里：{@code super} 调用只能写在宿主类里。 */
    private boolean pushPatternViaParent(IPatternDetails details, KeyCounter[] inputs) {
        return super.pushPattern(details, inputs);
    }

    /** 父类能力经宿主包装：协作类不必为了这几项去持有整个 logic 对象。 */
    private void resetCraftingLockViaParent() {
        resetCraftingLock();
    }

    private boolean isBlockingViaParent() {
        return isBlocking();
    }

    private Iterable<IPatternDetails> availablePatternsViaParent() {
        return getAvailablePatterns();
    }

    /**
     * 各协作类共用的宿主能力实现：把状态搬走后，协作类只看到各自的窄接口
     * （{@link PendingHost} / {@link ExtractionHost} / {@link PushHost}），不持有整个 logic 对象。
     */
    private final class Host implements PendingHost, ExtractionHost, PushHost {

        @Override
        public @Nullable BlockEntity blockEntity() {
            return host.getBlockEntity();
        }

        @Override
        public long gameTime() {
            return PatternDiskProviderLogic.this.gameTime();
        }

        @Override
        public IActionSource actionSource() {
            return PatternDiskProviderLogic.this.actionSource;
        }

        @Override
        public @Nullable MEStorage networkInventory() {
            var node = mainNode.getNode();
            if (node == null) {
                return null;
            }
            var grid = node.getGrid();
            return grid == null ? null : grid.getStorageService().getInventory();
        }

        @Override
        public InternalInventory mirror() {
            return PatternDiskProviderLogic.this.mirror;
        }

        @Override
        public boolean deliver(IPatternDetails details, KeyCounter[] inputs, Set<AEKey> ownInputs,
                boolean inventoryOnly) {
            return pushTargets.deliver(details, inputs, ownInputs, inventoryOnly);
        }

        @Override
        public boolean superPushPattern(IPatternDetails details, KeyCounter[] inputs) {
            return pushPatternViaParent(details, inputs);
        }

        /** 自己的可用面判据在 {@link ProviderPushTargets#activeSides}：同一套过滤，抽取与推送共用。 */
        @Override
        public Set<Direction> activeSides() {
            return ProviderPushTargets.activeSides(host, mainNode);
        }

        @Override
        public Iterable<IPatternDetails> availablePatterns() {
            return availablePatternsViaParent();
        }

        @Override
        public void resetCraftingLock() {
            resetCraftingLockViaParent();
        }

        @Override
        public boolean isBlocking() {
            return isBlockingViaParent();
        }
    }
}

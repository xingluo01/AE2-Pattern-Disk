package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import io.github.lounode.ae2pattern.api.PatternDiskApi;

/**
 * 协作类与宿主之间的窄接口：攒批块只需要这几项宿主能力，不持有整个 logic 对象。
 */
interface PendingHost {

    @Nullable
    BlockEntity blockEntity();

    long gameTime();

    IActionSource actionSource();

    /** 网络存储；网络未接上（节点 / 网格为空）时返回 {@code null}。 */
    @Nullable
    MEStorage networkInventory();

    InternalInventory mirror();

    /** 一次完整投递：返回是否算已交付（对应宿主的 {@code Delivery != NONE}）。 */
    boolean deliver(IPatternDetails details, KeyCounter[] inputs, Set<AEKey> ownInputs, boolean inventoryOnly);

    /** 回落到父类 {@code pushPattern} 的发送队列。 */
    boolean superPushPattern(IPatternDetails details, KeyCounter[] inputs);
}

/**
 * 发配倍增的「待处理批次」：攒批表、待还原表、退回缓冲，以及它们的存档、落地、掉落与重试。
 *
 * <p>状态与它的不变量整体在这里：材料一旦进了攒批表，所有权就在本机，直到被投递出去、退回网络或拆机掉落。
 * 宿主只在 {@code updatePatterns} / {@code tickServer} / 各入口处转调进来。</p>
 */
final class ProviderPendingBatches {

    /** 沿用宿主的 logger 名，日志分类不变。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternDiskProviderLogic.class);

    /**
     * 写进存档的撺批份数上限。
     *
     * <p>撺批表在“份数上限填 0”（不设限）时没有上界，而每份都要写一组 tag。不封的话一张大订单可以把存档
     * 撑到数 MB，区块存档和网络同步都跟着变慢。超过的部分不写盘——它们仍在内存里、仍会正常投递，只是存档
     * 不背这个量；真碰上崩服就会丢，所以配一次限流告警把玩家引向“填个数字”。</p>
     */
    private static final int MAX_PERSISTED_BATCHES = 512;

    /** 攒批表与待还原表的 NBT 键。 */
    private static final String NBT_PENDING_BATCHES = "pendingBatches";
    private static final String NBT_PENDING_PATTERN = "pattern";
    private static final String NBT_PENDING_INPUTS = "inputs";
    private static final String NBT_PENDING_RETURNS = "pendingReturns";

    /** 一条待还原的批：样板物品 + 每份的输入总量（每份一个 ListTag）。 */
    private record PendingBatchRestore(CompoundTag pattern, List<ListTag> batches) {}

    private final PendingHost host;

    /**
     * 发配倍增的合并表：每张样板一条，按到达顺序。值里的每个 {@code KeyCounter[]} 是 CPU 交来的一份完整输入。
     *
     * <p>只在发配倍增开启时非空。发配倍增期间材料在这里，不在父类的发送队列里，所以拆机必须由掉落路径
     * 掉出去。</p>
     */
    private final Map<IPatternDetails, List<KeyCounter[]>> pendingBatches = new LinkedHashMap<>();

    /** 最近一次收到交付的游戏时间，用来判定「发送方停手了」。 */
    private long pendingBatchTick = -1L;

    /**
     * 读档时把攒批的原始条目暂存到这里，等首个 tick（有 level）再解码成样板。
     *
     * <p>{@code readFromNBT} 只拿到 {@link HolderLookup.Provider}，没有 {@code Level}，而
     * {@code PatternDetailsHelper.decodePattern} 必须要 level。所以读档阶段只搬运原始 tag，不在那里解码。</p>
     *
     * <p>存的是「编码样板物品 + 每份的扁平输入」，不存 {@code IPatternDetails}：样板是磁盘内容的派生态，
     * 每次重建都是新实例，只有物品形态能跨存档。</p>
     */
    private List<PendingBatchRestore> pendingRestore = List.of();

    /**
     * 退回网络失败的材料，等网络可用了再退。
     *
     * <p>与待还原表同一种性质：材料是 CPU 已经记过账的交付，网络没接上（断电、被拆下来）时<b>既不能丢也不能
     * 掉在地上</b>——掉成物品就成了不可回滚，而且机器在无人加载的区块里时会直接被清除。所以先留在这里，
     * 通电后每 {@link BoostMergeLimit#QUIET_TICKS} 个 tick 重试一次。</p>
     *
     * <p>它也进存档：重试窗口可能刚好跨到一次存档，不写就白等了。</p>
     */
    private final List<KeyCounter> pendingReturns = new ArrayList<>();

    /** 下一次尝试退回网络的时间，避免网络长时间不通时每 tick 刷一遍。 */
    private long nextReturnAttempt;

    ProviderPendingBatches(PendingHost host) {
        this.host = host;
    }

    boolean isEmpty() {
        return pendingBatches.isEmpty();
    }

    /**
     * 发送方停手就把攒着的批交出去。
     *
     * <p>判据只有一条：距上次收下满 {@link BoostMergeLimit#QUIET_TICKS} 个 tick。</p>
     *
     * <p>不能改成「只要不是本 tick 就 flush」——那等于发送方隔一 tick 来一次就把批清一次，合并攒不起来，
     * 原材料会陆陆续续单独写进目标，正是「发配倍增好像没生效」的那种表现。</p>
     *
     * <p><b>没设份数上限（填 0）时，这里是唯一的落地触发。</b>发送方如果一直不停手，批就一直攒着——这是
     * 有意的取舍：要上界就填数字，填了就由 {@code isDue(craftCount)} 按份数落地，而不是靠计时。</p>
     */
    void flushIfQuiet(long now) {
        if (pendingBatches.isEmpty()) {
            return;
        }
        if (now - pendingBatchTick >= BoostMergeLimit.QUIET_TICKS) {
            flushAll(now);
        }
    }

    void flushAll(long now) {
        for (var details : new ArrayList<>(pendingBatches.keySet())) {
            flush(details);
        }
        pendingBatchTick = now;
    }

    /** 发出一张样板攒下的全部份数；合并批先试，不行再逐份（逐份失败交给父类的发送队列）。 */
    void flush(IPatternDetails details) {
        var batch = pendingBatches.remove(details);
        if (batch == null || batch.isEmpty()) {
            return;
        }
        var ownInputs = ProviderPushTargets.declaredInputs(details);
        // 合并成一次 IO：槽数对不上时 merge 返回 null（那两种到货形状下标意义不同，按下标截断会丢料），
        // 那就跳过合并、直接走下面的逐份路径。
        //
        // 单份批次（size == 1）不进合并：没有可合并的，合并的意义只是把它的投递降成 inventoryOnly（只给存储、
        // 不给机器），而单份恰好是机器最愿意接的形状。所以它走逐份路径的 deliver(..., false)。
        var mergedInputs = batch.size() > 1 ? BoostMerge.merge(batch) : null;
        if (mergedInputs != null) {
            if (host.deliver(details, mergedInputs, ownInputs, true)) {
                // ALL 与 PARTIAL 都算已接管：合并批已进入目标，重投就是复制。
                return;
            }
        }
        var leftover = new ArrayList<KeyCounter[]>();
        for (var one : batch) {
            if (host.deliver(details, one, ownInputs, false)) {
                // 部分投出也算已交付：材料已经进去，重投就是复制。
                continue;
            }
            if (host.superPushPattern(details, one)) {
                continue;
            }
            // 只回填真的没投出去的那几份：整批回填会把已投出的份再投一次（复制）。
            leftover.add(one);
        }
        if (!leftover.isEmpty()) {
            pendingBatches.computeIfAbsent(details, k -> new ArrayList<>()).addAll(leftover);
        }
    }

    /** 由宿主的服务端 tick 调用：只在发送方停手时把攒着的批交出去，不做每 tick 无条件清空。 */
    void flushPending() {
        flushIfQuiet(host.gameTime());
    }

    int pendingCount() {
        int total = 0;
        for (var list : pendingBatches.values()) {
            total += list.size();
        }
        return total;
    }

    /** 收下一份 CPU 交付，返回这张样板当前攒了多少份。 */
    int addBatch(IPatternDetails details, KeyCounter[] inputs, long now) {
        pendingBatches.computeIfAbsent(details, k -> new ArrayList<>()).add(BoostMerge.copy(inputs));
        pendingBatchTick = now;
        return pendingBatches.get(details).size();
    }

    void setLastBatchTick(long now) {
        pendingBatchTick = now;
    }

    void clearBatches() {
        pendingBatches.clear();
    }

    /**
     * 把「样板已经不在磁盘上」的撺批退出去。
     *
     * <p>撺批表的 key 是 {@code IPatternDetails}，而样板是磁盘内容的派生态：玩家把磁盘抽了、或换了一张别的，
     * 旧表里那一项就再也没有对应的样板了。它的材料是 CPU 已经记过账的交付，不能留在表里等一个永远不会发生的
     * 投递（也不该到存档时才被当成“没有样板可存”默默丢掉）。</p>
     *
     * <p>放在镜像重建后（{@code updatePatterns}）而不是写盘时：镜像是从磁盘重建的，重建完那一刻就是判断
     * “还在不在”最准的时机；写在写盘路径里会把副作用塞进存档流程，而存档应该只读。</p>
     */
    void dropOrphanBatches() {
        if (pendingBatches.isEmpty()) {
            return;
        }
        var be = host.blockEntity();
        if (be == null || !(be.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var byPattern = patternStacksByDetails(level);
        var iterator = pendingBatches.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (byPattern.containsKey(entry.getKey())) {
                continue;
            }
            int dropped = entry.getValue().size();
            for (var inputs : entry.getValue()) {
                returnInputsToNetwork(flatten(inputs));
            }
            iterator.remove();
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Pattern disk provider at {} dropped {} merged hand-over(s) of a pattern that is no "
                        + "longer on the disks; their inputs went back to the network", be.getBlockPos(), dropped);
            }
        }
    }

    // ---- 内存卡 / 存档：本块自己的两段 NBT --------------------------------

    /** 待退回材料写成平平的 key/amount 表：它们不需要样板就能还原。 */
    void writePendingReturns(CompoundTag tag, HolderLookup.Provider registries) {
        if (pendingReturns.isEmpty()) {
            return;
        }
        var list = new ListTag();
        for (var counter : pendingReturns) {
            for (var stack : counter) {
                if (stack.getLongValue() <= 0) {
                    continue;
                }
                if (list.size() >= MAX_PERSISTED_BATCHES) {
                    // 超出部分仍留在内存里、仍会重试，只是不进存档。
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Pattern disk provider saved only the first {} of {} entries in its "
                                + "pending-return buffer; the rest stay in memory only",
                                MAX_PERSISTED_BATCHES, list.size());
                    }
                    tag.put(NBT_PENDING_RETURNS, list);
                    return;
                }
                var keyTag = stack.getKey().toTagGeneric(registries);
                if (keyTag == null) {
                    // 与写攒批表同口径：编不出来的 key 至少留一条日志，否则「缓冲里少了一点」永远查不到。
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Pattern disk provider cannot serialise {} in its pending-return buffer; "
                                + "x{} stays in memory only", stack.getKey(), stack.getLongValue());
                    }
                    continue;
                }
                var entryTag = new CompoundTag();
                entryTag.put("key", keyTag);
                entryTag.putLong("amount", stack.getLongValue());
                list.add(entryTag);
            }
        }
        if (!list.isEmpty()) {
            tag.put(NBT_PENDING_RETURNS, list);
        }
    }

    void readPendingReturns(CompoundTag tag, HolderLookup.Provider registries) {
        pendingReturns.clear();
        if (!tag.contains(NBT_PENDING_RETURNS, Tag.TAG_LIST)) {
            return;
        }
        var list = tag.getList(NBT_PENDING_RETURNS, Tag.TAG_COMPOUND);
        var counter = new KeyCounter();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            long amount = entry.getLong("amount");
            if (amount <= 0 || !entry.contains("key", Tag.TAG_COMPOUND)) {
                continue;
            }
            var key = AEKey.fromTagGeneric(registries, entry.getCompound("key"));
            if (key != null) {
                counter.add(key, amount);
            }
        }
        if (!counter.isEmpty()) {
            pendingReturns.add(counter);
        }
    }

    /**
     * 把攒批表写进 NBT。
     *
     * <p>样板是磁盘内容的派生态，存不进存档；这里存的是编码样板<b>物品</b>，与镜像栏里的那一张
     * 对应。反查靠 {@code IPatternDetails} 的 equals：{@code getAvailablePatterns()} 本身就是个 Set，
     * 样板实现必然重写了 equals/hashCode，否则父类没法去重。</p>
     *
     * <p>找不到对应物品的批直接跳过并记一条 warn——那说明磁盘已经换过、这条批成了孤儿，它的材料应该早在
     * {@code updatePatterns} 里退回去了，不应该还留在这里。</p>
     */
    void writePendingBatches(CompoundTag tag, HolderLookup.Provider registries) {
        if (pendingBatches.isEmpty()) {
            return;
        }
        var be = host.blockEntity();
        var level = be == null ? null : be.getLevel();
        if (level == null) {
            return;
        }
        var byPattern = patternStacksByDetails(level);
        if (byPattern.isEmpty()) {
            return;
        }

        var list = new ListTag();
        int written = 0;
        boolean truncated = false;
        for (var entry : pendingBatches.entrySet()) {
            var patternStack = byPattern.get(entry.getKey());
            if (patternStack == null) {
                // 正常不可达：孤儿批在每次镜像重建后就被退掉了。
                // 真到了这里说明磁盘在两次重建之间被动过，不写盘、也不在这里做副作用（存档只读）。
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Pattern disk provider at {} has a merged batch with no disk pattern left; "
                            + "it is not written to the save", be.getBlockPos());
                }
                continue;
            }

            var batches = new ListTag();
            for (var inputs : entry.getValue()) {
                if (written >= MAX_PERSISTED_BATCHES) {
                    truncated = true;
                    break;
                }
                written++;
                // 三层：每份 → 每个输入槽 → 条目。槽位形状必须留着，否则读档后的批与新建的批槽数不等，
                // BoostMerge 合并不了，那台样板会退化成逐份投递。
                var slotsTag = new ListTag();
                for (var counter : inputs) {
                    var slotTag = new ListTag();
                    if (counter != null) {
                        for (var stack : counter) {
                            if (stack.getLongValue() <= 0) {
                                continue;
                            }
                            var keyTag = stack.getKey().toTagGeneric(registries);
                            if (keyTag == null) {
                                if (LOGGER.isWarnEnabled()) {
                                    LOGGER.warn("Pattern disk provider cannot persist {} in a merged batch; "
                                            + "it will be missing from the save", stack.getKey());
                                }
                                continue;
                            }
                            var entryTag = new CompoundTag();
                            entryTag.put("key", keyTag);
                            entryTag.putLong("amount", stack.getLongValue());
                            slotTag.add(entryTag);
                        }
                    }
                    slotsTag.add(slotTag);
                }
                batches.add(slotsTag);
            }

            var patternTag = new CompoundTag();
            patternTag.put(NBT_PENDING_PATTERN, patternStack.saveOptional(registries));
            patternTag.put(NBT_PENDING_INPUTS, batches);
            list.add(patternTag);
            if (truncated) {
                break;
            }
        }
        if (!list.isEmpty()) {
            tag.put(NBT_PENDING_BATCHES, list);
        }
        if (truncated && LOGGER.isWarnEnabled()) {
            LOGGER.warn("Pattern disk provider at {} only saved the first {} of {} merged hand-over(s); the other {} "
                    + "stay in memory only. Set a hand-over cap to keep the save small", be.getBlockPos(),
                    MAX_PERSISTED_BATCHES, pendingCount(),
                    Math.max(0, pendingCount() - MAX_PERSISTED_BATCHES));
        }
    }

    /** 读档只搬运原始 tag：decode 要 level，而这里拿不到。 */
    void readPendingBatches(CompoundTag tag) {
        pendingRestore = List.of();
        if (!tag.contains(NBT_PENDING_BATCHES, Tag.TAG_LIST)) {
            return;
        }
        var list = tag.getList(NBT_PENDING_BATCHES, Tag.TAG_COMPOUND);
        var restored = new ArrayList<PendingBatchRestore>(list.size());
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            if (!entry.contains(NBT_PENDING_PATTERN, Tag.TAG_COMPOUND)) {
                continue;
            }
            var batchesTag = entry.getList(NBT_PENDING_INPUTS, Tag.TAG_LIST);
            var batches = new ArrayList<ListTag>(batchesTag.size());
            for (int b = 0; b < batchesTag.size(); b++) {
                batches.add(batchesTag.getList(b));
            }
            restored.add(new PendingBatchRestore(entry.getCompound(NBT_PENDING_PATTERN), batches));
        }
        pendingRestore = restored;
    }

    /**
     * 首个 tick 把待还原的批解码回攒批表。
     *
     * <p>解码不出来（样板已经不在磁盘里、或者物品 tag 损坏）时，这一批的材料<b>退回网络</b>；网络也没接上
     * （断电、已拆除）就掉在该机器位置——这两条都是为了不让 CPU 已经记过账的材料凭空消失。</p>
     */
    void restorePendingBatches() {
        if (pendingRestore.isEmpty()) {
            return;
        }
        var be = host.blockEntity();
        var level = be == null ? null : be.getLevel();
        if (level == null) {
            return;
        }
        var restore = pendingRestore;
        pendingRestore = List.of();

        for (var entry : restore) {
            var patternStack = ItemStack.parseOptional(level.registryAccess(), entry.pattern());
            var details = patternStack.isEmpty() ? null : PatternDiskApi.decodePattern(patternStack, level);
            if (details == null) {
                // 样板没了：把这一批的材料原样退回去，不让它跟着方块一起消失。
                for (var inputsTag : entry.batches()) {
                    returnInputsToNetwork(flatten(readSlicedInputs(inputsTag, level.registryAccess())));
                }
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Pattern disk provider at {} could not restore a merged batch (pattern gone); "
                            + "its inputs go to the pending-return buffer", be.getBlockPos());
                }
                continue;
            }

            var existing = pendingBatches.get(details);
            var target = existing != null ? existing : new ArrayList<KeyCounter[]>();
            for (var inputsTag : entry.batches()) {
                var inputs = readSlicedInputs(inputsTag, level.registryAccess());
                if (isBlank(inputs)) {
                    continue;
                }
                target.add(inputs);
            }
            if (!target.isEmpty()) {
                pendingBatches.put(details, target);
            }
        }
        pendingBatchTick = host.gameTime();
    }

    /** 把一份按槽的输入还原成 KeyCounter[]；槽数原样保留，读档后的批才能与新建的批合并。 */
    private static KeyCounter[] readSlicedInputs(ListTag slotsTag, HolderLookup.Provider registries) {
        var inputs = new KeyCounter[slotsTag.size()];
        for (int slot = 0; slot < slotsTag.size(); slot++) {
            var counter = new KeyCounter();
            var slotTag = slotsTag.getList(slot);
            for (int i = 0; i < slotTag.size(); i++) {
                var entry = slotTag.getCompound(i);
                long amount = entry.getLong("amount");
                if (amount <= 0 || !entry.contains("key", Tag.TAG_COMPOUND)) {
                    continue;
                }
                var key = AEKey.fromTagGeneric(registries, entry.getCompound("key"));
                if (key != null) {
                    counter.add(key, amount);
                }
            }
            inputs[slot] = counter;
        }
        return inputs;
    }

    /** 把按槽的输入压成一个平坦 counter，供退回/掉落使用（那边不关心槽位）。 */
    private static KeyCounter flatten(KeyCounter[] inputs) {
        var counter = new KeyCounter();
        for (var slice : inputs) {
            if (slice == null) {
                continue;
            }
            for (var stack : slice) {
                counter.add(stack.getKey(), stack.getLongValue());
            }
        }
        return counter;
    }

    /** 一份输入里有没有料；全空就不必占一个位置。 */
    private static boolean isBlank(KeyCounter[] inputs) {
        for (var slice : inputs) {
            if (slice != null && !slice.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 磁盘镜像里每一张样板对应的物品：{@code IPatternDetails} 靠 equals 反查到它来自哪一格。 */
    private Map<IPatternDetails, ItemStack> patternStacksByDetails(Level level) {
        var result = new HashMap<IPatternDetails, ItemStack>();
        var mirror = host.mirror();
        for (int slot = 0; slot < mirror.size(); slot++) {
            var stack = mirror.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var details = PatternDiskApi.decodePattern(stack, level);
            if (details != null) {
                result.putIfAbsent(details, stack);
            }
        }
        return result;
    }

    /**
     * 把材料退回网络。
     *
     * <p>网络没接上（断电、被拆下来）或装不下时，余量进退回缓冲静静等着，通电后每
     * {@link BoostMergeLimit#QUIET_TICKS} 个 tick 重试一次。<b>不掉地上</b>：掉落物会过期、
     * 玩家也未必在场，那是不可回滚的丢料；只有拆机（掉落路径）才该掉。</p>
     */
    private void returnInputsToNetwork(KeyCounter inputs) {
        var leftover = insertIntoNetwork(inputs);
        if (leftover != null && !leftover.isEmpty()) {
            // 网络接不上或装不下：先攒着，通电后节流重试。不掉地上——那是不可回滚的。
            pendingReturns.add(leftover);
        }
    }

    /** 把材料插回网络，返回没插进去的那部分（全部插进去时返回 null）。 */
    @Nullable
    private KeyCounter insertIntoNetwork(KeyCounter inputs) {
        // 节点可能尚未初始化或已销毁（AE2 的 IManagedGridNode 允许 getNode() 为空，面板形态首 tick
        // 尤其容易碰上）。这里不能直接解引用：抛异常会打断 tickServer，而本意是「接不上就静静等着」。
        var network = host.networkInventory();
        if (network == null) {
            return inputs;
        }
        KeyCounter leftover = null;
        for (var entry : inputs) {
            long amount = entry.getLongValue();
            if (amount <= 0) {
                continue;
            }
            long inserted = network.insert(entry.getKey(), amount, Actionable.MODULATE, host.actionSource());
            long rest = amount - inserted;
            if (rest > 0) {
                if (leftover == null) {
                    leftover = new KeyCounter();
                }
                leftover.add(entry.getKey(), rest);
            }
        }
        return leftover;
    }

    /**
     * 把攒着待退回的材料再试一次。
     *
     * <p>节流到每 {@link BoostMergeLimit#QUIET_TICKS} 个 tick 一次：网络断开时这两个数可能是几百 tick，
     * 每 tick 跑一次模拟插入没意义，还会把主线程的预算吃掉。</p>
     */
    void retryReturns(long now) {
        if (pendingReturns.isEmpty() || now < nextReturnAttempt) {
            return;
        }
        nextReturnAttempt = now + BoostMergeLimit.QUIET_TICKS;
        // 整表替换而不是边遍历边改：同一次重试里没退完的会变成新的剩余量，直接写回原表会 CME。
        var stillPending = new ArrayList<KeyCounter>();
        for (var pending : pendingReturns) {
            var leftover = insertIntoNetwork(pending);
            if (leftover != null && !leftover.isEmpty()) {
                stillPending.add(leftover);
            }
        }
        pendingReturns.clear();
        pendingReturns.addAll(stillPending);
    }

    /** 拆机时把合并中与待退回的物品掉出去，与父类处理发送队列同口径。 */
    void addDrops(List<ItemStack> drops) {
        for (var batch : pendingBatches.values()) {
            for (var inputs : batch) {
                for (var counter : inputs) {
                    for (var entry : counter) {
                        if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0) {
                            drops.add(itemKey.toStack(
                                    (int) Math.min(Integer.MAX_VALUE, entry.getLongValue())));
                        }
                    }
                }
            }
        }
        pendingBatches.clear();
        // 退回缓冲里也是 CPU 已记账的材料：拆机时不掉出去就永远没有了。
        for (var counter : pendingReturns) {
            for (var entry : counter) {
                if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0) {
                    drops.add(itemKey.toStack(
                            (int) Math.min(Integer.MAX_VALUE, entry.getLongValue())));
                }
            }
        }
        pendingReturns.clear();
    }
}

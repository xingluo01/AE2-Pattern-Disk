package io.github.lounode.ae2pattern.common.logic;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/**
 * 并行槽位估算：一次交付能给多少个完整配方，也就是「元件缓冲还能装下几份这个配方」。
 *
 * <p>NEO ECO 在递出整批之前先问这个数，它决定一次交付带走多少份——这样交付就再也不是「一次一个配方」。</p>
 *
 * <p><b>按设计就是保守估计。</b>ECO 的契约不指名配方，而元件的空闲空间是所有 key 共享的，所以「每个 key
 * 各自算一遍取最小」不是答案：每个槽位的模拟都把元件看成只剩自己的样子，各自认为自己能独占整片空闲空间，
 * 按最小者定批量，等于要求「每个 key 各占满一次总空间」。因此估计取
 * {@code min(各槽空闲 / 每份总需求)}，在「配方的每个 key 都已在缓冲里」时是个安全下界：{@code n} 份的总需求
 * 是 {@code n × 每份总需求}，而它们共享同一片空间。尚未在缓冲里的 key 每多一个还要多付一份类型条目，
 * 所以那些条目在除法之前单独预留（见 {@link CellBuffer#typeEntryUnits}）。</p>
 *
 * <p>两条前提让这个算法站得住，而它们都由本机接受的输入保证：输入全是物品 key，所以每个 key 的
 * {@code amountPerByte} 相同、物品空间可以跨元件相加；携流体的配方在派发前就被拒。而「每份用量」取自该槽
 * 第一个可能的输入，也就是配方列在最前面的那个变体——具体 key 由 ECO 自己解析，所以候选之间用量不同的
 * 配方可能被按错的变体估。这里宁可小估：被拒的交付会让整批退回逐份路径，代价是每份一次 push。</p>
 *
 * <p>估算是每 tick 一次的记忆值：ECO 每 tick 会对多个候选各问一次，重算几遍纯属浪费。</p>
 */
public final class ParallelSlotProbe {

    /**
     * 一次模拟插入的探测上限：只须大于任何真实空闲空间即可，所以远在 {@link Long#MAX_VALUE} 之下。
     *
     * <p>有些元件会把你请求的数量原样当作「接收了」回报，于是空闲空间读回来就是整个
     * {@code Long.MAX_VALUE}——而估算无法把那个值与「什么都没测到」区分开，那正是「报出可用并行数」与
     * 「报零」的差别。AE2 自家元件装了虚空升级时就是这样，插着它的缓冲元件会静静把并行路径关掉。</p>
     *
     * <p>低估探测值只会低估估计值，而那是安全的方向：被拒的交付只是退回批量路径，不影响正确性。</p>
     */
    private static final long CAPACITY_PROBE = Long.MAX_VALUE / 1024;

    private final CellBuffer buffer;
    private final long maxAdvertisedSlots;
    private IPatternDetails cachedPattern;
    private long cachedGameTime = -1;
    private long cachedSlots;

    /**
     * @param buffer             被估的元件缓冲
     * @param maxAdvertisedSlots 对外公布的上限（可由系统属性覆盖，见调用点），估计结果会被它夹住
     */
    public ParallelSlotProbe(CellBuffer buffer, long maxAdvertisedSlots) {
        this.buffer = buffer;
        this.maxAdvertisedSlots = Math.max(1L, maxAdvertisedSlots);
    }

    /** 丢弃记忆值：配方池或元件内容被换过之后，上一个答案不再作数。 */
    public void invalidate() {
        cachedPattern = null;
    }

    /**
     * 估算 {@code pattern} 还能被完整交付多少份。
     *
     * @param now 当前游戏时间，用于判断记忆值是否还在同一 tick 内
     * @return 可交付的份数；{@code null} 配方或无法估算时返回 0
     */
    public long estimate(IPatternDetails pattern, long now) {
        if (pattern == null) {
            return 0L;
        }
        if (pattern == cachedPattern && now == cachedGameTime) {
            return cachedSlots;
        }

        long tightestFree = Long.MAX_VALUE;
        long perCraftTotal = 0;
        var occupied = buffer.contents();
        long reservedUnits = 0;
        for (var input : pattern.getInputs()) {
            if (input == null) {
                continue;
            }
            long perCraft = 0;
            AEKey key = null;
            for (var possible : input.getPossibleInputs()) {
                if (possible != null && possible.what() != null && possible.amount() > 0) {
                    perCraft = possible.amount() * Math.max(1L, input.getMultiplier());
                    key = possible.what();
                    break;
                }
            }
            if (key == null || perCraft <= 0) {
                // A slot the pattern does not describe is nothing this machine could size.
                continue;
            }
            // A key the cells do not hold yet costs one type entry on top of its items, and every such key
            // costs its own: the insertion deducts bytesPerType * amountPerByte once per new key, so a batch
            // that introduces several of them asks for that much more room than the item totals alone show.
            if (occupied.get(key) <= 0) {
                reservedUnits += buffer.typeEntryUnits(key);
            }
            // Saturating, because the sum only has to decide whether a hand-over is possible at all; a capped
            // estimate can overflow it on a large per-craft amount.
            perCraftTotal = perCraft >= Long.MAX_VALUE - perCraftTotal ? Long.MAX_VALUE : perCraftTotal + perCraft;
            long probe = perCraft <= CAPACITY_PROBE / maxAdvertisedSlots
                    ? perCraft * maxAdvertisedSlots
                    : CAPACITY_PROBE;
            long free = buffer.insert(key, probe, Actionable.SIMULATE);
            // The probe already saw the key as it is: for a key that is present it returns its item room, and
            // for a new key the first inserted item already paid the type entry. What the probe cannot know is
            // the other new keys, which is what the reservation above covers.
            tightestFree = Math.min(tightestFree, free);
        }
        long available = tightestFree == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, tightestFree - reservedUnits);
        long slots = perCraftTotal > 0 && available != Long.MAX_VALUE
                ? available / perCraftTotal
                : 0L;
        slots = Math.min(slots, maxAdvertisedSlots);

        cachedPattern = pattern;
        cachedGameTime = now;
        cachedSlots = Math.max(0L, slots);
        return cachedSlots;
    }
}

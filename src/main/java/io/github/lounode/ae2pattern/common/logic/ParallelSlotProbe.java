package io.github.lounode.ae2pattern.common.logic;

import java.util.HashMap;
import java.util.Map;

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
 * <p>估算是每 tick 一次的记忆值：ECO 每 tick 会对多个候选各问一次，重算几遍纯属浪费。记忆值按「配方 +
 * tick + 实际上限」三件套失效，所以 int 契约与 long 契约对同一配方各算一次——两者的上限不同，答案本来
 * 就不同，不能复用一个数。</p>
 *
 * <p><b>每个上限各留一槽。</b>单槽记忆在这里不是省一点算术，而是死循环的入口：ECO 同一次派发里交替问
 * {@code int} 契约（上限 {@link Integer#MAX_VALUE}）与 {@code long} 契约（上限 {@code maxAdvertisedSlots}），
 * 单槽会被两者交替顶掉，于是<b>每问一次都重算</b>——每条探测是一次 {@link CellBuffer#contents()} 加每槽
 * 一次模拟插入。并行度是上万次交接，主线程当场被打满。分槽后同一次派发里每档各算一次。</p>
 *
 * <p><b>元件变了就必须 {@link #invalidate()}。</b>记忆值的另一半前提是元件内容没动：同一 tick 里第一批
 * 交付会把元件塞满，而缓存还在答「还能装 N 份」，于是 ECO 交付 N 份、本机 all-or-nothing 地拒绝、ECO
 * 重试、缓存又答 N 份——同 tick 内转不出去。调用点见 {@code BatchAssemblerBlockEntity#bookArrival}。</p>
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

    /**
     * 记忆值，按「实际上限 + 配方」分槽。
     *
     * <p>两个维度都必须进键：上限不同则是两个不同的问题（{@code int} 契约与 {@code long} 契约各一格）；
     * 配方不同则它们在元件里争的是同一片空间，但各自算出的份数不同。单槽记忆在这两个维度上都会被交替顶掉，
     * 而每次未命中就是一次 {@link CellBuffer#contents()} 加每槽一次模拟插入——并行度是上万次交接，主线程
     * 当场被打满。组合数只有「两个上限 × 在场配方数」，不会长。</p>
     *
     * <p><b>只在服务端线程访问</b>：调用方全在服务端 tick / 派发路径（方块实体与它的 ECO/Omni 适配器），
     * 分析用的线程池不引用本类。</p>
     */
    private final Map<CacheKey, Estimate> estimates = new HashMap<>();

    /**
     * 分槽键：哪个上限档、哪条配方。
     *
     * <p>配方按<b>值</b>比较，不是实例相等：{@link IPatternDetails} 的实现重写了 {@code equals}/{@code hashCode}
     * （AE2 那几种都以样板定义里的 {@code AEItemKey} 为身份，而它的哈希是缓存字段）。这一点是刻意的：估算只
     * 取决于样板的输入，值相同的两份样板本来就该共用一格。代价是键的契约依赖对方实现——换样板类型时先确认
     * 它仍然稳定。
     */
    private record CacheKey(long ceiling, IPatternDetails pattern) {
    }

    /** 一格记忆值：哪个 tick、多少份。tick 对不上就作废。 */
    private record Estimate(long gameTime, long slots) {
    }

    /**
     * @param buffer             被估的元件缓冲
     * @param maxAdvertisedSlots 对外公布的上限（可由系统属性覆盖，见调用点），估计结果会被它夹住
     */
    public ParallelSlotProbe(CellBuffer buffer, long maxAdvertisedSlots) {
        this.buffer = buffer;
        this.maxAdvertisedSlots = Math.max(1L, maxAdvertisedSlots);
    }

    /**
     * 丢弃全部记忆值：配方池被重建、或元件内容被写过（真实插入、拒收之后的回滚）之后，旧答案不再作数。
     *
     * <p>两格一起清：虽然两条契约的上限不同、答案本来不同，但它们算的是同一片元件空间——元件一变，两个
     * 答案同时过期。</p>
     */
    public void invalidate() {
        estimates.clear();
    }

    /**
     * 本 tick 封住一条配方：先说 0，让调用方停手，下个 tick 再重新探测。
     *
     * <p>拒收之后不能只 {@link #invalidate()}。清缓存只是忘掉答案，而探测在元件没变时是确定性的——重算会
     * 给出<b>同一个过大的数</b>（多输入样板的模拟按单 key 算空闲，各自以为自己能独占整片空间），于是调用方
     * 拿这个数再交付、再被拒，同 tick 内转不出去。写一条零答案才是「这一档本 tick 别再问了」的正确表达。</p>
     *
     * <p>零答案只活一个 tick：{@link Estimate} 的命中要求 {@code gameTime == now}，下一 tick 空间真的够时会
     * 正常放行。</p>
     *
     * <p>封禁会被同 tick 内随后的一次 {@link #invalidate()} 冲掉（{@code bookArrival} 里那次），但那不构成
     * 回环：每次 {@code invalidate()} 都由「真的往元件里写成功了」的事件驱动，于是每个循环都伴随进展（元件
     * 被填），元件填满后不再有成功插入、也就再没人清表。最差情况是每次成功插入之后多一次失败的交付尝试。</p>
     *
     * <p>两条契约的上限不同，所以要按各自的上限各封一次（见调用点），否则另一条档位仍能拿到虚高的数。</p>
     */
    public void denyFor(IPatternDetails pattern, long now, long cap) {
        if (pattern == null) {
            return;
        }
        long ceiling = Math.min(maxAdvertisedSlots, Math.max(1L, cap));
        estimates.put(new CacheKey(ceiling, pattern), new Estimate(now, 0L));
    }

    /**
     * 估算 {@code pattern} 还能被完整交付多少份。
     *
     * @param now 当前游戏时间，用于判断记忆值是否还在同一 tick 内
     * @param cap 这一次询问能用到的上限：契约是 int 的调用方传 {@link Integer#MAX_VALUE}，long 的传
     *            {@link #maxAdvertisedSlots}。探测值不必大于对方能用到的数量——多出来的探测只会让元件的
     *            模拟插入变贵，而结果永远会被这个上限夹住。
     * @return 可交付的份数；{@code null} 配方或无法估算时返回 0
     */
    public long estimate(IPatternDetails pattern, long now, long cap) {
        if (pattern == null) {
            return 0L;
        }
        // 实际上限 = 配置上限与本次调用能力取小。它同时是探测值的乘数与最终夹子，也是记忆值的分槽键：
        // 同一 tick 里 int 与 long 两条契约问的是两个数，同一条配方各留一格，不能互相顶掉。
        long ceiling = Math.min(maxAdvertisedSlots, Math.max(1L, cap));
        var slotKey = new CacheKey(ceiling, pattern);
        var cached = estimates.get(slotKey);
        if (cached != null && cached.gameTime() == now) {
            return cached.slots();
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
            // The probe only has to prove that this many crafts fit; anything above the ceiling is room the
            // caller cannot use anyway, and asking for it makes the cell walk a bigger amount on every probe.
            // A multi-input pattern therefore lands somewhat below the ceiling: the sum below covers every
            // input, while each probe only covers one key's share. That is an underestimate, which is the safe
            // direction, and the single-input chain this machine is built for does not lose anything.
            long probe = perCraft <= CAPACITY_PROBE / ceiling
                    ? perCraft * ceiling
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
        slots = Math.min(slots, ceiling);

        long clamped = Math.max(0L, slots);
        estimates.put(slotKey, new Estimate(now, clamped));
        return clamped;
    }
}

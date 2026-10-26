package io.github.lounode.ae2pattern.common.logic;

/**
 * 发配倍增（接收侧合并交接）的额度。
 *
 * <p>样板磁盘供应器在用这一份判据，因为额度量的是同一件事：<b>这一批攒了多少份合成所需的输入</b>。玩家填 N 就是
 * 「最多攒 N 份的输入再一次写入」，0 表示不设限。</p>
 *
 * <p>单位是<b>份</b>（样板输入倍数），不是交接次数、也不是 tick。玩家的心智模型是「我最多攒 64 份的材料再一次
 * 写进去」，不是「攒 64 次交接」——后者与配方无关，看不出实际攒了多少东西。</p>
 *
 * <p>不设限（{@code configured == 0}）时不按份数封顶：落地完全交给调用方的停手判据（{@link #QUIET_TICKS} 个
 * tick 没有新交接）。这条路径没有上限，发送方如果一直不停手，批就一真攒着——这是有意的取舍：要上限就填数字。</p>
 *
 * <p>不可变：改额度用 {@link #withConfigured(int)} 换一个新实例，免得一处改了另一处还在读旧值。</p>
 */
public final class BoostMergeLimit {

    /** 输入栏可接受的最大值：24px 的输入框只放得下三位数。 */
    public static final int MAX_LIMIT = 999;

    /**
     * 发送方停手多久算这一批收完了。
     *
     * <p>它不归任何一台机器私有，因为判定的是同一个物理事件：没人再送货了。不能拿它去替机器的批窗口
     * （{@code batchIdleTicks()}，40/10 tick）——那是「开工前静默多久」的问题，两者串起来会让响应时间直接翻倍。</p>
     *
     * <p>10 的依据：发送方在一个发配任务内是连续推送的，间隔远小于一个批窗口；10 tick 没有任何新交接就足以
     * 认定停手。这个值不要超过机器的批窗口，否则「等静默」比「等开工」还久，出倒挂。</p>
     */
    public static final int QUIET_TICKS = 10;

    private final int configured;

    /**
     * @param configured 玩家设的份数额度；越界会被夹到 {@code [0, MAX_LIMIT]}。0 表示不设限。
     */
    public BoostMergeLimit(int configured) {
        this.configured = Math.clamp(configured, 0, MAX_LIMIT);
    }

    /**
     * 攒到这么多份就该落地了。
     *
     * <p><b>参数是这一批自己</b>攒了多少份，不是全表求和：额度是这张样板自己攒了多少料，跨样板求和没有意义。</p>
     *
     * <p>没设上限时恒为 {@code false}——不按份数封顶，落地时机交给调用方的停手判据。</p>
     *
     * @param crafts 这一批已经攒下的合成份数（含刚收下的这一次），不是交接次数
     */
    public boolean isDue(long crafts) {
        return configured > 0 && crafts >= configured;
    }

    /** 玩家设的值，0 表示不设限。NBT 与界面同步都用这个。 */
    public int configured() {
        return configured;
    }

    public BoostMergeLimit withConfigured(int value) {
        return new BoostMergeLimit(value);
    }
}

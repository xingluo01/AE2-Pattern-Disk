package io.github.lounode.ae2pattern.common.logic;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import appeng.api.stacks.AEKey;

/**
 * 平滑回送队列：产物不一次砸进 ME 网络，而是把每个 key 的累计总量摊到若干个 tick 里交出去。
 *
 * <p><b>为什么要有它。</b>一批极大订单的产物若在一个 tick 内整批插入网络存储，那一次 IO 就是整个服务器的
 * 尖峰；反过来交付太慢又会拖住正等着这些产物的合成 CPU。折中是「每 tick 交总量的 1/N」——大单自然摊到 N
 * 个 tick，小单一个窗口内就交完——外加一条优先通道：网络（或本机还没有消费完的清单）此刻正在等这个 key
 * 时，一次多交一批，免得上游的容器回收被磨成一次一件。</p>
 *
 * <p><b>本类只记账，不碰网络。</b>投递动作与「网络在不在等」都交给 {@link Target}，于是「怎么交付」可以换
 * （方块实体投给 ME 存储、将来若接别的目标也不必改这里），而「每 tick 交多少」只有这一处定义。</p>
 *
 * <p><b>每个 key 记两个数。</b>待交付量，以及它进队列时的累计总量。额度按<b>总量</b>算而不是按剩余量：按
 * 剩余量算的话交付速度会随着交付一路衰减，一个大单永远走不完。</p>
 */
public final class SmoothReturnQueue {

    /** 投递目标：队列把额度交给它，由它决定去向。 */
    public interface Target {

        /**
         * 把 {@code amount} 交给网络。
         *
         * @return 网络实际接收的数量。网络满时可以是 0，未接收的部分留在队列里等下一 tick。
         */
        long insert(AEKey key, long amount);

        /** 网络（或本机待消费清单）此刻是否在等这个 key。 */
        boolean waitingFor(AEKey key);
    }

    private final Map<AEKey, long[]> pending = new LinkedHashMap<>();
    private final int returnTicks;
    private final long priorityBurst;

    /**
     * @param returnTicks  交付窗口：一个 key 的累计总量摊到这么多个 tick 里
     * @param priorityBurst 被等待的 key 一次可以多交的量
     */
    public SmoothReturnQueue(int returnTicks, long priorityBurst) {
        this.returnTicks = Math.max(1, returnTicks);
        this.priorityBurst = Math.max(1, priorityBurst);
    }

    /** 是否还有东西没交出去。 */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** 记入待交付量。{@code key} 为 null 或 {@code amount <= 0} 时什么都不做。 */
    public void add(AEKey key, long amount) {
        if (key == null || amount <= 0) {
            return;
        }
        var slot = pending.computeIfAbsent(key, k -> new long[2]);
        slot[0] += amount;
        slot[1] += amount;
    }

    /**
     * 交付一轮：每个 key 交出它额度的量，被等待的 key 一次多交 {@code priorityBurst}。
     *
     * @return 是否有 key 因为网络没收下而停滞——调用方据此把机器状态报成「产物回送受阻」
     */
    public boolean drain(Target target) {
        boolean stalled = false;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var slot = entry.getValue();
            long amount = quota(slot);
            if (target.waitingFor(entry.getKey())) {
                amount = Math.min(slot[0], Math.max(amount, priorityBurst));
            }
            long inserted = target.insert(entry.getKey(), amount);
            slot[0] -= inserted;
            if (inserted == 0) {
                stalled = true;
            }
            if (slot[0] <= 0) {
                iterator.remove();
            }
        }
        return stalled;
    }

    /**
     * 待交付量的只读快照（key → 剩余量），给那些要自行处置而不是交给网络的调用方遍历：清仓退回与方块
     * 破坏时的掉落都走它。边遍历边改队列会让处置与记账互相看见，所以这里给的是副本。
     */
    public Map<AEKey, Long> snapshot() {
        Map<AEKey, Long> copy = new LinkedHashMap<>(pending.size());
        for (var entry : pending.entrySet()) {
            copy.put(entry.getKey(), entry.getValue()[0]);
        }
        return copy;
    }

    /**
     * 修正某个 key 的待交付量：处置方把一部分改投别处之后，用它把队列里的数减下去。
     *
     * <p>丢掉的那个 key 不在队列里时什么都不做，所以处置方不必先查存在性。</p>
     */
    public void setRemaining(AEKey key, long amount) {
        var slot = pending.get(key);
        if (slot != null) {
            slot[0] = Math.max(0, amount);
        }
    }

    /** 丢掉所有已经交完的 key（处置方自己算的账，交完与否由它决定）。 */
    public void discardEmpty() {
        pending.values().removeIf(slot -> slot[0] <= 0);
    }

    /** 清空队列，不交付。 */
    public void clear() {
        pending.clear();
    }

    /**
     * 存盘。队列里的成品已经产出、也已经被合成 CPU 记账，丢了就是真丢——所以它进 NBT，且格式稳定：条目名
     * 是 {@code e0}/{@code e1}…，字段是 {@code key}/{@code remaining}/{@code total}。
     */
    public void writeToNbt(CompoundTag tag, HolderLookup.Provider registries, String name) {
        var queueTag = new CompoundTag();
        int index = 0;
        for (var entry : pending.entrySet()) {
            var entryTag = new CompoundTag();
            entryTag.put("key", entry.getKey().toTag(registries));
            entryTag.putLong("remaining", entry.getValue()[0]);
            entryTag.putLong("total", entry.getValue()[1]);
            queueTag.put("e" + index++, entryTag);
        }
        tag.put(name, queueTag);
    }

    /**
     * 读盘。条目按上面的格式解析；读不出 key 的条目（物品被移除/改名的存档）直接丢，而不是把一条投不出去
     * 的账挂在那里。{@code total} 至少取到 {@code remaining}，否则额度会算成 0、那条账永远交不完。
     */
    public void readFromNbt(CompoundTag tag, HolderLookup.Provider registries, String name) {
        clear();
        var queueTag = tag.getCompound(name);
        for (var entryKey : queueTag.getAllKeys()) {
            var entryTag = queueTag.getCompound(entryKey);
            var key = AEKey.fromTagGeneric(registries, entryTag.getCompound("key"));
            if (key == null) {
                continue;
            }
            long remaining = Math.max(0, entryTag.getLong("remaining"));
            long total = Math.max(remaining, entryTag.getLong("total"));
            if (remaining > 0) {
                pending.put(key, new long[] { remaining, total });
            }
        }
    }

    /** 一轮交付的额度：按累计总量算（不按剩余量，理由见类注释），向上取整并至少为 1。 */
    private long quota(long[] slot) {
        long rate = Math.max(1, (slot[1] + returnTicks - 1) / returnTicks);
        return Math.min(slot[0], rate);
    }
}

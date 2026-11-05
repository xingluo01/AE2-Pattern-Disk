package io.github.lounode.ae2pattern.common.block.entity;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import appeng.api.networking.IManagedGridNode;

/**
 * 批处理装配室给三个协作类的宿主服务快照：把方块实体的几项平台能力收成五个回调，协作类只拿这份窄接口，
 * 不持有 {@code BatchAssemblerBlockEntity} 本身。
 *
 * <p>网格与游戏时间都按调用时刻取（{@code Supplier} / {@code LongSupplier}）：节点晚于构造器建立，
 * level 也可能为空，缓存一份实例值会在读档 / 接通网络那一刻用到旧对象。这与同包
 * {@code PatternDiskAssemblerBlockEntity.ProductDelivery} 收 {@code Supplier<IGrid>} 是同一个口径。</p>
 *
 * <p>五个方法各自对应方块实体上确实存在、且协作类绕不开的一项服务：主节点（网格 / 在线态 / ticker 提醒 /
 * 合成更新）、游戏时间、落盘、能否收料、重建配方池。没有把它做成一大堆 getter，是为了不让协作类反过来
 * 摸遍方块实体的私有字段。</p>
 */
final class BatchHost {

    private final Supplier<IManagedGridNode> mainNode;
    private final LongSupplier gameTime;
    private final Runnable save;
    private final BooleanSupplier acceptsPlans;
    private final Runnable rebuild;

    BatchHost(Supplier<IManagedGridNode> mainNode, LongSupplier gameTime, Runnable save,
            BooleanSupplier acceptsPlans, Runnable rebuild) {
        this.mainNode = mainNode;
        this.gameTime = gameTime;
        this.save = save;
        this.acceptsPlans = acceptsPlans;
        this.rebuild = rebuild;
    }

    /** 主节点：网格、在线态、ticker 提醒与合成更新都从它取。 */
    IManagedGridNode gridNodeManager() {
        return mainNode.get();
    }

    /** 当前世界游戏时间；实体尚未挂到世界时为 0。 */
    long gameTimeNow() {
        return gameTime.getAsLong();
    }

    /** 内容变化后落盘。 */
    void persistChanges() {
        save.run();
    }

    /** 元件缓冲是否还能吃下配方（{@code acceptsPlans()}）。 */
    boolean canAcceptPlans() {
        return acceptsPlans.getAsBoolean();
    }

    /** 重建配方池并重新向 CPU 暴露样板（{@code refreshRecipePool()}）。 */
    void rebuildRecipePool() {
        rebuild.run();
    }
}

package io.github.lounode.ae2pattern.common.logic;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.Actionable;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;

/**
 * 产物的回送：一件成品或余料该往哪去，以及送不掉时怎么记账。
 *
 * <p>去向由发配页自己定：被机器发配的那一页（有回送方向）先走相邻的返回节点，只有邻居拒收的部分才直接进
 * ME 存储；自发执行的那一页（手动样板、没有方向）则一律直接进网络——它刻意不碰相邻容器，旁边坐着什么都
 * 不属于这一页启动的作业，往那儿推会把成果带出网络。</p>
 *
 * <p><b>失败只报一次。</b>产物卡在机器里时每 tick 都会重试投递，无条件打日志会刷屏；诊断卡住的机器要知道的
 * 是「原因本身」，所以同一原因只记一条，送出去之后（或换成别的原因）才重置。网络拒收的半成品与整单被拒
 * 合并为同一条，因为成因相同，按措辞分档只会在两者之间来回刷日志。</p>
 */
public final class ProductDelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.assembler.delivery");

    private final Supplier<IGrid> grid;
    private final Supplier<Level> level;
    private final BlockPos position;
    private final IActionSource actionSource;
    private final Runnable onChanged;

    /** 上一次报过的失败原因；同一原因只报一次。 */
    private String lastFailureKey;

    /**
     * @param grid       调用时刻的网格（可能为空：机器没接上网络）
     * @param level      调用时刻的世界
     * @param position   机器位置：定位相邻容器（回送邻居）与日志定位
     * @param onChanged  真的送出过东西时通知调用方存档
     */
    public ProductDelivery(Supplier<IGrid> grid, Supplier<Level> level, BlockPos position,
            IActionSource actionSource, Runnable onChanged) {
        this.grid = grid;
        this.level = level;
        this.position = position;
        this.actionSource = actionSource;
        this.onChanged = onChanged;
    }

    /**
     * 投递一件产物。
     *
     * @param adjacentSide 发配页的回送方向；{@code null} 表示这一页不回送相邻容器
     * @return 没送出去的部分，调用方留在原位等下一 tick 重试
     */
    public ItemStack deliver(ItemStack stack, @Nullable Direction adjacentSide) {
        var world = level.get();
        if (world == null || world.isClientSide() || stack.isEmpty()) {
            return stack;
        }
        if (adjacentSide == null) {
            return insertIntoNetwork(stack);
        }
        stack = pushToAdjacent(stack, adjacentSide, world);
        return stack.isEmpty() ? stack : insertIntoNetwork(stack);
    }

    /**
     * Inserts as much of the stack as the ME network accepts and returns the rest, which callers leave
     * in place to retry on the next tick.
     */
    private ItemStack insertIntoNetwork(ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        var connected = grid.get();
        if (connected == null) {
            report("no-grid", "the machine is not connected to an ME network");
            return stack;
        }
        var storage = connected.getStorageService();
        if (storage == null) {
            report("no-storage", "the ME network has no storage service");
            return stack;
        }
        var key = AEItemKey.of(stack);
        if (key == null) {
            report("no-key", "the product cannot be turned into an AE key");
            return stack;
        }
        var inserted = storage.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, actionSource);
        if (inserted > 0) {
            stack.shrink((int) inserted);
            onChanged.run();
        }
        if (stack.isEmpty()) {
            lastFailureKey = null;
        } else {
            // One key for both variants: whether the network took part of the stack or none of it, the
            // cause is the same, and keying on the wording would re-log on every flip between them.
            report("network-refused", "the ME network took "
                    + (inserted > 0 ? "only part of the stack" : "none of the stack")
                    + " - it has no free storage space, or it does not accept this item type");
        }
        return stack;
    }

    private ItemStack pushToAdjacent(ItemStack output, Direction direction, Level world) {
        if (output.isEmpty()) {
            return output;
        }
        var adaptor = InternalInventory.wrapExternal(world, position.relative(direction), direction.getOpposite());
        if (adaptor == null) {
            return output;
        }
        int size = output.getCount();
        output = adaptor.addItems(output);
        int newSize = output.isEmpty() ? 0 : output.getCount();
        if (size != newSize) {
            onChanged.run();
        }
        return output;
    }

    /**
     * Reports why a finished product was not accepted by the ME network, once per distinct cause. The
     * delivery is retried every tick while products are stuck in the machine, so an unconditional log
     * line would flood the log; what matters when diagnosing a stuck machine is the cause itself.
     */
    private void report(String key, String detail) {
        if (!key.equals(lastFailureKey)) {
            lastFailureKey = key;
            LOGGER.info("Efficient molecular assembler at {} cannot return products to the ME network: {}",
                    position, detail);
        }
    }
}

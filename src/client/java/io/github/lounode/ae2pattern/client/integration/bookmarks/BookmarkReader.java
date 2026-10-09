package io.github.lounode.ae2pattern.client.integration.bookmarks;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * 收藏夹读取的入口（纯客户端）：按在场的模组分派给 {@link EmiBookmarks} 或 {@link JeiBookmarks}。
 *
 * <p>本类自己<b>不</b>引用任何 EMI / JEI 的类型：两个来源各自一个类，缺席的那个连加载都不会发生。
 * 这一点在这个模组里是有先例的教训——JEI 版本一挪内部类就崩过（见 build.gradle 里那几段），
 * 所以「不在场就绝不碰它的类」这条比省一个类更重要。</p>
 *
 * <p>两边都可能收藏非物品的东西（流体、标签、配方产物）：这些跳过并计数，交给调用方报给玩家——
 * 元件标记只认物品键，硬塞进去只会变成一格空的。</p>
 */
public final class BookmarkReader {

    private static volatile Boolean anyAvailable;

    private BookmarkReader() {}

    /**
     * 读到的收藏。
     *
     * @param items    物品（按收藏夹顺序去重、数量都归一成 1）
     * @param nonItems 跳过的非物品收藏项数
     */
    public record Result(List<ItemStack> items, int nonItems) {

        public static final Result EMPTY = new Result(List.of(), 0);
    }

    /** 至少有一个收藏夹模组在场，这个按钮才有意义。每帧都会问，所以缓存一下。 */
    public static boolean available() {
        var cached = anyAvailable;
        if (cached == null) {
            cached = ModList.get().isLoaded("emi") || ModList.get().isLoaded("jei");
            anyAvailable = cached;
        }
        return cached;
    }

    /** 两个都装时以 EMI 为准：它们各自的收藏夹本来也不互相同步，取一个就好。 */
    public static Result read() {
        try {
            if (ModList.get().isLoaded("emi")) {
                return EmiBookmarks.read();
            }
            if (ModList.get().isLoaded("jei")) {
                return JeiBookmarks.read();
            }
        } catch (Throwable unexpected) {
            // 兜底：上游内部结构变了也别把「点按钮」这件事炸掉（各自内部也已兜过一次）。
            return Result.EMPTY;
        }
        return Result.EMPTY;
    }

    /** 数量归一成 1、按「同物品同组件」去重：标记只关心是什么东西。 */
    static void add(List<ItemStack> items, ItemStack stack) {
        var single = stack.copyWithCount(1);
        for (var existing : items) {
            if (ItemStack.isSameItemSameComponents(existing, single)) {
                return;
            }
        }
        items.add(single);
    }
}

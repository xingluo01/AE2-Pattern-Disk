package io.github.lounode.ae2pattern.client.integration.bookmarks;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

import dev.emi.emi.runtime.EmiFavorites;

/**
 * EMI 收藏夹的读取。
 *
 * <p>单独一个类是有意的：EMI 不在场时，本类不该被加载，{@link BookmarkReader} 的字节码里也就不该出现
 * EMI 的类型（它的分支由 modId 把关，见 {@link BookmarkReader#read()}）。</p>
 *
 * <p>{@code EmiFavorites.favorites} 是用户自己收藏的那一列（公开静态列表）；{@code syntheticFavorites}
 * 是配方推出来的，不算收藏，不读。</p>
 */
final class EmiBookmarks {

    private EmiBookmarks() {}

    static BookmarkReader.Result read() {
        List<ItemStack> items = new ArrayList<>();
        int nonItems = 0;
        for (var favorite : EmiFavorites.favorites) {
            // 一个收藏项可能是标签（一标签展开成许多栈）：取第一个能拿到的物品，标记只记「是什么东西」。
            ItemStack first = ItemStack.EMPTY;
            for (var stack : favorite.getStack().getEmiStacks()) {
                first = stack.getItemStack();
                if (!first.isEmpty()) {
                    break;
                }
            }
            if (first.isEmpty()) {
                nonItems++;
            } else {
                BookmarkReader.add(items, first);
            }
        }
        return new BookmarkReader.Result(items, nonItems);
    }
}

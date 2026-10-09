package io.github.lounode.ae2pattern.client.integration.bookmarks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;

import io.github.lounode.ae2pattern.client.integration.JeiMarkNames;

/**
 * JEI 收藏夹的读取。
 *
 * <p>JEI 那边没有「列出全部收藏」的公开 API：{@code IBookmarkOverlay} 只给「鼠标下那一枚」，全量列表是
 * 实现类 {@code BookmarkOverlay} 的私有字段 {@code bookmarkList}。本模组的 JEI 兼容史里已经踩过
 * 「JEI 挪内部类」的坑（见 build.gradle 里那几段），所以内部那两样——字段与元素接口——全程反射、
 * 一次都不写进编译面；读不到就当没有收藏，不抛、不崩。</p>
 *
 * <p>单独一个类是有意的：JEI 不在场时本类不该被加载（分支由 modId 把关，见 {@link BookmarkReader#read()}）。</p>
 */
final class JeiBookmarks {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.client.bookmarks");

    private static final String BOOKMARK_LIST_FIELD = "bookmarkList";
    private static final String ELEMENT = "mezz.jei.gui.overlay.elements.IElement";

    private JeiBookmarks() {}

    static BookmarkReader.Result read() {
        var runtime = JeiMarkNames.runtime();
        if (runtime == null) {
            return BookmarkReader.Result.EMPTY;
        }
        try {
            Object bookmarkList = field(runtime.getBookmarkOverlay(), BOOKMARK_LIST_FIELD);
            if (bookmarkList == null) {
                return BookmarkReader.Result.EMPTY;
            }
            if (!(bookmarkList.getClass().getMethod("getElements").invoke(bookmarkList) instanceof List<?> elements)) {
                return BookmarkReader.Result.EMPTY;
            }
            // 元素接口本身也按类名拿：它的实现类是包私有的，直接反射到实现类会拿不到访问权。
            Method typedIngredient = Class.forName(ELEMENT).getMethod("getTypedIngredient");

            List<ItemStack> items = new ArrayList<>();
            int nonItems = 0;
            for (Object element : elements) {
                if (typedIngredient.invoke(element) instanceof ITypedIngredient<?> ingredient
                        && ingredient.getType() == VanillaTypes.ITEM_STACK
                        && ingredient.getIngredient() instanceof ItemStack stack && !stack.isEmpty()) {
                    BookmarkReader.add(items, stack);
                } else {
                    nonItems++;
                }
            }
            return new BookmarkReader.Result(items, nonItems);
        } catch (Throwable unavailable) {
            // 上游内部结构变了：收藏读不出来是小事，别把点按钮这件事炸掉。
            LOGGER.debug("[bookmarks] reading JEI bookmarks failed", unavailable);
            return BookmarkReader.Result.EMPTY;
        }
    }

    /** 按名字找字段，自己往上找父类——JEI 若给面板套了一层子类也照样认得出。 */
    @Nullable
    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) {
                // 继续往上找
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                LOGGER.debug("[bookmarks] field {} unavailable", name, unavailable);
                return null;
            }
        }
        return null;
    }
}

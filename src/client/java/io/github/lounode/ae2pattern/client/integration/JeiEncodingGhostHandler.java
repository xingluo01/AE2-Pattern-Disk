package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.core.localization.ItemModText;
import appeng.menu.slot.FakeSlot;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * JEI 的幽灵物品处理器：把 JEI 里的物品送进样板编辑区。两个入口，对应 JEI 的两套机制：
 *
 * <ul>
 *   <li><b>拖拽</b>（{@link #getTargetsTyped}）：每个活动的 {@code FakeSlot} 各是一个目标，把 JEI 里的物品
 *       拖上去就设成那一格的内容——与 AE2 自带 EMI 集成给 AE2 屏幕的通用拖放同一套做法（它也只认活动的
 *       {@code FakeSlot}），所以两边手感一致。</li>
 *   <li><b>Shift+点击</b>（{@link #quickMove}，JEI 的 {@code GhostIngredientQuickMoveManager} 调它）：
 *       取该物品的**第一条配方**整页填进编辑区，省掉「先看配方、再点 +」。</li>
 * </ul>
 *
 * <p>配方来源是 JEI 自己的配方树（先找「产物是它」的配方，找不到再退回「拿它当原料」的），顺序按 JEI 的类别
 * 顺序，所以结果与玩家在 JEI 里看到的第一条一致。填好之后不自动编码写盘：与「+」按钮一样只填编辑区，是否写盘
 * 由玩家按「编写样板」决定。</p>
 */
public class JeiEncodingGhostHandler implements IGhostIngredientHandler<PatternDiskEncodingTermScreen> {

    /** 合成格只有 3x3；更大的合成类配方装不下就不填（与「+」按钮同一道判断，只是这里不弹提示）。 */
    private static final int CRAFTING_GRID_SIZE = 3;

    /** JEI 的运行时对象，由 {@link PatternDiskJeiPlugin#onRuntimeAvailable} 在配方树重建时更新。 */
    @Nullable
    private static IJeiRuntime runtime;

    public static void setRuntime(@Nullable IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public <I> List<Target<I>> getTargetsTyped(PatternDiskEncodingTermScreen screen,
            ITypedIngredient<I> ingredient, boolean doStart) {
        // 只认得出来的物品/流体：转换不了的东西（能量之类）不给目标，免得拖上去没反应还高亮一片。
        if (toGenericStack(ingredient.getIngredient()) == null) {
            return List.of();
        }

        var targets = new ArrayList<Target<I>>();
        for (var slot : screen.getMenu().slots) {
            // 只看活动槽：当前编码模式不在用的那些格既画不出来，也不该接东西。
            if (slot.isActive() && slot instanceof FakeSlot fakeSlot) {
                targets.add(new SlotTarget<>(screen, fakeSlot));
            }
        }
        return targets;
    }

    @Override
    public void onComplete() {
        // 每次放入都各自发包收尾，这里没有需要清理的状态。
    }

    @Override
    public <I> boolean quickMove(PatternDiskEncodingTermScreen screen, ITypedIngredient<I> ingredient) {
        var menu = screen.getMenu();
        if (!(menu instanceof PatternDiskEncodingTermMenu diskMenu) || runtime == null) {
            return false;
        }
        if (toGenericStack(ingredient.getIngredient()) == null) {
            return false;
        }
        return fillFirstRecipe(diskMenu, ingredient);
    }

    /** 按 JEI 的类别顺序取该物品的第一条配方并整页填进去；填到了返回 {@code true}。 */
    private static boolean fillFirstRecipe(PatternDiskEncodingTermMenu menu, ITypedIngredient<?> ingredient) {
        var jei = runtime;
        if (jei == null) {
            return false;
        }

        var focusFactory = jei.getJeiHelpers().getFocusFactory();
        var manager = jei.getRecipeManager();

        // 先「产物是它」，再退回「拿它当原料」：玩家按下 Shift+点击想要的通常是「怎么做出来」。
        for (var role : List.of(RecipeIngredientRole.OUTPUT, RecipeIngredientRole.INPUT)) {
            IFocus<?> focus = focusFactory.createFocus(role, ingredient);
            var focuses = List.<IFocus<?>>of(focus);
            var focusGroup = focusFactory.createFocusGroup(focuses);

            var categories = manager.createRecipeCategoryLookup().limitFocus(focuses).get().toList();
            for (var category : categories) {
                if (tryFillCategory(menu, category, focuses, focusGroup)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 取这一类别里命中焦点的第一条配方；取到就填进编辑区，填成了返回 {@code true}。 */
    private static <R> boolean tryFillCategory(PatternDiskEncodingTermMenu menu, IRecipeCategory<R> category,
            List<IFocus<?>> focuses, IFocusGroup focusGroup) {
        var jei = runtime;
        if (jei == null) {
            return false;
        }
        var recipe = jei.getRecipeManager().createRecipeLookup(category.getRecipeType())
                .limitFocus(focuses)
                .get()
                .findFirst()
                .orElse(null);
        return recipe != null && fillFromRecipe(menu, category, recipe, focusGroup);
    }

    private static <R> boolean fillFromRecipe(PatternDiskEncodingTermMenu menu, IRecipeCategory<R> category,
            R recipe, IFocusGroup focusGroup) {
        var jei = runtime;
        if (jei == null) {
            return false;
        }

        // 槽位视图要从布局里取：与「+」按钮拿到的是同一种东西，下面的收集逻辑因此可以原样复用。
        var slotsView = jei.getRecipeManager().createRecipeLayoutDrawable(category, recipe, focusGroup)
                .map(drawable -> drawable.getRecipeSlotsView())
                .orElse(null);
        if (slotsView == null) {
            return false;
        }

        var holder = recipe instanceof RecipeHolder<?> h ? h : null;
        var value = holder == null ? null : holder.value();
        if (DiskEncodingHelper.isSupportedCraftingRecipe(value)) {
            if (value != null && !value.canCraftInDimensions(CRAFTING_GRID_SIZE, CRAFTING_GRID_SIZE)) {
                // 比 3x3 还大的合成类配方（只有外部模组会给出）填不下：与「+」按钮报同一句话，
                // 而不是默默什么都不做——否则玩家会以为是 Shift+点击没生效。
                notifyPlayer(ItemModText.RECIPE_TOO_LARGE.text());
                return false;
            }
            DiskEncodingHelper.encodeCraftingRecipe(menu, holder, List.of(), stack -> true);
        } else {
            DiskEncodingHelper.encodeProcessingRecipe(menu,
                    JeiDiskEncodeRecipeHandler.collectInputs(slotsView),
                    JeiDiskEncodeRecipeHandler.collectOutputs(slotsView));
        }

        // 与「+」按钮同一顺序：编码会先 setMode，手动换模式会丢弃旧类别，所以类别最后写。
        var categoryId = category.getRecipeType().getUid().toString();
        menu.setPendingRecipeCategory(categoryId);
        menu.noteCategoryImported(categoryId);
        return true;
    }

    /** 一句动作栏提示（客户端主玩家）。找不到玩家（例如界面已关）就什么都不做。 */
    private static void notifyPlayer(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    /**
     * 一个格子的拖放目标。落在格子矩形里就调 {@link FakeSlot#setFilterTo}——它自己会把动作包发给服务端，
     * 所以这里不用再拼包（AE2 给 EMI 用的那条拖放路径也是这么写的）。
     */
    private record SlotTarget<I>(PatternDiskEncodingTermScreen screen, FakeSlot slot) implements Target<I> {

        @Override
        public Rect2i getArea() {
            return new Rect2i(screen.getGuiLeft() + slot.x, screen.getGuiTop() + slot.y, 16, 16);
        }

        @Override
        public void accept(I ingredient) {
            var stack = toGenericStack(ingredient);
            if (stack == null) {
                return;
            }
            var filter = wrapFilterAsItem(stack);
            if (!slot.canSetFilterTo(filter)) {
                return; // 这一格不收这种东西：与 JEI 里拖到别的格子上一样，什么都不做
            }
            slot.setFilterTo(filter);
        }
    }

    /**
     * 把 JEI 的物品/流体转成 AE2 的栈；不认识的类型返回 {@code null}。只认这两类是因为本模组的编码槽也就只收
     * 这两类，别的转不出来也没处放。
     */
    private static @Nullable GenericStack toGenericStack(@Nullable Object ingredient) {
        if (ingredient instanceof ItemStack itemStack && !itemStack.isEmpty()) {
            return GenericStack.fromItemStack(itemStack);
        }
        if (ingredient instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            return GenericStack.fromFluidStack(fluidStack);
        }
        return null;
    }

    /** 编码槽内部用一个被包成 ItemStack 的 GenericStack 表示非物品，这里与 {@code FakeSlot} 的取值口径对齐。 */
    private static ItemStack wrapFilterAsItem(GenericStack stack) {
        var amount = Math.max(1, stack.amount());
        if (stack.what() instanceof AEItemKey itemKey) {
            return itemKey.toStack(amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount);
        }
        return GenericStack.wrapInItemStack(stack.what(), amount);
    }
}

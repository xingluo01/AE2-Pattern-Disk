package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;

import appeng.api.stacks.GenericStack;
import appeng.core.localization.ItemModText;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessEncodingTermMenu;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessManagementTermMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;

/**
 * JEI counterpart of {@link DiskEncodePatternHandler}: fills the pattern disk encoding terminal's encoding
 * grid from the recipe page the player has open (JEI's "+" button).
 *
 * <p>Registered as a universal handler, so it answers for every recipe category JEI shows while the
 * terminal is open - which is how AE2 wires its own encoding terminal and how the EMI handler here works.
 * Crafting-family recipes are encoded from the real recipe object (the encoding helper re-derives the 3x3
 * grid itself, including the stonecutting recipe id); anything else becomes a processing pattern built
 * from the recipe's ingredient slots. Ingredients beyond items and fluids (chemicals and other custom
 * {@code AEKeyType}s) come from AE2's JEI bridge through {@link JeiIngredientConverters}.</p>
 *
 * <p>The container class is a constructor argument because JEI keys its transfer table by the container's
 * <b>runtime</b> class and never walks up to a superclass: one instance has to be registered per concrete
 * class {@link PatternDiskEncodingTermMenu#concreteMenuClasses()} reports, or the recipe's transfer button
 * silently disappears for that environment.</p>
 */
public class JeiDiskEncodeRecipeHandler implements IUniversalRecipeTransferHandler<PatternDiskEncodingTermMenu> {

    /** The encoding grid is 3x3, so a recipe that needs a larger grid cannot be encoded here. */
    private static final int CRAFTING_GRID_SIZE = 3;

    private final IRecipeTransferHandlerHelper helper;

    /** 本实例登记在 JEI 表里的容器类键，必须是具体类（子类由父类工厂按 EAE+ 契约在场与否产生）。 */
    private final Class<? extends PatternDiskEncodingTermMenu> containerClass;

    public JeiDiskEncodeRecipeHandler(IRecipeTransferHandlerHelper helper,
            Class<? extends PatternDiskEncodingTermMenu> containerClass) {
        this.helper = helper;
        this.containerClass = containerClass;
    }

    @Override
    public Class<? extends PatternDiskEncodingTermMenu> getContainerClass() {
        return containerClass;
    }

    @Override
    public Optional<MenuType<PatternDiskEncodingTermMenu>> getMenuType() {
        // 顺序要紧：无线菜单继承面板菜单，isAssignableFrom 对父子双向成立，先判更具体的那个。
        if (PatternDiskWirelessManagementTermMenu.class.isAssignableFrom(containerClass)) {
            return Optional.of((MenuType) PatternDiskWirelessManagementTermMenu.TYPE);
        }
        if (PatternDiskManagementTermMenu.class.isAssignableFrom(containerClass)) {
            return Optional.of((MenuType) AEPatternRegistries.MENU_PATTERN_DISK_MANAGEMENT_TERMINAL.get());
        }
        if (PatternDiskWirelessEncodingTermMenu.class.isAssignableFrom(containerClass)) {
            return Optional.of((MenuType) PatternDiskWirelessEncodingTermMenu.TYPE);
        }
        return Optional.of((MenuType) AEPatternRegistries.MENU_PATTERN_DISK_ENCODING_TERMINAL.get());
    }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(PatternDiskEncodingTermMenu menu, Object recipeBase,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {

        // 1.21 hands recipe categories their recipes wrapped in a RecipeHolder; anything else is not a recipe
        // this mod can encode as crafting and falls back to the processing path below.
        var holder = recipeBase instanceof RecipeHolder<?> h ? h : null;
        var recipe = holder != null ? holder.value() : null;

        boolean craftingRecipe = DiskEncodingHelper.isSupportedCraftingRecipe(recipe);
        if (craftingRecipe && !fitsIn3x3Grid(recipe)) {
            return helper.createUserErrorWithTooltip(ItemModText.RECIPE_TOO_LARGE.text());
        }

        // 导入前先把档位调到这份配方对应的那一档（映射表见 docs/ENCODING_MODES.md）：合成→合成、切石→切石、
        // 锻造→锻造、雕凿→雕凿、其余加工→处理；高级/过载两个额外档先关掉（导入不写它们的面/行表，
        // 留着只会让屏幕停在另一个面板上，看起来像「转移过去了」实际没动）。
        // 雕凿那条先试：它的输入与产物是一对，配不上则返回 false，按普通路径继续导。
        if (doTransfer) {
            var chiselingInputs = collectInputs(recipeSlots);
            var chiselingOutputs = collectOutputs(recipeSlots);
            var handledAsChiseling = DiskEncodingHelper.selectChiselingTierForImport(menu,
                    chiselingInputs.isEmpty() || chiselingInputs.get(0).isEmpty() ? null : chiselingInputs.get(0).get(0),
                    chiselingOutputs.isEmpty() ? null : chiselingOutputs.get(0));
            if (handledAsChiseling) {
                // 雕凿的导入已经做完了（档位、输入、候选都摆好了）：**必须在这里就结束**。
                // 继续往下不是「带掉」而是写坏——Rechiseled 的雕凿配方不是原版配方类型，会落到
                // encodeProcessingRecipe 去，而它内部会 setMode(PROCESSING) 并把隐藏的处理编辑区覆盖掉；
                // 若某条被认成 supported crafting，encodeCraftingRecipe 首行又会把刚设好的雕凿档关掉。
                var chiselingCategory = JeiTransferCategory.take();
                var chiselingCategoryId = chiselingCategory == null ? null : chiselingCategory.toString();
                menu.setPendingRecipeCategory(chiselingCategoryId);
                menu.noteCategoryImported(chiselingCategoryId);
                return null;
            }
            DiskEncodingHelper.selectTierForImport(menu, recipe);
        }
        // 探问阶段（doTransfer == false）不改任何状态，只回答「能不能转移」：那是 JEI 选中/渲染配方时
        // 反复来问的路径，在这上面改档会让「悬停一张合成配方」就把玩家从额外档踢出去，而且每次都发包。

        // doTransfer == false is JEI asking whether the transfer would work; the answer here is "yes" as long
        // as the checks above passed, so the button stays enabled.
        if (doTransfer) {
            if (craftingRecipe) {
                // The crafting path rebuilds the grid from the recipe itself, so the slot view is unused.
                DiskEncodingHelper.encodeCraftingRecipe(menu, holder, List.of(), stack -> true);
            } else {
                DiskEncodingHelper.encodeProcessingRecipe(menu, collectInputs(recipeSlots), collectOutputs(recipeSlots));
            }

            // 与 EMI 侧同一顺序：编码会先 setMode，手动换模式会丢弃旧类别，所以类别最后写。
            var category = JeiTransferCategory.take();
            var categoryId = category == null ? null : category.toString();
            menu.setPendingRecipeCategory(categoryId);
            // 记一笔「导入过」并留下这次导入的类别：搜索栏只认这个入口填自己（见 noteCategoryImported）。
            menu.noteCategoryImported(categoryId);
        }
        return null;
    }

    private static boolean fitsIn3x3Grid(@Nullable Recipe<?> recipe) {
        return recipe == null || recipe.canCraftInDimensions(CRAFTING_GRID_SIZE, CRAFTING_GRID_SIZE);
    }

    /** One entry per input slot, each holding every variant JEI offers for that slot. */
    static List<List<GenericStack>> collectInputs(IRecipeSlotsView recipeSlots) {
        var inputs = new ArrayList<List<GenericStack>>();
        for (var slotView : recipeSlots.getSlotViews()) {
            if (slotView.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            var variants = new ArrayList<GenericStack>();
            for (var typed : slotView.getAllIngredients().toList()) {
                var stack = JeiIngredientConverters.toGenericStack(typed);
                if (stack != null) {
                    variants.add(stack);
                }
            }
            if (!variants.isEmpty()) {
                inputs.add(variants);
            }
        }
        return inputs;
    }

    /** One entry per output slot: the encoder expects a single candidate per output. */
    static List<GenericStack> collectOutputs(IRecipeSlotsView recipeSlots) {
        var outputs = new ArrayList<GenericStack>();
        for (var slotView : recipeSlots.getSlotViews()) {
            if (slotView.getRole() != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            for (var typed : slotView.getAllIngredients().toList()) {
                var stack = JeiIngredientConverters.toGenericStack(typed);
                if (stack != null) {
                    outputs.add(stack);
                    break;
                }
            }
        }
        return outputs;
    }
}

package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.world.item.crafting.RecipeHolder;

import dev.emi.emi.api.recipe.EmiRecipe;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.localization.ItemModText;
import appeng.integration.modules.emi.EmiStackHelper;
import appeng.menu.me.common.GridInventoryEntry;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * 处理样板磁盘编码终端中通过 EMI 配方页的 + 按钮导入配方（编码到网格）。
 * 移植自 AE2 {@code EmiEncodePatternHandler}，绑定 {@link PatternDiskEncodingTermMenu}。
 */
public class DiskEncodePatternHandler extends AbstractDiskRecipeHandler<PatternDiskEncodingTermMenu> {

    public DiskEncodePatternHandler() {
        super(PatternDiskEncodingTermMenu.class);
    }

    @Override
    protected Result transferRecipe(PatternDiskEncodingTermMenu menu, RecipeHolder<?> holder, EmiRecipe emiRecipe,
            boolean doTransfer) {

        var recipeId = holder != null ? holder.id() : null;
        var recipe = holder != null ? holder.value() : null;

        // 合成配方槽不分组，必须能塞进 3x3 网格。
        boolean craftingRecipe = isCraftingRecipe(recipe, emiRecipe);
        if (craftingRecipe && !fitsIn3x3Grid(recipe, emiRecipe)) {
            return Result.createFailed(ItemModText.RECIPE_TOO_LARGE.text());
        }

        if (doTransfer) {
            if (craftingRecipe && recipeId != null) {
                DiskEncodingHelper.encodeCraftingRecipe(menu,
                        new RecipeHolder<>(recipeId, recipe),
                        getGuiIngredientsForCrafting(emiRecipe),
                        stack -> true);
            } else {
                DiskEncodingHelper.encodeProcessingRecipe(menu,
                        EmiStackHelper.ofInputs(emiRecipe),
                        EmiStackHelper.ofOutputs(emiRecipe));
            }
        } else {
            var repo = menu.getClientRepo();
            Set<AEKey> craftableKeys = repo != null ? repo.getAllEntries().stream()
                    .filter(GridInventoryEntry::isCraftable)
                    .map(GridInventoryEntry::getWhat)
                    .collect(Collectors.toSet()) : Set.of();

            return new Result.EncodeWithCraftables(craftableKeys);
        }

        return Result.createSuccessful();
    }

    /**
     * 若配方未报告输入，则使用 EMI GUI 上显示的输入代替。
     */
    private List<List<GenericStack>> getGuiIngredientsForCrafting(EmiRecipe emiRecipe) {
        var result = new ArrayList<List<GenericStack>>(CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT);
        for (int i = 0; i < CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT; i++) {
            var stacks = new ArrayList<GenericStack>();

            if (i < emiRecipe.getInputs().size()) {
                for (var emiStack : emiRecipe.getInputs().get(i).getEmiStacks()) {
                    var genericStack = EmiStackHelper.toGenericStack(emiStack);
                    if (genericStack != null && genericStack.what() instanceof AEItemKey) {
                        stacks.add(genericStack);
                    }
                }
            }

            result.add(stacks);
        }

        return result;
    }
}
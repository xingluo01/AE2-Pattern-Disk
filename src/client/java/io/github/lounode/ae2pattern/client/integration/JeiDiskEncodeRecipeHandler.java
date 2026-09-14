package io.github.lounode.ae2pattern.client.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.fluids.FluidStack;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;

import appeng.api.stacks.GenericStack;
import appeng.core.localization.ItemModText;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * JEI counterpart of {@link DiskEncodePatternHandler}: fills the pattern disk encoding terminal's encoding
 * grid from the recipe page the player has open (JEI's "+" button).
 *
 * <p>Registered as a universal handler, so it answers for every recipe category JEI shows while the
 * terminal is open - which is how AE2 wires its own encoding terminal and how the EMI handler here works.
 * Crafting-family recipes are encoded from the real recipe object (the encoding helper re-derives the 3x3
 * grid itself, including the stonecutting recipe id); anything else becomes a processing pattern built
 * from the recipe's ingredient slots.</p>
 */
public class JeiDiskEncodeRecipeHandler implements IUniversalRecipeTransferHandler<PatternDiskEncodingTermMenu> {

    /** The encoding grid is 3x3, so a recipe that needs a larger grid cannot be encoded here. */
    private static final int CRAFTING_GRID_SIZE = 3;

    private final IRecipeTransferHandlerHelper helper;

    public JeiDiskEncodeRecipeHandler(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
    }

    @Override
    public Class<? extends PatternDiskEncodingTermMenu> getContainerClass() {
        return PatternDiskEncodingTermMenu.class;
    }

    @Override
    public Optional<MenuType<PatternDiskEncodingTermMenu>> getMenuType() {
        return Optional.of(AEPatternRegistries.MENU_PATTERN_DISK_ENCODING_TERMINAL.get());
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

        // doTransfer == false is JEI asking whether the transfer would work; the answer here is "yes" as long
        // as the checks above passed, so the button stays enabled.
        if (doTransfer) {
            if (craftingRecipe) {
                // The crafting path rebuilds the grid from the recipe itself, so the slot view is unused.
                DiskEncodingHelper.encodeCraftingRecipe(menu, holder, List.of(), stack -> true);
            } else {
                DiskEncodingHelper.encodeProcessingRecipe(menu, collectInputs(recipeSlots), collectOutputs(recipeSlots));
            }
        }
        return null;
    }

    private static boolean fitsIn3x3Grid(@Nullable Recipe<?> recipe) {
        return recipe == null || recipe.canCraftInDimensions(CRAFTING_GRID_SIZE, CRAFTING_GRID_SIZE);
    }

    /** One entry per input slot, each holding every variant JEI offers for that slot. */
    private static List<List<GenericStack>> collectInputs(IRecipeSlotsView recipeSlots) {
        var inputs = new ArrayList<List<GenericStack>>();
        for (var slotView : recipeSlots.getSlotViews()) {
            if (slotView.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            var variants = new ArrayList<GenericStack>();
            for (var typed : slotView.getAllIngredients().toList()) {
                var stack = toGenericStack(typed.getIngredient());
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
    private static List<GenericStack> collectOutputs(IRecipeSlotsView recipeSlots) {
        var outputs = new ArrayList<GenericStack>();
        for (var slotView : recipeSlots.getSlotViews()) {
            if (slotView.getRole() != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            for (var typed : slotView.getAllIngredients().toList()) {
                var stack = toGenericStack(typed.getIngredient());
                if (stack != null) {
                    outputs.add(stack);
                    break;
                }
            }
        }
        return outputs;
    }

    /**
     * Converts a JEI ingredient into an AE2 stack. Only the ingredient types this mod can encode are
     * accepted; everything else (energy, custom ingredient types) is skipped rather than guessed at.
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
}

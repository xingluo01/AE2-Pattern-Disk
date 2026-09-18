package io.github.lounode.ae2pattern.client.integration;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;

/**
 * The part of the disk-mark tooltip that needs EMI: recipe category names and the machines they run on.
 * A category is where a mod's machine gets its localized name ("精密锯木机", "烟熏", ...), which is what a
 * mark should read as, and its workstation is the block that machine is ("烟熏炉").
 *
 * <p>EMI is a compile-time dependency only, so nothing outside this class may name its types - a client
 * without EMI would otherwise fail to load whatever touches them. Callers check that the mod is present
 * before coming here.</p>
 */
public final class EmiMarkNames {

    private EmiMarkNames() {
    }

    /** The display name of the recipe category {@code id} names, or {@code null} when there is none. */
    @Nullable
    public static Component find(String id) {
        var category = findCategory(id);
        return category == null ? null : category.getName();
    }

    /**
     * The name of the block that runs recipes of category {@code id}, such as "烟熏炉", or {@code null}
     * when there is no such category or it has no block to show.
     *
     * <p>EMI keeps the machine on the category's icon (the recipe itself only names it as a catalyst), which
     * for vanilla categories is the workstation block. Categories advertised by a fluid or a custom renderable
     * have nothing to take a name from.</p>
     */
    @Nullable
    public static String machineName(String id) {
        var category = findCategory(id);
        if (category == null) {
            return null;
        }
        if (category.icon instanceof EmiStack stack) {
            var itemStack = stack.getItemStack();
            if (!itemStack.isEmpty()) {
                return itemStack.getHoverName().getString();
            }
        }
        return null;
    }

    @Nullable
    private static EmiRecipeCategory findCategory(String id) {
        var parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return null;
        }
        for (var category : EmiApi.getRecipeManager().getCategories()) {
            if (category.getId().equals(parsed)) {
                return category;
            }
        }
        return null;
    }
}

package io.github.lounode.ae2pattern.client.integration;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import dev.emi.emi.api.EmiApi;

/**
 * The part of the disk-mark tooltip that needs EMI: recipe category names. A category is where a mod's
 * machine gets its localized name ("精密锯木机", "烟熏", ...), which is what a mark should read as.
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
        var parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return null;
        }
        for (var category : EmiApi.getRecipeManager().getCategories()) {
            if (category.getId().equals(parsed)) {
                return category.getName();
            }
        }
        return null;
    }
}

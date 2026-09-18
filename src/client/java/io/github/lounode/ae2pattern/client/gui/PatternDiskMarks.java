package io.github.lounode.ae2pattern.client.gui;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.integration.EmiMarkNames;

/**
 * Turns a disk's mark (the {@code DISK_PREFIX} component) into the line shown in its tooltip.
 *
 * <p>A mark is either an identifier or plain text. Identifiers are written when a recipe is bound to a
 * disk and look like {@code #<recipe category id>} or {@code #mode:<encoding mode>}; plain text is what
 * disks written by older builds carry, and is shown as it is.</p>
 *
 * <p>Recipe category names come from EMI, which is where the machine a recipe belongs to gets its
 * localized name ("精密锯木机", "烟熏", ...). None of that is reachable without EMI, so a missing or
 * broken EMI degrades to showing the identifier rather than failing the tooltip.</p>
 */
public final class PatternDiskMarks {

    private PatternDiskMarks() {
    }

    /** The mark to display for {@code stack}, or {@code null} when it carries none. */
    @Nullable
    public static Component displayName(ItemStack stack) {
        return displayName(stack.get(AEPatternRegistries.DISK_PREFIX.get()));
    }

    /** The readable form of a stored mark, or {@code null} when there is none. */
    @Nullable
    public static Component displayName(@Nullable String mark) {
        if (mark == null || mark.isEmpty()) {
            return null;
        }

        if (mark.startsWith(MODE_PREFIX)) {
            return Component.translatable("ae2_pattern_disk.mark.mode." + mark.substring(MODE_PREFIX.length()));
        }

        if (mark.startsWith(ID_PREFIX)) {
            var id = mark.substring(ID_PREFIX.length());
            var name = findCategoryName(id);
            return name != null ? name : Component.literal(id);
        }

        return Component.literal(mark);
    }

    private static final String MODE_PREFIX = "#mode:";
    private static final String ID_PREFIX = "#";

    /** EMI is optional, so its types stay behind this check (see {@link EmiMarkNames}). */
    @Nullable
    private static Component findCategoryName(String id) {
        if (!ModList.get().isLoaded("emi")) {
            return null;
        }
        try {
            return EmiMarkNames.find(id);
        } catch (Throwable ignored) {
            // An EMI that moved its API still leaves the identifier as a usable label.
            return null;
        }
    }
}

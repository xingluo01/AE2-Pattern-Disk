package io.github.lounode.ae2pattern.client.gui;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.neoforged.fml.ModList;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.client.integration.EmiMarkNames;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

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

    /** The mark exactly as stored, for the line shown under vanilla's advanced tooltips. */
    @Nullable
    public static String rawMark(ItemStack stack) {
        var mark = stack.get(AEPatternRegistries.DISK_PREFIX.get());
        return mark == null || mark.isEmpty() ? null : mark;
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
            // 早期版本把 EMI 的配方类别 id 当标记存，同一台机器因此有第二个说法（切石既有“切石”也有
            // “切石样板”）。能归到编码模式的就按模式那套显示，让新老标记的显示名、搜索词、改名结果一致。
            var mode = PatternDiskEncodingTermMenu.modeForCategory(id);
            if (mode != null) {
                return Component.translatable("ae2_pattern_disk.mark.mode." + mode.name().toLowerCase(Locale.ROOT));
            }
            var name = findCategoryName(id);
            return name != null ? name : Component.literal(id);
        }

        return Component.literal(mark);
    }

    private static final String MODE_PREFIX = "#mode:";
    private static final String ID_PREFIX = "#";

    /**
     * The name to give a disk carrying {@code mark} when renaming it after its machine ("烟熏炉"). Marks
     * that stand for an encoding mode, and categories whose machine is unknown, fall back to the mark's own
     * readable name - the disk still ends up with a meaningful name rather than none. Without EMI a category
     * mark has no readable name at all, so what comes back is the identifier itself.
     */
    @Nullable
    public static String machineName(@Nullable String mark) {
        var label = displayName(mark);
        if (label == null || mark == null) {
            return null;
        }
        if (mark.startsWith(ID_PREFIX) && !mark.startsWith(MODE_PREFIX) && ModList.get().isLoaded("emi")) {
            try {
                var machine = EmiMarkNames.machineName(mark.substring(ID_PREFIX.length()));
                if (machine != null && !machine.isEmpty()) {
                    return machine;
                }
            } catch (Throwable ignored) {
                // Fall through to the readable name.
            }
        }
        return label.getString();
    }

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

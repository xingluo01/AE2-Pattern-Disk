package io.github.lounode.ae2pattern.client.gui;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.neoforged.fml.ModList;

import appeng.parts.encoding.EncodingMode;

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
            var modeName = mark.substring(MODE_PREFIX.length());
            if (modeName.isEmpty()) {
                // 只有一个 "#mode:"，没有模式名：显示原文，否则 tooltip 里会多出一个空行。
                return Component.literal(mark);
            }
            var mode = parseMode(modeName);
            if (mode == null) {
                // 认不出是哪个模式（例如玩家自己写下的 #mode:xyz）：显示原文，别丢一串未翻译的键名。
                return Component.literal(modeName);
            }
            // 手动编码写下的盘用的是模式标记，而导入过配方的盘用配方类别。把模式归一到它对应的规范
            // 类别名，两种盘就叫同一个名字（否则切石会同时看到“切石”和“切石样板”），搜索也才搜得到。
            var canonicalId = PatternDiskEncodingTermMenu.categoryForMode(mode);
            var canonicalName = canonicalId == null ? null : findCategoryName(canonicalId);
            return canonicalName != null
                    ? canonicalName
                    : Component.translatable("ae2_pattern_disk.mark.mode." + modeName);
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

    /** 模式标记里那个名字对应的模式；认不出来返回 null。 */
    @Nullable
    private static EncodingMode parseMode(String modeName) {
        try {
            return EncodingMode.valueOf(modeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownMode) {
            return null;
        }
    }

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

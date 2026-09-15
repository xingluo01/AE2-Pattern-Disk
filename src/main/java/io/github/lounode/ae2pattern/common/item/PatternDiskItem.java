package io.github.lounode.ae2pattern.common.item;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;

import io.github.lounode.ae2pattern.common.pattern.PatternClassifier;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskTier;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * A physical disk that stores up to {@link #capacity()} encoded patterns, all of a single type.
 *
 * <p>Usage:</p>
 * <ul>
 *   <li>{@link #contents(ItemStack)} returns the typed, capacity-bounded pattern list.</li>
 *   <li>{@link #tryInsert(ItemStack, ItemStack, Level)} adds an encoded pattern, locking the type on first use.</li>
 *   <li>Empty disks are untyped; the first inserted pattern determines the locked type.</li>
 * </ul>
 */
public class PatternDiskItem extends Item {

    private final PatternDiskTier tier;

    public PatternDiskItem(Properties properties, PatternDiskTier tier) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public PatternDiskTier tier() {
        return tier;
    }

    public int capacity() {
        return tier.capacity();
    }

    /**
     * Returns the current contents of the disk, or an empty untyped disk if none is stored yet.
     */
    public PatternDiskContents contents(ItemStack stack) {
        var contents = stack.get(AEPatternRegistries.DISK_CONTENTS.get());
        return contents != null ? contents : PatternDiskContents.empty(capacity());
    }

    /**
     * Item property value for the encoded-pattern type, used to drive the disk's rendered model:
     * 0 = untyped/empty, 1 = crafting, 2 = processing, 3 = smithing, 4 = stonecutting.
     */
    public static float typePropertyValue(ItemStack stack) {
        var instance = stack.getItem() instanceof PatternDiskItem disk ? disk : null;
        if (instance == null) {
            return 0;
        }
        var contents = instance.contents(stack);
        if (!contents.isTyped()) {
            return 0;
        }
        return switch (contents.type()) {
            case "ae2:crafting_pattern" -> 1;
            case "ae2:processing_pattern" -> 2;
            case "ae2:smithing_table_pattern" -> 3;
            case "ae2:stonecutting_pattern" -> 4;
            default -> 0;
        };
    }

    /**
     * Whether {@link #tryInsert} would accept {@code pattern} on this disk, without mutating anything.
     *
     * <p>This is the single source of truth for "can this disk take that pattern": the pattern type
     * must be resolvable, the disk must have room, its locked type (when set) must match, and no stored
     * pattern may already produce the same primary output (same-result mutual exclusion — only one
     * crafting route per output is worth storing).</p>
     *
     * <p>Callers that only need a read-only answer (slot filtering, space probing, pre-checks before a
     * larger multi-step move) should use this instead of re-deriving the rule from
     * {@link PatternDiskContents} directly.</p>
     */
    public boolean canInsert(ItemStack disk, ItemStack pattern, Level level) {
        IPatternDetails details = PatternClassifier.decode(pattern, level);
        String patternType = details == null ? null : PatternClassifier.typeOf(details);
        return patternType != null && canInsertAs(contents(disk), patternType, level, details);
    }

    /**
     * Attempts to insert an encoded pattern into the disk.
     *
     * <p>The same-result exclusion is enforced <em>here</em> rather than left to callers: every write
     * path ends up in this method, so a path that forgets the pre-check can no longer store a
     * duplicate crafting route for an output the disk already covers.</p>
     *
     * @return {@code true} if the pattern was added and the stack mutated, {@code false} if the pattern
     *         has no resolvable type, the disk is full, its locked type does not match, or a stored
     *         pattern already produces the same primary output
     */
    public boolean tryInsert(ItemStack disk, ItemStack pattern, Level level) {
        // Decoded once and reused for both the type key and the primary-output check: asking each of them
        // separately would decode the same candidate pattern twice.
        IPatternDetails details = PatternClassifier.decode(pattern, level);
        String patternType = details == null ? null : PatternClassifier.typeOf(details);
        if (patternType == null) {
            return false;
        }
        var contents = contents(disk);
        if (!canInsertAs(contents, patternType, level, details)) {
            return false;
        }
        var updated = contents.add(pattern, patternType);
        if (updated == null) {
            return false; // defensive: canInsertAs already proved room and type compatibility
        }
        disk.set(AEPatternRegistries.DISK_CONTENTS.get(), updated);
        return true;
    }

    /** Shared acceptance rule used by both the pure check and the write path. */
    private static boolean canInsertAs(PatternDiskContents contents, String patternType, Level level,
            IPatternDetails candidate) {
        // Capacity and type lock first. A full disk, or one locked to another type, cannot take the pattern
        // whatever it already stores, so the expensive half - checking every stored pattern for a matching
        // primary output - is skipped entirely. Both conditions are exactly the ones
        // PatternDiskContents.add rejects on, so this stays a pure short-circuit.
        if (!contents.acceptsType(patternType)) {
            return false;
        }
        return !PatternClassifier.hasSamePrimaryOutput(contents, candidate, level);
    }

    /**
     * Removes the pattern at the given index. No-op if the index is invalid.
     */
    public void removeAt(ItemStack disk, int index) {
        var contents = contents(disk);
        var updated = contents.remove(index);
        disk.set(AEPatternRegistries.DISK_CONTENTS.get(), updated);
    }

    /**
     * Returns the current number of stored patterns.
     */
    public int used(ItemStack stack) {
        return contents(stack).used();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        var contents = contents(stack);
        tooltip.add(Component.translatable("ae2_pattern_disk.tooltip.capacity", contents.used(), contents.capacity())
                .withStyle(ChatFormatting.GRAY));
        if (contents.isTyped()) {
            tooltip.add(Component.translatable("ae2_pattern_disk.tooltip.type", typeName(contents.type()))
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("ae2_pattern_disk.tooltip.untyped").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static Component typeName(String type) {
        return switch (type) {
            case "ae2:crafting_pattern" -> Component.translatable("ae2_pattern_disk.tooltip.type.crafting");
            case "ae2:processing_pattern" -> Component.translatable("ae2_pattern_disk.tooltip.type.processing");
            case "ae2:smithing_table_pattern" -> Component.translatable("ae2_pattern_disk.tooltip.type.smithing");
            case "ae2:stonecutting_pattern" -> Component.translatable("ae2_pattern_disk.tooltip.type.stonecutting");
            default -> Component.literal(type);
        };
    }
}

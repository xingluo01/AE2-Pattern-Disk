package io.github.lounode.ae2pattern.common.pattern;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;

/**
 * Classifies AE2 encoded pattern items into a stable type key used for disk type-locking.
 *
 * <p>The type key is the resource id of the encoded pattern item ({@code ae2:crafting_pattern},
 * {@code ae2:processing_pattern}, {@code ae2:smithing_table_pattern}, {@code ae2:stonecutting_pattern}).
 * Custom pattern items from other AE2 addons are classified by their own item id, so a disk can lock
 * to them too.</p>
 */
public final class PatternClassifier {

    private PatternClassifier() {
    }

    /**
     * Returns the type key for the given stack, or {@code null} if it is not an encoded pattern.
     */
    @Nullable
    public static String typeOf(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || level == null) {
            return null;
        }
        if (!PatternDetailsHelper.isEncodedPattern(stack)) {
            return null;
        }
        IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
        if (details == null) {
            return null;
        }
        return patternTypeId(details);
    }

    /**
     * Whether the given stack is an AE2 encoded pattern, without needing a level. Used for slot filters.
     */
    public static boolean isEncodedPatternStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && PatternDetailsHelper.isEncodedPattern(stack);
    }

    /**
     * Decodes the pattern details for an encoded-pattern stack, or {@code null} if it cannot be decoded.
     */
    @Nullable
    public static IPatternDetails decode(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || level == null) {
            return null;
        }
        if (!PatternDetailsHelper.isEncodedPattern(stack)) {
            return null;
        }
        return PatternDetailsHelper.decodePattern(stack, level);
    }

    /**
     * Whether the given stack is AE2's blank pattern item.
     */
    public static boolean isBlankPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var blank = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:blank_pattern"));
        return stack.is(blank);
    }

    /**
     * Returns the type key for an already-decoded pattern.
     */
    public static String typeOf(IPatternDetails details) {
        return patternTypeId(details);
    }

    private static String patternTypeId(IPatternDetails details) {
        AEItemKey definition = details.getDefinition();
        if (definition == null) {
            return null;
        }
        return definition.getId().toString();
    }
}

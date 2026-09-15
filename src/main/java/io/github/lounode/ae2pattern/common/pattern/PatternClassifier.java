package io.github.lounode.ae2pattern.common.pattern;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Decoded stored patterns per (contents snapshot, level) pair.
     *
     * <p>The contents are a record that is replaced on every write, so an entry normally cannot go
     * stale. Two caveats keep {@link #GENERATION} around: a record is only shallowly immutable, so an
     * element modified in place would leave an entry that still matches its key; and a data reload can
     * change what a stored pattern decodes to without any item changing at all. Without this cache,
     * every probe and every write re-decodes each pattern already on the disk, which on a large disk is
     * most of the cost of the check.</p>
     */
    private static final Map<PatternDiskContents, DecodedPatterns> DECODED_STORED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Incremented on server data reload; an entry from an earlier generation is not reused. */
    private static final AtomicLong GENERATION = new AtomicLong();

    private record DecodedPatterns(Level level, long generation, List<IPatternDetails> details) {
    }

    /**
     * Drops every memoized decode. Called when server data reloads: a pattern that decoded to one thing
     * can decode to another afterwards, and the cache is keyed by items that did not change.
     */
    public static void invalidateDecodedCache() {
        GENERATION.incrementAndGet();
        DECODED_STORED.clear();
    }

    /**
     * 判断待加入的候选配方是否与磁盘上已有配方产生相同主产物（主产物互斥）。
     * 同主产物仅允许存一条配方，避免同一产出被多条相似配方重复覆盖。
     *
     * @param contents  磁盘当前内容
     * @param candidate 已解码的待加入样板
     * @return 存在同主产物冲突时返回 true
     */
    public static boolean hasSamePrimaryOutput(PatternDiskContents contents, IPatternDetails candidate,
            Level level) {
        if (contents == null || candidate == null) {
            return false;
        }
        var candidateOutput = candidate.getPrimaryOutput();
        if (candidateOutput == null) {
            return false;
        }
        for (var details : decodedStored(contents, level)) {
            var existingOutput = details.getPrimaryOutput();
            if (existingOutput != null && existingOutput.what().equals(candidateOutput.what())) {
                return true;
            }
        }
        return false;
    }

    private static List<IPatternDetails> decodedStored(PatternDiskContents contents, Level level) {
        var patterns = contents.patterns();
        if (patterns.isEmpty() || level == null) {
            return List.of();
        }
        long generation = GENERATION.get();
        var cached = DECODED_STORED.get(contents);
        if (cached != null && cached.level() == level && cached.generation() == generation) {
            return cached.details();
        }
        var decoded = new ArrayList<IPatternDetails>(patterns.size());
        for (var stored : patterns) {
            var details = decode(stored, level);
            if (details != null) {
                decoded.add(details);
            }
        }
        var result = List.copyOf(decoded);
        DECODED_STORED.put(contents, new DecodedPatterns(level, generation, result));
        return result;
    }

    private static String patternTypeId(IPatternDetails details) {
        AEItemKey definition = details.getDefinition();
        if (definition == null) {
            return null;
        }
        return definition.getId().toString();
    }
}

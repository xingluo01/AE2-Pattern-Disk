package io.github.lounode.ae2pattern.common.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * The content stored on a pattern disk: a typed, capacity-bounded list of encoded patterns.
 *
 * <p>The {@code type} is the resource id of the AE2 encoded pattern item this disk is locked to
 * (e.g. {@code ae2:crafting_pattern}). An empty disk has {@code null} type until the first pattern
 * is added, at which point it locks to that pattern's type.</p>
 *
 * <p>The {@code patterns} list is copied on construction, so the record itself never changes - but its
 * elements are {@link ItemStack}s, which are mutable. Every write path replaces the whole record rather
 * than editing an element, and consumers that hand these stacks to something that could modify them
 * must copy. Code that memoizes anything against a record instance depends on that: an element edited
 * in place would leave a memo matching its own key while describing different contents.</p>
 *
 * @param type     the locked encoded-pattern item id, or {@code null} while the disk is untyped
 * @param patterns the encoded pattern stacks currently stored
 */
public record PatternDiskContents(
        String type,
        int capacity,
        List<ItemStack> patterns) {

    public PatternDiskContents {
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (type != null && patterns.size() > capacity) {
            throw new IllegalArgumentException("patterns exceed capacity");
        }
    }

    public static final Codec<PatternDiskContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("type").forGetter(c -> java.util.Optional.ofNullable(c.type)),
            Codec.INT.fieldOf("capacity").forGetter(PatternDiskContents::capacity),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("patterns").forGetter(PatternDiskContents::patterns))
            .apply(instance, (type, capacity, patterns) -> new PatternDiskContents(type.orElse(null), capacity, patterns)));

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternDiskContents> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8),
            c -> java.util.Optional.ofNullable(c.type),
            ByteBufCodecs.VAR_INT,
            PatternDiskContents::capacity,
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()),
            PatternDiskContents::patterns,
            (type, capacity, patterns) -> new PatternDiskContents(type.orElse(null), capacity, patterns));

    public static PatternDiskContents empty(int capacity) {
        return new PatternDiskContents(null, capacity, List.of());
    }

    public int used() {
        return patterns.size();
    }

    public boolean isFull() {
        return patterns.size() >= capacity;
    }

    /**
     * Whether a pattern of {@code patternType} could be added at all, ignoring the same-output exclusion.
     *
     * <p>A pure short-circuit, deliberately mirroring the two conditions {@link #add} rejects on. Callers
     * that have a more expensive acceptance test to run afterwards - decoding every stored pattern to check
     * for a matching primary output, say - should ask this first: a disk that is full or locked to another
     * type cannot take the pattern no matter what it already holds, and the cheap answer saves the scan.</p>
     *
     * <p>Keep the conditions here and in {@link #add} in step: if they drift, this stops being a
     * short-circuit and starts accepting patterns the write path then refuses.</p>
     */
    public boolean acceptsType(String patternType) {
        return !isFull() && acceptsTypeOnly(patternType);
    }

    /**
     * 只看类型锁，不看容量。{@link #acceptsType} 是它的容量版；需要分开判断“满了还是锁了”的调用方
     * （例如给玩家一句具体说明）用这个，避免自己再写一遍类型比较而与写入路径走偏。
     */
    public boolean acceptsTypeOnly(String patternType) {
        return type == null || type.equals(patternType);
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    public boolean isTyped() {
        return type != null;
    }

    /**
     * Adds a single pattern, locking the type if this disk was untyped. Returns the new contents on success,
     * or null if the disk is full or the pattern type does not match.
     */
    public PatternDiskContents add(ItemStack pattern, String patternType) {
        if (!acceptsType(patternType)) {
            return null;
        }
        var newPatterns = new ArrayList<>(patterns);
        newPatterns.add(pattern.copy());
        var newType = type != null ? type : patternType;
        return new PatternDiskContents(newType, capacity, newPatterns);
    }

    /**
     * Inserts a single pattern back at {@code index} (undo of {@link #remove(int)}): restores a pattern
     * to its original position during an AE2 swap rollback. Out-of-range indices clamp to the end. Returns
     * the new contents on success, or null if the disk is full or the pattern type does not match.
     */
    public PatternDiskContents insert(int index, ItemStack pattern, String patternType) {
        if (!acceptsType(patternType)) {
            return null;
        }
        if (index < 0 || index > patterns.size()) {
            index = patterns.size();
        }
        var newPatterns = new ArrayList<>(patterns);
        newPatterns.add(index, pattern.copy());
        var newType = type != null ? type : patternType;
        return new PatternDiskContents(newType, capacity, newPatterns);
    }

    /**
     * Removes the pattern at the given index, if the index is valid.
     */
    public PatternDiskContents remove(int index) {
        if (index < 0 || index >= patterns.size()) {
            return this;
        }
        var newPatterns = new ArrayList<>(patterns);
        newPatterns.remove(index);
        var newType = newPatterns.isEmpty() ? null : type;
        return new PatternDiskContents(newType, capacity, newPatterns);
    }
}

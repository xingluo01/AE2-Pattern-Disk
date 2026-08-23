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

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    public boolean isTyped() {
        return type != null;
    }

    /**
     * Adds a single pattern, locking the type if this disk was untyped. Returns {@code null} on success,
     * or the offending stack if the disk is full or the pattern type does not match.
     */
    public PatternDiskContents add(ItemStack pattern, String patternType) {
        if (isFull()) {
            return null;
        }
        if (type != null && !type.equals(patternType)) {
            return null;
        }
        var newPatterns = new ArrayList<>(patterns);
        newPatterns.add(pattern.copy());
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

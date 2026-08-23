package io.github.lounode.ae2pattern.client;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;

import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.core.AEPatternItems;

/**
 * Registers item properties that drive the pattern-disk model based on the encoded-pattern type.
 * The model JSON overrides switch on {@code pattern_type} so a disk renders differently when it
 * holds crafting/processing/smithing/stonecutting recipes.
 */
public final class InitPatternDiskProperties {

    private InitPatternDiskProperties() {
    }

    public static void init() {
        var propertyId = ResourceLocation.parse("ae2_pattern_disk:pattern_type");
        ItemProperties.register(AEPatternItems.PATTERN_DISK_1K.get(), propertyId,
                (stack, level, entity, seed) -> PatternDiskItem.typePropertyValue(stack));
        ItemProperties.register(AEPatternItems.PATTERN_DISK_4K.get(), propertyId,
                (stack, level, entity, seed) -> PatternDiskItem.typePropertyValue(stack));
        ItemProperties.register(AEPatternItems.PATTERN_DISK_16K.get(), propertyId,
                (stack, level, entity, seed) -> PatternDiskItem.typePropertyValue(stack));
        ItemProperties.register(AEPatternItems.PATTERN_DISK_64K.get(), propertyId,
                (stack, level, entity, seed) -> PatternDiskItem.typePropertyValue(stack));
    }
}

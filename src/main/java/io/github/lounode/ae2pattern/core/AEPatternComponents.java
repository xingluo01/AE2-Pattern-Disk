package io.github.lounode.ae2pattern.core;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;

/**
 * DataComponents carried by pattern disks and the transferer.
 */
public final class AEPatternComponents {

    private AEPatternComponents() {
    }

    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(
            Registries.DATA_COMPONENT_TYPE, AE2PatternDisk.MOD_ID);

    /**
     * Whether a disk is locked to a single pattern type. Type value is the AE2 encoded pattern item id.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DISK_TYPE = COMPONENTS
            .registerComponentType(
                    "disk_type",
                    builder -> builder.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /**
     * Capacity tier of the disk (number of patterns it can hold).
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> DISK_CAPACITY = COMPONENTS
            .registerComponentType(
                    "disk_capacity",
                    builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * The typed, capacity-bounded list of encoded patterns stored on a disk.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternDiskContents>> DISK_CONTENTS = COMPONENTS
            .registerComponentType(
                    "disk_contents",
                    builder -> builder.persistent(PatternDiskContents.CODEC)
                            .networkSynchronized(PatternDiskContents.STREAM_CODEC));
}

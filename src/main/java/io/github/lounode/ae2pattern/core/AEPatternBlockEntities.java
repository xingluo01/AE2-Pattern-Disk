package io.github.lounode.ae2pattern.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;

/**
 * Block entity registration for the mod.
 */
public final class AEPatternBlockEntities {

    private AEPatternBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            Registries.BLOCK_ENTITY_TYPE, AE2PatternDisk.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternTransfererBlockEntity>> PATTERN_TRANSFERER = BLOCK_ENTITIES
            .register(
                    "pattern_transferer",
                    () -> BlockEntityType.Builder.of(
                            PatternTransfererBlockEntity::new,
                            AEPatternBlocks.PATTERN_TRANSFERER.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternDiskProviderBlockEntity>> PATTERN_DISK_PROVIDER = BLOCK_ENTITIES
            .register(
                    "pattern_disk_provider",
                    () -> BlockEntityType.Builder.of(
                            PatternDiskProviderBlockEntity::new,
                            AEPatternBlocks.PATTERN_DISK_PROVIDER.get())
                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternDiskAssemblerBlockEntity>> PATTERN_DISK_ASSEMBLER = BLOCK_ENTITIES
            .register(
                    "pattern_disk_assembler",
                    () -> BlockEntityType.Builder.of(
                            PatternDiskAssemblerBlockEntity::new,
                            AEPatternBlocks.PATTERN_DISK_ASSEMBLER.get())
                            .build(null));
}

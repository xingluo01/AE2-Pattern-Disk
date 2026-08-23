package io.github.lounode.ae2pattern;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;
import io.github.lounode.ae2pattern.core.AEPatternBlockEntities;
import io.github.lounode.ae2pattern.core.AEPatternBlocks;
import io.github.lounode.ae2pattern.core.AEPatternCreativeTabs;

/**
 * Entry point for the AE2 Pattern Disk addon.
 */
@Mod(AE2PatternDisk.MOD_ID)
public class AE2PatternDisk {

    public static final String MOD_ID = "ae2_pattern_disk";

    public AE2PatternDisk(IEventBus modBus) {
        // Registration entry points
        AEPatternRegistries.register(modBus);
        AEPatternCreativeTabs.TABS.register(modBus);

        modBus.addListener(this::associateBlockEntities);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::registerCapabilities);
    }

    /**
     * Registers AE2 capabilities that AE2 itself only binds for its own BlockEntityTypes.
     * Our machines use new BlockEntityTypes, so we must re-register the same capabilities.
     * Without {@code IN_WORLD_GRID_NODE_HOST} the grid cannot reach our nodes (connection lost on
     * world reload / channel drop), and without {@code GENERIC_INTERNAL_INV}/{@code ItemHandler}
     * the molecular assembler cannot push crafts back into our return inventory.
     */
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Grid node host: lets AE2 cables / grid find our in-world nodes (fixes lost connection).
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternBlockEntities.PATTERN_TRANSFERER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternBlockEntities.PATTERN_DISK_PROVIDER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);

        // Generic internal inventory / item handler: exposes the return inventory so the molecular
        // assembler (and other AE machines) can push crafted results back into the provider.
        event.registerBlockEntity(
                appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                AEPatternBlockEntities.PATTERN_DISK_PROVIDER.get(),
                (be, dir) -> PatternDiskProviderBlockEntity.class.cast(be).getLogic().getReturnInv());

        // Crafting machine: exposes the assembler so any AE2 provider can push patterns to it.
        event.registerBlockEntity(
                appeng.api.AECapabilities.CRAFTING_MACHINE,
                AEPatternBlockEntities.PATTERN_DISK_ASSEMBLER.get(),
                (be, dir) -> (appeng.api.implementations.blockentities.ICraftingMachine) be);

        // Grid node host for the assembler (needed so cables can reach its node).
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternBlockEntities.PATTERN_DISK_ASSEMBLER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);
    }

    private void associateBlockEntities(RegisterEvent event) {
        if (event.getRegistryKey() == net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE) {
            AEPatternBlocks.PATTERN_TRANSFERER.get().setBlockEntity(
                    PatternTransfererBlockEntity.class,
                    AEPatternBlockEntities.PATTERN_TRANSFERER.get(),
                    null,
                    (lvl, p, st, be) -> ((appeng.blockentity.ServerTickingBlockEntity) be).serverTick());

            AEPatternBlocks.PATTERN_DISK_PROVIDER.get().setBlockEntity(
                    PatternDiskProviderBlockEntity.class,
                    AEPatternBlockEntities.PATTERN_DISK_PROVIDER.get(),
                    null,
                    null);

            AEPatternBlocks.PATTERN_DISK_ASSEMBLER.get().setBlockEntity(
                    PatternDiskAssemblerBlockEntity.class,
                    AEPatternBlockEntities.PATTERN_DISK_ASSEMBLER.get(),
                    null,
                    null);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(this::registerUpgrades);
    }

    private void registerUpgrades() {
        var machine = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_transferer"));
        var speedCard = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:speed_card"));
        if (machine != null && speedCard != null) {
            appeng.api.upgrades.Upgrades.add(speedCard, machine, 4);
        }
    }
}

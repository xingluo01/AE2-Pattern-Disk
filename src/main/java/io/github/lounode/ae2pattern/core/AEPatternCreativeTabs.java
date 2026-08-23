package io.github.lounode.ae2pattern.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.AE2PatternDisk;

/**
 * Independent creative tab for the AE2 Pattern Disk addon.
 */
public final class AEPatternCreativeTabs {

    private AEPatternCreativeTabs() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB, AE2PatternDisk.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2_pattern_disk"))
                    .icon(() -> new ItemStack(AEPatternItems.PATTERN_DISK_1K.get()))
                    .displayItems((params, output) -> {
                        output.accept(AEPatternItems.PATTERN_DISK_1K.get());
                        output.accept(AEPatternItems.PATTERN_DISK_4K.get());
                        output.accept(AEPatternItems.PATTERN_DISK_16K.get());
                        output.accept(AEPatternItems.PATTERN_DISK_64K.get());
                        output.accept(AEPatternItems.PATTERN_TRANSFERER.get());
                        output.accept(AEPatternItems.PATTERN_DISK_PROVIDER.get());
                        output.accept(AEPatternItems.PATTERN_DISK_ASSEMBLER.get());
                    })
                    .build());
}

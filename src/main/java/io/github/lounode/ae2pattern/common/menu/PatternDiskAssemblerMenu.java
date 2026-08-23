package io.github.lounode.ae2pattern.common.menu;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.interfaces.IProgressProvider;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;

/**
 * Menu for the pattern disk assembler (高效分子装配室). Shows upgrade slots (via
 * {@link UpgradeableMenu}) and total crafting progress across all threads.
 */
public class PatternDiskAssemblerMenu extends UpgradeableMenu<PatternDiskAssemblerBlockEntity>
        implements IProgressProvider {

    public static final MenuType<PatternDiskAssemblerMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskAssemblerMenu::new, PatternDiskAssemblerBlockEntity.class)
            .build("pattern_disk_assembler");

    private final PatternDiskAssemblerBlockEntity host;

    public PatternDiskAssemblerMenu(int id, Inventory playerInv, PatternDiskAssemblerBlockEntity host) {
        super(TYPE, id, playerInv, host);
        this.host = host;
    }

    public PatternDiskAssemblerBlockEntity getAssembler() {
        return host;
    }

    @Override
    public int getCurrentProgress() {
        return host.getCraftingProgressAcrossThreads();
    }

    @Override
    public int getMaxProgress() {
        return PatternDiskAssemblerBlockEntity.THREADS * 100;
    }
}

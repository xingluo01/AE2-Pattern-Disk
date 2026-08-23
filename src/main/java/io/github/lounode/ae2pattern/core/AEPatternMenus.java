package io.github.lounode.ae2pattern.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;
import io.github.lounode.ae2pattern.common.menu.PatternTransfererMenu;

/**
 * Menu registration for the mod.
 */
public final class AEPatternMenus {

    private AEPatternMenus() {
    }

    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU,
            AE2PatternDisk.MOD_ID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<MenuType<?>, MenuType<PatternTransfererMenu>> PATTERN_TRANSFERER = MENUS
            .register("pattern_transferer", () -> PatternTransfererMenu.TYPE);

    public static final net.neoforged.neoforge.registries.DeferredHolder<MenuType<?>, MenuType<PatternDiskProviderMenu>> PATTERN_DISK_PROVIDER = MENUS
            .register("pattern_disk_provider", () -> PatternDiskProviderMenu.TYPE);
}

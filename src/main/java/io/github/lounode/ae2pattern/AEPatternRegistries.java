package io.github.lounode.ae2pattern;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.lounode.ae2pattern.core.AEPatternBlocks;
import io.github.lounode.ae2pattern.core.AEPatternBlockEntities;
import io.github.lounode.ae2pattern.core.AEPatternComponents;
import io.github.lounode.ae2pattern.core.AEPatternItems;
import io.github.lounode.ae2pattern.core.AEPatternMenus;

/**
 * Aggregates all DeferredRegisters so the mod entry only needs one registration call.
 */
public final class AEPatternRegistries {

    private AEPatternRegistries() {
    }

    public static void register(IEventBus modBus) {
        AEPatternItems.ITEMS.register(modBus);
        AEPatternBlocks.BLOCKS.register(modBus);
        AEPatternBlockEntities.BLOCK_ENTITIES.register(modBus);
        AEPatternMenus.MENUS.register(modBus);
        AEPatternComponents.COMPONENTS.register(modBus);
    }
}

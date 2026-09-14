package io.github.lounode.ae2pattern.client.integration;

import net.minecraft.resources.ResourceLocation;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeTransferRegistration;

import io.github.lounode.ae2pattern.AE2PatternDisk;

/**
 * JEI entry point: wires the pattern disk encoding terminal into JEI's recipe transfer ("+") button.
 *
 * <p>Only this class and {@link JeiDiskEncodeRecipeHandler} touch JEI types, and JEI discovers the plugin
 * through {@link JeiPlugin}, so the mod keeps working unchanged when JEI is not installed - the same split
 * the EMI integration uses.</p>
 */
@JeiPlugin
public class PatternDiskJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(AE2PatternDisk.MOD_ID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addUniversalRecipeTransferHandler(new JeiDiskEncodeRecipeHandler(registration.getTransferHelper()));
    }
}

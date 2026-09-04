package io.github.lounode.ae2pattern.common.menu;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.parts.encoding.EncodingMode;
import appeng.util.ConfigInventory;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.filter.AEItemDefinitionFilter;

/**
 * Manages the encoding state for the pattern disk encoding terminal: the encoding grid (4 modes),
 * blank/encoded pattern slots, and the current mode/substitution settings.
 *
 * <p>Mirrors {@code appeng.parts.encoding.PatternEncodingLogic} but adapted for the pattern disk
 * encoding terminal's workflow.</p>
 */
public class DiskEncodingLogic implements InternalInventoryHost {

    private final IDiskEncodingLogicHost host;

    private static final int MAX_INPUT_SLOTS = Math.max(AECraftingPattern.CRAFTING_GRID_SLOTS,
            AEProcessingPattern.MAX_INPUT_SLOTS);
    private static final int MAX_OUTPUT_SLOTS = AEProcessingPattern.MAX_OUTPUT_SLOTS;

    private final ConfigInventory encodedInputInv = ConfigInventory.configStacks(MAX_INPUT_SLOTS)
            .changeListener(this::onEncodedInputChanged).allowOverstacking(true).build();
    private final ConfigInventory encodedOutputInv = ConfigInventory.configStacks(MAX_OUTPUT_SLOTS)
            .changeListener(this::onEncodedOutputChanged).allowOverstacking(true).build();

    private final AppEngInternalInventory blankPatternInv = new AppEngInternalInventory(this, 1);
    private final AppEngInternalInventory encodedPatternInv = new AppEngInternalInventory(this, 1);

    private EncodingMode mode = EncodingMode.CRAFTING;
    private boolean substitute = false;
    private boolean substituteFluids = true;
    private boolean isLoading = false;
    @Nullable
    private ResourceLocation stonecuttingRecipeId;

    public DiskEncodingLogic(IDiskEncodingLogicHost host) {
        this.host = host;
        this.blankPatternInv.setFilter(new AEItemDefinitionFilter(AEItems.BLANK_PATTERN));
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == this.encodedPatternInv) {
            loadEncodedPattern(encodedPatternInv.getStackInSlot(0));
        }
        saveChanges();
    }

    public void saveChanges() {
        if (!isLoading) {
            host.markForSave();
        }
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    @Override
    public boolean isClientSide() {
        return host.getLevel().isClientSide();
    }

    private void onEncodedInputChanged() {
        saveChanges();
    }

    private void onEncodedOutputChanged() {
        saveChanges();
    }

    private void loadEncodedPattern(ItemStack pattern) {
        if (pattern.isEmpty()) return;
        var details = PatternDetailsHelper.decodePattern(pattern, host.getLevel());
        if (details instanceof AECraftingPattern cp) {
            setMode(EncodingMode.CRAFTING);
            this.substitute = cp.canSubstitute();
            this.substituteFluids = cp.canSubstituteFluids();
            fillInventoryFromSparseStacks(encodedInputInv, cp.getSparseInputs());
            fillInventoryFromSparseStacks(encodedOutputInv, cp.getSparseOutputs());
        } else if (details instanceof AEProcessingPattern pp) {
            setMode(EncodingMode.PROCESSING);
            fillInventoryFromSparseStacks(encodedInputInv, pp.getSparseInputs());
            fillInventoryFromSparseStacks(encodedOutputInv, pp.getSparseOutputs());
        } else if (details instanceof appeng.crafting.pattern.AESmithingTablePattern st) {
            setMode(EncodingMode.SMITHING_TABLE);
            this.substitute = st.canSubstitute();
            encodedInputInv.clear();
            encodedInputInv.setStack(0, new GenericStack(st.getTemplate(), 1));
            encodedInputInv.setStack(1, new GenericStack(st.getBase(), 1));
            encodedInputInv.setStack(2, new GenericStack(st.getAddition(), 1));
            encodedOutputInv.clear();
        } else if (details instanceof appeng.crafting.pattern.AEStonecuttingPattern sc) {
            setMode(EncodingMode.STONECUTTING);
            this.stonecuttingRecipeId = sc.getRecipeId();
            this.substitute = sc.canSubstitute;
            encodedInputInv.clear();
            encodedInputInv.setStack(0, new GenericStack(sc.getInput(), 1));
            encodedOutputInv.clear();
        }
        saveChanges();
    }

    private static void fillInventoryFromSparseStacks(ConfigInventory inv, java.util.List<GenericStack> stacks) {
        inv.beginBatch();
        try {
            for (int i = 0; i < inv.size(); i++) {
                inv.setStack(i, i < stacks.size() ? stacks.get(i) : null);
            }
        } finally {
            inv.endBatch();
        }
    }

    public EncodingMode getMode() { return mode; }
    public void setMode(EncodingMode mode) { this.mode = mode; saveChanges(); }
    public boolean isSubstitution() { return substitute; }
    public void setSubstitution(boolean v) { this.substitute = v; saveChanges(); }
    public boolean isFluidSubstitution() { return substituteFluids; }
    public void setFluidSubstitution(boolean v) { this.substituteFluids = v; saveChanges(); }
    public @Nullable ResourceLocation getStonecuttingRecipeId() { return stonecuttingRecipeId; }
    public void setStonecuttingRecipeId(@Nullable ResourceLocation id) { this.stonecuttingRecipeId = id; saveChanges(); }

    public ConfigInventory getEncodedInputInv() { return encodedInputInv; }
    public ConfigInventory getEncodedOutputInv() { return encodedOutputInv; }
    public InternalInventory getBlankPatternInv() { return blankPatternInv; }
    public InternalInventory getEncodedPatternInv() { return encodedPatternInv; }

    public void readFromNBT(net.minecraft.nbt.CompoundTag data, net.minecraft.core.HolderLookup.Provider registries) {
        isLoading = true;
        try {
            try { this.mode = EncodingMode.valueOf(data.getString("mode")); } catch (IllegalArgumentException ignored) { this.mode = EncodingMode.CRAFTING; }
            this.substitute = data.getBoolean("substitute");
            this.substituteFluids = data.getBoolean("substituteFluids");
            if (data.contains("stonecuttingRecipeId", net.minecraft.nbt.Tag.TAG_STRING)) {
                this.stonecuttingRecipeId = ResourceLocation.parse(data.getString("stonecuttingRecipeId"));
            } else { this.stonecuttingRecipeId = null; }
            blankPatternInv.readFromNBT(data, "blankPattern", registries);
            encodedPatternInv.readFromNBT(data, "encodedPattern", registries);
            encodedInputInv.readFromChildTag(data, "encodedInputs", registries);
            encodedOutputInv.readFromChildTag(data, "encodedOutputs", registries);
        } finally { isLoading = false; }
    }

    public void writeToNBT(net.minecraft.nbt.CompoundTag data, net.minecraft.core.HolderLookup.Provider registries) {
        data.putString("mode", this.mode.name());
        data.putBoolean("substitute", this.substitute);
        data.putBoolean("substituteFluids", this.substituteFluids);
        if (this.stonecuttingRecipeId != null) data.putString("stonecuttingRecipeId", this.stonecuttingRecipeId.toString());
        blankPatternInv.writeToNBT(data, "blankPattern", registries);
        encodedPatternInv.writeToNBT(data, "encodedPattern", registries);
        encodedInputInv.writeToChildTag(data, "encodedInputs", registries);
        encodedOutputInv.writeToChildTag(data, "encodedOutputs", registries);
    }

}
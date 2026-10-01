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

    /**
     * 雕凿档自己的输入格。
     *
     * <p>不与 {@code encodedInputInv} 合用：那个库存的每一格都会被编码路径扫描（处理样板输入循环、高级方向表、
     * 倍数与除法），而且切石与锻造对它的下标是硬编码的 0/1/2。雕凿本来只需要一格「放个方块、看能雕成什么」，
     * 单独一份既不会与被扫描的格串味，也让两档各自记住自己的输入。</p>
     */
    private final ConfigInventory chiselingInputInv = ConfigInventory.configStacks(1)
            .changeListener(this::onEncodedInputChanged).allowOverstacking(true).build();

    private final AppEngInternalInventory blankPatternInv = new AppEngInternalInventory(this, 1);
    private final AppEngInternalInventory encodedPatternInv = new AppEngInternalInventory(this, 1);

    private EncodingMode mode = EncodingMode.CRAFTING;
    /**
     * 是否停在高级编码模式。它不在 AE2 的 {@code EncodingMode} 里（那个枚举不可扩展），但对玩家而言与 mode
     * 是同一件事——「当前在哪一档」，所以存在同一处、同样跟着终端走：关屏重开不该把它忘掉。
     */
    private boolean advancedMode;

    /**
     * 是否停在雕凿编码模式。与 {@link #advancedMode} 同处一个理由：它也不在 AE2 的枚举里，但对玩家而言同样
     * 是「当前在哪一档」，得跟着终端走。
     */
    private boolean chiselingMode;
    private boolean substitute = false;
    private boolean substituteFluids = true;
    private boolean mergeSameItems = true;
    private boolean showUnmarkedDisks = false;
    /** 管理终端：隐藏没有内容的样板供应器槽位（屏幕左侧列表的显示开关）。面板与无线共用这一份状态。 */
    private boolean hideEmptySlots = true;
    /** 管理终端：列表里被选中的样板磁盘序列号，0 表示未选中。跟着终端走，关屏重开不丢。 */
    private long selectedSerial;
    /** 管理终端新增搜索栏的搜索范围：只看输出、只看输入、或两者都看。 */
    private SearchScope searchScope = SearchScope.BOTH;
    /** 「附加排序」开关（mod 档下组内按名字里的数值排）。原本是纯客户端视图状态，换屏即失，
     * 所以并入这里才能跟着终端走。 */
    private boolean naturalSort = true;
    /** 管理终端的「显示模式」。面板版存在 AE2 的配置管理器里（设置由部件注册），而无线宿主的配置管理器
     * 来自物品（ItemWT 只注册了排序/视图三项），读那项设置会抛 UnsupportedSettingException，
     * 连带把分组清单也发不出去——所以无线一律用这份本模组自己的值。 */
    private appeng.api.config.ShowPatternProviders shownProviders = appeng.api.config.ShowPatternProviders.VISIBLE;
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
    public boolean isAdvancedMode() { return advancedMode; }
    public void setAdvancedMode(boolean v) { this.advancedMode = v; saveChanges(); }
    public boolean isChiselingMode() { return chiselingMode; }
    public void setChiselingMode(boolean v) { this.chiselingMode = v; saveChanges(); }
    public boolean isSubstitution() { return substitute; }
    public void setSubstitution(boolean v) { this.substitute = v; saveChanges(); }
    public boolean isFluidSubstitution() { return substituteFluids; }
    public void setFluidSubstitution(boolean v) { this.substituteFluids = v; saveChanges(); }

    /**
     * 处理模式下的「合并相同物品」。和替换一样存在宿主部件自己的 NBT 里：终端就是部件，开关就是它的设置，
     * 重开终端不该把它忘掉。
     */
    public boolean isMergeSameItems() { return mergeSameItems; }
    public void setMergeSameItems(boolean v) { this.mergeSameItems = v; saveChanges(); }

    /**
     * 「强制列出无标记的样板磁盘」。本身只是客户端的过滤口径，但同样存进宿主 NBT——玩家的诉求是“别每次开
     * 终端重调”，按终端记住即可。
     */
    public boolean isShowUnmarkedDisks() { return showUnmarkedDisks; }
    public void setShowUnmarkedDisks(boolean v) { this.showUnmarkedDisks = v; saveChanges(); }
    public @Nullable ResourceLocation getStonecuttingRecipeId() { return stonecuttingRecipeId; }
    public void setStonecuttingRecipeId(@Nullable ResourceLocation id) { this.stonecuttingRecipeId = id; saveChanges(); }

    public ConfigInventory getEncodedInputInv() { return encodedInputInv; }
    public ConfigInventory getChiselingInputInv() { return chiselingInputInv; }
    public ConfigInventory getEncodedOutputInv() { return encodedOutputInv; }
    public boolean isHideEmptySlots() { return this.hideEmptySlots; }
    public void setHideEmptySlots(boolean hide) { this.hideEmptySlots = hide; }
    public long getSelectedSerial() { return this.selectedSerial; }
    public void setSelectedSerial(long serial) { this.selectedSerial = serial; }
    public SearchScope getSearchScope() { return this.searchScope; }
    public void setSearchScope(SearchScope scope) { this.searchScope = scope; }
    public boolean isNaturalSort() { return this.naturalSort; }
    public void setNaturalSort(boolean naturalSort) { this.naturalSort = naturalSort; }
    public appeng.api.config.ShowPatternProviders getShownProviders() { return this.shownProviders; }
    public void setShownProviders(appeng.api.config.ShowPatternProviders shownProviders) {
        this.shownProviders = shownProviders;
    }

    /** 新增搜索栏的搜索范围三态，与 PAT 搜索栏旁那枚轮换按钮一一对应。 */
    public enum SearchScope {
        /** 只匹配样板的输出。 */
        OUTPUT,
        /** 只匹配样板的输入。 */
        INPUT,
        /** 输入与输出都匹配。 */
        BOTH
    }

    public InternalInventory getBlankPatternInv() { return blankPatternInv; }
    public InternalInventory getEncodedPatternInv() { return encodedPatternInv; }

    public void readFromNBT(net.minecraft.nbt.CompoundTag data, net.minecraft.core.HolderLookup.Provider registries) {
        isLoading = true;
        try {
            try { this.mode = EncodingMode.valueOf(data.getString("mode")); } catch (IllegalArgumentException ignored) { this.mode = EncodingMode.CRAFTING; }
            // 后加的键，旧存档里没有：缺键时落回「不在高级档」，不能直接 getBoolean 把它读成已开启。
            this.advancedMode = data.contains("advancedMode") && data.getBoolean("advancedMode");
            this.chiselingMode = data.contains("chiselingMode") && data.getBoolean("chiselingMode");
            this.substitute = data.getBoolean("substitute");
            this.substituteFluids = data.getBoolean("substituteFluids");
            // 这两个键是后加的：旧存档里没有。缺键时必须落到各自的默认值（合并默认开、无标记默认不列），
            // 不能直接 getBoolean——那会把缺失读成 false，把开关反过来。
            this.mergeSameItems = !data.contains("mergeSameItems") || data.getBoolean("mergeSameItems");
            this.showUnmarkedDisks = data.contains("showUnmarkedDisks") && data.getBoolean("showUnmarkedDisks");
            // 同上：缺键要落到默认值（默认隐藏空槽），不能直接 getBoolean 把开关反过来。
            this.hideEmptySlots = !data.contains("hideEmptySlots") || data.getBoolean("hideEmptySlots");
            this.selectedSerial = data.getLong("selectedSerial");
            try { this.searchScope = SearchScope.valueOf(data.getString("searchScope")); } catch (IllegalArgumentException ignored) { this.searchScope = SearchScope.BOTH; }
            // 缺键默认开（与按钮自己的默认一致），不能直接 getBoolean 把开关反过来。
            this.naturalSort = !data.contains("naturalSort") || data.getBoolean("naturalSort");
            try {
                this.shownProviders = appeng.api.config.ShowPatternProviders.valueOf(data.getString("shownProviders"));
            } catch (IllegalArgumentException ignored) {
                this.shownProviders = appeng.api.config.ShowPatternProviders.VISIBLE;
            }
            if (data.contains("stonecuttingRecipeId", net.minecraft.nbt.Tag.TAG_STRING)) {
                this.stonecuttingRecipeId = ResourceLocation.parse(data.getString("stonecuttingRecipeId"));
            } else { this.stonecuttingRecipeId = null; }
            blankPatternInv.readFromNBT(data, "blankPattern", registries);
            encodedPatternInv.readFromNBT(data, "encodedPattern", registries);
            encodedInputInv.readFromChildTag(data, "encodedInputs", registries);
            chiselingInputInv.readFromChildTag(data, "chiselingInput", registries);
            encodedOutputInv.readFromChildTag(data, "encodedOutputs", registries);
        } finally { isLoading = false; }
    }

    public void writeToNBT(net.minecraft.nbt.CompoundTag data, net.minecraft.core.HolderLookup.Provider registries) {
        data.putString("mode", this.mode.name());
        data.putBoolean("advancedMode", this.advancedMode);
        data.putBoolean("chiselingMode", this.chiselingMode);
        data.putBoolean("substitute", this.substitute);
        data.putBoolean("substituteFluids", this.substituteFluids);
        data.putBoolean("mergeSameItems", this.mergeSameItems);
        data.putBoolean("showUnmarkedDisks", this.showUnmarkedDisks);
        data.putBoolean("hideEmptySlots", this.hideEmptySlots);
        data.putLong("selectedSerial", this.selectedSerial);
        data.putString("searchScope", this.searchScope.name());
        data.putBoolean("naturalSort", this.naturalSort);
        data.putString("shownProviders", this.shownProviders.name());
        if (this.stonecuttingRecipeId != null) data.putString("stonecuttingRecipeId", this.stonecuttingRecipeId.toString());
        blankPatternInv.writeToNBT(data, "blankPattern", registries);
        encodedPatternInv.writeToNBT(data, "encodedPattern", registries);
        encodedInputInv.writeToChildTag(data, "encodedInputs", registries);
        chiselingInputInv.writeToChildTag(data, "chiselingInput", registries);
        encodedOutputInv.writeToChildTag(data, "encodedOutputs", registries);
    }

}
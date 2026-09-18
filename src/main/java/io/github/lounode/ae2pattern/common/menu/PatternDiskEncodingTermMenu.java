package io.github.lounode.ae2pattern.common.menu;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.PatternTermSlot;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.util.ConfigInventory;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic;
import io.github.lounode.ae2pattern.common.pattern.PatternClassifier;
import io.github.lounode.ae2pattern.common.pattern.PatternDiskContents;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;
import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskHostRegistry;
import io.github.lounode.ae2pattern.network.DiskListPayload;

// NEO ECO AE Extension integration
import cn.dancingsnow.neoecoae.api.PatternEncodingTermMenuExtension;
import cn.dancingsnow.neoecoae.api.IECOPatternStorageService;

/**
 * Menu for the pattern disk encoding terminal. Extends {@link MEStorageMenu} to inherit network
 * storage access (disk scanning) and the item terminal infrastructure.
 *
 * <p>Core encoding workflow mirrors AE2's {@code PatternEncodingTermMenu}: a mode-adaptive encoding
 * grid (crafting 3x3 / processing in-out / smithing / stonecutting), blank + encoded pattern slots,
 * and encode/clear client actions. On top of that, this terminal exposes the network pattern disk
 * list: click a disk to write the currently encoded pattern into it, shift-right-click to bind the
 * pattern's prefix to the disk (renaming it), middle-click to rename, and a mini search bar.</p>
 */
public class PatternDiskEncodingTermMenu extends MEStorageMenu implements PatternEncodingTermMenuExtension {

    private static final int CRAFTING_GRID_WIDTH = 3;
    private static final int CRAFTING_GRID_HEIGHT = 3;
    private static final int CRAFTING_GRID_SLOTS = CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT;

    private static final String ACTION_ENCODE = "encode";
    private static final String ACTION_CLEAR = "clear";
    private static final String ACTION_SET_MODE = "setMode";
    private static final String ACTION_CYCLE_PROCESSING_OUTPUT = "cycleProcessingOutput";
    private static final String ACTION_MULTIPLY_OUTPUT = "multiplyOutput";
    private static final String ACTION_DIVIDE_OUTPUT = "divideOutput";
    private static final String ACTION_TRANSFER_TO_DISK = "transferToDisk";
    private static final String ACTION_BIND_PREFIX = "bindPrefix";
    private static final String ACTION_RENAME_DISK = "renameDisk";
    private static final String ACTION_UPLOAD_PATTERN = "neoecoae:uploadPattern";

    // 不可用 build()：会将实例推入 AE2 的 InitMenuTypes 注册队列，与下方 MENUS DeferredRegister 形成同实例双通道注册，
    // 注册冲突即触发 NeoForge MappedRegistry 的 duplicate value 崩溃；其余三个菜单均用 buildUnregistered 单通道。
    public static final MenuType<PatternDiskEncodingTermMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskEncodingTermMenu::new, PatternDiskEncodingTerminalPart.class)
            .buildUnregistered(net.minecraft.resources.ResourceLocation.parse("ae2_pattern_disk:pattern_disk_encoding_terminal"));

    private final PatternDiskEncodingTerminalPart host;
    private final DiskEncodingLogic encodingLogic;
    private final ConfigInventory encodedInputsInv;
    private final ConfigInventory encodedOutputsInv;

    private final FakeSlot[] craftingGridSlots = new FakeSlot[9];
    private final FakeSlot[] processingInputSlots = new FakeSlot[AEProcessingPattern.MAX_INPUT_SLOTS];
    private final FakeSlot[] processingOutputSlots = new FakeSlot[AEProcessingPattern.MAX_OUTPUT_SLOTS];
    private final FakeSlot stonecuttingInputSlot;
    private final FakeSlot smithingTableTemplateSlot;
    private final FakeSlot smithingTableBaseSlot;
    private final FakeSlot smithingTableAdditionSlot;
    private final PatternTermSlot craftOutputSlot;
    private final RestrictedInputSlot blankPatternSlot;
    private final RestrictedInputSlot encodedPatternSlot;

    private RecipeHolder<CraftingRecipe> currentRecipe;
    private EncodingMode currentMode;

    @GuiSync(97)
    public EncodingMode mode = EncodingMode.CRAFTING;
    @GuiSync(96)
    public boolean substitute = false;
    @GuiSync(95)
    public boolean substituteFluids = true;
    @GuiSync(94)
    @Nullable
    public ResourceLocation stonecuttingRecipeId;

    /** 当前模式的标记（如 #mode:crafting），用于搜索栏自动填充。空字符串=无。由服务端在 broadcastChanges 中同步。 */
    @GuiSync(93)
    public String recipePrefix = "";

    /** 处理模式下同物品合并开关（true=启用，false=禁用）。 */
    @GuiSync(92)
    public boolean mergeSameItems = true;

    /**
     * 客户端侧：最近一次从 EMI/JEI 导入的配方类别 id。绑定标记时优先用它——它才是「这是一台什么
     * 机器」的答案，编码模式只是四个粗类。为空则退回编码模式。
     */
    @Nullable
    private String pendingRecipeCategory;

    /** 支持流体替换的合成网格槽位（用于 CraftingEncodingPanel 高亮）。 */
    public IntSet slotsSupportingFluidSubstitution = new IntArraySet();

    // ---- 磁盘列表同步（供应器扫描 <-> 客户端） ----

    /** 客户端接收到的磁盘列表（由 DiskListPayload 更新，供 Screen 渲染）。 */
    private List<DiskListPayload.DiskEntry> diskList = List.of();

    /** serial → 磁盘所在供应器槽位的反查表（仅服务端使用）。 */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<DiskRef> diskRefs = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long nextDiskSerial = Long.MIN_VALUE;
    /** 上次发送列表的指纹，避免无变化时重复全量推送。 */
    private int lastDiskFingerprint;
    private boolean fingerprintInitialized;

    private record DiskRef(IPatternDiskHost host, int slot) {
    }


    private final List<RecipeHolder<StonecutterRecipe>> stonecuttingRecipes = new java.util.ArrayList<>();

    public PatternDiskEncodingTermMenu(int id, Inventory ip, PatternDiskEncodingTerminalPart host) {
        super(TYPE, id, ip, host, true);
        this.host = host;
        this.encodingLogic = host.getLogic();
        this.encodedInputsInv = encodingLogic.getEncodedInputInv();
        this.encodedOutputsInv = encodingLogic.getEncodedOutputInv();

        var encodedInputs = encodedInputsInv.createMenuWrapper();
        var encodedOutputs = encodedOutputsInv.createMenuWrapper();

        // Crafting grid (3x3)
        for (int i = 0; i < CRAFTING_GRID_SLOTS; i++) {
            var slot = new FakeSlot(encodedInputs, i);
            slot.setHideAmount(true);
            this.addSlot(this.craftingGridSlots[i] = slot, SlotSemantics.CRAFTING_GRID);
        }
        this.addSlot(this.craftOutputSlot = new PatternTermSlot(), SlotSemantics.CRAFTING_RESULT);

        // Processing in/out
        for (int i = 0; i < processingInputSlots.length; i++) {
            this.addSlot(this.processingInputSlots[i] = new FakeSlot(encodedInputs, i), SlotSemantics.PROCESSING_INPUTS);
        }
        for (int i = 0; i < this.processingOutputSlots.length; i++) {
            this.addSlot(this.processingOutputSlots[i] = new FakeSlot(encodedOutputs, i),
                    SlotSemantics.PROCESSING_OUTPUTS);
        }

        // Stonecutting input
        this.addSlot(this.stonecuttingInputSlot = new FakeSlot(encodedInputs, 0), SlotSemantics.STONECUTTING_INPUT);
        this.stonecuttingInputSlot.setHideAmount(true);

        // Smithing inputs
        this.addSlot(this.smithingTableTemplateSlot = new FakeSlot(encodedInputs, 0),
                SlotSemantics.SMITHING_TABLE_TEMPLATE);
        this.smithingTableTemplateSlot.setHideAmount(true);
        this.addSlot(this.smithingTableBaseSlot = new FakeSlot(encodedInputs, 1), SlotSemantics.SMITHING_TABLE_BASE);
        this.smithingTableBaseSlot.setHideAmount(true);
        this.addSlot(this.smithingTableAdditionSlot = new FakeSlot(encodedInputs, 2),
                SlotSemantics.SMITHING_TABLE_ADDITION);
        this.smithingTableAdditionSlot.setHideAmount(true);

        // Blank + encoded pattern slots
        this.addSlot(
                this.blankPatternSlot = new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.BLANK_PATTERN,
                        encodingLogic.getBlankPatternInv(), 0),
                SlotSemantics.BLANK_PATTERN);
        this.addSlot(
                this.encodedPatternSlot = new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.ENCODED_PATTERN,
                        encodingLogic.getEncodedPatternInv(), 0),
                SlotSemantics.ENCODED_PATTERN);
        this.encodedPatternSlot.setStackLimit(1);

        registerClientAction(ACTION_ENCODE, this::encode);
        registerClientAction(ACTION_CLEAR, this::clear);
        registerClientAction(ACTION_SET_MODE, EncodingMode.class, encodingLogic::setMode);
        registerClientAction(ACTION_CYCLE_PROCESSING_OUTPUT, this::cycleProcessingOutput);
        registerClientAction(ACTION_MULTIPLY_OUTPUT, Integer.class, this::multiplyOutput);
        registerClientAction(ACTION_DIVIDE_OUTPUT, Integer.class, this::divideOutput);
        registerClientAction("setSubstitution", Boolean.class, encodingLogic::setSubstitution);
        registerClientAction("setFluidSubstitution", Boolean.class, encodingLogic::setFluidSubstitution);
        registerClientAction("setStonecuttingRecipeId", ResourceLocation.class,
                encodingLogic::setStonecuttingRecipeId);
        registerClientAction(ACTION_TRANSFER_TO_DISK, Long.class, this::transferToDisk);
        registerClientAction(ACTION_BIND_PREFIX, Long.class, this::bindPrefix);
        registerClientAction("setPendingRecipeCategory", String.class, this::setPendingRecipeCategory);
        registerClientAction(ACTION_RENAME_DISK, Long.class, this::renameDisk);
        registerClientAction("setMergeSameItems", Boolean.class, this::setMergeSameItems);
        registerClientAction(ACTION_UPLOAD_PATTERN, this::neoecoae$uploadPattern);

        updateStonecuttingRecipes();
    }

    // ---- Encoding ------------------------------------------------------------

    public void encode() {
        if (isClientSide()) {
            sendClientAction(ACTION_ENCODE);
            return;
        }
        ItemStack encodedPattern = encodePattern();
        if (encodedPattern != null) {
            var encodeOutput = this.encodedPatternSlot.getItem();
            if (!encodeOutput.isEmpty()
                    && !PatternDetailsHelper.isEncodedPattern(encodeOutput)
                    && !AEItems.BLANK_PATTERN.is(encodeOutput)) {
                return;
            } else if (encodeOutput.isEmpty()) {
                var blankPattern = this.blankPatternSlot.getItem();
                if (!isPattern(blankPattern)) {
                    return;
                }
                blankPattern.shrink(1);
                if (blankPattern.getCount() <= 0) {
                    this.blankPatternSlot.set(ItemStack.EMPTY);
                }
            }
            this.encodedPatternSlot.set(encodedPattern);
        } else {
            clearPattern();
        }
    }

    private void clearPattern() {
        var encodedPattern = this.encodedPatternSlot.getItem();
        if (PatternDetailsHelper.isEncodedPattern(encodedPattern)) {
            this.encodedPatternSlot.set(AEItems.BLANK_PATTERN.stack(encodedPattern.getCount()));
        }
    }

    @Nullable
    private ItemStack encodePattern() {
        return switch (this.mode) {
            case CRAFTING -> encodeCraftingPattern();
            case PROCESSING -> encodeProcessingPattern();
            case SMITHING_TABLE -> encodeSmithingTablePattern();
            case STONECUTTING -> encodeStonecuttingPattern();
        };
    }

    @Nullable
    private ItemStack encodeCraftingPattern() {
        var ingredients = new ItemStack[CRAFTING_GRID_SLOTS];
        boolean valid = false;
        for (int x = 0; x < ingredients.length; x++) {
            ingredients[x] = getEncodedCraftingIngredient(x);
            if (ingredients[x] == null) return null;
            else if (!ingredients[x].isEmpty()) valid = true;
        }
        if (!valid) return null;
        var result = getAndUpdateOutput();
        if (result.isEmpty() || currentRecipe == null) return null;
        return PatternDetailsHelper.encodeCraftingPattern(this.currentRecipe, ingredients, result, isSubstitute(),
                isSubstituteFluids());
    }

    @Nullable
    private ItemStack encodeProcessingPattern() {
        var inputs = new GenericStack[encodedInputsInv.size()];
        boolean valid = false;
        for (int slot = 0; slot < encodedInputsInv.size(); slot++) {
            inputs[slot] = encodedInputsInv.getStack(slot);
            if (inputs[slot] != null) valid = true;
        }
        if (!valid) return null;

        // 同物品合并：启用时相同 AEKey 的输入合并为单槽
        java.util.List<GenericStack> mergedInputs;
        if (isMergeSameItems()) {
            var byKey = new java.util.LinkedHashMap<AEKey, Long>();
            for (var in : inputs) {
                if (in != null) {
                    byKey.merge(in.what(), in.amount(), Long::sum);
                }
            }
            mergedInputs = new java.util.ArrayList<>();
            for (var e : byKey.entrySet()) {
                mergedInputs.add(new GenericStack(e.getKey(), e.getValue()));
            }
        } else {
            mergedInputs = new java.util.ArrayList<>(Arrays.asList(inputs));
            mergedInputs.removeIf(Objects::isNull);
        }

        var outputs = new GenericStack[encodedOutputsInv.size()];
        for (int slot = 0; slot < encodedOutputsInv.size(); slot++) {
            outputs[slot] = encodedOutputsInv.getStack(slot);
        }
        if (outputs[0] == null) return null;
        return PatternDetailsHelper.encodeProcessingPattern(mergedInputs, Arrays.asList(outputs));
    }

    @Nullable
    private ItemStack encodeSmithingTablePattern() {
        if (!(encodedInputsInv.getKey(0) instanceof AEItemKey template)
                || !(encodedInputsInv.getKey(1) instanceof AEItemKey base)
                || !(encodedInputsInv.getKey(2) instanceof AEItemKey addition)) {
            return null;
        }
        var input = new SmithingRecipeInput(template.toStack(), base.toStack(), addition.toStack());
        var level = getPlayer().level();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMITHING, input, level).orElse(null);
        if (recipe == null) return null;
        var output = AEItemKey.of(recipe.value().assemble(input, level.registryAccess()));
        return PatternDetailsHelper.encodeSmithingTablePattern(recipe, template, base, addition, output,
                encodingLogic.isSubstitution());
    }

    @Nullable
    private ItemStack encodeStonecuttingPattern() {
        if (stonecuttingRecipeId == null) return null;
        if (!(encodedInputsInv.getKey(0) instanceof AEItemKey input)) return null;
        var recipeInput = new SingleRecipeInput(input.toStack());
        var level = getPlayer().level();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.STONECUTTING, recipeInput, level,
                stonecuttingRecipeId).orElse(null);
        if (recipe == null) return null;
        var output = AEItemKey.of(recipe.value().getResultItem(level.registryAccess()));
        return PatternDetailsHelper.encodeStonecuttingPattern(recipe, input, output, encodingLogic.isSubstitution());
    }

    @Nullable
    private ItemStack getEncodedCraftingIngredient(int slot) {
        var what = encodedInputsInv.getKey(slot);
        if (what == null) return ItemStack.EMPTY;
        else if (what instanceof AEItemKey itemKey) return itemKey.toStack(1);
        else return null;
    }

    private boolean isPattern(ItemStack output) {
        return !output.isEmpty() && AEItems.BLANK_PATTERN.is(output);
    }

    private ItemStack getAndUpdateOutput() {
        var level = this.getPlayerInventory().player.level();
        var items = NonNullList.withSize(CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT, ItemStack.EMPTY);
        boolean invalidIngredients = false;
        for (int x = 0; x < items.size(); x++) {
            var stack = getEncodedCraftingIngredient(x);
            if (stack != null) items.set(x, stack);
            else invalidIngredients = true;
        }
        var input = CraftingInput.of(CRAFTING_GRID_WIDTH, CRAFTING_GRID_HEIGHT, items);
        if (this.currentRecipe == null || !this.currentRecipe.value().matches(input, level)) {
            this.currentRecipe = invalidIngredients ? null
                    : level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
            this.currentMode = this.mode;
            checkFluidSubstitutionSupport();
        }
        final ItemStack is;
        if (this.currentRecipe == null) is = ItemStack.EMPTY;
        else is = this.currentRecipe.value().assemble(input, level.registryAccess());
        this.craftOutputSlot.setResultItem(is);
        return is;
    }

    private void checkFluidSubstitutionSupport() {
        this.slotsSupportingFluidSubstitution.clear();

        if (this.currentRecipe == null) {
            return; // No recipe -> no substitution
        }

        var encodedPattern = encodePattern();
        if (encodedPattern != null) {
            var decodedPattern = PatternDetailsHelper.decodePattern(encodedPattern,
                    this.getPlayerInventory().player.level());
            if (decodedPattern instanceof AECraftingPattern craftingPattern) {
                for (int i = 0; i < craftingPattern.getSparseInputs().size(); i++) {
                    if (craftingPattern.getValidFluid(i) != null) {
                        slotsSupportingFluidSubstitution.add(i);
                    }
                }
            }
        }
    }

    // ---- Disk list interactions ---------------------------------------------

    /**
     * Writes the currently encoded pattern into the disk identified by its serial.
     * The disk lives in a PatternDiskProvider's disk inventory; after the write the provider
     * re-decodes its patterns, so the newly stored pattern becomes available to autocrafting.
     */
    public void transferToDisk(long serial) {
        if (isClientSide()) {
            sendClientAction(ACTION_TRANSFER_TO_DISK, serial);
            return;
        }
        var encoded = encodedPatternSlot.getItem();
        if (encoded.isEmpty() || !PatternDetailsHelper.isEncodedPattern(encoded)) {
            return;
        }

        var ref = diskRefs.get(serial);
        if (ref == null) return;
        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem disk)) return;

        // 接收判据（容量/锁定类型/主产物互斥）统一由 PatternDiskItem.canInsert/tryInsert 负责
        var level = getPlayer().level();
        var updated = stack.copy();
        if (disk.tryInsert(updated, encoded, level)) {
            inv.setItemDirect(ref.slot(), updated); // triggers host refresh
            // 样板已存入磁盘：编码槽清空，原编码样板回退为空白样板并按 样板槽→ME网→背包 优先级落位
            this.encodedPatternSlot.set(ItemStack.EMPTY);
            returnBlankPatternToStorage();
        }
    }

    /**
     * Binds the current recipe type to the disk identified by serial, as a mark in the disk's
     * {@code DISK_PREFIX} component. The disk's own name is left alone: the mark shows up in the disk's
     * tooltip instead of renaming the item, which used to make every disk of a kind look identical.
     */
    public void bindPrefix(long serial) {
        if (isClientSide()) {
            // The mark depends on the imported recipe's category, which only the client knows, so it travels
            // as its own action just ahead of the bind.
            var category = pendingRecipeCategory;
            sendClientAction("setPendingRecipeCategory", category == null ? "" : category);
            sendClientAction(ACTION_BIND_PREFIX, serial);
            return;
        }

        var ref = diskRefs.get(serial);
        if (ref == null) return;

        var mark = deriveMarkId();
        if (mark.isEmpty()) return;

        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) return;

        var updated = stack.copy();
        updated.set(io.github.lounode.ae2pattern.AEPatternRegistries.DISK_PREFIX, mark);
        inv.setItemDirect(ref.slot(), updated); // triggers host refresh
    }

    /**
     * The mark to bind: the imported recipe's category when there is one, otherwise the encoding mode.
     * Both are stored as {@code #}-prefixed identifiers so the tooltip can tell an identifier from the
     * plain text older disks carry.
     */
    public String deriveMarkId() {
        return pendingRecipeCategory != null && !pendingRecipeCategory.isEmpty()
                ? "#" + pendingRecipeCategory
                : modeMarkId(this.mode);
    }

    /** The mark standing for an encoding mode, for disks marked without an imported recipe. */
    public static String modeMarkId(EncodingMode mode) {
        return "#mode:" + mode.name().toLowerCase(Locale.ROOT);
    }

    /** Remembers the recipe category of the recipe just imported, for {@link #deriveMarkId()}. */
    public void setPendingRecipeCategory(@Nullable String categoryId) {
        this.pendingRecipeCategory = categoryId;
    }

    /**
     * Renames the disk identified by serial. 暂未实现独立命名 UI：重命名入口统一走
     * {@link #bindPrefix(long)}（潜行左键绑定配方类型标记），中键保留为未来扩展点。
     */
    public void renameDisk(long serial) {
        if (isClientSide()) {
            sendClientAction(ACTION_RENAME_DISK, serial);
            return;
        }
        // 未实现：需命名对话框 UI。当前标记路径 = 潜行左键 bindPrefix。
    }

    /**
     * 样板存入磁盘后，把回退产生的空白样板按 样板槽→ME网络→玩家背包 优先级落位。
     * 样板槽(blankPatternSlot)为空时优先回填，否则尝试插入 ME 网络存储，
     * 最后尝试放入玩家背包；均失败时留在编码槽下游由玩家手动取走。
     */
    private void returnBlankPatternToStorage() {
        // 1) 优先回填终端样板槽
        var blankSlotInv = encodingLogic.getBlankPatternInv();
        if (blankSlotInv.getStackInSlot(0).isEmpty()) {
            blankSlotInv.setItemDirect(0, AEPatternRegistries.blankPattern());
            broadcastChanges();
            return;
        }
        // 2) 插入 ME 网络存储
        var grid = getGrid();
        if (grid != null) {
            var storage = grid.getStorageService();
            if (storage != null) {
                var blank = appeng.api.stacks.AEItemKey.of(appeng.core.definitions.AEItems.BLANK_PATTERN);
                long inserted = storage.getInventory().insert(blank, 1,
                        appeng.api.config.Actionable.MODULATE, appeng.api.networking.security.IActionSource.ofPlayer(getPlayer()));
                if (inserted > 0) {
                    broadcastChanges();
                    return;
                }
            }
        }
        // 3) 放入玩家背包
        if (getPlayer().getInventory().add(AEPatternRegistries.blankPattern())) {
            broadcastChanges();
            return;
        }
        // 4) 均失败：留在编码槽（不丢失）
        broadcastChanges();
    }

    // ---- NEO ECO AE Extension upload ----------------------------------------

    /**
     * Uploads the currently encoded pattern to the NEO ECO computation cluster's IECOPatternStorageService.
     * Mirrors the behaviour of NEO ECO's own {@code PatternEncodingTermMenuMixin.neoecoae()}:
     * reads the encoded pattern slot, inserts it via the grid service, and on success clears the slot
     * and returns a blank pattern to storage/network.
     */
    @Override
    public void neoecoae$uploadPattern() {
        if (isClientSide()) {
            sendClientAction(ACTION_UPLOAD_PATTERN);
            return;
        }
        var node = getGridNode();
        if (node == null || !node.isActive()) return;
        var grid = node.getGrid();
        if (grid == null) return;

        var encoded = encodedPatternSlot.getItem();
        if (encoded.isEmpty() || !PatternDetailsHelper.isEncodedPattern(encoded)) return;

        var service = grid.getService(IECOPatternStorageService.class);
        if (service != null && service.getPatternStorage().insertPattern(encoded.copy())) {
            // Upload succeeded: clear the encoded slot, return a blank pattern
            this.encodedPatternSlot.set(ItemStack.EMPTY);
            returnBlankPatternToStorage();
        }
    }

    // ---- 磁盘列表同步（服务端扫描 <-> 客户端渲染） ----

    /**
     * 服务端：扫描网格中所有样板磁盘宿主（ME样板磁盘供应器、批处理装配室等）的磁盘槽，
     * 指纹变化时重建 serial 映射并推送全量列表到客户端。serial 在本菜单生命周期内稳定
     * 映射到 (磁盘宿主, 槽位)。
     */
    private void syncDiskList() {
        // 收集当前网格中所有磁盘宿主的磁盘槽（item 类型为 PatternDiskItem 的非空槽）
        var grid = getGrid();
        var slots = new java.util.ArrayList<DiskRef>();
        if (grid != null) {
            for (var machineClass : grid.getMachineClasses()) {
                if (machineClass == null || !IPatternDiskHost.class.isAssignableFrom(machineClass)) {
                    continue;
                }
                for (var machine : grid.getActiveMachines(machineClass)) {
                    if (!(machine instanceof IPatternDiskHost host)) continue;
                    collectHostDisks(host, slots);
                }
            }

            // Hosts contributed by integrations: machines from other mods cannot implement
            // IPatternDiskHost at compile time, so they register a collector instead.
            for (var host : PatternDiskHostRegistry.collectExtra(grid)) {
                collectHostDisks(host, slots);
            }
        }

        int fingerprint = computeDiskFingerprint(slots);
        if (fingerprintInitialized && fingerprint == lastDiskFingerprint) {
            return; // unchanged: skip full resend
        }
        lastDiskFingerprint = fingerprint;
        fingerprintInitialized = true;

        // Rebuild serial mapping and full packet
        diskRefs.clear();
        var entries = new java.util.ArrayList<DiskListPayload.DiskEntry>();
        for (var ref : slots) {
            var serial = nextDiskSerial++;
            diskRefs.put(serial, ref);
            var stack = ref.host().getDiskInventory().getStackInSlot(ref.slot());
            entries.add(new DiskListPayload.DiskEntry(serial, stack.copy()));
        }
        sendPacketToClient(new DiskListPayload(entries));
    }

    /** Appends every pattern disk currently sitting in {@code host}'s disk inventory. */
    private static void collectHostDisks(IPatternDiskHost host, List<DiskRef> slots) {
        var inv = host.getDiskInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) continue;
            slots.add(new DiskRef(host, i));
        }
    }

    /**
     * Fingerprint over the set of disk slots: item id, slot index, host position and the host's own
     * identity salt (two panels on one cable share a position).
     */
    private static int computeDiskFingerprint(List<DiskRef> slots) {
        int hash = 1;
        for (var ref : slots) {
            var stack = ref.host().getDiskInventory().getStackInSlot(ref.slot());
            hash = 31 * hash + net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(stack.getItem());
            hash = 31 * hash + stack.getComponentsPatch().hashCode();
            hash = 31 * hash + ref.host().getBlockPos().hashCode();
            hash = 31 * hash + ref.host().getIdentitySalt();
            hash = 31 * hash + ref.slot();
        }
        return hash;
    }

    /**
     * 返回当前网格，或 null（未连接/未激活）。
     */
    @Nullable
    private IGrid getGrid() {
        var node = getGridNode();
        return node != null && node.isActive() ? node.getGrid() : null;
    }

    /**
     * 客户端：接收服务端推送的磁盘列表，供 Screen 每帧渲染。
     */
    public void receiveDiskList(List<DiskListPayload.DiskEntry> disks) {
        this.diskList = disks;
    }

    /**
     * 客户端：当前已知磁盘列表（serial + ItemStack）。
     */
    public List<DiskListPayload.DiskEntry> getDiskList() {
        return diskList;
    }

    /**
     * Returns the mark of the current encoding mode, for disks bound without an imported recipe category.
     */
    @Nullable
    public String getCurrentRecipePrefix() {
        if (isClientSide()) {
            return this.recipePrefix.isEmpty() ? null : this.recipePrefix;
        }
        return modeMarkId(this.mode);
    }

    @Nullable
    private String resolveCurrentRecipePrefix() {
        // 兼容旧调用：当前模式的标记即当前前缀语义
        return modeMarkId(this.mode);
    }

    // ---- Accessors -----------------------------------------------------------

    @Override
    public void setItem(int slotID, int stateId, ItemStack stack) {
        super.setItem(slotID, stateId, stack);
        this.getAndUpdateOutput();
    }

    @Override
    public void initializeContents(int stateId, List<ItemStack> items, ItemStack carried) {
        super.initializeContents(stateId, items, carried);
        this.getAndUpdateOutput();
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (isServerSide()) {
            if (this.mode != encodingLogic.getMode()) {
                this.setMode(encodingLogic.getMode());
            }
            this.substitute = encodingLogic.isSubstitution();
            this.substituteFluids = encodingLogic.isFluidSubstitution();
            this.stonecuttingRecipeId = encodingLogic.getStonecuttingRecipeId();
            this.recipePrefix = Objects.toString(resolveCurrentRecipePrefix(), "");
            syncDiskList();
        }
    }

    @Override
    public void onServerDataSync(it.unimi.dsi.fastutil.shorts.ShortSet updatedFields) {
        super.onServerDataSync(updatedFields);
        for (var slot : craftingGridSlots) slot.setActive(mode == EncodingMode.CRAFTING);
        craftOutputSlot.setActive(mode == EncodingMode.CRAFTING);
        for (var slot : processingInputSlots) slot.setActive(mode == EncodingMode.PROCESSING);
        for (var slot : processingOutputSlots) slot.setActive(mode == EncodingMode.PROCESSING);
        if (this.currentMode != this.mode) {
            this.encodingLogic.setMode(this.mode);
            this.getAndUpdateOutput();
            this.updateStonecuttingRecipes();
        }
    }

    @Override
    public void onSlotChange(Slot s) {
        if (s == this.stonecuttingInputSlot) {
            updateStonecuttingRecipes();
        }
        if (s == this.encodedPatternSlot && isServerSide()) {
            this.broadcastChanges();
        }
    }

    private void updateStonecuttingRecipes() {
        stonecuttingRecipes.clear();
        if (encodedInputsInv.getKey(0) instanceof AEItemKey itemKey) {
            var level = getPlayer().level();
            var recipeInput = new SingleRecipeInput(itemKey.toStack());
            stonecuttingRecipes.addAll(level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, recipeInput,
                    level));
        }
        if (stonecuttingRecipeId != null
                && stonecuttingRecipes.stream().noneMatch(r -> r.id().equals(stonecuttingRecipeId))) {
            stonecuttingRecipeId = null;
        }
    }

    /**
     * 在配方转移未把玩家认为的主输出放入正确槽位时，轮换已编码的处理输出。
     */
    public void cycleProcessingOutput() {
        if (isClientSide()) {
            sendClientAction(ACTION_CYCLE_PROCESSING_OUTPUT);
        } else {
            if (mode != EncodingMode.PROCESSING) {
                return;
            }
            // 仅有 0/1 个非空输出时无可轮换，直接返回，避免单输出被清空
            if (!canCycleProcessingOutputs()) {
                return;
            }

            var newOutputs = new ItemStack[getProcessingOutputSlots().length];
            for (int i = 0; i < processingOutputSlots.length; i++) {
                newOutputs[i] = ItemStack.EMPTY;
                if (!processingOutputSlots[i].getItem().isEmpty()) {
                    // Search for the next, skipping empty slots
                    for (int j = 1; j < processingOutputSlots.length; j++) {
                        var nextItem = processingOutputSlots[(i + j) % processingOutputSlots.length].getItem();
                        if (!nextItem.isEmpty()) {
                            newOutputs[i] = nextItem;
                            break;
                        }
                    }
                }
            }

            for (int i = 0; i < newOutputs.length; i++) {
                processingOutputSlots[i].set(newOutputs[i]);
            }
        }
    }

    // 仅当已编码多个处理输出时可轮换
    public boolean canCycleProcessingOutputs() {
        return mode == EncodingMode.PROCESSING
                && Arrays.stream(processingOutputSlots).filter(s -> !s.getItem().isEmpty()).count() > 1;
    }

    /**
     * 将处理配方的全部输入与输出（含主副产物）堆叠数量乘以指定倍数。
     */
    public void multiplyOutput(int factor) {
        if (isClientSide()) {
            sendClientAction(ACTION_MULTIPLY_OUTPUT, factor);
            return;
        }
        if (mode != EncodingMode.PROCESSING || factor <= 0) {
            return;
        }
        multiplyInventory(encodedInputsInv, factor);
        multiplyInventory(encodedOutputsInv, factor);
        broadcastChanges();
    }

    /**
     * 将处理配方的全部输入与输出（含主副产物）堆叠数量除以指定倍数。
     * 全有或全无：任一物品数量无法整除时整体不做处理。
     */
    public void divideOutput(int factor) {
        if (isClientSide()) {
            sendClientAction(ACTION_DIVIDE_OUTPUT, factor);
            return;
        }
        if (mode != EncodingMode.PROCESSING || factor <= 0) {
            return;
        }
        // 先校验两个库存中全部非空物品均可整除，任一不满足则整体不处理
        for (var inv : new ConfigInventory[] { encodedInputsInv, encodedOutputsInv }) {
            for (int i = 0; i < inv.size(); i++) {
                var stack = inv.getStack(i);
                if (stack == null) {
                    continue;
                }
                if (stack.amount() <= 0 || stack.amount() % factor != 0) {
                    return;
                }
            }
        }
        divideInventory(encodedInputsInv, factor);
        divideInventory(encodedOutputsInv, factor);
        broadcastChanges();
    }

    /** 按槽位遍历倍乘全部非空堆叠，避免压缩索引错位。 */
    private static void multiplyInventory(ConfigInventory inv, int factor) {
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null) {
                inv.setStack(i, new GenericStack(stack.what(), stack.amount() * factor));
            }
        }
    }

    /** 按槽位遍历倍除全部非空堆叠，避免压缩索引错位。 */
    private static void divideInventory(ConfigInventory inv, int factor) {
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null) {
                inv.setStack(i, new GenericStack(stack.what(), stack.amount() / factor));
            }
        }
    }

    public void clear() {
        if (isClientSide()) {
            sendClientAction(ACTION_CLEAR);
            return;
        }
        encodedInputsInv.clear();
        encodedOutputsInv.clear();
        this.broadcastChanges();
        this.getAndUpdateOutput();
    }

    public EncodingMode getMode() { return this.mode; }

    public void setMode(EncodingMode mode) {
        if (this.mode != mode && mode == EncodingMode.STONECUTTING) {
            updateStonecuttingRecipes();
        }
        if (isClientSide()) {
            sendClientAction(ACTION_SET_MODE, mode);
        } else {
            this.mode = mode;
        }
    }

    public boolean isSubstitute() { return this.substitute; }
    public void setSubstitute(boolean v) {
        if (isClientSide()) sendClientAction("setSubstitution", v); else this.encodingLogic.setSubstitution(v);
    }
    public boolean isSubstituteFluids() { return this.substituteFluids; }
    public void setSubstituteFluids(boolean v) {
        if (isClientSide()) sendClientAction("setFluidSubstitution", v); else this.encodingLogic.setFluidSubstitution(v);
    }
    public boolean isMergeSameItems() { return this.mergeSameItems; }
    public void setMergeSameItems(boolean v) {
        if (isClientSide()) {
            sendClientAction("setMergeSameItems", v);
        } else {
            this.mergeSameItems = v;
        }
    }
    public @Nullable ResourceLocation getStonecuttingRecipeId() { return stonecuttingRecipeId; }
    public void setStonecuttingRecipeId(ResourceLocation id) {
        if (isClientSide()) sendClientAction("setStonecuttingRecipeId", id); else this.encodingLogic.setStonecuttingRecipeId(id);
    }

    @Override
    protected int transferStackToMenu(ItemStack input) {
        int initialCount = input.getCount();
        if (blankPatternSlot.mayPlace(input)) {
            input = blankPatternSlot.safeInsert(input);
            if (input.isEmpty()) return initialCount;
        }
        if (encodedPatternSlot.mayPlace(input)) {
            input = encodedPatternSlot.safeInsert(input);
            if (input.isEmpty()) return initialCount;
        }
        int transferred = initialCount - input.getCount();
        return transferred + super.transferStackToMenu(input);
    }

    public FakeSlot[] getCraftingGridSlots() { return craftingGridSlots; }
    public FakeSlot[] getProcessingInputSlots() { return processingInputSlots; }
    public FakeSlot[] getProcessingOutputSlots() { return processingOutputSlots; }
    public FakeSlot getStonecuttingInputSlot() { return stonecuttingInputSlot; }
    public FakeSlot getSmithingTableTemplateSlot() { return smithingTableTemplateSlot; }
    public FakeSlot getSmithingTableBaseSlot() { return smithingTableBaseSlot; }
    public FakeSlot getSmithingTableAdditionSlot() { return smithingTableAdditionSlot; }
    public List<RecipeHolder<StonecutterRecipe>> getStonecuttingRecipes() { return stonecuttingRecipes; }
    public PatternDiskEncodingTerminalPart getHostPart() { return host; }
}
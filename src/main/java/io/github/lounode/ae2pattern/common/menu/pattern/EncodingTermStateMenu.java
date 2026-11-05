package io.github.lounode.ae2pattern.common.menu.pattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import io.github.lounode.ae2pattern.config.AEPDCommonConfig;
import io.github.lounode.ae2pattern.integration.ae2lt.OverloadPatterns;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.menu.SlotSemantic;
import appeng.menu.slot.AppEngSlot;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.IConfigManager;
import appeng.menu.ISubMenu;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.slot.FakeSlot;
import appeng.menu.slot.PatternTermSlot;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.util.ConfigInventory;

import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;
import io.github.lounode.ae2pattern.integration.polymorph.PolymorphCompat;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingPatternEncoder;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic;
import io.github.lounode.ae2pattern.common.menu.slot.BlankPatternSlot;
import io.github.lounode.ae2pattern.common.menu.slot.PatternOutputSlot;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;
import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.api.PatternClassifier;
import io.github.lounode.ae2pattern.network.DiskListPayload;
import io.github.lounode.ae2pattern.common.menu.AbstractPatternDiskTermMenu;
import io.github.lounode.ae2pattern.common.menu.IPatternDiskTerminalHost;

/**
 * 编码终端的「磁盘清单、标记、同步覆写」中间父类。
 *
 * <p>与 {@link EncodingTierMenuState} 的分工：那一层持有 {@code @GuiSync} 档位字段与档位状态机，
 * 这一层持有磁盘清单的状态（{@code diskIndex} / {@code diskList} / 标记与候选数组）、槽位数组、
 * 以及每 tick 的同步覆写；最下面的具体菜单只留静态工厂、构造期的槽位与动作登记、编码流程、
 * 升级槽覆写、接口访问器与快捷移动。</p>
 *
 * <p>本类**不** {@code implements IPatternEncodingHost}：接口由具体菜单实现，它没有的接口方法
 * 由上面两层父类的公开方法继承满足。因此这里没有 {@code @Override} 的接口实现。</p>
 *
 * <p>只向具体菜单要两样构造期才有的东西：{@link #getAndUpdateOutput()} 与
 * {@link #refillBlankPatternSlot()}。其余状态都在本类或上一层，不回头摸菜单。</p>
 */
public abstract class EncodingTermStateMenu extends EncodingTierMenuState {

    protected final FakeSlot[] craftingGridSlots = new FakeSlot[9];
    protected final FakeSlot[] processingInputSlots = new FakeSlot[AEProcessingPattern.MAX_INPUT_SLOTS];
    protected final FakeSlot[] processingOutputSlots = new FakeSlot[AEProcessingPattern.MAX_OUTPUT_SLOTS];
    protected FakeSlot stonecuttingInputSlot;
    protected FakeSlot chiselingInputSlot;
    protected FakeSlot smithingTableTemplateSlot;
    protected FakeSlot smithingTableBaseSlot;
    protected FakeSlot smithingTableAdditionSlot;
    protected PatternTermSlot craftOutputSlot;
    protected PatternDock dock;

    protected RecipeHolder<CraftingRecipe> currentRecipe;
    protected EncodingMode currentMode;

    public IntSet slotsSupportingFluidSubstitution = new IntArraySet();

    protected List<DiskListPayload.DiskEntry> diskList = List.of();
    protected long diskListRevision;
    protected final DiskIndex diskIndex = new DiskIndex();
    protected PatternDiskTransfer transfer;
    protected DiskMarkBinder binder;

    protected EncodingTermStateMenu(MenuType<?> menuType, int id, Inventory ip,
            IPatternDiskTerminalHost host) {
        super(menuType, id, ip, host);
    }

    /** 产物重算依赖菜单里那份 {@code PatternEncodingLogic}，所以留由具体菜单实现。 */
    protected abstract ItemStack getAndUpdateOutput();

    /** 空白样板补料依赖编码槽与网络，留由具体菜单实现。 */
    protected abstract void refillBlankPatternSlot();

    protected static final String ACTION_ENCODE = "encode";
    protected static final String ACTION_CLEAR = "clear";
    protected static final String ACTION_CYCLE_PROCESSING_OUTPUT = "cycleProcessingOutput";
    /**
     * 轮换处理样板的输入顺序。与输出轮换是两个动作名：{@code 名字即协议}，同一个名字不能被两件事复用，
     * 也不能靠加参数改变已有动作的负载形状。
     */
    protected static final String ACTION_CYCLE_PROCESSING_INPUT = "cycleProcessingInput";
    protected static final String ACTION_MULTIPLY_OUTPUT = "multiplyOutput";
    protected static final String ACTION_DIVIDE_OUTPUT = "divideOutput";
    protected static final String ACTION_TRANSFER_TO_DISK = "transferToDisk";
    protected static final String ACTION_EXTRACT_FROM_DISK = "extractFromDisk";

    /** {@link ExtractRequest#index} 取这个值表示「整张磁盘」，而不是盘里第几张样板。 */
    public static final int WHOLE_DISK = -1;

    /** 从磁盘取出来的东西放哪。 */
    public enum ExtractTarget {
        /** 光标上（左键）。 */
        CURSOR,
        /** 玩家背包（Shift+左键）。 */
        INVENTORY,
        /** 样板编辑槽（右键一张样板）。 */
        ENCODED_SLOT
    }

    /**
     * 「从磁盘取东西」的客户端动作参数。
     *
     * <p>AE2 的客户端动作把参数当 JSON 传（见 {@code AEBaseMenu#registerClientAction}），所以这里用一组公开
     * 字段加无参构造器，而不是 record：不依赖 GSON 对 record 的支持。</p>
     */
    public static final class ExtractRequest {
        public long serial;
        public int index;
        public ExtractTarget target;

        public ExtractRequest() {
        }

        public ExtractRequest(long serial, int index, ExtractTarget target) {
            this.serial = serial;
            this.index = index;
            this.target = target;
        }
    }
    protected static final String ACTION_BIND_PREFIX = "bindPrefix";
    protected static final String ACTION_UPLOAD_PATTERN = "neoecoae:uploadPattern";

    public static volatile java.util.function.Consumer<PatternDiskEncodingTermMenu> uploadHandler;

    // ---- Disk list interactions ---------------------------------------------

    /** 候选为空时的哨兵：直接拿空数组当值传，不必每处新建。 */
    protected static final long[] NO_DISKS = new long[0];

    /**
     * 把改好的磁盘写回槽位，并立即把列表推给客户端。
     *
     * <p>写入不再依赖 {@link #syncDiskList()} 的指纹比对去到达客户端：那条路曾被看到落后于写入，
     * 而玩家刚刚标记完磁盘、眼里却没变化时，只会得出「这次右键没生效」的结论。传入调用方已经解析好的
     * 库存，免得写回时再解析一次。</p>
     */
    void writeDiskSlot(InternalInventory inv, int slot, ItemStack updated) {
        inv.setItemDirect(slot, updated); // triggers host refresh
        syncDiskList(true);
    }

    // ---- 磁盘写入与取出：客户端发动作，服务端交给 PatternDiskTransfer ----------------------

    /** 把当前编码好的样板写进指定序列号的磁盘（客户端只发动作，判定全在服务端）。 */
    public void transferToDisk(long serial) {
        if (isClientSide()) {
            sendClientAction(ACTION_TRANSFER_TO_DISK, serial);
            return;
        }
        transfer.transferTo(new long[] { serial });
    }

    /** 把某张磁盘整张取出来（管理终端左键）。 */
    public void extractDisk(long serial, ExtractTarget target) {
        requestExtract(serial, WHOLE_DISK, target);
    }

    /** 把某张盘里第 {@code index} 张样板取出来（左键 / Shift+左键 / 右键）。 */
    public void extractPattern(long serial, int index, ExtractTarget target) {
        requestExtract(serial, index, target);
    }

    private void requestExtract(long serial, int index, ExtractTarget target) {
        if (isClientSide()) {
            sendClientAction(ACTION_EXTRACT_FROM_DISK, new ExtractRequest(serial, index, target));
            return;
        }
        transfer.extract(new ExtractRequest(serial, index, target));
    }

    /** 服务端入口（注册给 AE2 的客户端动作）。 */
    protected void handleExtractFromDisk(ExtractRequest request) {
        transfer.extract(request);
    }

    @Override
    protected FakeSlot chiselingInputSlot() {
        return chiselingInputSlot;
    }

    /**
     * 自动写盘成功后顺手把标记绑成当前工作方块——只在盘上还没有标记时（理由见 {@link DiskMarkBinder}）。
     */
    protected void bindMarkIfUnmarked(long serial) {
        binder.bindIfUnmarked(serial, markSource());
    }

    public void bindPrefix(long serial) {
        bindPrefix(serial, null);
    }

    private void bindPrefix(long serial, @Nullable String markOverride) {
        if (isClientSide()) {
            // 标记取决于导入的配方类别，只有客户端知道，所以在 bind 之前单独送一趟。
            var category = pendingRecipeCategory;
            sendClientAction("setPendingRecipeCategory", category == null ? "" : category);
            sendClientAction(ACTION_BIND_PREFIX, serial);
            return;
        }
        binder.bind(serial, markOverride, markSource());
    }

    /** 两个样板槽与它们那本账（同包的协作类要读它）。 */
    @Override
    protected PatternDock dock() {
        return dock;
    }

    /** 这次绑定该依据的三个值：快照出去，绑定逻辑就不必回头认菜单。 */
    private DiskMarkBinder.Source markSource() {
        return new DiskMarkBinder.Source(pendingRecipeCategory, mode, chiselingMode);
    }

    /** 要绑到磁盘上的标记，口径见 {@link DiskMarkBinder}。 */
    public String deriveMarkId() {
        return binder.deriveMarkId(markSource());
    }


    /** The mark standing for an encoding mode, for disks marked without an imported recipe. */
    public void setPendingRecipeCategory(@Nullable String categoryId) {
        this.pendingRecipeCategory = categoryId;
    }

    /**
     * 导入配方（JEI/EMI 配方页的「编写样板」）的最后一步：记下这次导入的类别与一次导入事实，供搜索栏决定要不要填自己。
     *
     * <p>搜索栏的自动填充只认这一个入口。绑定标记、切换模式、放样板回流同样会改掉标记的值，但它们都不该
     * 动玩家正在用的搜索条件——所以判据是「发生了导入」，而不是「标记值变了」。</p>
     *
     * <p>用修订号而不是回调屏幕：导入那一下由配方页的转写处理器执行，它拿不到终端屏幕（也不该去拿）。
     * 修订号只记事实，屏幕下一帧自行读取，两边互不依赖对方存在。</p>
     */
    public void noteCategoryImported(@Nullable String categoryId) {
        this.lastImportedCategory = categoryId;
        this.categoryImportRevision++;
    }

    /** 导入配方发生过多少次；{@code 0} 表示还没导入过（屏幕据此判断「首次打开不填」）。 */
    public int getCategoryImportRevision() {
        return this.categoryImportRevision;
    }

    /**
     * 最近一次导入的类别，没导入过、或者那次没取到类别时是 {@code null}。填充用的是它而不是「当前类别」：
     * 后者在导入之后还可能被右键（光标上的工作方块）改掉、或被换模式清空，快照下来才能保证填的就是这次导入。
     */
    @Nullable
    public String getLastImportedCategory() {
        return this.lastImportedCategory;
    }

    private int categoryImportRevision;
    @Nullable
    private String lastImportedCategory;

    /**
     * 刚导入的配方类别（只有客户端知道），或者 {@code null}。给「光标上拿工作方块右键」当取舍提示用：光标上那个
     * 工作方块能跑这个类别时就用它——比同命名空间之类的启发式更贴切。
     */
    @Nullable
    public String getPendingRecipeCategory() {
        return pendingRecipeCategory;
    }

    /**
     * 客户端：写盘顺位候选（serial 按尝试顺序）。空数组表示这次编码不自动写盘。
     *
     * <p>候选与顺序只有客户端知道（屏幕上的列表、右键选中的那张盘都在那边），服务端拿到了就按顺序试，
     * 不再自己猜“该不该写”。</p>
     */
    protected long[] clientAutoDisks = NO_DISKS;

    /** @see #clientAutoDisks */
    public void setClientAutoDisks(long[] serials) {
        this.clientAutoDisks = serials == null ? NO_DISKS : serials;
    }

    /**
     * 服务端：客户端报上来的候选，与 {@code ACTION_ENCODE} 成对使用，用后清空。
     */
    protected long[] pendingAutoDisks = NO_DISKS;

    /** @see #pendingAutoDisks */
    protected void setPendingAutoDisks(long[] serials) {
        this.pendingAutoDisks = serials == null ? NO_DISKS : serials;
    }

    /** 客户端要打到磁盘上的标记文本（右键打标，管理终端为 Shift+右键），与 {@link #bindSearchMark(long)} 成对使用。 */
    private String pendingMarkText;

    /** Remembers the mark text the client resolved, for {@link #bindSearchMark(long)}. */
    public void setPendingMarkText(@Nullable String text) {
        this.pendingMarkText = text;
    }

    /**
     * 把搜索栏里写的标记打到磁盘上（右键打标，管理终端为 Shift+右键）；文本为空则清掉这张盘的标记。文本带不带 # 前缀
     * 都行，统一按标记存。与 {@link #bindPrefix(long)} 不同，这条标记不来自导入的配方，而是玩家自己
     * 写/搜出来的，所以它能把任意一类标记标到任意一张盘上。
     */
    public void bindSearchMark(long serial) {
        if (isClientSide()) {
            sendClientAction("setPendingMarkText", pendingMarkText == null ? "" : pendingMarkText);
            sendClientAction("bindSearchMark", serial);
            return;
        }

        // 一次操作一个值：留着会让下一次只发 bindSearchMark、没发 setPendingMarkText 的调用沿用旧文本。
        var text = pendingMarkText;
        pendingMarkText = null;
        // null 表示客户端根本没设过值（误用），空串表示要清标记，两者不能混。
        if (text == null) return;

        // 值来自客户端，服务端自己收紧：去掉控制字符与 §，再裁掉首尾空白。
        text = DiskMarkRules.sanitizeName(text.strip());
        // 只有“#”一个字符不算标记，当成空——否则盘上会多出一个看不出内容的标记。
        String mark = text.isEmpty() ? null : (text.startsWith("#") ? text : "#" + text);
        if (mark != null && mark.length() > DiskMarkRules.MAX_DISK_NAME_LENGTH) {
            mark = mark.substring(0, DiskMarkRules.MAX_DISK_NAME_LENGTH);
        }
        if (mark != null && mark.length() <= 1) {
            mark = null;
        }

        var ref = diskIndex.refOf(serial);
        if (ref == null) return;
        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) return;

        // 没什么可清的就别写：对无标记的盘重复打标会白白重发一次列表。
        if (mark == null && stack.get(AEPatternRegistries.DISK_PREFIX.get()) == null) return;

        var updated = stack.copy();
        if (mark == null) {
            updated.remove(AEPatternRegistries.DISK_PREFIX);
        } else {
            updated.set(AEPatternRegistries.DISK_PREFIX, mark);
        }
        writeDiskSlot(inv, ref.slot(), updated);
    }

    /** 样板存进磁盘后，把回退产生的空白样板还回去（兜底链见 {@link PatternDock}）。 */
    void returnBlankPatternToStorage() {
        dock.returnBlank();
    }

    // ---- NEO ECO AE Extension upload ----------------------------------------

    /**
     * Uploads the currently encoded pattern to the NEO ECO computation cluster.
     * Delegates to the integration-registered handler when neoecoae is present;
     * otherwise does nothing.
     */
    public void uploadPattern() {
        if (isClientSide()) {
            sendClientAction(ACTION_UPLOAD_PATTERN);
            return;
        }
        var h = uploadHandler;
        if (h != null) {
            // 处理器按具体菜单类型登记（见 NeoECOIntegration）；运行时 this 只会是那个具体菜单。
            h.accept((PatternDiskEncodingTermMenu) this);
        }
    }

    /**
     * @return the item in the encoded pattern slot.
     */
    public ItemStack getEncodedPatternItem() {
        return dock.encoded();
    }

    /**
     * 编码槽本身。ExtendedAE Plus 的「上传到供应器」要在服务端直接读/清这个槽，见
     * {@code integration.extendedae_plus.ExtendedAEPlusUploadMenu}。
     */
    public Slot getEncodedPatternSlot() {
        return dock.encodedSlot();
    }

    /**
     * Clears the encoded pattern slot and returns a blank pattern to storage / the player's inventory.
     */
    public void clearEncodedPatternAndReturnBlank() {
        dock.setEncoded(ItemStack.EMPTY);
        returnBlankPatternToStorage();
    }

    /**
     * 上传成功后清空编码槽。
     *
     * <p>与 {@link #clearEncodedPatternAndReturnBlank()} 的区别在“退多少”：上传方知道这次是被容器吃掉了
     * （物品真的没了，得补），还是只被搬进了槽位（物品还在网络里，补一张就是凭空多造），所以由它决定。
     *
     * @param replacement 退还的内容；空表示不用退
     */
    public void clearEncodedPatternAfterUpload(ItemStack replacement) {
        dock.setEncoded(ItemStack.EMPTY);
        dock.returnToStorage(replacement);
    }

    // ---- 磁盘列表同步（服务端扫描 <-> 客户端渲染） ----

    /**
     * 服务端：扫描网格中所有样板磁盘宿主（ME样板磁盘供应器、批处理装配室等）的磁盘槽，
     * 指纹变化时重建 serial 映射并推送全量列表到客户端。serial 按“宿主位置 + 身份盐 + 槽位”
     * 这一值身份在菜单生命周期内稳定映射到同一个磁盘槽，与宿主适配器是否被重建无关。
     */

    private void syncDiskList() {
        syncDiskList(false);
    }

    /**
     * @param force re-send even when the fingerprint says nothing changed. The client asks for this before
     *              reading a disk's components for an action, because a mark written a moment ago may not
     *              have reached it yet.
     */
    void syncDiskList(boolean force) {
        var entries = diskIndex.rebuild(collectDiskHosts(), force);
        if (entries == null) {
            return; // unchanged: skip full resend
        }
        sendPacketToClient(new DiskListPayload(entries));
        onDiskListRebuilt(entries);
    }

    /**
     * 子类扩展点：磁盘列表（连同序列号 ↔ 宿主映射）刚重建完。父类发的是扁平清单，子类可以再发它自己的视图数据。
     */
    protected void onDiskListRebuilt(java.util.List<DiskListPayload.DiskEntry> entries) {
    }

    /** 子类用：磁盘序列号对应的宿主；未知返回 {@code null}。索引是私有的，这里只开只读口。 */
    protected @org.jetbrains.annotations.Nullable IPatternDiskHost diskHostOf(long serial) {
        return diskIndex.hostOf(serial);
    }

    /** 子类用：磁盘序列号在宿主库存里的槽位；未知返回 -1。 */
    protected int diskSlotOf(long serial) {
        return diskIndex.slotOf(serial);
    }

    /**
     * Re-sends the disk list even when nothing seems to have changed. The client asks for this before an
     * action that reads a disk's components, since a mark written a moment ago may not have reached it yet.
     */
    public void refreshDiskList() {
        if (isClientSide()) {
            sendClientAction("refreshDiskList");
            return;
        }
        syncDiskList(true);
    }

    /**
     * 网格上所有支持样板磁盘的宿主：实现了 {@link IPatternDiskHost} 的机器，加上插件注册进来的收集器给出的
     * 宿主（“能把样板磁盘当内容物收着”的机器也算）。
     *
     * <p>同一个宿主只给一次（按身份去重）：插件给的是每帧新建的适配器对象，那样去不掉重，但同一次收集里重复
     * 给出的同一个对象能去掉。磁盘槽收集与管理终端的分组都走这里，两张表看到的是同一批机器。</p>
     *
     * <p>子类可读：管理终端要连“一张盘都没插”的机器一起列出来，所以不能只依赖磁盘清单。</p>
     */
    protected List<IPatternDiskHost> collectDiskHosts() {
        return PatternDiskApi.diskHosts(getGrid());
    }

    /**
     * 返回当前网格，或 null（未连接/未激活）。
     */
    @Nullable
    protected IGrid getGrid() {
        var node = getGridNode();
        return node != null && node.isActive() ? node.getGrid() : null;
    }

    /**
     * 客户端：接收服务端推送的磁盘列表，供 Screen 每帧渲染。
     */
    public void receiveDiskList(List<DiskListPayload.DiskEntry> disks) {
        this.diskList = disks;
        this.diskListRevision++; // 客户端用它判断一次刷新是否真的到货了
    }

    /**
     * How many disk-list payloads this client has received. A screen that just asked for a refresh compares
     * this against the value it saw when asking, so it acts on the fresh list rather than the stale one.
     */
    public long getDiskListRevision() {
        return diskListRevision;
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
        return DiskMarkRules.modeMarkId(this.mode);
    }

    @Nullable
    private String resolveCurrentRecipePrefix() {
        // 兼容旧调用：当前模式的标记即当前前缀语义
        return DiskMarkRules.modeMarkId(this.mode);
    }

    @Override
    public void setItem(int slotID, int stateId, ItemStack stack) {
        // 多态合成：材料变了才清缓存。槽位同步调得非常频，无条件清会让每次同步都重扫一遍合成配方；
        // 而内容真的变了时，清掉再重算才会重新走取配方路径，把选择权交回给 Polymorph。
        if (slotID >= 0 && slotID < this.slots.size()
                && !ItemStack.matches(this.slots.get(slotID).getItem(), stack)) {
            this.currentRecipe = null;
        }
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
            // 高级档与 mode 并列，权威值同样在 logic 里：那是唯一跟着终端持久化的地方，关屏重开不掉档。
            this.advancedMode = encodingLogic.isAdvancedMode();
            this.chiselingMode = encodingLogic.isChiselingMode();
            this.overloadedMode = encodingLogic.isOverloadedMode();
            // 两张行表也回读：它们是服务端权威（客户端点一下只是先改自己的那份），关屏重开要从 logic 拉回来。
            this.overloadedSides = encodingLogic.getOverloadedSides();
            this.overloadedMatchModes = encodingLogic.getOverloadedMatchModes();
            this.substitute = encodingLogic.isSubstitution();
            this.substituteFluids = encodingLogic.isFluidSubstitution();
            // 两个开关的权威值在部件自己的 logic 里（与替换同款），服务端每 tick 回读进菜单字段再下发客户端。
            this.mergeSameItems = encodingLogic.isMergeSameItems();
            this.showUnmarkedDisks = encodingLogic.isShowUnmarkedDisks();
            this.stonecuttingRecipeId = encodingLogic.getStonecuttingRecipeId();
            this.recipePrefix = Objects.toString(resolveCurrentRecipePrefix(), "");
            syncDiskList();
            // 槽是实物、网络只是它的来源：每 tick 把差额补齐，再把「网络还剩多少」同步给客户端。
            // 顺手取代了原先的镜像写入与旧存档搬迁——槽又能放东西了，存档里的内容就是槽的内容。
            refillBlankPatternSlot();
            this.advancedModeAvailable = hasAdvancedEncoder();
            if (!this.advancedModeAvailable && this.advancedMode) {
                // 卡被拿走了：模式立刻退出去，否则面板会停在一个再也读不出方向的空档上。
                this.setAdvancedMode(false);
            }
            // 过载档与高级档同口径：可用性看升级槽里那枚过载样板编码器（AE2LT 的物品）。模组被摘掉、
            // 或卡被拿走时同様立刻退档——否则面板会停在一个再也编不出东西的空档上。
            this.overloadedModeAvailable = hasOverloadEncoder();
            if (!this.overloadedModeAvailable && this.overloadedMode) {
                this.setOverloadedMode(false);
            }
            this.chiselingModeAvailable = hasChiselingEncoder();
            // 卡不在、或这一档还没开放时，模式立刻退出去。后半句不是多余的：存档里可能残留一个为真的
            // chiselingMode（开放前用开发构建写进去的），而它是每 tick 从 logic 拉回来的——不在这儿清掉，
            // 界面会一直停在空白区。与「卡被拿走了就退出」同一口径。
            if ((!this.chiselingModeAvailable || !CHISELING_TIER_ENABLED) && this.chiselingMode) {
                this.setChiselingMode(false);
            }
            // 放在可用性回读之后：切到某一个额外档之前得先知道那一档在不在（升级槽里有没有那枚编码器）。
            // 也必须留在 super.broadcastChanges() 之后：AE2 在那一句里先发 @GuiSync 数据、再发槽刷新，
            // 两者同 tick 按序下发。挪到它之前的话，客户端会先收到槽、后收到 chiselingInputRevision，
            // 面板那一下就会把程序设的选中序号当成「玩家换了输入」清掉。
            syncTierWithPatternOutput();
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
        // 三个额外档是并列的，但服务端字段可能同时到位（存档残留、或两个 action 同一 tick 到达）：
        // 这里也归一一次，不只依赖 broadcastChanges 那条路——只在一边归一，另一边就会停在两块同坐标
        // 面板叠画的状态上。优先级固定为「高级 > 雕凿 > 过载」（与屏幕上 extraTiers 的排序一致），
        // 每次只关一个：关掉的那个会写回 logic，权威值变假后不会再触发，收敛即停。
        if (this.advancedMode && this.chiselingMode) {
            this.setAdvancedMode(false);
        }
        if (this.advancedMode && this.overloadedMode) {
            this.setAdvancedMode(false);
        }
        if (this.chiselingMode && this.overloadedMode) {
            this.setChiselingMode(false);
        }
    }

    @Override
    public void onSlotChange(Slot s) {
        if (s == this.stonecuttingInputSlot) {
            updateStonecuttingRecipes();
        }
        if (s == dock.encodedSlot() && isServerSide()) {
            this.broadcastChanges();
        }
    }


    /**
     * 在配方转移未把玩家认为的主输出放入正确槽位时，轮换已编码的处理输出。
     */

    public void cycleProcessingOutput() {
        if (isClientSide()) {
            sendClientAction(ACTION_CYCLE_PROCESSING_OUTPUT);
            return;
        }
        // 轮换的算术在 ProcessingOutputMath；这里只判「这个模式该不该轮换」。
        if (mode != EncodingMode.PROCESSING || !canCycleProcessingOutputs()) {
            return;
        }
        ProcessingOutputMath.cycle(processingOutputSlots);
    }

    // 仅当已编码多个处理输出时可轮换
    public boolean canCycleProcessingOutputs() {
        return mode == EncodingMode.PROCESSING && ProcessingOutputMath.canCycle(processingOutputSlots);
    }

    /**
     * 轮换处理样板的输入顺序：首位挪到末尾，其余顺次前移，空槽不动。
     *
     * <p>样板编码时同物品会合并、空槽会丢弃，所以顺序是「首次出现的先后」，也是样板供应器推给机器的先后。
     * 它不改主产物，也不改数量，与输出轮换是两件事。</p>
     */
    public void cycleProcessingInput() {
        if (isClientSide()) {
            sendClientAction(ACTION_CYCLE_PROCESSING_INPUT);
            return;
        }
        if (mode != EncodingMode.PROCESSING || !canCycleProcessingInputs()) {
            return;
        }
        ProcessingOutputMath.cycle(processingInputSlots);
    }

    // 仅当已编码多个处理输入时可轮换
    public boolean canCycleProcessingInputs() {
        return mode == EncodingMode.PROCESSING && ProcessingOutputMath.canCycle(processingInputSlots);
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
        ProcessingOutputMath.multiply(encodedInputsInv, factor);
        ProcessingOutputMath.multiply(encodedOutputsInv, factor);
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
        if (!ProcessingOutputMath.allDivisible(factor, encodedInputsInv, encodedOutputsInv)) {
            return;
        }
        ProcessingOutputMath.divide(encodedInputsInv, factor);
        ProcessingOutputMath.divide(encodedOutputsInv, factor);
        broadcastChanges();
    }

    public void clear() {
        if (isClientSide()) {
            sendClientAction(ACTION_CLEAR);
            return;
        }
        encodedInputsInv.clear();
        encodedOutputsInv.clear();
        // 雕凿输入也是一份输入，按「清空」的语义一并清掉。
        encodingLogic.getChiselingInputInv().clear();
        this.broadcastChanges();
        this.getAndUpdateOutput();
    }

}

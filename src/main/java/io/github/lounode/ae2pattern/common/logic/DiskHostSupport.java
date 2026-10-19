package io.github.lounode.ae2pattern.common.logic;

import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

import appeng.api.ids.AEComponents;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.api.PatternDiskTerminalView;

/**
 * 一台「拿样板磁盘当样板栏」的宿主要重复的那几件事，收在一个对象里。
 *
 * <p>
 * 具体是五条语义，每条都容易漏、漏了就是复制或吞样板：磁盘栏与它的 NBT（键 {@code disks}）、终端视图与它的失效
 * 时机、磁盘一变就重建样板列表、拆机掉落只掉磁盘（镜像先清空）、内存卡导入导出把样板段摘掉并按空白样板对账。
 * 四份手抄的宿主（供应器方块/面板、自装配供应器方块/面板）原来各写一遍；现在只有这里一份。
 * </p>
 *
 * <p>
 * <b>为什么是组合而不是基类。</b>方块与面板的父类不同（{@code PatternProviderBlockEntity} 对 {@code AEBasePart}），
 * 把两者硬塞进一条继承链只能得到一个什么都认的壳子。这里只认宿主提供的四个回调（节点、世界、保存、侧别）与一个
 * 逻辑解析器，宿主自己保留继承关系与生命周期。批处理装配室不走这条组合：它的样板来自自己的配方池，没有镜像栏，
 * 两边共用的只有终端视图本身。
 * </p>
 *
 * <p>
 * <b>磁盘变更的通知链。</b>本类就是磁盘栏的 {@link InternalInventoryHost}：{@code AppEngInternalInventory} 的每次
 * 真实改动都会走到 {@link #onChangeInventory}，那里重建样板列表并让宿主保存。宿主因此不需要再判「通知的是不是磁盘栏」
 * ——那个判定也在这一处。
 * </p>
 */
public final class DiskHostSupport implements InternalInventoryHost {

    /** 磁盘栏在方块实体/部件 NBT 里的键。改它等于让老存档丢盘，别改。 */
    public static final String NBT_DISKS = "disks";

    /**
     * 宿主需要交出来的最小面。四样都是宿主本来就有的能力，本类不反过来持有宿主类型。
     */
    public interface Host extends IActionHost {
        IManagedGridNode getMainNode();

        @Nullable
        Level getLevel();

        void saveChanges();

        boolean isClientSide();
    }

    private final Host host;
    /**
     * 延迟解析宿主那份逻辑：方块实体的 {@code createLogic()} 由父构造调用，那时宿主的字段（含本对象）还没赋值，
     * 所以这里只能存一个「到用的时候再问」的入口，构造期不调用。
     */
    private final Supplier<PatternDiskProviderLogic> logicSupplier;
    private final AppEngInternalInventory diskInventory;
    private final PatternDiskTerminalView terminalView;

    /**
     * 批处理中为真：这段时间内磁盘栏的逐槽通知不重建样板列表、也不让宿主落盘，由批处理入口在末尾统一重建一次。
     *
     * <p>
     * 两个入口需要它：读存档（AE2 的 {@code readFromNBT} 逐槽写回——不屏住就是每读一槽做一次 9216 槽的全量重建）
     * 与清空（{@link #clearDisks()}）。中间态从来没意义：两者都只关心终态。
     * </p>
     *
     * <p>
     * 屏住期间也不发 {@code saveChanges()}：这两个入口都不需要落盘（一个是刚读完存档，一个是方块/部件即将被移除）。
     * 若日后把 {@link #clearDisks()} 用在活体机器上，得在它末尾补一次保存。
     * </p>
     */
    private boolean suppressRefresh;

    /**
     * @param host           宿主，提供节点、世界、保存与侧别
     * @param logicSupplier  解析宿主当前的供应器逻辑；构造期不会被调用，可以安全地读尚未初始化的字段
     * @param diskSlots      磁盘槽数
     */
    public DiskHostSupport(Host host, Supplier<PatternDiskProviderLogic> logicSupplier, int diskSlots) {
        this.host = host;
        this.logicSupplier = logicSupplier;
        this.diskInventory = new AppEngInternalInventory(this, diskSlots);
        // 终端视图缓存的原因见 PatternDiskTerminalView：已打开的样板访问终端会一直用它开屏时拿到的实例（行数冻结），
        // 所以磁盘一变就作废，下一次打开重建。
        this.terminalView = PatternDiskApi.terminalView(diskInventory,
                () -> host.getMainNode().getGrid(),
                host, this::onTerminalChanged, host::getLevel);
    }

    // ---- 磁盘栏 --------------------------------------------------------------

    /** @return 磁盘槽库存；只有这些槽里的 {@code PatternDiskItem} 是本模组认的样板来源 */
    public AppEngInternalInventory getDiskInventory() {
        return diskInventory;
    }

    /** @return 样板访问终端读的那份视图：磁盘上的样板展开成行，写入落回磁盘 */
    public InternalInventory getTerminalPatternInventory() {
        return terminalView.view();
    }

    // ---- 刷新 ----------------------------------------------------------------

    public void refreshFromDisks() {
        refreshFromDisks(false);
    }

    /**
     * @param force 磁盘没动也强制重建——内存卡导入会改写镜像而磁盘原地不动，状态比较看不出这一点
     */
    public void refreshFromDisks(boolean force) {
        var logic = logic();
        if (logic != null) {
            logic.refreshPatternsFromDisks(force);
        }
        // 已打开的终端继续用它自己那份（行数冻结）；下一次打开重建紧凑视图。
        terminalView.invalidate();
    }

    /**
     * 清空镜像样板栏而不触发逐槽通知。调用方只要空终态：掉落前要先空（否则 AE2 会把镜像当真实内容掉出来），
     * 导入前也要先空（随后强制重建）。
     */
    public void clearMirror() {
        var logic = logic();
        if (logic != null) {
            logic.clearMirror();
        }
    }

    // ---- 掉落、清空与存档 -----------------------------------------------------

    /** 把磁盘槽里的盘逐个交给掉落表；镜像不在这里掉，调用方清完镜像再走 AE2 的 {@code addDrops}。 */
    public void collectDiskDrops(List<ItemStack> drops) {
        for (int slot = 0; slot < diskInventory.size(); slot++) {
            var stack = diskInventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
    }

    /**
     * 清空磁盘栏，只重建一次样板列表。宿主自己的 {@code clearContent()} 里调它。
     *
     * <p>末尾的刷新带 {@code force}：清空后的槽位是「全空」而快照可能也已经是全空，非强制刷新会把这一次重建
     * 当成「没变」跳掉，镜像就留在原样。触不到这条路径（能写镜像的口子已经封了）不代表可以写错。</p>
     */
    public void clearDisks() {
        var previous = suppressRefresh;
        suppressRefresh = true;
        try {
            diskInventory.clear();
        } finally {
            suppressRefresh = previous;
        }
        refreshFromDisks(true);
    }

    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        diskInventory.writeToNBT(tag, NBT_DISKS, registries);
    }

    /** 读盘并重建样板列表。读期间的逐槽通知被 {@link #suppressRefresh} 屏住，末尾统一重建一次。 */
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        var previous = suppressRefresh;
        suppressRefresh = true;
        try {
            diskInventory.readFromNBT(tag, NBT_DISKS, registries);
        } finally {
            suppressRefresh = previous;
        }
        refreshFromDisks();
    }

    /**
     * 把内存卡里的样板段摘掉。旧版本、或 AE2 自己的供应器写出来的卡仍带着样板，导入时那些会被当作真实内容、
     * 为磁盘上已有的配方扣空白样板，然后在下次刷新时被丢掉。
     */
    public static DataComponentMap withoutPatterns(DataComponentMap input) {
        var sanitized = DataComponentMap.builder().addAll(input);
        sanitized.set(AEComponents.EXPORTED_PATTERNS, ItemContainerContents.EMPTY);
        return sanitized.build();
    }

    // ---- InternalInventoryHost（磁盘栏的通知都到这里） -------------------------

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv != diskInventory || suppressRefresh) {
            return;
        }
        // 磁盘槽内容变化（含终端取出真删、编码终端写盘、取盘/换盘、加载）：磁盘已是最终状态，
        // 重建样板列表并让宿主落盘。
        refreshFromDisks();
        host.saveChanges();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        host.saveChanges();
    }

    @Override
    public boolean isClientSide() {
        return host.isClientSide();
    }

    // ---- 内部 ------------------------------------------------------------------

    private @Nullable PatternDiskProviderLogic logic() {
        return logicSupplier.get();
    }

    /** 终端里真取走/写入了样板：重建并落盘（与磁盘变更同一套后果）。 */
    private void onTerminalChanged() {
        refreshFromDisks();
        host.saveChanges();
    }
}

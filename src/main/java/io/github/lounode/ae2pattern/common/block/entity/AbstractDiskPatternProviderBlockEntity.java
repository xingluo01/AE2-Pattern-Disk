package io.github.lounode.ae2pattern.common.block.entity;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.ids.AEComponents;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.util.SettingsFrom;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.common.logic.DiskHostSupport;
import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;

/**
 * 两台「带样板磁盘的供应器方块」共用的那半：磁盘栏、镜像语义、掉落与内存卡的样板约定。
 *
 * <p>
 * 磁盘那一半全在 {@link DiskHostSupport} 里（与面板形态共用一份），本类只做两件事：把 AE2 的几个覆盖点转过去
 * （{@code onLoad}/{@code saveAdditional}/{@code loadTag}/{@code addAdditionalDrops}/{@code importSettings}/
 * {@code exportSettings}/{@code clearContent}），以及把「样板栏只是磁盘镜像」这条约定的三处后果写死在一处：
 * 掉落先清镜像、导入前清镜像、导出不写样板段。
 * </p>
 *
 * <p>
 * 子类只留不同的三样：方块实体类型、供应器逻辑类型（普通 / 自装配）、以及注册用的物品。自装配那台另外多一条
 * 服务端 tick，但那属于它自己的事，不在这里。
 * </p>
 *
 * @param <L> 该方块使用的供应器逻辑类型；磁盘那半只按 {@link PatternDiskProviderLogic} 用
 */
public abstract class AbstractDiskPatternProviderBlockEntity<L extends PatternDiskProviderLogic>
        extends PatternProviderBlockEntity
        implements InternalInventoryHost, DiskHostSupport.Host {

    private final DiskHostSupport disks;

    protected AbstractDiskPatternProviderBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState,
            int diskSlots) {
        super(type, pos, blockState);
        // 注意顺序：父构造已经调用过 createLogic()（子类实现），那时只能传一个懒解析的 supplier，
        // 磁盘栏本身要等本对象建好之后才存在。
        this.disks = new DiskHostSupport(this, this::currentLogic, diskSlots);
    }

    /** @return 本方块使用的供应器逻辑；父构造期间调用，所以只能用静态量与本对象的既有方法 */
    @Override
    protected abstract L createLogic();

    /** @return 设备自己的物品，用于样板访问终端的分组图标与菜单图标 */
    protected abstract Item item();

    /** 父构造期 {@code disks} 还不存在，所以逻辑解析走 {@code getLogic()}，磁盘那半拿到逻辑前只做空转。 */
    private @Nullable PatternDiskProviderLogic currentLogic() {
        return getLogic() instanceof PatternDiskProviderLogic logic ? logic : null;
    }

    // ---- 磁盘那半（委托） -------------------------------------------------

    public AppEngInternalInventory getDiskInventory() {
        return disks.getDiskInventory();
    }

    public InternalInventory getTerminalPatternInventory() {
        return disks.getTerminalPatternInventory();
    }

    /**
     * 按当前磁盘内容重建样板列表，并作废缓存视图，让下一次打开的样板访问终端拿到紧凑的行。已经开着的终端继续用它
     * 自己那份（行数冻结），这里不重新同步它——重同步会把行数顶到终端客户端的固定槽数之上而崩服务端（AE2 的限制）。
     */
    public void refreshFromDisks() {
        disks.refreshFromDisks();
    }

    public void refreshFromDisks(boolean force) {
        disks.refreshFromDisks(force);
    }

    // ---- AE2 覆盖点 ----------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        disks.refreshFromDisks();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        disks.onChangeInventory(inv, slot);
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        disks.saveChangedInventory(inv);
    }

    @Override
    public boolean isClientSide() {
        return level != null && level.isClientSide();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        disks.save(tag, registries);
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        disks.load(tag, registries);
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        // 不调 super.addAdditionalDrops：父类会把注入的样板栏（逻辑从磁盘展开出来的副本）当成真实内容掉出来，
        // 而真身只有磁盘。下面的顺序是硬约定：先收磁盘 → 清镜像 → 让 AE2 掉待发送缓冲与返还栏。
        disks.collectDiskDrops(drops);
        // 镜像必须先清：AE2 的 addDrops 会连样板栏一起掉，不先清就等于把磁盘上的样板复制一份给玩家。
        disks.clearMirror();
        getLogic().addDrops(drops);
        // 清掉注入的样板栏，拆完不留脏数据。
        clearContent();
    }

    /**
     * 应用内存卡时保住磁盘镜像：AE2 的默认实现会清空供应器的样板栏并把里面的样板交给玩家，而这里那栏只是磁盘的
     * 镜像，照做等于把磁盘上已有的样板复制一份。
     */
    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        var cleanInput = DiskHostSupport.withoutPatterns(input);
        if (mode == SettingsFrom.MEMORY_CARD) {
            // 镜像马上会被 super.importSettings 改写，而磁盘没动——状态比较看不出这种变化。
            disks.clearMirror();
        }
        super.importSettings(mode, cleanInput, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            // 必须强制重建：磁盘没动，状态检查会跳过，镜像就停在 super.importSettings 留下的样子。
            disks.refreshFromDisks(true);
        }
    }

    /**
     * 写内存卡时摘掉样板段：AE2 的供应器会把自己的样板写进卡，做成磁盘内容的副本，拿到别处导入还会为这些副本
     * 扣空白样板。
     */
    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder, @Nullable Player player) {
        super.exportSettings(mode, builder, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            builder.set(AEComponents.EXPORTED_PATTERNS, ItemContainerContents.EMPTY);
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        disks.clearDisks();
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(item());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(item());
    }
}

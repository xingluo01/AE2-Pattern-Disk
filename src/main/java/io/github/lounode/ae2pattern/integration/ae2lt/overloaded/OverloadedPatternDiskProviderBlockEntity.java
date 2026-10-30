package io.github.lounode.ae2pattern.integration.ae2lt.overloaded;

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
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.ids.AEComponents;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEItemKey;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;
import appeng.util.SettingsFrom;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import com.moakiee.ae2lt.api.pattern.PatternProviderUiProfile;
import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.logic.DiskHostSupport;
import io.github.lounode.ae2pattern.common.logic.PatternsFromDisks;

/**
 * 过载样板磁盘供应器的方块实体：继承闪电科技的过载供应器，样板来自 9 个磁盘槽。
 *
 * <p>磁盘栏、掉落、内存卡与终端视图的行为照 {@code AbstractDiskPatternProviderBlockEntity} 抄一份：
 * 那台设备的父类已经被 AE2 的供应器占住，这里换成闪电科技的供应器，组合方式不变。</p>
 */
public class OverloadedPatternDiskProviderBlockEntity extends OverloadedPatternProviderBlockEntity
        implements PatternProviderUiProfile, PatternDiskProviderHost, DiskHostSupport.Host, InternalInventoryHost {

    public static final int DISK_SLOT_COUNT = 9;
    /** 9 张 256K 档磁盘的上限；构造期父类要用它建样板栏，所以是常量。 */
    public static final int PATTERN_CAPACITY = DISK_SLOT_COUNT * 1024;

    private final DiskHostSupport disks;

    public OverloadedPatternDiskProviderBlockEntity(BlockPos pos, BlockState state) {
        super(OverloadedPatternDiskProviderRegistrations.BE.get(), pos, state);
        this.disks = new DiskHostSupport(this, this::currentLogic, DISK_SLOT_COUNT);
    }

    @Override
    protected PatternProviderLogic createLogic() {
        return new OverloadedPatternDiskProviderLogic(
                getMainNode(), this, getTotalPatternCapacity(), this::getDiskInventory);
    }

    @Override
    public int getTotalPatternCapacity() {
        return PATTERN_CAPACITY;
    }

    private @Nullable PatternsFromDisks currentLogic() {
        return getLogic() instanceof OverloadedPatternDiskProviderLogic logic ? logic : null;
    }

    // ---- 磁盘栏 -------------------------------------------------------------

    @Override
    public AppEngInternalInventory getDiskInventory() {
        return disks.getDiskInventory();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return disks.getTerminalPatternInventory();
    }

    // ---- 生命周期 -----------------------------------------------------------

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
        // 不调 super：父类会把镜像样板栏当成真实内容掉出来，而真身只有磁盘。
        disks.collectDiskDrops(drops);
        disks.clearMirror();
        // 走上游的 addAdditionalDrops：样板栏此时已空，封包侧还会掉核心订单与适配卡。
        super.addAdditionalDrops(level, pos, drops);
        clearContent();
    }

    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        var cleanInput = DiskHostSupport.withoutPatterns(input);
        if (mode == SettingsFrom.MEMORY_CARD) {
            disks.clearMirror();
        }
        super.importSettings(mode, cleanInput, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            disks.refreshFromDisks(true);
        }
    }

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

    // ---- 菜单与图标 ---------------------------------------------------------

    @Override
    public void openMenu(Player player, MenuHostLocator locator) {
        if (level instanceof net.minecraft.server.level.ServerLevel) {
            clearInvalidConnections();
        }
        MenuOpener.open(OverloadedPatternDiskProviderMenu.TYPE, player, locator);
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(OverloadedPatternDiskProviderMenu.TYPE, player, subMenu.getLocator());
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(OverloadedPatternDiskProviderRegistrations.ITEM.get());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(OverloadedPatternDiskProviderRegistrations.ITEM.get());
    }

    @Override
    protected Item getItemFromBlockEntity() {
        return OverloadedPatternDiskProviderRegistrations.ITEM.get().asItem();
    }

    @Override
    public String ae2lt$titleTranslationKey() {
        return "gui.ae2_pattern_disk.overloaded_pattern_disk_provider";
    }
}
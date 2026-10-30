package io.github.lounode.ae2pattern.integration.ae2lt.packaged;

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
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;
import appeng.util.SettingsFrom;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import com.moakiee.ae2lt.api.pattern.PatternProviderUiProfile;
import com.moakiee.ae2lt.packaged.blockentity.PackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ProviderMode;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.logic.DiskHostSupport;
import io.github.lounode.ae2pattern.common.logic.PatternsFromDisks;

/**
 * 封包样板磁盘供应器的方块实体：继承闪电科技的封包供应器，样板来自 9 个磁盘槽。
 *
 * <p>磁盘栏、掉落、内存卡与终端视图照 {@code AbstractDiskPatternProviderBlockEntity} 的做法组合，
 * 不复制它的继承链。</p>
 */
public class PackagedPatternDiskProviderBlockEntity extends PackagedPatternProviderBlockEntity
        implements PatternProviderUiProfile, PatternDiskProviderHost, DiskHostSupport.Host, InternalInventoryHost {

    public static final int DISK_SLOT_COUNT = 9;
    public static final int PATTERN_CAPACITY = DISK_SLOT_COUNT * 1024;

    private final DiskHostSupport disks;

    public PackagedPatternDiskProviderBlockEntity(BlockPos pos, BlockState state) {
        this(PackagedPatternDiskProviderRegistrations.BE.get(), pos, state, ProviderMode.NORMAL);
    }

    protected PackagedPatternDiskProviderBlockEntity(
            BlockEntityType<?> type, BlockPos pos, BlockState state, ProviderMode mode) {
        super(type, pos, state, mode);
        this.disks = new DiskHostSupport(this, this::currentLogic, DISK_SLOT_COUNT);
    }

    @Override
    protected PatternProviderLogic createLogic() {
        return new PackagedPatternDiskProviderLogic(getMainNode(), this, this::getDiskInventory);
    }

    @Override
    public int getPatternSlotCount() {
        return PATTERN_CAPACITY;
    }

    private @Nullable PatternsFromDisks currentLogic() {
        return getLogic() instanceof PackagedPatternDiskProviderLogic logic ? logic : null;
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
        MenuOpener.open(PackagedPatternDiskProviderMenu.TYPE, player, locator);
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(PackagedPatternDiskProviderMenu.TYPE, player, subMenu.getLocator());
    }


    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(iconItem());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(iconItem());
    }

    @Override
    protected Item getItemFromBlockEntity() {
        return iconItem().asItem();
    }

    @Override
    public String ae2lt$titleTranslationKey() {
        return "gui.ae2_pattern_disk.packaged_pattern_disk_provider";
    }

    protected Item iconItem() {
        return PackagedPatternDiskProviderRegistrations.ITEM.get();
    }
}
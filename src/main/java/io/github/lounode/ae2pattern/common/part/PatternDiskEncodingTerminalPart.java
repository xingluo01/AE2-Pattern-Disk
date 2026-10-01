package io.github.lounode.ae2pattern.common.part;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.items.parts.PartModels;
import appeng.parts.PartModel;
import appeng.parts.reporting.AbstractTerminalPart;

import io.github.lounode.ae2pattern.common.menu.DiskEncodingLogic;
import io.github.lounode.ae2pattern.common.menu.IPatternDiskTerminalHost;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

public class PatternDiskEncodingTerminalPart extends AbstractTerminalPart
        implements IPatternDiskTerminalHost {

    @PartModels
    public static final ResourceLocation MODEL_OFF = ResourceLocation.parse(
            "ae2_pattern_disk:part/pattern_disk_encoding_terminal_off");
    @PartModels
    public static final ResourceLocation MODEL_ON = ResourceLocation.parse(
            "ae2_pattern_disk:part/pattern_disk_encoding_terminal_on");

    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, MODEL_OFF, MODEL_STATUS_OFF);
    public static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, MODEL_ON, MODEL_STATUS_ON);
    public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, MODEL_ON, MODEL_STATUS_HAS_CHANNEL);

    private final DiskEncodingLogic logic = new DiskEncodingLogic(this);

    /**
     * 升级槽：装上高级样板编码器，编码终端才会多出高级编码模式（那是唯一的要求，没有别的用途）。
     *
     * <p>库存建在部件自己身上而不是物品栈里——它得跟着这台装在世界里的终端走，拆下来时连带掉出。</p>
     */
    private final IUpgradeInventory upgrades;

    public PatternDiskEncodingTerminalPart(IPartItem<?> partItem) {
        super(partItem);
        this.upgrades = UpgradeInventories.forMachine(partItem.asItem(), 1, this::markForSave);
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        for (var is : this.logic.getBlankPatternInv()) drops.add(is);
        for (var is : this.logic.getEncodedPatternInv()) drops.add(is);
        for (var is : this.upgrades) drops.add(is);
    }

    @Override
    public void clearContent() {
        super.clearContent();
        this.logic.getBlankPatternInv().clear();
        this.logic.getEncodedPatternInv().clear();
        this.upgrades.clear();
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        logic.readFromNBT(data, registries);
        upgrades.readFromNBT(data, "upgrades", registries);
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        logic.writeToNBT(data, registries);
        upgrades.writeToNBT(data, "upgrades", registries);
    }

    @Override
    public MenuType<?> getMenuType(Player p) {
        return PatternDiskEncodingTermMenu.TYPE;
    }

    @Override
    public IPartModel getStaticModels() {
        return this.selectModel(MODELS_OFF, MODELS_ON, MODELS_HAS_CHANNEL);
    }

    @Override
    public DiskEncodingLogic getLogic() {
        return logic;
    }

    @Override
    public void markForSave() {
        getHost().markForSave();
    }
}
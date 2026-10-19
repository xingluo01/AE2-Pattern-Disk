package io.github.lounode.ae2pattern.common.part;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import appeng.api.inventories.InternalInventory;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.PartModel;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;
import io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu;

/**
 * ME 样板磁盘供应器的面板形态：贴在线缆上的部件，带
 * {@link PatternDiskProviderBlockEntity#DISK_SLOT_COUNT} 个磁盘槽，把磁盘上编码好的样板公开给 ME 自动合成。
 *
 * <p>「供应器」那一整套在 {@link PatternDiskProviderLogic}（它只要一个 {@code PatternProviderLogicHost}）。磁盘那半、
 * 推入方向与面板几何与自装配面板共用一份（见 {@link AbstractDiskPatternProviderPart}）；本类只带不同的两样：模型与
 * 逻辑类型。</p>
 */
public class PatternDiskProviderPart extends AbstractDiskPatternProviderPart<PatternDiskProviderLogic>
        implements PatternDiskProviderHost {

    /** 面板模型，登记后 AE2 的部件模型注册表才知道这个资源存在。 */
    @PartModels
    public static final ResourceLocation MODEL = ResourceLocation.parse(
            "ae2_pattern_disk:part/pattern_disk_provider_base");

    public static final IPartModel MODELS = new PartModel(MODEL);

    public PatternDiskProviderPart(IPartItem<?> partItem) {
        super(partItem, PatternDiskProviderBlockEntity.DISK_SLOT_COUNT);
    }

    @Override
    protected PatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏：基类构造就会调到这里，那时 logic 字段还没赋值，所以 supplier 只许在刷新时才解析。
        return new PatternDiskProviderLogic(getMainNode(), this,
                PatternDiskProviderBlockEntity.DISK_SLOT_COUNT, this::getDiskInventory);
    }

    @Override
    protected IPartModel models() {
        return MODELS;
    }

    @Override
    protected void openHostMenu(Player player) {
        MenuOpener.open(PatternDiskProviderMenu.TYPE, player, MenuLocators.forPart(this));
    }

    /**
     * 显式覆写只为一件事：给这个签名加一道编译期断言。AE2 的默认实现把<b>镜像样板栏</b>交给终端，而这里的镜像只是
     * 磁盘的副本——本方法一旦因接口改名而失去覆盖，样板访问终端会静默读到镜像（取走时不碰磁盘）。实现仍在基类。
     */
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return super.getTerminalPatternInventory();
    }
}

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

import io.github.lounode.ae2pattern.common.block.entity.MarkedPatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.logic.MarkedPatternDiskProviderLogic;
import io.github.lounode.ae2pattern.common.menu.MarkedPatternDiskProviderMenu;

/**
 * 标记式样板磁盘供应器的面板形态：贴在线缆上，磁盘槽数为 0。
 *
 * <p>推入方向、几何与红石转发都在 {@link AbstractDiskPatternProviderPart}；本类只给模型与逻辑类型。</p>
 */
public class MarkedPatternDiskProviderPart
        extends AbstractDiskPatternProviderPart<MarkedPatternDiskProviderLogic>
        implements MarkedPatternDiskProviderHost {

    @PartModels
    public static final ResourceLocation MODEL = ResourceLocation.parse(
            "ae2_pattern_disk:part/marked_pattern_disk_provider_base");

    public static final IPartModel MODELS = new PartModel(MODEL);

    public MarkedPatternDiskProviderPart(IPartItem<?> partItem) {
        super(partItem, 0);
    }

    @Override
    protected MarkedPatternDiskProviderLogic createLogic() {
        // 基类构造就会调到这里，那时 logic 字段还没赋值，supplier 只许在刷新时才解析。
        return new MarkedPatternDiskProviderLogic(getMainNode(), this, this::getDiskInventory);
    }

    @Override
    protected IPartModel models() {
        return MODELS;
    }

    @Override
    protected void openHostMenu(Player player) {
        MenuOpener.open(MarkedPatternDiskProviderMenu.TYPE, player, MenuLocators.forPart(this));
    }

    /**
     * 终端视图来自本机磁盘栏（0 槽），不是镜像：镜像出来的样板只在网络上参与合成，不进任何磁盘终端。
     */
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return super.getTerminalPatternInventory();
    }
}

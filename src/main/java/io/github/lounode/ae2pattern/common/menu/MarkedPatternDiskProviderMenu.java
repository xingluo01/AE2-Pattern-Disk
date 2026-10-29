package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;

import io.github.lounode.ae2pattern.common.block.entity.MarkedPatternDiskProviderHost;
import io.github.lounode.ae2pattern.common.logic.MarkedPatternDiskProviderLogic;

/**
 * 标记式样板磁盘供应器的菜单：在普通供应器那套三档设置之上，多一组「候选标记 + 已选标记」。
 *
 * <p>选中集合与候选列表都按字符串同步：{@link GuiSync} 只认基本类型，而标记本身已经过
 * {@code DiskMarkRules} 的清洗（控制字符被剔除），所以用 {@link #MARK_SEPARATOR} 拼一条串是安全的
 * ——分隔符不可能出现在标记里。候选列表每 {@link #MARK_REFRESH_INTERVAL} tick 重算一次，
 * 且只在内容变化时真正发包。</p>
 */
public class MarkedPatternDiskProviderMenu extends PatternDiskProviderMenu {

    /** 标记之间不可能出现的分隔符：控制字符会被 {@code DiskMarkRules.sanitizeName} 剔掉。 */
    public static final String MARK_SEPARATOR = "\u0001";

    private static final int MARK_REFRESH_INTERVAL = 20;

    public static final MenuType<MarkedPatternDiskProviderMenu> TYPE = MenuTypeBuilder
            .create((id, playerInventory, host) -> new MarkedPatternDiskProviderMenu(id, playerInventory, host),
                    MarkedPatternDiskProviderHost.class)
            .buildUnregistered(ResourceLocation.parse("ae2_pattern_disk:marked_pattern_disk_provider"));

    @GuiSync(20)
    public String selectedMarks = "";
    @GuiSync(21)
    public String availableMarks = "";

    private int markRefreshCooldown;

    public MarkedPatternDiskProviderMenu(int id, Inventory playerInv, MarkedPatternDiskProviderHost host) {
        super(TYPE, id, playerInv, host);
        registerClientAction("toggleMark", String.class, this::onToggleMark);
        registerClientAction("clearSelectedMarks", this::onClearSelectedMarks);
    }

    public MarkedPatternDiskProviderLogic getMarkedLogic() {
        return (MarkedPatternDiskProviderLogic) getProvider().getLogic();
    }

    /** 候选标记，按服务端下发的顺序。 */
    public List<String> getAvailableMarks() {
        return split(availableMarks);
    }

    /** 已选中的标记，按选中先后。 */
    public List<String> getSelectedMarkList() {
        return split(selectedMarks);
    }

    private void onToggleMark(String mark) {
        getMarkedLogic().toggleMark(mark);
    }

    private void onClearSelectedMarks() {
        getMarkedLogic().clearSelectedMarks();
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            var logic = getMarkedLogic();
            selectedMarks = String.join(MARK_SEPARATOR, logic.getSelectedMarks());
            if (--markRefreshCooldown <= 0) {
                markRefreshCooldown = MARK_REFRESH_INTERVAL;
                availableMarks = String.join(MARK_SEPARATOR, logic.collectAvailableMarks());
            }
        }
        super.broadcastChanges();
    }

    private static List<String> split(String wire) {
        if (wire == null || wire.isEmpty()) {
            return List.of();
        }
        var marks = new ArrayList<String>();
        for (var mark : wire.split(MARK_SEPARATOR, -1)) {
            if (!mark.isEmpty()) {
                marks.add(mark);
            }
        }
        return marks;
    }
}

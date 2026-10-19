package io.github.lounode.ae2pattern.common.part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import appeng.api.inventories.InternalInventory;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.PartModel;

import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderHost;
import io.github.lounode.ae2pattern.common.logic.SelfAssemblingPatternDiskProviderLogic;
import io.github.lounode.ae2pattern.common.menu.MeteoritePatternProviderMenu;

/**
 * 自装配样板磁盘供应器的面板形态：贴在线缆上的部件，带
 * {@link MeteoritePatternProviderBlockEntity#DISK_SLOT_COUNT} 个磁盘槽，把磁盘上的样板当作自装配样板公开给 ME
 * 合成服务。
 *
 * <p>
 * 磁盘那半、推入方向与面板几何都在 {@link AbstractDiskPatternProviderPart}（与普通供应器面板共用一份），契约跟着
 * {@link MeteoritePatternProviderHost} 与 {@link SelfAssemblingPatternDiskProviderLogic} 走。本类只补一块：自装配
 * 产物的回送得自己找节拍。
 * </p>
 *
 * <p>
 * <b>回送节拍。</b>自装配产物要每 tick 抽一次水：方块形态靠方块实体的服务端 tick，面板没有那条回调，而网格的
 * {@code IGridTickable} 服务槽归 AE2 的供应器逻辑——它在自己构造器里注册 Ticker，返回仓与待发送缓冲的重试都挂在
 * 那个 Ticker 的私有 {@code doWork()} 上，抢过来就等于把供应器最关键的那条路停掉（面板一开始就是这么写的，
 * 审查时被抓出来了）。所以面板一个服务也不占，改由服务端的世界 tick 事件驱动
 * {@link #pumpCraftedContents(ServerLevel)}。
 * </p>
 */
public class MeteoritePatternProviderPart extends AbstractDiskPatternProviderPart<SelfAssemblingPatternDiskProviderLogic>
        implements MeteoritePatternProviderHost {

    /**
     * 在场的面板表，由服务端世界 tick 事件驱动。两点讲究：
     *
     * <ul>
     * <li><b>弱引用</b>：在册/注销都写在生命周期里，万一漏注销（比如区块卸载那条路），条目也会随部件一起被回收。</li>
     * <li><b>同步包装</b>：这张表两边都会碰——客户端也持有一份零件（渲染用），它进/出世界走的是同一对回调，
     * 而遍历在服务端 tick 里。所以增删交给同步包装，遍历先取一份快照。</li>
     * </ul>
     */
    private static final Set<MeteoritePatternProviderPart> LOADED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** 面板模型，登记后 AE2 的部件模型注册表才知道这个资源存在。 */
    @PartModels
    public static final ResourceLocation MODEL = ResourceLocation.parse(
            "ae2_pattern_disk:part/meteorite_pattern_provider_base");

    public static final IPartModel MODELS = new PartModel(MODEL);

    public MeteoritePatternProviderPart(IPartItem<?> partItem) {
        super(partItem, MeteoritePatternProviderBlockEntity.DISK_SLOT_COUNT);
    }

    @Override
    protected SelfAssemblingPatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏，与方块实体同款：基类构造就会调到这里，那时 logic 字段还没赋值。
        return new SelfAssemblingPatternDiskProviderLogic(getMainNode(), this,
                MeteoritePatternProviderBlockEntity.DISK_SLOT_COUNT, this::getDiskInventory);
    }

    @Override
    protected IPartModel models() {
        return MODELS;
    }

    @Override
    protected void openHostMenu(Player player) {
        MenuOpener.open(MeteoritePatternProviderMenu.TYPE, player, MenuLocators.forPart(this));
    }

    /**
     * 显式覆写只为一件事：给这个签名加一道编译期断言。AE2 的默认实现把<b>镜像样板栏</b>交给终端，而这里的镜像只是
     * 磁盘的副本——本方法一旦因接口改名而失去覆盖，样板访问终端会静默读到镜像（取走时不碰磁盘）。实现仍在基类。
     */
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return super.getTerminalPatternInventory();
    }

    // ---- 自装配产物的回送（方块形态由方块实体的 serverTick 驱动） --------------

    /**
     * 给这个世界里的每一块面板抽一次自装配产物的回送。由服务端的世界 tick 事件调用（登记见
     * {@code MeteoritePatternProviderRegistrations}）。
     *
     * <p>
     * 每 tick 都抽，也不预判「有没有活干」：{@link SelfAssemblingPatternDiskProviderLogic#tickCraftedContents()}
     * 自带节拍（超频卡决定每 1/4 tick 回送一次），两次回送之间它返回 {@code false}；而且待回送表随时可能因为
     * 网络与返回仓同时塞满而留着东西等下一轮——跳过一次就是让它多等一整轮。方块形态那边也是无条件每 tick
     * 跑一遍。
     * </p>
     */
    public static void pumpCraftedContents(ServerLevel level) {
        if (LOADED.isEmpty()) {
            return;
        }
        // 快照后再遍历：同步包装的 toArray 是同步的，而遍历过程中部件可能被拆/被加载（另——客户端那份也会进表）。
        for (var part : new ArrayList<>(LOADED)) {
            var host = part.getBlockEntity();
            // 客户端那份、别的世界的、以及已卸载/移除的面板都跳过。
            if (host == null || host.isRemoved() || host.getLevel() != level) {
                // 顺手把彻底废掉的条目摘掉（区块卸载没走到 removeFromWorld 时会留下这种）：
                // 弱集合能兜住内存，但表会随运行时长越长越多，每次遍历都在扫它们。
                // 注意只摘「宿主没了」的：别的世界的面板是正常的，不能动。
                if (host == null || host.isRemoved()) {
                    LOADED.remove(part);
                }
                continue;
            }
            part.logic.tickCraftedContents();
        }
    }

    @Override
    protected void onAddedToWorld() {
        LOADED.add(this);
    }

    @Override
    protected void onRemovedFromWorld() {
        LOADED.remove(this);
    }
}

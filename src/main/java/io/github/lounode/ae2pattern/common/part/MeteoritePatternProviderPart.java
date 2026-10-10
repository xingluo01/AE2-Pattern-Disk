package io.github.lounode.ae2pattern.common.part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import appeng.api.ids.AEComponents;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNodeListener;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEItemKey;
import appeng.api.util.AECableType;
import appeng.block.crafting.PushDirection;
import appeng.items.parts.PartModels;
import appeng.menu.locator.MenuLocators;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import appeng.util.InteractionUtil;
import appeng.util.SettingsFrom;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.api.PatternDiskApi;
import io.github.lounode.ae2pattern.config.AEPDCommonConfig;
import io.github.lounode.ae2pattern.api.PatternDiskTerminalView;
import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderHost;
import io.github.lounode.ae2pattern.common.logic.SelfAssemblingPatternDiskProviderLogic;

/**
 * 自装配样板磁盘供应器的面板形态：贴在线缆上的部件，带
 * {@link MeteoritePatternProviderBlockEntity#DISK_SLOT_COUNT} 个磁盘槽，把磁盘上的样板当作自装配样板公开给 ME
 * 合成服务。
 *
 * <p>
 * 方块形态那份契约与逻辑原样复用——磁盘栏、镜像式样板栏、掉落与内存卡语义、菜单，都跟着
 * {@link MeteoritePatternProviderHost} 与 {@link SelfAssemblingPatternDiskProviderLogic} 走。本类只补两处方块
 * 实体有、部件没有的东西：自装配产物的回送得自己找节拍（见下方「回送节拍」），以及贴附面顶替了「推入方向」里的
 * 方位参照。
 * </p>
 *
 * <p>
 * <b>推入方向。</b>没被扳手动过时只朝自己贴附的那一面推（与方块默认档一致）。扳手在旋转模式下按固定顺序循环：
 * 贴附面（默认档）→ 全向 → 其余五个面逐个 → 回贴附面。方块形态是「点哪一面」那一套，两形态在这里不同；面板也
 * 还没有箭头模型，所以换档在动作栏报一声。
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
public class MeteoritePatternProviderPart extends AEBasePart
        implements MeteoritePatternProviderHost, InternalInventoryHost {

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

    private final AppEngInternalInventory diskInventory = new AppEngInternalInventory(this,
            MeteoritePatternProviderBlockEntity.DISK_SLOT_COUNT);

    /**
     * 终端视图，与方块形态共用一份（见 {@link PatternDiskTerminalView}）：样板访问终端必须从磁盘读，而不能从
     * 逻辑填出来的镜像栏读，否则取走一张样板时磁盘原地不动。
     */
    private final PatternDiskTerminalView terminalView = PatternDiskApi.terminalView(diskInventory,
            () -> getMainNode().getGrid(), this, this::markTerminalChanged, this::getLevel);

    protected final SelfAssemblingPatternDiskProviderLogic logic = createLogic();

    /**
     * 推入方向（扳手可调）。{@code null} = 没被扳手动过，保持旧行为：只朝自己贴附的那一面推。
     * 一旦动过就按 AE2 那套档位走：{@code ALL} = 六面都推，其余 = 只推那一面。
     */
    private @Nullable PushDirection pushDirection;

    private static final String NBT_PUSH_DIRECTION = "pushDirection";

    public MeteoritePatternProviderPart(IPartItem<?> partItem) {
        super(partItem);
    }

    protected SelfAssemblingPatternDiskProviderLogic createLogic() {
        // 懒取磁盘栏，与方块实体同款：父构造会先跑 createLogic()，那时磁盘栏字段还没赋值。
        return new SelfAssemblingPatternDiskProviderLogic(getMainNode(), this,
                MeteoritePatternProviderBlockEntity.DISK_SLOT_COUNT, this::getDiskInventory);
    }

    // ---- MeteoritePatternProviderHost / PatternDiskProviderHost / IPatternDiskHost ----

    @Override
    public AppEngInternalInventory getDiskInventory() {
        return diskInventory;
    }

    @Override
    public BlockPos getBlockPos() {
        var host = getBlockEntity();
        return host != null ? host.getBlockPos() : BlockPos.ZERO;
    }

    /** 同一根线缆上的多块面板共用一个方块坐标，所以它们的身份得由所贴的那一面区分。 */
    @Override
    public int getIdentitySalt() {
        return getSide().name().hashCode();
    }

    @Override
    public SelfAssemblingPatternDiskProviderLogic getLogic() {
        return logic;
    }

    @Override
    public EnumSet<Direction> getTargets() {
        // 没被扳手调过：只朝自己贴附的那一面推。调过之后：全向 = 六面都推，定向 = 只推那一面。
        if (pushDirection == null) {
            return EnumSet.of(getSide());
        }
        var single = pushDirection.getDirection();
        return single == null ? EnumSet.allOf(Direction.class) : EnumSet.of(single);
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(getPartItem());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(getPartItem());
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
                continue;
            }
            part.logic.tickCraftedContents();
        }
    }

    // ---- 库存宿主 -------------------------------------------------------------

    @Override
    public void saveChanges() {
        getHost().markForSave();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        saveChanges();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        if (inv == diskInventory) {
            // 插拔磁盘或盘内内容变了：重建公开出去的样板列表。
            refreshFromDisks();
            saveChanges();
        }
    }

    /** 按当前磁盘内容重建样板列表，并作废缓存视图。 */
    public void refreshFromDisks() {
        refreshFromDisks(false);
    }

    /**
     * @param force 磁盘没动也强制重建——内存卡导入会改写镜像而磁盘原地不动，状态比较看不出这一点
     */
    public void refreshFromDisks(boolean force) {
        logic.refreshPatternsFromDisks(force);
        terminalView.invalidate(); // 下一次打开终端时重新扫盘
    }

    /**
     * 清空镜像而不触发逐槽通知——plain {@code clear()} 会每槽一次全量重解析，两个调用方都只要空终态。
     */
    private void clearMirror() {
        logic.clearMirror();
    }

    /** 终端里真取走东西之后作废缓存视图并落盘。 */
    private void markTerminalChanged() {
        refreshFromDisks();
        saveChanges();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return terminalView.view();
    }

    // ---- 部件生命周期 ---------------------------------------------------------

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        this.logic.onMainNodeStateChanged();
    }

    /** 面板进入世界：登记进节拍表。 */
    @Override
    public void addToWorld() {
        super.addToWorld();
        LOADED.add(this);
        this.logic.updatePatterns();
    }

    /** 面板离开世界：注销，否则节拍表里会一直留一个只能靠 GC 收走的空壳。 */
    @Override
    public void removeFromWorld() {
        LOADED.remove(this);
        super.removeFromWorld();
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        // 与 AE2 的线缆样板供应器同尺寸。
        bch.addBox(2, 2, 14, 14, 14, 16);
        bch.addBox(5, 5, 12, 11, 11, 14);
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 4;
    }

    @Override
    public IPartModel getStaticModels() {
        return MODELS;
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        this.logic.readFromNBT(data, registries);
        diskInventory.readFromNBT(data, "disks", registries);
        this.pushDirection = parsePushDirection(data.getString(NBT_PUSH_DIRECTION));
        refreshFromDisks();
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        this.logic.writeToNBT(data, registries);
        diskInventory.writeToNBT(data, "disks", registries);
        if (pushDirection != null) {
            data.putString(NBT_PUSH_DIRECTION, pushDirection.getSerializedName());
        }
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        // 真身只有磁盘：逻辑填出来的样板栏是它们的镜像（方块实体那份注释里有同样的推理）。
        for (int i = 0; i < diskInventory.size(); i++) {
            ItemStack stack = diskInventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
        // AE2 的 addDrops 会把样板栏一起掉出来，对我们是镜像。先清空，再让它掉待发送缓冲、返还栏与升级槽。
        clearMirror();
        logic.addDrops(drops);
        // 部件被拆下时总是要先收集掉落再自清，两步都做成幂等的。
        clearContent();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        diskInventory.clear();
        logic.clearContent();
        terminalView.invalidate();
    }

    /**
     * 导入内存卡时保住磁盘镜像：AE2 的默认实现会清掉供应器的样板栏并把里面的样板交给玩家，而这里那栏只是
     * 磁盘的镜像，照做会把磁盘上已有的样板复制一份。
     *
     * <p>
     * 导出那边不用管：本类继承的是 {@link AEBasePart}，不是 AE2 的 {@code PatternProviderPart}——把样板段写
     * 进内存卡的那段代码在后者里，前者没有，所以面板写出去的卡生来就不带样板。（方块形态要显式清空，是因为
     * 它的父类会转发给逻辑。）
     * </p>
     */
    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        var cleanInput = withoutPatterns(input);
        if (mode == SettingsFrom.MEMORY_CARD) {
            // 镜像马上会被 super.importSettings 改写，而磁盘没动——状态比较看不出这种变化。
            clearMirror();
        }
        super.importSettings(mode, cleanInput, player);

        if (mode == SettingsFrom.MEMORY_CARD) {
            // 本类继承的是 AEBasePart，不是 AE2 的 PatternProviderPart，所以设置不会自己走到逻辑那去。
            logic.importSettings(cleanInput, player);
            // 必须强制重建：磁盘没动，状态检查会跳过，镜像就停在 super.importSettings 留下的样子。
            refreshFromDisks(true);
        }
    }

    /**
     * 剥离内存卡里的样板段。旧版本、或 AE2 自己的供应器写出来的卡仍带着样板，导入时那些会被当作真实内容、
     * 为磁盘上已有的配方扣空白样板，然后在下次刷新时被丢掉。
     */
    private static DataComponentMap withoutPatterns(DataComponentMap input) {
        var sanitized = DataComponentMap.builder().addAll(input);
        sanitized.set(AEComponents.EXPORTED_PATTERNS, ItemContainerContents.EMPTY);
        return sanitized.build();
    }

    @Override
    public void onNeighborChanged(BlockGetter level, BlockPos pos, BlockPos neighbor) {
        // 面板贴在线缆上，红石是经线缆方块传过来的；AE2 自己的线缆样板供应器同样这么接，少了它被脉冲锁住的
        // 供应器会一直不解锁。
        logic.updateRedstoneState();
    }

    @Override
    public boolean onUseWithoutItem(Player p, Vec3 pos) {
        if (!p.level().isClientSide()) {
            // 走宿主自己的 openMenu：它指向本设备的菜单（这个方法由 MeteoritePatternProviderHost 覆写）。
            openMenu(p, MenuLocators.forPart(this));
        }
        return true;
    }

    /**
     * 扳手切「全向 / 定向」。顺序是固定的，不依赖点的哪个面（面板没有「点哪一面」这回事）：
     * 贴附面（默认档）→ 全向 → 其余五个面逐个 → 回贴附面。方块形态才是「点某一面」那一套。
     * 面板没有箭头模型，所以换档后在动作栏报一声，否则玩家根本看不出这一扳手做了什么。
     */
    @Override
    public boolean onUseItemOn(ItemStack heldItem, Player player, InteractionHand hand, Vec3 pos) {
        if (InteractionUtil.canWrenchRotate(heldItem) && !InteractionUtil.isInAlternateUseMode(player)) {
            if (!player.level().isClientSide()) {
                var next = nextPushDirection();
                this.pushDirection = next;
                saveChanges();
                // 动作栏那一条的开关在 common 配置里：这句由服务端发，客户端配置拦不住它。
                var noticeKey = "gui.ae2_pattern_disk.pattern_disk_provider.push_direction";
                if (AEPDCommonConfig.isMessageShown(noticeKey)) {
                    player.displayClientMessage(
                            Component.translatable(noticeKey, directionLabel(next)),
                            true);
                }
            }
            return true;
        }
        return super.onUseItemOn(heldItem, player, hand, pos);
    }

    /** 下一档。贴附面（默认档）→ 全向 → 其余五个面逐个 → 回贴附面。 */
    private PushDirection nextPushDirection() {
        var cycle = pushDirectionCycle();
        var current = pushDirection == null ? cycle.get(0) : pushDirection;
        int index = cycle.indexOf(current);
        return cycle.get(index < 0 ? 0 : (index + 1) % cycle.size());
    }

    private List<PushDirection> pushDirectionCycle() {
        var mounting = PushDirection.fromDirection(getSide());
        var cycle = new ArrayList<PushDirection>();
        cycle.add(mounting);
        cycle.add(PushDirection.ALL);
        for (var direction : Direction.values()) {
            var candidate = PushDirection.fromDirection(direction);
            if (candidate != mounting) {
                cycle.add(candidate);
            }
        }
        return cycle;
    }

    /** 档位的可读名；{@code ALL} 说成「全向」，其余说成那个面。 */
    private static Component directionLabel(PushDirection direction) {
        var single = direction.getDirection();
        if (single == null) {
            return Component.translatable("gui.ae2_pattern_disk.pattern_disk_provider.push_direction.all");
        }
        return Component.translatable("gui.ae2_pattern_disk.pattern_disk_provider.push_direction.side",
                single.getName());
    }

    /** 存档里的档位；认不出（含空串）就当作没调过。 */
    private static @Nullable PushDirection parsePushDirection(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        if (PushDirection.ALL.getSerializedName().equals(stored)) {
            return PushDirection.ALL;
        }
        var side = Direction.byName(stored);
        return side == null ? null : PushDirection.fromDirection(side);
    }
}

package io.github.lounode.ae2pattern.common.part;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNodeListener;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEItemKey;
import appeng.api.util.AECableType;
import appeng.block.crafting.PushDirection;
import appeng.parts.AEBasePart;
import appeng.util.InteractionUtil;
import appeng.util.SettingsFrom;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;

import io.github.lounode.ae2pattern.config.AEPDCommonConfig;
import io.github.lounode.ae2pattern.common.logic.DiskHostSupport;
import io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic;

/**
 * 两台「带样板磁盘的面板供应器」共用的那半：磁盘栏、镜像语义、推入方向与几何尺寸。
 *
 * <p>
 * 磁盘那一半全在 {@link DiskHostSupport} 里（与方块形态共用一份）；本类补的是<b>面板独有</b>的那几件事：
 * 贴附面顶替了「点哪一面」的方位参照、线缆部件的碰撞箱与连接长度、红石经线缆方块转发、以及部件生命周期。
 * 两块面板原来把这些各抄了一遍（约 110 行），现在只有这里一份。
 * </p>
 *
 * <p>
 * 子类只留真正不同的三件：注册用的模型常量、供应器逻辑的类型（普通 / 自装配）、以及自装配那份额外的回送节拍。
 * 菜单入口不用各写：{@code openMenu} 由各自的宿主接口默认方法决定，本类统一调它。
 * </p>
 *
 * @param <L> 该面板使用的供应器逻辑类型；磁盘那半只按 {@link PatternDiskProviderLogic} 用
 */
public abstract class AbstractDiskPatternProviderPart<L extends PatternDiskProviderLogic> extends AEBasePart
        implements InternalInventoryHost, DiskHostSupport.Host {

    /** 推入方向的存档键。属存档协议面：改名字旧存档里的档位就读不回来，所以与磁盘栏的键（{@code DiskHostSupport.NBT_DISKS}）一样公开。 */
    public static final String NBT_PUSH_DIRECTION = "pushDirection";

    private final DiskHostSupport disks;

    /**
     * 子类的供应器逻辑。在基类构造里建好，子类不再持有自己的字段。
     *
     * <p>
     * <b>子类 {@link #createLogic()} 里只能用静态量与本对象的既有方法</b>：它在基类构造期被调用，那时子类字段还没
     * 初始化。（现有的两个实现都只传一个懒取磁盘栏的 supplier，不碰自己的字段。）
     * </p>
     */
    protected final L logic;

    /**
     * 推入方向（扳手可调）。{@code null} = 没被扳手动过，保持旧行为：只朝自己贴附的那一面推。
     * 一旦动过就按 AE2 那套档位走：{@code ALL} = 六面都推，其余 = 只推那一面。
     */
    private @Nullable PushDirection pushDirection;

    protected AbstractDiskPatternProviderPart(IPartItem<?> partItem, int diskSlots) {
        super(partItem);
        this.disks = new DiskHostSupport(this, this::currentLogic, diskSlots);
        this.logic = createLogic();
    }

    /** @return 本面板使用的供应器逻辑实例；构造期由基类调用，见 {@link #logic} 的说明 */
    protected abstract L createLogic();

    /** @return 静态模型（每个面板一份，注册在自己的配方/模型文件里） */
    protected abstract IPartModel models();

    /** 构造期 {@code logic} 还没赋值时返回 {@code null}；磁盘那半在拿到逻辑前只做空转。 */
    private @Nullable PatternDiskProviderLogic currentLogic() {
        return logic;
    }

    // ---- 磁盘那半（委托） -------------------------------------------------

    public AppEngInternalInventory getDiskInventory() {
        return disks.getDiskInventory();
    }

    /** 样板访问终端读的那份视图：磁盘上的样板展开成行，写入落回磁盘。 */
    public InternalInventory getTerminalPatternInventory() {
        return disks.getTerminalPatternInventory();
    }

    /** 按当前磁盘内容重建样板列表，并作废缓存视图。 */
    public void refreshFromDisks() {
        disks.refreshFromDisks();
    }

    /**
     * @param force 磁盘没动也强制重建——内存卡导入会改写镜像而磁盘原地不动，状态比较看不出这一点
     */
    public void refreshFromDisks(boolean force) {
        disks.refreshFromDisks(force);
    }

    // ---- 库存宿主 ------------------------------------------------------------

    /** 面板的保存落在宿主方块实体上（{@link AEBasePart} 没有自己的存档标记）。 */
    public void saveChanges() {
        getHost().markForSave();
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inv) {
        disks.saveChangedInventory(inv);
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        disks.onChangeInventory(inv, slot);
    }

    // ---- 宿主契约（接口由子类声明，实现都在这里） -------------------------------

    public L getLogic() {
        return logic;
    }

    public BlockPos getBlockPos() {
        var host = getBlockEntity();
        return host != null ? host.getBlockPos() : BlockPos.ZERO;
    }

    /** 同一根线缆上的多块面板共用一个方块坐标，所以它们的身份得由所贴的那一面区分。 */
    public int getIdentitySalt() {
        return getSide().name().hashCode();
    }

    public EnumSet<Direction> getTargets() {
        // 没被扳手调过：沿用旧行为——只朝自己贴附的那一面推。调过之后：全向 = 六面都推，定向 = 只推那一面。
        if (pushDirection == null) {
            return EnumSet.of(getSide());
        }
        var single = pushDirection.getDirection();
        return single == null ? EnumSet.allOf(Direction.class) : EnumSet.of(single);
    }

    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(getPartItem());
    }

    public ItemStack getMainMenuIcon() {
        return new ItemStack(getPartItem());
    }

    // ---- 部件生命周期 --------------------------------------------------------

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        this.logic.onMainNodeStateChanged();
    }

    @Override
    public void addToWorld() {
        super.addToWorld();
        onAddedToWorld();
        this.logic.updatePatterns();
    }

    @Override
    public void removeFromWorld() {
        onRemovedFromWorld();
        super.removeFromWorld();
    }

    /** 进入世界后的额外登记（自装配面板靠它进回送节拍表）。 */
    protected void onAddedToWorld() {
    }

    /** 离开世界时撤销 {@link #onAddedToWorld()} 做的事，别把空壳留在登记表里。 */
    protected void onRemovedFromWorld() {
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
        return models();
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        this.logic.readFromNBT(data, registries);
        disks.load(data, registries);
        this.pushDirection = parsePushDirection(data.getString(NBT_PUSH_DIRECTION));
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        this.logic.writeToNBT(data, registries);
        disks.save(data, registries);
        if (pushDirection != null) {
            data.putString(NBT_PUSH_DIRECTION, pushDirection.getSerializedName());
        }
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        // 真身只有磁盘：逻辑填出来的样板栏是它们的镜像（方块形态那份注释里有同样的推理）。
        disks.collectDiskDrops(drops);
        // AE2 的 addDrops 会把样板栏一起掉出来，对我们是镜像。先清空，再让它掉待发送缓冲、返还栏与升级槽。
        disks.clearMirror();
        logic.addDrops(drops);
        // 部件被拆下时总是先收集掉落再自清，两步都做成幂等的。
        clearContent();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        disks.clearDisks();
        logic.clearContent();
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
        var cleanInput = DiskHostSupport.withoutPatterns(input);
        if (mode == SettingsFrom.MEMORY_CARD) {
            // 镜像马上会被 super.importSettings 改写，而磁盘没动——状态比较看不出这种变化。
            disks.clearMirror();
        }
        super.importSettings(mode, cleanInput, player);

        if (mode == SettingsFrom.MEMORY_CARD) {
            // 本类继承的是 AEBasePart，不是 AE2 的 PatternProviderPart，所以设置不会自己走到逻辑那去。
            logic.importSettings(cleanInput, player);
            // 必须强制重建：磁盘没动，状态检查会跳过，镜像就停在 super.importSettings 留下的样子。
            disks.refreshFromDisks(true);
        }
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
            openHostMenu(p);
        }
        return true;
    }

    /**
     * 打开本设备自己的菜单。
     *
     * <p>
     * 这里不直接调 {@code openMenu(...)}：两个子类的宿主接口各自带一份默认实现（普通面板指向样板磁盘供应器菜单，
     * 自装配面板指向自己的），而默认方法在继承链里会被基类继承的那份遮蔽。让子类各自指名菜单，比让它们靠默认方法
     * 解析得清楚。
     * </p>
     */
    protected abstract void openHostMenu(Player player);

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

    /** 下一档。顺序是固定的：贴附面（默认档）→ 全向 → 其余五个面逐个 → 回贴附面。 */
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

package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.menu.implementations.MenuTypeBuilder;

import io.github.lounode.ae2pattern.api.IPatternDiskHost;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.part.PatternDiskManagementTerminalPart;
import io.github.lounode.ae2pattern.network.DiskContentPayload;
import io.github.lounode.ae2pattern.network.DiskHostListPayload;
import io.github.lounode.ae2pattern.network.DiskListPayload;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;

/**
 * The pattern disk management terminal's menu: the encoding terminal's menu plus the data its table needs.
 *
 * <p>The parent already does the hard part - finding every disk host on the grid, assigning stable serials and
 * pushing a flat list. On top of that this menu adds two things:</p>
 *
 * <ul>
 *   <li><b>Grouping.</b> {@link #onDiskListRebuilt} turns the flat list into
 *       {@link DiskHostListPayload.HostGroup}s using the serial → host mapping the parent keeps, so the table
 *       can print a header per machine without the server having to send host objects.</li>
 *   <li><b>Contents on demand.</b> A disk holds up to 1024 patterns, and the table draws many disks at once,
 *       so contents are not part of the list. The client reports which serials it is showing
 *       ({@link VisibleDisksPayload}) and the server pushes those disks' patterns, skipping any disk whose
 *       contents have not changed since the last push ({@link DiskContentPayload}).</li>
 * </ul>
 *
 * <p>Everything else - marks, renaming, the encoding area, the auto-write target - is inherited unchanged, so
 * both terminals act on disks exactly the same way.</p>
 */
public class PatternDiskManagementTermMenu extends PatternDiskEncodingTermMenu {

    // 不可用 build()：理由同父类，单通道注册。
    public static final MenuType<PatternDiskManagementTermMenu> TYPE = MenuTypeBuilder
            .create(PatternDiskManagementTermMenu::new, PatternDiskManagementTerminalPart.class)
            .buildUnregistered(ResourceLocation.parse("ae2_pattern_disk:pattern_disk_management_terminal"));

    /** 客户端侧：服务端推送的分组清单（Screen 每帧读）。 */
    private List<DiskHostListPayload.HostGroup> hostList = List.of();

    /** 客户端侧：服务端最近一次是按哪种显示模式筛的清单，屏幕用它回显按钮图标。 */
    private ShowPatternProviders shownProviders = ShowPatternProviders.VISIBLE;

    private static final String ACTION_INSERT_DISK = "insertDisk";
    private static final String ACTION_STORE_INVENTORY_DISK = "storeInventoryDisk";

    /** 分组键 → 宿主，与推给客户端的清单同一份口径：客户端只拿得到键，存入时得靠它找回宿主。 */
    private final Map<String, IPatternDiskHost> hostsByKey = new HashMap<>();

    /** 客户端侧：按序列号缓存的磁盘内容（Screen 每帧读）。 */
    private final Long2ObjectOpenHashMap<List<ItemStack>> diskContents = new Long2ObjectOpenHashMap<>();

    /** 服务端侧：客户端当前可见的磁盘，由 {@link VisibleDisksPayload} 上报。 */
    private final LongSet visibleDisks = new LongOpenHashSet();

    /** 服务端侧：上次推送内容时的指纹，用来避免滚动一下就把整盘重发一遍。 */
    private final Long2IntOpenHashMap sentContentFingerprints = new Long2IntOpenHashMap();

    /** 服务端侧：上次推送分组清单时的显示模式，换档就重推一次。 */
    private ShowPatternProviders lastShowPatternProviders = ShowPatternProviders.VISIBLE;

    /** 服务端侧：最近一次的扁平磁盘清单，换档时按它重新分组（清单本身没变，没必要重新扫网）。 */
    /** 服务端侧：与磁盘清单同一刻采到的宿主名单；分组的骨架就是它（含没插盘的机器）。 */
    private List<IPatternDiskHost> lastDiskHosts = List.of();

    /** 服务端最近一次推给客户端的磁盘清单，用来重推分组（例如「显示模式」换档时）。 */
    private List<DiskListPayload.DiskEntry> lastDiskEntries = List.of();

    public PatternDiskManagementTermMenu(int id, Inventory ip, PatternDiskManagementTerminalPart host) {
        // 必须显式传本类的 TYPE：走父类那个只收 (id, ip, host) 的构造器会拿到编码终端的菜单类型，
        // 客户端据此查到的是编码终端的屏幕。
        super(TYPE, id, ip, host);
        registerClientAction(ACTION_INSERT_DISK, InsertDiskRequest.class, this::insertDisk);
        registerClientAction(ACTION_STORE_INVENTORY_DISK, StoreInventoryDiskRequest.class, this::storeInventoryDisk);
    }

    /**
     * 把光标上那张样板磁盘放进指定宿主的空磁盘槽。
     *
     * <p>客户端只报「哪台机器」，具体哪个槽位由服务端挑第一个收得下的：客户端手上的清单本来就可能
     * 落后一帧，让客户端报槽位反而容易写错地方。放进去之后立刻重推磁盘清单，表与内容跟着更新。</p>
     */
    public void insertDisk(InsertDiskRequest request) {
        if (isClientSide()) {
            sendClientAction(ACTION_INSERT_DISK, request);
            return;
        }
        if (request == null) {
            return; // 改造过的客户端可以发空参数；宁可什么都不做，也不掉 NPE
        }

        var host = hostsByKey.get(request.hostKey);
        if (host == null) {
            notifyPlayer(false, "gui.ae2_pattern_disk.management_terminal.disk_store.no_host");
            return;
        }

        // 只认光标上那张盘：这一条手势就是「把手上这张放进去」。
        var carried = getCarried();
        if (!(carried.getItem() instanceof PatternDiskItem)) {
            notifyPlayer(false, "gui.ae2_pattern_disk.management_terminal.disk_store.no_disk");
            return;
        }

        if (!tryInsertIntoHost(host, carried)) {
            notifyPlayer(false, "gui.ae2_pattern_disk.management_terminal.disk_store.no_room", describeHost(host));
            return;
        }
        setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        // 菜单光标的同步在 broadcastChanges 里（本仓其它修改光标的动作也都显式推一次），不然客户端会同时
        // 看到「容器里多一张」与「鼠标上还拿着那张」。
        broadcastChanges();
        afterDiskStored(host);
    }

    /**
     * 「把背包里的某一张盘放进选中的那张盘所在的容器」：Shift+左键背包里的样板磁盘。
     *
     * <p>目标容器不取鼠标下的格子，而取**被选中的那张盘**（右键选中/打标）所在的宿主——这条手势的意思
     * 就是「跟这张盘放一起」，而光标此刻在背包上，没有提供容器的位置。</p>
     */
    public void storeInventoryDisk(StoreInventoryDiskRequest request) {
        if (isClientSide()) {
            sendClientAction(ACTION_STORE_INVENTORY_DISK, request);
            return;
        }
        if (request == null) {
            return;
        }

        // 宿主从被选中的那张盘反查（与磁盘清单同一份映射），客户端只报序列号。查不到就是那张盘已经不在了：
        // 这里的宿主是“由盘推出来”的，盘没了自然也没容器可存。
        var host = diskHostOf(request.targetDiskSerial);
        if (host == null) {
            notifyPlayer(false, "gui.ae2_pattern_disk.management_terminal.disk_store.disk_gone");
            return;
        }

        var player = getPlayer();
        var inventory = player.getInventory();
        if (request.containerSlot < 0 || request.containerSlot >= inventory.getContainerSize()) {
            notifyPlayer(false, "gui.ae2_pattern_disk.management_terminal.disk_store.slot_changed");
            return;
        }
        var source = inventory.getItem(request.containerSlot);
        if (!(source.getItem() instanceof PatternDiskItem)) {
            notifyPlayer(false, "gui.ae2_pattern_disk.management_terminal.disk_store.slot_changed");
            return;
        }

        // 减源只由 tryInsertIntoHost 做一次（它按实际插入量减）；这里再减会多扣一张。
        if (!tryInsertIntoHost(host, source)) {
            notifyPlayer(false, "gui.ae2_pattern_disk.management_terminal.disk_store.no_room", describeHost(host));
            return;
        }
        // 背包那份是活的 Inventory 对象（不在槽位包自动同步的范围里），得主动推一次。
        broadcastChanges();
        afterDiskStored(host);
    }

    /**
     * 把 {@code source} 的一张盘写进宿主第一个能收下的空槽；收下了返回 {@code true}（并已就地减掉 source）。
     *
     * <p>「收下了」的判据是返回值里的剩余量真的变少了：AE2 的接口默认实现对拒收的槽会原样返回同一堆栈。
     * 槽位由服务端自己挑（不信任客户端报的槽号：客户端的磁盘清单可能落后一帧）。</p>
     */
    private static boolean tryInsertIntoHost(IPatternDiskHost host, ItemStack source) {
        var inventory = host.getDiskInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var remainder = inventory.insertItem(slot, source.copy(), false);
            if (remainder.getCount() == source.getCount()) {
                continue; // 这个槽没收下（被占了或装不了）
            }
            source.shrink(source.getCount() - remainder.getCount());
            return true;
        }
        return false;
    }

    /** 存入成功后的收尾：重推清单（表、空槽数与盘内容同一拍更新）并报一声。 */
    private void afterDiskStored(IPatternDiskHost host) {
        refreshDiskList(); // 不等下一次扫描
        notifyPlayer(true, "gui.ae2_pattern_disk.management_terminal.disk_store.ok", describeHost(host));
    }

    /**
     * 回执。失败走聊天栏（要说清原因），成功走动作栏：Shift+左键是可连点的手势，每条都往聊天栏写会刷屏。
     */
    private void notifyPlayer(boolean actionBar, String key, Object... args) {
        var player = getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), actionBar);
        }
    }

    @Override
    protected void onDiskListRebuilt(List<DiskListPayload.DiskEntry> entries) {
        this.lastDiskEntries = List.copyOf(entries);
        // 宿主名单跟磁盘清单同一刻采：管理终端要连“一张盘都没插”的机器也列出来，不能只看盘。
        this.lastDiskHosts = collectDiskHosts();

        pushHostList(entries);

        // 清单变了（换盘、加机器）：内容指纹作废，免得新盘拿旧内容顶数。
        sentContentFingerprints.clear();
        if (!isClientSide()) {
            syncVisibleDiskContents();
        }
    }

    @Override
    protected int transferStackToMenu(ItemStack input) {
        // 本屏不进 ME 网络：这里把网络物品栏换成了磁盘表，从背包 Shift+点击塞进去等于让物品从视线里消失，
        // 既没地方拿回来、也没有任何提示。返回 0 之后，玩家侧的快捷移动就只剩「编码输出槽」那一条
        //（见父类的 quickMoveStack），其余一律原地不动——也不再会落到 AE2 那条往 FakeSlot 塞鬼影的回退上。
        return 0;
    }

    /**
     * 把父类的扁平清单按「序列号 → 宿主」分好组推给客户端。分组键取宿主的方块坐标与身份盐——同一根线缆上的
     * 多块面板靠盐区分，与父类的指纹口径一致。
     *
     * <p>「显示模式」在服务端生效：{@code VISIBLE} 档只留
     * {@link IPatternDiskHost#isVisibleInPatternAccessTerminal()} 为真的宿主，{@code ALL} 档全留。开关本身就是
     * AE2 样板访问终端用的那一个键，两个终端共用同一份设置。</p>
     */
    private void pushHostList(List<DiskListPayload.DiskEntry> entries) {
        var mode = getShownProviders();

        var builders = new LinkedHashMap<String, HostBuilder>();
        // 同名重建映射：客户端只报分组键，存入时要能看到此刻的宿主对象。
        hostsByKey.clear();

        // 先给每一台“支持样板磁盘的宿主”开户：没插盘、或盘被抽空的机器也在表里（表要能看出它在、还有几个
        // 空槽）。开完户再把磁盘按槽位挂回去。
        for (var host : lastDiskHosts) {
            if (!isShown(host, mode)) {
                continue;
            }
            var key = hostKey(host);
            hostsByKey.put(key, host);
            builders.computeIfAbsent(key,
                    key2 -> new HostBuilder(key2, describeHost(host), iconOf(host), countEmptySlots(host)));
        }

        for (var entry : entries) {
            var host = diskHostOf(entry.serial());
            if (host == null || !isShown(host, mode)) {
                continue;
            }
            var key = hostKey(host);
            hostsByKey.put(key, host);
            var builder = builders.get(key);
            if (builder == null) {
                // 没能在宿主名单里找到它（例如插件给的是每帧新建的适配器，没进 lastDiskHosts）：
                // 仍旧给它开一组，一张盘不该因为宿主不在名单里就从表里消失。
                builder = new HostBuilder(key, describeHost(host), iconOf(host), countEmptySlots(host));
                builders.put(key, builder);
            }
            builder.disks.add(new DiskHostListPayload.Entry(entry.serial(), entry.stack(),
                    patternCountOf(entry.stack())));
        }

        var groups = new ArrayList<DiskHostListPayload.HostGroup>(builders.size());
        for (var builder : builders.values()) {
            groups.add(new DiskHostListPayload.HostGroup(builder.key, builder.name, builder.icon,
                    List.copyOf(builder.disks), builder.emptySlots));
        }
        sendPacketToClient(new DiskHostListPayload(groups, mode));
    }

    /**
     * 与 AE2 样板访问终端同一口径（{@code PatternAccessTermMenu#isVisible}）：{@code VISIBLE} 只看宿主自己的
     * 「在样板访问终端中显示」开关；{@code NOT_FULL} 再要求它还有空磁盘槽；{@code ALL} 全留。
     *
     * <p>AE2 那个终端还为 {@code NOT_FULL} 维护一份「打开终端时就已经可见」的宿主白名单，本屏没有对应 UI，
     * 所以只按“是否已满”判断——语义一致，只是少那层保留。</p>
     */
    private static boolean isShown(IPatternDiskHost host, ShowPatternProviders mode) {
        if (!host.isVisibleInPatternAccessTerminal()) {
            return mode == ShowPatternProviders.ALL;
        }
        return mode != ShowPatternProviders.NOT_FULL || !isFull(host);
    }

    /** 磁盘槽全插满了才算满：{@code NOT_FULL} 档就是靠它把满盘机器收起来。 */
    private static boolean isFull(IPatternDiskHost host) {
        var disks = host.getDiskInventory();
        for (int slot = 0; slot < disks.size(); slot++) {
            if (disks.getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 显示模式的当前档位；设置由本终端的部件注册（{@code PatternDiskManagementTerminalPart#registerSettings}），
     * 与 AE2 样板访问终端同一个键，读的也是同一份实例。
     */
    public ShowPatternProviders getShownProviders() {
        return getHost().getConfigManager().getSetting(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS);
    }

    @Override
    public boolean canConfigureTypeFilter() {
        // 本屏没有物品网格，「配置可见类型」筛的是网格里的物品，这里没有可筛的东西，按钮去掉。
        return false;
    }

    @Override
    public void broadcastChanges() {
        if (isClientSide()) {
            return;
        }

        super.broadcastChanges();

        // 显示模式换档：按同一份清单重新分组推送（顺带把新档位带回客户端回显图标）。
        var shownProviders = getShownProviders();
        if (lastShowPatternProviders != shownProviders) {
            lastShowPatternProviders = shownProviders;
            if (!lastDiskEntries.isEmpty()) {
                pushHostList(lastDiskEntries);
            }
        }

        syncVisibleDiskContents();
    }

    // ---- 客户端侧：接收 ----

    public void receiveHostList(List<DiskHostListPayload.HostGroup> hosts, ShowPatternProviders shownProviders) {
        this.hostList = List.copyOf(hosts);
        this.shownProviders = shownProviders;
    }

    /** 服务端最近一次筛清单用的显示模式；屏幕拿它回显按钮图标。 */
    public ShowPatternProviders shownProviders() {
        return shownProviders;
    }

    public List<DiskHostListPayload.HostGroup> getHostList() {
        return hostList;
    }

    public void receiveDiskContents(List<DiskContentPayload.Entry> entries) {
        for (var entry : entries) {
            diskContents.put(entry.serial(), List.copyOf(entry.patterns()));
        }
        // 清单里已经没有的盘丢掉缓存，否则一张被换走的盘会一直留着旧内容。
        var live = new LongOpenHashSet();
        for (var group : hostList) {
            for (var disk : group.disks()) {
                live.add(disk.serial());
            }
        }
        diskContents.keySet().removeIf(serial -> !live.contains(serial));
    }

    /** @return the cached patterns of {@code serial}, or {@code null} when the server has not sent them yet. */
    @Nullable
    public List<ItemStack> getDiskContents(long serial) {
        return diskContents.get(serial);
    }

    // ---- 服务端侧：可见集合与内容推送 ----

    public void receiveVisibleDisks(List<Long> serials) {
        visibleDisks.clear();
        for (long serial : serials) {
            if (visibleDisks.size() >= VisibleDisksPayload.MAX_SERIALS) {
                break;
            }
            visibleDisks.add(serial);
        }
        // 不再可见的盘丢掉指纹：它再回到视野里时要重发一次，而不是拿旧内容顶数。
        sentContentFingerprints.keySet().removeIf(serial -> !visibleDisks.contains(serial));
    }

    private void syncVisibleDiskContents() {
        if (visibleDisks.isEmpty()) {
            return;
        }

        var entries = new ArrayList<DiskContentPayload.Entry>();
        for (var iterator = visibleDisks.iterator(); iterator.hasNext();) {
            long serial = iterator.nextLong();
            var host = diskHostOf(serial);
            int slot = diskSlotOf(serial);
            if (host == null || slot < 0) {
                continue;
            }

            var stack = host.getDiskInventory().getStackInSlot(slot);
            if (!(stack.getItem() instanceof PatternDiskItem diskItem)) {
                continue;
            }

            var patterns = diskItem.contents(stack).patterns();
            int fingerprint = contentFingerprint(patterns);
            int stored = sentContentFingerprints.get(serial);
            if (sentContentFingerprints.containsKey(serial) && stored == fingerprint) {
                continue;
            }
            sentContentFingerprints.put(serial, fingerprint);
            entries.add(new DiskContentPayload.Entry(serial, List.copyOf(patterns)));
        }

        if (!entries.isEmpty()) {
            sendPacketToClient(new DiskContentPayload(entries));
        }
    }

    private static int contentFingerprint(List<ItemStack> patterns) {
        int hash = patterns.size();
        for (var stack : patterns) {
            hash = hash * 31 + ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount();
        }
        return hash;
    }

    // ---- 服务端侧：宿主的分组键、名字与图标 ----

    private static String hostKey(IPatternDiskHost host) {
        return host.getBlockPos().asLong() + "#" + host.getIdentitySalt();
    }

    /**
     * 宿主的显示名：方块自己的名字 + 坐标。
     *
     * <p>{@link IPatternDiskHost} 只说得出位置，说不出自己叫什么，所以名字取自该位置的方块；坐标后缀是为了
     * 同一种机器摆了好几台时能分清是哪一台——组头就靠它区分，光写方块名会看到两行一模一样的标题。</p>
     */
    private String describeHost(IPatternDiskHost host) {
        var pos = host.getBlockPos();
        var level = getPlayer().level();
        var name = level.getBlockState(pos).getBlock().getName().getString();
        return name + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** 这张盘里有多少张样板：表格靠它决定一张盘占几行（内容本身只对屏幕上的盘下发）。 */
    private static int patternCountOf(ItemStack stack) {
        return stack.getItem() instanceof PatternDiskItem diskItem ? diskItem.contents(stack).used() : 0;
    }

    /**
     * 该宿主还剩多少个真正的空槽。只数空格、不用「总槽数 − 盘数」：有些宿主的槽位是共用的
     * （NEO ECO 的样板总线把样板盘与已编码样板放在同一批槽里），那样算出来的差值会把被别人占掉的槽当成空槽。
     */
    private static int countEmptySlots(IPatternDiskHost host) {
        var inventory = host.getDiskInventory();
        int empty = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStackInSlot(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /** 组头图标：方块对应的物品；方块没有物品形态（比如某些线缆面板）时留空，屏幕会退回只画名字。 */
    private ItemStack iconOf(IPatternDiskHost host) {
        var level = getPlayer().level();
        var item = level.getBlockState(host.getBlockPos()).getBlock().asItem();
        if (item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    /**
     * 「存入」动作的参数。AE2 的客户端动作把参数当 JSON 传，所以用公开字段加无参构造器，而不是 record。
     */
    public static final class InsertDiskRequest {
        /** 目标容器的分组键（客户端从表格分组里拿到的那个）。 */
        public String hostKey = "";

        public InsertDiskRequest() {
        }

        public InsertDiskRequest(String hostKey) {
            this.hostKey = hostKey;
        }
    }

    /**
     * 「把背包里的盘存进选中盘所在容器」的参数：只报两张盘的标识（目标＝选中那张的序列号，源＝背包槽号）。
     */
    public static final class StoreInventoryDiskRequest {
        /** 右键选中那张盘的序列号；服务端靠它反查目标容器。 */
        public long targetDiskSerial;
        /** 源盘在玩家背包里的槽号（{@code Slot#getContainerSlot()}）。 */
        public int containerSlot;

        public StoreInventoryDiskRequest() {
        }

        public StoreInventoryDiskRequest(long targetDiskSerial, int containerSlot) {
            this.targetDiskSerial = targetDiskSerial;
            this.containerSlot = containerSlot;
        }
    }

    /** 分组清单在构造过程中的可变形态，建完就转成不可变的 {@link DiskHostListPayload.HostGroup}。 */
    private static final class HostBuilder {
        private final String key;
        private final String name;
        private final ItemStack icon;
        /** 该宿主还剩多少个空槽；终端「隐藏槽位」把这些槽叠成一格。 */
        private final int emptySlots;
        private final List<DiskHostListPayload.Entry> disks = new ArrayList<>();

        private HostBuilder(String key, String name, ItemStack icon, int emptySlots) {
            this.key = key;
            this.name = name;
            this.icon = icon;
            this.emptySlots = emptySlots;
        }
    }
}

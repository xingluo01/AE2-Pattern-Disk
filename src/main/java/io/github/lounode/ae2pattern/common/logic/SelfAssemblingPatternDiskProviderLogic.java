package io.github.lounode.ae2pattern.common.logic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;
import appeng.core.settings.TickRates;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.jetbrains.annotations.Nullable;

import io.github.lounode.ae2pattern.common.util.AeKeyAmountNbt;
import io.github.lounode.ae2pattern.integration.ae2cs.AecsSoftDep;

/**
 * 样板磁盘供应器 + 自装配：可在供应器内部直接完成的合成不再推给外部机器。
 *
 * <p>
 * 磁盘展开那半仍由 {@link PatternDiskProviderLogic} 负责，本类只加「自完成」：样板若能被分子装配台的
 * 合成逻辑执行，就当场算产物、扣网络电量，把产物放进待回送表，由 tick 送回网络（网络塞不下的进返回仓）。
 * 磁盘里的配方与普通样板走同一条路——它们展开进同一个样板栏。
 * </p>
 *
 * <p>
 * <b>来源</b>：自装配核心复制自 AE2 Crystal Science 的 {@code MeteoritePatternProviderLogic}（分支
 * {@code 1.21.1-aepd}，提交 {@code a5d0dc56} 一代）。那边是「一台自己的供应器」，这边是「磁盘供应器
 * 多一种完成方式」，所以只搬那部分：能量与加速曲线、待回送表、产物缓存、tick 回送。节点 flags 与设备
 * 定位没有跟着搬 —— 本设备仍是 AEPD 的供应器，频道与频道密度按 AEPD 自己的来。
 * </p>
 */
public class SelfAssemblingPatternDiskProviderLogic extends PatternDiskProviderLogic implements IUpgradeableObject {

    /** 无加速卡时，一次自完成合成消耗的网络电量。与来源实现一致。 */
    public static final int energyPerWork = 50;

    private final IManagedGridNode mainNode;
    private final IActionSource actionSource;
    private final PatternProviderLogicHost host;

    /** 升级槽。方块是条件注册的，只有设备存在时本类才会被构造，所以这里取得到。 */
    public IUpgradeInventory upgrades = UpgradeInventories.forMachine(
            io.github.lounode.ae2pattern.MeteoritePatternProviderRegistrations.BLOCK.get(), 4,
            this::onUpgradesChange);

    /** 完成产物表：算好的产物先放这里，tick 中再真正对外发送。 */
    private final Object2LongOpenHashMap<AEKey> craftedContents = new Object2LongOpenHashMap<>();

    /** 本轮（tick）已完成的合成次数。 */
    private int worksInRound = 0;

    /** 本轮最多可完成次数。 */
    private int maxWorksInRound = 8;

    /** 速度卡生效数；装了超频卡时它固定为 0。 */
    private int speedCards = 0;

    /** 超频卡数。升级变更不频繁，热路径不扫槽。 */
    private int overclockCards = 0;

    /** 距上次回送经过的 tick 数，用于超频卡的节奏。 */
    private int ticksSinceLastReturn = 0;

    /** 样板产物的解析与缓存都在 {@link AssemblerOutputResolver} 里，这里只持有它。 */
    private final AssemblerOutputResolver outputResolver = new AssemblerOutputResolver();

    public SelfAssemblingPatternDiskProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host,
            int diskSlots, Supplier<AppEngInternalInventory> diskInventorySupplier) {
        super(mainNode, host, diskSlots, diskInventorySupplier);
        this.mainNode = mainNode;
        this.host = host;
        this.actionSource = new MachineSource(mainNode::getNode);
    }

    // ------------------------------------------------------------------ 升级与加速

    private void onUpgradesChange() {
        this.overclockCards = Math.min(4, AecsSoftDep.installedOverloadCards(upgrades));
        this.speedCards = overclockCards > 0 ? 0 : Math.min(4, getInstalledUpgrades(AEItems.SPEED_CARD));
        this.maxWorksInRound = (8 << speedCards) + 128 * overclockCards;
        this.saveChanges();

        // 节奏变了要立刻生效，包括设备正在 SLEEP 的时候。
        this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    /** 回送节奏：超频卡 ≥2 张每 tick，1 张每 4 tick，否则按 AE2 的接口速率。 */
    private int getWorkInterval() {
        if (overclockCards >= 2) {
            return 1;
        }
        if (overclockCards == 1) {
            return 4;
        }
        return TickRates.Interface.getMin();
    }

    private double getEnergyPerWorkAfterSpeed() {
        return energyPerWork << speedCards;
    }

    // ------------------------------------------------------------------ 自装配

    /**
     * 能自完成的样板当场完成，其余照旧推给外部机器。
     *
     * <p>
     * 自完成不经过目标机器，所以它只对分子装配台能执行的样板成立（合成、切石、锻造这些）；处理样板仍走
     * 供应器原本的推送路径。
     * </p>
     */
    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (worksInRound >= maxWorksInRound) {
            return false;
        }
        if (!(patternDetails instanceof IMolecularAssemblerSupportedPattern pattern)) {
            return super.pushPattern(patternDetails, inputHolder);
        }
        // 锁定（红石锁定 / 直到结果）要在自完成之前生效，否则锁定期间仍能直接出产物。
        if (getCraftingLockedReason() != LockCraftingMode.NONE) {
            return super.pushPattern(patternDetails, inputHolder);
        }

        boolean wasEmpty = craftedContents.isEmpty();

        // 先算产物再扣电：算不出来时（摆不出合成表、算不出产物）这次推送根本没发生，
        // 先扣电会随 AE 的每次重试白掉一份电量。
        List<GenericStack> output = outputResolver.resolve(pattern, inputHolder, levelOf());
        if (output == null) {
            return false;
        }

        double neededEnergy = getEnergyPerWorkAfterSpeed();
        if (!tryConsumeEnergyFromGrid(neededEnergy)) {
            return false;
        }

        for (GenericStack stack : output) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) {
                continue;
            }
            craftedContents.addTo(stack.what(), stack.amount());
        }

        saveChanges();
        worksInRound++;

        if (wasEmpty && !craftedContents.isEmpty()) {
            mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
        }
        return true;
    }

    /** 从网络扣除指定电量；不足时不做任何扣除。 */
    private boolean tryConsumeEnergyFromGrid(double energy) {
        IGrid grid = getGrid();
        if (grid == null) {
            return false;
        }
        IEnergyService energyService = grid.getEnergyService();        if (energyService == null) {
            return false;
        }

        double extracted = energyService.extractAEPower(energy, Actionable.MODULATE, PowerMultiplier.ONE);
        if (extracted + 1.0e-9 >= energy) {
            return true;
        }

        try {
            energyService.injectPower(extracted, Actionable.MODULATE);
        } catch (Throwable ignored) {
            // 退不回去就认这点损耗。
        }
        return false;
    }

    /** 归属世界；方块实体还没成型时为 {@code null}，产物就无从算起。 */
    private @Nullable Level levelOf() {
        var be = host.getBlockEntity();
        return be == null ? null : be.getLevel();
    }

    /**
     * 待回送产物按节奏发出去：先网络，塞不下的进返回仓，剩下的留到下一轮。由方块实体的服务端 tick 驱动——
     * AE2 供应器自己的 tick 里那两个方法（{@code doWork}/{@code hasWorkToDo}）是私有的，外面接管不了，也不该
     * 接管：推送那半仍要留给它。
     *
     * @return 本轮是否真的送出了东西
     */
    public boolean tickCraftedContents() {
        if (++ticksSinceLastReturn < getWorkInterval()) {
            return false;
        }
        ticksSinceLastReturn = 0;

        boolean worked = false;
        this.worksInRound = 0;

        if (craftedContents.isEmpty()) {
            return false;
        }

        @Nullable
        MEStorage gridInv = null;
        IGrid grid = getGrid();
        if (grid != null) {
            gridInv = grid.getStorageService().getInventory();
        }

        ObjectIterator<Object2LongMap.Entry<AEKey>> it = craftedContents.object2LongEntrySet().iterator();
        while (it.hasNext()) {
            Object2LongMap.Entry<AEKey> entry = it.next();
            AEKey key = entry.getKey();
            long remaining = entry.getLongValue();
            if (key == null || remaining <= 0) {
                it.remove();
                continue;
            }

            long allInserted = 0;
            if (gridInv != null) {
                long inserted = gridInv.insert(key, remaining, Actionable.MODULATE, actionSource);
                allInserted += inserted;
                remaining -= inserted;
            }
            if (remaining > 0) {
                long inserted = getReturnInv().insert(key, remaining, Actionable.MODULATE, actionSource);
                allInserted += inserted;
                remaining -= inserted;
            }

            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }
            if (allInserted > 0) {
                worked = true;
            }
        }

        if (worked) {
            saveChanges();
        }
        return worked;
    }

    /**
     * 样板访问终端里这一台的分组：优先用玩家自定义名，其次用它服务的那台机器，多台或没有机器时退回自己。
     *
     * <p>
     * 看的那些面是 AE2 供应器自己的口径——连接邻居去掉同为供应器的、以及同网格上的接口；此外邻居本身是
     * 供应器时也跳过。第二条是让并排或串联的供应器不至于全归到一个名字下的关键：这台供应器服务的对象是
     * 机器，永远不是另一台供应器。
     * </p>
     */
    @Override
    public PatternContainerGroup getTerminalGroup() {
        if (host instanceof Nameable nameable && nameable.hasCustomName()) {
            return new PatternContainerGroup(host.getTerminalIcon(), nameable.getCustomName(), List.of());
        }

        var blockEntity = host.getBlockEntity();
        var level = blockEntity == null ? null : blockEntity.getLevel();
        var groups = new LinkedHashSet<PatternContainerGroup>();
        if (level != null) {
            for (var side : getBorrowableSides()) {
                var pos = blockEntity.getBlockPos().relative(side);
                var neighbour = level.getBlockEntity(pos);
                if (neighbour == null || neighbour instanceof PatternProviderLogicHost) {
                    continue;
                }
                var group = PatternContainerGroup.fromMachine(level, pos, side.getOpposite());
                if (group != null) {
                    groups.add(group);
                }
            }
        }

        if (groups.size() == 1) {
            return groups.iterator().next();
        }

        var tooltip = new ArrayList<Component>();
        if (groups.size() > 1) {
            tooltip.add(GuiText.AdjacentToDifferentMachines.text().withStyle(ChatFormatting.BOLD));
            for (var group : groups) {
                tooltip.add(group.name());
                for (var line : group.tooltip()) {
                    tooltip.add(Component.literal("  ").append(line));
                }
            }
        }

        var icon = host.getTerminalIcon();
        return new PatternContainerGroup(icon, icon.getDisplayName(), tooltip);
    }

    /**
     * 该问名字的那些面：AE2 供应器自己的集合，会把邻居里同为供应器的、以及同网格上的接口去掉。这里镜像
     * 父类对同一批面做的过滤。
     */
    private Set<Direction> getBorrowableSides() {
        var sides = EnumSet.noneOf(Direction.class);
        sides.addAll(host.getTargets());

        var node = mainNode.getNode();
        if (node != null) {
            for (var entry : node.getInWorldConnections().entrySet()) {
                var otherNode = entry.getValue().getOtherSide(node);
                var owner = otherNode.getOwner();
                if (owner instanceof PatternProviderLogicHost
                        || (owner instanceof InterfaceLogicHost && otherNode.getGrid().equals(mainNode.getGrid()))) {
                    sides.remove(entry.getKey());
                }
            }
        }
        return sides;
    }

    // ------------------------------------------------------------------ 升级槽与持久化

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag, registries);
        upgrades.writeToNBT(tag, "upgrades", registries);
        AeKeyAmountNbt.write(tag, "crafted_contents", craftedContents, registries);
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.readFromNBT(tag, registries);
        upgrades.readFromNBT(tag, "upgrades", registries);
        AeKeyAmountNbt.read(tag, "crafted_contents", craftedContents, registries);
        onUpgradesChange();
    }

    @Override
    public void addDrops(List<ItemStack> drops) {
        super.addDrops(drops);
        for (ItemStack stack : upgrades) {
            drops.add(stack);
        }
        // 滞留的产物也要落地：网络与返回仓同时塞不下时它们留在待回送表里，拆机不该把它们吞掉。
        var blockEntity = host.getBlockEntity();
        if (blockEntity != null && blockEntity.getLevel() != null) {
            for (var entry : craftedContents.object2LongEntrySet()) {
                entry.getKey().addDrops(entry.getLongValue(), drops, blockEntity.getLevel(),
                        blockEntity.getBlockPos());
            }
        }
        craftedContents.clear();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        upgrades.clear();
        craftedContents.clear();
        outputResolver.clear();
    }

}

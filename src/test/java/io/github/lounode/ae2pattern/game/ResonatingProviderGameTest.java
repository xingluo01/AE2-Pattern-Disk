package io.github.lounode.ae2pattern.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;

import io.github.lounode.ae2cs.common.me.crafting.EncodedResonatingPattern;
import io.github.lounode.ae2cs.common.me.crafting.ResonatingProviderDefaults;
import io.github.lounode.ae2cs.common.me.logic.ResonatingPatternProviderHost;
import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.logic.ProviderExtractionMode;
import io.github.lounode.ae2pattern.integration.ae2cs.resonating.ResonatingPatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.integration.ae2cs.resonating.ResonatingPatternDiskProviderRegistrations;

/**
 * 谐振样板磁盘供应器的运行时断言：注册、实例化、磁盘镜像、绑定目标的拆装往返与镜像只读。
 *
 * <p>
 * 这台设备的类在 AECS 缺席时不会被加载，所以每条用例先读 {@code isRegistered()}；不满足时直接放行，
 * 让没有装 AECS 的测试环境也能跑完。装了 AECS 1.2.1+ 的开发环境会真正走一遍设备路径。
 * </p>
 */
@GameTestHolder(AE2PatternDisk.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResonatingProviderGameTest {

    private static final BlockPos POS = new BlockPos(2, 2, 2);

    @GameTest(template = "empty")
    public static void registeredAndInstantiated(GameTestHelper h) {
        if (!ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            h.succeed();
            return;
        }

        h.setBlock(POS, ResonatingPatternDiskProviderRegistrations.BLOCK.get());
        var be = h.getBlockEntity(POS);
        h.assertTrue(be instanceof ResonatingPatternDiskProviderBlockEntity, "方块实体应是谐振磁盘供应器");
        var provider = (ResonatingPatternDiskProviderBlockEntity) be;
        h.assertTrue(provider instanceof ResonatingPatternProviderHost,
                "必须实现 AECS 绑定器认的 ResonatingPatternProviderHost");
        h.assertTrue(provider.getResonatingLogic() != null, "谐振逻辑应已建立");
        h.assertTrue(provider.getLogic().getPatternInv().size()
                == ResonatingPatternDiskProviderBlockEntity.PATTERN_CAPACITY,
                "样板栏容量应是 9 × 1024");
        h.assertTrue(ResonatingPatternDiskProviderRegistrations.ITEM_PART != null, "面板物品应已注册");

        // 镜像样板栏对外只读：写入被安静拒绝，原栏仍是空的（样板只能走磁盘/终端视图）。
        provider.getLogic().getPatternInv().setItemDirect(0, new ItemStack(Items.IRON_INGOT));
        h.assertTrue(provider.getLogic().getPatternInv().getStackInSlot(0).isEmpty(),
                "镜像样板栏不应接受直接写入");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void diskPatternIsMirrored(GameTestHelper h) {
        if (!ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            h.succeed();
            return;
        }

        h.setBlock(POS, ResonatingPatternDiskProviderRegistrations.BLOCK.get());
        var provider = (ResonatingPatternDiskProviderBlockEntity) h.getBlockEntity(POS);

        var disk = new ItemStack(AEPatternRegistries.ITEM_DISK_1K.get());
        var pattern = PatternDetailsHelper.encodeProcessingPattern(
                List.of(GenericStack.fromItemStack(new ItemStack(Items.IRON_ORE))),
                List.of(GenericStack.fromItemStack(new ItemStack(Items.IRON_INGOT))));
        h.assertTrue(((PatternDiskItem) disk.getItem()).tryInsert(disk, pattern, h.getLevel()),
                "前置条件：样板应能写进空磁盘");
        provider.getDiskInventory().setItemDirect(0, disk);
        provider.refreshFromDisks();

        h.assertTrue(!provider.getLogic().getPatternInv().getStackInSlot(0).isEmpty(),
                "磁盘上的样板应镜像到谐振逻辑的样板栏");
        h.assertTrue(provider.getResonatingLogic().getAvailablePatterns().size() == 1,
                "镜像后谐振逻辑应把这张样板登记为可用样板");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void defaultTargetSurvivesBlockDrops(GameTestHelper h) {
        if (!ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            h.succeed();
            return;
        }

        h.setBlock(POS, ResonatingPatternDiskProviderRegistrations.BLOCK.get());
        var provider = (ResonatingPatternDiskProviderBlockEntity) h.getBlockEntity(POS);

        var target = new EncodedResonatingPattern.Target(
                GlobalPos.of(h.getLevel().dimension(), h.absolutePos(POS)), Direction.NORTH);
        // 走宿主接口的默认方法：绑定器（ae2cs:resonating_linker）调的就是这一条。
        provider.setDefaultInputTarget(0, Optional.of(target));

        // 走真实的方块掉落入口，而不是直接调 writeDefaultsToDrops：要证的是 getDrops 那一段接上了。
        var params = new LootParams.Builder(h.getLevel())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(h.absolutePos(POS)))
                .withParameter(LootContextParams.BLOCK_ENTITY, provider)
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY);
        var drops = ResonatingPatternDiskProviderRegistrations.BLOCK.get()
                .getDrops(h.getBlockState(POS), params);
        h.assertTrue(!drops.isEmpty(), "掉落表应掉出设备物品");

        var read = ResonatingProviderDefaults.readTargets(drops.get(0)).get(0);
        h.assertTrue(read.isPresent() && read.get().equals(target),
                "掉落物应带回绑定器写入的默认目标（拆装不丢绑定）");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void defaultTargetReadBackFromPlacedItem(GameTestHelper h) {
        if (!ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            h.succeed();
            return;
        }

        // 物品上先写目标，再走与真实放置相同的 setPlacedBy 入口。
        var stack = new ItemStack(ResonatingPatternDiskProviderRegistrations.ITEM.get());
        var target = new EncodedResonatingPattern.Target(
                GlobalPos.of(h.getLevel().dimension(), h.absolutePos(POS)), Direction.UP);
        var targets = new ArrayList<>(ResonatingProviderDefaults.emptyTargets());
        targets.set(0, Optional.of(target));
        ResonatingProviderDefaults.writeTargets(stack, targets);

        h.setBlock(POS, ResonatingPatternDiskProviderRegistrations.BLOCK.get());
        var state = h.getBlockState(POS);
        ResonatingPatternDiskProviderRegistrations.BLOCK.get()
                .setPlacedBy(h.getLevel(), h.absolutePos(POS), state, null, stack);

        var provider = (ResonatingPatternDiskProviderBlockEntity) h.getBlockEntity(POS);
        var read = provider.getResonatingLogic().getDefaultInputTargets().get(0);
        h.assertTrue(read.isPresent() && read.get().equals(target),
                "放置时应把物品上的默认目标带进设备");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void extractionModeSurvivesSaveLoad(GameTestHelper h) {
        if (!ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            h.succeed();
            return;
        }

        h.setBlock(POS, ResonatingPatternDiskProviderRegistrations.BLOCK.get());
        var provider = (ResonatingPatternDiskProviderBlockEntity) h.getBlockEntity(POS);
        h.assertTrue(provider.getResonatingLogic().getExtractionMode() == ProviderExtractionMode.OFF,
                "抽取默认应是关");
        provider.getResonatingLogic().setExtractionMode(ProviderExtractionMode.FAST);

        var saved = new CompoundTag();
        provider.saveAdditional(saved, h.getLevel().registryAccess());
        provider.getResonatingLogic().setExtractionMode(ProviderExtractionMode.OFF);
        provider.loadTag(saved, h.getLevel().registryAccess());

        h.assertTrue(provider.getResonatingLogic().getExtractionMode() == ProviderExtractionMode.FAST,
                "抽取档应随方块存档恢复");
        h.succeed();
    }
}

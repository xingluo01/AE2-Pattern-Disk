package io.github.lounode.ae2pattern.game;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.util.SettingsFrom;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * 样板磁盘供应器的运行时断言：那几条「只能靠实机看」的语义搬进 GameTest。
 *
 * <p>
 * 这三条都在纯 JUnit 里跑不起来（要真的方块实体、真的磁盘组件、真的注册表），但它们要证的正是最容易在重构里
 * 静默漂移的东西：<b>拆机只掉磁盘</b>、<b>存档键与读档后的重建</b>、<b>内存卡不带也别导入镜像样板</b>。
 * </p>
 *
 * <p>
 * 跑法：{@code ./gradlew runGameTestServer}（运行配置见 {@code build.gradle} 的 {@code gameTestServer}，
 * 源集就是 {@code src/test}，不会进发布 jar）。模板 {@code empty} 由
 * {@code src/test/resources/data/ae2_pattern_disk/structure/empty.nbt} 提供：GameTest 不会自动造空模板，缺这个
 * 110 字节的空结构会在开测前就抛 {@code Missing test structure}（模板按「holder 命名空间:模板名」解析）。
 * 该文件借自 AE2-Lightning-Tech-Reborn（LGPL-3）的同名测试结构，只存在于测试源集，不分发。
 * </p>
 *
 * <p>
 * 这里只驱动本模组自己的入口（{@code addAdditionalDrops} / {@code saveAdditional+loadTag} /
 * {@code exportSettings+importSettings}），不去模拟「玩家真的挥镐」——那条链路属于 vanilla/AE2，不是本模组改动的
 * 面积；而这三个入口恰好就是生产路径本身。
 * </p>
 */
@GameTestHolder(AE2PatternDisk.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiskProviderGameTest {

    private static final BlockPos POS = new BlockPos(2, 2, 2);

    /**
     * 拆机只掉磁盘：镜像里的样板是磁盘的副本，掉出来就是凭空复制一张；同时确认机器自己的东西
     * （返还仓/待发送缓冲）照旧掉——只掉磁盘不等于「除了磁盘什么都不掉」。
     */
    @GameTest(template = "empty")
    public static void breakingDropsDisksOnly(GameTestHelper h) {
        var provider = provider(h);
        provider.getDiskInventory().setItemDirect(0, diskWithPattern(h));
        provider.refreshFromDisks();
        // 返还仓里放一件产物：它在掉落里出现，才说明 logic.addDrops 那一步没被摸掉。
        provider.getLogic().getReturnInv().setStack(0,
                GenericStack.fromItemStack(new ItemStack(Items.IRON_INGOT, 3)));

        h.assertTrue(!provider.getLogic().getPatternInv().getStackInSlot(0).isEmpty(),
                "镜像应已按磁盘重建；不然这条用例证明不了「拆机时镜像里确实有东西」");

        var drops = new ArrayList<ItemStack>();
        provider.addAdditionalDrops(h.getLevel(), h.absolutePos(POS), drops);

        h.assertTrue(drops.size() == 2, "拆机应掉「1 张磁盘 + 1 份返还仓产物」，实际掉了 " + drops.size() + " 件");
        h.assertTrue(drops.get(0).is(AEPatternRegistries.ITEM_DISK_1K.get()), "第一件必须是那张磁盘本身");
        h.assertTrue(drops.get(1).is(Items.IRON_INGOT), "返还仓里的产物也应掉落（logic.addDrops 没跑就会丢）");
        h.succeed();
    }

    /** 存档键与读档后的重建：键名一改，旧存档里的盘就读不回来；读档不重建，网络就看不见盘上的样板。 */
    @GameTest(template = "empty")
    public static void disksSurviveSaveAndLoad(GameTestHelper h) {
        var provider = provider(h);
        provider.getDiskInventory().setItemDirect(0, diskWithPattern(h));
        provider.refreshFromDisks();

        var saved = new CompoundTag();
        provider.saveAdditional(saved, h.getLevel().registryAccess());
        // AE2 的父类也会把镜像写进存档（键 "patterns"）。留着它，下面的「镜像重建了」就可能只是从 NBT 读回来的，
        // 测不到「读档后按磁盘重建」。摸掉它，镜像就只能由磁盘派生。
        saved.remove(PatternProviderLogic.NBT_MEMORY_CARD_PATTERNS);

        provider.clearContent();
        h.assertTrue(provider.getDiskInventory().getStackInSlot(0).isEmpty(), "清空后磁盘槽应是空的");
        h.assertTrue(mirroredPatterns(provider) == 0, "清空后镜像也是空的：不然「读档后重建」可能只是没被清掉的旧镜像");

        provider.loadTag(saved, h.getLevel().registryAccess());

        h.assertTrue(provider.getDiskInventory().getStackInSlot(0).is(AEPatternRegistries.ITEM_DISK_1K.get()),
                "读档后磁盘应回到槽里（存档键 \"disks\" 属于协议面）");
        h.assertTrue(!provider.getLogic().getPatternInv().getStackInSlot(0).isEmpty(),
                "读档后镜像应由磁盘重建（存档里没有镜像，重建这一步没跑就会空）");
        h.succeed();
    }

    /** 内存卡两个方向都不该碰镜像样板：导出会把副本带到别处，导入会为副本扣玩家的空白样板。 */
    @GameTest(template = "empty")
    public static void memoryCardCarriesNoPatternCopies(GameTestHelper h) {
        var provider = provider(h);
        provider.getDiskInventory().setItemDirect(0, diskWithPattern(h));
        provider.refreshFromDisks();

        var exported = DataComponentMap.builder();
        provider.exportSettings(SettingsFrom.MEMORY_CARD, exported, null);
        var patterns = exported.build().get(AEComponents.EXPORTED_PATTERNS);
        h.assertTrue(patterns == null || patterns.equals(ItemContainerContents.EMPTY),
                "内存卡不该带走镜像样板：那份只是磁盘上的副本");

        // 导入方向：卡里放一张「磁盘上根本没有」的样板。只要样板段没被摘掉，AE2 就会拿它当真、
        // 从玩家背包扣一张空白样板（对应它自己在 importSettings 里的还原流程）。
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().add(AEItems.BLANK_PATTERN.stack());
        var card = DataComponentMap.builder()
                .addAll(exported.build())
                .set(AEComponents.EXPORTED_PATTERNS,
                        ItemContainerContents.fromItems(List.of(pattern(Items.IRON_INGOT, Items.IRON_BLOCK))))
                .build();

        var blanksBefore = blankPatterns(player);
        h.assertTrue(blanksBefore == 1, "前置条件：玩家背包里应有 1 张空白样板，实际 " + blanksBefore);

        provider.importSettings(SettingsFrom.MEMORY_CARD, card, player);

        h.assertTrue(blankPatterns(player) == blanksBefore,
                "导入带样板段的内存卡不得动玩家的空白样板：变了就说明卡上的样板被当成真实内容"
                        + "（前 " + blanksBefore + " 后 " + blankPatterns(player) + "）");
        h.assertTrue(mirroredPatterns(provider) == 1,
                "导入后镜像应仍只是磁盘上的那 1 张，实际 " + mirroredPatterns(provider) + " 张");
        h.succeed();
    }

    // ---- 夹具 ------------------------------------------------------------------

    private static PatternDiskProviderBlockEntity provider(GameTestHelper h) {
        h.setBlock(POS, AEPatternRegistries.BLOCK_PROVIDER.get());
        return h.getBlockEntity(POS);
    }

    private static ItemStack diskWithPattern(GameTestHelper h) {
        var disk = new ItemStack(AEPatternRegistries.ITEM_DISK_1K.get());
        var item = (PatternDiskItem) disk.getItem();
        h.assertTrue(item.tryInsert(disk, pattern(Items.IRON_ORE, Items.IRON_INGOT), h.getLevel()),
                "样板应能写进空磁盘：这里只关心「盘上有样板」这个前置条件");
        return disk;
    }

    /** 一张最小的处理样板。走 AE2 自己的编码入口，不手搓组件。 */
    private static ItemStack pattern(Item input, Item output) {
        return PatternDetailsHelper.encodeProcessingPattern(
                List.of(GenericStack.fromItemStack(new ItemStack(input))),
                List.of(GenericStack.fromItemStack(new ItemStack(output))));
    }

    private static int blankPatterns(net.minecraft.world.entity.player.Player player) {
        return player.getInventory().countItem(AEItems.BLANK_PATTERN.asItem());
    }

    private static int mirroredPatterns(PatternDiskProviderBlockEntity provider) {
        var mirror = provider.getLogic().getPatternInv();
        var count = 0;
        for (var slot = 0; slot < mirror.size(); slot++) {
            if (!mirror.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }
}

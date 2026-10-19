package io.github.lounode.ae2pattern.common.menu;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 轮换算术（{@link ProcessingOutputMath#rotated}）的用例。
 *
 * <p>输入与输出共用这份算术，所以用例按「非空项的相对顺序」断言，不绑定是哪一侧的槽位：处理输入有 81 格、
 * 输出有 27 格，两侧的格数差异不改变结论。</p>
 */
class ProcessingOrderMathTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        try (var loading = mockStatic(LoadingModList.class)) {
            var mods = mock(LoadingModList.class);
            when(mods.getModFiles()).thenReturn(List.of());
            loading.when(LoadingModList::get).thenReturn(mods);
            Bootstrap.bootStrap();
        }
    }

    private static ItemStack stack(Item item) {
        return new ItemStack(item);
    }

    /** 造一个给定长度的槽位数组，前几格依次填入给定物品，其余为空。 */
    private static ItemStack[] slots(int length, ItemStack... filled) {
        var out = new ItemStack[length];
        for (int i = 0; i < length; i++) {
            out[i] = i < filled.length ? filled[i] : ItemStack.EMPTY;
        }
        return out;
    }

    private static long filledCount(ItemStack[] stacks) {
        long filled = 0;
        for (var stack : stacks) {
            if (!stack.isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /**
     * 断言两个格子的内容一致。
     *
     * <p>不直接用 {@code ItemStack#equals}：本测试环境没跑完整数据包加载，两个内容相同的实例在那里会判不等；
     * 轮换只搬引用、不改内容，按「物品 + 数量」比已经足够覆盖这一行为。</p>
     */
    private static void assertSameStack(ItemStack expected, ItemStack actual, String message) {
        assertEquals(expected.getItem(), actual.getItem(), message);
        assertEquals(expected.getCount(), actual.getCount(), message);
    }

    @Test
    void fewerThanTwoEntriesCannotCycle() {
        assertFalse(ProcessingOutputMath.canCycle(slots(6)));
        assertFalse(ProcessingOutputMath.canCycle(slots(6, stack(Items.STONE))));
        assertTrue(ProcessingOutputMath.canCycle(slots(6, stack(Items.STONE), stack(Items.DIRT))));
    }

    @Test
    void rotationMovesFirstEntryToTheEnd() {
        var current = slots(6, stack(Items.STONE), stack(Items.DIRT), stack(Items.CHEST));
        var next = ProcessingOutputMath.rotated(current);

        assertSameStack(stack(Items.DIRT), next[0], "首位换成原来的第二项");
        assertSameStack(stack(Items.CHEST), next[1], "第二项换成原来的第三项");
        assertSameStack(stack(Items.STONE), next[2], "原首位绕回末尾");
    }

    @Test
    void rotationKeepsHolesAndEntryCount() {
        // 首位与第二位之间隔着空槽：空槽保持空，值只在非空位置之间搬。
        var current = slots(6, stack(Items.STONE));
        current[3] = stack(Items.DIRT);
        current[4] = stack(Items.CHEST);

        var next = ProcessingOutputMath.rotated(current);

        assertSameStack(stack(Items.DIRT), next[0], "首位取后面第一个非空项");
        assertTrue(next[1].isEmpty());
        assertTrue(next[2].isEmpty());
        assertSameStack(stack(Items.CHEST), next[3], "第三项前移到第二个非空位置");
        assertSameStack(stack(Items.STONE), next[4], "绕回后落到最后一个非空位置");
        assertTrue(next[5].isEmpty());
        assertEquals(filledCount(current), filledCount(next), "轮换不改变非空项个数");
    }

    @Test
    void rotatingAsManyTimesAsEntriesRestoresOrder() {
        // 输入侧格数远多于输出侧，用 81 格跑一遍，确认绕回与格数无关。
        var original = slots(81, stack(Items.STONE), stack(Items.DIRT), stack(Items.CHEST));
        var current = original;
        for (int i = 0; i < 3; i++) {
            current = ProcessingOutputMath.rotated(current);
        }
        for (int i = 0; i < original.length; i++) {
            assertSameStack(original[i], current[i], "第 " + i + " 格回到原位");
        }
    }

    @Test
    void rotationDoesNotMutateTheInput() {
        var current = slots(4, stack(Items.STONE), stack(Items.DIRT));
        ProcessingOutputMath.rotated(current);

        assertSameStack(stack(Items.STONE), current[0], "入参首格不被改写");
        assertSameStack(stack(Items.DIRT), current[1], "入参第二格不被改写");
    }
}

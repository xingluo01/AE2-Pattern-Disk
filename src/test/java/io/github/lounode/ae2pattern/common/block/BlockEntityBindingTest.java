package io.github.lounode.ae2pattern.common.block;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import net.neoforged.neoforge.registries.DeferredBlock;

import appeng.block.AEBaseEntityBlock;

import io.github.lounode.ae2pattern.AEPatternRegistries;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守「方块注册了，但没绑方块实体」这个坑。
 *
 * <p>
 * AE2 的 {@code AEBaseEntityBlock} 不会自己找 {@code BlockEntityType}：它等 {@code AE2PatternDisk} 在
 * {@code RegisterEvent} 上调 {@code setBlockEntity(...)}。漏了这一步不会让注册失败、也不会让启动失败，
 * 而是等玩家把方块放进世界里时直接 {@code NullPointerException}（{@code newBlockEntity} 里那个
 * {@code blockEntityType} 还是 null）——整局游戏崩掉。
 * </p>
 *
 * <p>
 * 本测试不启动 FML：只反射读 {@link AEPatternRegistries} 的字段类型（不读值，因此不会触发它的静态初始化），
 * 再对着接线源码逐个核对字段名。新增一台 {@code AEBaseEntityBlock} 而忘了接线时，它会红。
 * </p>
 */
class BlockEntityBindingTest {

    /** 方块实体接线所在的那个类。 */
    private static final Path WIRING = Path.of(
            "src", "main", "java", "io", "github", "lounode", "ae2pattern", "AE2PatternDisk.java");

    @Test
    void everyEntityBlockIsBoundToItsBlockEntityType() throws IOException {
        var wiring = Files.readString(WIRING);
        var unbound = new ArrayList<String>();

        for (var field : AEPatternRegistries.class.getDeclaredFields()) {
            if (!DeferredBlock.class.isAssignableFrom(field.getType())) {
                continue;
            }
            var blockType = blockClassOf(field);
            if (blockType == null || !AEBaseEntityBlock.class.isAssignableFrom(blockType)) {
                continue;
            }
            if (!wiring.contains(field.getName() + ".get()")) {
                unbound.add(field.getName() + " (" + blockType.getSimpleName() + ")");
            }
        }

        assertTrue(unbound.isEmpty(),
                () -> "这些方块没有绑定自己的方块实体类型，放进世界会在 AEBaseEntityBlock#newBlockEntity 抛 "
                        + "NullPointerException 直接崩游戏：" + unbound
                        + "；请在 AE2PatternDisk.associateBlockEntities 里补 setBlockEntity/attachTickingBlockEntity");
    }

    /** {@code DeferredBlock<X>} 里的那个 X；拿不到就返回 null（本测试不猜）。 */
    private static Class<?> blockClassOf(Field field) {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType parameterized)) {
            return null;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 1 || !(arguments[0] instanceof Class<?> type)) {
            return null;
        }
        return type;
    }
}

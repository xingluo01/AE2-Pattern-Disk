package io.github.lounode.ae2pattern.common.block;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import net.neoforged.neoforge.registries.DeferredHolder;

import appeng.blockentity.AEBaseBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;

import io.github.lounode.ae2pattern.AEPatternRegistries;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守「方块实体注册了，但能力没接线」这个坑。
 *
 * <p>
 * AE2 的网格与产物回送都靠 capability：没有 {@code IN_WORLD_GRID_NODE_HOST}，线缆找不到这台机器的节点
 * （不崩、不报错，只表现为「这台设备根本没上网」）；没有 {@code GENERIC_INTERNAL_INV}，分子装配台的产物
 * 塞不回返回栏。两类都属于「注册成功但功能静默失效」，实机很难一眼看出来，所以在启动前先用源码核对。
 * </p>
 */
class BlockEntityCapabilityWiringTest {

    private static final Path WIRING = Path.of(
            "src", "main", "java", "io", "github", "lounode", "ae2pattern", "AE2PatternDisk.java");

    @Test
    void everyGridMachineRegistersTheInWorldGridNodeHost() throws IOException {
        var wiring = Files.readString(WIRING);
        var missing = new ArrayList<String>();
        for (var entry : blockEntityFields()) {
            if (!AEBaseBlockEntity.class.isAssignableFrom(entry.type())) {
                continue;
            }
            if (!registers(wiring, "IN_WORLD_GRID_NODE_HOST", entry.name())) {
                missing.add(entry.name());
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "这些方块实体没有注册 IN_WORLD_GRID_NODE_HOST，线缆接不上它的网格节点：" + missing);
    }

    @Test
    void everyPatternProviderRegistersTheGenericInternalInventory() throws IOException {
        var wiring = Files.readString(WIRING);
        var missing = new ArrayList<String>();
        for (var entry : blockEntityFields()) {
            if (!PatternProviderBlockEntity.class.isAssignableFrom(entry.type())) {
                continue;
            }
            if (!registers(wiring, "GENERIC_INTERNAL_INV", entry.name())) {
                missing.add(entry.name());
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "这些供应器没有注册 GENERIC_INTERNAL_INV，装配台的产物塞不回返回栏：" + missing);
    }

    /** {@code IN_WORLD_GRID_NODE_HOST, AEPatternRegistries.<field>.get()} 这一对是否出现在接线源码里。 */
    private static boolean registers(String wiring, String capability, String field) {
        return Pattern.compile(capability + ",\\s*AEPatternRegistries\\." + field + "\\.get\\(\\)")
                .matcher(wiring)
                .find();
    }

    private record BlockEntityField(String name, Class<?> type) {
    }

    private static List<BlockEntityField> blockEntityFields() {
        var found = new ArrayList<BlockEntityField>();
        for (var field : AEPatternRegistries.class.getDeclaredFields()) {
            if (!DeferredHolder.class.isAssignableFrom(field.getType())) {
                continue;
            }
            var entityType = blockEntityTypeOf(field);
            if (entityType != null) {
                found.add(new BlockEntityField(field.getName(), entityType));
            }
        }
        return found;
    }

    /** {@code DeferredHolder<?, BlockEntityType<X>>} 里的那个 X；不是这个形状就返回 null。 */
    private static Class<?> blockEntityTypeOf(Field field) {
        if (!(field.getGenericType() instanceof ParameterizedType holder)) {
            return null;
        }
        // DeferredItem 也是 DeferredHolder 的子类，但它只带一个类型参数：先按形状筛掉，别按下标。
        Type[] holderArguments = holder.getActualTypeArguments();
        if (holderArguments.length != 2) {
            return null;
        }
        Type second = holderArguments[1];
        if (!(second instanceof ParameterizedType beType) || !(beType.getRawType() instanceof Class<?> raw)) {
            return null;
        }
        if (!net.minecraft.world.level.block.entity.BlockEntityType.class.isAssignableFrom(raw)) {
            return null;
        }
        Type[] inner = beType.getActualTypeArguments();
        return inner.length == 1 && inner[0] instanceof Class<?> entityType ? entityType : null;
    }
}

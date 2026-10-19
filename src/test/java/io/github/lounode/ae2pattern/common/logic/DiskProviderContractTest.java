package io.github.lounode.ae2pattern.common.logic;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 守着「默认实现会静默做错事」的那几条契约。
 *
 * <p>
 * 这几条的共同点是：<b>删掉一个覆写方法不会编译失败，只会在运行期表现成另一种行为</b>。
 * </p>
 *
 * <ul>
 * <li>{@code PatternProviderLogicHost#getTerminalPatternInventory()} 的默认实现把 {@code getLogic().getPatternInv()}
 * 交给终端，而那栏对我们是磁盘的派生副本——失去覆写就等于样板访问终端读的是镜像，取走时碰不到磁盘。</li>
 * <li>{@code PatternProviderLogic#getPatternInv()} 本身被本模组覆写成只读视图，第三方上传器写镜像那条路才会被明确
 * 拒绝；覆写一旦消失，写进来的样板又会成为「网络看得见、磁盘里没有」的幻影。</li>
 * </ul>
 *
 * <p>
 * 用类名字符串取 {@code Class}，不在测试里写类字面量：这些类会牵进 AE2 与可选邻居的类型，字面量在类校验阶段就会去
 * 解析它们，缺模组的环境下会先炸在加载上，而不是给出「跳过了」。
 * </p>
 *
 * <p>
 * <b>跳过只允许一种原因：测试环境里没有 AE2。</b>环境齐了却加载不到本仓的类，那是类被改名或依赖被夹断，得当失败报——
 * 否则守门测试会在最该响的时候静默变绿。
 * </p>
 */
class DiskProviderContractTest {

    private static final String LOGIC = "io.github.lounode.ae2pattern.common.logic.PatternDiskProviderLogic";
    private static final String SUPPORT = "io.github.lounode.ae2pattern.common.logic.DiskHostSupport";

    /** 环境哨兵：它在，说明 AE2 在 classpath 上，本仓的类就没有「缺依赖」这个借口。 */
    private static final String ENV_SENTINEL = "appeng.api.inventories.InternalInventory";

    /**
     * 两个抽象宿主 + 四个具体宿主：每个都得自己声明，不能靠接口默认实现回落。
     *
     * <p>新增一台带磁盘的供应器（方块或面板）时，把它的类名加进来——这张表就是契约的覆盖面。</p>
     */
    private static final List<String> HOSTS = List.of(
            "io.github.lounode.ae2pattern.common.block.entity.AbstractDiskPatternProviderBlockEntity",
            "io.github.lounode.ae2pattern.common.part.AbstractDiskPatternProviderPart",
            "io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity",
            "io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderBlockEntity",
            "io.github.lounode.ae2pattern.common.part.PatternDiskProviderPart",
            "io.github.lounode.ae2pattern.common.part.MeteoritePatternProviderPart");

    @Test
    void logicKeepsMirrorHooksOverridden() {
        var logic = require(LOGIC);

        assertNotNull(declaredMethod(logic, "getPatternInv"),
                "PatternDiskProviderLogic 必须继续覆写 getPatternInv()：那是把镜像栏改成只读视图的唯一入口，"
                        + "覆盖一旦消失，第三方上传器写进镜像的样板又会变成既不进磁盘、也不进掉落的幻影样板");
        assertNotNull(declaredMethod(logic, "clearMirror"),
                "PatternDiskProviderLogic 必须保留 clearMirror()：掉落前清镜像、导入内存卡前清镜像都走它");
        assertNotNull(declaredMethod(logic, "clearContent"),
                "PatternDiskProviderLogic 必须覆写 clearContent()：父实现逐槽清镜像，每槽一次通知会把重建量放大成"
                        + "几千万次槽访问");
    }

    @Test
    void everyHostDeclaresItsOwnTerminalPatternInventory() {
        for (var name : HOSTS) {
            var host = require(name);
            assertNotNull(declaredMethod(host, "getTerminalPatternInventory"),
                    host.getName() + " 必须自己声明 getTerminalPatternInventory()：接口默认实现交给终端的是只读镜像，"
                            + "这里要的是落回磁盘的那份视图");
        }
    }

    @Test
    void saveFormatKeysStayLiteral() {
        var support = require(SUPPORT);
        assertEquals("disks", staticString(support, "NBT_DISKS"),
                "磁盘栏的 NBT 键是存档协议面：改了名，旧存档里的盘就读不回来");

        var part = require("io.github.lounode.ae2pattern.common.part.AbstractDiskPatternProviderPart");
        assertEquals("pushDirection", staticString(part, "NBT_PUSH_DIRECTION"),
                "面板推入方向的 NBT 键同样是存档协议面");
    }

    /**
     * @return 已加载的类；环境缺 AE2 时整条测试跳过，否则加载不到就是失败
     */
    private static Class<?> require(String name) {
        var type = type(name);
        if (type != null) {
            return type;
        }
        assertFalse(envReady(), "测试环境有 AE2，却加载不到 " + name + "：类被改名或依赖被夹断，不能当成跳过");
        assumeTrue(false, "测试环境缺 AE2，跳过");
        return null;
    }

    private static boolean envReady() {
        return type(ENV_SENTINEL) != null;
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, DiskProviderContractTest.class.getClassLoader());
        } catch (Throwable missingDependency) {
            return null;
        }
    }

    /** @return 声明在 {@code type} 自身、public 且非 static 的无参方法；缺了、挪走了、改成 private 都返回 {@code null} */
    private static Method declaredMethod(Class<?> type, String name) {
        try {
            var method = type.getDeclaredMethod(name);
            var modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
                return null;
            }
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String staticString(Class<?> type, String fieldName) {
        try {
            var field = type.getDeclaredField(fieldName);
            field.setAccessible(true);
            return String.valueOf(field.get(null));
        } catch (ReflectiveOperationException e) {
            return "<缺失：" + fieldName + ">";
        }
    }
}

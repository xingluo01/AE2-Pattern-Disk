package io.github.lounode.ae2pattern.common.menu.cell;

import java.lang.reflect.Method;

import org.jetbrains.annotations.Nullable;

import appeng.helpers.IPriorityHost;

/**
 * 存储优先级的读写：AE2 与 EAE 走 {@link IPriorityHost}；ECO 把优先级挂在<b>存储控制器</b>上，青春版挂在
 * 它自己的 L1 宿主上——两者都不在我们的编译面里，只能按方法名探。
 *
 * <p>集成层可以注册一个 {@link CellDriveScanner.PriorityAdapter} 抢先接管（ECO 后来的版本公开了
 * {@code IECOStoragePriorityHost}）；没注册时继续走反射，行为与从前一致。</p>
 *
 * <p>入口判据是「<b>写得进去</b>」而不是「读得到」：AE2 的优先级界面只把值交给宿主，不读回也不报错，对着一个
 * 只有 getter 的设备确认下去，只会让玩家以为改成了。</p>
 */
final class CellStoragePriority {

    private CellStoragePriority() {
    }

    static volatile CellDriveScanner.PriorityAdapter priorityAdapter;

    /** 由集成层在邻居模组在场时调用一次；重复注册以最后一次为准。 */
    static void register(CellDriveScanner.PriorityAdapter adapter) {
        priorityAdapter = adapter;
    }

    /**
     * 存储优先级：ECO 挂在存储控制器上（不是像 AE2 那样挂在单个驱动器上），青春版挂在它自己的 L1 宿主上。
     * 两条路都按方法名探，探不到就认不出。
     *
     * <p>邻居模组把契约公开之后（ECO 21.2.6 的 {@code api.storage.IECOStoragePriorityHost}），集成层可以注册
     * 一个 {@link CellDriveScanner.PriorityAdapter} 抢先接管；没注册（或缺那个模组）时继续走方法名反射，行为与从前一致。</p>
     *
     * @return 读不到返回 null（表格那列就是「—」）
     */
    @Nullable
    static Integer read(Object owner) {
        CellDriveScanner.PriorityAdapter adapter = priorityAdapter;
        if (adapter != null && adapter.handles(owner)) {
            Integer contracted = adapter.read(owner);
            if (contracted != null) {
                return contracted;
            }
        }
        Object controller = controller(owner);
        Object priority = controller != null ? CellProbe.invoke(controller, "getStoragePriority") : null;
        return priority instanceof Integer value ? value : null;
    }

    /** 这台设备的优先级**写不写得进去**：只有读没有写时不给入口。 */
    static boolean canSet(Object owner) {
        if (owner instanceof IPriorityHost) {
            return true;
        }
        CellDriveScanner.PriorityAdapter adapter = priorityAdapter;
        if (adapter != null && adapter.handles(owner)) {
            // 接口在手：它有抽象 setter，编译期就能确认写得进去。
            return true;
        }
        return hasIntSetter(owner) || hasIntSetter(controller(owner));
    }

    /**
     * 探 {@code setStoragePriority(int)}。
     *
     * <p>为什么入口判据是“可写”而不是“可读”：AE2 的优先级界面只把值交给宿主，不读回也不报错，所以对着一个
     * 只有 getter 的设备（ECO 21.2.1–21.2.5 就是：它的 setter 是私有的、还要玩家参数）确认下去只会让玩家以为改成了。</p>
     */
    private static boolean hasIntSetter(@Nullable Object target) {
        if (target == null) {
            return false;
        }
        try {
            target.getClass().getMethod("setStoragePriority", int.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /** 表格里那列优先级显示什么：读不到就是「—」。 */
    static String display(Object owner) {
        Integer value = read(owner);
        return value != null ? Integer.toString(value) : CellDriveScanner.PRIORITY_UNKNOWN;
    }

    /**
     * 把存储优先级写进这台设备的存储控制器；写不进去返回 false。
     *
     * <p>**不是每家都能写**：青春版的 L1 宿主一直是 public 的 {@code setStoragePriority(int)}；ECO 本体自
     * 21.2.6 起把它公开并抽成了 {@code api.storage.IECOStoragePriorityHost}（更早的版本只有 getter，写必失败）。
     * 写不进去就返回 false，调用方据此报一句「只读」，而不是假装写成功。</p>
     */
    static boolean write(Object owner, int value) {
        CellDriveScanner.PriorityAdapter adapter = priorityAdapter;
        if (adapter != null && adapter.handles(owner)) {
            return adapter.write(owner, value);
        }
        Object controller = controller(owner);
        if (controller == null) {
            return false;
        }
        try {
            Method method = controller.getClass().getMethod("setStoragePriority", int.class);
            method.invoke(controller, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /**
     * 这台设备的存储控制器：ECO 本体在驱动器自己身上（{@code getStorageController()}），青春版在
     * {@code getCluster().getController()}（驱动器没有前者，但继承了 {@code NEBlockEntity.getCluster()}）。
     *
     * <p>公开是给集成层的 {@link CellDriveScanner.PriorityAdapter} 用：拿到它才能 {@code instanceof} 邻居那个优先级接口。</p>
     */
    @Nullable
    static Object controller(Object owner) {
        Object controller = CellProbe.invoke(owner, "getStorageController");
        if (controller != null) {
            return controller;
        }
        Object cluster = CellProbe.invoke(owner, "getCluster");
        return cluster != null ? CellProbe.invoke(cluster, "getController") : null;
    }

}

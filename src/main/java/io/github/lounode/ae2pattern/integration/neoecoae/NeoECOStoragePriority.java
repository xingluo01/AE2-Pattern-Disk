package io.github.lounode.ae2pattern.integration.neoecoae;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.dancingsnow.neoecoae.api.storage.IECOStoragePriorityHost;

import io.github.lounode.ae2pattern.common.menu.CellDriveScanner;

/**
 * 把 NEO ECO 的存储优先级公开契约接到元件管理终端。
 *
 * <p>来源：本模组向上游提的 API 请求（{@code DancingSnow0517/NeoECOAEExtension} issue #119）已在 21.2.6 落地——
 * 优先级从「只有私有 setter」变成 {@code api.storage.IECOStoragePriorityHost}。此前终端只能按方法名探，且对
 * ECO 本体只读（那个 setter 既私有又带玩家参数）。</p>
 *
 * <h2>为什么这层桥放在 integration 包</h2>
 *
 * <p>{@link CellDriveScanner} 在总是加载的 {@code main} 源集里，不能出现邻居模组的类型名——ECO 缺席时那会变成
 * 加载期错误。所以由本类（只在契约类存在时才会被注册）实现
 * {@link CellDriveScanner.PriorityAdapter} 并注册进去，契约于是只活在这一层。
 * 调用点（{@code NeoECOIntegration#apply}）会先用字符串探一次类，探不到就连本类也不加载。</p>
 *
 * <h2>仍然靠反射的那一半</h2>
 *
 * <p>优先级记在存储控制器上，不在单格元件座上，所以「从驱动器走到控制器」这一步依旧由
 * {@link CellDriveScanner#storageController(Object)} 按方法名探（ECO 走 {@code getStorageController()}、
 * 青春版走 {@code getCluster().getController()}）；只有拿到控制器之后的读写才是编译期契约。</p>
 *
 * <p>青春版 1.3.6 目前仍未实现该接口（它选的是先挂 {@code util.ICellHost}），所以它的优先级继续走反射——
 * 它那两个方法本来就是 public，行为与从前一致。ECO 更早的版本同理。</p>
 */
public final class NeoECOStoragePriority implements CellDriveScanner.PriorityAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    private NeoECOStoragePriority() {
    }

    /** 公开契约不在旧版 ECO 里：用类名探而不是直接引用类型。 */
    private static final String PRIORITY_CONTRACT =
            "cn.dancingsnow.neoecoae.api.storage.IECOStoragePriorityHost";

    /**
     * 由 {@code NeoECOIntegration#apply()} 调用一次（那边已先用字符串探过一次类）。
     *
     * <p>这里再探一次是防御：本类（含下面的 {@link #priorityHost}）引用了那个接口，而它只在 ECO 21.2.6+ 里
     * 存在。探不到就不注册，终端继续走反射；换了别的调用点也能自己拦住。</p>
     */
    public static void register() {
        if (!classPresent(PRIORITY_CONTRACT)) {
            return;
        }
        try {
            CellDriveScanner.registerPriorityAdapter(new NeoECOStoragePriority());
        } catch (LinkageError mismatch) {
            // 契约类探到了、桥却载不起来：这不是「邻居太旧」，而是本模组的构建与运行期 ECO 错配。
            // 按 ERROR 报（而不是与旧版退化共用一条静默路径），否则这种错配会被当成正常退场。
            LOGGER.error("[AE2-Pattern-Disk] the NEO ECO storage priority contract is present but this mod's"
                    + " bridge could not be linked; the priority column stays read-only", mismatch);
        }
    }

    private static boolean classPresent(String name) {
        try {
            // initialize=false：只要「类在」，不触发静态初始化。
            Class.forName(name, false, NeoECOStoragePriority.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    @Override
    public boolean handles(Object owner) {
        return priorityHost(owner) != null;
    }

    @Override
    @Nullable
    public Integer read(Object owner) {
        IECOStoragePriorityHost host = priorityHost(owner);
        return host != null ? host.getStoragePriority() : null;
    }

    @Override
    public boolean write(Object owner, int value) {
        IECOStoragePriorityHost host = priorityHost(owner);
        if (host == null) {
            return false;
        }
        host.setStoragePriority(value);
        return true;
    }

    /**
     * 找出这台设备（或其存储控制器）身上的优先级宿主；没有就返回 null，让扫描器回退到反射。
     *
     * <p>驱动器自己实现该接口时要先认它：那说明优先级就在这一层，不必再往下走控制器。</p>
     */
    @Nullable
    private static IECOStoragePriorityHost priorityHost(Object owner) {
        if (owner instanceof IECOStoragePriorityHost host) {
            return host;
        }
        Object controller = CellDriveScanner.storageController(owner);
        return controller instanceof IECOStoragePriorityHost host ? host : null;
    }
}

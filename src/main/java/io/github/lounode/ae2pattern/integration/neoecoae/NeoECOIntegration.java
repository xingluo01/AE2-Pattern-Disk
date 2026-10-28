package io.github.lounode.ae2pattern.integration.neoecoae;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.stacks.AEItemKey;

import cn.dancingsnow.neoecoae.api.integration.Integration;
import cn.dancingsnow.neoecoae.api.me.provider.ECOParallelCraftingProviders;

import io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.menu.CellBulkCutoffCompat;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * Integration entry point for NEO ECO AE Extension ({@code neoecoae}).
 *
 * <p>NEO ECO's {@code IntegrationManager} discovers this class at startup through
 * {@code @Integration("ae2_pattern_disk")} and calls {@link #apply()}. Each feature is wired in its own step
 * and each step is wrapped on its own: this runs inside NEO ECO's constructor, so a throw from one step must
 * not take the other - or the game - down with it.</p>
 *
 * <h2>The parallel intake contract</h2>
 *
 * <p>NEO ECO's CPU hands a provider that answers its parallel contract a whole batch instead of one craft at
 * a time, and the batch assembler can honour that. It must not name the ECO type itself - NEO ECO is an
 * optional dependency - so it is registered here, keyed by its class, and the contract is reached through
 * {@link BatchAssemblerParallelIntake}. This replaces a mixin that added the interface to the machine
 * directly: that reached the same lookup, but only by touching the machine's class, which then depended on
 * when and whether the mixin was applied. A registration is the same lookup without the class surgery.</p>
 *
 * <h2>The encoding terminal's upload button</h2>
 *
 * <p>NEO ECO's pattern storage reports how an upload ended, which is what lets that button tell "a disk took
 * it" from "nothing happened". Without the report an answer could only be guessed, and a wrong guess hands a
 * blank pattern back for one that was merely moved. So the button stays off when the report is absent -
 * the same rule as before, now checked on its own rather than behind another feature's guard.</p>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>The disk side is not wired here. NEO ECO compiles against this mod's {@code PatternDiskApi} and
 * registers its own disk holders and terminal views, so the reflection this class used to carry - the bus
 * accessor, the auxiliary store it called back into, the terminal hook - has been deleted along with the
 * store the bus used to look for.</p>
 */
@Integration("ae2_pattern_disk")
public class NeoECOIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.neoecoae");

    /**
     * Called once NEO ECO has loaded and this mod is present.
     *
     * <p>Each feature is wired on its own and wrapped on its own, because this runs inside NEO ECO's mod
     * constructor: a throw from here is not a lost feature but a failed game start.</p>
     */
    public void apply() {
        guarded("the encoding terminal upload", NeoECOIntegration::wireEncodingTerminalUpload);
        guarded("the parallel intake registration", NeoECOIntegration::registerParallelIntake);
        guarded("the bulk cell cutoff bridge", NeoECOIntegration::registerBulkCutoff);
        // 这次的 API 请求（issue #119）落地后，优先级读写走公开契约、反射退居兜底。
        // 先探类、再取方法引用：缺契约时连桥类都不加载（加载会解析它 implements 的超接口），
        // 方法体里引用的邻居类型则要等执行时才解析——两道都挡上。探类本身不命名邻居类型。
        if (classPresent(STORAGE_PRIORITY_CONTRACT)) {
            guarded("the storage priority contract", NeoECOStoragePriority::register);
        }
    }

    /** ECO 21.2.6 才有的公开契约（存储优先级的读写口）。 */
    private static final String STORAGE_PRIORITY_CONTRACT =
            "cn.dancingsnow.neoecoae.api.storage.IECOStoragePriorityHost";

    /** ECO 21.2.11 的大宗元件契约；旧版没有，相关按钮不出现。 */
    private static final String BULK_MARKABLE_CELL =
            "cn.dancingsnow.neoecoae.api.storage.IECOBulkMarkableCellItem";
    private static final String BULK_DISPLAY_CELL =
            "cn.dancingsnow.neoecoae.api.storage.IECOBulkDisplayCell";
    private static final String BULK_CHAIN_METHOD = "getCompressionChain";
    private static final String BULK_CUTOFF_METHOD = "getCompressionCutoff";

    /**
     * 只有用本地补丁 jar 构建时才会进源集。上游发版前普通构建走反射兜底，发版后把它移进主源集。
     */
    private static final String BULK_API_ADAPTER =
            "io.github.lounode.ae2pattern.integration.neoecoae.EcoBulkCellsApi";

    /**
     * 大宗元件的压缩截断桥：新版走公开 API，旧版走反射，ECO 缺席或不带该契约时整层不注册。
     */
    private static void registerBulkCutoff() {
        if (!classPresent(BULK_MARKABLE_CELL) || !classPresent(BULK_DISPLAY_CELL)) {
            return;
        }
        if (bulkCutoffApiPresent() && classPresent(BULK_API_ADAPTER)) {
            try {
                var adapter = (CellBulkCutoffCompat.Adapter) Class.forName(BULK_API_ADAPTER)
                        .getDeclaredConstructor()
                        .newInstance();
                CellBulkCutoffCompat.register(adapter);
                LOGGER.info("[AE2-Pattern-Disk] ECO bulk cutoff wired through the public chain API");
                return;
            } catch (ReflectiveOperationException | LinkageError broken) {
                LOGGER.warn("[AE2-Pattern-Disk] the ECO bulk cutoff API adapter could not be loaded;"
                        + " falling back to reflection", broken);
            }
        }
        CellBulkCutoffCompat.register(new EcoBulkCellsCompat());
        LOGGER.info("[AE2-Pattern-Disk] ECO bulk cutoff wired through reflection");
    }

    /** 上游是否已经有压缩链与生效截断两个查询；缺任一个都连 API 适配器都不加载。 */
    private static boolean bulkCutoffApiPresent() {
        try {
            Class<?> display = Class.forName(BULK_DISPLAY_CELL, false, NeoECOIntegration.class.getClassLoader());
            display.getMethod(BULK_CHAIN_METHOD, AEItemKey.class);
            display.getMethod(BULK_CUTOFF_METHOD, AEItemKey.class);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
            return false;
        }
    }

    /** 类在不在；keep initialize=false，不触发对方静态初始化。 */
    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, NeoECOIntegration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Runs one wiring step, keeping a failure inside it from reaching the mod loader.
     *
     * <p>捕 {@link LinkageError} 不只是防御：邻居模组是可选的，它们的类在旧版里可能不存在或签名不同，
     * 那种失败是 {@code Error}（NoClassDefFoundError / NoSuchMethodError / AbstractMethodError），只捕
     * RuntimeException 会把它放出去——而这一段跑在 ECO 的 mod 构造器里，放出去就是启动失败。</p>
     *
     * <p><b>它只包住 lambda 体，不包参数求值</b>：写 {@code guarded("x", SomeNeighbourClass::wire)} 时，
     * 方法引用本身先于本方法求值（加载那个类）。所以面向可选邻居的引用必须先在外面用字符串探过类，
     * 再放进 lambda 里取。</p>
     */
    private static void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.warn("[AE2-Pattern-Disk] {} could not be wired; that feature stays off", what, broken);
        }
    }

    /**
     * Hands NEO ECO the batch assembler's parallel intake.
     *
     * <p>Keyed by class rather than by instance: providers are looked up while a crafting job runs, so the
     * registration has to answer for machines that are created later.</p>
     */
    private static void registerParallelIntake() {
        ECOParallelCraftingProviders.register(BatchAssemblerBlockEntity.class,
                provider -> new BatchAssemblerParallelIntake((BatchAssemblerBlockEntity) provider));
        LOGGER.info("[AE2-Pattern-Disk] Batch assembler registered for NEO ECO parallel intake");
    }

    private static void wireEncodingTerminalUpload() {
        PatternDiskEncodingTermMenu.uploadHandler = NeoECOUploadHandler.create();
        LOGGER.info("[AE2-Pattern-Disk] Encoding terminal upload button wired");
    }

    /** Client-side initialisation: the terminal screen adds its upload button on its own. */
    public void applyClient() {
    }
}

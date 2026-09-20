package io.github.lounode.ae2pattern.integration.extendedae_plus;

import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.ModList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.part.PatternDiskEncodingTerminalPart;

/**
 * Integration shim for ExtendedAE Plus ({@code extendedae_plus}).
 *
 * <p>That mod's "upload pattern to a provider" button looks for an empty row in a provider's pattern
 * inventory and then writes into it. A disk-backed provider has no rows of its own to write into, so
 * {@code PatternDiskRemoveInventory} answers the write and lands it on a disk; this class holds what belongs
 * to the other mod rather than to that view: its mod id, the presence probe, the terminal-contract probe (the
 * two interfaces our adapter subclasses implement only exist in builds of that mod that carry them), and the
 * rule that decides whether a free row is worth advertising.</p>
 *
 * <p>Its exit condition is ExtendedAE Plus delegating disk-backed providers to a write API of their own
 * instead of the generic terminal inventory: once that lands, this package goes away.</p>
 */
public final class ExtendedAEPlusCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.extendedae_plus");

    /** Mod id of the uploader. Product code never references its classes, only this string. */
    public static final String MOD_ID = "extendedae_plus";

    /** Marker for the builds of that mod that carry its third-party terminal upload contract. It exists on its
     * 1.21.1 branch so far (issue #154), not in the release on Modrinth, hence a class probe rather than a
     * version comparison. The upload side has two interfaces and our two adapters implement one each, so both
     * are probed: a build carrying only one of them would leave the other adapter unlinkable. */
    private static final String UPLOAD_MENU_CONTRACT_CLASS = "com.extendedae_plus.api.upload.IPatternUploadMenu";

    private static final String UPLOAD_TERMINAL_CONTRACT_CLASS = "com.extendedae_plus.api.upload.IPatternUploadTerminal";

    /**
     * Name of our adapter subclass, kept as a string on purpose - see {@link #createUploadMenu}.
     */
    private static final String UPLOAD_MENU_CLASS = "io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusUploadMenu";

    /** 解析结果缓存。volatile：RegisterEvent 阶段是多线程派发，两次并发探测会算出同一个值，但共享字段仍要走
     *  正常的可见性语义，不能靠「反正值一样」省掉。 */
    private static volatile Boolean present;

    private static volatile Boolean uploadContract;

    private ExtendedAEPlusCompat() {
    }

    /** @return whether ExtendedAE Plus is installed. */
    public static boolean isPresent() {
        if (present == null) {
            try {
                // Only a real answer is cached: the class can initialize before the mod list exists, and
                // caching that "absent" would silently disable uploads for the rest of the session.
                present = ModList.get().isLoaded(MOD_ID);
            } catch (Throwable ignored) {
                return false;
            }
        }
        return present;
    }

    /**
     * Whether the installed ExtendedAE Plus carries the terminal upload contract. Our two adapter subclasses
     * implement its interfaces, so instantiating them against a build without those classes would end in
     * {@code NoClassDefFoundError} - probe by class, not by version, because version strings drift and the
     * release carrying the contract is not out yet.
     */
    public static boolean hasUploadContract() {
        if (uploadContract == null) {
            if (!isPresent()) {
                return false;
            }
            try {
                var loader = ExtendedAEPlusCompat.class.getClassLoader();
                Class.forName(UPLOAD_MENU_CONTRACT_CLASS, false, loader);
                Class.forName(UPLOAD_TERMINAL_CONTRACT_CLASS, false, loader);
                uploadContract = true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return uploadContract;
    }

    /**
     * Creates the upload adapter menu, or the base menu when the installed ExtendedAE Plus has no contract.
     *
     * <p><b>This indirection is load-bearing, not stylistic.</b> The adapter implements an interface that only
     * exists in builds of that mod carrying the contract, and a class is linked the moment it is loaded - an
     * {@code if} guard stops <i>execution</i>, never <i>loading</i>: loading a class resolves the interfaces it
     * implements, so naming the adapter from a class that is always loaded (the menu class is initialized while
     * the menu registry resolves its supplier, during {@code RegisterEvent}) ends in
     * {@code NoClassDefFoundError: com/extendedae_plus/api/upload/IPatternUploadMenu} whenever that interface
     * is absent. That is exactly how 0.4.0 crashed on startup without ExtendedAE Plus. The adapter is therefore
     * reached through a class name string and reflection only, so no always-loaded class carries a reference to
     * it. Same rule for {@link #uploadMenuClasses()} and for the client-side screen adapter.</p>
     */
    public static PatternDiskEncodingTermMenu createUploadMenu(int containerId, Inventory playerInventory,
            PatternDiskEncodingTerminalPart host) {
        try {
            return (PatternDiskEncodingTermMenu) Class
                    .forName(UPLOAD_MENU_CLASS, true, ExtendedAEPlusCompat.class.getClassLoader())
                    .getConstructor(int.class, Inventory.class, PatternDiskEncodingTerminalPart.class)
                    .newInstance(containerId, playerInventory, host);
        } catch (ReflectiveOperationException e) {
            // 不接 LinkageError/ExceptionInInitializerError：走到这里说明构建与运行时错配（契约在，适配类却载不起来），
            // 这种情况应当响亮失败，不要把加载期错误揉成运行期异常。
            LOGGER.error("ExtendedAE Plus upload menu adapter failed to load", e);
            throw new IllegalStateException("ExtendedAE Plus upload menu adapter failed to load", e);
        }
    }

    /**
     * The concrete menu classes this terminal can actually be instantiated as: the base class, plus the adapter
     * when the contract is there. Callers look up integrations by runtime class, so a missing entry means a
     * silently absent feature.
     *
     * <p>Reflection for the adapter, same reason as {@link #createUploadMenu}.</p>
     */
    public static java.util.List<Class<? extends PatternDiskEncodingTermMenu>> uploadMenuClasses() {
        var classes = new java.util.ArrayList<Class<? extends PatternDiskEncodingTermMenu>>(2);
        classes.add(PatternDiskEncodingTermMenu.class);
        if (hasUploadContract()) {
            try {
                classes.add(Class.forName(UPLOAD_MENU_CLASS, false, ExtendedAEPlusCompat.class.getClassLoader())
                        .asSubclass(PatternDiskEncodingTermMenu.class));
            } catch (ClassNotFoundException e) {
                // hasUploadContract() 已确认契约在场，却取不到适配类：构建与运行时不同步。只列基类可以，但要说一声——
                // JEI 按运行时类精确查表，少登记一个类就是「编写样板」按钮静默消失。
                LOGGER.warn("Upload menu adapter class is missing although the contract probe was positive; "
                        + "JEI will not offer pattern writing for the adapter menu.", e);
            }
        }
        return java.util.List.copyOf(classes);
    }

    /**
     * Whether a provider view should advertise a free row to that mod's row scan.
     *
     * <p>只看对方在不在场，不看终端上传契约：这条路径对对应的是 EAE+ 发布版就具备的供应器侧上传能力，
     * 与适配子类实现的那个终端契约无关。两者口径不同是设计，不是就该“统一”。</p>
     *
     * <p>A view that cannot resolve a level can never accept an upload - decoding a pattern needs one -
     * and a view whose disks are all full has nothing to offer the caller.</p>
     *
     * @param hasLevelSupplier whether the view can resolve a level at all
     * @param freeCapacity     total free pattern slots across the view's disks
     */
    public static boolean wantsFreeRow(boolean hasLevelSupplier, int freeCapacity) {
        return hasLevelSupplier && isPresent() && freeCapacity > 0;
    }
}

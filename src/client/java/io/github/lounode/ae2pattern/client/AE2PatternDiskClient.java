package io.github.lounode.ae2pattern.client;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.util.FastColor;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.api.util.AEColor;
import appeng.client.gui.style.StyleManager;
import appeng.client.render.StaticItemColor;
import io.github.lounode.ae2pattern.client.render.PatternDiskAssemblerRenderer;
import io.github.lounode.ae2pattern.client.render.DriveHighlight;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.client.gui.BatchAssemblerScreen;
import io.github.lounode.ae2pattern.client.gui.CellManagementTermScreen;
import io.github.lounode.ae2pattern.common.menu.CellManagementTermMenu;
import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.client.gui.PatternDiskManagementTermScreen;
import io.github.lounode.ae2pattern.client.gui.PatternDiskAssemblerScreen;
import io.github.lounode.ae2pattern.client.gui.PatternDiskProviderScreen;
import io.github.lounode.ae2pattern.client.gui.PatternTransfererScreen;
import io.github.lounode.ae2pattern.client.integration.ae2wtlib.PatternDiskWirelessEncodingTermScreen;
import io.github.lounode.ae2pattern.client.integration.ae2wtlib.PatternDiskWirelessManagementTermScreen;
import io.github.lounode.ae2pattern.client.integration.ae2wtlib.CellManagementWirelessTermScreen;
import io.github.lounode.ae2pattern.client.integration.ipn.InventoryProfilesIntegration;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessEncodingTermMenu;
import io.github.lounode.ae2pattern.integration.ae2wtlib.PatternDiskWirelessManagementTermMenu;
import io.github.lounode.ae2pattern.integration.ae2wtlib.CellManagementWirelessTermMenu;
import io.github.lounode.ae2pattern.AEPatternRegistries;

/**
 * Client-side entry point for AE2 Pattern Disk.
 */
@Mod(value = AE2PatternDisk.MOD_ID, dist = Dist.CLIENT)
public class AE2PatternDiskClient {

    public AE2PatternDiskClient(IEventBus modBus) {
        // 整理模组（IPN）的登记口：它按菜单类登记容器类型，登记得越早越好，趁它还没消费这张表。
        // IPN 不在时这行什么都不做（类名靠反射找，够不到就安静退场）。
        InventoryProfilesIntegration.registerTerminalMenus();
        modBus.addListener(this::registerScreens);
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::registerBlockRenderers);
        modBus.addListener(this::registerAdditionalModels);
        modBus.addListener(this::registerItemColors);
        // 元件管理终端选中驱动器时在世界里圈出那个方块（线框状态见 DriveHighlight）。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(DriveHighlight::onRenderLevel);
        // 退出世界时把「钉住」的高亮一并清掉：钉子只按维度 + 坐标记，换个存档就会指到别人身上。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> DriveHighlight.clearPinned());
    }

    /**
     * The encoding terminal is a PartItem whose model relies on tint indices (same as AE2's own part
     * items); without a color handler the front layers render blank/white.
     *
     * <p>{@link AEColor} variants carry no alpha channel, and AE2 wraps every item color handler in
     * {@code FastColor.ARGB32.opaque} for exactly that reason. Without that wrapper the four tinted
     * layers of {@code ae2:item/display_base} are fully transparent and the icon loses its screen,
     * leaving only the frame drawn by the untinted {@code front_base} layer.</p>
     */
    private void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(
                (stack, tintIndex) -> FastColor.ARGB32.opaque(TERMINAL_COLOR.getColor(stack, tintIndex)),
                AEPatternRegistries.ITEM_PATTERN_DISK_ENCODING_TERMINAL.get(),
                AEPatternRegistries.ITEM_PATTERN_DISK_MANAGEMENT_TERMINAL.get(),
                AEPatternRegistries.ITEM_CELL_MANAGEMENT_TERMINAL.get());
    }

    /** Fluix-coloured tint source, matching AE2's own terminals. */
    private static final StaticItemColor TERMINAL_COLOR = new StaticItemColor(AEColor.TRANSPARENT);

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(InitPatternDiskProperties::init);
        // 多态合成（可选前置）：把本模组的编码终端登记给 Polymorph，未装时此调用直接返回。
        event.enqueueWork(io.github.lounode.ae2pattern.client.integration.polymorph.PolymorphClientCompat::register);
        // 整理模组（可选前置）：复查六个终端屏上的 @IPNPlayerSideOnly 有没有被认到；未装时这行只留一条 debug。
        event.enqueueWork(() -> InventoryProfilesIntegration.reportAnnotatedScreens(
                PatternDiskEncodingTermScreen.class,
                PatternDiskManagementTermScreen.class,
                CellManagementTermScreen.class,
                PatternDiskWirelessEncodingTermScreen.class,
                PatternDiskWirelessManagementTermScreen.class,
                CellManagementWirelessTermScreen.class));
    }

    private void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(PatternDiskAssemblerRenderer.LIGHTS_MODEL);
    }

    private void registerBlockRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(AEPatternRegistries.BE_ASSEMBLER.get(),
                PatternDiskAssemblerRenderer::new);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(AEPatternRegistries.MENU_TRANSFERER.get(), PatternTransfererScreen::new);
        // 屏幕类多了一个接受布局文档的构造，方法引用不再能唯一定位，所以这里把两个泛型参数写实。
        event.register(AEPatternRegistries.MENU_PROVIDER.get(),
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<
                        io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu, PatternDiskProviderScreen>() {
                    @Override
                    public PatternDiskProviderScreen create(
                            io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        return new PatternDiskProviderScreen(menu, playerInventory, title);
                    }
                });
        event.register(AEPatternRegistries.MENU_ASSEMBLER.get(), PatternDiskAssemblerScreen::new);
        event.register(AEPatternRegistries.MENU_BATCH_ASSEMBLER.get(), BatchAssemblerScreen::new);
        registerEncodingTerminalScreen(event);
        registerManagementTerminalScreen(event);
        registerCellManagementTerminalScreen(event);
        registerWirelessTerminalScreens(event);
        registerMeteoriteProviderScreen(event);
    }

    /**
     * 自装配样板磁盘供应器的屏幕：与样板磁盘供应器同一个屏幕类、同一份槽位布局，只换布局文档（设备字样写在
     * 文档里）。未装 AE2 Crystal Science 时它不存在，这里安静跳过。
     */
    private void registerMeteoriteProviderScreen(RegisterMenuScreensEvent event) {
        if (!io.github.lounode.ae2pattern.MeteoritePatternProviderRegistrations.isRegistered()) {
            return;
        }
        event.register(io.github.lounode.ae2pattern.MeteoritePatternProviderRegistrations.MENU.get(),
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<
                        io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu, PatternDiskProviderScreen>() {
                    @Override
                    public PatternDiskProviderScreen create(
                            io.github.lounode.ae2pattern.common.menu.PatternDiskProviderMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        return new PatternDiskProviderScreen(menu, playerInventory, title,
                                StyleManager.loadStyleDoc(
                                        "/screens/ae2_pattern_disk/meteorite_pattern_provider.json"),
                                true);
                    }
                });
    }

    /**
     * 两个无线版终端的屏幕：布局文档直接用面板版那份（GUI 复用），差的只是多挂上 AE2WTLib 的升级面板与
     * 终端切换按钮（见两个无线屏幕类）。
     */
    private void registerWirelessTerminalScreens(RegisterMenuScreensEvent event) {
        // 与面板版同一套写法：屏幕类型的泛型参数写在父屏的菜单类型上（AEBaseScreen 的签名把菜单类型写死），
        // 注册时用真实菜单类型，工厂里再收窄——ScreenConstructor 要求屏幕的 MenuAccess 与菜单类型一致，
        // lambda 推不出这一层，所以用匿名类显式写。
        event.register(AEPatternRegistries.MENU_WIRELESS_PATTERN_DISK_ENCODING_TERMINAL.get(),
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<PatternDiskEncodingTermMenu, PatternDiskEncodingTermScreen>() {
                    @Override
                    public PatternDiskEncodingTermScreen create(PatternDiskEncodingTermMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        var style = StyleManager.loadStyleDoc(
                                "/screens/ae2_pattern_disk/wireless_pattern_disk_encoding_terminal.json");
                        var wirelessMenu = (PatternDiskWirelessEncodingTermMenu) menu;
                        // 与面板版同一处理：装了带终端上传契约的 EAE+ 时换用「无线 + 上传」的子类，
                        // 好让 EAE+ 把「上传到供应器」按钮注入进来；否则用普通无线屏幕。
                        // 子类同样只能反射创建（理由见 ClientExtendedAEPlusCompat 的类注释）。
                        if (io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat
                                .hasUploadContract()) {
                            return io.github.lounode.ae2pattern.client.integration.extendedae_plus.ClientExtendedAEPlusCompat
                                    .createWirelessUploadScreen(wirelessMenu, playerInventory, title, style);
                        }
                        return new PatternDiskWirelessEncodingTermScreen(wirelessMenu, playerInventory, title, style);
                    }
                });
        event.register(AEPatternRegistries.MENU_WIRELESS_PATTERN_DISK_MANAGEMENT_TERMINAL.get(),
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<PatternDiskEncodingTermMenu, PatternDiskEncodingTermScreen>() {
                    @Override
                    public PatternDiskEncodingTermScreen create(PatternDiskEncodingTermMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        return new PatternDiskWirelessManagementTermScreen(
                                (PatternDiskWirelessManagementTermMenu) menu, playerInventory, title,
                                StyleManager.loadStyleDoc(
                                        "/screens/ae2_pattern_disk/wireless_pattern_disk_management_terminal.json"));
                    }
                });
    }

    private void registerManagementTerminalScreen(RegisterMenuScreensEvent event) {
        var type = AEPatternRegistries.MENU_PATTERN_DISK_MANAGEMENT_TERMINAL.get();
        // 用父菜单类型登记：新屏幕的菜单类型写死在继承签名里（AEBaseScreen<PatternDiskEncodingTermMenu>），而
        // ScreenConstructor 的屏幕类型参数要求与菜单类型配套。注册的菜单类型只有一个，转换必定成立。
        event.register(type,
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<PatternDiskEncodingTermMenu, PatternDiskEncodingTermScreen>() {
                    @Override
                    public PatternDiskEncodingTermScreen create(PatternDiskEncodingTermMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        appeng.client.gui.style.ScreenStyle style = appeng.client.gui.style.StyleManager
                                .loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_management_terminal.json");
                        return new io.github.lounode.ae2pattern.client.gui.PatternDiskManagementTermScreen(
                                (io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu) menu,
                                playerInventory, title, style);
                    }
                });
    }

    /**
     * 元件管理终端的屏幕：屏幕类直接继承 {@code MEStorageScreen}，菜单类型与屏幕类型一一对应，
     * 不需要样板终端那种「用父菜单类型登记再强转」的写法。
     */
    private void registerCellManagementTerminalScreen(RegisterMenuScreensEvent event) {
        var type = AEPatternRegistries.MENU_CELL_MANAGEMENT_TERMINAL.get();
        event.register(type,
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<CellManagementTermMenu, CellManagementTermScreen>() {
                    @Override
                    public CellManagementTermScreen create(CellManagementTermMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        appeng.client.gui.style.ScreenStyle style = appeng.client.gui.style.StyleManager
                                .loadStyleDoc("/screens/ae2_pattern_disk/cell_management_terminal.json");
                        return new CellManagementTermScreen(menu, playerInventory, title, style);
                    }
                });
        // 无线版（AE2WTLib）：菜单类型不同，屏幕继承面板版那一张。
        event.register(AEPatternRegistries.MENU_WIRELESS_CELL_MANAGEMENT_TERMINAL.get(),
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<CellManagementTermMenu, CellManagementTermScreen>() {
                    @Override
                    public CellManagementTermScreen create(CellManagementTermMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        appeng.client.gui.style.ScreenStyle style = appeng.client.gui.style.StyleManager
                                .loadStyleDoc("/screens/ae2_pattern_disk/wireless_cell_management_terminal.json");
                        return new CellManagementWirelessTermScreen((CellManagementWirelessTermMenu) menu,
                                playerInventory, title, style);
                    }
                });
    }

    private void registerEncodingTerminalScreen(RegisterMenuScreensEvent event) {
        var type = AEPatternRegistries.MENU_PATTERN_DISK_ENCODING_TERMINAL.get();
        event.register(type,
                new net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<PatternDiskEncodingTermMenu, PatternDiskEncodingTermScreen>() {
                    @Override
                    public PatternDiskEncodingTermScreen create(PatternDiskEncodingTermMenu menu,
                            net.minecraft.world.entity.player.Inventory playerInventory,
                            net.minecraft.network.chat.Component title) {
                        appeng.client.gui.style.ScreenStyle style = appeng.client.gui.style.StyleManager
                                .loadStyleDoc("/screens/ae2_pattern_disk/pattern_disk_encoding_terminal.json");
                        // 装了带终端上传契约的 EAE+ 时用它认得的子类（实现它的上传终端接口，好让上传按钮
                        // 注入进来），否则基类。子类必须经 ClientExtendedAEPlusCompat 反射创建：类一被加载
                        // 就会解析它 implements 的接口，守卫只挡执行不挡加载（0.4.0 的启动崩溃即此）。
                        if (io.github.lounode.ae2pattern.integration.extendedae_plus.ExtendedAEPlusCompat
                                .hasUploadContract()) {
                            return io.github.lounode.ae2pattern.client.integration.extendedae_plus.ClientExtendedAEPlusCompat
                                    .createUploadScreen(menu, playerInventory, title, style);
                        }
                        return new PatternDiskEncodingTermScreen(menu, playerInventory, title, style);
                    }
                });
    }
}

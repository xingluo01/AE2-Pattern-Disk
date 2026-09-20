package io.github.lounode.ae2pattern.client.integration;

import net.minecraft.resources.ResourceLocation;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferListener;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;

import io.github.lounode.ae2pattern.AE2PatternDisk;
import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.client.gui.PatternDiskManagementTermScreen;
import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;

/**
 * JEI entry point: wires the pattern disk encoding terminal into JEI's recipe transfer ("+") button.
 *
 * <p>Only this class, {@link JeiDiskEncodeRecipeHandler}, {@link JeiMarkNames} and
 * {@link JeiTransferCategory} touch JEI types, and JEI discovers the plugin through {@link JeiPlugin},
 * so the mod keeps working unchanged when JEI is not installed - the same split the EMI integration
 * uses.</p>
 */
@JeiPlugin
public class PatternDiskJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(AE2PatternDisk.MOD_ID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        var helper = registration.getTransferHelper();
        // 每个具体菜单类各登记一条：JEI 的登记表是 ImmutableTable<容器类, 配方类型, 处理器>，键取自
        // container.getClass()，精确匹配、不认父类。本终端在装了带上传契约的 EAE+ 时会实例化适配子类，
        // 只登记基类的话，那种环境下配方页的转移（「编写样板」）按钮会静默消失。
        for (var containerClass : PatternDiskEncodingTermMenu.concreteMenuClasses()) {
            registration.addUniversalRecipeTransferHandler(new JeiDiskEncodeRecipeHandler(helper, containerClass));
        }
        // 管理终端的菜单有自己的类型（管理菜单继承编码菜单，但 JEI 的登记表按 class 精确匹配），
        // 所以单独再登一条。
        registration.addUniversalRecipeTransferHandler(new JeiDiskEncodeRecipeHandler(helper, PatternDiskManagementTermMenu.class));
        registerCategoryCapture(registration);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // 拖拽入口在幽灵物品处理器里（见 JeiEncodingGhostHandler）：每个活动的 FakeSlot 各是一个目标。
        // 管理终端是编码终端的子类，处理器表按屏幕 class 查、父类登记也能命中，但这里的实例只依赖
        // getMenu()/getGuiLeft()，多登一条零成本，还能避免“最近的一层”被别家（比如 AE2 的 JEI 集成给
        // AEBaseScreen 登的那条）抢在前头。
        IGhostIngredientHandler<PatternDiskEncodingTermScreen> handler = new JeiEncodingGhostHandler();
        registration.addGhostIngredientHandler(PatternDiskEncodingTermScreen.class, handler);
        registration.addGhostIngredientHandler(PatternDiskManagementTermScreen.class, managementScreenHandler(handler));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static IGhostIngredientHandler<PatternDiskManagementTermScreen> managementScreenHandler(
            IGhostIngredientHandler<PatternDiskEncodingTermScreen> handler) {
        return (IGhostIngredientHandler) handler;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // 标记的显示名与机器名要按类别 id 反查，运行时对象是唯一入口（见 JeiMarkNames）。
        JeiMarkNames.setRuntime(jeiRuntime);
        // 换了一个运行时对象就说明配方树重建了（重载资源也会走到这里），机器→类别索引必须跟着重建。
        MachineRecipeTypes.invalidate();
    }

    @Override
    public void onRuntimeUnavailable() {
        // JEI 丢掉旧运行时了：别让标记那边还拿着一个已失效的对象去反查（下一次 onRuntimeAvailable
        // 会立刻填上新的）。
        JeiMarkNames.setRuntime(null);
    }

    /**
     * 通用转移处理器拿不到配方类别（JEI 用适配器包住它时会丢掉上下文里的 RecipeType），所以另挂一个
     * 监听器：传输开始前把类别记到 {@link JeiTransferCategory}，结束后清掉，处理器在编码完读它。
     *
     * <p>监听器 API 自 JEI 19.52 提供；本模组按 mods.toml 把 JEI 声明为可选依赖、下限取编译基线
     * 19.56（与 EAE+ 的同名声明一致），所以更旧的 JEI 在加载阶段就会被拒，走不到这里。</p>
     */
    private static void registerCategoryCapture(IRecipeTransferRegistration registration) {
        try {
            registration.addRecipeTransferListener(new IRecipeTransferListener() {
                @Override
                public void beforeRecipeTransfer(IRecipeTransferContext<?, ?> context) {
                    // 别的容器的传输没人会读它，留着会串到下一条标记，所以只认本终端的容器。
                    JeiTransferCategory.set(context.getContainer() instanceof PatternDiskEncodingTermMenu
                            ? context.getRecipeType().getUid()
                            : null);
                }

                @Override
                public void afterRecipeTransfer(IRecipeTransferContext<?, ?> context, RecipeTransferResult result) {
                    JeiTransferCategory.clear();
                }
            });
        } catch (Throwable ignored) {
            // 旧版 JEI 没有监听器 API：保留编码，不保留类别。
        }
    }
}

package io.github.lounode.ae2pattern.client.integration;

import net.minecraft.world.inventory.MenuType;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;

/**
 * EMI 配方导入插件：注册样板磁盘编码终端的配方编码 handler。
 * 与原版 AE2 编码终端一致，EMI 会自动扫描 {@code @EmiEntrypoint} 注解并调用。
 */
@EmiEntrypoint
public class PatternDiskEmiPlugin implements EmiPlugin {

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void register(EmiRegistry registry) {
        registry.addRecipeHandler(PatternDiskEncodingTermMenu.TYPE,
                new DiskEncodePatternHandler());
        // 管理菜单有自己的 TYPE；它继承编码菜单，转移逻辑同一套，只是登记键要单独一条。
        registry.addRecipeHandler((MenuType) PatternDiskManagementTermMenu.TYPE,
                (StandardRecipeHandler) new DiskEncodePatternHandler(PatternDiskManagementTermMenu.class));
    }
}
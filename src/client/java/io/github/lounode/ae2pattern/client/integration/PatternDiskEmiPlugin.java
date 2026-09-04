package io.github.lounode.ae2pattern.client.integration;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;

import io.github.lounode.ae2pattern.common.menu.PatternDiskEncodingTermMenu;

/**
 * EMI 配方导入插件：注册样板磁盘编码终端的配方编码 handler。
 * 与原版 AE2 编码终端一致，EMI 会自动扫描 {@code @EmiEntrypoint} 注解并调用。
 */
@EmiEntrypoint
public class PatternDiskEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipeHandler(PatternDiskEncodingTermMenu.TYPE,
                new DiskEncodePatternHandler());
    }
}
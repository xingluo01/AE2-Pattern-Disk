package io.github.lounode.ae2pattern.client.gui;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.style.Blitter;

import io.github.lounode.ae2pattern.common.menu.pattern.DiskEncodingLogic.SearchScope;

/**
 * 样板内容搜索栏的「搜索范围」轮换按钮：仅输出 → 仅输入 → 输入+输出，点一次换一档。
 *
 * <p>图标取自 {@code states.png} 的 (32,8,24,8)——三段 8&times;8 并排，从左到右依次是
 * 「仅搜索输出」「仅搜索输入」「搜索输入和输出」。状态本体存在终端的编码逻辑里
 * （面板版落部件 NBT、无线版落物品组件），所以关屏重开、换机器都还在。</p>
 */
public final class ContentSearchScopeButton {

    private static final Blitter ICON_OUTPUT = icon(32);
    private static final Blitter ICON_INPUT = icon(40);
    private static final Blitter ICON_BOTH = icon(48);
    // 图标本身就是按钮的全部：8×8 三段并排，不铺按钮背景（states.png (208,224,36,20) 那套留给其它按钮）。

    private final StatesIconButton button;
    private SearchScope scope;
    /** 上次写进按钮的提示语档位；没变就不重建那几行文本。 */
    private SearchScope lastTooltipScope;

    /**
     * @param onChanged 换档后要跑的动作（发回服务端并重筛），只在切换时调一次。
     */
    public ContentSearchScopeButton(SearchScope initial, Consumer<SearchScope> onChanged) {
        this.scope = initial;
        this.button = new StatesIconButton(() -> blitterFor(this.scope), btn -> {
            this.scope = next(this.scope);
            updateTooltip();
            onChanged.accept(this.scope);
        });
        this.button.setPressAnimation(false);
        this.button.setSize(8, 8);
        updateTooltip();
    }

    /** 交给工具栏摆放。 */
    public StatesIconButton widget() {
        return this.button;
    }

    public SearchScope scope() {
        return this.scope;
    }

    /** 服务端回推后同步用：不触发回调，避免又发一轮包。 */
    public void setScope(SearchScope scope) {
        if (scope == null || scope == this.scope) {
            return;
        }
        this.scope = scope;
        updateTooltip();
    }

    private void updateTooltip() {
        if (this.lastTooltipScope == this.scope) {
            return;
        }
        this.lastTooltipScope = this.scope;
        this.button.setTooltip(List.of(Component.translatable(switch (this.scope) {
            case OUTPUT -> "gui.ae2_pattern_disk.search.scope.output";
            case INPUT -> "gui.ae2_pattern_disk.search.scope.input";
            case BOTH -> "gui.ae2_pattern_disk.search.scope.both";
        })));
    }

    private static SearchScope next(SearchScope scope) {
        return switch (scope) {
            case OUTPUT -> SearchScope.INPUT;
            case INPUT -> SearchScope.BOTH;
            case BOTH -> SearchScope.OUTPUT;
        };
    }

    private static Blitter blitterFor(SearchScope scope) {
        return switch (scope) {
            case OUTPUT -> ICON_OUTPUT;
            case INPUT -> ICON_INPUT;
            case BOTH -> ICON_BOTH;
        };
    }

    private static Blitter icon(int x) {
        return Blitter.texture(ResourceLocation.parse("ae2_pattern_disk:textures/guis/states.png"))
                .src(x, 8, 8, 8);
    }

    @Override
    public String toString() {
        return "ContentSearchScopeButton[scope=" + this.scope + "]";
    }
}

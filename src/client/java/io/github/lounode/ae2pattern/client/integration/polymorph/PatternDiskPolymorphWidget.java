package io.github.lounode.ae2pattern.client.integration.polymorph;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import appeng.menu.SlotSemantics;
import appeng.parts.encoding.EncodingMode;

import com.illusivesoulworks.polymorph.api.client.widgets.PlayerRecipesWidget;

import io.github.lounode.ae2pattern.client.gui.PatternDiskEncodingTermScreen;
import io.github.lounode.ae2pattern.common.menu.pattern.PatternDiskEncodingTermMenu;

/**
 * 合成层上的多态选择按钮：一组材料能出多个结果时，产物槽上方出现按钮，点开选一个。
 *
 * <p>锚点是产物槽（{@code SlotSemantics.CRAFTING_RESULT}），位置由 Polymorph 自己从槽坐标推算，
 * 所以四个形态（方块 / 面板 / 无线编码 / 无线管理）共用这一个实现。</p>
 *
 * <p><b>只在合成层显示</b>：加工、锻造、切石三种模式的「产物」不是同一组材料的多个候选，
 * 多态在这里没有意义。判定用渲染与点击两道闸门——只挡渲染的话，看不见的按钮仍会吃掉鼠标点击。</p>
 */
public class PatternDiskPolymorphWidget extends PlayerRecipesWidget {

    private final PatternDiskEncodingTermMenu menu;

    public PatternDiskPolymorphWidget(PatternDiskEncodingTermScreen screen) {
        // 产物槽：与界面 JSON 里的 slots.CRAFTING_RESULT 对应。
        super(screen, screen.getMenu().getSlots(SlotSemantics.CRAFTING_RESULT).getFirst());
        this.menu = screen.getMenu();
    }

    @Override
    public void selectRecipe(ResourceLocation id) {
        // 交给 Polymorph 记下选择，再让服务端重算产物——服务端那边有产物缓存，不通知就还是旧结果。
        super.selectRecipe(id);
        this.menu.notifyRecipeSelected();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        if (isCraftingMode()) {
            super.render(guiGraphics, mouseX, mouseY, partialTicks);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return isCraftingMode() && super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isCraftingMode() {
        return this.menu.getEncodingMode() == EncodingMode.CRAFTING;
    }
}

package io.github.lounode.ae2pattern.common.menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;

/**
 * 四套样板编码：把编码区当前的内容编成一枚样板（合成 / 加工 / 锻造 / 切石）。
 *
 * <p>从 {@code PatternDiskEncodingTermMenu} 抽出来——那里原本同时管着菜单槽位、同步、磁盘写入与四种编码，
 * 1700 多行里这四套编逻辑是唯一可以整块摘走的部分。搬出来之后它只依赖
 * {@link IPatternEncodingHost} 暴露的七样状态，不再碰菜单的任何私有字段。</p>
 *
 * <p>没有搬走的那部分是有意留下的：{@code encode()} 作为入口负责「先编码、再决定写哪张盘、扣网络空白样板、
 * 清空槽位」这一串副作用，与菜单的写盘链路绑得太紧；{@code getAndUpdateOutput()} 会顺带改写缓存，
 * 属菜单状态管理。两者都经宿主接口回调。</p>
 */
public final class PatternEncodingLogic {

    /** 编码区的合成网格是 3&times;3。 */
    private static final int CRAFTING_GRID_SLOTS = 9;

    private final IPatternEncodingHost host;

    public PatternEncodingLogic(IPatternEncodingHost host) {
        this.host = host;
    }

    /** 按当前模式编一枚样板；内容不足以编码时返回 null。 */
    @Nullable
    public ItemStack encodePattern() {
        // 高级编码模式是并列的一档，不在 AE2 的 EncodingMode 里，所以先判它。
        // 不先判的话它会落到默认的合成路径上：高级模式下菜单的 mode 仍停在 CRAFTING。
        if (this.host.isAdvancedMode()) {
            return encodeProcessingPattern();
        }
        return switch (this.host.getEncodingMode()) {
            case CRAFTING -> encodeCraftingPattern();
            case PROCESSING -> encodeProcessingPattern();
            case SMITHING_TABLE -> encodeSmithingTablePattern();
            case STONECUTTING -> encodeStonecuttingPattern();
        };
    }

    @Nullable
    private ItemStack encodeCraftingPattern() {
        var ingredients = new ItemStack[CRAFTING_GRID_SLOTS];
        boolean valid = false;
        for (int x = 0; x < ingredients.length; x++) {
            ingredients[x] = this.host.getCraftingIngredient(x);
            if (ingredients[x] == null) return null;
            else if (!ingredients[x].isEmpty()) valid = true;
        }
        if (!valid) return null;
        var result = this.host.updateAndGetCraftingOutput();
        var recipe = this.host.getCurrentCraftingRecipe();
        if (result.isEmpty() || recipe == null) return null;
        return PatternDetailsHelper.encodeCraftingPattern(recipe, ingredients, result,
                this.host.isEncodingSubstitute(), this.host.isEncodingSubstituteFluids());
    }

    @Nullable
    private ItemStack encodeProcessingPattern() {
        var inputs = this.host.getEncodedInputs();
        var outputsInv = this.host.getEncodedOutputs();

        var rawInputs = new GenericStack[inputs.size()];
        boolean valid = false;
        for (int slot = 0; slot < inputs.size(); slot++) {
            rawInputs[slot] = inputs.getStack(slot);
            if (rawInputs[slot] != null) valid = true;
        }
        if (!valid) return null;

        // 同物品合并：启用时相同 AEKey 的输入合并为单槽
        List<GenericStack> mergedInputs;
        if (this.host.isEncodingMergeSameItems()) {
            var byKey = new LinkedHashMap<AEKey, Long>();
            for (var in : rawInputs) {
                if (in != null) {
                    byKey.merge(in.what(), in.amount(), Long::sum);
                }
            }
            mergedInputs = new ArrayList<>();
            for (var e : byKey.entrySet()) {
                mergedInputs.add(new GenericStack(e.getKey(), e.getValue()));
            }
        } else {
            mergedInputs = new ArrayList<>(Arrays.asList(rawInputs));
            mergedInputs.removeIf(Objects::isNull);
        }

        var outputs = new GenericStack[outputsInv.size()];
        for (int slot = 0; slot < outputsInv.size(); slot++) {
            outputs[slot] = outputsInv.getStack(slot);
        }
        if (outputs[0] == null) return null;
        // 高级编码模式：把玩家分配的面并进方向表，换成 AdvancedAE 的高级处理样板。编不出来就退回普通
        // 处理样板——「高级」是加料，不是替掉这条路径。
        //
        // 方向表按 AEKey 查而不是按槽下标：上面那一步「同物品合并」会把同 key 的多个槽折成一格，
        // 下标对不上，按下标查会把某一格的面静默贴到另一个输入上。
        if (this.host.isAdvancedMode()) {
            var advanced = AdvPatternSupport.encodeProcessing(mergedInputs, Arrays.asList(outputs),
                    this.host.advancedSidesByKey());
            if (advanced != null) {
                return advanced;
            }
        }
        return PatternDetailsHelper.encodeProcessingPattern(mergedInputs, Arrays.asList(outputs));
    }

    @Nullable
    private ItemStack encodeSmithingTablePattern() {
        var inputs = this.host.getEncodedInputs();
        if (!(inputs.getKey(0) instanceof AEItemKey template)
                || !(inputs.getKey(1) instanceof AEItemKey base)
                || !(inputs.getKey(2) instanceof AEItemKey addition)) {
            return null;
        }
        var input = new SmithingRecipeInput(template.toStack(), base.toStack(), addition.toStack());
        var level = this.host.getLevel();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMITHING, input, level).orElse(null);
        if (recipe == null) return null;
        var output = AEItemKey.of(recipe.value().assemble(input, level.registryAccess()));
        return PatternDetailsHelper.encodeSmithingTablePattern(recipe, template, base, addition, output,
                isSubstitution());
    }

    @Nullable
    private ItemStack encodeStonecuttingPattern() {
        var recipeId = this.host.getStonecuttingRecipeId();
        if (recipeId == null) return null;
        if (!(this.host.getEncodedInputs().getKey(0) instanceof AEItemKey input)) return null;
        var recipeInput = new SingleRecipeInput(input.toStack());
        var level = this.host.getLevel();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.STONECUTTING, recipeInput, level, recipeId)
                .orElse(null);
        if (recipe == null) return null;
        var output = AEItemKey.of(recipe.value().getResultItem(level.registryAccess()));
        return PatternDetailsHelper.encodeStonecuttingPattern(recipe, input, output, isSubstitution());
    }

    /** 替代开关的统一入口：锻造与切石用的是同一个「替代物」判定。 */
    private boolean isSubstitution() {
        return this.host.isEncodingSubstitute();
    }
}

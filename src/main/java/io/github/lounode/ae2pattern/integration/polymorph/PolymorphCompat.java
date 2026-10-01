package io.github.lounode.ae2pattern.integration.polymorph;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import net.neoforged.fml.ModList;

import org.jetbrains.annotations.Nullable;

/**
 * Polymorph（多态合成）的服务端接入点：把「匹配第一个配方」换成「按玩家选择取配方」。
 *
 * <p>不装 Polymorph 时这里退化成原生行为，调用方无需分支。判断放在这一个类里、Polymorph 的类型只出现在
 * {@link Bridge} 中，是为了让未装它的环境不会在类加载阶段就去解析 Polymorph 的类（{@code Bridge} 只在
 * {@link #isLoaded()} 为真时才被触达）。</p>
 *
 * <p>为什么本模组要自己接而不用现成的 AE2 附属（如 Polymorphic Energistics）：那类附属是把按钮注入到
 * <b>AE2 自己的</b> {@code PatternEncodingTermScreen} 上，而本模组的终端是独立实现的屏幕与菜单，
 * 不在它们的注入范围里。</p>
 */
public final class PolymorphCompat {

    public static final String MOD_ID = "polymorph";

    /**
     * 玩家在客户端选中配方后发给服务端的动作名。服务端收到后清掉合成产物缓存并重算——
     * 缓存里存的是上一个配方的产物，不清就会继续显示旧结果。
     */
    public static final String ACTION_SELECT_RECIPE = "selectRecipe";

    private static final boolean LOADED = ModList.get().isLoaded(MOD_ID);

    private PolymorphCompat() {}

    /** 集成是否可用：装了 Polymorph 才为真。 */
    public static boolean isLoaded() {
        return LOADED;
    }

    /**
     * 取当前 3&times;3 网格对应的合成配方。
     *
     * <p>装了 Polymorph 时由它按玩家在这组材料上选过的配方返回；没装时退回原生的「第一个匹配」。
     * 两者都可能返回 null（没有配方匹配）。</p>
     */
    @Nullable
    public static RecipeHolder<CraftingRecipe> getCraftingRecipe(AbstractContainerMenu menu,
            CraftingInput input, Level level, Player player) {
        if (isLoaded()) {
            return Bridge.getPlayerRecipe(menu, input, level, player);
        }
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
    }

    /**
     * 唯一直接引用 Polymorph 类型的地方。方法签名只用原版类型，所以它只在
     * {@link #getCraftingRecipe} 已经确认 Polymorph 在场之后才会被加载。
     */
    private static final class Bridge {

        private Bridge() {}

        @Nullable
        static RecipeHolder<CraftingRecipe> getPlayerRecipe(AbstractContainerMenu menu,
                CraftingInput input, Level level, Player player) {
            return com.illusivesoulworks.polymorph.api.PolymorphApi.getInstance()
                    .getRecipeManager()
                    .getPlayerRecipe(menu, RecipeType.CRAFTING, input, level, player)
                    .orElse(null);
        }
    }
}

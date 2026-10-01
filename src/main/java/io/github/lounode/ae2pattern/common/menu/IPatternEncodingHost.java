package io.github.lounode.ae2pattern.common.menu;

import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import appeng.parts.encoding.EncodingMode;
import appeng.util.ConfigInventory;

/**
 * {@link PatternEncodingLogic} 需要的全部外部状态，由编码终端菜单实现。
 *
 * <p>为什么抽这个接口：四套样板编码（合成 / 加工 / 锻造 / 切石）本身只依赖「编码模式、输入输出配置容器、
 * 三个开关、切石配方 id、当前合成配方、世界」，把这七样暴露出来之后，那套编码逻辑就能整体搬出菜单类
 * （{@code PatternDiskEncodingTermMenu} 已经 1700 多行），而菜单只管槽位、同步与动作分发。</p>
 *
 * <p>与 {@link IDiskEncodingLogicHost} 的区别：那个是「磁盘内容」那套逻辑（{@code DiskEncodingLogic}）
 * 的宿主契约，这个是「样板编码」的——两套逻辑互不相干，各自有宿主接口。</p>
 */
public interface IPatternEncodingHost {

    /** 当前编码模式：决定走四套编码里的哪一套。 */
    EncodingMode getEncodingMode();

    /** 编码区的输入配置容器（合成方格 / 加工输入槽等共用这一份）。 */
    ConfigInventory getEncodedInputs();

    /**
     * 取编码区某一格的物品，供编码与产物预览共用：该格为空时返回空栈，
     * 槽位越界或内容不是物品（如流体）时返回 null。
     */
    @Nullable
    ItemStack getCraftingIngredient(int slot);

    /** 编码区的输出配置容器（加工样板的副产物）。 */
    ConfigInventory getEncodedOutputs();

    /** 合成样板是否启用替代物。 */
    boolean isEncodingSubstitute();

    /** 合成样板是否启用流体替代。 */
    boolean isEncodingSubstituteFluids();

    /** 加工样板是否把相同物品合并为单槽。 */
    boolean isEncodingMergeSameItems();

    /** 选中的切石配方 id；没选时为 null。 */
    @Nullable
    ResourceLocation getStonecuttingRecipeId();

    /** 当前 3&times;3 网格对应的合成配方；网格为空或非法时为 null。 */
    @Nullable
    RecipeHolder<CraftingRecipe> getCurrentCraftingRecipe();

    /** 由菜单侧算出的合成产物（含「更新产物」的副作用，所以留在菜单里）。 */
    ItemStack updateAndGetCraftingOutput();

    /** 取世界：锻造与切石要拿它查配方表。 */
    Level getLevel();

    /**
     * 是否停在高级编码模式：给加工样板的每个输入分配一个接入面，编成 AdvancedAE 的高级处理样板。
     */
    boolean isAdvancedMode();

    /**
     * 编辑区每个输入槽分配的面，按 {@link AEKey} 索引；没分配（相邻）的不放键。
     *
     * <p>按 key 而不是按槽下标传：编码路径上的「同物品合并」会把同 key 的多个槽折成一格，下标在那之后
     * 就对不上了。</p>
     */
    Map<AEKey, Direction> advancedSidesByKey();
}

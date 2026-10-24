package io.github.lounode.ae2pattern.common.logic;

import java.util.Set;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/**
 * 阻挡判据的纯运算部分：把「目标里有什么」与「本样板要什么」两组 key 放在一起比较。
 *
 * <p>AE2 的 {@code PatternProviderTarget} 只提供「目标是否含有给定集合中的任一项」（{@code containsPatternInput}），
 * 表达不了「目标里有没有集合之外的项」。智能阻挡需要的正是后者，所以本类按目标内容快照与样板输入集合做补集
 * 判断，不依赖那层接口。</p>
 *
 * <p>比较一律用 {@link AEKey#dropSecondary()}：与 AE2 自己的阻挡判据同口径，工具的磨损程度、流体的附属标签
 * 不参与比较，否则同一配方换一把磨损过的工具就会被当成「相异原料」。</p>
 */
public final class ProviderBlockingRules {

    private ProviderBlockingRules() {
    }

    /**
     * 目标内容里是否存在不属于本样板原材料的项（即「相异配方的原材料」）。
     *
     * @param target    目标库存的内容快照
     * @param ownInputs 本样板声明的全部输入 key（已 dropSecondary）
     * @return 空目标返回 false（放行）；完全由本样板原料构成也返回 false；混入任何外来项返回 true
     */
    public static boolean hasForeignInput(KeyCounter target, Set<AEKey> ownInputs) {
        if (target == null) {
            return false;
        }
        for (var entry : target) {
            var key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (!ownInputs.contains(key.dropSecondary())) {
                return true;
            }
        }
        return false;
    }
}

package io.github.lounode.ae2pattern.common.block.entity;

import java.util.LinkedHashMap;

import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;

import io.github.lounode.ae2pattern.common.logic.ProductDelivery;

/**
 * 单个执行单元的网格装填、清理、合成判定与产物投递。
 *
 * <p>状态是 {@link ProductDelivery}（它自己按失败原因去重记账），加上调用时刻的宿主环境窄契约
 * {@link AssemblerCrafting.CraftingEnv} 与 {@link IActionSource}。本类只操作传进来的那个 {@link CraftUnit}，
 * 不认得 {@code units} 数组、也不持有 BlockEntity；八个单元归 {@link AssemblerCrafting} 调度。</p>
 */
final class AssemblerGridLogistics {

    private static final int GRID_SIZE = PatternDiskAssemblerBlockEntity.GRID_SIZE; // 3x3
    private static final int OUTPUT_SLOT = 9; // gridInv is 10 slots (9 grid + 1 output)

    private final AssemblerCrafting.CraftingEnv env;
    private final IActionSource actionSource;
    private final ProductDelivery delivery;

    AssemblerGridLogistics(AssemblerCrafting.CraftingEnv env, IActionSource actionSource) {
        this.env = env;
        this.actionSource = actionSource;
        // 网格与世界都是拿调用时刻的实况，所以给 Supplier：节点晚于构造器建立，level 则可能为空。
        this.delivery = new ProductDelivery(env::grid, env::level, env.pos(), actionSource, env::saveChanges);
    }

    void fillGrid(CraftUnit unit, KeyCounter[] table, IMolecularAssemblerSupportedPattern adapter) {
        adapter.fillCraftingGrid(table, unit.grid::setItemDirect);
        for (var list : table) {
            list.removeZeros();
        }
    }

    /**
     * Tops this unit's crafting grid up from the ME network for a self-executing page (a pattern
     * manually inserted into the unit's pattern slot), and reports whether the grid can now be
     * assembled.
     *
     * <p>The sparse 3x3 slot mapping is resolved through
     * {@link IMolecularAssemblerSupportedPattern#fillCraftingGrid}, because the compressed input list
     * returned by {@code IPatternDetails#getInputs()} does not expose recipe slot indices.</p>
     *
     * <p><b>为什么是「要么补齐、要么一格不放」.</b> AE2 的样板在 {@code assemble} 里第一件事就是拿传入
     * 网格的裁剪尺寸与样板自己的裁剪尺寸比对，不等就返回空（{@code AECraftingPattern#assemble}），而传入的
     * 尺寸来自网格里非空物品的最小包围盒。所以只要有一次只填进去一部分原料、或某一格被不属于样板的物品占住，
     * 包围盒的形状就与样板不同，{@code canAssemble} 会永远为假——机器表现为「第二次把料拉进网格后就不再
     * 合成」，而料还留在网格里。这里因此先按 key 汇总出这 9 格的真实需求（够不够只能按总量判，逐格判只会
     * 得到「每格都有一件」这种假答案）、再一次性抽取；凑不齐时连样板自己的格子也清空，让网格回到「干净地等料」，
     * 而不是留下一个形状不对的半成品。</p>
     */
    boolean tryFillGridFromNetwork(CraftUnit unit) {
        var plan = unit.plan;
        if (plan == null) {
            return false;
        }
        var grid = env.grid();
        if (grid == null) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var inputs = plan.getInputs();
        if (inputs.length == 0) {
            return false;
        }
        // 网格已经能合成：别动它，让这一轮进度照常累完。
        if (canAssemble(unit)) {
            return true;
        }

        // 每个输入挑一个「网络现在就能给」的候选，按它自己的需求量判（getMultiplier 就是该输入占用的格数）；
        // 给不了也要留一个占位候选，好知道它该落在哪一格。
        var chosen = new AEItemKey[inputs.length];
        var shape = new KeyCounter[inputs.length];
        boolean allAvailable = true;
        for (int i = 0; i < inputs.length; i++) {
            shape[i] = new KeyCounter();
            var input = inputs[i];
            if (input == null) {
                // 样板自己的空占位：不参与可用性判断。
                continue;
            }
            // 预判只取 multiplier、不看候选的 amount()：流体替代样板下预判与实际装填量可能不一致（未实测）。
            long need = Math.max(1, input.getMultiplier());
            AEItemKey first = null;
            for (var possible : input.getPossibleInputs()) {
                if (possible == null || !(possible.what() instanceof AEItemKey itemKey)) {
                    continue;
                }
                if (first == null) {
                    first = itemKey;
                }
                if (storage.extract(itemKey, need, Actionable.SIMULATE, actionSource) >= need) {
                    chosen[i] = itemKey;
                    break;
                }
            }
            var candidate = chosen[i] != null ? chosen[i] : first;
            if (candidate != null) {
                shape[i].add(candidate, GRID_SIZE);
            }
            allAvailable &= chosen[i] != null;
        }

        // 哪个输入落在哪一格由样板自己算（捕获它的写入即可）：我们只需要知道哪些格位属于这颗样板。
        var needed = new boolean[GRID_SIZE];
        var target = new ItemStack[GRID_SIZE];
        plan.fillCraftingGrid(shape, (slot, stack) -> {
            if (slot >= 0 && slot < GRID_SIZE) {
                needed[slot] = true;
                target[slot] = stack;
            }
        });

        // 按 key 汇总一次真实需求再判：逐格判只能回答「这一格有没有一件」，回答不了「总量够不够」——配方要
        // 两件同名材料、网络只有一件时，两格都会判通过，于是抽出前一件、后一件失败，网格里留半份料；而清料
        // 路径优先送相邻容器，那件材料就被搬进了旁边的箱子，每 tick 一次。汇总后网络给不出整份就一格不抽。
        var demand = new LinkedHashMap<AEItemKey, Long>();
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            if (!needed[slot] || target[slot] == null || target[slot].isEmpty()
                    || !unit.grid.getStackInSlot(slot).isEmpty()) {
                continue;
            }
            var key = AEItemKey.of(target[slot]);
            if (key != null) {
                demand.merge(key, 1L, Long::sum);
            }
        }
        for (var entry : demand.entrySet()) {
            if (storage.extract(entry.getKey(), entry.getValue(), Actionable.SIMULATE, actionSource)
                    < entry.getValue()) {
                allAvailable = false;
            }
        }

        // 清掉不该留在网格里的：不在样板里的格位、格位对但物品不对的；凑不齐时连样板自己的格子也清空
        // （半成品的包围盒与样板形状不等，留着只会把这一页钉死）。
        boolean changed = false;
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            var current = unit.grid.getStackInSlot(slot);
            if (current.isEmpty()) {
                continue;
            }
            if (allAvailable && needed[slot] && plan.isItemValid(slot, AEItemKey.of(current), env.level())) {
                continue;
            }
            changed |= ejectGridSlot(unit, slot);
        }
        if (changed) {
            env.saveChanges();
        }
        if (!allAvailable) {
            return false;
        }

        // 真抽：需求量已在上面按 key 核过，正常不会再失败；真失败（同一 tick 里有别的消费者）就把本 tick 已经
        // 抽出来的原路退回 ME 网络——不能留给下一轮的清理路径，那条路也会把材料送回返回节点或网络，
        // 等于中途换一次去向；直接退回网络才能保证「没抽成功就一格不动」。
        var placedKeys = new AEItemKey[GRID_SIZE];
        var placedSlots = new int[GRID_SIZE];
        int placedCount = 0;
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            var stack = target[slot];
            if (stack == null || stack.isEmpty() || !unit.grid.getStackInSlot(slot).isEmpty()) {
                continue;
            }
            var key = AEItemKey.of(stack);
            if (key == null) {
                continue;
            }
            // A crafting grid slot holds exactly one item, even if a third-party pattern wrote a
            // larger stack into the probe table.
            if (storage.extract(key, 1, Actionable.MODULATE, actionSource) <= 0) {
                for (int back = placedCount - 1; back >= 0; back--) {
                    if (storage.insert(placedKeys[back], 1, Actionable.MODULATE, actionSource) > 0) {
                        unit.grid.setItemDirect(placedSlots[back], ItemStack.EMPTY);
                    }
                }
                env.saveChanges();
                return canAssemble(unit);
            }
            unit.grid.setItemDirect(slot, key.toStack(1));
            placedKeys[placedCount] = key;
            placedSlots[placedCount] = slot;
            placedCount++;
            changed = true;
        }
        if (changed) {
            env.saveChanges();
        }
        return canAssemble(unit);
    }

    /**
     * 把一格物品送出网格，走页面自己的回送契约（见 {@link #pushItemOut}）：被发配页先走相邻返回节点、
     * 再回 ME 网络；自拉取页只回 ME 网络、不碰相邻容器。两条路都送不掉的部分才落到输出槽兜底。
     *
     * @return 这一格是否已经清空
     */
    private boolean ejectGridSlot(CraftUnit unit, int slot) {
        var stack = unit.grid.getStackInSlot(slot);
        if (stack.isEmpty()) {
            return true;
        }
        var left = pushItemOut(unit, stack.copy());
        if (left.isEmpty()) {
            unit.grid.setItemDirect(slot, ItemStack.EMPTY);
            return true;
        }
        if (!unit.grid.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
            // 输出槽也占着：把没送出去的部分放回原格，这一格下一轮再清（而不是把它丢掉）。
            unit.grid.setItemDirect(slot, left);
            return false;
        }
        unit.grid.setItemDirect(slot, ItemStack.EMPTY);
        unit.grid.setItemDirect(OUTPUT_SLOT, left);
        return true;
    }

    /** True when the unit's grid can already assemble its plan into a non-empty result. */
    private boolean canAssemble(CraftUnit unit) {
        var level = env.level();
        var plan = unit.plan;
        if (plan == null || level == null) {
            return false;
        }
        var positioned = craftGrid(unit);
        return !plan.assemble(positioned.input(), level).isEmpty();
    }

    /**
     * Builds a positioned crafting input from the unit's grid, reusing the unit's cached crafting container.
     */
    net.minecraft.world.item.crafting.CraftingInput.Positioned craftGrid(CraftUnit unit) {
        for (int i = 0; i < GRID_SIZE; i++) {
            unit.craftingInv.setItem(i, unit.grid.getStackInSlot(i));
        }
        return unit.craftingInv.asPositionedCraftInput();
    }

    /** Keeps returning crafting containers and other remainders until the unit is empty. */
    boolean drainRemainders(CraftUnit unit) {
        boolean pending = false;
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack remainder = unit.grid.getStackInSlot(i);
            if (remainder.isEmpty()) {
                continue;
            }
            ItemStack left = pushItemOut(unit, remainder);
            unit.grid.setItemDirect(i, left);
            pending |= !left.isEmpty();
        }
        return pending;
    }

    /**
     * Moves one grid item that is no longer a valid input for the unit's current pattern out of the grid,
     * so it can not block the unit forever. Mirrors AE2's ejection of stale crafting-grid items after
     * pattern changes and craft completion.
     *
     * <p>早先这里是「挪进输出槽、等下一轮回送」，但输出槽一被占住整个方法就早退，于是该清的东西永远
     * 清不掉、网格的包围盒形状也就此定型（AE2 的样板按裁剪尺寸严格校验，见 tryFillGridFromNetwork）。
     * 现在直接走回送路径（按页面模式：相邻返回节点或 ME 网络），输出槽只是兜底落点。</p>
     */
    void ejectHeldItems(CraftUnit unit) {
        var plan = unit.plan;
        for (int i = 0; i < GRID_SIZE; i++) {
            var stack = unit.grid.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            var key = AEItemKey.of(stack);
            if (plan != null && key != null && plan.isItemValid(i, key, env.level())) {
                continue;
            }
            if (ejectGridSlot(unit, i)) {
                env.saveChanges();
            }
            return;
        }
    }

    /**
     * Sends one item out of a unit, following that page's return contract:
     *
     * <ul>
     * <li>A <b>provider-pushed page</b> ({@code pushDirection} set) returns to the neighbouring return
     * node first - the AE2 molecular assembler contract, where the provider's return inventory hands the
     * goods back to the network - and only what that neighbour refuses goes to ME storage directly.</li>
     * <li>A <b>self-executing page</b> (manual pattern, no push direction) hands products and remainders
     * straight to the ME network. It deliberately never touches adjacent containers: whatever happens to
     * sit beside the machine is not part of the job the page started, and pushing into it would carry the
     * results out of the network.</li>
     * </ul>
     */
    private ItemStack pushItemOut(CraftUnit unit, ItemStack stack) {
        return delivery.deliver(stack, unit.pushDirection);
    }

    /**
     * Pushes the output slot out of the unit, following the page's return contract (see
     * {@link #pushItemOut}).
     */
    void pushOut(CraftUnit unit, ItemStack stack) {
        ItemStack left = pushItemOut(unit, stack);
        unit.grid.setItemDirect(OUTPUT_SLOT, left);
    }
}

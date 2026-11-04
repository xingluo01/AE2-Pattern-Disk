package io.github.lounode.ae2pattern.common.menu.pattern;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.parts.encoding.EncodingMode;

import io.github.lounode.ae2pattern.AEPatternRegistries;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;

/**
 * 磁盘标记的绑定：把「这台终端现在在编什么」写成盘上的 {@code #<标记>}。
 *
 * <p>标记有三个来源，优先级从高到低：右键打标的搜索栏文本（{@code bindSearchMark}，把文本原样存下）、
 * 导入配方的类别（{@code pendingRecipeCategory}）、当前编码模式。前两个只有客户端知道，所以绑定这条链路
 * 永远先由客户端把类别单独送过来，再由服务端落盘。</p>
 *
 * <p><b>系统替玩家办的事不覆盖已有标记</b>：编码后自动写盘、拿编码槽里现成的样板写盘，这两条只在盘上还没有
 * 标记时绑；手动打标才是玩家明确要改那张盘的标记。</p>
 */
final class DiskMarkBinder {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
            .getLogger("ae2_pattern_disk.mark.bind");

    /**
     * 这次该写什么标记：三个来源里只有客户端知道的两个（导入的类别）由调用方快照进来，
     * 剩下的「当前模式」也是一份快照。绑定本身不再向菜单要任何东西。
     */
    record Source(@Nullable String category, EncodingMode mode, boolean chiseling) {
    }

    /** 写回一格磁盘。菜单那边传 {@code this::writeDiskSlot}，绑定逻辑不认得菜单。 */
    @FunctionalInterface
    interface DiskSlots {
        void write(InternalInventory inv, int slot, ItemStack updated);
    }

    private final DiskIndex index;
    private final DiskSlots slots;

    DiskMarkBinder(DiskIndex index, DiskSlots slots) {
        this.index = index;
        this.slots = slots;
    }

    /**
     * 自动写盘成功后顺手把标记绑成当前工作方块——**只在盘上还没有标记时**。
     *
     * <p>这两条（编码后写盘、拿编码槽里现成的样板写盘）是系统替玩家办的事，不该顺手改掉玩家的组织意图，
     * 所以盘上已有的标记一律保留：无论是导入配方留下的 {@code #<类别>}，还是玩家自己写的文本。
     * 手动打标（{@link PatternDiskEncodingTermMenu#bindPrefix(long)} / {@link PatternDiskEncodingTermMenu#bindSearchMark(long)}）是玩家明确要改那张盘的标记，
     * 那才覆盖。</p>
     */
    void bindIfUnmarked(long serial, Source source) {
        var ref = index.refOf(serial);
        if (ref == null) {
            return;
        }
        var stack = ref.host().getDiskInventory().getStackInSlot(ref.slot());
        if (stack.isEmpty()) {
            return;
        }
        // 与分组侧的判据保持一致（{@code tierTypeOf}）：空串当「没标记」，免得两边对同一张盘
        // 一个说有、一个说没有。（正常路径不会写出空串标记：bindSearchMark 在空文本时是 remove。）
        var existing = stack.get(AEPatternRegistries.DISK_PREFIX.get());
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        bind(serial, uploadMark(source), source);
    }

    /**
     * @param markOverride 非 null 时直接用它当作要写的标记、跳过 {@link #deriveMarkId(source)}。编码上传路径用它
     *                     给雕凿档指定固定标记（雕凿不在 {@link EncodingMode} 里、也不对应任何配方类别）；
     *                     右键打标那条手势传 null，沿用「光标上工作方块的类别 / 当前模式」那一套。
     */
    void bind(long serial, @Nullable String markOverride, Source source) {
        var ref = index.refOf(serial);
        if (ref == null) {
            // 客户端列表可能比服务端旧：那张盘已经被拿走了。不出声（AE2 原生对目标消失也是静默的），
            // 留一条 debug 线头。
            LOGGER.debug("[encoding] 打标记时目标磁盘已不在列表（客户端滞后）");
            return;
        }

        var inv = ref.host().getDiskInventory();
        var stack = inv.getStackInSlot(ref.slot());
        if (stack.isEmpty() || !(stack.getItem() instanceof PatternDiskItem)) {
            // 同一个理由：列表里还有它，槽里已经不是了。
            LOGGER.debug("[encoding] 打标记时槽里已不是样板磁盘（客户端滞后）");
            return;
        }

        // 光标上没有可识别的工作方块、也没有刚导入的配方时，deriveMarkId(source) 给出的只是当前模式标记——那不是玩家
        // 对这张盘的判断，写下去只会把盘上原有的标记冲成「处理样板」。这种右键不写，并且跟上传链路一样把结果
        // 说进聊天栏：写没写成，玩家得看得到。
        //
        // 带着 markOverride 进来时不适用这条：雕凿的标记本来就是固定的，不需要「导入过类别」这个前提。
        if (markOverride == null && (source.category() == null || source.category().isEmpty())) {
            LOGGER.info("Bind skipped for disk {}: nothing on the cursor and nothing imported", serial);
            return;
        }

        var mark = markOverride != null ? markOverride : deriveMarkId(source);
        LOGGER.info("Binding mark {} to disk {}", mark, serial);

        var updated = stack.copy();
        updated.set(AEPatternRegistries.DISK_PREFIX, mark);
        slots.write(inv, ref.slot(), updated);
    }

    /**
     * 要绑到磁盘上的标记：导入过配方就用它自己的类别（同一台机器下的不同类别分得开），否则回退到编码模式。
     * 两者都存成 {@code #} 开头的标识符。
     *
     * <p>{@code #mode:} 那一支在绑盘链路上通常出不来：{@link PatternDiskEncodingTermMenu#bindPrefix(long)} 会先判掉空的类别。例外是
     * 编码上传路径（{@link #uploadMark(source)}）——雕凿不在 {@link EncodingMode} 里、也没有配方类别，它带着
     * 一个固定标记进 {@link PatternDiskEncodingTermMenu#bindPrefix(long, String)}，绕过了那个判断。要刻意给磁盘打模式标记
     * 还可以走右键打标（管理终端为 Shift+右键；{@link PatternDiskEncodingTermMenu#bindSearchMark(long)}，它把搜索栏文本原样存下）。</p>
     *
     * <p>两套写法看起来是两种标记，但显示与搜索会把模式标记归一成对应的类别（见
     * {@link DiskMarkRules#categoryForMode}），所以玩家看到的、搜到的名字是一致的。</p>
     *
     * <p><b>已知行为（不是缺陷）</b>：高级档（{@code advancedMode}）不在 {@link EncodingMode} 里、切进去时也不改
     * {@code mode}，所以它<b>没有专属标记</b>——读到的 {@code mode} 是「进入高级档之前那个常规档」，
     * 写出来就是那个常规档的标记（从处理档进去就是处理档的）。
     * 这是已知行为，后续审查不必再把它当新问题报。雕凿档因为样板语义不同
     * （记「把谁雕成谁」，与导入的配方类别无关），单独走 {@link #uploadMark(source)} 给了固定标记；若日后要给高级档
     * 补一个，照 {@link DiskMarkRules#CHISELING_MARK} 那一套加，且同样<b>只加在上传路径上</b>。</p>
     */
    String deriveMarkId(Source source) {
        return DiskMarkRules.deriveMarkId(source.category(), source.mode());
    }

    /**
     * 从磁盘编码上传（顺位写盘）时该绑的标记：雕凿档用固定的模式标记——它的样板记的是「把谁雕成谁」，
     * 与导入的类别无关；其它档沿用 {@link #deriveMarkId(source)}。
     *
     * <p>这个雕凿分支只在**上传路径**上生效，没有写进 {@code deriveMarkId(source)}：标记的常规来源是
     * 「导入配方」与「右键打标的搜索栏文本」两条，它们各按各的口径走，不经过这里。</p>
     *
     * <p>只给雕凿开分支，是因为它的样板语义与常规档不同（记的是「把谁雕成谁」，与导入的配方类别无关）。
     * <b>高级档没有专属标记</b>——它继承进入本档之前那个常规档的，属已知行为；
     * 不要顺手在这里补一个：那会改变磁盘上现有标记的含义，不是修复。</p>
     */
    private String uploadMark(Source source) {
        return source.chiseling() ? DiskMarkRules.CHISELING_MARK : deriveMarkId(source);
    }
}

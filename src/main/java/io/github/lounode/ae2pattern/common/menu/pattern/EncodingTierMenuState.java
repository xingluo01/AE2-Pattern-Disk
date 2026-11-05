package io.github.lounode.ae2pattern.common.menu.pattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.network.PacketDistributor;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.network.ServerboundPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.InventoryAction;
import appeng.menu.guisync.GuiSync;
import appeng.menu.slot.FakeSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.util.ConfigInventory;

import io.github.lounode.ae2pattern.api.PatternClassifier;
import io.github.lounode.ae2pattern.common.item.PatternDiskItem;
import io.github.lounode.ae2pattern.common.menu.AbstractPatternDiskTermMenu;
import io.github.lounode.ae2pattern.common.menu.IPatternDiskTerminalHost;
import io.github.lounode.ae2pattern.integration.advancedae.AdvPatternSupport;
import io.github.lounode.ae2pattern.integration.ae2lt.OverloadPatterns;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingPatternEncoder;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingRecipes;

/**
 * 编码终端的「档位状态 + 状态机」父类。
 *
 * <p>为什么是父类而不是协作类：AE2 的 {@code DataSynchronization#collectFields} 从运行时类出发，
 * 沿继承链逐层 {@code getDeclaredFields()} 收集 {@link GuiSync} 字段，直到 {@code AbstractContainerMenu}。
 * 也就是说同步字段可以放在父类里，但**不能**放进菜单持有的某个协作对象——后者根本不在扫描范围内。
 * 于是把雕凿 / 高级 / 过载三档的 20 多个同步字段连同操作它们的状态机整体搬到这里，
 * 具体菜单只留槽位、动作登记、编码流程与磁盘清单。</p>
 *
 * <p>本类不实现 {@code IPatternEncodingHost}：接口由具体菜单实现，它继承本类的公开方法即可满足契约，
 * 所以 {@code advancedSidesByKey()} 上不能写 {@code @Override}。</p>
 *
 * <p>本类只多要具体菜单两样东西：两个样板槽的那本账（{@link #dock()}）与雕凿输入槽
 * （{@link #chiselingInputSlot()}）。其余状态都在这里，不回头摸菜单。</p>
 */
public abstract class EncodingTierMenuState extends AbstractPatternDiskTermMenu {

    protected final IPatternDiskTerminalHost host;
    protected final DiskEncodingLogic encodingLogic;
    protected final ConfigInventory encodedInputsInv;
    protected final ConfigInventory encodedOutputsInv;

    protected EncodingTierMenuState(MenuType<?> menuType, int id, Inventory ip,
            IPatternDiskTerminalHost host) {
        super(menuType, id, ip, host);
        this.host = host;
        this.encodingLogic = host.getLogic();
        this.encodedInputsInv = encodingLogic.getEncodedInputInv();
        this.encodedOutputsInv = encodingLogic.getEncodedOutputInv();
    }

    /** 编码槽与空白样板槽的那本账；两个槽由具体菜单在构造期建好。 */
    protected abstract PatternDock dock();

    /** 雕凿档自己的输入槽；具体菜单在构造期建好。 */
    protected abstract FakeSlot chiselingInputSlot();

    protected static final String ACTION_SET_MODE = "setMode";

    protected static final String ACTION_SET_ADVANCED_MODE = "setAdvancedMode";
    protected static final String ACTION_SET_CHISELING_MODE = "setChiselingMode";
    protected static final String ACTION_SET_CHISELING = "setChiseling";
    protected static final String ACTION_SET_ADVANCED_SIDE = "setAdvancedSide";
    protected static final String ACTION_SET_OVERLOADED_MODE = "setOverloadedMode";
    protected static final String ACTION_SET_OVERLOADED_ROW = "setOverloadedRow";

    /**
     * 客户端侧：最近一次从 EMI/JEI 导入的配方类别 id。绑定标记时优先用它——它才是「这是一台什么
     * 机器」的答案，编码模式只是四个粗类。为空则退回编码模式。
     */
    @Nullable
    protected String pendingRecipeCategory;

    @GuiSync(97)
    public EncodingMode mode = EncodingMode.CRAFTING;
    @GuiSync(96)
    public boolean substitute = false;
    @GuiSync(95)
    public boolean substituteFluids = true;
    @GuiSync(94)
    @Nullable
    public ResourceLocation stonecuttingRecipeId;

    /** 当前模式的标记（如 #mode:crafting），用于搜索栏自动填充。空字符串=无。由服务端在 broadcastChanges 中同步。 */
    @GuiSync(93)
    public String recipePrefix = "";

    /** 处理模式下同物品合并开关（true=启用，false=禁用）。权威值在宿主 logic 里，这里是回读的镜像。 */
    @GuiSync(92)
    public boolean mergeSameItems = true;

    /**
     * 是否把无标记的样板磁盘也列出来。与「替换」同款：权威值在宿主部件自己的 logic 上（随部件 NBT 持久化），
     * 服务端每 tick 把它回读进这个镜像字段，客户端按钮跟着它走。
     */
    @GuiSync(91)
    public boolean showUnmarkedDisks;

    /**
     * 雕凿档是否已开放。
     *
     * <p>面板（候选列表 + 编码路径）做完前必须为 false：它一旦生效而面板还没挂，屏幕会停在四个常规面板
     * 全让位后的空白区，而雕凿档下 {@code mode} 仍是 CRAFTING（与高级档同样的机制），点「编写样板」会把
     * 输出栏那张样板当合成样板编。面板就绪后翻这一行即可——所有入口与守卫都读它。</p>
     */
    public static final boolean CHISELING_TIER_ENABLED = true;

    @GuiSync(85)
    public int selectedChiseling = -1;

    /**
     * 雕凿输入槽被程序设过几次（配方导入、放入雕凿样板切档）。
     *
     * <p>同步到客户端，面板靠它区分「输入槽的变化是程序干的」与「玩家自己换了输入」：前者连着把选中序号
     * 也设好了，清掉会让导入变成「档位对、输入对、却没有选中项」，点「编写样板」只得到一句「需要先选候选」；
     * 后者才该把旧序号作废。理由与完整推演见 {@code docs/ENCODING_MODES.md} 的配方导入那一节。</p>
     */
    @GuiSync(104)
    public int chiselingInputRevision;

    @GuiSync(87)
    public boolean chiselingModeAvailable;

    /** 当前是否停在雕凿编码模式。与高级档一样，它不在 AE2 的 {@link EncodingMode} 里。 */
    @GuiSync(86)
    public boolean chiselingMode;

    /** 升级槽里装着高级样板编码器——高级编码模式因此可用（屏幕上才会多出那一档）。 */
    @GuiSync(90)
    public boolean advancedModeAvailable;

    /** 当前是否停在高级编码模式。它不在 AE2 的 {@link EncodingMode} 里（那个枚举不可扩展），是并列的一档。 */
    @GuiSync(89)
    public boolean advancedMode;

    /**
     * 样板输出栏那张高级样板每个输入声明的接入面：{@code Direction} 的序号，或 -1 表示「相邻」（没指定）。
     * 逗号分隔、按输入顺序——面板上的按钮逐行跟着它亮。
     */
    @GuiSync(88)
    public String advancedSides = "";

    /** 装没装 AE2 Lightning Tech——过载档因此可用（屏幕上才会多出那一档）。 */
    @GuiSync(99)
    public boolean overloadedModeAvailable;

    /** 当前是否停在过载编码模式。同样不在 AE2 的 {@link EncodingMode} 里，是并列的一档。 */
    @GuiSync(98)
    public boolean overloadedMode;

    /** 过载档每行是输出(1)还是输入(0)，逗号分隔、按行序。面板上的行与编码区的槽一一对应。 */
    @GuiSync(102)
    public String overloadedSides = "";

    /** 过载档每行是否「忽略组件匹配」（1=忽略）。缺位一律当 0，也就是开关的默认态「启用组件匹配」。 */
    @GuiSync(103)
    public String overloadedMatchModes = "";

    /**
     * 服务端：ME 网络里可持续供应的空白样板数（{@link #refillBlankPatternSlot()} 抽料后剩下的量）。
     *
     * <p>槽改成实物之后，客户端光看槽已经不知道网络还有没有货，而「编码按钮要不要亮」靠这个判断。
     * 取 int 是因为 {@code @GuiSync} 只认得基本类型与字符串，而这个数只用来回答「有没有」。
     */
    @GuiSync(105)
    public int networkBlankPatternCount;

    private final List<RecipeHolder<StonecutterRecipe>> stonecuttingRecipes = new java.util.ArrayList<>();

    protected void updateStonecuttingRecipes() {
        stonecuttingRecipes.clear();
        if (encodedInputsInv.getKey(0) instanceof AEItemKey itemKey) {
            var level = getPlayer().level();
            var recipeInput = new SingleRecipeInput(itemKey.toStack());
            stonecuttingRecipes.addAll(level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, recipeInput,
                    level));
        }
        if (stonecuttingRecipeId != null
                && stonecuttingRecipes.stream().noneMatch(r -> r.id().equals(stonecuttingRecipeId))) {
            stonecuttingRecipeId = null;
        }
    }

    public EncodingMode getMode() { return this.mode; }

    // ---- 高级编码模式 --------------------------------------------------------

    /**
     * 高级编码模式编辑的是样板输出栏里那张**已编码**的高级样板：读出它的输入与「输入 → 接入面」表，让玩家
     * 逐个输入改面，再整张重写回去。
     *
     * <p>它不动样板的输入与输出，只动方向表——那正是 AdvancedAE 自己的编码器编不出来的东西，也是本模组的
     * 供应器按面投递时要读的东西。没有产物槽、也不碰编码按钮：产物就是输出栏里原来那张样板。</p>
     */
    public record AdvancedSideChange(int input, int side) {
    }

    /** 每个输入槽分配的面序号；-1 = 相邻。客户端与服务端都读它。 */
    public int advancedSideAt(int slot) {
        var parts = this.advancedSides.isEmpty() ? new String[0] : this.advancedSides.split(",");
        if (slot < 0 || slot >= parts.length) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[slot].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 是否停在高级编码模式（供编码路径判断要不要编成高级处理样板）。 */
    public boolean isAdvancedMode() {
        return this.advancedMode;
    }

    public void setOverloadedMode(boolean on) {
        if (isClientSide()) {
            // 客户端只换自己的显示状态：要摆进编辑区的那些物得由服务端从样板里摊（那是世界数据）。
            this.overloadedMode = on;
            this.overloadedSides = "";
            this.overloadedMatchModes = "";
            sendClientAction(ACTION_SET_OVERLOADED_MODE, on);
            return;
        }
        this.overloadedMode = on;
        // 两张表先清：每次进档都重新从输出栏那张样板摊一遍，不做跨次残留。
        this.overloadedSides = "";
        this.overloadedMatchModes = "";
        this.encodingLogic.setOverloadedMode(on);
        this.encodingLogic.setOverloadedSides("");
        this.encodingLogic.setOverloadedMatchModes("");
        if (!on) {
            return;
        }
        seedOverloadedRowsFromPattern();
    }

    // ---- 档位跟随样板输出栏 ----

    /** 上一次看到的输出栏物件身份（null = 空）。比物品身份就够：档位只由样板物品类型决定。 */
    @Nullable
    private Item lastOutputPatternItem;

    /** 上一次看到的输出栏是不是空的。 */
    private boolean lastOutputWasEmpty;

    /**
     * 输出栏那份快照是不是建过基线了。
     *
     * <p>开屏第一帧只记值、不动作：存档里本来就停着一枚样板（玩家上次没拿走），那是现状而不是
     * 「刚放进去」，据此改档会盖掉玩家存下来的档位。</p>
     */
    private boolean outputTierBaselineSet;

    /**
     * 样板输出栏里出现一枚样板时，把档位切到与它对应的那一档。
     *
     * <p>玩家的意图很直接：往输出栏里放一枚样板就是要接着编辑它（或者干脆是回来改它）。终端却可能停在
     * 另一个档上——雕凿样板停在合成档、高级处理样板停在处理档——面板显示的东西与槽里那枚样板对不上。
     * 所以槽里那枚样板的类型就是档位的依据。这与「进档时从这张样板摊东西」是同一套口径：过载档摊行、
     * 高级档读方向表与输入输出，它们都在本类的 setter 里，切过去就自然跑。</p>
     *
     * <p>只在**内容真的变了**时才判，且只比物品身份：同物品的另一枚样板（不同配方）不需要重新切档。
     * 编码路径自己写进输出栏那一次也会走到这里，那时档位本来就对得上，是空操作。</p>
     */
    protected void syncTierWithPatternOutput() {
        var current = dock().encoded();
        var item = current.isEmpty() ? null : current.getItem();
        if (!this.outputTierBaselineSet) {
            this.outputTierBaselineSet = true;
            adoptOutputPatternAsBaseline();
            return;
        }
        if (item == this.lastOutputPatternItem && current.isEmpty() == this.lastOutputWasEmpty) {
            return;
        }
        adoptOutputPatternAsBaseline();
        if (item == null) {
            // 被拿走 / 被清成空白样板：不动档位。玩家可能正要往这个档里写东西，替他把档切走只会碍事。
            return;
        }
        switchToTierForPatternType(PatternClassifier.typeOf(current, getPlayer().level()));
    }

    /**
     * 把输出栏当前内容记成基线，表示「这一次变化已消化过」。
     *
     * <p>编码路径写完输出栏后也会调它：那一枚是本菜单按当前档位自己编出来的，不是玩家放进去的。
     * 不跟上的话，下一帧 {@link #syncTierWithPatternOutput} 会把它当成「玩家换了样板」而反过来改档位
     * ——高级档补面时编出的那张普通处理样板能把人踢出高级档。</p>
     */
    protected void adoptOutputPatternAsBaseline() {
        var current = dock().encoded();
        this.lastOutputPatternItem = current.isEmpty() ? null : current.getItem();
        this.lastOutputWasEmpty = current.isEmpty();
    }

    /**
     * 按一枚已编码样板的类型切到对应的编码档；认不出的类型（如万象——编码侧还没接）不动。
     *
     * <p>额外档要连它的可用性一起判：不可用就是「升级槽里没那枚编码器」，切过去只会是一屏空的隐藏面板，
     * 比停着不动更让人困惑。</p>
     */
    private void switchToTierForPatternType(@Nullable String patternTypeId) {
        if (patternTypeId == null) {
            return;
        }
        if (PatternDiskItem.CHISELING_PATTERN.equals(patternTypeId)) {
            if (this.chiselingModeAvailable && CHISELING_TIER_ENABLED) {
                if (!this.chiselingMode) {
                    closeExtraTiers();
                    setChiselingMode(true);
                }
                // 已经在雕凿档也要重推：换的是另一枚雕凿样板，输入与选中得跟着它变。
                seedChiselingFromPattern();
            }
            return;
        }
        if (PatternDiskItem.ADVANCED_PROCESSING_PATTERN.equals(patternTypeId)) {
            if (this.advancedModeAvailable) {
                if (!this.advancedMode) {
                    closeExtraTiers();
                }
                // 与雕凿档同理：setAdvancedMode(true) 自己会从输出栏那张样板重新摊一遍输入输出与方向表，
                // 所以已经在档时也要再调一次。
                setAdvancedMode(true);
            }
            return;
        }
        if (PatternDiskItem.OVERLOAD_PATTERN.equals(patternTypeId)) {
            if (this.overloadedModeAvailable) {
                if (!this.overloadedMode) {
                    closeExtraTiers();
                }
                setOverloadedMode(true);
            }
            return;
        }
        var mode = DiskMarkRules.modeForPatternType(patternTypeId);
        if (mode == null) {
            return;   // 认不出的类型（万象等）不动。
        }
        // 常规档与额外档是并列的：切过去之前**一律**先关掉三个额外档，这一步不跟「mode 是否变化」挂钩
        // ——人在高级档、放进去的却是合成样板时 mode 本来就等于 CRAFTING，不关的话屏幕会继续停在高级档，
        // 而那正是本功能要消除的「面板与槽里那枚样板对不上」。
        closeExtraTiers();
        if (mode != this.mode) {
            setMode(mode);
        }
    }

    /**
     * 关掉三个并列的额外档。
     *
     * <p>与客户端侧 {@code DiskEncodingHelper.closeExtraTiers} 同口径；那一个在 client 源集里，
     * 菜单（common）用不了，所以这里自己一份——它只碰菜单字段与 logic。</p>
     */
    private void closeExtraTiers() {
        if (this.advancedMode) {
            setAdvancedMode(false);
        }
        if (this.chiselingMode) {
            setChiselingMode(false);
        }
        if (this.overloadedMode) {
            setOverloadedMode(false);
        }
    }

    /**
     * 进雕凿档时从那枚雕凿样板反推「把谁雕成谁」，把输入摆上、选中项定好。
     *
     * <p>与过载档摊行、高级档读方向表同一个口径：进档时就把编辑区按输出栏那枚样板摆好，玩家不用再手点
     * 一次候选。样板不是雕凿样板、或它的产物不在候选表里（数据包换过、那件物品不再参与雕凿）就什么都不
     * 做——留一个空档出来，玩家自己去选。</p>
     */
    private void seedChiselingFromPattern() {
        var decoded = ChiselingRecipes.decode(dock().encoded());
        if (decoded == null) {
            return;
        }
        var index = ChiselingRecipes.serverIndexOf(decoded.input(), decoded.output());
        if (index < 0) {
            return;
        }
        // 顺序要紧：先摆输入、再定选中。面板每帧看输入槽是否变过，一变就把选中项作废并重算候选
        // ——选中写在填输入之前会被那一下清掉（setChiselingInput 会把修订号推上去，面板因此知道
        // 这一次的输入变化是程序干的，见 chiselingInputRevision）。
        setChiselingInput(new ItemStack(decoded.input()));
        setChiseling(index);
    }

    /**
     * 进过载档时从输出栏那张样板摊一行。
     *
     * <p>与高级档不同的一点是：**输出产物也要摊进编辑区**，因为过载档的每一行就是一个物品，得由玩家
     * 自己说它是输入还是输出。摊进来的行里输入在前、输出在后，后者直接标成「输出」——那是主产物
     * 的定义所在，不标的话默认会当成一个输入项。</p>
     *
     * <p>输出栏空着或不是可解码的样板时直接返回：玩家常常先切进来、再往输出栏放东西，那是正常路径。</p>
     */
    private void seedOverloadedRowsFromPattern() {
        var details = PatternDetailsHelper.decodePattern(dock().encoded(), getPlayer().level());
        if (details == null) {
            return;
        }
        encodedInputsInv.clear();
        var sides = new StringBuilder();
        var modes = new StringBuilder();
        var row = 0;
        // 输入优先取「实际材料表」（getSparseInputs 给的是每格真正用到的那个，而不是候选模板），
        // 只有 AE2 自己的处理样板公开了这个视图；其余样板退回候选表的第一项。
        //
        // 两张表各自逐项摊，不按下标互相索引：同一物品占两格时 getSparseInputs 比 getInputs 长
        // （后者已按 key 合并成一条），拿后者的下标去查前者会错位，顺带把每行的数量也带偏。
        var inputs = new ArrayList<GenericStack>();
        if (details instanceof AEProcessingPattern processing) {
            for (var stack : processing.getSparseInputs()) {
                if (stack != null && stack.what() != null) {
                    inputs.add(stack);
                }
            }
        } else {
            for (var input : details.getInputs()) {
                var stack = firstCandidate(input);
                if (stack != null && stack.what() != null) {
                    inputs.add(stack);
                }
            }
        }
        var outputs = new ArrayList<GenericStack>();
        for (var output : details.getOutputs()) {
            if (output != null && output.what() != null) {
                outputs.add(output);
            }
        }
        // 输入在前、输出在后，两段合计超过编辑区容量时整表放弃：过载档的编码要的是一份完整行表，按行截断
        // 会把排在最后的输出（含主产物）悄悄丢掉，那比不摊更糟。行表清空后编码路径会按「没东西可编」提示。
        if (inputs.size() + outputs.size() > encodedInputsInv.size()) {
            this.overloadedSides = "";
            this.overloadedMatchModes = "";
            this.encodingLogic.setOverloadedSides(this.overloadedSides);
            this.encodingLogic.setOverloadedMatchModes(this.overloadedMatchModes);
            return;
        }
        for (var stack : inputs) {
            encodedInputsInv.setStack(row, stack);
            row = EncodingFlags.appendRow(sides, modes, row, false);
        }
        for (var stack : outputs) {
            encodedInputsInv.setStack(row, stack);
            // 标成输出：主产物就在这一批里，不标的话编码时会把它当成又一个输入项。
            row = EncodingFlags.appendRow(sides, modes, row, true);
        }
        this.overloadedSides = sides.toString();
        this.overloadedMatchModes = modes.toString();
        this.encodingLogic.setOverloadedSides(this.overloadedSides);
        this.encodingLogic.setOverloadedMatchModes(this.overloadedMatchModes);
    }

    /** 一个输入格的候选表里挑第一个；一格多个候选时挑不准，挑一个总比空着强（玩家可自己改编辑区）。 */
    @Nullable
    private static GenericStack firstCandidate(IPatternDetails.IInput input) {
        var candidates = input.getPossibleInputs();
        return candidates.length == 0 ? null : candidates[0];
    }

    /** 是否停在过载编码模式（供编码路径判断要不要编成过载样板）。 */
    public boolean isOverloadedMode() {
        return this.overloadedMode;
    }

    /** 过载档第 row 行是输出还是输入。没写过的行按输入算。 */
    public boolean overloadedRowIsOutput(int row) {
        return EncodingFlags.flagAt(this.overloadedSides, row);
    }

    /** 过载档第 row 行是否忽略组件匹配。没写过的行按不忽略算——也就是开关的默认态。 */
    public boolean overloadedRowIgnoresComponents(int row) {
        return EncodingFlags.flagAt(this.overloadedMatchModes, row);
    }

    /** 过载档某一行改了什么（物品属性 = 输入/输出，加上那个组件匹配开关）。 */
    public record OverloadedRowChange(int row, boolean output, boolean ignoreComponents) {
    }

    /**
     * 客户端点过载档某一行：本地先改（界面立刻响应），再发给服务端；服务端的改动会同步回来。
     *
     * <p>与高级档的方向按钮同口径：只改菜单上的两张表，样板一个字节也不动——它在点「编写样板」那一刻
     * 才并进去。</p>
     */
    public void setOverloadedRow(int row, boolean output, boolean ignoreComponents) {
        // 上界也要查：行号来自客户端，越界会把那张 0/1 表撑成一条没有意义的长串（并照样写进存档）。
        if (row < 0 || row >= encodedInputsInv.size()) {
            return;
        }
        if (isClientSide()) {
            applyOverloadedRow(row, output, ignoreComponents);
            sendClientAction(ACTION_SET_OVERLOADED_ROW,
                    new OverloadedRowChange(row, output, ignoreComponents));
        } else {
            applyOverloadedRow(row, output, ignoreComponents);
        }
    }

    /** 改本次编辑的过载行表，并把结果交给 logic 存住（关屏重开不掉）。 */
    protected void applyOverloadedRow(int row, boolean output, boolean ignoreComponents) {
        this.overloadedSides = EncodingFlags.withFlag(this.overloadedSides, row, output);
        this.overloadedMatchModes = EncodingFlags.withFlag(this.overloadedMatchModes, row, ignoreComponents);
        this.encodingLogic.setOverloadedSides(this.overloadedSides);
        this.encodingLogic.setOverloadedMatchModes(this.overloadedMatchModes);
    }

    /**
     * 编辑区每个输入槽分配的面，按 {@link AEKey} 索引；没分配（相邻）的不放键。
     *
     * <p>编码路径上的「同物品合并」会改变输入的下标，所以方向表按 key 递过去。</p>
     */
    public java.util.Map<AEKey, Direction> advancedSidesByKey() {
        var out = new LinkedHashMap<AEKey, Direction>();
        for (int i = 0; i < encodedInputsInv.size(); i++) {
            var key = encodedInputsInv.getKey(i);
            if (key == null) {
                continue;
            }
            var side = advancedSideAt(i);
            // 没分配的格子显式放进一个 null（「未指定，交给供应器按相邻那面」），与上游自己的
            // AdvPatternEncoderMenu 同一写法（它对每个输入都 put(key, null)）。AdvancedAE 的编码会把它
            // 翻成 NULLDIR，缺键与 null 值等价——这里补全只是为了少一条隐含约定。
            out.put(key, side < 0 ? null : Direction.values()[side]);
        }
        return out;
    }

    /** 升级槽里是否装着高级样板编码器。 */
    protected boolean hasAdvancedEncoder() {
        for (var stack : host.getUpgrades()) {
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(AdvPatternSupport.ADVANCED_ENCODER)) {
                return true;
            }
        }
        return false;
    }

    /** 升级槽里是否装着过载样板编码器。与上一支同形：只认物品 id、不看数量。 */
    protected boolean hasOverloadEncoder() {
        for (var stack : host.getUpgrades()) {
            if (OverloadPatterns.isEncoder(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 升级槽里是否装着雕凿样板编码器。与上一支同形：只认物品 id，不看数量。 */
    protected boolean hasChiselingEncoder() {
        for (var stack : host.getUpgrades()) {
            if (ChiselingPatternEncoder.isEncoder(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 选中第几个雕凿候选。两侧都按同一份配方表算候选，所以只传序号——不传列表。
     *
     * <p>序号在服务端可能对不上（配方表是同步过来的，理论上与客户端同源，但服务端总是权威）：
     * 落盘时按序号现查，查不到就什么也不做，比写出一枚错的雕凿样板好。</p>
     */
    /**
     * 面板上那一个雕凿输入格（与选中的候选一起决定「把谁雕成谁」）。
     *
     * <p>与 {@link #setChiseling} 不同，这个参数是 <b>ItemStack</b>，不能当 client action 的参数——
     * AE2 那条路走裸 Gson、没有 ItemStack 适配器（它自用的参数类型只有 Boolean/Integer/Long/String/
     * ResourceLocation/枚举几种），而 ItemStack 带着 Holder&lt;Item&gt; 与组件表，序列化不了。所以走
     * AE2 给槽位准备的 {@link InventoryActionPacket}（SET_FILTER），也就是本项目导入配方时用的同一条路。</p>
     */
    public void setChiselingInput(ItemStack stack) {
        var value = stack == null ? ItemStack.EMPTY : stack;
        // 先记一笔「这次输入是程序设的」：面板重算候选时要靠它与「玩家自己换了输入」区分开。
        this.chiselingInputRevision++;
        if (isClientSide()) {
            // 本地先改，面板下一帧就能按新输入重算候选。
            chiselingInputSlot().set(value);
            ServerboundPacket message = new InventoryActionPacket(
                    InventoryAction.SET_FILTER, chiselingInputSlot().index, value);
            PacketDistributor.sendToServer(message);
            return;
        }
        chiselingInputSlot().set(value);
    }

    public void setChiseling(int index) {
        if (isClientSide()) {
            this.selectedChiseling = index;
            sendClientAction(ACTION_SET_CHISELING, index);
            return;
        }
        this.selectedChiseling = index;
    }

    /**
     * 切换雕凿编码模式。
     *
     * <p>与高级档不同，这一档不从输出栏那张样板里摊任何东西：雕凿的候选来自 Rechiseled 自己的配方表
     * （{@code ChiselingRecipeManager}），不来自样板。所以这里只切开关，权威值写进 logic 跟着终端持久化。</p>
     */
    public void setChiselingMode(boolean on) {
        if (isClientSide()) {
            // 客户端先换自己的显示状态（面板要立刻跟着变），权威值由服务端回读后下发。
            this.chiselingMode = on;
            sendClientAction(ACTION_SET_CHISELING_MODE, on);
            return;
        }
        this.chiselingMode = on;
        this.encodingLogic.setChiselingMode(on);
    }

    /**
     * 切换高级编码模式。进去时把输出栏那张样板的原材料拦进编辑区、方向归零；出来时只关开关。
     *
     * <p>不把方向写回任何样板：它只在点「编写样板」那一刻并进去（见
     * {@link PatternEncodingLogic#encodeProcessingPattern()}）。所以进出模式不会破坏输出栏里那张样板。</p>
     *
     * <p>接受**普通处理样板**：它就是「玩家已经编好的样板」，而高级化的意思正是给它的每个输入补一个接入
     * 面。要求先有一张高级样板会变成一个悖论——那样板本来就是这条路要产出的东西。</p>
     */
    public void setAdvancedMode(boolean on) {
        if (isClientSide()) {
            // 客户端只换自己的显示状态：那几格原材料得由服务端从样板里摊（那是世界数据）。
            this.advancedMode = on;
            this.advancedSides = "";
            sendClientAction(ACTION_SET_ADVANCED_MODE, on);
            return;
        }
        this.advancedMode = on;
        this.advancedSides = "";
        // 权威值写进 logic（与 mode 同处）：它们一起回答「当前在哪一档」，得跟着终端走。
        this.encodingLogic.setAdvancedMode(on);
        if (!on) {
            return;
        }
        var details = PatternDetailsHelper.decodePattern(dock().encoded(), getPlayer().level());
        // 普通处理样板与高级样板都得收：前者是「玩家已编好、想给它补面」的那张，后者是「回来改面」的那张。
        // 注意两者**不是**同一个类——AE2 的是 AEProcessingPattern，AdvancedAE 的 AdvProcessingPattern 只
        // 实现 IPatternDetails，所以不能只判 AE2 那个类。
        var isPlainProcessing = details instanceof AEProcessingPattern;
        var isAdvanced = AdvPatternSupport.isEditable(details);
        if (!isPlainProcessing && !isAdvanced) {
            // 输出栏里没有可编辑的处理样板：停在空面板上。
            // 不回滚模式：玩家常常是先切进来、再往输出栏放样板。
            return;
        }
        // 输入从样板的「实际材料表」读，而不是从 getInputs()：后者给的是每格的候选模板，倍数与替代品都在
        // 里面。高级样板走 AdvancedAE 的接口，普通处理样板直接读 AE2 自己那一份（getSparseInputs 是 public）。
        var inputs = isAdvanced
                ? AdvPatternSupport.sparseInputs(details)
                : ((AEProcessingPattern) details).getSparseInputs();
        if (inputs == null) {
            return;
        }
        encodedInputsInv.clear();
        for (int i = 0; i < inputs.size() && i < encodedInputsInv.size(); i++) {
            encodedInputsInv.setStack(i, inputs.get(i));
        }
        // 输出也要摊：编码路径要求首个输出非空（那是「主产物」的定义），空着必定编不出来。
        // 高级模式下 PROCESSING_OUTPUTS 槽是藏起来的，玩家也补不了，所以必须在这里给全。
        encodedOutputsInv.clear();
        var outputs = details.getOutputs();
        for (int i = 0; i < outputs.size() && i < encodedOutputsInv.size(); i++) {
            encodedOutputsInv.setStack(i, outputs.get(i));
        }

        // 把样板已有的面读回来。玩家进来常常是「看一眼再改一格」，不播种的话面板会把每一行都显示成
        // 「相邻」，那时点「编写样板」就把原有的面按 NULLDIR 写回去——配置被静默丢掉，还会顺着写盘落进磁盘。
        // 只有高级样板有这张表；普通处理样板没有面可读，保持全 -1。
        var existing = AdvPatternSupport.directionMap(details);
        if (existing != null && !existing.isEmpty()) {
            var builder = new StringBuilder();
            for (int i = 0; i < encodedInputsInv.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                var key = encodedInputsInv.getKey(i);
                var direction = key == null ? null : existing.get(key);
                builder.append(direction == null ? -1 : direction.ordinal());
            }
            this.advancedSides = builder.toString();
        }
    }

    /**
     * 客户端点某个方向按钮：本地先改（界面立刻响应），再发给服务端；服务端的改动会同步回来。
     */
    public void setAdvancedSide(int input, int side) {
        if (isClientSide()) {
            applyAdvancedSide(input, side);
            sendClientAction(ACTION_SET_ADVANCED_SIDE, new AdvancedSideChange(input, side));
        } else {
            applyAdvancedSide(input, side);
        }
    }

    /** 改本次编辑的方向表；样板一个字节也不动。 */
    protected void applyAdvancedSide(int input, int side) {
        if (input < 0) {
            return;
        }
        var size = Math.max(input + 1, encodedInputsInv.size());
        var sides = new int[size];
        for (int i = 0; i < size; i++) {
            sides[i] = advancedSideAt(i);
        }
        sides[input] = side;
        var out = new StringBuilder();
        for (int value : sides) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        this.advancedSides = out.toString();
    }


    public void setMode(EncodingMode mode) {
        if (this.mode != mode && mode == EncodingMode.STONECUTTING) {
            updateStonecuttingRecipes();
        }
        if (isClientSide()) {
            // 手动换模式等于放弃刚才导入的那个配方：类别是绑盘时要写的标记，留着它会让下一次绑定写出
            // 一份与当前模式不符的标记。导入自己也会走 setMode，所以设置类别的一方要放在编码之后。
            pendingRecipeCategory = null;
            sendClientAction(ACTION_SET_MODE, mode);
        } else {
            this.mode = mode;
        }
    }

    public List<RecipeHolder<StonecutterRecipe>> getStonecuttingRecipes() { return stonecuttingRecipes; }

}

package io.github.lounode.ae2pattern.integration.rechiseledae;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.supermartijn642.rechiseled.ae.RechiseledAE;
import com.supermartijn642.rechiseled.ae.chiseling_pattern.EncodedChiselingPattern;
import com.supermartijn642.rechiseled.api.chiseling.ChiselingRecipeManager;

/**
 * Rechiseled 雕凿配方的读取与雕凿样板的编码。这是本仓唯一直接引用 Rechiseled / RechiseledAE2 类的文件，
 * 单独隔开是为了让它们缺席时只有这个类加载失败——调用方在碰它之前先用 ModList 判前置。
 *
 * <p>候选的粒度取「一个风格组内有连接材质版的那种对」：{@code getAnyItem()} 给普通款、
 * {@code getAnyConnectingItem()} 给带连接材质的款，两者都有才成一项。Rechiseled 的配方是「同一风格的
 * 方块/楼梯/台阶互为变体」的无向集合，官方那台编码器是让玩家自己放输入方块再从候选里挑输出——本模组的
 * 终端没有那个输入槽，所以直接把「把谁雕成谁」写进候选项里。</p>
 *
 * <p><b>配方表要按侧取。</b>Rechiseled 自己维护两份：服务端那份在数据重载时填，客户端那份由它整份同步过来
 * （玩家加入时发一次，重载时再发）。CLIENT 副本在收到之前是空的，拿它会抛
 * {@code IllegalStateException}。上游把服务端表**按序**同步，所以两侧的候选顺序一致，可以只传序号。</p>
 */
public final class ChiselingRecipes {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.rechiseledae");

    /** 一项候选：把 {@code input} 雕成 {@code output}。 */
    public record Candidate(Item input, Item output) {
    }

    /**
     * 面板侧：客户端那一份配方表。表还没同步到、或前置缺席时返回空表（面板会显示成空档）。
     */
    public static List<Candidate> clientCandidates() {
        return candidates(true);
    }

    /**
     * 落盘侧：按序号在服务端那一份里现查。
     *
     * <p>序号来自客户端选中的那项，两侧顺序一致（见类注释），所以这里直接按下标取；越界返回 null，
     * 调用方给一句提示就完了——比写出一枚错的雕凿样板好。</p>
     */
    @Nullable
    public static Candidate serverCandidateAt(int index) {
        var all = candidates(false);
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    /**
     * 编一枚雕凿样板：它记住「把 input 雕成 output」。编码失败时返回 {@code null}。
     *
     * <p>失败必须是 null 而不是空栈：调用方把空栈当「编码成功但内容为空」处理，会去清编码槽、
     * 甚至会白扣一张网络空白样板。</p>
     */
    @Nullable
    public static ItemStack encode(Item input, Item output) {
        try {
            var stack = new ItemStack(RechiseledAE.chiseling_pattern);
            stack.set(EncodedChiselingPattern.COMPONENT_TYPE, new EncodedChiselingPattern(input, output));
            return stack;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Could not encode a chiseling pattern: {} -> {}", input, output, e);
            return null;
        }
    }

    private static List<Candidate> candidates(boolean client) {
        var result = new ArrayList<Candidate>();
        try {
            for (var recipe : ChiselingRecipeManager.get(client).getAllRecipes()) {
                for (var entry : recipe.entries()) {
                    var regular = entry.getAnyItem();
                    var connecting = entry.getAnyConnectingItem();
                    if (regular == null || connecting == null) {
                        continue;
                    }
                    var input = regular.item();
                    var output = connecting.item();
                    // 同一对可能被多个配方/条目重复给出，去重后再定序，否则两侧的「第 N 项」会在
                    // 上游数据略有差异时错位。
                    if (input != output && result.stream()
                            .noneMatch(c -> c.input() == input && c.output() == output)) {
                        result.add(new Candidate(input, output));
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Could not read Rechiseled's chiseling recipes", e);
            return List.of();
        }
        return result;
    }

    private ChiselingRecipes() {
    }
}

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
import com.supermartijn642.rechiseled.api.chiseling.ChiselingBlockShape;
import com.supermartijn642.rechiseled.api.chiseling.ChiselingRecipeManager;

/**
 * Rechiseled 雕凿配方的读取与雕凿样板的编码。这是本仓唯一直接引用 Rechiseled / RechiseledAE2 类的文件，
 * 单独隔开是为了让它们缺席时只有这个类加载失败——调用方在碰它之前先用 ModList 判前置。
 *
 * <p><b>候选怎么来的。</b>Rechiseled 的一条配方是一个「风格组」，组内每条 entry 最多 6 件物品：三个形状
 * （{@code BLOCK / STAIRS / SLAB}）各有普通态与连接材质态。转换只发生在<b>同组 × 同形状 × 同态</b>的子集里，
 * 普通态与连接态之间不能互转（普通梁不会雕成连接梁）——这一点与 Rechiseled 自己的雕凿机器口径一致。</p>
 *
 * <p>所以输入不是候选表的一部分，而是查询的入口：终端里那个切石输入槽放着什么，就用
 * {@link ChiselingRecipeManager#getRecipeForItem} 找到它所在的组、认出它落在哪个（形状, 态），再列出该组
 * 同一子集里的其余物品。槽里放普通台阶，列出的就全是普通台阶；放连接台阶，列出的就全是连接台阶。</p>
 *
 * <p><b>配方表要按侧取。</b>Rechiseled 自己维护两份：服务端那份在数据重载时填，客户端那份由它整份同步过来
 * （玩家加入时发一次，重载时再发）。CLIENT 副本在收到之前是空的，拿它会抛
 * {@code IllegalStateException}。上游把服务端表**按序**同步，两侧都从同一个输入算，所以候选顺序一致，
 * 可以只传序号。</p>
 */
public final class ChiselingRecipes {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.integration.rechiseledae");

    /** 一项候选：把输入槽里那个物品雕成 {@code output}。输入是查询入口，不进候选项。 */
    public record Candidate(Item output) {
    }

    /**
     * 面板侧：客户端那一份配方表，列出用 {@code input} 雕得出的产物；{@code input} 为 null 时返回空表。
     * 表还没同步到、或前置缺席时也返回空表（面板会显示成空档）。
     */
    public static List<Candidate> clientCandidates(@Nullable Item input) {
        return candidates(true, input);
    }

    /**
     * 落盘侧：按序号在服务端那一份里现查。
     *
     * <p>序号来自客户端选中的那项，两侧顺序一致（见类注释），所以这里直接按下标取；越界返回 null，
     * 调用方给一句提示就完了——比写出一枚错的雕凿样板好。</p>
     */
    @Nullable
    public static Candidate serverCandidateAt(int index, @Nullable Item input) {
        var all = candidates(false, input);
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

    private static List<Candidate> candidates(boolean client, @Nullable Item input) {
        if (input == null) {
            return List.of();
        }
        try {
            var recipe = ChiselingRecipeManager.get(client).getRecipeForItem(input);
            if (recipe == null) {
                // 这个物品不参与雕凿（没被任何配方收录）。
                return List.of();
            }

            // 先认出输入落在哪个（形状, 态）。
            ChiselingBlockShape shape = null;
            var connecting = false;
            for (var entry : recipe.entries()) {
                for (var candidateShape : ChiselingBlockShape.values()) {
                    var regular = entry.hasRegularItem(candidateShape) ? entry.getRegularItem(candidateShape) : null;
                    if (regular != null && regular.item() == input) {
                        shape = candidateShape;
                        break;
                    }
                    var connectingItem = entry.hasConnectingItem(candidateShape)
                            ? entry.getConnectingItem(candidateShape)
                            : null;
                    if (connectingItem != null && connectingItem.item() == input) {
                        shape = candidateShape;
                        connecting = true;
                        break;
                    }
                }
                if (shape != null) {
                    break;
                }
            }
            if (shape == null) {
                return List.of();
            }

            // 再列该组同一子集里的其余物品。上游数据里同一件物品可能被多个 entry 给出，去重后再定序，
            // 否则两侧的「第 N 项」会在数据略有差异时错位。
            var result = new ArrayList<Candidate>();
            for (var entry : recipe.entries()) {
                var item = connecting
                        ? (entry.hasConnectingItem(shape) ? entry.getConnectingItem(shape).item() : null)
                        : (entry.hasRegularItem(shape) ? entry.getRegularItem(shape).item() : null);
                if (item == null || item == input) {
                    // 输入自己也在候选集里，但「雕成自己」没有意义，跳过。
                    continue;
                }
                var duplicate = false;
                for (var existing : result) {
                    if (existing.output() == item) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    result.add(new Candidate(item));
                }
            }
            return result;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Could not read Rechiseled's chiseling recipes", e);
            return List.of();
        }
    }

    private ChiselingRecipes() {
    }
}

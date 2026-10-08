package io.github.lounode.ae2pattern.client.render;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Consumer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 左键钉住那几台驱动器之后，世界里给它们画的标记。
 *
 * <p>左键的用处是「把这一台在世界里显示出来」——它可能埋在墙里，也可能跟十几台长得一样的并排，光看表格认不出
 * 是哪一台。所以画两样：<b>一层半透明填充壳</b>（靠近时靠它认形状，一像素宽的线在近处看不出是什么）和
 * <b>一圈亮边 + 一条从眼睛过去的直线</b>（远处靠它们指方向）。都是虹色，色相跟着时间跑。</p>
 *
 * <p>两样都要<b>穿透</b>：被方块本身挡住的话，「它在墙后」这件事就白说了。穿透靠的是反复跟驱动确认过的一件事——
 * {@code RenderType} 的状态是在<b>刷出时</b>才施加的，而且不保证没人改写它（渲染优化模组会接管这一环）。所以这里
 * 不走 {@code BufferSource}：自己 {@code setupRenderState} → <b>把深度状态再显式说一遍</b> → 画 → {@code clearRenderState}。
 * 夹在中间那三句才是真正决定「穿不穿」的东西。</p>
 *
 * <p>右键那个「GUI 里的选中目标」在这里没有份：它的提示全在表格那圈黄色描边上，世界里不画——两个状态要是
 * 都在同一个方块上各画一圈，谁也说不清哪圈是什么意思。</p>
 *
 * <p>状态是客户端静态的，按维度分开记：「维度 + 坐标」才是完整身份。屏幕关掉时不清（这正是「钉住」的意思），
 * 再左键一次取消，或者退出世界时由 {@link #clearPinned()} 统一清掉——钉子不绑存档，留着会指到别人身上。</p>
 */
public final class DriveHighlight {

    private static final Logger LOGGER = LoggerFactory.getLogger("ae2_pattern_disk.drive_highlight");

    /** 虹色绕一圈的周期（毫秒）：太快像警灯，太慢看不出在变。 */
    private static final long RAINBOW_PERIOD = 4000L;
    /** 虹色的饱和度与明度：都用满，边框才好认。 */
    private static final float RAINBOW_SATURATION = 0.85f;
    private static final float RAINBOW_VALUE = 1.0f;

    private static final float ALPHA = 1.0f;
    /**
     * 填充壳的透明度：低了看不见，高了会把方块糊住、也把里头的元件盖掉。
     *
     * <p>六面都画（不剔除背面），所以视线穿过盒子时会叠两层，肉眼看到的浓度约为这个值的两倍。</p>
     */
    private static final float FILL_ALPHA = 0.22f;

    /**
     * 线宽：能生效多少算多少。
     *
     * <p>核心模式下驱动常把线宽夹回 1 像素，所以这条只是锦上添花——近处认形状靠填充壳，不是靠把线画粗。</p>
     */
    private static final float LINE_WIDTH = 3.0f;

    /** 边线往方块里收一点：贴边画会和方块表面那圈框线重合，看着像在抖。 */
    private static final double OUTLINE_DEFLATE = 0.004;
    /** 填充壳往外撑一点：同理，贴在方块表面上会与它的面共面打架。 */
    private static final double FILL_INFLATE = 0.002;

    /** 连线的几条微偏移：线宽不保证，叠出来才看得清。 */
    private static final double[][] LINK_SHIFTS = { { 0, 0, 0 }, { 0.012, 0.012, 0 }, { 0.024, 0, 0.012 } };
    /** 连线的起点离眼睛多远：贴太近会糊在视野边上。 */
    private static final double LINK_START = 0.6;

    /** 顶点暂存区：一台约 1 KiB（壳 384 B + 边 480 B + 连线 120 B），16 KiB 够十几台。 */
    private static final int BUFFER_SIZE = 16384;
    /** 深度比较恒通过（GL_ALWAYS）：穿透就是靠这一条，别的都可以谈，这条不能让。 */
    private static final int DEPTH_ALWAYS = 519;
    /** 深度比较恢复成默认的「小于等于」（GL_LEQUAL）。 */
    private static final int DEPTH_LEQUAL = 515;

    /**
     * 穿透的线框类型。
     *
     * <p><b>不能用 {@code RenderType.lines()}</b>：它自带 {@code LEQUAL_DEPTH_TEST}，框会被方块本身挡住。</p>
     */
    private static final RenderType HIGHLIGHT_LINES = RenderType.create(
            "ae2_pattern_disk_highlight_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(LINE_WIDTH)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .createCompositeState(false));

    /** 穿透的半透明填充壳。同样把 {@code NO_DEPTH_TEST} 写进自己的状态。 */
    private static final RenderType HIGHLIGHT_FILL = RenderType.create(
            "ae2_pattern_disk_highlight_fill",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .createCompositeState(false));

    /** 顶点暂存区，跨帧复用（每帧画完就把里面交出去，不留东西）。 */
    private static final ByteBufferBuilder SCRATCH = new ByteBufferBuilder(BUFFER_SIZE);

    /** 诊断标记：深度真值只打一次，几何写坏也只报一次（这类问题一发生就是每帧都发生）。 */
    private static boolean depthLogged;
    private static boolean brokenLogged;

    /** 钉住的那些，按维度分开记：坐标在别的维度上指不到任何东西。 */
    private static final Map<ResourceLocation, Set<BlockPos>> PINNED = new LinkedHashMap<>();

    private DriveHighlight() {}

    /**
     * 钉住 / 取消钉住一台（左键）。
     *
     * @return true 表示这一下刚把它钉住，false 表示这一下取消了它
     */
    public static boolean togglePinned(ResourceLocation dimensionId, BlockPos pos) {
        Set<BlockPos> pins = PINNED.computeIfAbsent(dimensionId, key -> new LinkedHashSet<>());
        BlockPos key = pos.immutable();
        if (pins.remove(key)) {
            if (pins.isEmpty()) {
                PINNED.remove(dimensionId);
            }
            return false;
        }
        pins.add(key);
        return true;
    }

    /** 这一台钉着没有（表格里的描边要跟世界里那圈框同一个口径）。 */
    public static boolean isPinned(ResourceLocation dimensionId, BlockPos pos) {
        Set<BlockPos> pins = PINNED.get(dimensionId);
        return pins != null && pins.contains(pos);
    }

    /** 清掉所有钉子：退出世界时调（见 {@code AE2PatternDiskClient}）。 */
    public static void clearPinned() {
        PINNED.clear();
    }

    /** 当前这一帧的虹色（三通道各 0~1）；同一帧里填充、边框与直线取同一个色，看着才是一套。 */
    private static float[] rainbow() {
        float hue = (Util.getMillis() % RAINBOW_PERIOD) / (float) RAINBOW_PERIOD;
        int rgb = Mth.hsvToRgb(hue, RAINBOW_SATURATION, RAINBOW_VALUE);
        return new float[] {
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f };
    }

    /**
     * 同一份虹色的 ARGB 形式（{@code alpha} 是 0~255 那一档）。
     *
     * <p>表格里那圈描边走这个入口：周期与 HSV 参数只此一份，两边不会各自改一处就把色相对不上。</p>
     */
    public static int rainbowArgb(int alpha) {
        float[] rgb = rainbow();
        return (alpha << 24) | (Math.round(rgb[0] * 255f) << 16)
                | (Math.round(rgb[1] * 255f) << 8) | Math.round(rgb[2] * 255f);
    }

    /**
     * 世界渲染阶段：把钉住的每一台罩一层半透明壳、描一圈边，并从眼睛拉一条线过去。
     *
     * <p>挑半透明层画：这一层已经把实体与方块都画完，再往后就没有别的东西会盖上来。</p>
     *
     * <p><b>用事件给的 pose，把原点平移到相机上</b>（JDT 与 ImmersiveEngineering 的工作范围绘制都是这么摆的）：
     * 那个 pose 里相机旋转已经应用、平移还没有，挪完就能直接写世界坐标。</p>
     *
     * <p>不要把 {@code getModelViewMatrix()} 叠到一个空 pose 上：那个矩阵是<b>相机旋转</b>（不含相机平移），
     * 拿它配世界坐标等于假设「相机在世界原点」，整个框会被挪走一个相机位移，多数位置压根不在视野里。</p>
     */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Set<BlockPos> pinned = PINNED.get(minecraft.level.dimension().location());
        if (pinned == null || pinned.isEmpty()) {
            return; // 当前维度没有钉子：什么都不用画
        }

        // 用事件自己的相机：它与这个 pose 同源（主相机在特殊渲染路径下未必是同一个）。
        Vec3 camera = event.getCamera().getPosition();
        float[] color = rainbow();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            // 先壳后边：两批各自设状态、各自画，叠放顺序就是这里的先后。
            flush(HIGHLIGHT_FILL, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR, consumer -> {
                for (BlockPos target : pinned) {
                    renderFilledBox(poseStack, consumer, new AABB(target).inflate(FILL_INFLATE), color, FILL_ALPHA);
                }
            });
            flush(HIGHLIGHT_LINES, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    consumer -> {
                        for (BlockPos target : pinned) {
                            LevelRenderer.renderLineBox(poseStack, consumer, new AABB(target).deflate(OUTLINE_DEFLATE),
                                    color[0], color[1], color[2], ALPHA);
                            drawLink(consumer, poseStack, camera, target, color);
                        }
                    });
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * 把一批几何画出去：自己 setup → 自己再设一遍深度 → 画 → clear。
     *
     * <p>不走 {@code BufferSource} 有两个原因：一是它的 buffer 生命周期不由我们掌握（写一个已被结束的 buffer 会
     * 抛 {@code Not building}）；二是「穿透」这件事必须落在 {@code setupRenderState} 之后、绘制之前——中间那一小段
     * 只有自己走才插得进去，而它正是「框被方块挡住」那条 bug 的最后一道保险。</p>
     */
    private static void flush(RenderType type, VertexFormat.Mode mode, VertexFormat format,
            Consumer<VertexConsumer> writer) {
        MeshData mesh;
        try {
            BufferBuilder builder = new BufferBuilder(SCRATCH, mode, format);
            writer.accept(builder);
            mesh = builder.build();
        } catch (RuntimeException broken) {
            logBrokenOnce(broken);
            return;
        }
        if (mesh == null) {
            return;
        }
        try (mesh) {
            boolean setup = false;
            try {
                type.setupRenderState();
                setup = true;
                // 穿透：真关深度测试，再把比较函数也钉成「恒通过」。两句一起上是有意的——有些渲染优化模组
                // 按「布尔开关 + 缓存的比较函数」重新落状态，只设函数会被悄悄拨回去；关掉开关则必须有人
                // 显式打开才恢复，赖不掉。不写深度是配套的：穿透的画不该把后面的实体/粒子挖空。
                RenderSystem.disableDepthTest();
                RenderSystem.depthFunc(DEPTH_ALWAYS);
                RenderSystem.depthMask(false);
                logDepthOnce();
                BufferUploader.drawWithShader(mesh);
            } finally {
                if (setup) {
                    // clearRenderState 按各 shard 的默认值恢复（深度测试会重新打开）；即便 setup 抛在半路，
                    // 这里也得把全局状态还回去。
                    type.clearRenderState();
                }
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(DEPTH_LEQUAL);
                RenderSystem.depthMask(true);
            }
        }
    }

    /** 诊断用：第一次绘制时把此刻的深度状态打一行——它直接回答「我们的穿透设置有沒有落到 GL」。 */
    private static void logDepthOnce() {
        if (depthLogged) {
            return;
        }
        depthLogged = true;
        LOGGER.info("穿透诊断：depthTest={} func={} writeMask={}",
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
                GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
    }

    /** 几何写坏只报一次：它一旦发生就是每帧都发生，堆栈打一次足够。 */
    private static void logBrokenOnce(RuntimeException broken) {
        if (brokenLogged) {
            return;
        }
        brokenLogged = true;
        LOGGER.warn("跳过一批驱动器高亮：几何没写完整（后续不再重复报）", broken);
    }

    /**
     * 画一个半透明的盒壳（六个面；面朝哪边都画，所以靠近时从里往外看也看得见）。
     *
     * <p>顶点是手写的：1.21 里没有现成的「填充盒」画法，{@code LevelRenderer} 只有画线框的那一个。</p>
     */
    private static void renderFilledBox(PoseStack poseStack, VertexConsumer consumer, AABB box, float[] color,
            float alpha) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;
        // 前后两面（-Z / +Z）
        quad(poseStack, consumer, color, alpha, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        quad(poseStack, consumer, color, alpha, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1);
        // 左右两面（-X / +X）
        quad(poseStack, consumer, color, alpha, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1);
        quad(poseStack, consumer, color, alpha, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        // 上下两面（-Y / +Y）
        quad(poseStack, consumer, color, alpha, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
        quad(poseStack, consumer, color, alpha, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    /** 一个四边形：四个顶点按顺序写进去（不剔除背面，所以绕序无所谓）。 */
    private static void quad(PoseStack poseStack, VertexConsumer consumer, float[] color, float alpha,
            float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3) {
        var pose = poseStack.last();
        consumer.addVertex(pose, x0, y0, z0).setColor(color[0], color[1], color[2], alpha);
        consumer.addVertex(pose, x1, y1, z1).setColor(color[0], color[1], color[2], alpha);
        consumer.addVertex(pose, x2, y2, z2).setColor(color[0], color[1], color[2], alpha);
        consumer.addVertex(pose, x3, y3, z3).setColor(color[0], color[1], color[2], alpha);
    }

    /** 眼睛到方块中心的一条线（粗靠三条微偏移的线叠出来）。 */
    private static void drawLink(VertexConsumer consumer, PoseStack poseStack, Vec3 eye, BlockPos target,
            float[] color) {
        double cx = target.getX() + 0.5;
        double cy = target.getY() + 0.5;
        double cz = target.getZ() + 0.5;
        // 起点从眼睛出发：eye 是世界坐标，与上面的 pose（原点在相机）同一套。
        Vec3 direction = new Vec3(cx - eye.x, cy - eye.y, cz - eye.z);
        if (direction.lengthSqr() < 1.0e-6) {
            return; // 人就站在它里面：画不出方向，也没有画的意义
        }
        Vec3 start = eye.add(direction.normalize().scale(LINK_START));
        for (double[] shift : LINK_SHIFTS) {
            consumer.addVertex(poseStack.last(), (float) start.x, (float) start.y, (float) start.z)
                    .setColor(color[0], color[1], color[2], ALPHA)
                    .setNormal(poseStack.last(), 0f, 1f, 0f);
            consumer.addVertex(poseStack.last(), (float) (cx + shift[0]), (float) (cy + shift[1]),
                    (float) (cz + shift[2]))
                    .setColor(color[0], color[1], color[2], ALPHA)
                    .setNormal(poseStack.last(), 0f, 1f, 0f);
        }
    }
}

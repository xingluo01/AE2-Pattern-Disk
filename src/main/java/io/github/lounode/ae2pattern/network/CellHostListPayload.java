package io.github.lounode.ae2pattern.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.core.network.ClientboundPacket;

import io.github.lounode.ae2pattern.common.menu.CellManagementTermMenu;

/**
 * 元件管理终端的驱动器清单：每台驱动器一条，带它里面的存储元件与（ECO 系才有的）存储优先级。
 *
 * <p>与样板终端那条 {@code DiskHostListPayload} 分开，是因为载荷不同：那边要的是「盘里有多少样板」，
 * 这边要的是「这台驱动器里插了哪些元件、它的存储优先级是多少」。合成一条包就得两头都带用不上的字段。</p>
 *
 * <p><b>{@code priority} 用字符串而不是数字</b>：它有三种含义不同的状态——一个有符号整数、没有这个概念
 * （AE2 本体与 EAE 的驱动器走自己的菜单设优先级）、以及「这个版本问不出来」（ECO 家族靠方法名探，
 * 探不到就是这种）。三种都要能在界面上如实区分，用 int 就得靠 -1 之类的哨兵，那会把「真的是 -1」
 * 和「问不出来」混成同一个东西。AE2/EAE 那两类由扫描器直接给出真值，不需要哨兵。</p>
 */
public record CellHostListPayload(List<HostGroup> groups) implements ClientboundPacket {

    public static final Type<CellHostListPayload> TYPE = new Type<>(
            ResourceLocation.parse("ae2_pattern_disk:cell_host_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CellHostListPayload> STREAM_CODEC = StreamCodec
            .ofMember(CellHostListPayload::write, CellHostListPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(RegistryFriendlyByteBuf data) {
        HostGroup.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(data, groups);
    }

    public static CellHostListPayload decode(RegistryFriendlyByteBuf data) {
        return new CellHostListPayload(HostGroup.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(data));
    }

    /** 客户端：把清单交给正在开着的元件管理终端菜单，屏幕每帧读它。 */
    @Override
    public void handleOnClient(Player player) {
        if (player.containerMenu instanceof CellManagementTermMenu menu) {
            menu.setDriveList(groups);
        }
    }

    /**
     * 表格里的一组驱动器：同一种方块 + 同一个优先级算一组（组里具体是哪几台由每一格自带的 {@code hostKey} 说明）。
     *
     * @param key      组的身份键（类型 + 优先级）：标题栏与分组用它
     * @param name     标题栏文字（这一组是哪一种驱动器）
     * @param icon     驱动器方块物品图标（标题栏与每台驱动器的首格都画它）
     * @param priority 存储优先级显示串；没有这个概念的来源给空串
     * @param cells    这一组里所有驱动器的格位（稠密，同一台的格连续排在一起；每格自带宿主）
     */
    public record HostGroup(String key, String name, ItemStack icon, String priority, List<CellSlot> cells) {

        public static final StreamCodec<RegistryFriendlyByteBuf, HostGroup> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HostGroup::key,
                ByteBufCodecs.STRING_UTF8, HostGroup::name,
                ItemStack.OPTIONAL_STREAM_CODEC, HostGroup::icon,
                ByteBufCodecs.STRING_UTF8, HostGroup::priority,
                CellSlot.STREAM_CODEC.apply(ByteBufCodecs.list()), HostGroup::cells,
                HostGroup::new);
    }

    /**
     * 一整格的存储元件，连同它下方两根占用条的填充度与「这一格是谁的」。
     *
     * <p><b>表是稠密的</b>：每台驱动器的每个物理格都占一条（空格也在），这样列表下标就是物理格位，客户端
     * 点第几列就是第几格。表若只装「有元件的格」，一旦驱动器上有间隔，显示列号与物理格位就会错开，
     * 取放会落到另一格上。</p>
     *
     * @param typeFill 存储种类占用 0..1（已用种类 / 该元件允许的种类上限）
     * @param byteFill 存储数量占用 0..1（已用字节 / 该元件的字节上限）
     * @param hostKey  这一格的宿主身份键（取放时用它找回真正的方块实体与它的格位）：AE2/EAE 是那台驱动器；
     *                 ECO 家族是承载这一格的那台元件座——一个主机下的每一格来自不同的元件座，
     *                 拿首格键（主机）代替会永远找不到能写的那一台。
     * @param hostSlot 这一格在它自己宿主上的格位（单格元件座恒为 0）
     * @param leaderKey 这一格归属的「首格单」键：AE2/EAE 驱动器就是它自己的宿主键（所以一台占一个首列），
     *                 ECO 家族则是它那个主机的键（所以一个主机带最多 33 个存储矩阵，只占一个首格）。
     *                 客户端按「这个键一变就插一个首格」排表，选中、世界高亮与存入也按这个键找目标。
     */
    public record CellSlot(ItemStack stack, float typeFill, float byteFill, String hostKey, int hostSlot,
            String leaderKey) {

        public static final StreamCodec<RegistryFriendlyByteBuf, CellSlot> STREAM_CODEC = StreamCodec.composite(
                ItemStack.OPTIONAL_STREAM_CODEC, CellSlot::stack,
                ByteBufCodecs.FLOAT, CellSlot::typeFill,
                ByteBufCodecs.FLOAT, CellSlot::byteFill,
                ByteBufCodecs.STRING_UTF8, CellSlot::hostKey,
                ByteBufCodecs.VAR_INT, CellSlot::hostSlot,
                ByteBufCodecs.STRING_UTF8, CellSlot::leaderKey,
                CellSlot::new);
    }
}

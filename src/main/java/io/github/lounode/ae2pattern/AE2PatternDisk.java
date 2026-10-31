package io.github.lounode.ae2pattern;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.RegisterEvent;

import io.github.lounode.ae2pattern.common.AEPatternDiskCapabilities;
import io.github.lounode.ae2pattern.integration.omnisequence.OmniSequenceIntegration;
import io.github.lounode.ae2pattern.integration.omnisequence.OmniSequenceSoftDep;
import io.github.lounode.ae2pattern.network.AssemblerAnimationPayload;
import io.github.lounode.ae2pattern.network.DiskListPayload;
import io.github.lounode.ae2pattern.network.DiskContentPayload;
import io.github.lounode.ae2pattern.network.CellHostListPayload;
import io.github.lounode.ae2pattern.network.CellHostUpdatePayload;
import io.github.lounode.ae2pattern.network.CellNoticePayload;
import io.github.lounode.ae2pattern.network.DiskHostListPayload;
import io.github.lounode.ae2pattern.network.VisibleDisksPayload;
import io.github.lounode.ae2pattern.common.menu.PatternDiskManagementTermMenu;
import io.github.lounode.ae2pattern.config.AEPDCommonConfig;
import io.github.lounode.ae2pattern.config.AEPDConfig;

import io.github.lounode.ae2pattern.common.block.entity.PatternDiskAssemblerBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.MeteoritePatternProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.common.block.entity.PatternTransfererBlockEntity;
import io.github.lounode.ae2pattern.integration.ae2cs.AecsSoftDep;
import io.github.lounode.ae2pattern.integration.ae2cs.resonating.ResonatingPatternDiskProviderBlockEntity;
import io.github.lounode.ae2pattern.integration.ae2cs.resonating.ResonatingPatternDiskProviderRegistrations;
import io.github.lounode.ae2pattern.integration.ae2lt.OverloadPatterns;
import io.github.lounode.ae2pattern.integration.advancedae.AdvancedPatternEncoder;
import io.github.lounode.ae2pattern.integration.appflux.AppFluxInductionCard;
import io.github.lounode.ae2pattern.integration.rechiseledae.ChiselingPatternEncoder;

/**
 * Entry point for the AE2 Pattern Disk addon.
 */
@Mod(AE2PatternDisk.MOD_ID)
public class AE2PatternDisk {

    public static final String MOD_ID = "ae2_pattern_disk";

    public AE2PatternDisk(IEventBus modBus, ModContainer modContainer) {
        // 附加排序的层级表是纯客户端的视图设置：注册成 CLIENT，服务端不加载这份文件，也不随网络同步。
        // 同一份文件里还有聊天栏消息开关（客户端显示的那半）。
        modContainer.registerConfig(ModConfig.Type.CLIENT, AEPDConfig.CLIENT_SPEC);
        // 服务端直发的聊天栏消息开关：那些消息由服务端当场写进聊天栏，客户端配置拦不住它们，开关只能在这一侧。
        // 用 COMMON 而不是 SERVER：这是一份跟着模组走的全局偏好，不该每开一个存档各配一遍。
        modContainer.registerConfig(ModConfig.Type.COMMON, AEPDCommonConfig.COMMON_SPEC);

        // Registration entry points
        AEPatternRegistries.register(modBus);

        // 自装配样板磁盘供应器：整套注册以 AE2 Crystal Science 在场为前提，不在场时这台设备在游戏里不存在。
        // 判定放在这里而不是静态字段上，是因为 ModList 要到 mod 构造期才可读。
        if (AecsSoftDep.isLoaded()) {
            MeteoritePatternProviderRegistrations.register(modBus);
            ResonatingPatternDiskProviderRegistrations.register(modBus);
        }

        // 万象构序：只往它的两个适配器注册表里登记批处理装配室，不注册任何方块物品，所以不在场时整段都不
        // 执行——集成类本身的加载也以在场为前提（它编译期引用上游类型，缺席时加载会 NoClassDefFoundError）。
        // 同样只能在这里判定：ModList 要到 mod 构造期才可读。
        if (OmniSequenceSoftDep.isLoaded()) {
            OmniSequenceIntegration.register();
        }

        modBus.addListener(this::associateBlockEntities);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::registerCapabilities);
        modBus.addListener(this::registerPartCapabilities);
        modBus.addListener(this::registerPayloads);

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) -> {
                    if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                        io.github.lounode.ae2pattern.common.part.AbstractDiskPatternProviderPart
                                .pumpLogic(level);
                    }
                });

        // A reload can change what a stored pattern decodes to without the items changing, so the
        // item-keyed decode memo has to be dropped. Nothing else observes reloads, and the bus's own
        // decode caches are rebuilt from slots on the next change.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.AddReloadListenerEvent event) ->
                        io.github.lounode.ae2pattern.api.PatternClassifier.invalidateDecodedCache());
    }

    /**
     * Registers AE2 capabilities that AE2 itself only binds for its own BlockEntityTypes.
     * Our machines use new BlockEntityTypes, so we must re-register the same capabilities.
     * Without {@code IN_WORLD_GRID_NODE_HOST} the grid cannot reach our nodes (connection lost on
     * world reload / channel drop), and without {@code GENERIC_INTERNAL_INV}/{@code ItemHandler}
     * the molecular assembler cannot push crafts back into our return inventory.
     */
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // 无线终端的 FE 能力（单独成类，那段注释要讲清 AE2 自家的 FE 桥怎么复用）。
        // 这里直接调而不用 @EventBusSubscriber：该注解的 bus 默认是 GAME，而本事件是模组总线事件，
        // 不显式写 bus = MOD 就会挂错总线——不报错、不警告，只表现为终端充不上电（已踩过一次）。
        AEPatternDiskCapabilities.registerItemEnergy(event);
        // Grid node host: lets AE2 cables / grid find our in-world nodes (fixes lost connection).
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternRegistries.BE_TRANSFERER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternRegistries.BE_PROVIDER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);

        // Generic internal inventory / item handler: exposes the return inventory so the molecular
        // assembler (and other AE machines) can push crafted results back into the provider.
        event.registerBlockEntity(
                appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                AEPatternRegistries.BE_PROVIDER.get(),
                (be, dir) -> PatternDiskProviderBlockEntity.class.cast(be).getLogic().getReturnInv());

        // 标记式供应器：与上面两条同一套后果——漏了不报错，只是这台机器根本不上网、产物回不来。
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternRegistries.BE_MARKED_PROVIDER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);
        event.registerBlockEntity(
                appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                AEPatternRegistries.BE_MARKED_PROVIDER.get(),
                (be, dir) -> io.github.lounode.ae2pattern.common.block.entity.MarkedPatternDiskProviderBlockEntity
                        .class.cast(be).getLogic().getReturnInv());

        // 自装配样板磁盘供应器：它同样要能从网上被找到，并把返回仓露给分子装配台回送产物。
        if (MeteoritePatternProviderRegistrations.isRegistered()) {
            event.registerBlockEntity(
                    appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                    MeteoritePatternProviderRegistrations.BE.get(),
                    (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);
            event.registerBlockEntity(
                    appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                    MeteoritePatternProviderRegistrations.BE.get(),
                    (be, dir) -> MeteoritePatternProviderBlockEntity.class.cast(be).getLogic().getReturnInv());
        }

        // 谐振样板磁盘供应器：与自装配那台同一套能力面，只在 AECS 带谐振供应器时存在。
        if (ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            event.registerBlockEntity(
                    appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                    ResonatingPatternDiskProviderRegistrations.BE.get(),
                    (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);
            event.registerBlockEntity(
                    appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                    ResonatingPatternDiskProviderRegistrations.BE.get(),
                    (be, dir) -> ResonatingPatternDiskProviderBlockEntity.class.cast(be).getLogic().getReturnInv());
        }

        // Crafting machine: exposes the assembler so any AE2 provider can push patterns to it.
        event.registerBlockEntity(
                appeng.api.AECapabilities.CRAFTING_MACHINE,
                AEPatternRegistries.BE_ASSEMBLER.get(),
                (be, dir) -> (appeng.api.implementations.blockentities.ICraftingMachine) be);

        // Grid node host for the assembler (needed so cables can reach its node).
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternRegistries.BE_ASSEMBLER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);

        // Item handler for the assembler's output slots: pipes, hoppers and AE2 storage buses can
        // recover a product that neither the neighbouring return node nor the ME network would take.
        // AE2 binds the same capability for its own molecular assembler, whose crafting-grid filter
        // likewise only allows extraction from the output slot.
        event.registerBlockEntity(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                AEPatternRegistries.BE_ASSEMBLER.get(),
                (be, dir) -> PatternDiskAssemblerBlockEntity.class.cast(be)
                        .getExposedOutputInventory().toItemHandler());

        // Batch assembler: grid node host + crafting machine (receives provider-pushed patterns).
        // NOTE: deliberately no ME_STORAGE / IStorageProvider exposure - its cell slots must stay private.
        event.registerBlockEntity(
                appeng.api.AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AEPatternRegistries.BE_BATCH_ASSEMBLER.get(),
                (be, dir) -> (appeng.api.networking.IInWorldGridNodeHost) be);
        event.registerBlockEntity(
                appeng.api.AECapabilities.CRAFTING_MACHINE,
                AEPatternRegistries.BE_BATCH_ASSEMBLER.get(),
                (be, dir) -> (appeng.api.implementations.blockentities.ICraftingMachine) be);
    }

    /**
     * Parts are not covered by {@link RegisterCapabilitiesEvent}: AE2 uses a separate event for them, so
     * the panel form of the provider has to register its return inventory here, exactly like the block
     * form does above (that is the channel a molecular assembler pushes crafted results back through).
     */
    private void registerPartCapabilities(appeng.api.parts.RegisterPartCapabilitiesEvent event) {
        // 两个变体必须各注册一次，不能按基类合一份：AE2 在 RegisterPartCapabilitiesEvent#register 里明确只收具体类
        // （partClass 是接口或抽象类时直接抛 IllegalArgumentException，见 19.2.17 的 :91-94）。这不是本模组偷懒，
        // 是上游 API 的形状。新增第三个变体时同样在这里补一段，并把类名加进 DiskProviderContractTest 的 HOSTS。
        event.addHostType(appeng.core.definitions.AEBlockEntities.CABLE_BUS.get());
        event.register(
                appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                (part, context) -> ((io.github.lounode.ae2pattern.common.part.PatternDiskProviderPart) part)
                        .getLogic().getReturnInv(),
                io.github.lounode.ae2pattern.common.part.PatternDiskProviderPart.class);

        // 自装配供应器的面板形态同理。它只在 AE2CS 在场时存在，类型名也就只在这里碰——未装时这一段整个不执行，
        // 那个类不会被加载。
        if (MeteoritePatternProviderRegistrations.isRegistered()) {
            event.register(
                    appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                    (part, context) -> ((io.github.lounode.ae2pattern.common.part.MeteoritePatternProviderPart) part)
                            .getLogic().getReturnInv(),
                    io.github.lounode.ae2pattern.common.part.MeteoritePatternProviderPart.class);
        }

        // 谐振样板磁盘供应器的面板形态：同样把返回仓露给分子装配台。
        if (ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            event.register(
                    appeng.api.AECapabilities.GENERIC_INTERNAL_INV,
                    (part, context) -> ((io.github.lounode.ae2pattern.integration.ae2cs.resonating.ResonatingPatternDiskProviderPart) part)
                            .getLogic().getReturnInv(),
                    io.github.lounode.ae2pattern.integration.ae2cs.resonating.ResonatingPatternDiskProviderPart.class);
        }
    }

    /**
     * 给方块实体挂服务端 ticker。
     *
     * <p>为什么要有这个 helper：AE2 的 {@code AEBaseEntityBlock#setBlockEntity} 只是把 ticker 存下来，
     * <b>不会</b>替实现了 {@link appeng.blockentity.ServerTickingBlockEntity} 的实体自动补一个；以前三处
     * 注册里有两处直接传了 {@code null}，结果方块形态的供应器从来没有被 tick 过（抽取与发配倍增后的发配全部静默
     * 失效），只有面板形态正常。泛型同时约束「AE 方块实体」与「实现 ServerTickingBlockEntity」，漏掉接口
     * 会编译失败；{@link #verifyTickingBlockEntities()} 再兜一层运行期自检。</p>
     *
     * <p>只挂 <b>server</b> ticker（client 传 null）：本模组没有需要 {@code ClientTickingBlockEntity}
     * 的方块，将来要客户端 tick 请另写一个 helper，别在这里加开关。</p>
     */
    private static <T extends appeng.blockentity.AEBaseBlockEntity & appeng.blockentity.ServerTickingBlockEntity>
            void attachTickingBlockEntity(
                    appeng.block.AEBaseEntityBlock<T> block,
                    Class<T> blockEntityClass,
                    net.minecraft.world.level.block.entity.BlockEntityType<T> blockEntityType) {
        block.setBlockEntity(blockEntityClass, blockEntityType, null,
                (lvl, pos, state, be) -> be.serverTick());
        TICKING_BLOCKS.add(block);
    }

    /** 已经挂过 ticker 的方块；{@link #verifyTickingBlockEntities()} 用它做启动期自检。 */
    private static final java.util.Set<appeng.block.AEBaseEntityBlock<?>> TICKING_BLOCKS =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private void associateBlockEntities(RegisterEvent event) {
        if (event.getRegistryKey() == net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE) {
            attachTickingBlockEntity(
                    AEPatternRegistries.BLOCK_TRANSFERER.get(),
                    PatternTransfererBlockEntity.class,
                    AEPatternRegistries.BE_TRANSFERER.get());

            attachTickingBlockEntity(
                    AEPatternRegistries.BLOCK_PROVIDER.get(),
                    PatternDiskProviderBlockEntity.class,
                    AEPatternRegistries.BE_PROVIDER.get());

            // 标记式供应器：同样需要 server ticker（网格重扫与攒批外发都靠它驱动）。
            attachTickingBlockEntity(
                    AEPatternRegistries.BLOCK_MARKED_PROVIDER.get(),
                    io.github.lounode.ae2pattern.common.block.entity.MarkedPatternDiskProviderBlockEntity.class,
                    AEPatternRegistries.BE_MARKED_PROVIDER.get());

            AEPatternRegistries.BLOCK_ASSEMBLER.get().setBlockEntity(
                    PatternDiskAssemblerBlockEntity.class,
                    AEPatternRegistries.BE_ASSEMBLER.get(),
                    null,
                    null);

            AEPatternRegistries.BLOCK_BATCH_ASSEMBLER.get().setBlockEntity(
                    io.github.lounode.ae2pattern.common.block.entity.BatchAssemblerBlockEntity.class,
                    AEPatternRegistries.BE_BATCH_ASSEMBLER.get(),
                    null,
                    null);

            if (MeteoritePatternProviderRegistrations.isRegistered()) {
                attachTickingBlockEntity(
                        MeteoritePatternProviderRegistrations.BLOCK.get(),
                        MeteoritePatternProviderBlockEntity.class,
                        MeteoritePatternProviderRegistrations.BE.get());
            }

            if (ResonatingPatternDiskProviderRegistrations.isRegistered()) {
                attachTickingBlockEntity(
                        ResonatingPatternDiskProviderRegistrations.BLOCK.get(),
                        ResonatingPatternDiskProviderBlockEntity.class,
                        ResonatingPatternDiskProviderRegistrations.BE.get());
            }
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MOD_ID);
        registrar.playToClient(
                AssemblerAnimationPayload.TYPE,
                AssemblerAnimationPayload.STREAM_CODEC,
                (payload, context) -> {
                    var player = context.player();
                    if (player != null) {
                        payload.handleOnClient(player);
                    }
                });
        registrar.playToClient(
                DiskListPayload.TYPE,
                DiskListPayload.STREAM_CODEC,
                (payload, context) -> payload.handleOnClient(context));
        // 管理终端的表格：分组清单与「按需的盘内内容」。内容走单独一条包，因为一张盘最多 1024 张样板，
        // 不能跟着列表一起发（见 DiskContentPayload 的注释）。
        registrar.playToClient(
                DiskHostListPayload.TYPE,
                DiskHostListPayload.STREAM_CODEC,
                (payload, context) -> payload.handleOnClient(context));
        registrar.playToClient(
                DiskContentPayload.TYPE,
                DiskContentPayload.STREAM_CODEC,
                (payload, context) -> payload.handleOnClient(context));
        // 客户端上报「表格当前显示哪些盘」；服务端不猜视口，只按它答内容。
        registrar.playToServer(
                VisibleDisksPayload.TYPE,
                VisibleDisksPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    var player = context.player();
                    if (player != null && player.containerMenu instanceof PatternDiskManagementTermMenu menu) {
                        payload.handleOnServer(menu);
                    }
                }));
        // 元件管理终端：首次与拓扑变化发全表，其余只发发生变化的格位。
        registrar.playToClient(
                CellHostListPayload.TYPE,
                CellHostListPayload.STREAM_CODEC,
                (payload, context) -> payload.handleOnClient(context));
        registrar.playToClient(
                CellHostUpdatePayload.TYPE,
                CellHostUpdatePayload.STREAM_CODEC,
                (payload, context) -> payload.handleOnClient(context));
        // 同终端的一句话反馈：元件格取放的拒绝原因与结果，玩家要看得见。
        registrar.playToClient(
                CellNoticePayload.TYPE,
                CellNoticePayload.STREAM_CODEC,
                (payload, context) -> payload.handleOnClient(context));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(this::registerUpgrades);
        event.enqueueWork(AE2PatternDisk::verifyTickingBlockEntities);
    }

    /**
     * 启动期自检：必须 tick 的方块都得挂上 ticker。
     *
     * <p>这类漏挂不会报错、只会「功能静默不工作」：供应器不 tick 就等于产物抽取与发配倍增后的发配全废，
     * 玩家从现象上根本看不出是注册漏了。宁可启动时硬失败。</p>
     */
    private static void verifyTickingBlockEntities() {
        var required = new java.util.ArrayList<Object>();
        required.add(AEPatternRegistries.BLOCK_PROVIDER.get());
        required.add(AEPatternRegistries.BLOCK_MARKED_PROVIDER.get());
        required.add(AEPatternRegistries.BLOCK_TRANSFERER.get());
        if (MeteoritePatternProviderRegistrations.isRegistered()) {
            required.add(MeteoritePatternProviderRegistrations.BLOCK.get());
        }
        if (ResonatingPatternDiskProviderRegistrations.isRegistered()) {
            required.add(ResonatingPatternDiskProviderRegistrations.BLOCK.get());
        }
        for (var block : required) {
            if (!TICKING_BLOCKS.contains(block)) {
                // 先单独打一条：FML 会把 enqueueWork 里的异常包成「Failed to complete deferred work」，
                // 原始信息在崩溃摘要里往往看不出来。
                org.slf4j.LoggerFactory.getLogger(AE2PatternDisk.class).error(
                        "Block {} was registered without a server ticker; its serverTick() would never run",
                        block);
                throw new IllegalStateException("Block entity of " + block
                        + " was registered without a server ticker; its serverTick() would never run");
            }
        }
    }

    private void registerUpgrades() {
        // 无线信号增幅器**不登记**：它管的是无线访问点的信号范围，放进终端的升级槽没有意义
        // （AE2WTLib 也没给自家终端挂它，同一个理由）。
        //
        // 两个无线终端的升级登记（量子桥卡、能源卡上限）与无线接入点的可链接登记都在
        // WirelessTerminalRegistrations —— 那是 AE2WTLib 侧的整套登记，见其类注释。

        var speedCard = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:speed_card"));
        // 速度卡按 AE2 的规矩挂到本模组自己的机器上（上限与设备并行度对齐）。取不到速度卡就这台设备
        // 不额外挂，而不是把初始化带下去。
        if (speedCard != null) {
            appeng.api.upgrades.Upgrades.add(speedCard, AEPatternRegistries.BLOCK_TRANSFERER.get(), 4);
            appeng.api.upgrades.Upgrades.add(speedCard, AEPatternRegistries.BLOCK_ASSEMBLER.get(), 5);
            appeng.api.upgrades.Upgrades.add(speedCard, AEPatternRegistries.BLOCK_BATCH_ASSEMBLER.get(), 4);
        }

        // 元件管理终端的 6 个升级槽：不登记的话卡片拖进去会被弹回（AE2 的 Upgrades.getMaxInstallable 对未登记
        // 的组合返回 0，库存的准入过滤器据此拒收）——档位在那儿却收不下任何东西。当前只登记能源卡（终端的常
        // 规项，减空闲耗电）；其余 5 格留给待定的设计，不要为了让槽位“有用”而乱挂不相关的卡。
        var energyCard = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse("ae2:energy_card"));
        if (energyCard != null) {
            appeng.api.upgrades.Upgrades.add(energyCard,
                    AEPatternRegistries.ITEM_CELL_MANAGEMENT_TERMINAL.get(), 1);
        }

        // 自装配样板磁盘供应器：速度卡按 AE2 的规矩上；陨石超频卡是 AE2 Crystal Science 的升级件，得由本模组
        // 把它挂到自己的方块上（那张卡不知道这台设备），取卡细节在 AecsSoftDep 里。
        if (MeteoritePatternProviderRegistrations.isRegistered()) {
            var meteoriteProvider = MeteoritePatternProviderRegistrations.BLOCK.get();
            if (speedCard != null) {
                appeng.api.upgrades.Upgrades.add(speedCard, meteoriteProvider, 4);
            }
            AecsSoftDep.registerOverloadCard(meteoriteProvider, 4);
            // 面板形态不用另外登记：共享的那份逻辑建升级库存时传的是上面这个方块物品（
            // SelfAssemblingPatternDiskProviderLogic 里的 forMachine），卡的上下限就按它算，两形态同为 4 张。
        }

        // Applied Flux 的感应卡：它自己只给 AE2 的供应器与接口登记过，所以本模组的供应器两种形态得各登记
        // 一次——否则 AppFlux 挂上来的那一格升级库存摆在那儿，却一张卡也放不进去。细节见 AppFluxInductionCard。
        AppFluxInductionCard.register(AEPatternRegistries.ITEM_PROVIDER.get(),
                AEPatternRegistries.ITEM_CABLE_PATTERN_DISK_PROVIDER.get(),
                AEPatternRegistries.ITEM_MARKED_PROVIDER.get(),
                AEPatternRegistries.ITEM_CABLE_MARKED_PROVIDER.get());

        // 过载样板磁盘供应器同样缺这一句：它继承 AE2 的供应器逻辑，AppFlux 那一格本来就挂得上来，
        // 只是没人给它登记过卡上限。机器的身份用它的终端图标物品，与 AppFlux 建库存时取的是同一个。
        if (net.neoforged.fml.ModList.get().isLoaded("ae2lt")) {
            AppFluxInductionCard.register(io.github.lounode.ae2pattern.integration.ae2lt.overloaded
                    .OverloadedPatternDiskProviderRegistrations.ITEM.get());
        }

        // AdvancedAE 的高级样板编码器：装上它，编码终端才多出高级编码模式。面板版走 AE2 的升级表，
        // 无线版（含 AE2WTLib 的通用终端）走它自己那一套。细节见 AdvancedPatternEncoder。
        AdvancedPatternEncoder.register(AEPatternRegistries.ITEM_PATTERN_DISK_ENCODING_TERMINAL.get());

        // Rechiseled: Applied Energistics 的雕凿样板编码器：同一条路——装上它，编码终端才多出雕凿编码模式。
        // 它是方块物品（只能按物品 id 取），自己也只在世界里工作，在这儿纯粹当一把钥匙。
        ChiselingPatternEncoder.register(AEPatternRegistries.ITEM_PATTERN_DISK_ENCODING_TERMINAL.get());

        // AE2 Lightning Tech 的过载样板编码器：同一条路——装上它，编码终端才多出过载编码模式。
        // 它必须登记：那枚卡不是 UpgradeCardItem 的子类，升级槽只靠 Upgrades 表收它（细节见 OverloadPatterns，
        // 漏登记的表现为「档位就在那儿、卡拖进槽里弹回来、还没任何提示」）。
        OverloadPatterns.register(AEPatternRegistries.ITEM_PATTERN_DISK_ENCODING_TERMINAL.get());

        // 两个无线终端再单独登记一次：不走 AE2WTLib 的 UpgradeHelper——它只遍历上游自己的终端表、
        // 且 readyForUpgrades 置位前后行为不同，本模组这两个终端在不在其中不由我们说了算。放到这里而不是
        // 物品的注册回调里：那边 AdvancedAE 的物品可能还没注册好，取不到卡就只有一条 debug 日志，
        // 表现为「槽在那儿、卡放不进」而毫无提示。
        // 判 AE2WTLib 是否加载是必需的：没装它时两个无线终端根本没有物品实例，而那个工厂方法会当场 new，
        // 在注册表已冻结的 commonSetup 阶段会抛 IllegalStateException。
        if (net.neoforged.fml.ModList.get().isLoaded("ae2wtlib")) {
            AdvancedPatternEncoder.registerFor(AEPatternRegistries.wirelessEncodingItem(),
                    AEPatternRegistries.wirelessManagementItem());
            ChiselingPatternEncoder.registerFor(AEPatternRegistries.wirelessEncodingItem(),
                    AEPatternRegistries.wirelessManagementItem());
            OverloadPatterns.registerFor(AEPatternRegistries.wirelessEncodingItem(),
                    AEPatternRegistries.wirelessManagementItem());
        }
    }
}

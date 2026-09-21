package io.github.lounode.ae2pattern.integration.neoecoae;

/**
 * NEO ECO 的类型名与方法名，集中一处。
 *
 * <p>反射面（{@link NeoECOBusAccess} 等）与客户端能力门控（{@code NeoECOClientIntegration}）必须问同一批
 * 名字：两侧各写一份字面串的话，一边加了判据、另一边没加，就会出现「客户端画了按钮、服务端没接线」这类
 * 各说各话的状态——那正是这套门控要消除的东西。</p>
 *
 * <p>这里只有字面串，没有任何 NEO ECO 类型引用：客户端要在没装 NEO ECO 的机器上读它，不能触发对方类加载。</p>
 */
public final class NeoECOTypes {
    /** 被集成的 FD 智能样板总线。 */
    public static final String BUS =
            "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity";
    /** 总线对外暴露的辅助样板存储 SPI。 */
    public static final String AUXILIARY_STORE = "cn.dancingsnow.neoecoae.api.AuxiliaryPatternStore";
    /** 已解码样板（{@code ECOPreparedPattern}），报告式上传的入参。 */
    public static final String PREPARED_PATTERN = "cn.dancingsnow.neoecoae.api.ECOPreparedPattern";
    /** 样板存储服务接口。 */
    public static final String STORAGE_SERVICE = "cn.dancingsnow.neoecoae.api.IECOPatternStorageService";
    /** 上传按钮控件（客户端独有）。 */
    public static final String UPLOAD_BUTTON = "cn.dancingsnow.neoecoae.gui.widget.UploadButton";
    /** 报告式上传入口的方法名。 */
    public static final String REPORTING_ENTRY = "insertPreparedPatternReporting";
    /** 消耗掉样板时退还什么的查询方法名（与报告式入口同批引入）。 */
    public static final String BLANK_REPLACEMENT_ENTRY = "blankPatternReplacementFor";
    /** 插入结果枚举。 */
    public static final String INSERTION_RESULT = "cn.dancingsnow.neoecoae.api.ECOPatternInsertionResult";
    /** 上传记账要认的结果常量；官方 v21.1.2 也有它，可当构建完整性的哨兵。 */
    public static final String ALREADY_PRESENT_CONSTANT = "ALREADY_PRESENT";

    private NeoECOTypes() {
    }
}

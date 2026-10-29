package io.github.lounode.ae2pattern.common.menu;

/**
 * AE2 客户端动作的参数编码。
 *
 * <p>AE2 的 {@code AEBaseMenu.ClientAction.handle} 用 {@code Gson.fromJson(payload, argClass)} 解参数，
 * 所以发送端必须交 JSON。单独成类（而不是挂在某个菜单的静态方法上）有两个理由：菜单类带着未注册的
 * {@code MenuType} 静态初始化，纯 JVM 测试里碰不得；而编码这件事与任何一台设备都无关。</p>
 */
public final class ClientActionPayload {

    /** Gson 默认是宽松模式：{@code #} 开头会被当注释跳过，读到底就是 {@code null}。 */
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private ClientActionPayload() {
    }

    /**
     * 把一个字符串编成 AE2 客户端动作要的 JSON 参数。
     *
     * <p>磁盘标记长这样：{@code #minecraft:crafting}。裸着发出去不会报错，Gson 会把它当注释读成
     * {@code null}，于是动作处理函数收到一个空标记、判定「与当前相同」，表现就是点候选格子没反应。</p>
     */
    public static String json(String value) {
        return GSON.toJson(value);
    }
}

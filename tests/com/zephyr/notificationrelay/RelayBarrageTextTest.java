package com.zephyr.notificationrelay;

public final class RelayBarrageTextTest {
    public static void main(String[] args) {
        equal("小红书 | 小明 | 测试手机: 有一条新消息", RelayBarrageText.format(
                "小红书 | 小明 | 测试手机", "有一条新消息", "小红书", true));
        equal("QQ · 小明 | 测试手机: 测试消息", RelayBarrageText.format(
                "小明 | 测试手机", "测试消息", "QQ", false));
        equal("QQ | 测试手机: 测试消息", RelayBarrageText.format("QQ | 测试手机", "测试消息", "QQ", false));
        equal("小红书 | 小明 | 测试手机: 测试消息", RelayBarrageText.format(
                "小红书 | 小明 | 测试手机", "测试消息", "小红书", false));
        equal("小明: 第一行 第二行", RelayBarrageText.format("小明", "第一行\n第二行", "QQ", true));
        equal("QQ: 测试消息", RelayBarrageText.format(null, "测试消息", "QQ", false));
        String full = "😀".repeat(120);
        equal(full, RelayBarrageText.format(null, full, null, true));
        String truncated = RelayBarrageText.format(null, "😀".repeat(121), null, true);
        equal("😀".repeat(119) + "…", truncated);
        if (truncated.codePointCount(0, truncated.length()) != 120) throw new AssertionError("length limit");
        System.out.println("RelayBarrageText tests passed");
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Unexpected presentation for synthetic test");
    }
}

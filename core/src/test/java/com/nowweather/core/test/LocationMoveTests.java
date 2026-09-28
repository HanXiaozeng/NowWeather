package com.nowweather.core.test;

import com.nowweather.core.sync.WeatherController;

/**
 * 位置巡检：同城静默、跨城播报。
 *
 * <p>判据是 adcode 的行政区划前缀（33=浙江 / 3301=杭州 / 330106=西湖区），
 * 所以这里把边界钉死 —— 判错会导致玩家在城里走两步就收到「已进入 XX」。</p>
 */
final class LocationMoveTests {

    private LocationMoveTests() {
    }

    static void run() {
        sameCityRules();
        crossingLabels();
    }

    private static void sameCityRules() {
        T.section("位置巡检：同城判定（不该打扰玩家的情形）");
        // 同一个区
        T.check("同一个区 -> 同城", WeatherController.sameCity("330106", "杭州市", "330106", "杭州市"));
        // 同城跨区：西湖区 -> 余杭区，前 4 位都是 3301
        T.check("同城跨区（330106 -> 330110）-> 同城",
                WeatherController.sameCity("330106", "杭州市", "330110", "杭州市"));
        // 城市名写法不同但 adcode 相同：不能因为字符串不同就误报
        T.check("adcode 相同但城市名写法不同 -> 同城",
                WeatherController.sameCity("330106", "杭州市", "330106", "杭州"));

        T.section("位置巡检：跨城判定（必须播报的情形）");
        T.check("跨市（杭州 -> 宁波）-> 不同城",
                !WeatherController.sameCity("330106", "杭州市", "330200", "宁波市"));
        T.check("跨省（杭州 -> 上海）-> 不同城",
                !WeatherController.sameCity("330106", "杭州市", "310104", "上海市"));
        T.check("跨境（无 adcode，城市名不同）-> 不同城",
                !WeatherController.sameCity("", "杭州市", "", "Tokyo"));
        T.check("一边有 adcode 一边没有 -> 不同城",
                !WeatherController.sameCity("330106", "杭州市", "", "Tokyo"));

        T.section("位置巡检：信息不足时不能瞎报");
        T.check("两边都没有任何信息 -> 不算同城（宁可不报）",
                !WeatherController.sameCity("", "", "", ""));
        T.check("adcode 非法（非数字）时不误判为同城",
                !WeatherController.sameCity("abc123", "A", "abd123", "B"));
        T.check("adcode 太短时不按前缀比",
                !WeatherController.sameCity("33", "A", "31", "B"));
    }

    private static void crossingLabels() {
        T.section("位置巡检：播报文案");
        T.check("跨省文案", WeatherController.describeCrossing("330106", "310104").contains("跨省"));
        T.check("跨市文案", WeatherController.describeCrossing("330106", "330200").contains("跨市"));
        T.check("境外交文案", WeatherController.describeCrossing("", "330106").contains("跨境"));
        T.check("省内有 adcode 时不写「跨境」",
                !WeatherController.describeCrossing("330106", "330200").contains("跨境"));
    }
}
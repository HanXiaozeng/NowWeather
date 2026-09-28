package com.nowweather.core.test;

import com.nowweather.core.providers.openmeteo.OpenMeteoGeocoder;

/**
 * 中文城市名的地理编码候选词。
 *
 * <p>回归用例：Open-Meteo 的地理编码接口<b>吃不下带「市」字的写法</b> ——
 * 实测 {@code name=杭州市} 返回空、{@code name=杭州} 才命中。
 * 而 UApiPro 按 IP 定位返回的城市名恰好带「市」字，于是自动定位链路断在这里。</p>
 */
final class GeocoderTests {

    private GeocoderTests() {
    }

    static void run() {
        candidates();
    }

    private static void candidates() {
        T.section("地理编码：中文行政区划后缀必须能剥掉");
        var hangzhou = OpenMeteoGeocoder.candidateQueries("杭州市");
        T.check("「杭州市」能生成候选词", !hangzhou.isEmpty());
        T.check("「杭州市」-> 「杭州」", hangzhou.contains("杭州"));

        var xihu = OpenMeteoGeocoder.candidateQueries("西湖区");
        T.check("「西湖区」-> 「西湖」", xihu.contains("西湖"));

        var full = OpenMeteoGeocoder.candidateQueries("杭州市西湖区");
        T.check("「杭州市西湖区」逐级缩短", full.contains("杭州市西湖") && full.contains("杭州市西湖区".substring(0, 5))
                || full.contains("杭州市"));
        T.check("多级候选中包含「杭州」", full.contains("杭州"));

        T.check("「内蒙古自治区」-> 去掉「自治区」",
                OpenMeteoGeocoder.candidateQueries("内蒙古自治区").contains("内蒙古"));
        T.check("「浙江省」-> 「浙江」",
                OpenMeteoGeocoder.candidateQueries("浙江省").contains("浙江"));

        // 已经是纯城市名时不该产生噪声候选
        T.check("「杭州」不产生多余候选（自身不作为候选）",
                !OpenMeteoGeocoder.candidateQueries("杭州").contains("杭州"));
        T.check("英文名不产生候选", OpenMeteoGeocoder.candidateQueries("Tokyo").isEmpty());
        T.check("太短的输入不产生候选", OpenMeteoGeocoder.candidateQueries("市").isEmpty());
    }
}
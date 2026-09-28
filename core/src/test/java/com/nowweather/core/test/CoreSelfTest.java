package com.nowweather.core.test;

/**
 * NowWeather 核心自测入口。
 *
 * <p>零依赖、零网络、零 Minecraft：直接跑就能验证「气候映射 / 预报合理性 /
 * UApiPro 解析 / 同步链路」这些最关键的部分。</p>
 *
 * <pre>{@code
 * # 用 Gradle
 * ./gradlew :core:coreSelfTest
 *
 * # 或者直接（JDK 17+）
 * javac -d out $(find core/src/main/java core/src/test/java -name '*.java')
 * java -cp out com.nowweather.core.test.CoreSelfTest
 * }</pre>
 *
 * <p>退出码：0 = 全部通过，1 = 有失败用例。</p>
 */
public final class CoreSelfTest {

    private CoreSelfTest() {
    }

    public static void main(String[] args) {
        System.out.println("NowWeather 核心自测 v" + com.nowweather.core.NowWeatherCore.VERSION);
        System.out.println("Java: " + System.getProperty("java.version") + " / " + System.getProperty("os.name"));

        // ★ 必须先装翻译器：所有「展示名」现在都是语言键，装不上就会显示键名。
        //   这里读的是仓库里真正发给玩家的那一份 zh_cn.json，所以漏键会直接被断言抓到。
        TestLang.install();

        long start = System.currentTimeMillis();
        JsonTests.run();
        ClimateTests.run();
        WeatherTypeTests.run();
        SkyForecastTests.run();
        LocationMoveTests.run();
        GeocoderTests.run();
        CaiyunTests.run();
        AllowanceTests.run();
        ProviderTests.run();
        OpenMeteoTests.run();
        ForecastTests.run();
        IntegrationTests.run();
        BiomeWeatherMatrixTests.run();
        SeasonalTests.run();
        TimeSyncTests.run();
        SolarTests.run();
        TerrainTests.run();
        TyphoonTests.run();
        LlmTests.run();
        ContinentalityTests.run();
        OfflineTests.run();

        int failures = T.summary();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("耗时: " + elapsed + " ms");
        if (failures == 0) {
            System.out.println("✅ 全部通过（" + T.passedCount() + " 项断言）");
            System.exit(0);
        } else {
            System.out.println("❌ 有 " + failures + " 项失败");
            System.exit(1);
        }
    }
}

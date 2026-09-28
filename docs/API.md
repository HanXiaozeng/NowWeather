# NowWeather 扩展接口（给其它模组用）

> 这个模组刻意把「天气从哪来」「天气往哪去」「气候怎么算」三件事都做成公开接口。
> 别的模组不需要依赖 NowWeather 的实现，只需要对着下面这几个接口写。
>
> 包路径：`com.nowweather.core.api`。

---

## 0. 一张图看懂三个扩展点

```
                    ┌───────────────────────────────┐
   ① WeatherProvider │  入站：你的天气 → NowWeather   │
      （数据源）     └───────────────┬───────────────┘
                                    ▼
                    ┌───────────────────────────────┐
   ② ClimateResolver│  中间：改写某个群系的气候判定  │
      （气候覆盖）   └───────────────┬───────────────┘
                                    ▼
                    ┌───────────────────────────────┐
   ③ WeatherBinding │  出站：NowWeather → 你的天气   │
      （联动）       └───────────────────────────────┘
```

| 你的模组是… | 应该实现 | 例子 |
| --- | --- | --- |
| 自己也查天气（另一个天气 API / 覆盖网络） | `WeatherProvider` | 你自己的数据源插进回退链（内置链的先后顺序见 §1） |
| 有季节/维度概念，想改写群系气候 | `ClimateResolver` | 季节模组：冬天把温带群系的正常温度范围整体下移 |
| 有自己的云层/气压/风暴系统，想跟着真实天气走 | `WeatherBinding` | 让大气模组的云量跟着湿度走 |
| 只是想让玩家看到真实天气 | 不用写代码 | 读 `NowWeatherApi.currentObservation()` 即可 |

---

## 1. 入站：`WeatherProvider`

把任意来源的天气喂给 NowWeather。**核心不关心你从哪拿数据。**

```java
public final class MyWeatherProvider implements WeatherProvider {

    @Override public String id() { return "myweathermod"; }
    @Override public String displayName() { return "我的天气源"; }

    /** 数字越小越优先：0~49 本地模组，50~199 在线 API（UApiPro = 100），200+ 兜底 */
    @Override public int priority() { return 60; }

    @Override public boolean isEnabled(ProviderContext context) {
        return ModList.get().isLoaded("myweathermod");   // 按需
    }

    /** ★ 这是阻塞调用，框架会在自己的 IO 线程上执行它，不要在 MC 主线程调用。 */
    @Override public ProviderResult fetch(ProviderContext context) {
        try {
            var observation = WeatherObservation.builder(id())
                    .weatherType(WeatherType.THUNDERSTORM)
                    .conditionText("雷阵雨")
                    .temperatureC(28.5)
                    .humidityPercent(88)
                    .windScale(5)
                    .windDirection(WindDirection.SW)
                    .location("浙江省", "杭州", "330100")
                    .build();
            return ProviderResult.ok(id(), observation);
        } catch (Exception e) {
            return ProviderResult.failure(id(), ProviderStatus.NETWORK_ERROR, e.getMessage());
        }
    }

    @Override public long minIntervalMillis() { return 5 * 60 * 1000L; }  // 缓存 5 分钟
}
```

注册：

```java
NowWeatherApi.registerProvider(new MyWeatherProvider());
```

要点：

- **回退链**：NowWeather 会按优先级（**数字小者先试**，`WeatherProviderRegistry.register()` 里就是
  `Comparator.comparingInt(WeatherProvider::priority)`）依次尝试所有可用数据源，直到有一个成功。
  运行时实际注册的 3 个内置数据源依次是 **彩云 caiyun(80) → Open-Meteo(90) → UApiPro(100)**；
  彩云需要配置里的 `api.caiyunToken`，没填 token 时它会被跳过，于是实际起点就是 Open-Meteo。
  三个全挂之后**再落到离线推算**（本地气候生成）—— 注意那不是第四个 `WeatherProvider`，
  而是控制器在数据源链走完后的兜底路径，所以它没有优先级数字。
- **`ProviderStatus` 要如实返回**：`NETWORK_ERROR`/`RATE_LIMITED` 会触发退避重试，
  `NO_LOCATION`/`DISABLED` 则会被跳过，不会浪费请求。
- 想做「按玩家 IP 定位」的数据源，把 `providesLocation()` 返回 true。

---

## 2. 中间：`ClimateResolver`

改写某个生物群系的气候判定。典型场景是**季节**。

```java
public final class SeasonalClimateResolver implements ClimateResolver {

    @Override public String id() { return "myseason:seasonal"; }
    @Override public int priority() { return 500; }   // 越小越先被询问

    @Override public Optional<BiomeClimate> resolve(BiomeFacts facts) {
        if (facts.isNether() || facts.isEnd()) {
            return Optional.empty();          // 交给别人处理
        }
        BiomeClimate base = /* 你自己算出来的基础气候 */;
        // 冬天整体下移 10°C，夏天上移 6°C
        double shift = seasonOffsetCelsius();
        return Optional.of(BiomeClimate.builder(base)
                .tempRange(base.tempRange().minC() + shift, base.tempRange().maxC() + shift)
                .source("myseason")
                .build());
    }
}
```

注册：`NowWeatherApi.registerClimateResolver(resolver);`

> 解析优先级：**配置里的精确条目 > 标签规则 > 分类规则 > 你的解析器 > 内置启发式**。
> 也就是说玩家/整合包永远可以覆盖你；这是刻意的设计。

如果只是想给某个群系一条固定配置，更简单的做法是直接注册：

```java
NowWeatherApi.registerBiomeClimate(BiomeClimate.builder("some_mod:my_biome")
        .climateClass(ClimateClass.POLAR)
        .tempRange(-35, 5)
        .precipitation(PrecipitationType.SNOW)
        .humidity(0.6).windiness(0.7).thunderBias(0.5)
        .possibleWeather(WeatherType.CLEAR, WeatherType.RAIN, WeatherType.THUNDERSTORM)
        .source("my_mod").confidence(Confidence.HIGH)
        .build());
```

> `BiomeClimate.Builder` 只有两个天气白名单方法：`possibleWeather(Set<WeatherType>)` 与
> `possibleWeather(WeatherType...)`，参数都是核心的 `WeatherType`（25 种扩展天气）。
> **`possibleMcWeather` 只是 JSON 字段名**，不是 builder 方法 —— 它出现在
> `data/nowweather/climate/*.json` 里时会被自动展开成扩展天气（原版三档
> `CLEAR`/`RAIN`/`THUNDER`），想在 Java 代码里直接构造请用 `possibleWeather`。

---

## 3. 出站：`WeatherBinding`

把 NowWeather 决定好的天气推给你的模组（云层、气压、风暴、粒子……）。

```java
public final class MyAtmosphereBinding implements WeatherBinding {

    @Override public String id() { return "myatmosphere:bridge"; }
    @Override public String targetModId() { return "myatmosphere"; }
    @Override public int priority() { return 50; }

    @Override public void onActivate(BindingContext context) {
        // 目标模组在场时调用一次：初始化 API、注册你自己的监听
        MyAtmosphereApi.registerProvider(api -> { /* ... */ });
    }

    @Override public void onWeatherChanged(BindingContext context, PlatformWeather applied,
                                           WeatherObservation observation) {
        // 真实天气变了：把云量/气压/风推给你的系统
        double humidity = observation == null ? 0.5 : observation.humidity01();
        MyAtmosphereApi.setCloudCover(humidity * applied.ordinal() * 0.5);
        MyAtmosphereApi.setWindKmh(observation == null ? 0 : observation.windSpeedKmh());
    }

    @Override public void onServerTick(BindingContext context, PlatformWeather applied) {
        // 需要平滑过渡时用（每 5 刻调用一次）
    }
}
```

注册：`NowWeatherApi.registerBinding(new MyAtmosphereBinding());`

`BindingContext` 里能拿到：维度、生物群系 id、**该群系的气候画像**（含正常温度范围与天气白名单）、
世界时间、模组存在性探测、日志器、以及 `PlatformWeatherAccess`（想自己读写天气也可以）。

> 注意：`isAvailable(presence)` 默认检查 `targetModId()` 是否加载。
> 目标模组不在场时，你的 `onWeatherChanged` 不会被调用 —— 所以**不要**在静态初始化里碰目标模组的类。

---

## 4. 只读：拿数据给玩家看

```java
// 当前真实天气
NowWeatherApi.currentObservation().ifPresent(obs ->
        player.sendMessage(new TextComponent(obs.describeForPlayer()), Util.NIL_UUID));

// 最近一次天气决策（含「是否被本地气候修正过」）
NowWeatherApi.lastDecision().ifPresent(decision ->
        LOGGER.info("当前：{}（{}）", decision.target(), decision.reason()));

// 某个生物群系的气候
NowWeatherApi.climateOf(biomeFacts).ifPresent(climate -> {
    TempRange normal = climate.tempRange();                     // ★ 正常温度范围
    Set<PlatformWeather> possible = climate.possiblePlatformWeather();  // ★ 可能出现的天气
    LOGGER.info("{}：正常 {}，可能出现 {}", climate.biomeId(), normal.describe(), possible);
});
```

`NowWeatherApi` 的所有方法在 NowWeather 未安装 / 未初始化时都**安全返回空值**，
不会抛异常 —— 所以你可以把 NowWeather 声明为可选依赖，放心调用。

### 4.1 全部公开方法（`com.nowweather.core.api.NowWeatherApi`）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `isAvailable()` | `boolean` | 已经 install 过（可用）就 true；比 `ModList.isLoaded` 更准，因为模组可能在但运行时还没装好 |
| `runtime()` | `Optional<NowWeatherRuntime>` | 拿到运行时本体（数据源表、气候表、绑定表、控制器）；想深入诊断时才用 |
| `currentObservation()` | `Optional<WeatherObservation>` | 当前真实天气 |
| `lastDecision()` | `Optional<WeatherDecision>` | 最近一次天气决策 |
| `climateOf(BiomeFacts)` | `Optional<BiomeClimate>` | 按群系事实解析气候（会走完整解析链 + `ClimateModifier` 后处理） |
| `climateOf(String)` | `Optional<BiomeClimate>` | 只查**显式注册**的那一条（内置数据 / 配置 / 数据包），不做启发式推断，可能为空 |
| `climates()` | `Optional<BiomeClimateRegistry>` | 直接拿到气候注册表，自己遍历或注册规则 |
| `registerProvider(WeatherProvider)` | `boolean` | 见 §1 |
| `unregisterProvider(String providerId)` | `boolean` | 按 id 摘掉一个数据源；没找到返回 false |
| `registerBinding(WeatherBinding)` | `boolean` | 见 §3 |
| `unregisterBinding(String bindingId)` | `boolean` | 按 id 摘掉一个联动 |
| `registerClimateResolver(ClimateResolver)` | `boolean` | 见 §2 |
| `registerBiomeClimate(BiomeClimate)` | `boolean` | 直接注册/覆盖某个群系的气候 |
| `install(NowWeatherRuntime)` / `uninstall()` | `void` | **由加载器/平台层调用**，普通模组不要碰：`install` 是 NowWeather 自己初始化时调用的，`uninstall` 是关闭时清空运行时 |

> `registerXxx` 系列在 NowWeather 未就绪时返回 `false` 而不是抛异常，所以你可以无条件调用。

---

## 5. 可选依赖怎么写

`mods.toml`：

```toml
[[dependencies.yourmod]]
modId = "nowweather"
mandatory = false          # 可选
versionRange = "[0.1,)"
ordering = "AFTER"         # 在 NowWeather 之后加载，保证它已经初始化
side = "BOTH"
```

代码里：

```java
if (ModList.get().isLoaded("nowweather")) {
    NowWeatherApi.registerBinding(new MyAtmosphereBinding());
}
```

> `ModList.get().isLoaded(...)` 在模组构造期就可以安全调用（1.18.2 已验证）。
> 但不要在构造期去访问目标模组的类 —— 那时它的静态初始化可能还没完成。
> 安全的时机是 `FMLCommonSetupEvent` 或服务端启动之后。

---

## 6. 稳定性承诺

| 包 | 稳定性 | 说明 |
| --- | --- | --- |
| `com.nowweather.core.api` | **稳定** | 面向外部模组的接口，破坏性改动会升 major 版本 |
| `com.nowweather.core.weather` / `climate` | 稳定 | 数据对象（`WeatherObservation`、`BiomeClimate`、`WeatherType`…） |
| `com.nowweather.core.*` 其它 | 内部 | 随时可能改，别直接依赖 |
| `com.nowweather.forge.*` | 内部 | 平台实现，跨版本必然不同 |

已实现的真实例子见 `docs/research/cross-mod-compatibility.md` 第 4 节列出的
天气模组清单（Weather2 / Project Atmosphere / Serene Seasons…），
它们的接入方式都可以归结为上面三个接口之一。

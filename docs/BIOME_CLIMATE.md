# 生物群系与气候映射

> 这份文档解释 NowWeather 的核心问题：**现实世界的天气，怎么落到一个虚拟生物群系里才合理？**
> 全部内置数据见自动生成的 [BIOME_CLIMATE_TABLE.md](BIOME_CLIMATE_TABLE.md)
> （条数不在这里写死 —— 每次新增版本/群系模组数据它都会变，以那张表末尾的合计为准）。

---

## 1. 两条硬约束

每个生物群系的气候由两组数据定义，它们回答两个不同的问题：

| 约束 | 字段 | 回答的问题 | 举例 |
| --- | --- | --- | --- |
| **正常温度范围** | `tempMinC` ~ `tempMaxC` | 这里的温度**合不合理**？ | 雪原 `-38 ~ 5°C`：真实世界 30°C 会被拉回去 |
| **可能出现的天气** | `possibleWeather` / `possibleMcWeather` | 这种天气在这里**会不会发生**？ | 沙漠只有「晴」：真实世界下暴雨也不会在游戏里下雨 |

两者都会被预报系统与天气决策强制执行：

```
真实天气（杭州 30°C 晴）
        │
        ├─→ ① 温度校验：32°C 超出雪原的 -38~5°C → 记为违规并夹回
        ├─→ ② 天气白名单：雪原不会出现「台风」→ 换成最接近的合法天气
        └─→ ③ 形态校验：-3°C 下「中雨」→ 自动变成雪
        ↓
   最终落地：晴 / 雨 / 雷暴
```

违规项不会被静默丢弃，而是记录在预报条目里（`violations` 字段），
`/NowWeather biome`、`/NowWeather status` 与 HUD 都会标出「已依本地气候修正」。

---

## 2. 数据从哪来（解析优先级）

解析一个生物群系的气候时，按下面的顺序找，**先命中先用**：

| 优先级 | 来源 | 说明 |
| --- | --- | --- |
| 1 | 配置里的精确 id 条目 | `config/nowweather/climate/*.json` 或数据包里的 `"biomes": [{"id": "..."}]` |
| 2 | 标签规则 | `"tags": [{"tag": "minecraft:is_forest", ...}]`，作用于所有带该标签的群系 |
| 3 | 原版分类规则 | `"categories": [{"category": "snowy", ...}]` |
| 4 | 其它模组注册的解析器 | `ClimateResolver` 扩展接口（例如季节模组按季节改写范围） |
| 5 | **启发式推断** | 永远是最后一道兜底，见下一节 |
| 6 | **气候修正器（后处理）** | 不是「再找一次」，而是**上面前 5 级无论命中哪一级，结果都会再过一遍 `ClimateModifier` 链** |

> 第 6 级容易漏看：`BiomeClimateRegistry.resolve()` 先按 1~5 级得到基础气候，
> 然后按 `priority()` 从小到大依次调用所有 `isActive()` 的 `ClimateModifier`，把返回值作为最终结果
> （返回 `null` 表示该修正器本次不干预）。**季节联动就挂在这里**（`SereneSeasonsClimateModifier`）——
> 这就是为什么「季节」不能写成 `ClimateResolver`：内置数据一旦命中，解析器根本不会被问到，
> 季节就永远改不动已配置的群系。另外，只要注册了修正器，解析结果**不再缓存**
> （修正结果会随季节推进而变化，缓存住就会一直停在注册那一刻的季节）。

> **标签/分类规则的聪明之处**：如果规则里**没有**写 `tempMinC/tempMaxC`（大多数情况下都不该写），
> 那么它只提供「气候大类」，温度范围仍然按该群系**真实的原版基础温度**推断。
> 这样一条 `#minecraft:is_forest` 规则可以安全地覆盖上千个模组群系，
> 而不会把所有森林都硬塞进同一个温度区间。

---

## 3. 启发式推断（未知群系的兜底）

面对没写过配置的模组群系，按下面的顺序判断：

```
① 维度       下界 → NETHER，末地 → END
② 名称关键词  "snowy_*" → 极地，"jungle" → 热带，"volcano" → 火山 …
③ 原版分类   category=forest → 温带，category=icy → 极地 …
④ 原版温度   baseTemperature 2.0 → 炎热，0.0 → 严寒
```

### 原版温度 ↔ 摄氏的换算

```
摄氏 = 原版基础温度 × 20 − 10
原版 0.0（雪原）→ −10°C ；0.8（平原）→ 6°C ；2.0（沙漠）→ 30°C
```

这是一条**公开的、可复算的启发式规则**，不是官方数值。
它的作用只是给模组群系一个合理的起点值。

### 温度范围怎么定

基准半径是 `HeuristicClimateResolver.DEFAULT_SPREAD = 8.0`（摄氏度），然后按气候大类调整
（`ClimateMath.heuristicRange`）：

```
radius = 8
        + 6            若气候大类「极端」（isExtreme() = POLAR / ARID / NETHER / VOID / VOLCANIC）
        + 6            若大类是 ARID 或 VOLCANIC（干旱群系昼夜温差更大；这两类同时也是极端类，故共 +12）
radius  = max(6, radius - 5)   若大类是 OCEANIC（海洋调温，范围更窄）
```

拿到 `以原版温度为中心 ± radius` 之后，分两条路：

- **名称关键词命中了**（说明大类可信）→ **以大类的默认范围为准**：
  先求「大类默认范围 ∩ 按原版温度算出的范围向外扩 12°C」；
  交集跨度 **≥ 10°C** 就采用它，否则说明两者差得离谱 ——
  改为把大类默认范围**整体朝原版温度平移**，平移量夹在自身跨度的 ±25% 以内
  （不推翻大类，只是挪一挪）。理由：丛林一定有丛林的样子，
  不能因为某个模组把温度设低了就允许它下雪；
- **只靠分类/温度兜底** → 求「大类默认范围 ∩ 按原版温度算出的范围向外扩 **4°C**」，
  **交集跨度 ≥ 8°C 才采用**；否则整体退回「原版温度 ± radius」那个原始范围，
  即**以原版实际温度为准**（两套推断冲突时，实测到的原版温度更可信）。

### 天气白名单怎么推

| 依据 | 影响 |
| --- | --- |
| 温度范围**上限** ≥ 2°C（`maxC() >= 2.0`） | 允许雨类天气 |
| 温度范围**下限** ≤ 1°C（`minC() <= 1.0`） | 允许雪类天气；下限 ≤ −8°C 才有大雪；下限 ≤ −5°C 且风大才有暴风雪 |
| 降水形态 = `NONE` | 禁止一切降水（沙漠、下界、末地、洞穴） |
| 雷暴倾向 `thunderBias` ≥ 0.45 | 允许雷阵雨；≥ 0.95 且湿度高才允许强雷暴 |
| 湿度 ≥ 0.60 | 允许浓雾；湿度 < 0.30 允许霾 |
| 风力 ≥ 0.55 | 允许大风 |
| 热带 + 高湿 + 大风 | 才可能出现台风 |

---

## 4. 一个例子：为什么沙漠只有「晴」

```json
{
  "id": "minecraft:desert",
  "climateClass": "ARID",
  "tempMinC": 10, "tempMaxC": 52,
  "precipitation": "NONE",
  "humidity": 0.07, "windiness": 0.55, "thunderBias": 0.15,
  "possibleMcWeather": ["CLEAR"]
}
```

- 原版沙漠基础温度 2.0（= 30°C），且 `precipitation = none`；
- 因此 `canPrecipitate() = false` → 任何降水都被替换成霾 / 沙尘暴 / 多云；
- `thunderBias = 0.15 < 0.45` → 连雷阵雨都不允许；
- 结果：**沙漠的原版天气只可能是「晴」**，与原版行为一致。

现实世界在沙漠下暴雨时，NowWeather 会在日志/HUD 里写明「已依本地气候修正」，
而不是硬把雨塞进沙漠。

---

## 5. 想改？三种方式

### 5.1 游戏内改（最简单）

```
模组列表 → NowWeather → 配置
```
能改开关、位置、API Key、阈值、预报参数等。但**不能改某个群系的温度范围**（那是数据，不是配置）。

### 5.2 导出内置数据再改（推荐给服主）

```
/NowWeather dump
```
会把内置的 **3 个**数据文件复制到 `config/nowweather/climate/`（`ClimateDataReloader.dumpBuiltinToConfig()`
里写死的三个：`vanilla_1.18.1-1.18.2.json`、`vanilla_later_1.19-26.x.json`、`biomesoplenty_16.0.0.134.json`），
直接编辑后用
```
/NowWeather reload
```
生效。配置目录的优先级高于模组内置数据，也高于数据包。

> ⚠️ **只导出这 3 份**：Terralith（96 条）和 Tectonic（26 条）**不会被导出** ——
> 那两份是随 jar 打包、按群系模组是否加载才生效的数据，`dump` 不碰它们。
> 想改它们的群系，请用下面的数据包方式自己写一份覆盖条目。

### 5.3 用数据包追加（推荐给整合包作者）

在你自己的数据包里放 `data/nowweather/climate/my_pack.json`：

```json
{
  "biomes": [
    {
      "id": "regions_unexplored:alpha_grove",
      "climateClass": "TEMPERATE",
      "tempMinC": -10, "tempMaxC": 30,
      "precipitation": "RAIN",
      "humidity": 0.7, "windiness": 0.3, "thunderBias": 1.0,
      "possibleMcWeather": ["CLEAR", "RAIN", "THUNDER"],
      "source": "my_pack",
      "confidence": "HIGH"
    }
  ],
  "tags": [
    { "tag": "regions_unexplored:is_cold", "climateClass": "POLAR" }
  ]
}
```

---

## 6. 字段速查

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | ✅ | 生物群系资源 id，如 `minecraft:plains` |
| `climateClass` | enum | | 气候大类，见 `ClimateClass`（TEMPERATE / ARID / POLAR / …） |
| `tempMinC` / `tempMaxC` | number | | **正常温度范围**（摄氏）。也可写成 `"tempRange": [-10, 30]` |
| `baseTemperature` | number | | 原版基础温度（MC 量纲），用于核对与兜底 |
| `precipitation` | enum | | `NONE` / `RAIN` / `SNOW` / `SLEET` / `MIXED` |
| `humidity` | 0~1 | | 湿度 |
| `windiness` | 0~1 | | 常见风力 |
| `thunderBias` | number | | 雷暴倾向倍率，`0.15`（沙漠）~`1.55`（丛林） |
| `possibleWeather` | string[] | | 扩展天气白名单，如 `["CLEAR","RAIN","THUNDERSTORM"]` |
| `possibleMcWeather` | string[] | | 原版三档白名单 `CLEAR`/`RAIN`/`THUNDER`，会自动展开成扩展天气 |
| `source` | string | | 数据来源标记，会显示在 `/NowWeather biome` 里 |
| `confidence` | enum | | `HIGH` / `MEDIUM` / `LOW` / `GUESS`，影响预报可信度 |
| `since` | string | | 该群系加入的 MC 版本（生成器写入，便于核对） |

> 想只写「这个群系可能下雨、可能打雷」，用 `possibleMcWeather` 就够了；
> 想精确控制（例如「只会有毛毛雨和小雨，不会有暴雨」），用 `possibleWeather`。

---

## 7. 数据来源与置信度

| 数据 | 来源 | 置信度 |
| --- | --- | --- |
| 原版 1.18 基线的基础温度 / 降水 | Minecraft 1.18.2 数据包原文（`mcmeta`）+ 源码逐字段核对 | 高 |
| 1.19~26.x 新增群系 | Minecraft Wiki 版本页 + 各群系页（见 `docs/research/biomes-by-version.md`） | 中~高 |
| 超多生物群系 65 条 | Glitchfiend/BiomesOPlenty 源码（见 `docs/research/bop-biomes-1.18.2.md`） | 高 |
| 气候大类 / 温度范围 / 天气白名单 | **本项目依据原版数据 + 现实气候常识推导**（规则公开、可复算） | 推导值，可覆盖 |

已知的两个坑（调研时确认）：

- **Java 版稀树草原不下雨**：`savanna` / `savanna_plateau` / `windswept_savanna` 在 Java 版里
  `precipitation = none`，且基础温度均为 2.0（网上流传的 1.2 / 1.0 / 1.1 是基岩版的数值）；
- **1.19.4 改了降水的表示方式**：枚举 `precipitation` 被换成布尔 `has_precipitation`，
  下雪与否只由基础温度是否 < 0.15 决定 —— 跨版本时需要改平台层的读取方式
  （已集中在 `ForgeBiomeFactsFactory` 一个类里）。

---

## 8. 数据是怎么生成的

`docs/BIOME_CLIMATE_TABLE.md` 与 `data/nowweather/climate/*.json` **都是生成出来的**，
不是手写的：

```bash
javac -encoding UTF-8 --release 17 -d tools/out/core $(find core/src/main/java -name '*.java')
javac -encoding UTF-8 --release 17 -cp tools/out/core -d tools/out/gen tools/gen/GenerateClimateDatasets.java
java  -cp "tools/out/core;tools/out/gen" com.nowweather.tools.GenerateClimateDatasets
```

这样做的原因：天气白名单必须和运行时用的推导逻辑**完全一致**，
生成器直接调用 `WeatherAllowance.derive(...)`，所以文档永远不会和代码脱节。

- 输入：`_vanilla_source_1.18.2.json`（人工维护的原版基线）+ 版本新增表 + BOP 调研报告里的源码级数据
- 输出：`vanilla_1.18.1-1.18.2.json`、`vanilla_later_1.19-26.x.json`、`biomesoplenty_16.0.0.134.json`、`terralith_2.2.6.json`、`tectonic_2.3.5a.json` + `BIOME_CLIMATE_TABLE.md`

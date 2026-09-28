# Biomes O' Plenty 1.18.2 + 原版 Minecraft 1.18.2 生物群系气候数据清单

> 用途：WeatherSync 天气模组的气候映射表（每个生物群系的「正常温度范围」用于判断生成的气象数据是否合理）。
>
> - 目标版本：**Biomes O' Plenty for Minecraft 1.18.2**（Forge，BOP `16.0.0.x`）+ **原版 Minecraft Java Edition 1.18.2**
> - 文档生成方式：全部数据来自可公开访问的源码/数据包原文，逐条记录核对来源；**未编写或运行任何 Java 代码**，未修改仓库其他文件。
> - 说明：`原版基础温度` 即 MC `Biome.getBaseTemperature()` / 生物群系 JSON 中 `temperature` 字段的原始数值（游戏内单位，非摄氏度）；`正常温度范围°C` 是**派生的气候区间**（见 §2 规则），不是游戏内数据。

---

## 0. 结论速览

| 项目 | 数量 |
| --- | --- |
| BOP 1.18.2 生物群系注册 ID | **65**（58 主世界陆地 + 2 洞穴 + 5 下界） |
| 原版 1.18.2 生物群系注册 ID | **61**（51 主世界 + 5 下界 + 5 末地，含仅超平坦使用的 `minecraft:the_void`） |
| JSON 条目总数 | **126** |
| `confidence: high` 条目 | **100**（BOP 65 条 + 原版 35 条源码核验） |
| `confidence: medium` 条目 | **26**（原版，Wiki 气候表来源，未逐条对源码） |

> 注意：BOP 的生物群系可通过 `ModConfig` 开关禁用（`ModConfig.isBiomeEnabled(key)`），因此实际世界内出现的 BOP 群系数量可能少于 65。

---

## 1. 数据来源与核对程度（实际访问过的 URL）

### 1.1 BOP（Glitchfiend/BiomesOPlenty）

**重要前提（必须了解的不确定性）**：上游仓库 **没有公开 `1.18.2` 分支或 `1.18.2-*` 标签**。经 GitHub API 全量列举（47 个分支、33 个标签）确认：

- 分支：`1.20.1 … 26.2`、`BOP-1.7.10-2.1.x … BOP-1.16.4-13.x.x`、`BOP-1.19.x-17.x.x …`、`BOP-public`、`master` —— **无 1.18.x 分支**。
- 标签：最新为 `1.18.1-15.0.0`（MC 1.18.1），**无 1.18.2 标签**。

因此本文采用 **1.18.2 代码线上的最后一个提交**：`c3252ad898f4ff0e097d23ead85433bae33cb36e`，即「Updated to Minecraft 1.19」提交（`23711f2a…`，2022-06-08）的**直接父提交**。该提交位于 1.18.2 开发线末端（对应 BOP `16.0.0.x` 构建），是公开可核对的最后一份 1.18.2 状态源码。

已核对的原文（HTTP 200 全文读取）：

| 文件（ref = `c3252ad898f4ff0e097d23ead85433bae33cb36e`） | 核对内容 |
| --- | --- |
| `public/src/main/java/biomesoplenty/api/biome/BOPBiomes.java` | **65 个 `ResourceKey<Biome>` 常量与注册名字符串**（`new ResourceLocation("biomesoplenty", name)`）→ 即 `biomesoplenty:xxx` 注册 ID |
| `src/main/java/biomesoplenty/init/ModBiomes.java` | 65 条 `registerBiome(...)` 注册清单、`BiomeDictionary` 类型、村民类型 |
| `src/main/java/biomesoplenty/common/biome/BOPOverworldBiomes.java` | 每个主世界/洞穴群系的 `Precipitation` / `BiomeCategory` / `temperature(F)` / `downfall(F)` 常量 |
| `src/main/java/biomesoplenty/common/biome/BOPNetherBiomes.java` | 5 个下界群系：`Precipitation.NONE`、`BiomeCategory.NETHER`、`temperature(2.0F)`、`downfall(0.0F)` |

核对方式：以 `gh-proxy.com` 代理读取 `raw.githubusercontent.com` 原文（本机 hosts 屏蔽了 github 域名），并对 Java 源码做正则解析（方法名 → `return biome(...)` 参数）得到温度/降水/类别。

辅助交叉核对（非 ID/温度来源，仅用于确认名称与存在性）：

- BOP 官方 Wiki「Biomes List / Biomes Index」（wiki.gg）：<https://biomesoplenty.wiki.gg/wiki/Biomes_List>、<https://biomesoplenty.wiki.gg/wiki/Biomes_Index>
- Modrinth API 的 1.18.2 版本列表（确认 1.18.2 = `16.0.0.x`，最新 `16.0.0.134`，且 15.0.0.100→16.0.0.134 的变更日志中**没有新增/删除生物群系**，只有「Rainforest Cliffs 改名为 Rocky Rainforest」「移除 Mediterranean Lakes」等）：
  <https://api.modrinth.com/v2/project/biomes-o-plenty/version?game_versions=%5B%221.18.2%22%5D>

### 1.2 原版 Minecraft 1.18.2

| 来源 | 核对内容 | 核对程度 |
| --- | --- | --- |
| misode/mcmeta 数据包 JSON（由官方 1.18.2 客户端/服务端数据生成）：`cdn.jsdelivr.net/gh/misode/mcmeta@1.18.2-data-json/data/minecraft/worldgen/biome/*.json` | **61 个群系 ID 清单**（目录列举）+ 其中 **35 个群系**的 `precipitation` / `temperature` / `downfall` / `category` 原文数值 | 源码级（datapack 原文） |
| Minecraft Wiki「Biome → List of biome climates」表（Overworld）：`https://minecraft.wiki/api.php?action=parse&page=Biome&section=29&prop=wikitext&format=json` | 其余 26 个群系的 Base temperature / Downfall / Precipitation | Wiki 表格（当前版本），**未逐条对 1.18.2 源码** |

交叉校验结果：Wiki 表与源码抽样 **35/35 一致**，仅发现 **1 处冲突**——`deep_frozen_ocean`：Wiki 表标 Base temperature `0.0`，而 1.18.2 数据包原文为 `"temperature": 0.5` + `"temperature_modifier": "frozen"`、`"precipitation": "rain"`。**本文以数据包原文为准**（见 §5）。

---

## 2. 气候大类与「正常温度范围 °C」派生规则（重要）

- `climateClass`、`tempMinC`、`tempMaxC` 三列**不是**游戏内或模组内数据，而是本文为了天气判断而定义的**启发式映射**，规则完全公开、可复算：

### 2.1 由 MC 基础温度（`baseTemperature`）决定基础大类

| 条件 | climateClass | tempMinC | tempMaxC |
| --- | --- | --- | --- |
| `t ≤ 0.0`（或降水为 SNOW） | `SNOWY` 雪原 | −35 | 5 |
| `0.0 < t ≤ 0.25` | `COLD` 寒冷/寒温带 | −15 | 12 |
| `0.25 < t ≤ 0.85` | `TEMPERATE` 温带 | 0 | 25 |
| `0.85 < t ≤ 1.05` | `WARM_TEMPERATE` 暖温带 | 10 | 32 |
| `t > 1.05` | `TROPICAL` 热带 | 18 | 38 |

### 2.2 覆盖规则（优先级高于 2.1）

| 条件 | climateClass | tempMinC | tempMaxC |
| --- | --- | --- | --- |
| 降水 `NONE` 且 `t ≥ 1.5` | `ARID` 干旱热漠 | 25 | 48 |
| 降水 `NONE` 且 `0.8 ≤ t < 1.5` | `SEMI_ARID` 半干旱（草原/灌丛/稀树草原） | 18 | 38 |
| 降水 `NONE` 且 `t < 0.8` | `COLD_ARID` 寒漠 | −20 | 15 |
| BOP `BiomeCategory.SWAMP`（或下界/主世界沼泽类）且带为 COLD | `COLD_SWAMP` 寒冷沼泽 | −10 | 15 |
| 同上且带为 TEMPERATE | `TEMPERATE_SWAMP` 温带沼泽 | 5 | 28 |
| 同上且带为 WARM/TROPICAL（`BiomeDictionary` 含 `HOT`） | `TROPICAL_SWAMP` 热带沼泽 | 18 | 36 |
| `BiomeDictionary` 含 `HOT` 且 `t ≥ 0.9`（BOP） | `TROPICAL` | 18 | 38 |
| BOP `BiomeCategory.EXTREME_HILLS`；原版 `mountain` 类 | `MOUNTAIN` 山地 | −20 | 20 |
| BOP `BiomeCategory.UNDERGROUND`；原版 `underground` 类（洞穴） | `CAVE` 洞穴 | 5 | 20 |
| BOP `BiomeCategory.NETHER`；原版 `nether` 类 | `NETHER` 下界 | 40 | 90 |
| 原版 `the_end` 类 | `END` 末地 | −20 | 25 |
| 原版 `mushroom` 类 | `MUSHROOM` 蘑菇岛 | 5 | 25 |
| 海洋/河流/海滩类（见下） | `OCEAN_*` / `COASTAL` | 见 §2.3 | 见 §2.3 |
| BOP `volcano` / `volcanic_plains` | `VOLCANIC` 火山 | 25 | 60 |
| 原版 `the_void` | `OTHER` 其他（仅超平坦预设） | 0 | 25 |

### 2.3 海洋与海岸（原版所有非冰冻海洋的 baseTemperature 都是 `0.5`，无法用温度区分冷暖，故按 ID 语义分类）

| climateClass | tempMinC | tempMaxC | 适用 |
| --- | --- | --- | --- |
| `OCEAN_FROZEN` | −2 | 5 | `frozen_ocean`、`deep_frozen_ocean` |
| `OCEAN_COLD` | 2 | 15 | `cold_ocean`、`deep_cold_ocean` |
| `OCEAN_TEMPERATE` | 8 | 25 | `ocean`、`deep_ocean` |
| `OCEAN_WARM` | 15 | 32 | `lukewarm_ocean`、`deep_lukewarm_ocean`、`warm_ocean`（25~35 更贴合，见备注） |
| `COASTAL` | 5 | 32 | `beach`、`dune_beach`、`stony_shore` |

### 2.4 与仓库现有 `com.weathersync.core.climate.ClimateClass` 枚举的对照

本仓库已有枚举 `core/src/main/java/com/weathersync/core/climate/ClimateClass.java`（TROPICAL / SUBTROPICAL / ARID / MEDITERRANEAN / TEMPERATE / CONTINENTAL / SUBPOLAR / POLAR / OCEANIC / COASTAL / WETLAND / MOUNTAIN / PLATEAU / VOLCANIC / MUSHROOM / CAVE / NETHER / END / VOID / UNKNOWN）。本文 §2.1~2.3 的分类更细（把「雪原沼泽」「温带沼泽」「寒漠」「海洋冷暖」拆开），可按下表映射；`ClimateClass.parse()` 的别名表已能识别其中一部分（`SNOWY→POLAR`、`SWAMP→WETLAND`、`OCEAN→OCEANIC` 等），其余需显式映射：

| 本文 climateClass | 建议映射到 ClimateClass | 备注 |
| --- | --- | --- |
| `TROPICAL` | `TROPICAL` | |
| `TROPICAL_SWAMP` | `WETLAND` | 也可保留 TROPICAL 并调高 humidity |
| `WARM_TEMPERATE` | `SUBTROPICAL` | |
| `TEMPERATE` | `TEMPERATE` | |
| `TEMPERATE_SWAMP` | `WETLAND` | |
| `COLD` | `SUBPOLAR` | |
| `COLD_SWAMP` | `SUBPOLAR` 或 `WETLAND` | 寒冷沼泽，建议 SUBPOLAR + 高 humidity |
| `SNOWY` | `POLAR` | |
| `SNOWY_SWAMP` | `POLAR` | |
| `ARID` | `ARID` | |
| `SEMI_ARID` | `SUBTROPICAL` | 稀树草原/灌丛；若想体现「不下雨」请改用 precipitation=NONE |
| `COLD_ARID` | `ARID` 或 `POLAR` | BOP `cold_desert`：降水 NONE、温度带寒冷 |
| `MOUNTAIN` | `MOUNTAIN` | |
| `VOLCANIC` | `VOLCANIC` | |
| `COASTAL` | `COASTAL` | |
| `OCEAN_FROZEN` | `POLAR` | 冰冻海洋 |
| `OCEAN_COLD` | `SUBPOLAR` | |
| `OCEAN_TEMPERATE` | `OCEANIC` | |
| `OCEAN_WARM` | `SUBTROPICAL` 或 `OCEANIC` | 温暖海洋 |
| `CAVE` | `CAVE` | |
| `NETHER` | `NETHER` | |
| `END` | `END` | |
| `MUSHROOM` | `MUSHROOM` | |
| `OTHER` | `VOID` | 仅 `minecraft:the_void`（超平坦预设） |

---

## 3. BOP 1.18.2 生物群系表（65 条）

- `原版基础温度` = 源码 `Biome.BiomeBuilder.temperature(...)` 常量（BOP 调用 MC 的 `Biome`，因此该值就是 MC 意义的 `baseTemperature`）。
- 「来源/置信度」栏：`S` = 源码核验（上表 4 个文件，提交 `c3252ad`），`high`；`dict` = 该群系在 `ModBiomes.java` 中的 `BiomeDictionary` 类型（用于大类判定）。

| ID | 气候大类 | 正常温度范围°C | 降水 | 原版基础温度 | 来源/置信度 |
| --- | --- | --- | --- | --- | --- |
| `biomesoplenty:bamboo_grove` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.6 | S / high（dict: WET,LUSH,RARE,FOREST） |
| `biomesoplenty:bayou` | TROPICAL_SWAMP 热带沼泽 | 18 ~ 36 | RAIN | 0.95 | S / high（dict: HOT,WET,SWAMP） |
| `biomesoplenty:bog` | COLD_SWAMP 寒冷沼泽 | −10 ~ 15 | RAIN | 0.2 | S / high（dict: COLD,SPARSE,WET,SWAMP） |
| `biomesoplenty:boreal_forest` | TEMPERATE 温带（针叶） | 0 ~ 25 | RAIN | 0.4 | S / high（dict: FOREST） |
| `biomesoplenty:cherry_blossom_grove` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.6 | S / high（dict: WET,LUSH,RARE,FOREST） |
| `biomesoplenty:clover_patch` | TEMPERATE 温带草原 | 0 ~ 25 | RAIN | 0.6 | S / high（dict: SPARSE,LUSH,PLAINS,WET） |
| `biomesoplenty:cold_desert` | COLD_ARID 寒漠 | −20 ~ 15 | NONE | 0.25 | S / high（dict: COLD,SPARSE,SNOWY,DRY） |
| `biomesoplenty:coniferous_forest` | TEMPERATE 温带针叶林 | 0 ~ 25 | RAIN | 0.45 | S / high（dict: DENSE,CONIFEROUS,FOREST） |
| `biomesoplenty:crag` | MOUNTAIN 山地 | −20 ~ 20 | RAIN | 0.6 | S / high（category EXTREME_HILLS） |
| `biomesoplenty:dead_forest` | COLD 寒冷 | −15 ~ 12 | RAIN | 0.2 | S / high（dict: COLD,SPARSE,DEAD,RARE,FOREST） |
| `biomesoplenty:dryland` | SEMI_ARID 半干旱 | 18 ~ 38 | RAIN | 0.85 | S / high（dict: HOT,SPARSE,DRY,SAVANNA,SANDY） |
| `biomesoplenty:dune_beach` | COASTAL 海岸 | 5 ~ 32 | RAIN | 0.7 | S / high（dict: BEACH,LUSH） |
| `biomesoplenty:field` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.4 | S / high（category TAIGA） |
| `biomesoplenty:fir_clearing` | TEMPERATE 温带针叶林 | 0 ~ 25 | RAIN | 0.45 | S / high |
| `biomesoplenty:floodplain` | TROPICAL_SWAMP 热带沼泽 | 18 ~ 36 | RAIN | 1.2 | S / high（dict: HOT,WET,JUNGLE,LUSH,SWAMP） |
| `biomesoplenty:forested_field` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.4 | S / high（= `field(true)`，同方法同数值） |
| `biomesoplenty:fungal_jungle` | TROPICAL 热带（蘑菇） | 18 ~ 38 | RAIN | 0.9 | S / high（dict: HOT,WET,JUNGLE,MUSHROOM,MAGICAL） |
| `biomesoplenty:grassland` | TEMPERATE 温带草原 | 0 ~ 25 | RAIN | 0.6 | S / high |
| `biomesoplenty:highland` | MOUNTAIN 山地/高原 | −20 ~ 20 | RAIN | 0.6 | S / high（category EXTREME_HILLS） |
| `biomesoplenty:highland_moor` | MOUNTAIN 山地（湿草甸） | −20 ~ 20 | RAIN | 0.6 | S / high（= `highland(true)`） |
| `biomesoplenty:jade_cliffs` | MOUNTAIN 山地森林 | −20 ~ 20 | RAIN | 0.8 | S / high（dict 含 MOUNTAIN,FOREST） |
| `biomesoplenty:lavender_field` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.8 | S / high |
| `biomesoplenty:lavender_forest` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.8 | S / high（= `lavenderField(true)`，与 field 同方法同数值、category 同为 PLAINS） |
| `biomesoplenty:lush_desert` | TROPICAL 热带（稀树） | 18 ~ 38 | RAIN | 0.9 | S / high（dict: HOT,SPARSE,SAVANNA,LUSH,RARE,SANDY） |
| `biomesoplenty:lush_savanna` | TROPICAL 热带草原 | 18 ~ 38 | RAIN | 0.9 | S / high（dict: HOT,SPARSE,SAVANNA,LUSH,RARE,PLAINS） |
| `biomesoplenty:maple_woods` | COLD 寒冷 | −15 ~ 12 | RAIN | 0.25 | S / high（dict: COLD,DENSE,CONIFEROUS,FOREST） |
| `biomesoplenty:marsh` | TEMPERATE_SWAMP 温带沼泽 | 5 ~ 28 | RAIN | 0.65 | S / high（category SWAMP） |
| `biomesoplenty:mediterranean_forest` | TEMPERATE 温带（地中海） | 0 ~ 25 | RAIN | 0.8 | S / high（downfall 仅 0.275） |
| `biomesoplenty:muskeg` | SNOWY_SWAMP 雪原沼泽 | −30 ~ 5 | SNOW | −0.25 | S / high（category ICY） |
| `biomesoplenty:mystic_grove` | TEMPERATE 温带（魔法森林） | 0 ~ 25 | RAIN | 0.7 | S / high（dict: WET,LUSH,MAGICAL,RARE,FOREST） |
| `biomesoplenty:old_growth_dead_forest` | TEMPERATE 温带（寒温带边缘） | 0 ~ 25 | RAIN | 0.3 | S / high（dict: COLD,DEAD,RARE,FOREST） |
| `biomesoplenty:old_growth_woodland` | TEMPERATE 温带森林 | 0 ~ 25 | RAIN | 0.8 | S / high（= `woodland(true)`） |
| `biomesoplenty:ominous_woods` | TEMPERATE 温带（阴森） | 0 ~ 25 | RAIN | 0.6 | S / high（dict: COLD,WET,CONIFEROUS,SPOOKY,RARE,FOREST） |
| `biomesoplenty:orchard` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.8 | S / high |
| `biomesoplenty:origin_valley` | TEMPERATE 温带（复古） | 0 ~ 25 | RAIN | 0.6 | S / high（category NONE，dict: RARE） |
| `biomesoplenty:pasture` | TEMPERATE 温带草原 | 0 ~ 25 | RAIN | 0.8 | S / high |
| `biomesoplenty:prairie` | TEMPERATE 温带草原 | 0 ~ 25 | RAIN | 0.8 | S / high |
| `biomesoplenty:pumpkin_patch` | TEMPERATE 温带（秋色） | 0 ~ 25 | RAIN | 0.4 | S / high |
| `biomesoplenty:rainbow_hills` | SNOWY 雪原 | −35 ~ 5 | SNOW | −0.25 | S / high（dict 含 SNOWY,MAGICAL） |
| `biomesoplenty:rainforest` | TROPICAL 热带雨林 | 18 ~ 38 | RAIN | 1.2 | S / high |
| `biomesoplenty:redwood_forest` | TEMPERATE 温带雨林 | 0 ~ 25 | RAIN | 0.8 | S / high |
| `biomesoplenty:rocky_rainforest` | TROPICAL 热带雨林（山地） | 18 ~ 38 | RAIN | 1.2 | S / high（1.18.2 中「Rainforest Cliffs」已改名为 `rocky_rainforest`） |
| `biomesoplenty:rocky_shrubland` | TEMPERATE 温带灌丛 | 0 ~ 25 | RAIN | 0.6 | S / high（downfall 仅 0.05） |
| `biomesoplenty:scrubland` | SEMI_ARID 半干旱灌丛 | 18 ~ 38 | RAIN | 1.1 | S / high（dict: HOT,SPARSE,DRY,SAVANNA） |
| `biomesoplenty:seasonal_forest` | TEMPERATE 温带（季节林） | 0 ~ 25 | RAIN | 0.4 | S / high |
| `biomesoplenty:shrubland` | TEMPERATE 温带灌丛 | 0 ~ 25 | RAIN | 0.6 | S / high |
| `biomesoplenty:snowy_coniferous_forest` | SNOWY 雪原针叶林 | −35 ~ 5 | SNOW | −0.25 | S / high（= `coniferousForest(true)`，category ICY） |
| `biomesoplenty:snowy_fir_clearing` | SNOWY 雪原针叶林 | −35 ~ 5 | SNOW | −0.25 | S / high（= `firClearing(true)`，category ICY） |
| `biomesoplenty:snowy_maple_woods` | SNOWY 雪原森林 | −35 ~ 5 | SNOW | −0.25 | S / high（= `mapleWoods(true)`，category ICY） |
| `biomesoplenty:tropics` | TROPICAL 热带海滨 | 18 ~ 38 | RAIN | 0.95 | S / high（dict: HOT,WET,JUNGLE,LUSH,RARE） |
| `biomesoplenty:tundra` | COLD 寒冷苔原 | −15 ~ 12 | RAIN | 0.2 | S / high（dict: COLD,SPARSE,DEAD,PLAINS） |
| `biomesoplenty:volcanic_plains` | VOLCANIC 火山 | 25 ~ 60 | NONE | 0.95 | S / high（黑沙/熔岩，category DESERT） |
| `biomesoplenty:volcano` | VOLCANIC 火山 | 25 ~ 60 | NONE | 0.95 | S / high（含地表熔岩湖，category DESERT） |
| `biomesoplenty:wasteland` | ARID 干旱热漠 | 25 ~ 48 | RAIN | 2.0 | S / high（dict: HOT,SPARSE,DRY,DEAD,WASTELAND） |
| `biomesoplenty:wetland` | TEMPERATE_SWAMP 温带沼泽 | 5 ~ 28 | RAIN | 0.6 | S / high（category SWAMP） |
| `biomesoplenty:wooded_scrubland` | SEMI_ARID 半干旱灌丛 | 18 ~ 38 | RAIN | 1.1 | S / high（= `scrubland(true)`） |
| `biomesoplenty:wooded_wasteland` | ARID 干旱热漠（有树） | 25 ~ 48 | RAIN | 2.0 | S / high（= `wasteland(true)`） |
| `biomesoplenty:woodland` | TEMPERATE 温带森林 | 0 ~ 25 | RAIN | 0.8 | S / high |
| `biomesoplenty:glowing_grotto` | CAVE 洞穴 | 5 ~ 20 | RAIN | 0.5 | S / high（category UNDERGROUND） |
| `biomesoplenty:spider_nest` | CAVE 洞穴 | 5 ~ 20 | RAIN | 0.5 | S / high（category UNDERGROUND） |
| `biomesoplenty:crystalline_chasm` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | S / high（category NETHER，dict: NETHER,HOT,DRY,MAGICAL） |
| `biomesoplenty:erupting_inferno` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | S / high（dict: NETHER,HOT,DRY） |
| `biomesoplenty:undergrowth` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | S / high（dict: NETHER,HOT,DRY,FOREST） |
| `biomesoplenty:visceral_heap` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | S / high（dict: NETHER,HOT,DRY） |
| `biomesoplenty:withered_abyss` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | S / high（dict: NETHER,HOT,DRY,VOID） |

---

## 4. 原版 Minecraft 1.18.2 生物群系表（61 条）

- 「核对」栏：`源码` = 已从 1.18.2 数据包 JSON 原文逐字段读取（35 条）；`wiki` = 取自 Wiki 气候表（26 条，未逐条对源码，见 §5）。
- `原版基础温度` 单位为 MC 游戏温度单位（`Biome.getBaseTemperature()`）；原版所有非冰冻海洋/河流均为 `0.5`。

| ID | 气候大类 | 正常温度范围°C | 降水 | 原版基础温度 | 核对 |
| --- | --- | --- | --- | --- | --- |
| `minecraft:badlands` | ARID 干旱热漠 | 25 ~ 48 | NONE | 2.0 | 源码 |
| `minecraft:bamboo_jungle` | TROPICAL 热带 | 18 ~ 38 | RAIN | 0.95 | 源码 |
| `minecraft:basalt_deltas` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | 源码 |
| `minecraft:beach` | COASTAL 海岸 | 5 ~ 32 | RAIN | 0.8 | wiki |
| `minecraft:birch_forest` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.6 | wiki |
| `minecraft:cold_ocean` | OCEAN_COLD 寒冷海洋 | 2 ~ 15 | RAIN | 0.5 | wiki |
| `minecraft:crimson_forest` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | wiki |
| `minecraft:dark_forest` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.7 | wiki |
| `minecraft:deep_cold_ocean` | OCEAN_COLD 寒冷海洋 | 2 ~ 15 | RAIN | 0.5 | wiki |
| `minecraft:deep_frozen_ocean` | OCEAN_FROZEN 冰冻海洋 | −2 ~ 5 | SNOW | 0.5 | 源码 ※ |
| `minecraft:deep_lukewarm_ocean` | OCEAN_WARM 温暖海洋 | 15 ~ 32 | RAIN | 0.5 | wiki |
| `minecraft:deep_ocean` | OCEAN_TEMPERATE 温带海洋 | 8 ~ 25 | RAIN | 0.5 | wiki |
| `minecraft:desert` | ARID 干旱热漠 | 25 ~ 48 | NONE | 2.0 | 源码 |
| `minecraft:dripstone_caves` | CAVE 洞穴 | 5 ~ 20 | RAIN | 0.8 | 源码 |
| `minecraft:end_barrens` | END 末地 | −20 ~ 25 | NONE | 0.5 | wiki |
| `minecraft:end_highlands` | END 末地 | −20 ~ 25 | NONE | 0.5 | wiki |
| `minecraft:end_midlands` | END 末地 | −20 ~ 25 | NONE | 0.5 | wiki |
| `minecraft:eroded_badlands` | ARID 干旱热漠 | 25 ~ 48 | NONE | 2.0 | wiki |
| `minecraft:flower_forest` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.7 | wiki |
| `minecraft:forest` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.7 | wiki |
| `minecraft:frozen_ocean` | OCEAN_FROZEN 冰冻海洋 | −2 ~ 5 | SNOW | 0.0 | 源码 |
| `minecraft:frozen_peaks` | SNOWY 雪原峰 | −35 ~ 5 | SNOW | −0.7 | 源码 |
| `minecraft:frozen_river` | SNOWY 冰冻河流 | −35 ~ 5 | SNOW | 0.0 | 源码 |
| `minecraft:grove` | SNOWY 雪原林地 | −35 ~ 5 | SNOW | −0.2 | 源码 |
| `minecraft:ice_spikes` | SNOWY 雪原 | −35 ~ 5 | SNOW | 0.0 | 源码 |
| `minecraft:jagged_peaks` | SNOWY 雪原峰 | −35 ~ 5 | SNOW | −0.7 | 源码 |
| `minecraft:jungle` | TROPICAL 热带 | 18 ~ 38 | RAIN | 0.95 | 源码 |
| `minecraft:lukewarm_ocean` | OCEAN_WARM 温暖海洋 | 15 ~ 32 | RAIN | 0.5 | wiki |
| `minecraft:lush_caves` | CAVE 洞穴 | 5 ~ 20 | RAIN | 0.5 | 源码 |
| `minecraft:meadow` | MOUNTAIN 山地草甸 | −20 ~ 20 | RAIN | 0.5 | 源码 |
| `minecraft:mushroom_fields` | MUSHROOM 蘑菇岛 | 5 ~ 25 | RAIN | 0.9 | 源码 |
| `minecraft:nether_wastes` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | 源码 |
| `minecraft:ocean` | OCEAN_TEMPERATE 温带海洋 | 8 ~ 25 | RAIN | 0.5 | 源码 |
| `minecraft:old_growth_birch_forest` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.6 | wiki |
| `minecraft:old_growth_pine_taiga` | TEMPERATE 温带（针叶） | 0 ~ 25 | RAIN | 0.3 | 源码 |
| `minecraft:old_growth_spruce_taiga` | COLD 寒冷 | −15 ~ 12 | RAIN | 0.25 | wiki |
| `minecraft:plains` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.8 | 源码 |
| `minecraft:river` | TEMPERATE 温带河流 | 0 ~ 25 | RAIN | 0.5 | 源码 |
| `minecraft:savanna` | SEMI_ARID 稀树草原 | 18 ~ 38 | NONE | 2.0 | 源码 |
| `minecraft:savanna_plateau` | SEMI_ARID 稀树草原 | 18 ~ 38 | NONE | 2.0 | 源码 |
| `minecraft:small_end_islands` | END 末地 | −20 ~ 25 | NONE | 0.5 | wiki |
| `minecraft:snowy_beach` | SNOWY 雪原海岸 | −35 ~ 5 | SNOW | 0.05 | 源码 |
| `minecraft:snowy_plains` | SNOWY 雪原 | −35 ~ 5 | SNOW | 0.0 | 源码 |
| `minecraft:snowy_slopes` | SNOWY 雪坡 | −35 ~ 5 | SNOW | −0.3 | 源码 |
| `minecraft:snowy_taiga` | SNOWY 雪原针叶林 | −35 ~ 5 | SNOW | −0.5 | 源码 |
| `minecraft:soul_sand_valley` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | wiki |
| `minecraft:sparse_jungle` | TROPICAL 热带 | 18 ~ 38 | RAIN | 0.95 | 源码 |
| `minecraft:stony_peaks` | MOUNTAIN 山地 | −20 ~ 20 | RAIN | 1.0 | 源码 |
| `minecraft:stony_shore` | COASTAL 石岸 | 5 ~ 32 | RAIN | 0.2 | 源码 |
| `minecraft:sunflower_plains` | TEMPERATE 温带 | 0 ~ 25 | RAIN | 0.8 | wiki |
| `minecraft:swamp` | TEMPERATE_SWAMP 温带沼泽 | 5 ~ 28 | RAIN | 0.8 | 源码 |
| `minecraft:taiga` | COLD 寒冷针叶林 | −15 ~ 12 | RAIN | 0.25 | 源码 |
| `minecraft:the_end` | END 末地 | −20 ~ 25 | NONE | 0.5 | 源码 |
| `minecraft:the_void` | OTHER 其他（仅超平坦预设） | 0 ~ 25 | NONE | 0.5 | 源码 |
| `minecraft:warm_ocean` | OCEAN_WARM 温暖海洋 | 18 ~ 35 | RAIN | 0.5 | wiki |
| `minecraft:warped_forest` | NETHER 下界 | 40 ~ 90 | NONE | 2.0 | wiki |
| `minecraft:windswept_forest` | MOUNTAIN 山地森林 | −20 ~ 20 | RAIN | 0.2 | wiki |
| `minecraft:windswept_gravelly_hills` | MOUNTAIN 山地砾石 | −20 ~ 20 | RAIN | 0.2 | wiki |
| `minecraft:windswept_hills` | MOUNTAIN 山地 | −20 ~ 20 | RAIN | 0.2 | wiki |
| `minecraft:windswept_savanna` | SEMI_ARID 稀树草原（破碎） | 18 ~ 38 | NONE | 2.0 | 源码 |
| `minecraft:wooded_badlands` | ARID 干旱热漠（有树） | 25 ~ 48 | NONE | 2.0 | wiki |

※ `deep_frozen_ocean` 数据包原文为 `"precipitation": "rain"` + `"temperature_modifier": "frozen"`，`"temperature": 0.5`；游戏内因 frozen 修正而结冰/降雪，故本表降水记为 `SNOW`、基础温度记为 `0.5`。

---

## 5. 不确定 / 待核实

1. **BOP 1.18.2 的精确构建版本无法与源码一一对应（中等风险）**
   - 上游无 `1.18.2` 分支/标签，本文用的是 1.19 移植提交的父提交 `c3252ad`（2022-06-08，对应 1.18.2 `16.0.0.x`）。
   - 1.18.2 还有 `16.0.0.109 → 16.0.0.134`（至 2022-06-11）等构建，其变更日志只涉及「结构用群系标签、Forge 事件注册颜色、Cold Desert 加石头衬层、构建脚本」等，**据变更日志未增删生物群系、未改温度常量**；但无法 100% 排除细微差异。若需绝对精确，应解包 `BiomesOPlenty-1.18.2-16.0.0.134.jar` 并用 `/biomesoplenty`/调试命令核对。
2. **`climateClass` 与 `tempMinC/tempMaxC` 是派生值，不是官方数据**（§2 规则）。它们代表「该群系在地球气候类比下的合理气温区间」，用于判断预报数据是否离谱；请勿当作游戏内数值。若你更信任别的映射刻度（如 Project Atmosphere 的刻度），只需替换 §2 的区间表。
3. **边界群系的大类判定属主观（仅影响 climateClass，不影响 baseTemperature）**：
   - `biomesoplenty:bog`（0.2）、`maple_woods`（0.25）、`tundra`（0.2）、`dead_forest`（0.2）、`old_growth_dead_forest`（0.3）、`ominous_woods`（0.6）落在寒冷/温带分界附近，字典类型（COLD）与温度带（TEMPERATE）给出不同答案。
   - `biomesoplenty:lush_desert` / `lush_savanna`（均 0.9、降水 RAIN、字典含 HOT+LUSH）被归为 TROPICAL，但它们同时带 SAVANNA/SANDY；若你的天气模型需要「旱季」，这两条应特殊处理。
   - `biomesoplenty:volcanic_plains` / `volcano` 的 25~60°C 是「地表/火山口」估计，MC 基础温度仍只有 0.95。
   - `minecraft:meadow`（0.5）与 `stony_peaks`（1.0）按 `mountain` 类归 MOUNTAIN（−20~20），因此温度范围比其基础温度暗示的更宽。
4. **原版 26 条温度来自 Wiki 气候表，未逐条对源码**（`beach`、`birch_forest`、`cold_ocean`、`crimson_forest`、`dark_forest`、`deep_cold_ocean`、`deep_lukewarm_ocean`、`deep_ocean`、`end_barrens`、`end_highlands`、`end_midlands`、`eroded_badlands`、`flower_forest`、`forest`、`lukewarm_ocean`、`old_growth_birch_forest`、`old_growth_spruce_taiga`、`small_end_islands`、`soul_sand_valley`、`sunflower_plains`、`warm_ocean`、`warped_forest`、`windswept_forest`、`windswept_gravelly_hills`、`windswept_hills`、`wooded_badlands`）。
   - 抽样 35 条与 1.18.2 数据包原文**完全一致**，说明该表在 1.18→当前版本间是稳定的；唯一例外是 `deep_frozen_ocean`（Wiki 标 0.0，实际 0.5 + frozen 修正）。
   - 若要求全量源码级核对，可继续拉取 `mcmeta@1.18.2-data-json` 中这 26 个 `worldgen/biome/*.json` 的 `temperature`/`precipitation`/`category` 字段。
5. **Wiki 表格与源码冲突记录**：`minecraft:deep_frozen_ocean` 基础温度（Wiki 0.0 ↔ 源码 0.5，带 `temperature_modifier: frozen`）。本文取源码值，并在降水列标注为 SNOW。
6. **不要在 1.18.2 清单里出现的常见误列项**（来自更高版本或旧版本，已核对为 1.18.2 不存在）：
   - 1.19+ / 1.20+ 才有：`biomesoplenty:end_wilds`、`end_reef`、`end_corruption`、`seasonal_orchard`、`snowblossom_grove`、`auroral_garden`、`moor`（作为独立 ID）。
   - 1.18.x 早期已移除或改名：`biomesoplenty:mediterranean_lakes`（15.0.0.100 移除）、`biomesoplenty:rainforest_cliffs`（改名为 `rocky_rainforest`）、`burnt_forest`、`gravel_beach`（更早移除）。
   - 原版侧：1.18.2 没有 `minecraft:mangrove_swamp`（1.19）、`cherry_grove`（1.20）、`pale_garden`（1.21.4）、`deep_dark`（1.19）。
7. **未纳入本文的数据**：BOP 自身的 TerraBlender 气候参数（`BOPOverworldBiomeBuilder.java` 中的 temperature/humidity/continentalness/erosion 六维表）以及 `BOPClimate.java` 噪声参数未展开核对——如果你的映射表需要「群系出现概率/邻近性」而非温度，应另行核对这两个文件（同为提交 `c3252ad`）。
8. **与仓库现有 `versions/forge-common/src/main/resources/data/weathersync/climate/vanilla.json` 的交叉核对：发现 3 处数值可疑，建议修正**
   - 该文件中 `minecraft:savanna` 的 `baseTemperature` 写为 **1.2**、`minecraft:savanna_plateau` 写为 **1.0**、`minecraft:windswept_savanna` 写为 **1.1**；其中 `1.2` / `1.0` 与 Wiki 表格中标注的 **Bedrock 版**数值（`savanna` BE 1.2、`savanna_plateau` BE 1.0）完全吻合，`1.1` 疑为同类来源（`windswept_savanna` 的 BE 值本文未核对）。**Minecraft Java 1.18.2 数据包原文三条均为 `2.0`**，且 `precipitation` 为 `"none"`（不是 `"rain"`）。证据：`mcmeta@1.18.2-data-json` 的 `savanna.json` / `savanna_plateau.json` / `windswept_savanna.json` 已逐字段读取（见 §1.2）。
   - 该文件其余已抽样核对的部分与 1.18.2 源码一致（含 `deep_frozen_ocean` = 0.5、`ice_spikes` = 0.0、`grove` = -0.2、`snowy_taiga` = -0.5、`stony_peaks` = 1.0、`mushroom_fields` = 0.9、`swamp` = 0.8、`plains` = 0.8、`desert` = 2.0 等），故问题应**仅限这三条**。
   - 字段风格差异（不影响数值正确性，但影响解析）：该文件用大写 `"confidence": "HIGH"/"MEDIUM"`，本文按要求用小写 `"high"/"medium"`；该文件还有 `humidity`/`windiness`/`thunderBias`/`source` 字段，而本文严格只给 7 个字段。若要用本文数据补全 BOP 部分，请统一大小写，并按需补上那三个数值（本文不提供：任务未要求，且无可靠来源，不编造）。
   - 另外注意：`ClimateClass.parse()` 的别名表会把本文的 `SNOWY→POLAR`、`SWAMP→WETLAND`、`OCEAN→OCEANIC`、`JUNGLE→TROPICAL` 等自动转换，但本文的复合类名（`SNOWY_SWAMP`/`TEMPERATE_SWAMP`/`COLD_ARID`/`SEMI_ARID`/`WARM_TEMPERATE`/`OCEAN_*`）**不在别名表内**，会落到 `UNKNOWN`；请按 §2.4 显式映射。

---

## 6. 程序可读 JSON

```json
{"biomes":[
{"id":"biomesoplenty:bamboo_grove","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:bayou","climateClass":"TROPICAL_SWAMP","tempMinC":18,"tempMaxC":36,"precipitation":"RAIN","baseTemperature":0.95,"confidence":"high"},
{"id":"biomesoplenty:bog","climateClass":"COLD_SWAMP","tempMinC":-10,"tempMaxC":15,"precipitation":"RAIN","baseTemperature":0.2,"confidence":"high"},
{"id":"biomesoplenty:boreal_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.4,"confidence":"high"},
{"id":"biomesoplenty:cherry_blossom_grove","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:clover_patch","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:cold_desert","climateClass":"COLD_ARID","tempMinC":-20,"tempMaxC":15,"precipitation":"NONE","baseTemperature":0.25,"confidence":"high"},
{"id":"biomesoplenty:coniferous_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.45,"confidence":"high"},
{"id":"biomesoplenty:crag","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:dead_forest","climateClass":"COLD","tempMinC":-15,"tempMaxC":12,"precipitation":"RAIN","baseTemperature":0.2,"confidence":"high"},
{"id":"biomesoplenty:dryland","climateClass":"SEMI_ARID","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.85,"confidence":"high"},
{"id":"biomesoplenty:dune_beach","climateClass":"COASTAL","tempMinC":5,"tempMaxC":32,"precipitation":"RAIN","baseTemperature":0.7,"confidence":"high"},
{"id":"biomesoplenty:field","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.4,"confidence":"high"},
{"id":"biomesoplenty:fir_clearing","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.45,"confidence":"high"},
{"id":"biomesoplenty:floodplain","climateClass":"TROPICAL_SWAMP","tempMinC":18,"tempMaxC":36,"precipitation":"RAIN","baseTemperature":1.2,"confidence":"high"},
{"id":"biomesoplenty:forested_field","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.4,"confidence":"high"},
{"id":"biomesoplenty:fungal_jungle","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.9,"confidence":"high"},
{"id":"biomesoplenty:grassland","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:highland","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:highland_moor","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:jade_cliffs","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:lavender_field","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:lavender_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:lush_desert","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.9,"confidence":"high"},
{"id":"biomesoplenty:lush_savanna","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.9,"confidence":"high"},
{"id":"biomesoplenty:maple_woods","climateClass":"COLD","tempMinC":-15,"tempMaxC":12,"precipitation":"RAIN","baseTemperature":0.25,"confidence":"high"},
{"id":"biomesoplenty:marsh","climateClass":"TEMPERATE_SWAMP","tempMinC":5,"tempMaxC":28,"precipitation":"RAIN","baseTemperature":0.65,"confidence":"high"},
{"id":"biomesoplenty:mediterranean_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:muskeg","climateClass":"SNOWY_SWAMP","tempMinC":-30,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.25,"confidence":"high"},
{"id":"biomesoplenty:mystic_grove","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.7,"confidence":"high"},
{"id":"biomesoplenty:old_growth_dead_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.3,"confidence":"high"},
{"id":"biomesoplenty:old_growth_woodland","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:ominous_woods","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:orchard","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:origin_valley","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:pasture","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:prairie","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:pumpkin_patch","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.4,"confidence":"high"},
{"id":"biomesoplenty:rainbow_hills","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.25,"confidence":"high"},
{"id":"biomesoplenty:rainforest","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":1.2,"confidence":"high"},
{"id":"biomesoplenty:redwood_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:rocky_rainforest","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":1.2,"confidence":"high"},
{"id":"biomesoplenty:rocky_shrubland","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:scrubland","climateClass":"SEMI_ARID","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":1.1,"confidence":"high"},
{"id":"biomesoplenty:seasonal_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.4,"confidence":"high"},
{"id":"biomesoplenty:shrubland","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:snowy_coniferous_forest","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.25,"confidence":"high"},
{"id":"biomesoplenty:snowy_fir_clearing","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.25,"confidence":"high"},
{"id":"biomesoplenty:snowy_maple_woods","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.25,"confidence":"high"},
{"id":"biomesoplenty:tropics","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.95,"confidence":"high"},
{"id":"biomesoplenty:tundra","climateClass":"COLD","tempMinC":-15,"tempMaxC":12,"precipitation":"RAIN","baseTemperature":0.2,"confidence":"high"},
{"id":"biomesoplenty:volcanic_plains","climateClass":"VOLCANIC","tempMinC":25,"tempMaxC":60,"precipitation":"NONE","baseTemperature":0.95,"confidence":"high"},
{"id":"biomesoplenty:volcano","climateClass":"VOLCANIC","tempMinC":25,"tempMaxC":60,"precipitation":"NONE","baseTemperature":0.95,"confidence":"high"},
{"id":"biomesoplenty:wasteland","climateClass":"ARID","tempMinC":25,"tempMaxC":48,"precipitation":"RAIN","baseTemperature":2.0,"confidence":"high"},
{"id":"biomesoplenty:wetland","climateClass":"TEMPERATE_SWAMP","tempMinC":5,"tempMaxC":28,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"high"},
{"id":"biomesoplenty:wooded_scrubland","climateClass":"SEMI_ARID","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":1.1,"confidence":"high"},
{"id":"biomesoplenty:wooded_wasteland","climateClass":"ARID","tempMinC":25,"tempMaxC":48,"precipitation":"RAIN","baseTemperature":2.0,"confidence":"high"},
{"id":"biomesoplenty:woodland","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"biomesoplenty:glowing_grotto","climateClass":"CAVE","tempMinC":5,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"high"},
{"id":"biomesoplenty:spider_nest","climateClass":"CAVE","tempMinC":5,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"high"},
{"id":"biomesoplenty:crystalline_chasm","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"biomesoplenty:erupting_inferno","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"biomesoplenty:undergrowth","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"biomesoplenty:visceral_heap","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"biomesoplenty:withered_abyss","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:badlands","climateClass":"ARID","tempMinC":25,"tempMaxC":48,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:bamboo_jungle","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.95,"confidence":"high"},
{"id":"minecraft:basalt_deltas","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:beach","climateClass":"COASTAL","tempMinC":5,"tempMaxC":32,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"medium"},
{"id":"minecraft:birch_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"medium"},
{"id":"minecraft:cold_ocean","climateClass":"OCEAN_COLD","tempMinC":2,"tempMaxC":15,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:crimson_forest","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"medium"},
{"id":"minecraft:dark_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.7,"confidence":"medium"},
{"id":"minecraft:deep_cold_ocean","climateClass":"OCEAN_COLD","tempMinC":2,"tempMaxC":15,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:deep_frozen_ocean","climateClass":"OCEAN_FROZEN","tempMinC":-2,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":0.5,"confidence":"high"},
{"id":"minecraft:deep_lukewarm_ocean","climateClass":"OCEAN_WARM","tempMinC":15,"tempMaxC":32,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:deep_ocean","climateClass":"OCEAN_TEMPERATE","tempMinC":8,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:desert","climateClass":"ARID","tempMinC":25,"tempMaxC":48,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:dripstone_caves","climateClass":"CAVE","tempMinC":5,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"minecraft:end_barrens","climateClass":"END","tempMinC":-20,"tempMaxC":25,"precipitation":"NONE","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:end_highlands","climateClass":"END","tempMinC":-20,"tempMaxC":25,"precipitation":"NONE","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:end_midlands","climateClass":"END","tempMinC":-20,"tempMaxC":25,"precipitation":"NONE","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:eroded_badlands","climateClass":"ARID","tempMinC":25,"tempMaxC":48,"precipitation":"NONE","baseTemperature":2.0,"confidence":"medium"},
{"id":"minecraft:flower_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.7,"confidence":"medium"},
{"id":"minecraft:forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.7,"confidence":"medium"},
{"id":"minecraft:frozen_ocean","climateClass":"OCEAN_FROZEN","tempMinC":-2,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":0.0,"confidence":"high"},
{"id":"minecraft:frozen_peaks","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.7,"confidence":"high"},
{"id":"minecraft:frozen_river","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":0.0,"confidence":"high"},
{"id":"minecraft:grove","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.2,"confidence":"high"},
{"id":"minecraft:ice_spikes","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":0.0,"confidence":"high"},
{"id":"minecraft:jagged_peaks","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.7,"confidence":"high"},
{"id":"minecraft:jungle","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.95,"confidence":"high"},
{"id":"minecraft:lukewarm_ocean","climateClass":"OCEAN_WARM","tempMinC":15,"tempMaxC":32,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:lush_caves","climateClass":"CAVE","tempMinC":5,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"high"},
{"id":"minecraft:meadow","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"high"},
{"id":"minecraft:mushroom_fields","climateClass":"MUSHROOM","tempMinC":5,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.9,"confidence":"high"},
{"id":"minecraft:nether_wastes","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:ocean","climateClass":"OCEAN_TEMPERATE","tempMinC":8,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"high"},
{"id":"minecraft:old_growth_birch_forest","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.6,"confidence":"medium"},
{"id":"minecraft:old_growth_pine_taiga","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.3,"confidence":"high"},
{"id":"minecraft:old_growth_spruce_taiga","climateClass":"COLD","tempMinC":-15,"tempMaxC":12,"precipitation":"RAIN","baseTemperature":0.25,"confidence":"medium"},
{"id":"minecraft:plains","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"minecraft:river","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"high"},
{"id":"minecraft:savanna","climateClass":"SEMI_ARID","tempMinC":18,"tempMaxC":38,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:savanna_plateau","climateClass":"SEMI_ARID","tempMinC":18,"tempMaxC":38,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:small_end_islands","climateClass":"END","tempMinC":-20,"tempMaxC":25,"precipitation":"NONE","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:snowy_beach","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":0.05,"confidence":"high"},
{"id":"minecraft:snowy_plains","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":0.0,"confidence":"high"},
{"id":"minecraft:snowy_slopes","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.3,"confidence":"high"},
{"id":"minecraft:snowy_taiga","climateClass":"SNOWY","tempMinC":-35,"tempMaxC":5,"precipitation":"SNOW","baseTemperature":-0.5,"confidence":"high"},
{"id":"minecraft:soul_sand_valley","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"medium"},
{"id":"minecraft:sparse_jungle","climateClass":"TROPICAL","tempMinC":18,"tempMaxC":38,"precipitation":"RAIN","baseTemperature":0.95,"confidence":"high"},
{"id":"minecraft:stony_peaks","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":1.0,"confidence":"high"},
{"id":"minecraft:stony_shore","climateClass":"COASTAL","tempMinC":5,"tempMaxC":32,"precipitation":"RAIN","baseTemperature":0.2,"confidence":"high"},
{"id":"minecraft:sunflower_plains","climateClass":"TEMPERATE","tempMinC":0,"tempMaxC":25,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"medium"},
{"id":"minecraft:swamp","climateClass":"TEMPERATE_SWAMP","tempMinC":5,"tempMaxC":28,"precipitation":"RAIN","baseTemperature":0.8,"confidence":"high"},
{"id":"minecraft:taiga","climateClass":"COLD","tempMinC":-15,"tempMaxC":12,"precipitation":"RAIN","baseTemperature":0.25,"confidence":"high"},
{"id":"minecraft:the_end","climateClass":"END","tempMinC":-20,"tempMaxC":25,"precipitation":"NONE","baseTemperature":0.5,"confidence":"high"},
{"id":"minecraft:the_void","climateClass":"OTHER","tempMinC":0,"tempMaxC":25,"precipitation":"NONE","baseTemperature":0.5,"confidence":"high"},
{"id":"minecraft:warm_ocean","climateClass":"OCEAN_WARM","tempMinC":18,"tempMaxC":35,"precipitation":"RAIN","baseTemperature":0.5,"confidence":"medium"},
{"id":"minecraft:warped_forest","climateClass":"NETHER","tempMinC":40,"tempMaxC":90,"precipitation":"NONE","baseTemperature":2.0,"confidence":"medium"},
{"id":"minecraft:windswept_forest","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.2,"confidence":"medium"},
{"id":"minecraft:windswept_gravelly_hills","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.2,"confidence":"medium"},
{"id":"minecraft:windswept_hills","climateClass":"MOUNTAIN","tempMinC":-20,"tempMaxC":20,"precipitation":"RAIN","baseTemperature":0.2,"confidence":"medium"},
{"id":"minecraft:windswept_savanna","climateClass":"SEMI_ARID","tempMinC":18,"tempMaxC":38,"precipitation":"NONE","baseTemperature":2.0,"confidence":"high"},
{"id":"minecraft:wooded_badlands","climateClass":"ARID","tempMinC":25,"tempMaxC":48,"precipitation":"NONE","baseTemperature":2.0,"confidence":"medium"}
]}
```

**统计：BOP 条目 65 条，原版条目 61 条，共 126 条；其中 `confidence: high` 100 条（BOP 全部 65 条 + 原版 35 条经 1.18.2 数据包源码逐条核对），`confidence: medium` 26 条（原版，取自 Wiki 气候表）。**

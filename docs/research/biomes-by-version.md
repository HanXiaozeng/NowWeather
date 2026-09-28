# Minecraft Java 版生物群系变更清单：1.18 → 26.3

> **调查日期（快照时间）**：2026-09-13
> **最新已发布版本**：**26.2**（Chaos Cubed，2026-06-16）
> **未发布版本**：**26.3**（Wilderness Bound，计划 2026-09-15，截至本文写作日尚未正式发布；当前开发版为 `26.3-pre-3`）
> **范围**：仅 *Minecraft: Java Edition*；仅原版（vanilla）数据包内容；不含模组。
> **本文所有温度/降水数值均标注来源与置信度；凡无法从权威来源确证者一律标注「未确证」，不臆造群系 id。**

## 0. 口径与方法（读表前必看）

### 0.1 「基础温度」(base temperature)

- 对应代码中的 `Biome.ClimateSettings#temperature`（数据包 JSON 里 biome 的 `temperature` 字段），量纲为 Minecraft 的无量纲温度单位（MC 量纲），典型区间 `-0.7` ～ `2.0`。
- 这是 **基础值**。世界中的**实际**温度还会受高度影响：Y=81 以上开始线性下降（Y=81 处随机抖动 −0.00875 ～ +0.01125），因此即使基础温度 ≥ 0.15 的群系，在高空也会下雪。
  - 出处：<https://minecraft.wiki/w/Biome#Temperature>、<https://minecraft.wiki/w/Biome#Precipitation>

### 0.2 「降水形态」(precipitation)

**这是本文最容易踩坑的地方，请务必注意版本断代：**

| 时期 | 数据包字段 | 取值 |
| --- | --- | --- |
| ≤ 1.19.3 | `precipitation`（枚举） | `"none"` / `"rain"` / `"snow"` |
| ≥ 1.19.4 | `has_precipitation`（布尔） | `true` / `false`；下雨还是下雪**只由温度决定** |

- 变更点为 **1.19.4 的首个快照 `23w03a`（2023-01-18）**：`Custom biome` 一项明确写着「Removed `precipitation` precipitation type field. Added `has_precipitation` boolean field.」
  - 出处：<https://minecraft.wiki/w/Java_Edition_23w03a>
- 现行派生规则（`Biome.Precipitation` 枚举的实际取值）：
  - `has_precipitation == false` → **NONE**
  - `has_precipitation == true` 且 基础温度 `< 0.15` → **SNOW**
  - `has_precipitation == true` 且 基础温度 `>= 0.15` → **RAIN**
  - 出处：<https://minecraft.wiki/w/Biome#Precipitation>
- ⚠️ 因此本文表格中的 `RAIN`/`SNOW`/`NONE` 对 **1.19.4 及以后**是权威口径；对 **1.18.2 – 1.19.3** 则是**按现行规则反推的"有效形式"**，而非当年 JSON 里的字面枚举值（当年逐群系的枚举字面值 wiki 未逐版本存档，见 §4）。

### 0.3 置信度定义

- **高**：wiki 条目 + 独立第二处来源（版本页/群系页/官方日志）互相印证，数值可直接引用。
- **中**：单一权威来源，或由权威规则推导（如 §0.2 的 RAIN/SNOW 推导），无矛盾证据。
- **低 / 未确证**：无来源或来源互相矛盾。

### 0.4 主要来源（全部实际访问核对过）

- 群系总表 / 气候表 / 历史表 / 降水规则：<https://minecraft.wiki/w/Biome>（含 `#List_of_biome_climates`、`#Precipitation`、`#History`）
- 群系 Java 版 id 全表：<https://minecraft.wiki/w/Biome/ID>
- 版本页：<https://minecraft.wiki/w/Java_Edition_1.19>、[1.19.4](https://minecraft.wiki/w/Java_Edition_1.19.4)、[1.20](https://minecraft.wiki/w/Java_Edition_1.20)、[1.21.2](https://minecraft.wiki/w/Java_Edition_1.21.2)、[1.21.4](https://minecraft.wiki/w/Java_Edition_1.21.4)、[26.1](https://minecraft.wiki/w/Java_Edition_26.1)、[26.2](https://minecraft.wiki/w/Java_Edition_26.2)、[26.3](https://minecraft.wiki/w/Java_Edition_26.3)
- 快照页：[23w03a](https://minecraft.wiki/w/Java_Edition_23w03a)、[21w40a](https://minecraft.wiki/w/Java_Edition_21w40a)
- 群系页：[Mangrove Swamp](https://minecraft.wiki/w/Mangrove_swamp)、[Deep Dark](https://minecraft.wiki/w/Deep_dark)、[Cherry Grove](https://minecraft.wiki/w/Cherry_grove)、[Pale Garden](https://minecraft.wiki/w/Pale_garden)、[Sulfur Caves](https://minecraft.wiki/w/Sulfur_caves)、[Dappled Forest](https://minecraft.wiki/w/Dappled_forest)、[Savanna](https://minecraft.wiki/w/Savanna)
- 更新主题：[Wilderness Bound](https://minecraft.wiki/w/Wilderness_Bound)
- 官方日志（交叉印证 1.21.4 加入 pale garden）：<https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-4>

---

## 1. 基线：1.18.2 全部生物群系

**1.18.2 共 61 个原版群系**：Overworld 51 个（含仅用于超平坦预设的 `the_void`）+ Nether 5 个 + End 5 个。
（推算依据：当前已发布的 26.2 共 66 个 → 减去 1.18.2 之后新增的 5 个：`deep_dark`、`mangrove_swamp`、`cherry_grove`、`pale_garden`、`sulfur_caves`，得 61。与 <https://minecraft.wiki/w/Biome/ID> 的 Java 版 65 项清单（= 26.2 的 66 减去 26.2 新增的 `sulfur_caves`）一致。）

**降水列说明**：按 §0.2 的现行规则推导的"有效形式"；1.18.2 当年的 datapack 实际写的是枚举字面值（见 §4 存疑项）。

### 1.1 Overworld（51）

| # | 群系 id | 基础温度 | 降水 |
| --- | --- | --- | --- |
| 1 | `minecraft:the_void` | 0.5 | NONE |
| 2 | `minecraft:plains` | 0.8 | RAIN |
| 3 | `minecraft:sunflower_plains` | 0.8 | RAIN |
| 4 | `minecraft:snowy_plains` | 0.0 | SNOW |
| 5 | `minecraft:ice_spikes` | 0.0 | SNOW |
| 6 | `minecraft:desert` | 2.0 | NONE |
| 7 | `minecraft:swamp` | 0.8 | RAIN |
| 8 | `minecraft:forest` | 0.7 | RAIN |
| 9 | `minecraft:flower_forest` | 0.7 | RAIN |
| 10 | `minecraft:birch_forest` | 0.6 | RAIN |
| 11 | `minecraft:dark_forest` | 0.7 | RAIN |
| 12 | `minecraft:old_growth_birch_forest` | 0.6 | RAIN |
| 13 | `minecraft:old_growth_pine_taiga` | 0.3 | RAIN |
| 14 | `minecraft:old_growth_spruce_taiga` | 0.25 | RAIN |
| 15 | `minecraft:taiga` | 0.25 | RAIN |
| 16 | `minecraft:snowy_taiga` | −0.5 | SNOW |
| 17 | `minecraft:savanna` | 2.0 | NONE |
| 18 | `minecraft:savanna_plateau` | 2.0 | NONE |
| 19 | `minecraft:windswept_hills` | 0.2 | RAIN |
| 20 | `minecraft:windswept_gravelly_hills` | 0.2 | RAIN |
| 21 | `minecraft:windswept_forest` | 0.2 | RAIN |
| 22 | `minecraft:windswept_savanna` | 2.0 | NONE |
| 23 | `minecraft:jungle` | 0.95 | RAIN |
| 24 | `minecraft:sparse_jungle` | 0.95 | RAIN |
| 25 | `minecraft:bamboo_jungle` | 0.95 | RAIN |
| 26 | `minecraft:badlands` | 2.0 | NONE |
| 27 | `minecraft:eroded_badlands` | 2.0 | NONE |
| 28 | `minecraft:wooded_badlands` | 2.0 | NONE |
| 29 | `minecraft:meadow` | 0.5 | RAIN |
| 30 | `minecraft:grove` | −0.2 | SNOW |
| 31 | `minecraft:snowy_slopes` | −0.3 | SNOW |
| 32 | `minecraft:frozen_peaks` | −0.7 | SNOW |
| 33 | `minecraft:jagged_peaks` | −0.7 | SNOW |
| 34 | `minecraft:stony_peaks` | 1.0 | RAIN |
| 35 | `minecraft:river` | 0.5 | RAIN |
| 36 | `minecraft:frozen_river` | 0.0 | SNOW |
| 37 | `minecraft:beach` | 0.8 | RAIN |
| 38 | `minecraft:snowy_beach` | 0.05 | SNOW |
| 39 | `minecraft:stony_shore` | 0.2 | RAIN |
| 40 | `minecraft:warm_ocean` | 0.5 | RAIN |
| 41 | `minecraft:lukewarm_ocean` | 0.5 | RAIN |
| 42 | `minecraft:deep_lukewarm_ocean` | 0.5 | RAIN |
| 43 | `minecraft:ocean` | 0.5 | RAIN |
| 44 | `minecraft:deep_ocean` | 0.5 | RAIN |
| 45 | `minecraft:cold_ocean` | 0.5 | RAIN |
| 46 | `minecraft:deep_cold_ocean` | 0.5 | RAIN |
| 47 | `minecraft:frozen_ocean` | 0.0 | SNOW |
| 48 | `minecraft:deep_frozen_ocean` | 0.0 | SNOW |
| 49 | `minecraft:mushroom_fields` | 0.9 | RAIN |
| 50 | `minecraft:dripstone_caves` | 0.8 | RAIN |
| 51 | `minecraft:lush_caves` | 0.5 | RAIN |

### 1.2 The Nether（5）

| # | 群系 id | 基础温度 | 降水 |
| --- | --- | --- | --- |
| 52 | `minecraft:nether_wastes` | 2.0 | NONE |
| 53 | `minecraft:warped_forest` | 2.0 | NONE |
| 54 | `minecraft:crimson_forest` | 2.0 | NONE |
| 55 | `minecraft:soul_sand_valley` | 2.0 | NONE |
| 56 | `minecraft:basalt_deltas` | 2.0 | NONE |

### 1.3 The End（5）

| # | 群系 id | 基础温度 | 降水 |
| --- | --- | --- | --- |
| 57 | `minecraft:the_end` | 0.5 | NONE |
| 58 | `minecraft:end_highlands` | 0.5 | NONE |
| 59 | `minecraft:end_midlands` | 0.5 | NONE |
| 60 | `minecraft:small_end_islands` | 0.5 | NONE |
| 61 | `minecraft:end_barrens` | 0.5 | NONE |

> **温度来源**：<https://minecraft.wiki/w/Biome#List_of_biome_climates>（wiki 当前表，反映 26.2 数据）。
> **为何可当 1.18.2 基线用**：§2.3 的系统性检索显示，1.18.2 → 26.2 之间**没有任何已发布群系的基础温度被改动过**（唯一的温度改动发生在 1.18 开发期 pre-release 6，早于 1.18.2 基线）。现值 = 1.18.2 值。置信度：**高**。
> **降水列**：见 §0.2 与 §4，1.18.2 当年的枚举字面值**未确证**。

---

## 2. 按版本的新增/变更表

### 2.1 版本节点总览（覆盖任务要求的全部 32 个节点）

| 版本 | 发布日期 | 群系新增 | 群系变更/移除/改名 |
| --- | --- | --- | --- |
| 1.18 | 2021-11-30 | 无（见注） | savanna 家族基础温度改动（pre-release 6）；21w40a 删 25 个高度变体子群系 + 13 项改名 |
| 1.18.1 | 2021-12-10 | 无 | 无 |
| **1.18.2** | 2022-02-28 | 无 | 无（**本文基线节点**） |
| 1.19 | 2022-06-07 | **`minecraft:deep_dark`**、**`minecraft:mangrove_swamp`** | 无 |
| 1.19.1 | 2022-07-27 | 无 | 无 |
| 1.19.2 | 2022-08-05 | 无 | 无 |
| 1.19.3 | 2022-12-07 | 无 | 无 |
| 1.19.4 | 2023-03-14 | **`minecraft:cherry_grove`**（仅实验性数据包） | **降水字段语义变更**（23w03a：枚举 → 布尔） |
| 1.20 | 2023-06-07 | 无（cherry_grove 转为默认可用） | 无 |
| 1.20.1 | 2023-06-12 | 无 | 无 |
| 1.20.2 | 2023-09-21 | 无 | 无 |
| 1.20.3 | 2023-12-05 | 无 | 无 |
| 1.20.4 | 2023-12-07 | 无 | 无 |
| 1.20.5 | 2024-04-23 | 无 | 无（mangrove_swamp 增加 bogged 生成） |
| 1.20.6 | 2024-04-29 | 无 | 无 |
| 1.21 | 2024-06-13 | 无 | 无（deep_dark 中 trial chamber 生成概率下调） |
| 1.21.1 | 2024-08-08 | 无 | 无 |
| 1.21.2 | 2024-10-22 | **`minecraft:pale_garden`**（仅 Winter Drop 实验性数据包） | 无 |
| 1.21.3 | 2024-10-23 | 无 | 无 |
| 1.21.4 | 2024-12-03 | 无（pale_garden 转为默认可用） | 无 |
| 1.21.5 | 2025-03-25 | 无 | 无（pale_garden 生成更频繁、面积更大；mangrove_swamp 增加 firefly bush） |
| 1.21.6 | 2025-06-17 | 无 | 无 |
| 1.21.7 | 2025-06-30 | 无 | 无 |
| 1.21.8 | 2025-07-17 | 无 | 无 |
| 1.21.9 | 2025-09-30 | 无 | 无 |
| 1.21.10 | 2025-10-07 | 无 | 无 |
| 1.21.11 | 2025-12-09 | 无 | 无（25w42a 为群系引入 "environment attributes"） |
| 26.1 | 2026-03-24 | 无 | 无 |
| 26.1.1 | 2026-04-01 | 无 | 无 |
| 26.1.2 | 2026-04-09 | 无 | 无 |
| **26.2** | 2026-06-16 | **`minecraft:sulfur_caves`** | 无 |
| **26.3** | **计划 2026-09-15（未发布）** | **`minecraft:dappled_forest`**（仅开发版） | 无 |

「无群系变更」的判定依据：<https://minecraft.wiki/w/Biome#History> 的 Java Edition 历史表在这些版本下没有任何群系增删/改名条目；各补丁版本的页面简介亦未提及群系。
发布日期来源：各 `Java Edition <版本>` 页面简介句（wiki 引言「released on …」）。

### 2.2 新增群系明细（核心表）

| 版本 | 群系 id | 维度 | 基础温度 | 降水 | 说明 | 来源 | 置信度 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1.19 | `minecraft:deep_dark` | overworld | 0.8 | RAIN（有效形式） | 深暗之域：地下洞穴群系，覆盖 sculk，除监守者外无自然敌怪生成；first 出现在 `Deep Dark Experimental Snapshot 1`，1.19 的 `22w11a` 正式加入 | <https://minecraft.wiki/w/Deep_dark>（Climate 栏 0.8 / 0.4 / Yes）、<https://minecraft.wiki/w/Biome#History> | 高（温度/是否降水）；降水字面形态对 1.19–1.19.3 为**中**（见 §4） |
| 1.19 | `minecraft:mangrove_swamp` | overworld | 0.8 | RAIN | 红树林沼泽：温暖的沼泽变体，泥巴 + 红树，树叶/藤蔓特殊浅绿、水呈青色 | <https://minecraft.wiki/w/Mangrove_swamp>（0.8 / 0.9 / Yes）、<https://minecraft.wiki/w/Biome#History> | 高（温度/是否降水）；字面形态同上有保留 |
| 1.19.4（仅实验性）→ 1.20（默认） | `minecraft:cherry_grove` | overworld | 0.5（JE；BE 为 0.3） | RAIN | 樱花树林：山地坡地/高原群系，粉瓣 + 樱花树，似草甸 | <https://minecraft.wiki/w/Cherry_grove>（0.5 JE / 0.8 / Yes）、<https://minecraft.wiki/w/Java_Edition_1.20> | 高 |
| 1.21.2（仅实验性）→ 1.21.4（默认） | `minecraft:pale_garden` | overworld | 0.7 | RAIN | 苍白之园：黑暗森林的稀有高位变体，苍白橡树 + 苍白苔藓，天空/水/植被灰白去饱和，无环境音乐，夜间生成嘎枝 | <https://minecraft.wiki/w/Pale_garden>（0.7 / 0.8 / Yes）、<https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-4> | 高 |
| 26.2 | `minecraft:sulfur_caves` | overworld | 0.8 | RAIN（有效形式） | 硫磺洞穴：地下洞穴群系，硫磺 + 朱砂，硫磺池含强效硫磺（产生致呕气体与间歇泉）；与繁茂洞穴/滴水石洞穴同层但更小更碎 | <https://minecraft.wiki/w/Sulfur_caves>（Climate 栏 0.8 / 0.4 / Yes）、<https://minecraft.wiki/w/Java_Edition_26.2> | 高 |
| **26.3（未发布）** | `minecraft:dappled_forest` | overworld | 0.6 | RAIN | 斑驳森林：秋色森林，杨树（红/橙/黄三色叶）+ 红色灌木 + 层孔菌 + 落叶，橙棕色草色，水色近黑且不透明；寒冷、极低湿、高 weirdness | <https://minecraft.wiki/w/Dappled_forest>（0.6 / 0.6 / Yes）、<https://minecraft.wiki/w/Wilderness_Bound> | 高（数值）；**该版本尚未发布** |

**汇总：1.18.2 → 26.3 只新增了 6 个原版群系**（其中 1 个在 26.3 中尚未随正式版发布）：
`deep_dark`、`mangrove_swamp`（1.19）；`cherry_grove`（1.19.4 实验 → 1.20 默认）；`pale_garden`（1.21.2 实验 → 1.21.4 默认）；`sulfur_caves`（26.2）；`dappled_forest`（26.3，未发布）。
**全部 6 个都属于 Overworld**。1.18.2 → 26.3 期间 **Nether 与 End 没有新增任何群系**。

**对你原始说法的核对结论：**

| 你的说法 | 核对结果 |
| --- | --- |
| 1.19 加入 `mangrove_swamp` | ✅ 正确。`22w14a` 加入，1.19（2022-06-07）发布。温度 0.8、downfall 0.9、`has_precipitation` 为真 |
| 1.19 加入 `deep_dark` | ✅ 正确。`Deep Dark Experimental Snapshot 1` 引入，1.19 的 `22w11a` 正式加入。温度 0.8、downfall 0.4 |
| 1.20 加入 `cherry_grove` | ⚠️ **需修正（版本号）**。严格说 `cherry_grove` 是 **1.19.4** 的 `23w07a` 以 "Update 1.20" 实验性数据包形式加入的；**1.20 的 `23w12a` 才让它在默认玩法中可用**。若你的口径是「正式可玩版本」，写 1.20 可以接受，但应注明 1.19.4 已可通过实验开关获得 |
| 1.21.4 加入 `pale_garden` | ⚠️ **需修正（版本号）**。`pale_garden` 在 **1.21.2**（`24w40a`，Winter Drop 实验性数据包）加入；**1.21.4**（`24w44a`）才默认可用。你的说法与 1.21.4 的官方日志口径一致，但严格的首个出现版本是 1.21.2 |
| （你未提及） | ➕ **遗漏 2 个**：**`sulfur_caves`（26.2）** 与 **`dappled_forest`（26.3，未发布）** |
| 各版本是否改动过既有群系的温度/降水 | ➕ **有**：1. 温度——仅在 **1.18 开发期 Pre-release 6** 改过（savanna 家族 3 个，早于 1.18.2 基线，见 §2.3）；2. 降水——**1.19.4 `23w03a` 改了字段语义**（枚举 → 布尔），这是影响所有群系的全局变更（见 §0.2） |

### 2.3 既有群系的温度 / 降水变更（系统性核查）

**核查方法**：对 Minecraft Wiki 全文做 `insource:"base temperature" insource:"changed"` 检索（命中 9 页），逐条排查与「群系基础温度被改动」相关的历史条目；再加 `insource:"precipitation" insource:"has_precipitation"` 检索降水语义变更。检索于 2026-09-13 执行。

**结果：**

| 版本 | 对象 | 变更 | 来源 | 置信度 |
| --- | --- | --- | --- | --- |
| 1.18 Pre-release 6 | `minecraft:savanna` | 基础温度 **1.2 → 2.0**；天空色 `#75aaff` → `#6eb1ff` | <https://minecraft.wiki/w/Savanna>（Java Edition 历史表） | 高 |
| 1.18 Pre-release 6 | `minecraft:savanna_plateau` | 基础温度 **1.0 → 2.0**；天空色 `#76a8ff` → `#6eb1ff` | <https://minecraft.wiki/w/Savanna_Plateau> | 高 |
| 1.18 Pre-release 6 | `minecraft:windswept_savanna` | 基础温度 **1.1 → 2.0**；天空色 `#76a9ff` → `#6eb1ff` | <https://minecraft.wiki/w/Windswept_Savanna> | 高 |
| 1.19.4（`23w03a`） | 全部群系 | datapack 字段 `precipitation`（枚举 none/rain/snow）**被移除**，改为布尔 `has_precipitation`；下雪与否改由基础温度（< 0.15）决定 | <https://minecraft.wiki/w/Java_Edition_23w03a>、<https://minecraft.wiki/w/Biome#History> | 高 |
| 1.21.11（`25w42a`） | 全部群系 | 为群系新增 "environment attributes"（环境属性体系），**未改动** temperature / downfall / has_precipitation | <https://minecraft.wiki/w/Biome#History> | 高 |
| 26.2 | `minecraft:sulfur_caves` | `snap7`：草地颜色「less green」（视觉色，非温度）→ 最后为 `#aba64f` | <https://minecraft.wiki/w/Sulfur_caves> | 高 |

**结论：1.18.2 → 26.2（最新已发布）之间，没有任何已发布群系的基础温度数值被改动过。** 上面 3 项温度改动全部发生在 1.18 的 pre-release 阶段，即**早于 1.18.2 基线**，所以 §1 的基线温度表可直接沿用当前 wiki 数值。
（检索局限见 §4。）

### 2.4 移除与改名

**1.18 → 26.3 期间，没有任何群系被移除或改名。**
但为了完整性——同时也是因为**基线 1.18.2 的形状正是由下面这两批操作决定的**——在此记录 1.18 开发期（快照 `21w40a`，于 1.18 正式版之前）的移除与改名。若你的代码需要兼容 1.17 及更早的存档/数据，这些映射是必须的。

**（a）`21w40a` 从代码中移除并与基础群系合并的 25 个高度变体子群系：**

`Badlands Plateau`、`Bamboo Jungle Hills`、`Birch Forest Hills`、`Dark Forest Hills`、`Desert Hills`、`Desert Lakes`、`Giant Spruce Taiga Hills`、`Giant Tree Taiga Hills`、`Gravelly Mountains+`、`Jungle Hills`、`Modified Badlands Plateau`、`Modified Jungle`、`Modified Jungle Edge`、`Modified Wooded Badlands Plateau`、`Mountain Edge`、`Mushroom Field Shore`、`Shattered Savanna Plateau`、`Snowy Mountains`、`Snowy Taiga Hills`、`Snowy Taiga Mountains`、`Swamp Hills`、`Taiga Hills`、`Taiga Mountains`、`Tall Birch Hills`、`Wooded Hills`

**（b）`21w40a` 的 13 项改名（英文显示名 → 新英文显示名）：**

| 旧名 | 新名 | 旧资源位置 → 新资源位置（JE） |
| --- | --- | --- |
| Giant Spruce Taiga | Old Growth Spruce Taiga | `giant_spruce_taiga` → `old_growth_spruce_taiga` |
| Giant Tree Taiga | Old Growth Pine Taiga | `giant_tree_taiga` → `old_growth_pine_taiga` |
| Gravelly Mountains | Windswept Gravelly Hills | `gravelly_mountains` → `windswept_gravelly_hills` |
| Jungle Edge | Sparse Jungle | `jungle_edge` → `sparse_jungle` |
| Lofty Peaks | Jagged Peaks | （1.18 快照新增名，先叫 Lofty Peaks）→ `jagged_peaks` |
| Mountains | Windswept Hills | `mountains` → `windswept_hills` |
| Shattered Savanna | Windswept Savanna | `shattered_savanna` → `windswept_savanna` |
| Snowcapped Peaks | Frozen Peaks | （1.18 快照新增名）→ `frozen_peaks` |
| Snowy Tundra | Snowy Plains | `snowy_tundra` → `snowy_plains` |
| Stone Shore | Stony Shore | `stone_shore` → `stony_shore` |
| Tall Birch Forest | Old Growth Birch Forest | `tall_birch_forest` → `old_growth_birch_forest` |
| Wooded Badlands Plateau | Wooded Badlands | `wooded_badlands_plateau` → `wooded_badlands` |
| Wooded Mountains | Windswept Forest | `wooded_mountains` → `windswept_forest` |

> ⚠️ 上表的「资源位置」列是我**根据改名规则推断**的映射（wiki 该节只给出英文显示名新旧的对应）。**推断部分置信度为「中」**，请以实际 datapack/存档中的字符串为准；若要写进代码，建议自行反查 <https://minecraft.wiki/w/Biome/ID> 的当前 id 全表确认。
> 出处（显示名部分）：<https://minecraft.wiki/w/Java_Edition_21w40a>（World generation → Biomes）、<https://minecraft.wiki/w/Biome#History>

**（c）1.18 开发期其他相关移除**：`21w43a` 移除了 `deep_warm_ocean` 群系（它在 1.17 及以前可用 `buffet` 世界选项访问，1.18 起不再存在）。因此在 **1.18.2 中不存在 `minecraft:deep_warm_ocean`** —— 如果你见过这个 id，它属于 1.17 及更早、或 Bedrock 版（BE 的数字 id 41 仍是 `deep_warm_ocean`）。
出处：<https://minecraft.wiki/w/Biome#History>、<https://minecraft.wiki/w/Biome/ID>

---

## 2.5 26.x（新版本号体系）专项

### 结论速览

> **26.x 到目前为止确实新增了生物群系**，而且不止一个：
> 1. **`minecraft:sulfur_caves`** —— 加入 **26.2**（Chaos Cubed，2026-06-16 发布），Overworld 地下洞穴群系。**已随正式版发布，可直接使用。**
> 2. **`minecraft:dappled_forest`** —— 加入 **26.3**（Wilderness Bound，Drop 3 of 2026），Overworld 森林群系。**截至 2026-09-13 尚未发布**（计划 2026-09-15 发布；当前仅有 snapshot / pre-release / release-candidate）。在正式版发布前，它的注册 id 与数值**只存在于开发版数据中**。

因而不适用「截至 <日期> 未查到 26.x 新增生物群系」这一结论——本文的结论是**查到了 2 个**，来源见下。

### 版本号体系与 26.x 路线图

- 从 **26.1 起**，Java 版启用新的「_年份_._掉落序号_._修复号_」版本格式（2025 年 12 月公布）。26.1 是首个使用该格式、也是首个完全去混淆的正式版。
  出处：<https://minecraft.wiki/w/Java_Edition_26.1>
- 2026 年的三个 game drop：

| Drop | 正式名 | Java 版 | Bedrock 版 | Java 发布日期 | 群系 |
| --- | --- | --- | --- | --- | --- |
| Drop 1 of 2026 | **Tiny Takeover** | 26.1 | 26.1 | 2026-03-24 | 无 |
| Drop 2 of 2026 | **Chaos Cubed** | 26.2 | 26.20 | 2026-06-16 | **`sulfur_caves`** |
| Drop 3 of 2026 | **Wilderness Bound** | 26.3 | 26.50（BE 26.40 为实验性预览） | **计划 2026-09-15（未发布）** | **`dappled_forest`** |

出处：<https://minecraft.wiki/w/Java_Edition_26.1>、<https://minecraft.wiki/w/Java_Edition_26.2>、<https://minecraft.wiki/w/Java_Edition_26.3>、<https://minecraft.wiki/w/Wilderness_Bound>、<https://minecraft.wiki/w/Chaos_Cubed>
- 26.1 的热修复：26.1.1（2026-04-01）、26.1.2（2026-04-09）——均**未**新增群系。
- **最新已发布版本 = 26.2**。写作时 wiki 侧栏显示的「Latest: 26.2 / Upcoming: 26.3 / Dev: 26.3-pre-3」。

### 26.x 新增群系明细

| 版本 | 群系 id | 维度 | 基础温度 | 降水 | 来源 | 置信度 |
| --- | --- | --- | --- | --- | --- | --- |
| 26.2 | `minecraft:sulfur_caves` | overworld（地下） | 0.8 | RAIN（有效形式；实际地下无降雨） | <https://minecraft.wiki/w/Sulfur_caves>、<https://minecraft.wiki/w/Java_Edition_26.2> | 高 |
| 26.3（未发布） | `minecraft:dappled_forest` | overworld | 0.6 | RAIN | <https://minecraft.wiki/w/Dappled_forest>、<https://minecraft.wiki/w/Java_Edition_26.3> | 高（数值）/ 该版本未发布 |

### 26.x 是否改动了既有群系？

- 26.1：Additions 只含方块（金蒲公英）、命令（`/swing`）与大量技术性变更（村民交易数据驱动化、世界时钟、环境属性扩展等），**无世界生成/群系条目**。变更节亦无群系内容。出处：<https://minecraft.wiki/w/Java_Edition_26.1>
- 26.2：世界生成新增节只列出 **Sulfur caves**（群系）与 **Sulfur spring**（地物）；改动节无群系温度/降水变更。26.2 的 Removals 只涉及 General（技术项）。出处：<https://minecraft.wiki/w/Java_Edition_26.2>
- 26.3：世界生成新增节只列出 **Dappled forest**（群系）+ 杨树/红色灌木/层孔菌等（地物）+ 废弃营地（结构）。出处：<https://minecraft.wiki/w/Java_Edition_26.3>、<https://minecraft.wiki/w/Wilderness_Bound>
- 既有群系在 26.x 中确有**非温度/降水**类变化（例如：`pale_garden`/`cherry_grove`/`savanna` 等在 26.3 加入废弃营地结构；`dappled_forest` 在 26.3-pre1 改用 forest 音乐池）。这些不影响 `temperature` / `has_precipitation`。

---

## 3. 未确证 / 存疑

以下条目**没有**得到权威来源确证，请勿直接用于代码，或在使用前自行核对游戏内数据。

1. **1.18.2 – 1.19.3 期间各群系 `precipitation` 枚举的字面值（未确证）**
   本节表格给出的 `RAIN`/`SNOW`/`NONE` 是按**现行**规则（`has_precipitation` + 温度 < 0.15）反推的"有效形式"。在 `23w03a`（1.19.4）之前，datapack 里写的是 `"none"`/`"rain"`/`"snow"` 三个字面枚举，**Minecraft Wiki 未按版本逐群系存档该枚举的取值**，我也未能找到官方逐群系对照表。我尝试用 wiki 的历史修订版（`Biome` 页 2022-07-30 版，revid 2157264）复查，但当年的气候表只有 `Biome / Temperature / Rainfall / Type / Grass color` 五列，**没有 precipitation 列**。
   影响范围：仅 `deep_dark`（1.19–1.19.3）与 `mangrove_swamp`（1.19–1.19.3）两个群系；1.19.4 之后的所有版本不受影响。
   → **建议**：若你的模组/工具需要读取 1.19.4 之前的数据，请以实测为准，不要依赖本文的 RAIN/SNOW 标注。

2. **`deep_dark` 与 `sulfur_caves` 的降水形态语义（存疑，非数值问题）**
   两者是**地下洞穴群系**，当前 `has_precipitation` 为 `true`（wiki 群系页 Climate 栏写 "Yes"，温度 0.8），按 §0.2 的规则推导 `Biome.Precipitation == RAIN`。但洞穴群系实际上没有天空可见度，**现实中不会真的下雨**，因此用 `RAIN` 描述它们在语义上容易误导。本文在 JSON 中仍填 `"RAIN"`（忠于枚举推导），并另加 `"note"` 字段说明。
   出处：<https://minecraft.wiki/w/Deep_dark>、<https://minecraft.wiki/w/Sulfur_caves>、<https://minecraft.wiki/w/Biome#List_of_biome_climates>

3. **`21w40a` 改名表的「资源位置」映射为推断值（中等置信度）**
   wiki 的 `21w40a` 页面只给出英文**显示名**的新旧对应，未给出资源位置（id）对照表。§2.4(b) 第三列是我按命名规律推断的。请以 <https://minecraft.wiki/w/Biome/ID> 的当前 id 全表反查确认。

4. **「无温度变更」结论的检索局限（方法学声明）**
   §2.3 的结论基于 wiki 的 `insource"base temperature" + "changed"` 全文检索（命中 9 页，逐页排查）。该方法的局限：
   - 如果某次温度改动**只写在版本页（如 `Java Edition 1.21.5`）而没有写进对应群系页的历史表**，本次检索会漏掉它。
   - 如果 wiki 编辑者用词不是 "base temperature"（例如只写 "temperature" 或 "climate"），也会漏掉。
   我已用**第二重**交叉印证来降低风险：`Biome` 页的 Java Edition 历史表（1.18 → 26.3 的权威汇总）中，除了 `23w03a` 的降水字段变更与 `25w42a` 的 environment attributes 之外，**没有任何**涉及群系气候的条目。因此「1.18.2 → 26.2 无群系基础温度变更」这一结论置信度为**高**，但仍非数学证明。

5. **`Biome/ID` 页面的 Java 版表已过期（非错误，仅提示）**
   该页 Java Edition 表中**没有**列出 `sulfur_caves` 与 `dappled_forest`（最新编辑早于 26.2）。二者的 Java 版 id 我是从各自的群系页 "Data values → ID" 表取得的（`sulfur_caves`、`dappled_forest`，均无 `minecraft:` 前缀书写，实际注册 id 应加命名空间，即 `minecraft:sulfur_caves`、`minecraft:dappled_forest`）。在 BE 的表中它们分别是数字 id 194 与 195。
   出处：<https://minecraft.wiki/w/Biome/ID>、<https://minecraft.wiki/w/Sulfur_caves#ID>、<https://minecraft.wiki/w/Dappled_forest#ID>

6. **26.3 的发布时间为「计划」而非既成事实**
   26.3 计划于 **2026-09-15** 发布，本文写于 **2026-09-13**，因此 26.3 的**全部内容（含 `dappled_forest` 的温度/降水）都可能在正式发布前变动**。`dappled_forest` 的水色/雾效在开发期已至少改过一次（26.3-pre1 起改用 forest 音乐池替代 flower forest 音乐池）。
   出处：<https://minecraft.wiki/w/Java_Edition_26.3>、<https://minecraft.wiki/w/Dappled_forest>

7. **BE/JE 数值差异不加区分（提请注意）**
   `cherry_grove` 温度 JE 为 **0.5**、BE 为 **0.3**（因此 BE 中 Y=200 以上会下雪，自然地形达不到）；`savanna` 温度 JE 为 2.0、BE 为 1.2。本文表格除非特别标注，一律给出 **Java Edition** 数值。

8. **未查证项：`dripstone_caves` / `lush_caves` 在 1.19.4 之前是否 `precipitation: "none"`（未确证）**
   现行数据中它们是 `has_precipitation == true`（温度 0.8 / 0.5），但坊间普遍认为 1.17/1.18 时期它们写的是 `"none"`。我**没有**找到权威来源证实或证伪这一说法。由于这两个群系不属于「1.18.2 之后新增」，不影响本文的新增清单，但会影响基线表的降水列精度。

---

## 4. 供程序读取的 JSON

```json
{
  "meta": {
    "generatedAt": "2026-09-13",
    "edition": "java",
    "latestReleasedVersion": "26.2",
    "latestReleasedDate": "2026-06-16",
    "unreleasedVersions": ["26.3"],
    "nextPlannedRelease": { "version": "26.3", "officialName": "Wilderness Bound", "plannedDate": "2026-09-15" },
    "baselineVersion": "1.18.2",
    "baselineBiomeCount": 61,
    "temperatureUnit": "Minecraft dimensionless base temperature (Biome.ClimateSettings.temperature)",
    "precipitationSemantics": "Effective Biome.Precipitation. Since 1.19.4/23w03a the datapack field is boolean has_precipitation; rain vs snow is decided by baseTemperature < 0.15 => SNOW else RAIN. For 1.18.2-1.19.3 the literal enum values are unverified (see docs section 3.1).",
    "sources": [
      "https://minecraft.wiki/w/Biome",
      "https://minecraft.wiki/w/Biome/ID",
      "https://minecraft.wiki/w/Biome#List_of_biome_climates",
      "https://minecraft.wiki/w/Biome#History",
      "https://minecraft.wiki/w/Java_Edition_23w03a",
      "https://minecraft.wiki/w/Java_Edition_21w40a"
    ]
  },
  "versions": [
    {
      "mcVersion": "1.18",
      "released": "2021-11-30",
      "added": [],
      "changed": [
        { "id": "minecraft:savanna", "field": "baseTemperature", "from": 1.2, "to": 2.0, "snapshot": "1.18 Pre-release 6", "confidence": "high" },
        { "id": "minecraft:savanna_plateau", "field": "baseTemperature", "from": 1.0, "to": 2.0, "snapshot": "1.18 Pre-release 6", "confidence": "high" },
        { "id": "minecraft:windswept_savanna", "field": "baseTemperature", "from": 1.1, "to": 2.0, "snapshot": "1.18 Pre-release 6", "confidence": "high" }
      ],
      "removed": [
        "minecraft:deep_warm_ocean (removed in 21w43a)",
        "25 height-variation sub-biomes removed and merged in 21w40a (see docs section 2.4a)"
      ],
      "renamed": [
        { "oldName": "Giant Spruce Taiga", "newName": "Old Growth Spruce Taiga" },
        { "oldName": "Giant Tree Taiga", "newName": "Old Growth Pine Taiga" },
        { "oldName": "Gravelly Mountains", "newName": "Windswept Gravelly Hills" },
        { "oldName": "Jungle Edge", "newName": "Sparse Jungle" },
        { "oldName": "Lofty Peaks", "newName": "Jagged Peaks" },
        { "oldName": "Mountains", "newName": "Windswept Hills" },
        { "oldName": "Shattered Savanna", "newName": "Windswept Savanna" },
        { "oldName": "Snowcapped Peaks", "newName": "Frozen Peaks" },
        { "oldName": "Snowy Tundra", "newName": "Snowy Plains" },
        { "oldName": "Stone Shore", "newName": "Stony Shore" },
        { "oldName": "Tall Birch Forest", "newName": "Old Growth Birch Forest" },
        { "oldName": "Wooded Badlands Plateau", "newName": "Wooded Badlands" },
        { "oldName": "Wooded Mountains", "newName": "Windswept Forest" }
      ],
      "note": "These changes happened during the 1.18 development cycle (snapshot 21w40a / 1.18 Pre-release 6) and are therefore already baked into the 1.18.2 baseline."
    },
    { "mcVersion": "1.18.1", "released": "2021-12-10", "added": [], "changed": [] },
    { "mcVersion": "1.18.2", "released": "2022-02-28", "added": [], "changed": [], "note": "BASELINE" },
    {
      "mcVersion": "1.19",
      "released": "2022-06-07",
      "added": [
        { "id": "minecraft:deep_dark", "baseTemperature": 0.8, "downfall": 0.4, "precipitation": "RAIN", "dimension": "overworld", "kind": "cave", "firstSnapshot": "Deep Dark Experimental Snapshot 1", "officialSnapshot": "22w11a", "precipitationLiteralForThisVersionUnverified": true, "confidence": "high" },
        { "id": "minecraft:mangrove_swamp", "baseTemperature": 0.8, "downfall": 0.9, "precipitation": "RAIN", "dimension": "overworld", "firstSnapshot": "22w14a", "precipitationLiteralForThisVersionUnverified": true, "confidence": "high" }
      ],
      "changed": []
    },
    { "mcVersion": "1.19.1", "released": "2022-07-27", "added": [], "changed": [] },
    { "mcVersion": "1.19.2", "released": "2022-08-05", "added": [], "changed": [] },
    { "mcVersion": "1.19.3", "released": "2022-12-07", "added": [], "changed": [] },
    {
      "mcVersion": "1.19.4",
      "released": "2023-03-14",
      "added": [
        { "id": "minecraft:cherry_grove", "baseTemperature": 0.5, "downfall": 0.8, "precipitation": "RAIN", "dimension": "overworld", "firstSnapshot": "23w07a", "experimental": true, "experimentalDatapack": "Update 1.20", "becameDefaultIn": "1.20 (23w12a)", "confidence": "high" }
      ],
      "changed": [
        { "id": "*", "field": "precipitation", "description": "Datapack field 'precipitation' enum (none/rain/snow) removed; replaced by boolean 'has_precipitation'. Snow vs rain now derived solely from baseTemperature < 0.15. Snapshot 23w03a.", "confidence": "high" }
      ]
    },
    { "mcVersion": "1.20", "released": "2023-06-07", "added": [], "changed": [], "note": "cherry_grove became available without the experimental datapack (23w12a)." },
    { "mcVersion": "1.20.1", "released": "2023-06-12", "added": [], "changed": [] },
    { "mcVersion": "1.20.2", "released": "2023-09-21", "added": [], "changed": [] },
    { "mcVersion": "1.20.3", "released": "2023-12-05", "added": [], "changed": [] },
    { "mcVersion": "1.20.4", "released": "2023-12-07", "added": [], "changed": [] },
    { "mcVersion": "1.20.5", "released": "2024-04-23", "added": [], "changed": [], "note": "bogged started spawning in mangrove_swamp (24w07a) - mob spawn, not climate." },
    { "mcVersion": "1.20.6", "released": "2024-04-29", "added": [], "changed": [] },
    { "mcVersion": "1.21", "released": "2024-06-13", "added": [], "changed": [] },
    { "mcVersion": "1.21.1", "released": "2024-08-08", "added": [], "changed": [] },
    {
      "mcVersion": "1.21.2",
      "released": "2024-10-22",
      "added": [
        { "id": "minecraft:pale_garden", "baseTemperature": 0.7, "downfall": 0.8, "precipitation": "RAIN", "dimension": "overworld", "firstSnapshot": "24w40a", "experimental": true, "experimentalDatapack": "Winter Drop", "becameDefaultIn": "1.21.4 (24w44a)", "confidence": "high" }
      ],
      "changed": []
    },
    { "mcVersion": "1.21.3", "released": "2024-10-23", "added": [], "changed": [] },
    { "mcVersion": "1.21.4", "released": "2024-12-03", "added": [], "changed": [], "note": "pale_garden became available without the Winter Drop experimental datapack (24w44a)." },
    { "mcVersion": "1.21.5", "released": "2025-03-25", "added": [], "changed": [], "note": "pale_garden generates more frequently and larger (25w02a); woodland mansions can generate in it. Not a climate change." },
    { "mcVersion": "1.21.6", "released": "2025-06-17", "added": [], "changed": [] },
    { "mcVersion": "1.21.7", "released": "2025-06-30", "added": [], "changed": [] },
    { "mcVersion": "1.21.8", "released": "2025-07-17", "added": [], "changed": [] },
    { "mcVersion": "1.21.9", "released": "2025-09-30", "added": [], "changed": [] },
    { "mcVersion": "1.21.10", "released": "2025-10-07", "added": [], "changed": [] },
    { "mcVersion": "1.21.11", "released": "2025-12-09", "added": [], "changed": [], "note": "25w42a added 'environment attributes' for biomes; temperature/downfall/has_precipitation unchanged." },
    { "mcVersion": "26.1", "released": "2026-03-24", "officialName": "Tiny Takeover", "added": [], "changed": [] },
    { "mcVersion": "26.1.1", "released": "2026-04-01", "added": [], "changed": [] },
    { "mcVersion": "26.1.2", "released": "2026-04-09", "added": [], "changed": [] },
    {
      "mcVersion": "26.2",
      "released": "2026-06-16",
      "officialName": "Chaos Cubed",
      "added": [
        { "id": "minecraft:sulfur_caves", "baseTemperature": 0.8, "downfall": 0.4, "precipitation": "RAIN", "dimension": "overworld", "kind": "cave", "firstSnapshot": "26.2 snap1", "note": "Cave biome. has_precipitation is true and temperature >= 0.15, so the derived Biome.Precipitation is RAIN, but no rain actually falls underground.", "confidence": "high" }
      ],
      "changed": []
    },
    {
      "mcVersion": "26.3",
      "released": null,
      "plannedRelease": "2026-09-15",
      "released_flag": false,
      "officialName": "Wilderness Bound",
      "dropName": "Drop 3 of 2026",
      "added": [
        { "id": "minecraft:dappled_forest", "baseTemperature": 0.6, "downfall": 0.6, "precipitation": "RAIN", "dimension": "overworld", "firstSnapshot": "26.3 snap1", "note": "NOT RELEASED as of 2026-09-13. Values may change before release.", "confidence": "high (values), release-pending" }
      ],
      "changed": []
    }
  ]
}
```

---

## 5. 总结（≤400 字）

1.18.2 共有 61 个原版群系。到最新已发布版 26.2 为止，**只新增了 5 个**：1.19 加 `deep_dark`(深暗之域, 温度 0.8, RAIN) 与 `mangrove_swamp`(红树林沼泽, 0.8, RAIN)；1.19.4 以实验开关加、1.20 转正的 `cherry_grove`(樱花树林, 0.5, RAIN)；1.21.2 实验、1.21.4 转正的 `pale_garden`(苍白之园, 0.7, RAIN)；26.2 加 `sulfur_caves`(硫磺洞穴, 0.8, RAIN)。另有 1 个已进入开发版但**尚未发布**：26.3(计划 2026-09-15) 的 `dappled_forest`(斑驳森林, 0.6, RAIN)。六者**全属 Overworld**，Nether/End 零新增；期间**无任何群系被移除或改名**（那批删除与改名发生在 1.18 开发期快照 21w40a，已计入基线）。既有群系温度仅在 1.18 Pre-release 6 改过 savanna 家族三个（1.2/1.0/1.1 → 均 2.0），早于基线；降水则有 1.19.4 的全局语义变更(枚举→布尔 `has_precipitation`)。**没查到的**：1.18.2–1.19.3 各群系 `precipitation` 枚举的逐群系字面值；`dripstone_caves`/`lush_caves` 当年是否为 `"none"`；21w40a 改名表的资源位置映射（为推断）；26.3 正式数值仍可能变动。

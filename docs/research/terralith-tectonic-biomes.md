# Terralith 与 Tectonic 生物群系核查报告（Minecraft 1.18.2）

> 面向 WeatherSync 气候配置的数据核查文档。
> 每条结论附**来源 URL**与**置信度**（高 / 中 / 低）。
> **所有生物群系注册 id 均来自实际的 `data/<ns>/worldgen/biome/*.json` 文件清单，无一条为推测编造。**

---

## 0. 结论摘要（TL;DR）

| 问题 | 结论 |
|---|---|
| Terralith 1.18.2 需要 TerraBlender 吗？ | **不需要。** 1.18.2 分支是**纯数据包**（`data/` + `pack.mcmeta`，无 `fabric.mod.json` / `mods.toml`），通过替换 `minecraft:overworld` 维度与 noise settings 注入群系。Modrinth 上的「mod」版本（2.2.4+）**必需依赖 Cristel Lib**，TerraBlender 是可选的兼容开关。 |
| Terralith 注册了多少生物群系？ | **96 个 `terralith:` 群系**（1.18.2 分支实测）。 |
| Terralith 是否覆盖原版注册 id？ | **是。** 覆盖 **51 个 `minecraft:` 命名空间群系文件**（其中 50 个是 1.18.2 真实原版群系，另 1 个 `minecraft:deep_warm_ocean` 在 1.18.2 原版数据中不存在）。同时替换 `minecraft:overworld` 维度 / 维度类型 / noise settings。 |
| Terralith 改了原版温度/降水吗？ | **温度改了 5 个**（`deep_frozen_ocean`、`savanna`、`savanna_plateau`、`stony_shore`、`windswept_savanna`）；**降水 0 个被改**。 |
| Tectonic 作者是谁 / 用途？ | **Apollo**（GitHub `Apollounknowndev`）。用途是**地形塑形**（"Terrain shaping brought to new heights"）：大陆/山脉/深海/峡谷/沙丘，不是群系包。 |
| **Tectonic 有没有新增生物群系？** | **有。** 1.18.2 可用的 v1.x 系列注册了 **26 个 `tectonic:` 群系**（实测于 tag `mc1.18.2-0.1`），并覆盖 **46 个 `minecraft:` 群系**。⚠️ 注意：更新的 Tectonic v2 主打纯地形，但其 1.18.2 版本（2.3.5a）源码在 GitHub 上无对应标签，**其群系清单未能核证**（见 §4）。 |
| Tectonic 支持 1.18.2 吗？ | **支持。** Modrinth 项目 `tectonic` 的 `game_versions` 明确包含 `1.18.2`。1.18.2 最新版本为 **v2.3.5a**（2024-06-05）。 |
| 两者组合 | 「Tectonic 模组版」内置 Terralith 兼容；**Terratonic** 独立数据包只支持 1.19+，**1.18.2 无独立 Terratonic**。 |

---

## 1. 核查方法与数据来源

本机 DNS 对 `github.com` / `raw.githubusercontent.com` / `cdn.jsdelivr.net` / `api.modrinth.com` / `cdn.modrinth.com` 均不可用（超时或被污染）。实际可用通道：

- **`fastly.jsdelivr.net`** —— 可用，用于拉取 GitHub 仓库**文件内容**（`https://fastly.jsdelivr.net/gh/<owner>/<repo>@<ref>/<path>`）。
- **`data.jsdelivr.com`** —— 可用，用于拉取仓库**完整文件清单**（`?structure=flat`），是判断「有没有 `worldgen/biome` 目录」的关键证据。
- **`api.github.com`** —— 可用，用于列 tag / branch。
- Modrinth API 与网页通过检索工具读取。

**核心事实：GitHub tag 与 MC 版本不一一对应。** Terralith 仓库的 tag（`1.6.4`/`2.0.12a`/`2.2.1a`/`2.3.5` = `pack_format` 10 → 1.19.3；`2.2.4`/`2.4.11`/`2.5.9` = `pack_format` 15 → 1.20+）**没有一个对应 1.18.2**。1.18.2 的内容在**分支**上：`1.18.1`、`1.18.2`、`1.18.2-mod`、`1.19.3`、`1.19.4`、`1.20`、`main`（默认分支 `1.20`）。

本报告最终采用的 1.18.2 数据源：

| 模组 | 采用的 ref | `pack_format` | 证据 |
|---|---|---|---|
| Terralith | 分支 `1.18.2` | **9** | 分支名即 1.18.2；`pack_format 9`（1.18.2）；使用 1.18.2 专有目录名 `configured_structure_feature`（1.19 起改名为 `structure`）；含 `data/minecraft/dimension/overworld.json` |
| Tectonic | tag `mc1.18.2-0.1` | **9** | `pack.mcmeta` 描述字符串为 `"v0.1 for 1.18.2"`；无 1.19 才有的 `mangrove_swamp` |

来源（文件清单与内容）：
- [Terralith 分支清单](https://data.jsdelivr.com/v1/packages/gh/Stardust-Labs-MC/Terralith@1.18.2/?structure=flat) — 置信度**高**
- [Terralith `pack.mcmeta`](https://fastly.jsdelivr.net/gh/Stardust-Labs-MC/Terralith@1.18.2/pack.mcmeta) — 置信度**高**
- [Tectonic `mc1.18.2-0.1` 清单](https://data.jsdelivr.com/v1/packages/gh/Apollounknowndev/tectonic@mc1.18.2-0.1?structure=flat) — 置信度**高**
- [Tectonic `pack.mcmeta`](https://fastly.jsdelivr.net/gh/Apollounknowndev/tectonic@mc1.18.2-0.1/pack.mcmeta) — 置信度**高**

**原版基线**：为判断「温度/降水是否被改」，另外拉取了原版 1.18.2 数据包镜像
[misode/mcmeta@1.18.2-data](https://data.jsdelivr.com/v1/packages/gh/misode/mcmeta@1.18.2-data?structure=flat)（**61 个**原版群系 JSON，已排除 tags）作为对照基线，置信度**高**。

### 摄氏温度换算约定（本仓库自定，非模组数据）

模组文件里**只有** MC 量纲的 `temperature`（float），**没有任何摄氏值**。下表 `°C` 列由本报告按显式线性公式推导，**属于推断**：

```
°C = (temperature - 0.15) × 28         // 0.15 = 原版积雪临界值 → 0 °C
正常温度范围 = °C ± 6                  // 昼夜/季节摆动幅度，经验值
```

（例：`temperature 0.8` → 18 °C，范围 12~24 °C；`2.0` → 52 °C；`-0.5` → −18 °C。）
**置信度：低（本仓库自定义约定）**。若 WeatherSync 已有既定换算，应以既有约定为准。

### 降水字段说明（关于 1.19.4 的改动）

1.18.2 使用字符串字段 `"precipitation": "rain" | "snow" | "none"`（**已实测确认**，本报告全部取值来自该字段）。1.19.4 起该字段被布尔字段 `"has_precipitation"` 取代，`category` 字段同时被移除。**因此本报告所有 `precipitation` 值均为 1.18.2 的权威形态；`has_precipitation` 不适用于 1.18.2。** 置信度**高**。

---

## 2. Terralith

- 作者：**Starmute**（原始作者）/ 现由 **Stardust Labs** 团队维护（Modrinth 项目组织 `xdGr4sFb`）。
- 用途：重制主世界地形与群系，「almost 100 new biomes」，官方文案称 "adds over 95 brand new biomes"。
- 1.18.2 形态：**纯数据包**。`1.18.2` 分支只有 `pack.mcmeta` + `data/`（3056 个文件），**没有** `fabric.mod.json`、`mods.toml`、`META-INF`。`1.18.2-mod` 分支才是 Java mod 包装层（含 `fabric/src/...`、`forge/src/...`），**该分支内 0 个群系 JSON**——证明群系数据完全在数据包里。
- **TerraBlender 结论**：数据包形态**不需要** TerraBlender。它通过 `data/minecraft/dimension/overworld.json` + `dimension_type/overworld.json` + `worldgen/noise_settings/overworld.json` 直接替换原版主世界生成器，这也是官方文档所说「biome-adding datapacks are typically not compatible」的原因。mod 形态（2.2.4+）必需 [Cristel Lib](https://modrinth.com/mod/cristel-lib)，TerraBlender 仅是可配置的兼容模式（2.2.6 更新日志："Fixed crash that would occur when not using Terrablender compatibility mode"）。置信度**高**。

### 2.1 Terralith 全部 96 个注册 id

来源：`data/terralith/worldgen/biome/*.json`（含子目录 `cave/`）。置信度**高**（`temperature` / `precipitation` / `category` 三项直接读自文件）。

| 注册 id | temperature | °C（推断） | 降水 | 原版 category | 大类（推断） |
|---|---|---|---|---|---|
| `terralith:alpha_islands` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:alpha_islands_winter` | 0 | -4 (-10~2) | SNOW | forest | 温带森林 |
| `terralith:alpine_grove` | -0.2 | -10 (-16~-4) | SNOW | forest | 温带森林 |
| `terralith:alpine_highlands` | 0.45 | 8 (2~14) | RAIN | taiga | 寒温带针叶林 |
| `terralith:amethyst_canyon` | 0.95 | 22 (16~28) | RAIN | jungle | 热带雨林 |
| `terralith:amethyst_rainforest` | 0.95 | 22 (16~28) | RAIN | jungle | 热带雨林 |
| `terralith:ancient_sands` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `terralith:arid_highlands` | 1.6 | 41 (35~47) | NONE | savanna | 热带草原 |
| `terralith:ashen_savanna` | 1 | 24 (18~30) | NONE | savanna | 热带草原 |
| `terralith:basalt_cliffs` | 0.5 | 10 (4~16) | RAIN | beach | 海岸 |
| `terralith:birch_taiga` | 0.22 | 2 (-4~8) | RAIN | forest | 温带森林 |
| `terralith:blooming_plateau` | 0.5 | 10 (4~16) | RAIN | mountain | 山地 |
| `terralith:blooming_valley` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:brushland` | 1.2 | 29 (23~35) | NONE | savanna | 热带草原 |
| `terralith:bryce_canyon` | 2 | 52 (46~58) | NONE | mesa | 恶地/平顶山 |
| `terralith:caldera` | 0.45 | 8 (2~14) | RAIN | taiga | 寒温带针叶林 |
| `terralith:cave/andesite_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cave/crystal_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cave/deep_caves` | 0.6 | 13 (7~19) | RAIN | underground | 洞穴 |
| `terralith:cave/desert_caves` | 0.8 | 18 (12~24) | RAIN | underground | 洞穴 |
| `terralith:cave/diorite_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cave/frostfire_caves` | 0.8 | 18 (12~24) | RAIN | underground | 洞穴 |
| `terralith:cave/fungal_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cave/granite_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cave/ice_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cave/infested_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cave/mantle_caves` | 2 | 52 (46~58) | RAIN | underground | 洞穴 |
| `terralith:cave/thermal_caves` | 0.8 | 18 (12~24) | RAIN | underground | 洞穴 |
| `terralith:cave/tuff_caves` | 1 | 24 (18~30) | RAIN | underground | 洞穴 |
| `terralith:cloud_forest` | 0.25 | 3 (-3~9) | RAIN | taiga | 寒温带针叶林 |
| `terralith:cold_shrubland` | 0.14 | 0 (-6~6) | RAIN | plains | 平原/草原 |
| `terralith:desert_canyon` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `terralith:desert_oasis` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `terralith:desert_spires` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `terralith:emerald_peaks` | 0.1 | -1 (-7~5) | SNOW | mountain | 山地 |
| `terralith:forested_highlands` | 0.36 | 6 (0~12) | RAIN | taiga | 寒温带针叶林 |
| `terralith:fractured_savanna` | 1.1 | 27 (21~33) | NONE | savanna | 热带草原 |
| `terralith:frozen_cliffs` | 0 | -4 (-10~2) | SNOW | icy | 雪原/冰原 |
| `terralith:glacial_chasm` | 0 | -4 (-10~2) | SNOW | icy | 雪原/冰原 |
| `terralith:granite_cliffs` | 0.4 | 7 (1~13) | RAIN | beach | 海岸 |
| `terralith:gravel_beach` | 0.8 | 18 (12~24) | RAIN | beach | 海岸 |
| `terralith:gravel_desert` | 0.14 | 0 (-6~6) | SNOW | icy | 雪原/冰原 |
| `terralith:haze_mountain` | 0.3 | 4 (-2~10) | RAIN | extreme_hills | 山地 |
| `terralith:highlands` | 0.4 | 7 (1~13) | RAIN | plains | 平原/草原 |
| `terralith:hot_shrubland` | 0.8 | 18 (12~24) | RAIN | plains | 平原/草原 |
| `terralith:ice_marsh` | 0.14 | 0 (-6~6) | SNOW | swamp | 沼泽 |
| `terralith:jungle_mountains` | 0.95 | 22 (16~28) | RAIN | jungle | 热带雨林 |
| `terralith:lavender_forest` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:lavender_valley` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:lush_valley` | 0.4 | 7 (1~13) | RAIN | taiga | 寒温带针叶林 |
| `terralith:mirage_isles` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:moonlight_grove` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:moonlight_valley` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:mountain_steppe` | 0.4025 | 7 (1~13) | RAIN | plains | 平原/草原 |
| `terralith:orchid_swamp` | 0.8 | 18 (12~24) | RAIN | swamp | 沼泽 |
| `terralith:painted_mountains` | 1 | 24 (18~30) | RAIN | mountain | 山地 |
| `terralith:red_oasis` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `terralith:rocky_jungle` | 0.95 | 22 (16~28) | RAIN | jungle | 热带雨林 |
| `terralith:rocky_mountains` | 1 | 24 (18~30) | NONE | mountain | 山地 |
| `terralith:rocky_shrubland` | 0.14 | 0 (-6~6) | RAIN | plains | 平原/草原 |
| `terralith:sakura_grove` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:sakura_valley` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `terralith:sandstone_valley` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `terralith:savanna_badlands` | 1 | 24 (18~30) | NONE | savanna | 热带草原 |
| `terralith:savanna_slopes` | 1 | 24 (18~30) | NONE | savanna | 热带草原 |
| `terralith:scarlet_mountains` | 0.1 | -1 (-7~5) | SNOW | mountain | 山地 |
| `terralith:shield` | 0.4 | 7 (1~13) | RAIN | taiga | 寒温带针叶林 |
| `terralith:shield_clearing` | 0.4 | 7 (1~13) | RAIN | plains | 平原/草原 |
| `terralith:shrubland` | 1.2 | 29 (23~35) | NONE | savanna | 热带草原 |
| `terralith:siberian_grove` | 0.23 | 2 (-4~8) | RAIN | taiga | 寒温带针叶林 |
| `terralith:siberian_taiga` | 0.3 | 4 (-2~10) | RAIN | taiga | 寒温带针叶林 |
| `terralith:skylands` | 0.5 | 10 (4~16) | RAIN | ocean | 海洋 |
| `terralith:skylands_autumn` | 0.5 | 10 (4~16) | RAIN | ocean | 海洋 |
| `terralith:skylands_spring` | 0.5 | 10 (4~16) | RAIN | ocean | 海洋 |
| `terralith:skylands_summer` | 0.5 | 10 (4~16) | RAIN | ocean | 海洋 |
| `terralith:skylands_winter` | 0.2 | 1 (-5~7) | SNOW | ocean | 海洋 |
| `terralith:snowy_badlands` | 0 | -4 (-10~2) | SNOW | mesa | 恶地/平顶山 |
| `terralith:snowy_maple_forest` | 0.1 | -1 (-7~5) | SNOW | taiga | 寒温带针叶林 |
| `terralith:snowy_shield` | 0.1 | -1 (-7~5) | **RAIN** | taiga | 寒温带针叶林 |
| `terralith:steppe` | 0.4 | 7 (1~13) | RAIN | plains | 平原/草原 |
| `terralith:stony_spires` | 0.7 | 15 (9~21) | RAIN | mountain | 山地 |
| `terralith:temperate_highlands` | 0.9 | 21 (15~27) | RAIN | taiga | 寒温带针叶林 |
| `terralith:tropical_jungle` | 0.95 | 22 (16~28) | RAIN | jungle | 热带雨林 |
| `terralith:valley_clearing` | 0.4 | 7 (1~13) | RAIN | plains | 平原/草原 |
| `terralith:volcanic_crater` | 1 | 24 (18~30) | RAIN | mountain | 山地 |
| `terralith:volcanic_peaks` | 1 | 24 (18~30) | RAIN | mountain | 山地 |
| `terralith:warm_river` | 0.5 | 10 (4~16) | RAIN | river | 河流 |
| `terralith:warped_mesa` | 2 | 52 (46~58) | NONE | mesa | 恶地/平顶山 |
| `terralith:white_cliffs` | 0.4 | 7 (1~13) | RAIN | beach | 海岸 |
| `terralith:white_mesa` | 2 | 52 (46~58) | NONE | mesa | 恶地/平顶山 |
| `terralith:windswept_spires` | 0.2 | 1 (-5~7) | RAIN | extreme_hills | 山地 |
| `terralith:wintry_forest` | -0.5 | -18 (-24~-12) | SNOW | taiga | 寒温带针叶林 |
| `terralith:wintry_lowlands` | -0.5 | -18 (-24~-12) | SNOW | taiga | 寒温带针叶林 |
| `terralith:yellowstone` | 0.24775 | 3 (-3~9) | RAIN | taiga | 寒温带针叶林 |
| `terralith:yosemite_cliffs` | 0.375 | 6 (0~12) | RAIN | taiga | 寒温带针叶林 |
| `terralith:yosemite_lowlands` | 0.25 | 3 (-3~9) | RAIN | taiga | 寒温带针叶林 |

合计 **96** 个（83 个地面 + 13 个 `cave/`）。注意 `terralith:snowy_shield` 温度 0.1（明显偏冷）但降水是 **RAIN** 而非 SNOW——这是文件里的实际值，不是笔误，配置时需小心。

### 2.2 被覆盖/改写的原版注册 id（51 个）

Terralith 在 `data/minecraft/worldgen/biome/` 下放了 **51 个文件**，直接以同名 id 覆盖原版群系。**结论：是的，它覆盖原版注册 id。**

**其中温度被真实改动的只有 5 个**（数值比较，已排除 `2.0` vs `2` 这类纯格式差异）：

| 注册 id | 原版 1.18.2 temperature | Terralith temperature | 降水是否改变 |
|---|---|---|---|
| `minecraft:deep_frozen_ocean` | 0.5 | **0.0** | 否（rain→rain） |
| `minecraft:savanna` | 2.0 | **1.2** | 否（none→none） |
| `minecraft:savanna_plateau` | 2.0 | **1.0** | 否（none→none） |
| `minecraft:stony_shore` | 0.2 | **0.6** | 否（rain→rain） |
| `minecraft:windswept_savanna` | 2.0 | **1.1** | 否（none→none） |

**降水形态：51 个覆盖里 0 个被改动。** 置信度**高**。

其余 46 个覆盖的 `temperature` / `precipitation` 与原版**完全一致**，改动集中在 `features` / `spawners` / `carvers` / `effects`（生物群系颜色）。例证：`data/minecraft/worldgen/biome/plains.json` 中插入了 Terralith 自定义特征 `terralith:plains/patch_grass`、`terralith:plains/patch_tall_grass`，并在 carvers 里加了 `terralith:deep_ravine`。**因此原版群系在 Terralith 下「温度/降水不变、内容大改」**——对天气模组是好消息：原版气候表基本可沿用。

覆盖清单（51 个 id，全部来自实际文件）：

```
minecraft:badlands                    minecraft:bamboo_jungle              minecraft:beach
minecraft:birch_forest                minecraft:cold_ocean                 minecraft:dark_forest
minecraft:deep_cold_ocean             minecraft:deep_frozen_ocean          minecraft:deep_lukewarm_ocean
minecraft:deep_ocean                  minecraft:deep_warm_ocean            minecraft:desert
minecraft:dripstone_caves             minecraft:eroded_badlands            minecraft:flower_forest
minecraft:forest                      minecraft:frozen_ocean               minecraft:frozen_peaks
minecraft:frozen_river                minecraft:grove                      minecraft:ice_spikes
minecraft:jagged_peaks                minecraft:jungle                     minecraft:lukewarm_ocean
minecraft:lush_caves                  minecraft:meadow                     minecraft:mushroom_fields
minecraft:ocean                       minecraft:old_growth_birch_forest    minecraft:old_growth_pine_taiga
minecraft:old_growth_spruce_taiga     minecraft:plains                     minecraft:river
minecraft:savanna                     minecraft:savanna_plateau            minecraft:snowy_beach
minecraft:snowy_plains                minecraft:snowy_slopes               minecraft:snowy_taiga
minecraft:sparse_jungle               minecraft:stony_peaks                minecraft:stony_shore
minecraft:sunflower_plains            minecraft:swamp                      minecraft:taiga
minecraft:warm_ocean                  minecraft:windswept_forest           minecraft:windswept_gravelly_hills
minecraft:windswept_hills             minecraft:windswept_savanna          minecraft:wooded_badlands
```

> ⚠️ `minecraft:deep_warm_ocean`（温度 0.5 / rain）**不在** 1.18.2 原版 61 个群系文件中，属于 Terralith 以 `minecraft:` 命名空间**新增**的 id，而非覆盖。见 §4。

### 2.3 Terralith 1.18.2 版本可用性与链接

1.18.2 在 Modrinth 上只有 **mod 形态**（loader: fabric / forge / quilt），共 4 个版本：

| 版本 | 发布日 | 依赖 |
|---|---|---|
| **2.2.6**（1.18.2 最新） | 2023-12-08 | Cristel Lib（必需）、Unfixed Seeds（可选）、Fabric API（可选） |
| 2.2.5 | 2023-12-07 | 同上 |
| 2.2.4 | 2023-05-30 | 同上；此版本起「Now compatible with Terrablender by default」 |
| 2.2.3 | 2022-11-23 | 无依赖（1.18.2 首个 Modrinth 版本） |

**数据包形态**没有单独的 Modrinth 文件，需从 GitHub 分支 `1.18.2` 取得（或从 mod jar 内提取 `data/`）。

- Modrinth: <https://modrinth.com/mod/terralith>（project `8oi3bsk5`）
- CurseForge: <https://www.curseforge.com/minecraft/mc-mods/terralith>
- GitHub: <https://github.com/Stardust-Labs-MC/Terralith>（1.18.2 分支：`.../tree/1.18.2`）
- 依赖 Cristel Lib: <https://modrinth.com/mod/cristel-lib>

置信度**高**。

---

## 3. Tectonic

### 3.1 作者与用途

- 作者：**Apollo**（Modrinth 作者名 `Apollo`，GitHub `Apollounknowndev`）。**不是 Starmute。** Starmute/Stardust Labs 是 Terralith 的作者；Apollo 另外做了 Terralith↔Tectonic 的兼容包 Terratonic。置信度**高**。
- 用途：**地形塑形**。官方描述 "Terrain shaping brought to new heights, grander and more varied than ever before!"，功能为大陆与岛屿、更深的海洋、巨型山脉、地下河、岩浆隧道、峡谷、沙丘、高原、湿地。类别 `worldgen`（无 `biomes` 类目）。置信度**高**。

### 3.2 **明确回答：Tectonic 有没有新增生物群系？**

**有——至少在 1.18.2 可用的 v1.x 系列中存在。**

证据不是文档措辞，而是**数据包目录结构**：tag `mc1.18.2-0.1` 的 `?structure=flat` 清单里存在 `data/tectonic/worldgen/biome/` 目录，含 **26 个 JSON**；另有 `data/tectonic/tags/worldgen/biome/all_tectonic_biomes.json`（一个把所有 Tectonic 群系聚合起来的标签，这本身就证明它有自有群系集合）。

```
/data/tectonic/worldgen/biome/   ← 26 个文件（即"新增了群系"的直接证据）
/data/minecraft/worldgen/biome/  ← 46 个文件（覆盖原版）
/data/tectonic/tags/worldgen/biome/{all_tectonic_biomes,is_desert_dunes,is_oceanside_cliff,is_underground_river}.json
```

结论对照：

| 说法 | 核证结果 |
|---|---|
| 「Tectonic 只改地形高度/形状」 | **对 v2/v3 的定位成立，但对 1.18.2 可用的 v1.x 不成立** |
| 「Tectonic 没有新增生物群系」 | **不成立。** v1.x 明确注册了 `tectonic:` 命名空间群系 |

置信度**高**（文件清单为硬证据）。

### 3.3 Tectonic（`mc1.18.2-0.1`）全部 26 个注册 id

| 注册 id | temperature | °C（推断） | 降水 | 原版 category | 大类（推断） |
|---|---|---|---|---|---|
| `tectonic:alpine_meadow` | 0.4 | 7 (1~13) | RAIN | mountain | 山地 |
| `tectonic:ancient_river` | 0.8 | 18 (12~24) | RAIN | underground | 洞穴 |
| `tectonic:autumn_birch_forest` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `tectonic:autumn_forest` | 0.7 | 15 (9~21) | RAIN | forest | 温带森林 |
| `tectonic:cold_beach` | 0.8 | 18 (12~24) | RAIN | beach | 海岸 |
| `tectonic:cold_cliffs` | 0.8 | 18 (12~24) | RAIN | beach | 海岸 |
| `tectonic:cold_plains` | 0.25 | 3 (-3~9) | RAIN | plains | 平原/草原 |
| `tectonic:cold_river` | 0.5 | 10 (4~16) | RAIN | river | 河流 |
| `tectonic:coral_river` | 0.5 | 10 (4~16) | RAIN | underground | 洞穴 |
| `tectonic:desert_dunes` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `tectonic:dripstone_river` | 0.8 | 18 (12~24) | RAIN | underground | 洞穴 |
| `tectonic:grasslands` | 1.4 | 35 (29~41) | RAIN | plains | 平原/草原 |
| `tectonic:icy_cliffs` | 0 | -4 (-10~2) | RAIN | beach | 海岸 |
| `tectonic:icy_river` | 0 | -4 (-10~2) | SNOW | river | 河流 |
| `tectonic:island` | 0.8 | 18 (12~24) | RAIN | plains | 平原/草原 |
| `tectonic:lagoon` | 0.5 | 10 (4~16) | RAIN | ocean | 海洋 |
| `tectonic:lantern_river` | 0.8 | 18 (12~24) | RAIN | underground | 洞穴 |
| `tectonic:lukewarm_beach` | 0.8 | 18 (12~24) | RAIN | beach | 海岸 |
| `tectonic:lukewarm_cliffs` | 0.8 | 18 (12~24) | RAIN | beach | 海岸 |
| `tectonic:lukewarm_river` | 0.5 | 10 (4~16) | RAIN | river | 河流 |
| `tectonic:lush_river` | 0.5 | 10 (4~16) | RAIN | river | 河流 |
| `tectonic:old_growth_snowy_taiga` | 0 | -4 (-10~2) | RAIN | taiga | 寒温带针叶林 |
| `tectonic:red_desert` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `tectonic:red_desert_dunes` | 2 | 52 (46~58) | NONE | desert | 沙漠 |
| `tectonic:sandstone_cliffs` | 2 | 52 (46~58) | NONE | beach | 海岸 |
| `tectonic:warm_river` | 0.5 | 10 (4~16) | RAIN | river | 河流 |

配套的 Terralith 版本（tag `mc1.18.2-1.1.1`，实测 30 个）额外包含：`badlands_spires`、`evergreen_forest`、`old_growth_evergreen_forest`、`white_cliffs`。**但该 tag 的 `pack.mcmeta` 描述写的是 "v1.1.1 for 1.19.x" 且含 `mangrove_swamp`（1.19 群系），说明该 GitHub tag 内容实为 1.19，命名不可靠**——见 §4。

### 3.4 Tectonic 覆盖的原版 id（46 个）

`data/minecraft/worldgen/biome/` 下 46 个文件。**温度只有 1 个被改**：

| 注册 id | 原版 1.18.2 | Tectonic | 降水是否改变 |
|---|---|---|---|
| `minecraft:stony_shore` | 0.2 / rain | **0.8 / rain** | 否 |

其余 45 个温度与降水与原版一致。覆盖清单：

```
minecraft:badlands                 minecraft:bamboo_jungle          minecraft:beach
minecraft:birch_forest             minecraft:cold_ocean             minecraft:dark_forest
minecraft:deep_cold_ocean          minecraft:deep_frozen_ocean      minecraft:deep_lukewarm_ocean
minecraft:deep_ocean               minecraft:desert                 minecraft:dripstone_caves
minecraft:eroded_badlands          minecraft:flower_forest          minecraft:forest
minecraft:frozen_ocean             minecraft:frozen_peaks           minecraft:frozen_river
minecraft:grove                    minecraft:ice_spikes             minecraft:jagged_peaks
minecraft:jungle                   minecraft:lukewarm_ocean         minecraft:lush_caves
minecraft:meadow                   minecraft:mushroom_fields        minecraft:ocean
minecraft:old_growth_birch_forest  minecraft:old_growth_pine_taiga  minecraft:old_growth_spruce_taiga
minecraft:plains                   minecraft:river                  minecraft:savanna
minecraft:savanna_plateau          minecraft:snowy_beach            minecraft:snowy_plains
minecraft:snowy_slopes             minecraft:snowy_taiga            minecraft:sparse_jungle
minecraft:stony_peaks              minecraft:stony_shore            minecraft:sunflower_plains
minecraft:swamp                    minecraft:taiga                  minecraft:warm_ocean
minecraft:wooded_badlands
```

以上 46 个是 tag `mc1.18.2-0.1` 的实测结果（已排除 tags 目录）。注意该 46 个**不含** `windswept_forest` / `windswept_hills` / `windswept_gravelly_hills`（这些是 Terralith 的覆盖项，Tectonic v0.1 未覆盖）。

⚠️ 另一 tag `mc1.18.2-1.1.1` 的覆盖清单有 **52** 个，且包含 `minecraft:mangrove_swamp`（1.19 群系）与 `minecraft:deep_dark`（1.19 群系），而其 `pack.mcmeta` 自述为 "v1.1.1 for 1.19.x"——**证明该 tag 命名与内容不符，实为 1.19 内容**。本报告因此不采用它。**这是本报告对 Tectonic 1.18.2 数据的主要保留项。**

### 3.5 Tectonic + Terralith 的组合行为

- **模组版组合（1.18.2 推荐）**：Tectonic 的 mod 版本**内置** Terralith 兼容。证据是 1.18.2 的更新日志明确提到修复了二者共存问题：
  - `v2.2.1a`（1.18.2 专属热修）："Fixed crashing with Terralith."
  - `v2.3.4a`："Fixed Terratonic being non-functional on 1.18.2."
  - `v2.3a`："Fixed Terratonic 1.18.2 not having surfaces or oceans."
  - `v2.3.3`："Fixed crashing when using the Increased Height setting with Terratonic."
- **数据包版组合**：官方说明「If you are using the datapack versions, you must use **Terratonic** instead of Tectonic」。但 **Terratonic 独立数据包的 `game_versions` 从 1.19 起，不含 1.18.2**（[Modrinth Terratonic](https://modrinth.com/datapack/terratonic)，project `3L2L6sWL`）。所以 **1.18.2 没有独立的 Terratonic 数据包**，只能用 Tectonic mod 内置的兼容。
- **组合后行为**：Terralith 提供群系与地表，Tectonic 重塑高度/山脉/海洋深度；Tectonic 的海岛地形在装了 Terralith 时会生成 Caldera 与 Volcano（官方文案："With Terralith installed, these islands spawn Calderas and Volcanoes!"）。置信度**中**（来自官方文案与更新日志，未实机验证）。

### 3.6 Tectonic 1.18.2 版本可用性与链接

**Tectonic 支持 1.18.2**（`game_versions` 含 `1.18.2`）。1.18.2 版本序列（部分）：

- 数据包（loader `datapack`）：v1.1.1、v1.1.2、v1.1.3、v1.1.4、v1.1.5、v1.1.7/1.1.7a、v1.1.10/1.1.10a、**v2.2.2**（2024-04-24，单文件同时声明 1.18.2~1.20.6）
- 模组（fabric / forge / quilt）：v0.1、v1.1、v1.1.1、v1.1.2、v1.1.3、v1.1.4、v1.1.5、v1.1.7/1.1.7a、v1.1.10/1.1.10a、v2.2.1/2.2.1a、v2.3、v2.3.1、v2.3.2、v2.3.3、v2.3.4/2.3.4a、v2.3.5、**v2.3.5a（1.18.2 最新，2024-06-05）**

> 注意：Tectonic 官方页面声明「Tectonic's supported versions right now are **1.21.1** and **26.1**. Other versions will not have issues fixed on them.」——1.18.2 属**已停止维护**，能跑但不再修 bug。置信度**高**。

- Modrinth: <https://modrinth.com/mod/tectonic>（project `lWDHr9jE`；数据包页 <https://modrinth.com/datapack/tectonic>）
- CurseForge: <https://www.curseforge.com/minecraft/mc-mods/tectonic>
- GitHub: <https://github.com/Apollounknowndev/tectonic>
- Terratonic（1.19+）: <https://modrinth.com/datapack/terratonic>

---

## 4. 不确定 / 未确证

逐条列出，凡属推断均已标注。

1. **Terralith `1.18.2` 分支 ≠ 已发布的 2.2.6 jar（推断）。** 我用分支名 + `pack_format 9` + `configured_structure_feature`（1.18.2 目录名）+ 分支存在 `dimension/overworld.json` 判定它是 1.18.2 数据包。**未**逐字节比对 Modrinth 上 `Terralith_1.18.2_v2.2.6.jar` 内的 `data/`（`cdn.modrinth.com` 在本机不可达）。**置信度：中高。** 96 这个数字对 2.2.6 有较小误差风险。

2. **Tectonic 1.18.2 的群系清单以 v0.1 为准，最新 2.3.5a 的清单未确证。** 我采用 tag `mc1.18.2-0.1`（`pack.mcmeta` 自述 "v0.1 for 1.18.2"）。但 Modrinth 上 1.18.2 最新是 **2.3.5a**，而 GitHub 无 v2.x 的 1.18.2 标签（仓库只有 `mc1.18.2-*`、`1.1.3`、`mc1.19-*` 及 v2/v3 分支的 1.20+）。**更新日志显示 v1.x 后续删过群系**：v1.1 移除 `ancient_river`（文件保留）、v1.1.4 移除 `autumn_forest` 与 `autumn_birch_forest`、v1.1.7 移除 `lagoon`。**故 2.3.5a 的 `tectonic:` 群系数很可能少于 26。置信度：低（对 2.3.5a）／高（对 v0.1）；建议以实际 jar 内的 `data/tectonic/worldgen/biome/` 为准。**

3. **Tectonic 是否在 v2 彻底移除自有群系——未确证。** v1.1.10 更新日志称 "Tectonic is shifting towards focusing solely on terrain generation"（上下文是移除自定义树木）。Tectonic 1.18.2 数据包 v2.2.2 体积仅 120 KB，而 v1.1.10 数据包 316 KB，**体积差异暗示 v2 大幅精简了内容（可能包括群系），但这是体积推断，非直接证据。置信度：低。**

4. **GitHub tag 命名不可靠。** tag `mc1.18.2-1.1.1` 的 `pack.mcmeta` 写着 "v1.1.1 for 1.19.x" 且含 `mangrove_swamp`；其 **52** 个 `minecraft:` 覆盖中也有 `deep_dark`（1.19）。**凡以 tag 名推断 MC 版本都可能出错，必须以 `pack.mcmeta` + 目录结构交叉验证。置信度：高（这是已确证的陷阱）。**

5. **`minecraft:deep_warm_ocean` 的性质。** 它不在原版 1.18.2 的 61 个群系文件中，因此 Terralith 是用 `minecraft:` 命名空间**新增**它。但该 id 在 1.18.2 注册表里是否仍作为「未使用的遗留群系」存在，**未确证**。**置信度：中。**

6. **摄氏温度全部是推断。** 模组文件只提供 MC 量纲 `temperature`（float）。`°C = (T − 0.15) × 28` 与 `±6` 摆动带是本报告为方便配置而设的约定，**不是模组或原版数据**。荒漠类的 52 °C 是线性外推结果，实际配置建议设上限。**置信度：低。**

7. **中文「大类」列是推断。** 该列由原版 `category` 字段（forest / taiga / jungle / desert / mesa / savanna / mountain / extreme_hills / beach / ocean / river / swamp / icy / underground / plains）映射而来。原版 `category` 是**权威值**（置信度高）；中文归类是**我的映射**（置信度中）。

8. **覆盖群系的改动范围只核证了温度与降水。** 我确认了 51（Terralith）/ 46（Tectonic）个覆盖文件与「features / spawners / effects 有改动」（例：Terralith 的 `minecraft:plains` 插入 `terralith:plains/patch_grass`）。**但「每个覆盖文件具体改了哪些字段」未逐字段 diff。置信度：中。**

9. **Terralith 1.18.2 是否有独立 CurseForge 数据包文件——未核证。** 我只确认了 Modrinth 上 1.18.2 仅有模组形态。CurseForge 的 Terralith 文件列表未逐条核对。**置信度：低。**

10. **组合行为未实机验证。** §3.5 的结论来自官方文案与更新日志，未在 1.18.2 实机跑过。**置信度：中。**

11. **「Tectonic 的作者是 Starmute」这一说法的来源。** 任务描述里提到「作者 Apollo / Starmute？」。经核证：Tectonic 作者是 **Apollo**，Starmute 是 Terralith 作者。两者通过 Terratonic 相容，但**并非同一作者**。置信度**高**（Modrinth author 字段 + GitHub 仓库归属）。

---

## 5. 机器可读 JSON

> `baseTemperature` / `precipitation` / `category` 三项均直接读自 1.18.2 数据包文件（置信度 high）。
> `confidence` 反映的是**该生物群系 id 与基础数据对该版本的可核证程度**（见 §4）。

```json
{"mods":[
{"id":"terralith","mcVersion":"1.18.2","sourceRef":"github:Stardust-Labs-MC/Terralith@1.18.2","packFormat":9,"addsBiomes":true,"overwritesVanilla":true,"biomeCount":96,"overwrittenVanillaCount":51,
 "requiresTerraBlender":false,"datapackOnly":true,"modVersionRequires":["cl223EMc (Cristel Lib)"],
 "overwrittenVanilla":["minecraft:badlands","minecraft:bamboo_jungle","minecraft:beach","minecraft:birch_forest","minecraft:cold_ocean","minecraft:dark_forest","minecraft:deep_cold_ocean","minecraft:deep_frozen_ocean","minecraft:deep_lukewarm_ocean","minecraft:deep_ocean","minecraft:deep_warm_ocean","minecraft:desert","minecraft:dripstone_caves","minecraft:eroded_badlands","minecraft:flower_forest","minecraft:forest","minecraft:frozen_ocean","minecraft:frozen_peaks","minecraft:frozen_river","minecraft:grove","minecraft:ice_spikes","minecraft:jagged_peaks","minecraft:jungle","minecraft:lukewarm_ocean","minecraft:lush_caves","minecraft:meadow","minecraft:mushroom_fields","minecraft:ocean","minecraft:old_growth_birch_forest","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga","minecraft:plains","minecraft:river","minecraft:savanna","minecraft:savanna_plateau","minecraft:snowy_beach","minecraft:snowy_plains","minecraft:snowy_slopes","minecraft:snowy_taiga","minecraft:sparse_jungle","minecraft:stony_peaks","minecraft:stony_shore","minecraft:sunflower_plains","minecraft:swamp","minecraft:taiga","minecraft:warm_ocean","minecraft:windswept_forest","minecraft:windswept_gravelly_hills","minecraft:windswept_hills","minecraft:windswept_savanna","minecraft:wooded_badlands"],
 "vanillaTemperatureChanges":{"minecraft:deep_frozen_ocean":{"from":0.5,"to":0.0},"minecraft:savanna":{"from":2.0,"to":1.2},"minecraft:savanna_plateau":{"from":2.0,"to":1.0},"minecraft:stony_shore":{"from":0.2,"to":0.6},"minecraft:windswept_savanna":{"from":2.0,"to":1.1}},
 "vanillaPrecipitationChanges":{},
 "biomes":[
  {"id":"terralith:alpha_islands","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:alpha_islands_winter","baseTemperature":0.0,"precipitation":"SNOW","category":"forest","confidence":"high"},
  {"id":"terralith:alpine_grove","baseTemperature":-0.2,"precipitation":"SNOW","category":"forest","confidence":"high"},
  {"id":"terralith:alpine_highlands","baseTemperature":0.45,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:amethyst_canyon","baseTemperature":0.95,"precipitation":"RAIN","category":"jungle","confidence":"high"},
  {"id":"terralith:amethyst_rainforest","baseTemperature":0.95,"precipitation":"RAIN","category":"jungle","confidence":"high"},
  {"id":"terralith:ancient_sands","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"terralith:arid_highlands","baseTemperature":1.6,"precipitation":"NONE","category":"savanna","confidence":"high"},
  {"id":"terralith:ashen_savanna","baseTemperature":1.0,"precipitation":"NONE","category":"savanna","confidence":"high"},
  {"id":"terralith:basalt_cliffs","baseTemperature":0.5,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"terralith:birch_taiga","baseTemperature":0.22,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:blooming_plateau","baseTemperature":0.5,"precipitation":"RAIN","category":"mountain","confidence":"high"},
  {"id":"terralith:blooming_valley","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:brushland","baseTemperature":1.2,"precipitation":"NONE","category":"savanna","confidence":"high"},
  {"id":"terralith:bryce_canyon","baseTemperature":2.0,"precipitation":"NONE","category":"mesa","confidence":"high"},
  {"id":"terralith:caldera","baseTemperature":0.45,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:cave/andesite_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/crystal_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/deep_caves","baseTemperature":0.6,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/desert_caves","baseTemperature":0.8,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/diorite_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/frostfire_caves","baseTemperature":0.8,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/fungal_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/granite_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/ice_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/infested_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/mantle_caves","baseTemperature":2.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/thermal_caves","baseTemperature":0.8,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cave/tuff_caves","baseTemperature":1.0,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"terralith:cloud_forest","baseTemperature":0.25,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:cold_shrubland","baseTemperature":0.14,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:desert_canyon","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"terralith:desert_oasis","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"terralith:desert_spires","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"terralith:emerald_peaks","baseTemperature":0.1,"precipitation":"SNOW","category":"mountain","confidence":"high"},
  {"id":"terralith:forested_highlands","baseTemperature":0.36,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:fractured_savanna","baseTemperature":1.1,"precipitation":"NONE","category":"savanna","confidence":"high"},
  {"id":"terralith:frozen_cliffs","baseTemperature":0.0,"precipitation":"SNOW","category":"icy","confidence":"high"},
  {"id":"terralith:glacial_chasm","baseTemperature":0.0,"precipitation":"SNOW","category":"icy","confidence":"high"},
  {"id":"terralith:granite_cliffs","baseTemperature":0.4,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"terralith:gravel_beach","baseTemperature":0.8,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"terralith:gravel_desert","baseTemperature":0.14,"precipitation":"SNOW","category":"icy","confidence":"high"},
  {"id":"terralith:haze_mountain","baseTemperature":0.3,"precipitation":"RAIN","category":"extreme_hills","confidence":"high"},
  {"id":"terralith:highlands","baseTemperature":0.4,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:hot_shrubland","baseTemperature":0.8,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:ice_marsh","baseTemperature":0.14,"precipitation":"SNOW","category":"swamp","confidence":"high"},
  {"id":"terralith:jungle_mountains","baseTemperature":0.95,"precipitation":"RAIN","category":"jungle","confidence":"high"},
  {"id":"terralith:lavender_forest","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:lavender_valley","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:lush_valley","baseTemperature":0.4,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:mirage_isles","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:moonlight_grove","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:moonlight_valley","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:mountain_steppe","baseTemperature":0.4025,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:orchid_swamp","baseTemperature":0.8,"precipitation":"RAIN","category":"swamp","confidence":"high"},
  {"id":"terralith:painted_mountains","baseTemperature":1.0,"precipitation":"RAIN","category":"mountain","confidence":"high"},
  {"id":"terralith:red_oasis","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"terralith:rocky_jungle","baseTemperature":0.95,"precipitation":"RAIN","category":"jungle","confidence":"high"},
  {"id":"terralith:rocky_mountains","baseTemperature":1.0,"precipitation":"NONE","category":"mountain","confidence":"high"},
  {"id":"terralith:rocky_shrubland","baseTemperature":0.14,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:sakura_grove","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:sakura_valley","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"terralith:sandstone_valley","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"terralith:savanna_badlands","baseTemperature":1.0,"precipitation":"NONE","category":"savanna","confidence":"high"},
  {"id":"terralith:savanna_slopes","baseTemperature":1.0,"precipitation":"NONE","category":"savanna","confidence":"high"},
  {"id":"terralith:scarlet_mountains","baseTemperature":0.1,"precipitation":"SNOW","category":"mountain","confidence":"high"},
  {"id":"terralith:shield","baseTemperature":0.4,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:shield_clearing","baseTemperature":0.4,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:shrubland","baseTemperature":1.2,"precipitation":"NONE","category":"savanna","confidence":"high"},
  {"id":"terralith:siberian_grove","baseTemperature":0.23,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:siberian_taiga","baseTemperature":0.3,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:skylands","baseTemperature":0.5,"precipitation":"RAIN","category":"ocean","confidence":"high"},
  {"id":"terralith:skylands_autumn","baseTemperature":0.5,"precipitation":"RAIN","category":"ocean","confidence":"high"},
  {"id":"terralith:skylands_spring","baseTemperature":0.5,"precipitation":"RAIN","category":"ocean","confidence":"high"},
  {"id":"terralith:skylands_summer","baseTemperature":0.5,"precipitation":"RAIN","category":"ocean","confidence":"high"},
  {"id":"terralith:skylands_winter","baseTemperature":0.2,"precipitation":"SNOW","category":"ocean","confidence":"high"},
  {"id":"terralith:snowy_badlands","baseTemperature":0.0,"precipitation":"SNOW","category":"mesa","confidence":"high"},
  {"id":"terralith:snowy_maple_forest","baseTemperature":0.1,"precipitation":"SNOW","category":"taiga","confidence":"high"},
  {"id":"terralith:snowy_shield","baseTemperature":0.1,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:steppe","baseTemperature":0.4,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:stony_spires","baseTemperature":0.7,"precipitation":"RAIN","category":"mountain","confidence":"high"},
  {"id":"terralith:temperate_highlands","baseTemperature":0.9,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:tropical_jungle","baseTemperature":0.95,"precipitation":"RAIN","category":"jungle","confidence":"high"},
  {"id":"terralith:valley_clearing","baseTemperature":0.4,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"terralith:volcanic_crater","baseTemperature":1.0,"precipitation":"RAIN","category":"mountain","confidence":"high"},
  {"id":"terralith:volcanic_peaks","baseTemperature":1.0,"precipitation":"RAIN","category":"mountain","confidence":"high"},
  {"id":"terralith:warm_river","baseTemperature":0.5,"precipitation":"RAIN","category":"river","confidence":"high"},
  {"id":"terralith:warped_mesa","baseTemperature":2.0,"precipitation":"NONE","category":"mesa","confidence":"high"},
  {"id":"terralith:white_cliffs","baseTemperature":0.4,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"terralith:white_mesa","baseTemperature":2.0,"precipitation":"NONE","category":"mesa","confidence":"high"},
  {"id":"terralith:windswept_spires","baseTemperature":0.2,"precipitation":"RAIN","category":"extreme_hills","confidence":"high"},
  {"id":"terralith:wintry_forest","baseTemperature":-0.5,"precipitation":"SNOW","category":"taiga","confidence":"high"},
  {"id":"terralith:wintry_lowlands","baseTemperature":-0.5,"precipitation":"SNOW","category":"taiga","confidence":"high"},
  {"id":"terralith:yellowstone","baseTemperature":0.24775,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:yosemite_cliffs","baseTemperature":0.375,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"terralith:yosemite_lowlands","baseTemperature":0.25,"precipitation":"RAIN","category":"taiga","confidence":"high"}
 ]},
{"id":"tectonic","mcVersion":"1.18.2","sourceRef":"github:Apollounknowndev/tectonic@mc1.18.2-0.1","packFormat":9,"addsBiomes":true,"overwritesVanilla":true,"biomeCount":26,"overwrittenVanillaCount":46,
 "requiresTerraBlender":false,"note":"Tectonic v3 定位为纯地形塑形；下表 26 个群系对 v0.1/v1.1.x (1.18.2) 精确，对最新 1.18.2 版 2.3.5a 置信度低（可能已删除部分群系，见报告第 4 节第 2 条）",
 "overwrittenVanilla":["minecraft:badlands","minecraft:bamboo_jungle","minecraft:beach","minecraft:birch_forest","minecraft:cold_ocean","minecraft:dark_forest","minecraft:deep_cold_ocean","minecraft:deep_frozen_ocean","minecraft:deep_lukewarm_ocean","minecraft:deep_ocean","minecraft:desert","minecraft:dripstone_caves","minecraft:eroded_badlands","minecraft:flower_forest","minecraft:forest","minecraft:frozen_ocean","minecraft:frozen_peaks","minecraft:frozen_river","minecraft:grove","minecraft:ice_spikes","minecraft:jagged_peaks","minecraft:jungle","minecraft:lukewarm_ocean","minecraft:lush_caves","minecraft:meadow","minecraft:mushroom_fields","minecraft:ocean","minecraft:old_growth_birch_forest","minecraft:old_growth_pine_taiga","minecraft:old_growth_spruce_taiga","minecraft:plains","minecraft:river","minecraft:savanna","minecraft:savanna_plateau","minecraft:snowy_beach","minecraft:snowy_plains","minecraft:snowy_slopes","minecraft:snowy_taiga","minecraft:sparse_jungle","minecraft:stony_peaks","minecraft:stony_shore","minecraft:sunflower_plains","minecraft:swamp","minecraft:taiga","minecraft:warm_ocean","minecraft:wooded_badlands"],
 "vanillaTemperatureChanges":{"minecraft:stony_shore":{"from":0.2,"to":0.8}},
 "vanillaPrecipitationChanges":{},
 "biomes":[
  {"id":"tectonic:alpine_meadow","baseTemperature":0.4,"precipitation":"RAIN","category":"mountain","confidence":"high"},
  {"id":"tectonic:ancient_river","baseTemperature":0.8,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"tectonic:autumn_birch_forest","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"tectonic:autumn_forest","baseTemperature":0.7,"precipitation":"RAIN","category":"forest","confidence":"high"},
  {"id":"tectonic:cold_beach","baseTemperature":0.8,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"tectonic:cold_cliffs","baseTemperature":0.8,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"tectonic:cold_plains","baseTemperature":0.25,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"tectonic:cold_river","baseTemperature":0.5,"precipitation":"RAIN","category":"river","confidence":"high"},
  {"id":"tectonic:coral_river","baseTemperature":0.5,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"tectonic:desert_dunes","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"tectonic:dripstone_river","baseTemperature":0.8,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"tectonic:grasslands","baseTemperature":1.4,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"tectonic:icy_cliffs","baseTemperature":0.0,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"tectonic:icy_river","baseTemperature":0.0,"precipitation":"SNOW","category":"river","confidence":"high"},
  {"id":"tectonic:island","baseTemperature":0.8,"precipitation":"RAIN","category":"plains","confidence":"high"},
  {"id":"tectonic:lagoon","baseTemperature":0.5,"precipitation":"RAIN","category":"ocean","confidence":"high"},
  {"id":"tectonic:lantern_river","baseTemperature":0.8,"precipitation":"RAIN","category":"underground","confidence":"high"},
  {"id":"tectonic:lukewarm_beach","baseTemperature":0.8,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"tectonic:lukewarm_cliffs","baseTemperature":0.8,"precipitation":"RAIN","category":"beach","confidence":"high"},
  {"id":"tectonic:lukewarm_river","baseTemperature":0.5,"precipitation":"RAIN","category":"river","confidence":"high"},
  {"id":"tectonic:lush_river","baseTemperature":0.5,"precipitation":"RAIN","category":"river","confidence":"high"},
  {"id":"tectonic:old_growth_snowy_taiga","baseTemperature":0.0,"precipitation":"RAIN","category":"taiga","confidence":"high"},
  {"id":"tectonic:red_desert","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"tectonic:red_desert_dunes","baseTemperature":2.0,"precipitation":"NONE","category":"desert","confidence":"high"},
  {"id":"tectonic:sandstone_cliffs","baseTemperature":2.0,"precipitation":"NONE","category":"beach","confidence":"high"},
  {"id":"tectonic:warm_river","baseTemperature":0.5,"precipitation":"RAIN","category":"river","confidence":"high"}
 ]}
]}
```

---

## 6. 总结

**Terralith** 在 1.18.2 注册 **96 个** `terralith:` 群系（83 地面 + 13 洞穴），并覆盖 **51 个** `minecraft:` 群系文件；温度只改了 5 个原版群系（`deep_frozen_ocean` 0.5→0、`savanna` 2→1.2、`savanna_plateau` 2→1、`stony_shore` 0.2→0.6、`windswept_savanna` 2→1.1），**降水一个都没改**。它 1.18.2 是**纯数据包**（`pack_format 9`，替换 `minecraft:overworld` 维度），**不需要 TerraBlender**；Modrinth 模组版 2.2.3–2.2.6 需 **Cristel Lib**。

**Tectonic** 作者是 **Apollo**（非 Starmute），主业是地形塑形。**它确实新增了群系**：1.18.2 的 v1.x 注册 **26 个** `tectonic:` 群系（`lagoon`、`island`、`desert_dunes`、`grasslands` 等），并覆盖 46 个原版群系，只改了 `stony_shore` 的温度。注意更新的 Tectonic v2/v3 转向纯地形，1.18.2 最新版 2.3.5a 的群系清单**未能核证**（GitHub 无对应标签，且 v1.x 更新日志显示 `lagoon`、`autumn_forest`、`ancient_river` 已被移除）。

**1.18.2 可用版本**：Terralith **2.2.6**（2023-12-08，最新）、2.2.5、2.2.4、2.2.3；Tectonic **2.3.5a**（2024-06-05，最新），数据包可用 v1.1.10a / v2.2.2。两者官方均已停止维护 1.18.2。组合使用时须用 Tectonic 模组版（内置 Terralith 兼容）；Terratonic 独立数据包**不支持 1.18.2**。

---

### 附：本报告引用链接

- Terralith Modrinth: <https://modrinth.com/mod/terralith> ｜ API: <https://api.modrinth.com/v2/project/8oi3bsk5>
- Terralith GitHub: <https://github.com/Stardust-Labs-MC/Terralith> ｜ 1.18.2 分支清单: <https://data.jsdelivr.com/v1/packages/gh/Stardust-Labs-MC/Terralith@1.18.2/?structure=flat>
- Terralith CurseForge: <https://www.curseforge.com/minecraft/mc-mods/terralith>
- Tectonic Modrinth: <https://modrinth.com/mod/tectonic> ｜ API: <https://api.modrinth.com/v2/project/lWDHr9jE>
- Tectonic GitHub: <https://github.com/Apollounknowndev/tectonic> ｜ 1.18.2 tag 清单: <https://data.jsdelivr.com/v1/packages/gh/Apollounknowndev/tectonic@mc1.18.2-0.1?structure=flat>
- Tectonic CurseForge: <https://www.curseforge.com/minecraft/mc-mods/tectonic>
- Terratonic: <https://modrinth.com/datapack/terratonic>
- Cristel Lib: <https://modrinth.com/mod/cristel-lib>
- 原版 1.18.2 基线数据: <https://data.jsdelivr.com/v1/packages/gh/misode/mcmeta@1.18.2-data?structure=flat>

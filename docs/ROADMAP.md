# NowWeather 路线图与多版本策略

> 目标：**1.18.1 / 1.18.2（Forge）→ 1.19.x → 1.20.x → 1.21.x → 26.x（NeoForge）**
> 一套核心，多个薄平台层。本文说明为什么这么切、每个版本要改什么、以及工具链怎么选。

---

## 1. 架构：核心 + 平台层 + 版本适配

```
                     ┌──────────────────────────────────────────┐
                     │  core/  纯 Java 17，零 Minecraft 依赖     │
                     │  气候推断 · 预报 · 合理性校验 · 天气白名单 │
                     │  UApiPro 客户端 · 同步策略 · 自带 JSON/HTTP│
                     │  ★ 可离线编译与自测（无需启动游戏）       │
                     └──────────────────────────────────────────┘
                                        ▲ 只通过接口交互
        ┌───────────────┬───────────────┼───────────────┬───────────────┐
        │ forge-1.18.2  │ forge-1.18.1  │ forge-1.19.2  │ neoforge-1.21 │  …
        │ (Forge 40)    │ (Forge 39)    │ (Forge 43)    │ (NeoForge 21) │
        └───────────────┴───────────────┴───────────────┴───────────────┘
              forge-common：1.18.x 共用的胶水（1.18.1 与 1.18.2 同一份源码）
```

核心与平台之间只有 **6 个适配接口**（下表前 6 行），另外还有 **2 个气候扩展缝**：
`ClimateResolver`（参与解析，只在显式配置/标签/分类都没命中时被问到）与
`ClimateModifier`（后处理，前 5 级无论命中哪一级，结果都会再过一遍它）。换版本时只改实现，不动核心：

| 接口 | 作用 | 版本风险 |
| --- | --- | --- |
| `PlatformWeatherAccess` | 读写游戏天气（由平台层实现） | 低（`setWeatherParameters` 从 1.16 至今没变） |
| `PlayerHandle` | 玩家信息（名字/位置/群系/权限）（由平台层实现） | 低 |
| `BiomeFacts` | 读取生物群系事实的**接口**（纯核心）；Forge 侧的**实现类**是 `ForgeBiomeFactsFactory` | **高**（见 §3） |
| `WeatherProvider` | 入站天气数据源 | 无（纯核心） |
| `WeatherBinding` | 出站联动其它模组 | 中（依赖对方 API） |
| `ModPresence` | 探测其它模组（由平台层实现） | 低 |
| `ClimateResolver` | 气候解析扩展缝（比启发式优先、比显式配置低） | 无（纯核心） |
| `ClimateModifier` | 气候后处理扩展缝（季节联动的落点） | 无（纯核心） |

---

## 2. 版本矩阵

| MC | 加载器 | 加载器版本 | 构建工具 | Gradle | JDK | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 1.18.1 | Forge | 39.1.2 | ForgeGradle 5.1 | 7.5.1 | 17 | ✅ 首要目标 |
| 1.18.2 | Forge | 40.3.12 | ForgeGradle 5.1 | 7.5.1 | 17 | ✅ 首要目标 |
| 1.19.2 | Forge | 43.5.2 | ForgeGradle 5.1 | 7.5.1 | 17 | 🗺️ 下一批 |
| 1.19.4 | Forge | 45.4.5 | ForgeGradle 5.1 / 6.x | 7.6+ | 17 | 🗺️ 注意降水 API 变化 |
| 1.20.1 | Forge / NeoForge | 47.4.x / 47.1.x | ModDevGradle（legacy） | 8.x | 17 | 🗺️ |
| 1.20.4 / 1.20.6 | NeoForge | 20.4.x / 20.6.x | ModDevGradle | 8.x | 17 / 21 | 🗺️ |
| 1.21.1 | NeoForge | 21.1.x | ModDevGradle | 8.x | 21 | 🗺️ |
| 1.21.5 / 1.21.8 | NeoForge | 21.5.x / 21.8.x | ModDevGradle | 8.x+ | 21 | 🗺️ |
| 1.21.9 ~ 1.21.11 | NeoForge | 21.9.x ~ 21.11.x | ModDevGradle | 9.x | 21 | 🗺️ |
| 26.1 / 26.2 / 26.3 | NeoForge | 62.x / 65.x | ModDevGradle | **≥9.1** | **25** | 🗺️ 工具链无法与 1.18.2 共存 |

**关键结论：26.x 需要 Gradle ≥ 9.1 + JDK 25，而 1.18.2 必须用 Gradle 7.5.1 + JDK 17 —— 两套工具链无法在同一个 Gradle 进程里共存。**
这正是「核心必须独立」的根本原因：`core/` 用任意一套工具链都能编，平台层各版本各用各的。

---

## 3. 每个版本真正要改什么（已核对的坑）

### 3.1 降水表示方式（1.19.4 起，影响所有版本）

快照 `23w03a` 移除了数据包里的 `precipitation` 枚举字段，改成布尔 `has_precipitation`；
下雪与否**只**由基础温度是否 < 0.15 决定。

- 1.18.1 ~ 1.19.3：读 `Biome.getPrecipitation()`（`NONE`/`RAIN`/`SNOW`）
- 1.19.4+：没有这个 API 了，要按 `baseTemperature < 0.15F ? SNOW : has_precipitation ? RAIN : NONE` 推导

> 已集中在 `ForgeBiomeFactsFactory` 一个方法里（`toPrecipitationType`），换版本改这一处。

### 3.2 生物群系 API 的其他变化

| 版本 | 变化 | 应对 |
| --- | --- | --- |
| 1.19.3 | `Registry.BIOME_REGISTRY` → `Registries.BIOME` | 改一行常量 |
| 1.19.4+ | `Biome#getPrecipitation()` 移除 | 见 §3.1 |
| 1.21.2+ | `Biome#getBiomeCategory()` 移除 | 改用标签判断；必要时代码里内置一份 id→分类表 |
| 1.18 | 是 `EntityJoinWorldEvent`（1.19+ 是 `EntityJoinLevelEvent`） | 现在没用到，将来用到时注意 |
| 全版本 | `Biome#getTemperature/getHeightAdjustedTemperature` 是 **private** | 只能用 `getBaseTemperature()` |

### 3.3 工具链

| 版本段 | 推荐工具 | 说明 |
| --- | --- | --- |
| 1.18.x | ForgeGradle 5.1 + Gradle 7.5.1 | MDK 官方组合，已验证可用 |
| 1.19.x ~ 1.20.4 | 可继续 ForgeGradle，1.20.1 起建议 ModDevGradle | FG legacy 插件在 1.20.2+ 逐步失效 |
| 1.20.6+ / 21.x / 26.x | **ModDevGradle**（NeoForge 官方推荐） | NeoGradle 已进入维护模式 |
| 跨 3 个以上版本后 | 考虑 **Stonecutter**（只作用于平台层） | 单一代码库多版本预处理，已被多个模组用到 26.x |

**不推荐 Architectury**：它解决的是「同一个 MC 版本里 Fabric/Forge/NeoForge 三加载器对齐」，
而本项目的问题轴是 **MC 版本**，不是加载器；引入它只会增加一层抽象与构建复杂度。
（Fabric 支持是另一个独立的路线，见 §6。）

---

## 4. 与其它模组的共存（1.18.2 现状）

调研结论（详见 `docs/research/cross-mod-compatibility.md`）：

- **1.18.2 上真正会抢天气控制权的只有 Weather2 / Weather, Storms & Tornadoes** ——
  它自带局部天气系统并替换原版天气与雨雪渲染；
- Project Atmosphere（只到 Forge 1.20.1 / NeoForge 1.21.1）、ProtoManly's Weather（NeoForge 1.21.1）
  **在 1.18.2 上根本装不上**，不需要现在适配；等对应版本目标建立后再做 `WeatherBinding`；
- 原版天气是「两个 flag + 两个倒计时器」，`setWeatherParameters` 的语义是**重置计时器**：
  - 不能每 tick 强设（这是清天气类模组最常犯的错误）；
  - 不能用 `clearWeatherTime` 做长期晴天 —— 它到期时两个 flag 会一起翻滚成雷暴；
  - 必须尊重 `doWeatherCycle` 游戏规则与 `/weather clear`。

**已实现的共存策略**（配置项：`compat.yieldToOtherWeatherMods`、`compat.yieldToModIds`、`compat.weather2Drive`）：

```
        检测到 Weather2 等模组在控制天气
                    │
      ┌─────────────┴─────────────┐
      │ 是                        │ 否
      ▼                           ▼
  观察模式（applyWeather=false）   正常接管
  · 照常拉真实天气               · 决策并写入游戏天气
  · 照常生成预报
  · 照常同步客户端 / 提供给其它模组
  · 不写游戏天气
```

配置项 `compat.yieldToOtherWeatherMods`（默认开，检测到别的天气模组在控制天气就让权）与
`compat.yieldToModIds`（自定义让权名单，留空则用内置名单）；
另有 `compat.weather2Drive`（默认开，用真实/推算天气主动指挥 Weather2 生成风暴）。
把 NowWeather 当作「真实天气数据源」给体温/逻辑类模组用，是比抢控制权更好的共存方式。

---

## 5. 近期计划

### 已完成

- [x] 核心（气候 / 预报 / 校验 / 天气白名单 / UApiPro / 同步策略）—— 断言数**不写死**，
      由 `./gradlew :core:coreSelfTest` 运行时打印（正常输出 `通过: N，失败: 0`）
- [x] **250 条**生物群系气候数据（原版 1.18 基线 57 + 1.19~26.x 新增 6 + 超多生物群系 65 + Terralith 96 + Tectonic 26）
- [x] 数据集生成器（JSON + 人类可读总表，保证文档与运行时逻辑一致）
- [x] Forge 1.18.2 / 1.18.1 平台层（配置界面、HUD、网络同步、命令、数据包重载、共存策略）
- [x] **两个真机服务端全量回归**：250 群系 × 20 天气 = 5000 用例 / 版本，
      4680 次写入并回读校验成功，**0 越界、0 回读失败**，320 例按下界/末地/虚空保持原版
- [x] 真实时间同步（游戏内一天 = 真实 24 小时）+ 时区三级解析
- [x] 离线推算链路（本地缓存 → 距离融合 → 轻量推算），含"距海岸距离 → 季节振幅"模型
- [x] Serene Seasons 季节联动（`ClimateModifier` 扩展点）
- [x] **Terralith / Tectonic 装了模组后的真机验证**（见 `docs/IN_GAME_TEST_REPORT.md` 第八轮）：
      jar 内 96 个 Terralith 群系与内置气候表**完全一致**（0 多余、0 遗漏）；
      Tectonic v2.x 默认不注册群系（26 条只在 legacy 数据包里存在），属**休眠数据**

### 进行中

- [ ] `runClient` 客户端实测（配置界面 / HUD 的真机截图验证）—— 目前只有编译与代码层验证

### 下一步

- [ ] `WeatherBinding` 出站联动的第一个真实实现（1.20.1 上的 Project Atmosphere）
- [ ] 多维度独立天气（目前只驱动配置列表里的第一个维度）
- [ ] 客户端独立预报复现（只同步种子与锚点，进一步省带宽）
- [ ] 历史天气 / 未来预报接口（UApiPro 有历史天气 API）
- [ ] 1.19.2 / 1.20.1 平台层

### 远期

- [ ] 26.x（NeoForge）平台层 —— 需要 Gradle 9.1 + JDK 25，独立构建
- [ ] Fabric / Quilt 平台层（核心已经与加载器无关，只差一个平台实现）

---

## 6. 为什么核心能跨版本

核心模块里**没有任何** `net.minecraft.*` 引用，这一点由构建强制保证：
`core/` 是一个独立的 Gradle 子工程，只在编译它自己时会用到 JDK，不依赖 Minecraft。
任何人不小心在核心里写了 MC 代码，`./gradlew :core:coreSelfTest` 立刻会失败。

这条约束换来的是：

- **换 MC 版本时风险被压缩到 6 个适配类**，其余约 1.3 万行逻辑原样复用；
- 现实世界的天气逻辑（UApiPro 解析、气候推断、预报、合理性校验）
  **可以用单元测试完整验证**，不需要启动游戏；
- 将来要支持 Fabric/Quilt，只需要再写一份平台层实现，核心 0 改动。

---

## 附：关于「接入 WeatherNext 这类 AI 天气预报模型」

有人会想到把 Google DeepMind 的 WeatherNext 3（全球 AI 预报、逐小时、5km 级）接进来当数据源。
这里把结论写清楚，避免后续走弯路：

**它不能用于"离线计算"。** WeatherNext 是云端跑的神经/扩散模型，
官方接入方式是把**预报结果网格**通过 [Google Cloud Storage（Zarr）](https://developers.google.com/weathernext/guides/gcs)
或 [Vertex AI](https://developers.google.com/weathernext/guides/access-vmg) 提供 ——
需要 Google Cloud 项目与凭据，单个全球网格动辄数百 MB~GB 级。这跟「离线、轻量」是直接冲突的。

**可行的做法是把它当成"另一个在线数据源"**（和 Open-Meteo 并列），也就是：

```
WeatherProvider（在线拉一次）→ 本地缓存逐小时预报 → 离线期间按小时插值 → 超出预报范围才退回轻量推算
```

这条链路里，**只有最后一步是"离线计算"**，而它必须是轻量的（纬度带气候 + 海拔 + 昼夜 + 持续性融合，
也就是本项目已实现的 `OfflineWeatherSynthesizer`）。前面的「缓存逐小时预报再插值」是纯工程问题，
接口已经预留好（`WeatherProvider#isForecastProvider()`），将来要接谁都可以：

| 数据源 | 覆盖 | 分辨率/更新 | 接入成本 | 适合 |
| --- | --- | --- | --- | --- |
| Open-Meteo（已实现） | 全球 | 1~11 km / 15 分钟更新，逐小时预报 16 天 | 零（无需密钥） | ★ 默认 |
| UApiPro（已实现） | 中国 | 实况十几分钟 | 零（可选密钥） | 国内精确实况 |
| WeatherNext 3 | 全球 | 5 km / 逐小时 | 高（GCS/Vertex 凭据 + 大网格） | 远期，服务端可选 |

> 结论：**离线保持轻量**（现状），把「逐小时预报缓存 + 插值」作为下一步的通用能力，
> WeatherNext 只是这条链路上的一个可选上游。
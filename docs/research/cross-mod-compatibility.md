# WeatherSync 跨模组兼容性设计报告

- **对象**：WeatherSync（`modId = weathersync`），用真实世界天气数据驱动 Minecraft 天气。
- **首要目标版本**：Forge 1.18.2 / 1.18.1；**远期**：Forge 1.20.1、NeoForge 1.21.x、NeoForge 26.x。
- **本报告范围**：结论与建议，不含 Java 实现。
- **性质**：联网查证报告。凡无法确证的内容一律显式标注 **【未确证】**。请勿把标注项当作事实写进代码或用户文档。

---

## 取证与可信度声明（请先读）

本次调研可直接抓取的来源：

| 来源 | 状态 | 用途 |
| --- | --- | --- |
| `api.modrinth.com/v2/*`（JSON API） | ✅ 可抓 | slug / project_id / loaders / game_versions / 项目描述正文 |
| `www.mcmod.cn/class/*.html` | ✅ 可抓 | 中文模组页、前置/联动/不兼容关系、支持的 MC 版本 |
| `minecraft.wiki` | ✅ 可抓 | 原版天气机制、群系温度语义 |
| `docs.minecraftforge.net`（1.18.x） | ✅ 可抓 | 事件、侧别、配置的官方规则 |
| `docs.neoforged.net` + `neoforged.net/news/*` | ✅ 可抓 | NeoForge 事件/包名/版本体系 |
| `forums.minecraftforge.net` | ✅ 可抓 | 「有没有天气事件」等第一手社区问答 |
| `nekoyue.github.io/ForgeJavaDocs-NG`（1.18.2 javadoc 镜像） | ✅ 可抓 | 1.18.2 API 是否存在、可见性 |
| `mappings.dev/1.18.2/*` | ✅ 可抓 | 原版方法签名 |
| `github.com` / `api.github.com` / `raw.githubusercontent.com` | ❌ **DNS 被解析到非公网 IP，无法抓取** | 改走 `cdn.jsdelivr.net/gh/...` 镜像；`.toml`/`.properties`/`.gradle` 文件 CDN 拒发 |
| `curseforge.com` | ❌ HTTP 403（Cloudflare） | 只能用搜索结果，**页面内容未亲自核对** |
| `modrinth.com` 网页版 | ❌ HTTP 500 | 改用 JSON API |

**因此**：

- **modId 的取证优先级**：① 从真实发行 jar 的 `META-INF/mods.toml` 读出 → ② 从源码常量读出 → ③ 强推断 → ④ 标【未确证】。本报告中 `biomesoplenty`、`sereneseasons` 属于第 ① 档（本次已实际下载并解包 1.18.2 发行 jar 验证），`regions_unexplored` 属于第 ② 档。
- 文中给出的 CurseForge 链接**只保证链接形态**（来自 mcmod.cn 收录或搜索结果），其页面内容未核对。
- 带「**强推断**」标签的结论有多条间接证据但缺一手来源；带「**【未确证】**」的是完全无法证实，不要在实现文档里当成事实。

---

## 0. 结论速览（TL;DR）

1. **最大的兼容性风险不是「天气被别的模组抢」，而是「名字已经被占」**。Modrinth 上已存在一个 slug 为 [`weathersync`](https://modrinth.com/mod/weathersync) 的 Fabric 模组（作者 macbrayne，MC 1.20.4，服务端，查询 **Open-Meteo** 把真实天气同步进 MC，带 `/weathersync location set auto|city|custom` 命令），产品概念与本项目**完全重合**。除此之外还有 Bukkit 插件 WeatherSync、CurseForge "Real World Weather Sync"、Spigot "RealWeather"。→ **modId / 品牌 / Modrinth slug 必须重新决策**（见 §1.10、§5 风险 1）。
2. **在 1.18.2 上真正会抢全局天气控制权的对手只有少数几个**，最重要的是 **Weather2 / Weather, Storms & Tornadoes（Corosus）**：它自带局部天气系统并**替换**原版世界天气，1.18.2 可用（需 CoroUtil）。`Project Atmosphere` 与 `ProtoManly's Weather` **都不支持 1.18.2**（分别只到 Forge 1.20.1 / NeoForge 1.21.1），1.18.2 阶段不必为它们写兼容代码，只需在架构上预留「让出控制权」的开关。
3. **设计基调：WeatherSync 做「决策者」，不做「独占写入者」**。默认工作在**温和写手模式**：只在需要状态变化时调用一次 `ServerLevel#setWeatherParameters` 写入**剩余时长**，并显式尊重 `doWeatherCycle` 游戏规则、`/weather clear`（clearWeatherTime）语义、以及其它模组的写入；检测到 Weather2 / Simple Clouds / Project Atmosphere 存在时自动降级为**只读 + 推送**模式。
4. **气候推断必须做三级降级，不能只靠 `Biome#getBaseTemperature()`**。已确证 `baseTemperature` 本质是「草色/树叶色的视觉参数」，洞穴群系有地表级温度、下界群系全被压成 2.0、Terralith 直接覆写约 35 个原版群系 id、Homeostatic 会**整体改写群系温度**。正确顺序：Forge `Tags.Biomes` 气候组 → `getBaseTemperature()/getDownfall()/getPrecipitation()` → **用户可编辑的逐群系覆盖表**（业内有 `ClimateAdjuster` 这一现成范式）。
5. **原版天气是「两个布尔 flag + 两个倒计时器」**，`setWeatherParameters(clearTime, weatherTime, raining, thundering)` 的语义是**重置计时器**，不是「立即设置外观」。`clearWeatherTime` 有个陷阱：它生效期间两个 timer 被锁在 1 tick，结束后会立刻翻滚成雷暴。→ 不要每 tick 强设天气，也不要用 clearWeatherTime 做「长期晴天」。
6. **Forge 没有「天气变化事件」**。官方文档没有，社区问答（Forge 官方论坛两处）也明确回答「No there isn't」。因此只能**主动轮询**（`TickEvent` / `LevelTickEvent` + 状态对账），而且社区口径是「probably not every tick」。
7. **只写服务端状态**：跨逻辑侧必须走网络包，禁止用 static 字段跨侧共享（官方 Sides 文档明确把这条列为 common mistake）。
8. **只写原版状态是不够的**。生态里已经存在 `weather2-compatibility-bridge`、`weather2-compat`、`Serene Seasons API Stub Bridge` 这类「桥」模组，说明「只改原版 rain flag」在装了大天气模组的整合包里必然失效。WeatherSync 应同时提供：① 写原版状态 ② **自家只读查询 API** ③ 可选的 bridge/adapter 层。
9. **多版本策略**：1.18.x 保持现状（ForgeGradle 5 + Gradle 7.5.1 + JDK 17）即可；别为了「统一」过早引入 Architectury（它解决的是同版本内加载器对齐，不解决 MC 版本对齐）。等出现**第 3 个版本目标**（1.20.1）时再考虑 Stonecutter，且只作用于 `versions/` 平台层。**唯一零风险纯收益的重构**：把 `core/` 从 `srcDirs` 手写包含改成标准子工程依赖 `implementation project(':core')`。
10. **远期 1.21.x 的对手关系已经明确**：`Project Atmosphere` 官方在 mcmod 页面上**自己声明与 `局部气候&风暴`（Weather2/WS&T）不兼容**——这等于宣告「真实气候模拟派」与「局部风暴派」互斥。WeatherSync 必须选边站：站在**数据源/决策层**一侧，把「表现」让给它们。

---

## 1. 生态里已有的「天气类」模组清单（以 1.18.2 为第一视角）

### 1.0 先分类：五种「天气模组」与它们的冲突面

| 类别 | 它碰什么 | 与 WeatherSync 的关系 |
| --- | --- | --- |
| **A 全局天气写入者** | 直接改原版 `rain`/`thunder` flag 与计时器 | **直接抢控制权**，必须显式协商 |
| **B 自有局部天气系统** | 自己一套天气模拟，通常**替换**原版天气与雨雪渲染 | **最严重冲突**：装了它，WeatherSync 写的原版状态会被无视或被打架 |
| **C 气候/季节/温度层** | 不写天气，但决定「这里该下雨还是下雪」「体感温度」 | **必须共存**：应读它、不要覆盖它 |
| **D 只读消费者** | 读 `isRaining` / `isRainingAt` / rainLevel 做玩法 | **理想下游**：WeatherSync 应主动服务它们 |
| **E 纯客户端表现** | 渲染/音效/本地覆盖显示，不改服务端状态 | 无冲突，但会放大「天气看起来不对」的观感问题 |
| **F 名称/概念冲突** | 同名或同概念 | **非技术冲突，但危害最大**（见 §1.10） |

**判据（如何判断它是不是 A/B 类）**：看它有没有自己的天气创建/推进命令与配置（如 `/weather2 storm create`、`/pmweather`），有没有自己的雨雪渲染替换，有没有第三方专门为它写的「桥」模组。**有专用桥模组 ≈ 它没有让原版状态保持一致。**

---

### 1.1 Project Atmosphere（以及它和 ProtoManly 系天气模组的关系）

| 项 | 内容 |
| --- | --- |
| modId | **【未确证】**（只能确证 slug 与仓库名；Modrinth `/v2/project/project-atmosphere` 反复返回非 JSON，`modrinth.com` 网页 500，CurseForge 403） |
| 链接 | [mcmod 词条](https://www.mcmod.cn/class/26161.html) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/project-atmosphere) · [Modrinth](https://modrinth.com/mod/project-atmosphere) · [GitHub `xGabou/Project-Atmosphere`](https://github.com/xGabou/Project-Atmosphere) |
| 支持版本 | **Forge 1.20.1 + NeoForge 1.21.1（不含 1.18.2）**（mcmod 版本块）。GitHub 仓库分支为 `Forge-1.20.1` / `NeoForge-1.21.1`，与之一致 |
| 作者 | Gaboouu（所有者）、gold3nberry（策划） |
| 前置 | **必须有一个季节系统**：TerraFirmaCraft: TNG / Ecliptic Seasons / Serene Seasons 三选一；另有 Gabou's Libs、Simple Clouds、Better Days |
| 控制什么 | 自己一整套大气模拟：温度、气压、湿度、风向；开场生成 7 天预报 → 找低压区 → 生成气旋作为「吸引子」→ 之后转成**单一区域预报（半径约 1000 格）**；40+ 种云（1–7 级）；龙卷风/飓风仍在开发，可配置开关 |
| 怎么控制 | **B 类**：不是「改原版 rain flag」，而是自己的气候模拟 + 云/风暴系统；重计算放异步线程，用「克隆的群系采样器」避免与实时区块并发 |
| 抢不抢天气 | **抢，但抢的是「大气表现层」**；由于它要求季节模组并自建区域预报，实际上把全局天气交给了自己 |
| 与体温模组的联动 | 曾直接整合 Legendary Survival Overhaul / Cold Sweat / Tough As Nails；官方说明未来方向是**不再传温度数值，而是把「天气状况（下雨/风暴）」传给其它模组** |
| 群系兼容名单 | 超多生物群系、Still Life、Atmospheric（悠然一派）、TerraFirmaCraft: TNG、Terralith、Nature's Spirit、Regions Unexplored、Biomes We've Gone、Galosphere、Neapolitan、Integrated Dynamics、Autumnity |
| **明确不兼容** | **`局部气候&风暴`（Weather, Storms & Tornadoes / Weather2）**（同一 mcmod 页面「不兼容」小节） |

**它和 ProtoManly 系天气模组的关系**：**不同作者、不同模组，没有改名/拆分关系的证据**。

- Project Atmosphere = Gaboouu/gold3nberry，Forge 1.20.1 + NeoForge 1.21.1。
- [ProtoManly's Weather](https://www.mcmod.cn/class/19930.html)（Modrinth slug `protomanlys-weather`，project_id `TfCsmTLI`，作者 ProtoManly）**只有 NeoForge，只有 1.21.1**；用 raymarched 着色器渲染云层/降雨/雷暴/飑线/超级单体/龙卷风，龙卷风可破坏建筑；`/pmweather` 命令；**与光影模组不兼容**（需其附属 `PMShaders` 支持 Iris，且 0.17.0-alpha 及以上失效）；闭源。
- 两者是同一时代（NeoForge 1.21.1）的**竞争性「真实风暴」模组**，都自带风暴系统 ⇒ 互相之间以及与 WeatherSync 都是 B 类冲突。二者是否存在代码/作者渊源：**【未确证】**（没有证据，也没有反证）。

**WeatherSync 的共存建议**：

- **1.18.2 阶段**：两者都装不上，**不要为它们写任何运行期分支**，只在架构上预留 `WeatherController` 抽象与「让出控制权」的配置项即可。
- **1.21.x 阶段（若目标扩展到该版本）**：检测到 Project Atmosphere 时**让出全局天气控制权**，把自己降级为「数据源」：把真实天气（晴/雨/雷暴 + 未来预报）通过**只读 API** 暴露，让 PA 或体温模组自行消费。这与 PA 官方「未来把天气状况传给体温模组」的路线方向一致，是唯一不打架的接口面。
- **文档义务**：在 README 与游戏内警告中说明「PA/ProtoManly's Weather 存在时 WeatherSync 不再写原版天气」。同时**引用 PA 自己的不兼容声明**说明 PA 与 Weather2 互斥，避免用户三方同装后把崩溃归因到 WeatherSync。

---

### 1.2 Weather2 / Weather, Storms & Tornadoes（Corosus）—— 1.18.2 上最重要的对手

| 项 | 内容 |
| --- | --- |
| 名称关系 | **同一个模组的两个名字**：仓库/mod 名 `weather2`，发布名 "Weather, Storms & Tornadoes"；mcmod 词条名是「局部气候&风暴 (Weather, Storms & Tornadoes)」 |
| modId | `weather2` —— **强推断，【未确证】**（依据：GitHub 仓库 `Corosauce/weather2`，描述 *"Minecraft Forge Mod - Localized Weather - A rewrite of weather & tornados with a focus on localized storm systems"*（本次通过 GitHub API 实际读取）；mcmod 社区教程标题「`weather2` 命令与天气/气象系统概念」；Modrinth 上存在同名 slug 而 Forge modId 通常与 slug 一致。**未能从 1.18.2 发行 jar 的 `mods.toml` 直接读出**） |
| 链接 | [mcmod](https://www.mcmod.cn/class/860.html) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/weather-storms-tornadoes) · [Modrinth `weather-storms-tornadoes`](https://modrinth.com/mod/weather-storms-tornadoes) · [GitHub](https://github.com/Corosauce/weather2) · 官方站 `coros.us/mods/weather2` |
| 支持版本 | **Forge 1.20.1 / 1.18.2 / 1.12.2 / 1.12.1 / 1.11.2 / 1.10.2 / 1.8.9 / 1.7.10 / 1.6.4**；NeoForge 1.21.1 / 1.21 / 1.20.1（mcmod 版本块）。**注意 Modrinth 只有 1.20.1+，1.18.2 只在 CurseForge** |
| 前置 | **CoroUtil**（1.18.2 与 1.20.1 均需要） |
| 控制什么 | **B 类**：自己一套**局部天气系统，用于取代 Minecraft 世界的天气**，外加风与环境增强：龙卷风、飓风、沙尘暴、水龙卷、风暴、雷暴、分阶段降雨；冷/暖气团粒子相遇形成锋面产生更剧烈风暴；1.18+ 新增暴风雪与积雪、灰尘粒子、避雷针支持局部风暴闪电、龙卷风消散时给生物缓降、龙卷风偏转器用原版 POI 系统 |
| 怎么控制 | 完全自有系统 + **替换原版雨雪渲染**（Modrinth 正文：「Replaces rain and snowfall with a particle based one that blows in the wind」） |
| 抢不抢天气 | **抢，而且是硬抢**（替换原版天气与渲染） |
| API | **没有公开文档**：mcmod 社区教程直接写「此 Mod 作者并没有写文档，这是个很不好的习惯」，只能读源码。是否有面向第三方的 API：**【未确证】**，证据倾向「没有」 |
| **关键生态证据** | 存在专门为它而写的桥模组：`weather2-compatibility-bridge`（「让依赖下雨检测的其它模组与 Weather2 兼容」）、`weather2-compat`（「让 Minecraft 使用 Weather2 的天气判定系统」）、`tfc-weather`（1.18.2 + 1.20.1，TFC↔Weather2 桥）。**这说明「只写原版 rain flag」在装了 Weather2 的整合包里必然失效** |
| 官方立场 | mcmod 页面注明**推荐和静谧四季（Serene Seasons）搭配**；下界不受影响（不会生成龙卷风/台风） |

**WeatherSync 的共存建议（1.18.2，最高优先级）**：

1. **默认互斥，且必须可配置**：提供三档 `compat.weather2 = {YIELD, BRIDGE, IGNORE}`。
   - `YIELD`（默认，检测到存在时）：**完全不写**原版天气，只把真实天气状态通过只读 API / 一条聊天提示 / 命令暴露出来。理由：Weather2 替换了天气与渲染，此时写原版状态的结果是「用户看不到、但别的只读模组会读到不一致数据」，比不写更糟。
   - `BRIDGE`（进阶/可选，按 `weather2-compatibility-bridge` 的既有范式实现）：把天气推给 Weather2 / 或让依赖下雨检测的模组从 WeatherSync 读。1.18.2 的实现成本高（无公开 API），**建议放到 1.19+ 再评估**。
   - `IGNORE`：强制双方都写（仅给自担风险的整合包作者）。
2. **运行期检测**：`ModList.get().isLoaded("weather2")`（1.18.2 该方法已确证存在，见 §3.2 R9）。
3. **文档写清楚**：「Weather2 与本模组功能重叠，二者同装时本模组默认只读」。这一条能挡掉大量「整合包里的天气坏了」的反馈。

---

### 1.3 Simple Clouds（为什么单列它）

Project Atmosphere 把「启用服务器侧局部天气」的能力继承自它，所以它在 1.20.1+ 也是 B 类。

- Modrinth slug `simple-clouds`（`LtZfKhel`），Forge/NeoForge，**MC 1.20.1 / 1.21 / 1.21.1，无 1.18.2**；作者 nonamecrackers2（与 PA 不同作者）。
- 行为（据 mcmod `class/17085` 摘要）：**仅客户端安装时**局部天气被禁用、回落到原版全局天气；**服务端也安装时**才启用它的局部天气系统。
- **共存建议**：1.18.2 无关。1.20.1+ 若与 PA 同装（PA 强制要求它），WeatherSync 必须进入 `YIELD`。

---

### 1.4 「Dynamic Weather / Dynamic Weather and AA」—— 需要澄清，**不要把它写进冲突清单**

本次调研结论：**在 Minecraft 生态里找不到一个叫 `Dynamic Weather` 或 `Dynamic Weather and AA` 的天气模组**，多轮检索下【未确证】。容易混淆的是：

| 候选 | 实际情况 |
| --- | --- |
| **Drongo's Dynamic Weather** | **是 Arma 3 的模组**，不是 Minecraft（已通过 Arma 3 Mods Catalogue 页面核对） |
| `wynncraft-dynamic-weather`（Modrinth `6z01GnNz`） | **纯客户端**，Fabric 1.21.x，只对 Wynncraft 服务器生效 |
| `dynamic-weather-system`（Modrinth `ckEuDvzu`） | 是 **Bukkit/Paper 插件**，不是 mod，1.21/1.21.1 |
| `-dynamic-seasons-weather`（Modrinth `5oGcxZEB`） | Forge 1.16.5 / 1.19.1 / 1.19.2，**无 1.18.2** |
| `dynamic-surroundings`（mcmod `class/1083`） | 1.17.1 起为**纯客户端**模组，会在降雨时生成随机风暴强度 ⇒ **不写服务端天气** |

> **建议**：本报告与项目文档中都**不要**把「Dynamic Weather」列为竞争对手——那会误导用户。如果团队内部原本指的是别的模组（例如搜到的 CurseForge "Advanced Weather" / "The Weather Update"），请补一个确切链接再纳入；这两者本身也**【未确证】**（仅有搜索摘要）。

---

### 1.5 Serene Seasons（季节与温度）—— 必须共存，且**它会动天气**

| 项 | 内容 |
| --- | --- |
| modId | **`sereneseasons`** ✅（本次从真实 1.18.2 发行 jar `Serene Seasons-1.18.2-7.0.0.15.jar` 的 `META-INF/mods.toml` 读出） |
| 链接 | [mcmod](https://www.mcmod.cn/class/1132.html) · [Modrinth `serene-seasons`](https://modrinth.com/mod/serene-seasons)（id `e0bNACJD`） · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/serene-seasons) |
| 支持版本 | Forge **1.18.1 / 1.18.2** ✅（Modrinth `game_versions` 含两者），以及 1.19.x–1.21.x、26.1.x/26.2；Fabric/NeoForge 也覆盖 |
| 前置 | 1.20.1+ 需 GlitchCore |
| 它是否控制天气 | **会**。Modrinth 正文：「Seasons also affect various other features, like **crop growth, weather**, and more!」以及「in the winter, temperatures will shift and **allow for snow to fall in some biomes that don't normally receive any**」。mcmod：「季节变化也会影响其他许多方面，比如说**天气（春天多雨而夏天多风暴等）**」 |
| 它怎么做到的 | 从 1.18.2 jar 的 `META-INF/coremods.json` 读到：它注册了一个 ASM transformer `transformers/temperature.js`，改写 `Biome#shouldFreeze`、`Biome#shouldSnow`、`ServerLevel#tickChunk`、`SnowGolem#aiStep`，把它们转接到 `sereneseasons.season.SeasonHooks.*Hook`。**它不改 `getBaseTemperature()`，而是叠在群系既有数值之上** |
| 对外 API | 存在（Ecliptic Seasons 官方就发布了一个「Serene Seasons API Stub Bridge」兼容层给依赖 SS API 的模组用）⇒ WeatherSync **应该读 SS 的 API，而不是硬覆盖它** |
| 群系模组兼容 | 官方 FAQ：对模组群系「在大多数方面自动工作」，模组群系可能需要正确打上热带群系标签，也可以由用户自制数据包补 |
| 是否直接写 rain flag | **【未确证】**（只确证它会改变降水相态与季节概率） |

**WeatherSync 的共存建议**：**分层，不要对抗**。

- 分工定义：**WeatherSync 决定「此地此刻要不要降水」；Serene Seasons 决定「这个季节/这个群系允许下的是雨还是雪」**。
- 实现上：WeatherSync 只写全局 rain/thunder flag 与计时器；**不要**去改群系温度、不要 patch `shouldSnow`/`shouldFreeze`，也不要假设 `getBaseTemperature()` 在季节变化下恒定（SS 在 1.18.2 走 coremod 钩子，温度在**调用点**被改写，而不是字段被改）。
- 检测到 SS 时，把「冬季 + 目标群系」的期望状态交给 SS 判定：例如真实天气是「小雨」而当前为冬季寒带，写入 `raining = true` 后由原版/SS 决定表现为雪——这正是分工的自然结果。
- **不要**试图用 `setWeatherParameters` 去「压过」SS 的季节天气概率；那只会产生随机反复横跳的天气。

---

### 1.6 体温类：Tough As Nails / Homeostatic / Cold Sweat 等

| 模组 | slug / id | 1.18.2 | modId | 它做什么 | 写天气吗 |
| --- | --- | --- | --- | --- | --- |
| **Tough As Nails** | `tough-as-nails` / `ge1sOdFH` | ✅（含 1.18.1） | **【未确证】** | 体温 + 口渴（1.16 版无体温机制） | **不写**（是否**读**下雨 【未确证】） |
| **Homeostatic** | `homeostatic` / `oDobngty` | ✅ | **【未确证】** | 按 WBGT 的环境温度、体温、口渴、潮湿；**「Biome temperatures in Minecraft are overridden」**（覆盖群系温度）；装有 Serene Seasons / Fabric Seasons 时会纳入季节 | **不写** |
| **Cold Sweat** | `uXhSmPjd` | ✅（1.18.1/1.18.2） | **【未确证】** | 体温生存；mcmod 摘要称**天气是影响温度的因素之一** ⇒ **读**天气 | 不写 |
| **Frozify** / **Freeze It And Heat It** | `ME0bwGV6` / `6jlFKPwR` | ✅ | **【未确证】** | 体温/食物温度附加 | **【未确证】** |

（Modrinth 上以 `1.18.2` 为筛选条件的 `temperature` 关键词检索命中的模组总共只有 6 个，上表已覆盖主要项。）

**WeatherSync 的共存建议**：

- 把它们当 **D 类只读消费者**：WeatherSync 应**主动提供**「当前天气 + 未来短时预报」的只读 API（甚至是 IMCS/事件），让体温模组能读到，而**不要去改它们的温度**。
- **反向注意（重要）**：`Homeostatic` 明确会**覆盖群系温度**。这意味着**装了它以后，`Biome#getBaseTemperature()` 可能不再返回数据包里的值**。WeatherSync 的气候推断**不能把 `getBaseTemperature()` 当作地面真值**，必须把它当作「提示」并有覆盖表兜底（详见 §2.5）。

---

### 1.7 生物群系温度 / 气候覆盖类模组（「Biome Temperature」这一类）

这类模组不写天气，但会**改写天气赖以判断的气候输入**，是隐性的兼容炸弹。

| 模组 | 说明 | 对 WeatherSync 的意义 |
| --- | --- | --- |
| **Climate Adjuster**（`cofl/ClimateAdjuster`） | 提供 `config/climateadjuster/climate_data.json`，按**群系 id**覆盖 `temperature` / `temperatureModifier` / `downfall` / `precipitation`（README 记录取值范围 temperature `[-0.5, 2.0]`、downfall `[0.0, 1.0]`），并显式提示与 Serene Seasons 的交互（「优先用 `rain` 相态」） | **这是本领域现成的「逃生舱」范式**：WeatherSync 应当**读它**（若存在）来修正自己的气候映射，并**提供自己同构的覆盖表**（见 §2.6），而不是另造一套不兼容的配置 |
| **Homeostatic** | 覆盖群系温度（见 §1.6） | 同上：`getBaseTemperature()` 不再可信 |
| **Serene Seasons** | 季节化温度/相态（见 §1.5） | 温度是季节的函数 |
| Forge 层面的机制 | 1.18.2 提供 `BiomeLoadingEvent`，可 `getClimate()/setClimate(Biome.ClimateSettings)` 改写群系气候 | 这类模组就是用它实现的；WeatherSync **应只读、不要写**（见 §2.3） |

---

### 1.8 mcmod.cn「天气」标签下与 1.18.2 相关的热门模组

mcmod 的标签检索入口：`https://www.mcmod.cn/s?key=天气&mold=1`（本次检索到的量级：全部 1064 / **模组 300** / 整合包 22 / 教程 200）。下表只列与 1.18.2 相关或容易被误认为相关的项。

| 模组（mcmod 词条） | 1.18.2 | 类别 | 一句话结论 |
| --- | --- | --- | --- |
| [局部气候&风暴 (Weather, Storms & Tornadoes)](https://www.mcmod.cn/class/860.html) | ✅ | **B** | 1.18.2 头号对手，需 CoroUtil |
| [静谧四季 (Serene Seasons)](https://www.mcmod.cn/class/1132.html) | ✅ | C | 必须共存，见 §1.5 |
| [[TAN] 意志坚定 (Tough As Nails)](https://www.mcmod.cn/class/531.html) | ✅ | D | 体温/口渴，不写天气 |
| [冷汗 (Cold Sweat)](https://www.mcmod.cn/class/4383.html) | ✅ | D | 读天气影响体温 |
| [真实物理 (Physics Mod)](https://www.mcmod.cn/class/3460.html) | ✅（mcmod 表格标「天气物理 ✔」） | E | 纯客户端，不改服务端天气 |
| [动态环境 (Dynamic Surroundings)](https://www.mcmod.cn/class/1083.html) | **【未确证】** | E | 1.17.1 起纯客户端；会为降雨生成随机风暴强度（表现层） |
| [TweakerMore](https://www.mcmod.cn/class/5771.html) | **【未确证】** | E | 有 `tweakmWeatherOverride`，**仅覆盖客户端世界天气，对服务端无影响** |
| [节气 (Ecliptic Seasons)](https://www.mcmod.cn/class/16860.html) | ❌（1.20.1+） | C+部分 B | 会动态影响天气（「天气不再局限于原版可预测的循环」、季节化天气概率、连续降雨与降雪、按温度融雪）；同时是 PA 的三种季节前置之一 |
| [Project Atmosphere](https://www.mcmod.cn/class/26161.html) | ❌（1.20.1/1.21.1） | **B** | 见 §1.1 |
| [ProtoManly's Weather](https://www.mcmod.cn/class/19930.html) | ❌（NeoForge 1.21.1） | **B** | 见 §1.1 |
| [超多生物群系 (Biomes O' Plenty)](https://www.mcmod.cn/class/108.html) | ✅ | C（气候输入） | 见 §2.4 |
| [Terralith](https://www.mcmod.cn/class/4557.html) | ✅ | C（气候输入） | 见 §2.4 |
| [未至之地 (Regions Unexplored)](https://www.mcmod.cn/class/9118.html) | ✅ | C（气候输入） | 见 §2.4 |
| 洲际导弹 ICBM Classic（气象导弹） | **【未确证】** | A（偶发） | 关键词检索命中「气象导弹」；是否会与 WeatherSync 打架 **【未确证】**，建议做运行期日志观测而非预先适配 |
| Create: Slice & Dice | **【未确证】** | D | 关键词检索提到「流动的水会让游戏把该处当作正在下雨」——**这是「局部伪造降雨」的现成先例**，说明「按位置判定降雨」在生态里是常态 |

> 说明：上表中标 **【未确证】** 的几项来自 mcmod 关键词检索摘要，未逐页核对；引用前请复核。

---

### 1.9 1.18.2 上「会写原版天气」的其它模组（必须协商的小写入者）

这些模组不大，但在整合包里出现频率不低，**最容易造成「天气乱跳」**。

| 模组 | slug / id | 1.18.2 | 机制 | 共存建议 |
| --- | --- | --- | --- | --- |
| **Clear Weather 🚫⛈️🌧️❄️** | `clear-weather2` / `T6DObtIe` | ✅ | Modrinth 描述原文：**「Clears the weather every tick」**——**每 tick 清天气** | **硬冲突**。检测到它时 WeatherSync 应直接进入只读模式并在日志里警告（与它对着写会每 tick 打拉锯战） |
| **Survival Weather Control** | `weather-control` / `BJvwQKb4` | ✅ | 用「召唤 Herobrine 的祭坛」招雷暴（数据包 + mod） | 玩家/事件驱动的一次性写入 ⇒ 可共存，但 WeatherSync 需**对账**：检测到自己没写的状态变化时不要立刻覆盖（见 §3.2 R6） |
| **NoWeatherSkip** | `noweatherskip` / `ncsAPvAb` | ✅ | 防止睡觉跳过雨雪 | 低频写入 ⇒ 可共存 |
| **Boat Thunderstorm (weather)** | `boat-thunderstorm` / `EfJ3d5iI` | ✅ | 坐船一段时间后改天气 | 可共存，属「偶发写入」 |
| **Nie's British Weather** | `nies-british-weather` / `SdRZU5MW` | ✅ | 服务端天气玩法（具体机制 **【未确证】**） | 需运行期观测 |
| **Ready Player Fun** | `ready-player-fun` / `AwiAYDUq` | ✅（1.18.1+） | **无玩家在线时暂停服务器**，支持 time / **weather** / season | **应尊重**：WeatherSync 在无玩家在线时同样不应推进/写入天气，避免与它互相抵消 |
| **Better Weather Control**（someaddon） | **【未确证】** | ✅（Forge 1.16.5 / **1.18.2** / 1.19.2） | 通过配置用**命令**控制天气时长、晴天时长、事件概率；自称「因为只是按配置执行命令，所以兼容所有天气模组」，且「只需装在服务端」 | **可共存**：它也是 A 类写入者；但它的自述提供了重要的实现借鉴——**「低频、按配置、用命令语义写入」** 是生态认可的低冲突做法 |

---

### 1.10 ⚠️ 名称与 modId 冲突：`weathersync` 已经被占用

这是本次调研中**最需要立刻决策**的发现。

| 项目 | 事实 |
| --- | --- |
| **Modrinth slug `weathersync`** | 已被占用：[`https://modrinth.com/mod/weathersync`](https://modrinth.com/mod/weathersync)，`project_id = cRmOY2t0`，作者 **macbrayne**，标题 "WeatherSync"，描述 *"Server-side mod to sync peoples' weather to the real world!"*，**Fabric，game_versions = ["1.20.4"]，服务端必需，EUPL-1.2，下载 4021，创建于 2023-12-31，最后更新 2024-01-18** |
| 它的功能 | 与本项目**完全同构**：查询 **Open-Meteo**，支持城市名 / 经纬度 / IP 定位；命令 `/weathersync enable|disable`、`/weathersync location set auto|city <name>|custom <lat> <lon>`、`/weathersync sync`、`/weathersync timer get`；系统属性 `weathersync.api-backend`（默认 `https://api.open-meteo.com/v1/dwd-icon`）。仓库 `github.com/macbrayne/weathersync` |
| modId 是否为 `weathersync` | **强推断，【未确证】**（命令前缀与系统属性名都是 `weathersync`；未能读到其 `fabric.mod.json`——GitHub 直连被 DNS 拦截，`raw.githubusercontent.com` 同样不可达） |
| 其它同名 | Bukkit/Spigot 插件 **WeatherSync**（`dev.bukkit.org/projects/weathersync`、`legacy.curseforge.com/.../bukkit-plugins/weathersync`）；CurseForge "Real World Weather Sync"；Spigot "RealWeather"（Open-Meteo，1.21.x）；Spigot "SyncedWeather"、"WeatherTimeSync"；Modrinth 插件 `weather2beta`（用 OpenWeatherMap，b1.7.3）。以上均为**搜索摘要级**证据 |
| 已占用的相近 slug | `weather-control`（= Survival Weather Control）、`weather2`、`weather-changer`、`weather-detector`、`clear-weather`、`clear-weather2` |
| 为什么是硬冲突 | ① Forge 的 **modId 在一个实例内必须唯一**，同名会导致加载失败；② modId 同时是资源/配置命名空间（`config/weathersync-common.toml`）；③ Modrinth/CurseForge **slug 已被占**，无法用同名发布；④ 用户搜索、整合包讨论、崩溃报告都会混淆 |

**决策建议（按推荐度排序）**：

1. **现在改名**（成本最低的时点就是现在）：把 `modId` 从 `weathersync` 改成一个未被占用的名字（如 `realsky` / `weatherlink` / `skysync` 之类），项目显示名保留 "WeatherSync" 或在副标题注明。**注意**：本文**没有**逐一代查候选名的可用性，任何候选名在定稿前必须同时在 **Modrinth 与 CurseForge** 上核查（本项目无法访问 CurseForge 网页，需人工核查）。
2. 若坚持用 `weathersync` 作 modId：必须在 README 首屏写明与 macbrayne 的 WeatherSync **无关且不可同装**，并接受无法在 Modrinth 使用该 slug；同时因为两者 MC 版本不同（1.20.4 Fabric vs 1.18.2 Forge），实际同装风险有限，但**品牌混淆不可避免**。
3. 无论选哪个：**加一个「同名模组检测」**——启动时若发现已加载同名/同概念的模组，在日志与聊天里给出一条清晰告警。

---

### 1.11 共存决策树（可直接作为代码里的检测顺序）

```
启动 / 世界加载时：
├─ 检测到 clear-weather2（每 tick 清天气）
│    → READONLY（只读 + 日志警告），不写任何天气
├─ 检测到 weather2（Weather2 / WS&T）
│    → YIELD（默认）：不写原版天气，仅暴露只读 API
│    → 或 BRIDGE（需用户显式配置，1.19+ 再评估）
├─ 检测到 project_atmosphere / simple_clouds（1.20.1+）
│    → YIELD：把自己降级为「真实天气数据源」，让它们决定表现
├─ 检测到 sereneseasons
│    → 正常写入，但：不改群系温度、不 patch shouldSnow/shouldFreeze、
│      把相态（雨/雪）判定让给 SS + 原版
├─ 检测到 homeostatic / climate_adjuster
│    → 正常写入，但气候映射改为「优先读它们的覆盖值」，并降低对 getBaseTemperature() 的信任
├─ 检测到 ready-player-fun
│    → 无玩家在线时暂停推进与写入
└─ 默认（无上述模组）
     → NORMAL：温和写手模式（见 §3）
```

配套配置（`SERVER` 类型，见 §3.2 R8）：

```toml
[compat]
   # 三档：AUTO（按上表自动决策）/ FORCE_WRITE（我就是要写）/ READONLY（永不写）
   mode = "AUTO"
   # 与其打架的模组名单，显式让服主增删
   yield_to = ["weather2", "clear-weather2", "project_atmosphere", "simple_clouds"]
   # 当其它模组写入了本模组没写的状态时，多久之后才允许纠正
   reconcile_grace_ticks = 6000
```

---

## 2. 生物群系模组兼容

### 2.1 1.18.2 可用的气候 API 事实表（逐项已核对 javadoc）

来源：[Forge 1.18.2 javadoc 镜像 · Biome](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraft/world/level/biome/Biome.html)

| 成员 | 1.18.2 状态 |
| --- | --- |
| `getBaseTemperature()` | **存在且 public**（1.18.2 里是 `final`） |
| `getDownfall()` | 存在，public |
| `getPrecipitation()` | 存在（无参），返回 `Biome.Precipitation` ∈ {`NONE`, `RAIN`, `SNOW`} |
| `getTemperature(BlockPos)` | **存在但 `private`（且 `@Deprecated`）→ 不可用** |
| `getHeightAdjustedTemperature(BlockPos)` | `private` → 不可用 |
| `warmEnoughToRain(BlockPos)` / `shouldSnow(LevelReader, BlockPos)` / `shouldFreeze(...)` / `coldEnoughToSnow(BlockPos)` | 存在，public（**这就是 Serene Seasons 用 coremod 挂钩的那几个**） |
| `getPrecipitationAt(BlockPos)` / `hasPrecipitation()` | **1.18.2 不存在**（1.19.4 才引入） |
| `getModifiedClimateSettings()` | **1.18.2 不存在**（1.19.3 才出现） |
| 气候字段载体 | `Biome.ClimateSettings`（1.18.2 是普通 `public static class`，字段 **public final**：`precipitation`（枚举）、`temperature`、`temperatureModifier`、`downfall`） |
| 相态修正 | `Biome.TemperatureModifier` ∈ {`NONE`, `FROZEN`}，带 `modifyTemperature(BlockPos, float)` —— **证明 `getBaseTemperature()` ≠ 逐位置温度**。原版 `frozen_ocean.json` 就是 `temperature: 0.0` + `temperature_modifier: "frozen"` |

**跨版本断点表（写平台层适配时按这个分叉）**：

| 版本 | 关键差异 |
| --- | --- |
| 1.18.2 | `getPrecipitation()` / `getDownfall()` 可用；`BiomeCategory` 存在；`ClimateSettings` 是普通类；`BiomeDictionary` 存在但已 `@Deprecated` |
| 1.19.3 | 出现 `getModifiedClimateSettings()` / `modifiableBiomeInfo()`；`ClimateSettings` 变 record；`BiomeCategory` **被移除**；`getBaseTemperature()` 不再 final |
| 1.19.4+ / 1.20.1 | **`getPrecipitation()` 与 `getDownfall()` 被移除**，改为 `hasPrecipitation()` + `getPrecipitationAt(BlockPos)`（数据包字段 `precipitation` → `has_precipitation` 布尔） |

> **移植规则**：`getBaseTemperature()` 是 1.18.2–1.20.1 唯一全程存在的访问器，应以它为基线；`getDownfall()`/`getPrecipitation()` 在 ≥1.19.4 换成 `getModifiedClimateSettings()`。

**逐位置群系查询是否够便宜？** —— 结构上它是四分之一分辨率噪声 + 原版自带的 LRU 缓存（`Biome` 内部有 `temperatureCache`），但**没有任何权威文档说它昂贵或便宜 ⇒ 「昂贵」这一说法本身【未确证】**。工程结论：**不要每 tick 逐位置查**；按「区块/玩家所在地 + 缓存」策略，并把缓存生命周期绑到数据包重载上（见 §2.6）。

---

### 2.2 标签体系（1.18.2）

**原版 [`BiomeTags`（1.18.2）](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraft/tags/BiomeTags.html)**：有 `IS_OCEAN`、`IS_DEEP_OCEAN`、`IS_BEACH`、`IS_RIVER`、`IS_MOUNTAIN`、`IS_BADLANDS`、`IS_HILL`、`IS_TAIGA`、`IS_JUNGLE`、`IS_FOREST`、`IS_NETHER` 与约 45 个 `HAS_*`（结构）。
- **没有通用的 `HAS_STRUCTURE`**（按结构拆分）。
- **没有** `IS_OVERWORLD` / `IS_END` / `IS_SAVANNA`（这些在 1.20.1 才有）。

**Forge [`Tags.Biomes`（1.18.2）](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/common/Tags.Biomes.html)** 补上了气候轴，**这才是气候分类该用的东西**：

`IS_HOT` / `IS_COLD` / `IS_WET` / `IS_DRY` / `IS_SPARSE` / `IS_DENSE`（各有 `_OVERWORLD` / `_NETHER` / `_END` 变体），以及 `IS_SAVANNA`、`IS_CONIFEROUS`、`IS_SPOOKY`、`IS_DEAD`、`IS_LUSH`、`IS_MUSHROOM`、`IS_MAGICAL`、`IS_RARE`、`IS_PLATEAU`、`IS_MODIFIED`、`IS_WATER`、`IS_PLAINS`、`IS_SWAMP`、`IS_SANDY`、`IS_SNOWY`、`IS_WASTELAND`、`IS_BEACH`、`IS_VOID`、`IS_UNDERGROUND`、`IS_PEAK`、`IS_SLOPE`、`IS_OVERWORLD`、`IS_END`。

> **多版本陷阱**：`Tags.Biomes` 到 **1.20.1 丢掉** 了 `IS_SAVANNA` / `IS_BEACH` / `IS_OVERWORLD` / `IS_END`（改为原版提供），并**新增** `IS_CAVE` / `IS_DESERT` / `IS_MOUNTAIN`。所以**同一份引用这些常量的源码不能同时编译 1.18.2 与 1.20.1**，必须在平台层分叉。

**`BiomeDictionary`**：1.18.2 存在但已 `@Deprecated`（javadoc 明确「tags 在 `BiomeLoadingEvent` 阶段还不可用，所以那里仍需要 BiomeDictionary」），到 1.19 **被移除且无替代**（KubeJS 文档口径：「removed with no replacement in favour of biome tags」）。→ **本项目不要基于它做设计。**

---

### 2.3 `BiomeLoadingEvent`：可以改，但**我们不该用它改**

[`BiomeLoadingEvent`（1.18.2）](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/event/world/BiomeLoadingEvent.html) 提供 `getClimate()/setClimate(Biome.ClimateSettings)`、`getCategory()/setCategory()`、`getName()`。1.19.3 起被 biome modifier 取代（该页面在 1.19.3 已 404，**具体移除版本【未确证】**）。

**关键坑（javadoc 原文警告）**：`getName()` 「generally SHOULD NOT be null, but due to vanilla's biome handling and codec weirdness, there may be cases where it is. **Do check for this possibility!**」 ⇒ **任何以「群系名字/id」为键的映射表都必须处理 null / 未注册的情况。**

**WeatherSync 的规则**：

- **只读**：可利用该事件收集「本次加载实际出现了哪些群系 id」用于建立映射表（这是 1.18.2 最可靠的一次性枚举时机）。
- **不要写**：不要用 `setClimate()` 去改群系温度来实现「真实天气」。原因：① 它会与 Serene Seasons / Homeostatic / Climate Adjuster 争抢同一个字段；② 它与真实天气无关（真实天气是「今天的雨」，不是「这个群系的气候」）；③ 用户会失去用数据包调气候的能力。

---

### 2.4 逐个群系模组

#### 超多生物群系 / Biomes O' Plenty（BOP）

| 项 | 内容 |
| --- | --- |
| **modId** | **`biomesoplenty`** ✅（本次从真实发行 jar `BiomesOPlenty-1.18.2-16.0.0.134.jar` 的 `META-INF/mods.toml` 读出；同 jar 还声明依赖 `forge` 与 `terrablender`） |
| 链接 | [mcmod](https://www.mcmod.cn/class/108.html) · [Modrinth `biomes-o-plenty`](https://modrinth.com/mod/biomes-o-plenty)（id `HXF82T3G`） · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/biomes-o-plenty) |
| 1.18.2 | ✅（版本号 `16.0.0.x`；1.18+ 必需前置 **TerraBlender**） |
| **是否设置各群系 baseTemperature** | **是，而且是显式的逐群系数值**。`BOPOverworldBiomes.java` 对每个群系调用 `.temperature(t).downfall(d)`；实测值样例：bayou `0.95/0.9`、bog `0.2/0.5`、coldDesert `0.25/0.0`、coniferousForest `isSnowy ? -0.25 : 0.45`、rainforest `1.2/2.0`、scrubland `1.1/0.15`、**wasteland `2.0/0.0`**、volcano `0.95/0.3`、spiderNest/glowingGrotto `0.5/0.5`。观测区间 **−0.25 … 2.0** |
| 下界群系 | 五個全部硬编码 `.temperature(2.0F).downfall(0.0F)`（与原版一致，即**气候信息在下界是失效的**） |
| 版本注意 | 上述源码取自 **1.18.1 线（15.0.0.x）** 的文件（jsDelivr 找不到 `16.x` tag，`@1.18.2` 路径 404）⇒ **1.18.2 专属文件内容【未确证】**，但「逐群系设置温度」这一事实与 jar 的 modId 均已确证 |

**共存建议**：BOP 是**理想的数据源**——它的 `baseTemperature` 有真实区分度。直接读 `getBaseTemperature()` 即可，无需为它写特例。但注意 `wasteland = 2.0` 与 `volcano = 0.95` 说明这是「视觉调色」参数：**2.0 表示「极度干燥/异星」，不能直接当摄氏气温用**（见 §2.5）。

#### Terralith

| 项 | 内容 |
| --- | --- |
| 链接 | [mcmod](https://www.mcmod.cn/class/4557.html) · [Modrinth `terralith`](https://modrinth.com/mod/terralith)（id `8oi3bsk5`） · 源码 `Stardust-Labs-MC/Terralith` · wiki `stardustlabs.miraheze.org` |
| 1.18.2 | ✅（`project_type` 同时是 `mod` 与 `datapack`，加载器含 forge/fabric/quilt/neoforge；1.18.2 发行物为 `Terralith_1.18.2_v2.2.6.jar`） |
| 加了什么 | **96** 个 `data/terralith/worldgen/biome/*.json` |
| **是否设置温度** | **是**。从 1.18.2 发行 jar 内解出的 `yellowstone.json` 含 `"temperature": 0.24775, "downfall": 0.8` |
| **⚠️ 关键行为** | 它**同时覆写原版群系 id**：仓库里有 `data/minecraft/worldgen/biome/<原版id>.json` 一整套（plains、desert、jungle、swamp、taiga、snowy_plains、badlands、dripstone_caves、lush_caves、frozen_ocean… 名单至少 35 项，来自仓库列表，可能不完整）。这些文件是对原版群系的**完整重定义** |
| 气候标签 | 据称自 1.18.2 起附带 `data/c/tags/worldgen/biome/climate_hot|cold|dry|wet|temperate.json` 与 `data/forge/tags/worldgen/biome/is_*`（**部分确证**，读自被截断的仓库列表，文件内容未逐字核对） |

**共存建议**：Terralith 装了就**不要相信「原版群系 = 原版气候」**。例如它把 `minecraft:plains` 重定义（`temperature: 0.8`），而真实世界的「平原」未必是 0.8 那种气候。→ WeatherSync 的气候映射表**必须以「实际加载后的群系对象」为键，而不是以「我以为的原版群系」为键**；并且**尽可能优先使用它提供的 `forge:is_*` / `c:*` 气候标签**。

#### Oh The Biomes You'll Go（BYG）

| 项 | 内容 |
| --- | --- |
| **⚠️ slug 纠正** | **不是** `oh-the-biomes-youll-go`（该 slug 在 Modrinth 上取不到任何数据）。真实 slug 是 **`biomesyougo`**：[modrinth.com/mod/biomesyougo](https://modrinth.com/mod/biomesyougo)，id `uE1WpIAk` |
| 1.18.2 | ✅（`game_versions` 含 1.18.2；加载器 fabric/forge/quilt；**1.18.2 需要 TerraBlender ≥ 1.2.0.0**） |
| modId | `byg` —— **【未确证】**（jsDelivr 对该仓库报「包超过 50MB 上限」，jar 下载超时，未能读到 `mods.toml` 或资源目录） |
| 温度处理 | **【未确证】** |

**共存建议**：把 BYG 归入「未知气候的模组群系」桶——**靠标签 + 名称关键词 + 覆盖表兜底**，并在文档中诚实标注「BYG 未核对」。

#### Regions Unexplored（未至之地）

| 项 | 内容 |
| --- | --- |
| 链接 | [mcmod](https://www.mcmod.cn/class/9118.html) · [Modrinth `regions-unexplored`](https://modrinth.com/mod/regions-unexplored)（id `Tkikq67H`） · 源码 `Apollounknowndev/RegionsUnexplored` |
| 1.18.2 | ✅（1.18.2 发行物 `RegionsUnexploredForge-0.4.1+1.18.2.jar`；≤0.5.9 用 TerraBlender，≥0.6 用 Lithostitched） |
| **modId** | **`regions_unexplored`** ✅（源码常量 `public static final String MOD_ID = "regions_unexplored";`，并有 `assets/regions_unexplored/...` 资源路径佐证） |
| 温度处理 | 1.18.2 jar 里**没有任何群系 JSON**（对 jar 内所有 `.json` 搜索 `"temperature"` 得 0 命中）⇒ 群系是**代码注册**的；含 `BiomeBuilder` 与 `temperature` 常量的类为 `RegionPrimaryBiomeBuilder` / `RegionSecondaryBiomeBuilder`；**.temperature(...) 的具体字面量【未确证】**（需要反编译） |

**共存建议（重要且反直觉）**：**它的群系是代码注册的、没有数据包 JSON** ⇒ **用户无法用数据包给它打气候标签**，你也无法靠读它的数据文件建映射。这类模组**只能**靠 `getBaseTemperature()` + 名称关键词 + 覆盖表。→ 这正是「覆盖表必须存在」的最强论据。

#### 其它

- **TerraBlender**（`kkmrDlKT`，1.18.2 ✅）：BOP 与 BYG 在 1.18.2 的必需前置，是「加群系的库」；它是否触碰群系温度 **【未确证】**。
- **Ecologics**（1.18.2 ✅）：修改**既有原版群系**（沙漠加骆驼、平原加树），不新增 id；modId **【未确证】**。
- **Wilder Wild**（1.18.2 ❌，`game_versions` 从 1.19 起，且只有 fabric/quilt）。
- **Alex's Caves**（1.18.2 ❌，只有 1.20.1）。
- **Biomes We've Gone** 是**另一个更新的模组**，不要与 BYG 混为一谈。

---

### 2.5 裁决：「原版 `getBaseTemperature()` + 标签 + 名称关键词」够用吗？

**结论：可以当第一遍粗筛，但绝不能单独使用。必须当作「提示 + 覆盖表」的系统来设计。**

九个已确证的失败模式：

1. **`baseTemperature` 本质是视觉参数**。Java 版群系格式把 `temperature` 定义为控制「草/树叶颜色，以及一个高度调整后的温度（决定下雨还是下雪）」——即**为好看而取值**。BOP 的 `wasteland = 2.0`、`volcano = 0.95`，Terralith 的 `yellowstone = 0.24775` vs 它自己定义的 `plains = 0.8`，都不是现实气温。
2. **洞穴群系带地表级温度**：原版 `dripstone_caves` = `temperature 0.8, downfall 0.4, category "underground"`；`lush_caves` = `0.5/0.5`；BOP 的 `glowingGrotto`/`spiderNest` = `0.5/0.5`（`BiomeCategory.UNDERGROUND`）。→ 真实气候映射会把地下当成温带地表。
3. **下界/末地塌缩成单一值**：原版 `nether_wastes` = `temperature 2.0, downfall 0.0, precipitation "none"`；BOP 五个下界群系全是 `2.0F/0.0F`。→ 必须用 `BiomeTags.IS_NETHER` / `Tags.Biomes.IS_NETHER` / `IS_END` 先剔除。
4. **逐位置 ≠ 逐群系**：`TemperatureModifier.FROZEN`（冻洋 `temperature 0.0` + `frozen`）意味着同一群系在不同位置温度不同。
5. **群系 id 不是稳定身份**：Terralith 覆写约 35 个**原版 id**。以 `minecraft:plains` 为键的硬编码表可能描述的不是实际加载的那个群系。
6. **原版标签只含原版群系**：`minecraft:is_forest`（1.18.2）恰好只有 6 个 `minecraft:` 条目；**没有任何机制强制模组群系加入** `minecraft:is_forest` 或 Forge 的 `forge:is_hot` 组。有的模组（如 Terralith）会补，很多不会。Forge 是否自带默认 `forge:is_*` 成员关系 **【未确证】**。
7. **群系可能根本没有 id**：`BiomeLoadingEvent.getName()` 可为 null（javadoc 明说），任何以 id 为键的表都要有 null/未注册分支。
8. **代码注册的群系无法被数据包打标签**：Regions Unexplored 1.18.2 就是这种（jar 内 0 个群系 JSON）。
9. **别的模组会改写温度输入**：`Homeostatic` 明确「覆盖群系温度」；`Serene Seasons` 在调用点改写相态判定；`Climate Adjuster` 按 id 覆盖 `temperature/downfall/precipitation`。→ `getBaseTemperature()` 不等于「数据包写的值」，也不等于「玩家看到的气候」。

**风险量化（供决策）**：在上述前提下，「靠 baseTemperature 自动推断」在**原版 + BOP** 上表现尚可；在 **Terralith / BYG / Regions Unexplored / Homeostatic** 任一存在时**必然出现明显错判**，必须靠覆盖表兜底。

---

### 2.6 推荐的气候解析管线（三级降级 + 覆盖表 + 缓存）

```
输入：Holder<Biome>（及其 ResourceLocation id）
  │
  ├─ 第 0 级：硬排除
  │     Tags.Biomes.IS_NETHER / IS_END / IS_VOID / IS_UNDERGROUND
  │     + 原版 BiomeCategory == NETHER/END/THEEND/UNDERGROUND/NONE
  │     → 判定为「不参与真实气候映射」，直接返回 SKIP
  │
  ├─ 第 1 级：用户覆盖表（最高优先级，永远赢）
  │     config/weathersync/climate_overrides.json
  │     Schema 建议对齐现成的 Climate Adjuster 范式，便于服主迁移：
  │       { "namespace:biome": { "climate": "temperate|hot|dry|cold|polar|tropical|oceanic|null",
  │                                 "temperature": number|null,
  │                                 "downfall": number|null,
  │                                 "precipitation": "none|rain|snow|null" } }
  │     若检测到 Climate Adjuster 存在，优先读它的 climate_data.json
  │
  ├─ 第 2 级：Forge 气候标签（Tags.Biomes）
  │     IS_HOT / IS_COLD / IS_WET / IS_DRY / IS_SNOWY / IS_SANDY / IS_SWAMP /
  │     IS_JUNGLE / IS_TAIGA / IS_SAVANNA / IS_PLAINS / IS_OCEAN ...
  │     → 标签命中即定为该气候带（这是 Forge 设计者给的气候轴）
  │
  ├─ 第 3 级：数值启发式（最后手段）
  │     getBaseTemperature() / getDownfall() / getPrecipitation()
  │     + 群系 id 名称关键词（jungle|desert|snow|tundra|swamp|savanna|ocean...）
  │     + TemperatureModifier == FROZEN 时按「极地/海洋」处理
  │     → 命中时记一条 DEBUG 日志，便于收集长尾
  │
  └─ 兜底：UNKNOWN → 采用「保守中性气候」+ 在磁盘上输出待人工确认清单
        （logs/weathersync/unmapped-biomes.txt 或 config 内注释块）
```

**缓存与失效规则**（有来源支撑）：

- 按 **群系 id** 缓存解析结果，不要每 tick 重新解析标签。
- **不要在跨数据包重载的范围内缓存标签结果**。Forge 论坛的权威回答（warjort）：*「If you are iterating over tags, you are almost certainly doing it wrong… Tags… can change dynamically, e.g. if somebody changes and reloads datapacks while minecraft is running. That means you should not do any preprocessing or caching based on their values at any one specific point in time.」*（[Forge 论坛 #122482](https://forums.minecraftforge.net/topic/122482-how-to-get-an-array-or-similar-list-of-all-forge-biome-tags/)）→ 必须挂在数据包重载/群系加载事件上重建缓存。
- 缓存对象是「解析后的气候」，**不是**「群系对象」；群系对象随世界/数据包变化。
- 提供一个 `/weathersync climate dump` 之类的自检命令，把「每个已见群系 → 解析结果 → 命中级别」打出来。这会极大降低整合包排障成本。

---

## 3. 不冲突的工程实践（Forge 生态的成文与不成文规则）

### 3.1 先搞清楚原版天气到底是什么（这一节决定了后面所有规则）

来源：[Minecraft Wiki · Weather（Java Edition mechanics）](https://minecraft.wiki/w/Weather)、[mappings.dev 1.18.2 `ServerLevel`](https://mappings.dev/1.18.2/net/minecraft/server/level/ServerLevel.html)

- Java 版天气由**两个内部 flag** 驱动（rain、thunder），**各带一个倒计时器**；计时器归零则翻转该 flag 并重新随机取值。
  - rain 打开持续 **12000–24000 tick**，关闭持续 **12000–180000 tick**。
  - thunder 打开持续 **3600–15600 tick**，关闭持续 **12000–180000 tick**。
  - thunder flag **只在 rain flag 为开时才有意义**。
- 1.18.2 的公开写入入口是 **`ServerLevel#setWeatherParameters(int, int, boolean, boolean)`**（`setWeather(int, int, boolean, boolean)`），另有 `private` 的 `tickWeather()` / `resetWeather()`——**私有，别用反射去碰**。
- **`clearWeatherTime` 陷阱（必须写进代码注释）**：Wiki 原文——这个计时器只被 `/weather clear` 影响，正常游戏里恒为 0；**它生效期间 rain 与 thunder 两个 flag 都被强制关闭、且两个 timer 被锁在 1 tick**。因此**当它结束时，两个 flag 会立刻同时翻成「开」，两个 timer 一起重置 → 直接变成雷暴。** ⇒
  - 不要用 `setWeatherParameters(大数值, 0, false, false)` 来实现「长期晴天」——到期会给玩家一个惊喜雷暴。
  - 玩家/服主用 `/weather clear` 之后，不要立刻用 `setWeatherParameters` 覆盖它（也应视为一次「人类意图」）。
- **`doWeatherCycle` 游戏规则**可以直接关掉自然天气变化（开启后天气保持不变，除非用 `/weather` 改）。⇒ 服主关掉它时，默认就表示「别让天气自己变」，**外部天气模组必须尊重这个意图**。
- 原版天气是**全局**的（Wiki：*"temporary but precipitation and storms that are temporary but global"*），而「某处是否真的在下雨」由「群系温度 + 高度」逐位置判定：**降雨只发生在温度 > 0.15 的方块处，且沙漠/热带草原/恶地等干燥群系不下雨；降雪发生在基础温度 < 0.15 的群系**。
- 相关 API 细节（讨论区结论，用于自检逻辑）：`Level#isRaining()` 只表示「有降水这件事」，`Level#isRainingAt(BlockPos)` 才是「该位置是否下雨」（会考虑洞穴与群系）；要判断下雪要另外查相态。有开发者报告即使身处雪中 `getPrecipitation()` 仍返回 `RAIN`（1.12 时代的观测），**说明「下雪」判定不该只看相态枚举**。（来源：[Forge 论坛 #76140](https://forums.minecraftforge.net/topic/76140-eventbus-weather-and-minecart-events/)）

---

### 3.2 十二条规则（结论 / 依据 / 后果）

**R1 · 没有天气事件，只能轮询；而且不要每 tick 干重活。**
- 依据：Forge 官方文档的事件章节没有任何天气事件；Forge 官方论坛两个帖子直接回答「Is there a weather event for rain and snow? **No there isn't**」，并建议「check this in `WorldTickEvent`（**probably not every tick though**）」与「You will basically have to check using `WorldTickEvent`」。
  （[#76140](https://forums.minecraftforge.net/topic/76140-eventbus-weather-and-minecart-events/) · [#107331](https://forums.minecraftforge.net/topic/107331-get-weather-change-event/)）
- 做法：用 `TickEvent` / `LevelTickEvent`，但按**分级频率**执行（例如：状态对账每 100–200 tick、UI/命令即时）；只在「期望状态 ≠ 当前状态」时才写。
- 后果（违反）：每 tick 全量计算 + 逐位置群系查询 = TPS 抖动，且会把别的模组的写入全部冲掉。

**R2 · 写入的语义是「重置计时器」，所以要写「剩余时长」，不要每 tick 重写。**
- 依据：Wiki 的 flag/timer 机制；`setWeatherParameters(clearTime, weatherTime, raining, thundering)`。
- 做法：拿到真实天气后，把它翻译成「接下来 N tick 保持 raining=X, thundering=Y」，一次写入；到期前不要重复写。
- 后果：每 tick 写 → 其它模组的 `setWeatherParameters` 被立刻覆盖、原版天气在客户端反复抖动。

**R3 · 尊重 `/weather` 与 `clearWeatherTime`；尤其不要用 clear 做长期晴天。**
- 依据：§3.1 的 clearWeatherTime 语义（Minecraft Wiki）。
- 做法：记录「上一次天气状态变化是不是我们写的」；不是我们写的，就按 `reconcile_grace_ticks` 冷静一段时间再纠正。

**R4 · 尊重 `doWeatherCycle`。**
- 依据：Minecraft Wiki（该游戏规则关掉后天气不变，除非用 `/weather`）。
- 做法：`doWeatherCycle == false` 时默认**不写**（可配置覆盖），因为服主的意图就是「稳定天气」。

**R5 · 只在逻辑服务端写；跨侧必须走网络包；禁止 static 字段跨侧共享。**
- 依据：Forge 官方 Sides 文档——「Whenever you want to send information from one logical side to another, you must **always** use network packets」，并把「通过 static 字段在单人存档里直连两侧」列为 **Common Mistakes**（会引发竞态与崩溃）；默认判据是 `Level#isClientSide`。
- 做法：天气决策只在 `!level.isClientSide` 分支执行；客户端要显示的东西（HUD、命令补全）走包或走配置同步。
- 后果：客户端改状态 = 幽灵状态/回滚；static 字段 = 单人世界随机崩溃。

**R6 · 写之前先「对账」（reconciliation），不要无条件覆盖。**
- 依据：本条是**工程推论**（不是文档条文），但被生态事实强烈支持——存在 `weather2-compatibility-bridge`、`weather2-compat`、`Better Weather Control`（自称「按配置执行命令所以兼容所有天气模组」）等专门处理「多方写天气」的模组。
- 做法：每次决策前读取当前 `raining/thundering` 与剩余时间；若与期望一致 → 什么都不做；若不一致且**不是我们造成的** → 等一个宽限期再纠正，并打日志（便于定位是哪一方在写）。

**R7 · 事件优先级用 NORMAL / LOW，别用 HIGHEST 去「压」别人；`receiveCanceled` 只在需要观察被取消事件时开。**
- 依据：Forge 1.18.x 官方文档——优先级 `HIGHEST → LOWEST` 依次执行（HIGHEST 最先）；可取消事件被取消后**其它 handler 不再执行**；对不可取消事件调用 `setCanceled` 会抛 `UnsupportedOperationException` 并**预期导致游戏崩溃**，因此「Always check `Event#isCancelable()`」。
  NeoForge 官方文档补充了 `receiveCanceled`：*「Event handlers can opt to explicitly receive cancelled events… by setting the `receiveCanceled` boolean parameter in `IEventBus#addListener` (or `@SubscribeEvent`) to true」*，且「If an event is cancelled, other event handlers for this event will not run」。
- 对 WeatherSync 的具体口径：
  - WeatherSync 关心的 **tick / level load 类事件本身不可取消**，所以**正常情况下不需要 `receiveCanceled`**。硬加 `receiveCanceled = true` 会让你的 handler 在别人取消事件后仍然执行，属于「越权」，应当只在你**明确需要观测**该事件时才开（例如你想统计「谁在取消什么」用于诊断）。
  - **不要**为了让自己的天气生效而用 `HIGHEST` 优先级 + 取消别人事件的方式去压制；这与「不成文规则 R6/R8」冲突，且一旦目标事件不可取消就会崩游戏。
  - 反过来：**如果别的模组取消了某个事件**，正确反应不是「抢回来」，而是「本轮不写、记录一次、并依赖 R6 的对账在下个周期重新评估」。因为天气的最终真值来自服务端的 flag/timer，而不是来自事件是否被取消。
- 参照 `EventPriority` 五个取值（`HIGHEST/HIGH/NORMAL/LOW/LOWEST`）与「同优先级按注册顺序（主总线上大致等于模组加载顺序）」。

**R8 · 用 SERVER 类型配置让服主「一键让出控制权」，并支持热重载。**
- 依据：Forge 官方 Configuration 文档——配置类型表：

  | 类型 | 加载侧 | 是否同步到客户端 | 服务端位置 |
  | --- | --- | --- | --- |
  | `CLIENT` | 仅客户端 | 否 | N/A |
  | `COMMON` | 两侧 | 否 | `<server_folder>/config` |
  | `SERVER` | 仅服务端 | **是** | `<server_folder>/world/serverconfig` |

  并且 `ConfigValue` 支持 `worldRestart` 上下文（「The world must be restarted before the config value can be changed」）；配置变更通过 `ModConfigEvent.Loading` / `ModConfigEvent.Reloading` 通知。
- 做法：把「是否接管天气」「让给谁」「写入频率」「API 端点/超时」「真实地理位置」都放进 **SERVER 配置**（世界级、服主可覆盖、且会同步给客户端用于 HUD）；整合包作者用 **COMMON 配置**提供默认值；实现 `ModConfigEvent.Reloading` 以支持 `/reload` 后立即生效或优雅提示「需重启世界」。

**R9 · 运行期检测其它模组要用 `ModList`，跨模组通信优先 `InterModComms`。**
- 依据：1.18.2 javadoc 已确证 `net.minecraftforge.fml.ModList#isLoaded(String)` 为 public（本次核对 [ModList 1.18.2](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/fml/ModList.html)）；Forge 文档在 Mod Event Bus 一节说明：并行初始化事件中「can't directly execute code from other mods… Use the **`InterModComms`** system for that」（NeoForge 文档同样描述 `InterModEnqueueEvent`/`InterModProcessEvent`）。
- 做法：不要在类加载期就去引用对方类（会 NoClassDefFoundError）；检测到之后再走反射/桥。

**R10 · 「只写原版状态」不够——必须同时提供只读 API 与桥接能力。**
- 依据：生态事实——`weather2-compatibility-bridge`（明确写给「rely on rain detection」的模组）、`weather2-compat`（「让 Minecraft 使用 Weather2 的天气判定系统」）、`tfc-weather`（TFC↔Weather2 桥）、`Serene Seasons API Stub Bridge`。这些桥模组的存在说明：**在装了大天气模组的包里，只改原版 flag 会被绕过**。
- 做法（三层）：① 写原版状态（给只看原版的玩法）② 暴露 `WeatherSyncApi.getCurrent(location)/getForecast()` 只读接口（给体温模组、HUD、逻辑模组）③ 预留 adapter 接口（`WeatherSink`），未来可接 Weather2/PA。

**R11 · 只读地使用 `BiomeLoadingEvent`；不要用它改气候。**（见 §2.3）

**R12 · 不要依赖客户端表现来决定服务端天气；也不要硬依赖特定渲染层。**
- 依据：结论 E 类模组（Pretty Rain、Biome Particle Weather、Particle Rain）全是 `client_only`、`server_side: unsupported`；它们只消费原版降水状态。Forge Sides 文档要求逻辑归逻辑侧。
- 做法：不检测光影/渲染模组来决定天气；不发送自定义天气包给客户端（让原版同步去做）。若要给客户端看「真实天气 HUD」，那走**配置同步 + 只读展示**。

---

### 3.3 事件优先级与 `receiveCanceled` 的正确用法（汇总）

| 场景 | 推荐做法 | 依据 |
| --- | --- | --- |
| 监听 tick 事件做天气推进 | `EventPriority.NORMAL`（或 `LOW`），**不设** `receiveCanceled` | tick 事件不可取消；优先级只影响与其它 handler 的顺序 |
| 需要在别人之后观察最终状态 | `EventPriority.LOWEST` | Forge 文档优先级顺序 |
| 需要先于别人建立状态（例如在做决策前准备缓存） | `EventPriority.HIGH` / `HIGHEST`，但**不要**取消事件 | 同上 |
| 想「压制」另一个模组的写入 | **不要这么做**。改为：检测模组 → 让出控制权（配置） | Forge 文档：取消会让后续 handler 不执行；且不可取消事件会抛异常导致崩溃 |
| 需要观察被取消的事件（诊断/统计） | `receiveCanceled = true` | NeoForge 文档明确定义了该参数 |
| 想取消事件本身 | 先 `event.isCancelable()` 判断；对自己不拥有的事件**不要取消** | Forge 文档警告：对不可取消事件调用会抛 `UnsupportedOperationException`，**预期导致游戏崩溃** |

---

## 4. 多版本策略：把同一套源码从 Forge 1.18.1 复用到 NeoForge 26.x

### 4.1 五种主流方案对比

| 方案 | 工作原理 | 版本对齐 | 加载器对齐 | 优点 | 缺点 / 维护成本 |
| --- | --- | --- | --- | --- | --- |
| **多分支（每版本一分支）** | 每版本独立分支，手工 cherry-pick | ✅ | ✅ | 无工具依赖、心智最简单；Forge 官方历史上的默认做法（Forge 前负责人 LexManos 的官方口径：只在当前稳定版积极开发，旧版只回填必要修复） | **成本最高（线性）**：每次改动都要合并到每个分支并逐个测试；合并冲突与「切分支慢」是官方 Stonecutter 文档列出的既有痛点 |
| **共享 source set + 预处理器** | Gradle `srcDirs` 共享目录；`//#if MC>=11700` 注释条件编译 | ✅ | ✅ | 零/低依赖；[ReplayMod preprocessor](https://github.com/ReplayMod/preprocessor) / [essential-gradle-toolkit](https://github.com/DeDiamondPro/essential-gradle-toolkit) / [WiIIiam278/PreProcessor](https://github.com/WiIIiam278/PreProcessor) 均已存在 | 注释语法脆、IDE 不友好；提交时必须切回主版本；essential-gradle-toolkit 自述「不能一步同时跨 mappings 和 MC 版本」，其对 NeoForge 1.21+/26.x 的支持 **【未确证】** |
| **Stonecutter**（[插件页](https://plugins.gradle.org/plugin/dev.kikugie.stonecutter) · [文档](https://stonecutter.kikugie.dev/wiki)） | `settings.gradle` 里声明版本，每版本一个共享源码的子工程；`//? if >=1.21 {` / `//?}` / `/*? if >1.20 {*/` / `//~ if >=26.1 'A' -> 'B'` / `/*$ mc_version*/` 等指令 | ✅ | 与加载器无关（官方推荐「branched + split buildscript」配合各加载器原生插件） | **当前事实标准**：0.9.8（2026-08-31）；已被真实模组验证到 **NeoForge 26.x**（Elytra Trims：Forge 1.20.1 → NeoForge 26.2；PatPat：1.16 → 26.2，24 个版本）；与 `versions/<loader>-<mc>` 布局天然契合 | 官方自述限制：**不支持源码 remap、不支持 `@Pattern`、不支持按版本整文件覆盖**；内容型大改的模组不建议用；0.10 需要 Kotlin 2.4 + Java 25；最低 Gradle 版本 **【未确证】** |
| **Architectury**（[文档](https://docs.architectury.dev/)） | common + fabric + neoforge 三模块 + 抽象 API | ❌（**不解决 MC 版本对齐**；文档按 MC 版本切分，每版一份 API，示例 `architectury_api_version=20.1.13`） | ✅ | 同版本内 Forge/Fabric 对齐成熟；支持 NeoForge（26.1.x 需 NeoForge 26.1.2.21-beta+、Java 25、Loom 1.17+，26.1 起须 `loom-no-remap`） | 抽象成本高；对「跨 MC 版本」无帮助；26.1.x 文档页已标 "no longer actively maintained"；Stonecutter 文档点名其 flat 用法在 **Legacy Forge 有严重问题** |
| **MultiLoader-Template**（[jaredlll08](https://github.com/jaredlll08/MultiLoader-Template)） | Forge + Fabric 共享 `common` source set；common **只对 vanilla 编译**，不得接触任何加载器 API | ❌（README 完全没提多版本） | ✅ | 概念清晰、约束严格（common 不许碰加载器 API——**这个约束本身值得抄**） | 只解决加载器；维护状态 **【未确证】**（README 仍写 Java 16 与 `genIntellijRuns`） |

### 4.2 逐版本工具链矩阵（含官方依据）

| 目标 | 构建插件 | Gradle | JDK | 依据 |
| --- | --- | --- | --- | --- |
| **Forge 1.18.1 / 1.18.2** | **ForgeGradle 5.x**（本仓库现用 `5.1.+`，正确） | 7.5.1（本仓库现状） | **17**（`options.release = 17`，正确） | [ForgeGradle 5.x 文档](https://docs.minecraftforge.net/en/fg-5.x/gettingstarted/)；Forge 官方 JDK 对照表：1.18–1.19 → JDK 17 |
| Forge 1.17 – 1.20.1 | **ModDevGradle legacy 插件 `net.neoforged.moddev.legacyforge`** | — | 17 | [ModDevGradle LEGACY.md](https://cdn.jsdelivr.net/gh/neoforged/ModDevGradle@main/LEGACY.md)：官方**推荐用它取代 ForgeGradle**，并称可减少将来移植 NeoForge 时的构建脚本改动 |
| NeoForge 1.21.x | **MDG 2.0.x**（推荐）或 NeoGradle 7.x | 8.8 起 | **21** | [MDG 文档](https://docs.neoforged.net/toolchain/docs/plugins/mdg/) |
| NeoForge 26.1 / 26.2 | MDG **2.0.141** / NG **7.1.21** | **≥ 9.1.0** | **25** | [NeoForge 26.1 发布文](https://neoforged.net/news/26.1release/)：26.1 起取消混淆、MC 用 Java 25、语言级别 25；NeoForm 版本格式改为 `<mc>-<build>` |

> 顺带确认：本仓库使用的 `net.minecraftforge:forge:1.18.2-40.3.12` 是 1.18.2 的**最新** Forge 版本（1.18.1 对应 39.1.2 系列），无需改动。

### 4.3 NeoForge 相对 Forge 的「强制分叉点」（这些必须写在平台层）

依据：[NeoForge 20.2 发布文](https://neoforged.net/news/20.2release/) · [EventBus 变更](https://neoforged.net/news/20.2eventbus-changes/) · [注册表重做](https://neoforged.net/news/20.2registry-rework/) · [26.1 发布文](https://neoforged.net/news/26.1release/) · [NeoForge 事件文档](https://docs.neoforged.net/docs/concepts/events/)

- 包名 `net.minecraftforge.*` → `net.neoforged.*`；modid 从 `forge` → `neoforge`；**1.20.5 起 `mods.toml` 改名 `neoforge.mods.toml`**。
- 事件总线：`MinecraftForge.EVENT_BUS` → `NeoForge.EVENT_BUS`；总线包名 → `net.neoforged.bus`；`@Cancelable` → `implements ICancellableEvent`；抽象事件不可监听；静态订阅注解是 **`@EventBusSubscriber(modid = ...)`**（不是 `@Mod.EventBusSubscriber`）。
- **tick 事件在 1.20.5 起被拆成独立抽象类并带 `Pre`/`Post` 变体** ⇒ 天气推进的挂载点在 Forge 1.18.2 与 NeoForge 1.21.x 是不同的 API。
- 入口点：`FMLJavaModLoadingContext` 时代结束，改为构造器注入（26.1 文档示例 `public ExampleMod(ModContainer container)`）。
- 配置：`ForgeConfigSpec` → `ModConfigSpec`。
- 注册表：`ForgeRegistries.X.getValue` → `BuiltInRegistries.X.get`；`RegistryObject` → `DeferredHolder`；capability 改为 `RegisterCapabilitiesEvent` + 类型化 capability。
- 26.1 语义变化举例：`ItemStack`/`FluidStack` 需要注册表已加载 → 改用 `ItemStackTemplate`/`FluidStackTemplate`。

**结论**：**「一套源码」的可行边界是平台层之上的抽象，不是平台层本身。** 平台层的这些差异必须被隔离在少数几个类里。

### 4.4 对 WeatherSync 的具体建议

1. **【最高优先，零风险】把 `core/` 从 `srcDirs` 手写包含改成标准子工程依赖。**
   现状（`versions/forge-1.18.2/build.gradle` 与 `forge-1.18.1`）是：

   ```gradle
   sourceSets { main { java.srcDirs = ['src/main/java', '../forge-common/src/main/java', '../../core/src/main/java'] } }
   ```

   问题：① `core` 的源码被每个版本工程**重复编译**（N 个版本 = N 份 `core` class，还各自带 ForgeGradle 的编译类路径，`core` 声称的「零 Minecraft 依赖」在编译期不再被强制保证——只要有人 import 了 MC 类，`core` 的自测构建才会发现，版本工程构建却可能通过）；② IntelliJ 里 `core` 是独立子工程（`settings.gradle` 已 `include 'core'`），`srcDirs` 又把它拉进 `forge-1.18.2`，会出现**同一份文件属于两个模块**、跳转/重构/警告设置（`-Xlint:all`）不一致的问题。
   改法：`dependencies { implementation project(':core') }`（`core` 已经是 `java-library` 且有自带自测 sourceSet，天然适合）。保留 `forge-common` 的 `srcDirs` 共享是合理的（它就是 1.18.x 的胶水，没有独立编译需求）。

2. **1.18.1 / 1.18.2 维持现状**：FG5 + Gradle 7.5.1 + `release 17`，共享 `forge-common`。两者差异极小，**不要**为它引入任何新工具。

3. **明确「薄平台层」的接口面**（这是跨版本复用的真正抓手）。建议在 `core` 里定义、在平台层实现：
   - `WeatherState`（晴/雨/雷暴 + 强度 + 有效时长）
   - `WeatherWriter.write(WeatherState)`（1.18.2 实现 = `ServerLevel#setWeatherParameters`；21.x 实现 = 对应 API）
   - `ClimateSource`（群系 → 气候，含三级降级管线）
   - `Clock` / `Scheduler`（把「tick 计数」抽象出来，避免核心依赖 MC 时间）
   - `ModPresence`（`isLoaded(modid)` 的抽象）
   然后**平台层永远只有几十行**：把 `Level`、`Biome`、`ModList`、配置转到这些接口上。

4. **等出现第 3 个版本目标（1.20.1）时再引入 Stonecutter**，且只作用于 `versions/` 平台层，`core/` 不进预处理器。官方推荐的「branched + split buildscript」正好匹配现有 `versions/<loader>-<mc>` 布局。
   - 用 0.9.8（稳定）而不是 0.10-alpha（需要 Kotlin 2.4 + Java 25，会污染 1.18.2 的构建环境）。
   - 若要同时支持 1.18.2 与 1.21.x，注意 **tick 事件 API 不同**（1.20.5 拆成 Pre/Post）与 **事件注解名不同**（`@Mod.EventBusSubscriber` vs `@EventBusSubscriber`），这些正是 Stonecutter 条件编译最擅长的场景。

5. **不要引入 Architectury。** 它解决的是「同一 MC 版本内 Forge/Fabric 对齐」，而本项目的需求是「跨 MC 版本 + Forge→NeoForge」；引入它等于给已经够薄的平台层再加一层抽象，并且它在 Legacy Forge 侧被 Stonecutter 文档点名为有问题。

6. **不要指望 MultiLoader-Template 解决版本问题**（它只解决加载器），但**可以抄它的核心约束**：common 模块**只对 vanilla 编译、不得接触任何加载器 API**——这正是 `core/` 应该保持的纪律，而第 1 条的重构会让这条纪律变成构建期强制。

7. **支持策略建议**：1.18.2 是当前首要目标（内容生态成熟、Forge 稳定）；**1.21.1 作为长期主力**（NeoForge 官方明确承诺继续支持，且存量最大）；1.20.1 只能走 Forge（NeoForge 已放弃 1.20.1），性价比需自行权衡；**26.x 属于预留**（26.2 已稳定，但工具链要求 Gradle ≥ 9.1 + JDK 25，与 1.18.2 的 Gradle 7.5.1 + JDK 17 无法共享同一构建进程 ⇒ **必须分仓库/分构建**，这也是「core 独立子工程」价值最大的地方）。

---

## 5. 风险清单（按严重程度排序，1 = 最严重）

> 每条包含：**风险 → 触发条件 → 后果 → 可执行规避方案**。

### 风险 1（致命，非技术）· modId 与品牌被 `WeatherSync` 占据
- **触发**：以 `weathersync` 为 modId/slug 发布或进入整合包。
- **后果**：Modrinth slug 已被 macbrayne 的同概念 mod 占据；Forge modId 在一个实例内必须唯一（同名即加载失败）；modId 同时是配置/资源命名空间；用户与整合包作者必然混淆，崩溃报告归因错误。
- **规避**：① **改 modId / 显示名**（推荐，现在改成本最低）；② 定稿前在 **Modrinth 与 CurseForge 双平台**核查候选名（本项目无法访问 CurseForge 网页，需人工核）；③ README 首屏写清与既有 WeatherSync / Bukkit WeatherSync / Real World Weather Sync 无关；④ 启动时检测同名/同概念模组并告警。

### 风险 2（高）· 与 Weather2 / WS&T 抢天气控制权
- **触发**：整合包同时装 Weather2（1.18.2 需 CoroUtil）与 WeatherSync。
- **后果**：Weather2 **替换**原版天气与雨雪渲染，WeatherSync 写的原版状态「看不见但存在」，导致依赖下雨检测的模组（农田、锈蚀、体温）行为与玩家所见不一致；双方互相覆盖 → 天气乱跳。
- **规避**：① 运行期 `ModList.get().isLoaded(...)` 检测，默认进入 **YIELD**（不写原版天气，只暴露只读 API）；② 提供 `compat.mode` 与 `yield_to` 配置（SERVER 类型）；③ 文档化互斥；④ 引用 Project Atmosphere 官方「与 WS&T 不兼容」的声明，说明这是生态共识而非本模组的缺陷；⑤ 中期按 `weather2-compatibility-bridge` 范式提供 bridge（1.19+ 再评估）。

### 风险 3（高）· 写入方式错误：每 tick 强设 / 误用 `clearWeatherTime` / 覆盖玩家的 `/weather clear`
- **触发**：每 tick 或每个采样周期无条件调用 `setWeatherParameters`；用 `setWeatherParameters(大值, 0, false, false)` 做长期晴天；玩家用 `/weather clear` 后立即覆盖。
- **后果**：① 与其它写入者拉锯（典型：Clear Weather「每 tick 清天气」）；② clearWeatherTime 到期瞬间双 flag 翻滚 → **玩家控诉「莫名雷暴」**；③ 服务器刷无用状态同步包；④ 服主/玩家的意图被无视。
- **规避**：写入「剩余时长」而非「当前外观」；先对账再写（R6）；**禁止用 clearWeatherTime 实现长期晴天**；尊重 `/weather` 与 `doWeatherCycle`；提供 `reconcile_grace_ticks`。

### 风险 4（高）· 气候推断错判，导致观感崩坏
- **触发**：只靠 `getBaseTemperature()`；未排除洞穴/下界/末地；装了 Terralith（覆写原版群系 id）/BYG（未知）/**Regions Unexplored（群系纯代码注册、无 JSON 可打标签）**/Homeostatic（覆盖群系温度）。
- **后果**：沙漠下暴雨、雪原不下雪、地下洞穴被当成温带、真实天气与群系气候互相矛盾 → 用户直接判定「这个模组坏了」。
- **规避**：§2.6 的三级管线 + 第 0 级硬排除 + **用户覆盖表**（对齐 Climate Adjuster 范式，并在其存在时读它）；`/weathersync climate dump` 自检命令；未映射群系输出清单；缓存挂在数据包重载上。

### 风险 5（中高）· 网络依赖与线程模型
- **触发**：直接在服务端 tick 线程里做 HTTP 调用；UApiPro 超时/限流/地区不可达；无缓存。
- **后果**：**服务端卡死（最严重级别的事故）**；天气在断网时完全失效；302/429 导致的异常刷屏。
- **规避**：所有 IO 在异步线程（Project Atmosphere 就是「重计算全部异步 + 克隆群系采样器以避免并发」的正面范例）；主线程只消费不可变快照；硬超时 + 指数退避 + 失败降级（回落「不写天气」而不是写错天气）；磁盘缓存最近一次成功的天气与预报；把 API 端点/超时/开关放进 SERVER 配置；**不要在 `core` 里引入任何第三方 HTTP/JSON 依赖**（现状已经是自实现，保持住）。

### 风险 6（中高）· 客户端/服务端状态与渲染层不一致
- **触发**：在客户端写入或推断天气；用 static 字段跨侧共享；依赖光影/渲染模组决定天气。
- **后果**：单人世界里随机崩溃/幽灵状态（Forge 官方 Sides 文档明确的 common mistake）；装了 Pretty Rain / Biome Particle Weather 等客户端表现模组后玩家看到的与服务端状态不一致，被误报为 WeatherSync 的 bug。
- **规避**：只在 `!level.isClientSide` 决策与写入；跨侧一律走包；客户端只做只读展示（走配置同步）；文档明确「渲染层由客户端模组负责」。

### 风险 7（中）· 多版本/多加载器代码分叉导致语义漂移
- **触发**：把 1.18.2 的群系气候 API 直接搬到 1.19.4+/1.21.x（`getDownfall()`/`getPrecipitation()` 被移除）；把 `@Mod.EventBusSubscriber` 搬到 NeoForge；依赖 `Tags.Biomes.IS_SAVANNA`（1.20.1 已移除）；tick 事件在 1.20.5 被拆分。
- **后果**：编译失败或更糟——**静默的行为改变**（例如相态判断失效）。
- **规避**：`core/` 纯 Java、平台层薄；把全部版本/加载器差异收敛到接口实现里；在 `core` 的接口注释里显式写出「此接口在 1.18.2 与 1.21.x 分别由什么实现」；引入 CI 矩阵逐版本构建；等第 3 个目标出现且差异变大时再上 Stonecutter。

### 风险 8（中）· 服主/整合包作者无法关闭或覆盖
- **触发**：把所有东西塞进 `COMMON` 配置或硬编码；不响应配置热重载；没有「让出控制权」开关。
- **后果**：服主唯一的办法是删模组；整合包作者无法给出一致默认值；`/reload` 后行为与配置不符。
- **规避**：`SERVER` 配置放运行策略（世界级、服主可覆盖、会同步给客户端），`COMMON` 放默认值；对需要重启的项使用 `worldRestart` 语义并在注释里说明；实现 `ModConfigEvent.Reloading`；文档给出「让出控制权 / 只读 / 完全关闭」三个明确档位。

### 风险 9（中）· 与季节/温度模组的语义打架
- **触发**：WeatherSync 写入的「下雨」在冬季寒带被 Serene Seasons 表现为雪（用户以为没生效），或被 Homeostatic 的体感温度系统解读为不同结果；或 WeatherSync 反过来去改群系温度。
- **后果**：用户困惑、反复报 bug；若改了群系温度，则与 SS/Homeostatic/Climate Adjuster 三方争抢同一个字段。
- **规避**：**分层不动摇**——WeatherSync 只决定「是否降水 + 是否雷暴」，绝不改群系温度、绝不 patch `shouldSnow`/`shouldFreeze`/`tickChunk`；文档写清分工；检测到 SS 时在 `/weathersync status` 里显示「相态判定已交由 Serene Seasons」。

### 风险 10（中低，但会被投诉）· 真实地点映射与隐私/多世界语义不清
- **触发**：默认用 IP 定位；把「真实坐标 ↔ 世界坐标」硬绑定；服务器多世界/多维度下只支持一个地点；未说明数据去哪了。
- **后果**：隐私投诉；`/weathersync` 在多世界里行为不可预期；玩家在服务器上看到「另一个城市的天气」而困惑。
- **规避**：**默认不启用 IP 定位**（要求显式配置城市/经纬度）；把「真实地点」定义为**每个世界一份**的 SERVER 配置，并明确只作用于主世界（其它维度不写天气）；在 README 与游戏内说明会向哪个外部 API 发送什么数据（UApiPro），并提供完全离线模式（用缓存/固定值）。

---

## 附录 A · 一手来源清单（本次实际抓取/核对）

**原版机制与 API**
- Minecraft Wiki · Weather（Java Edition mechanics、clearWeatherTime 语义、doWeatherCycle、降雨/降雪的群系温度阈值）：https://minecraft.wiki/w/Weather
- mappings.dev 1.18.2 `ServerLevel`（`setWeatherParameters`、私有 `tickWeather`/`resetWeather`）：https://mappings.dev/1.18.2/net/minecraft/server/level/ServerLevel.html
- Forge 1.18.2 javadoc 镜像 · `Biome`：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraft/world/level/biome/Biome.html
- 同上 · `Biome.ClimateSettings`：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraft/world/level/biome/Biome.ClimateSettings.html
- 同上 · `Biome.TemperatureModifier`：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraft/world/level/biome/Biome.TemperatureModifier.html
- 同上 · `BiomeTags`：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraft/tags/BiomeTags.html
- 同上 · `Tags.Biomes`：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/common/Tags.Biomes.html
- 同上 · `BiomeDictionary`（已废弃）：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/common/BiomeDictionary.html
- 同上 · `BiomeLoadingEvent`（`getName()` 可空警告）：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/event/world/BiomeLoadingEvent.html
- 同上 · `ModList#isLoaded`：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/net/minecraftforge/fml/ModList.html
- 1.19.3 / 1.19.4 / 1.20.1 `Biome` 对照：https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.19.3/net/minecraft/world/level/biome/Biome.html · https://mcstreetguy.github.io/ForgeJavaDocs/1.19.4-45.1.0/net/minecraft/world/level/biome/Biome.html

**Forge / NeoForge 官方规则**
- Forge 1.18.x · Events（优先级、取消、`isCancelable` 警告）：https://docs.minecraftforge.net/en/1.18.x/concepts/events/
- Forge 1.18.x · Sides（跨侧必须走包、static 字段反例）：https://docs.minecraftforge.net/en/1.18.x/concepts/sides/
- Forge 1.18.x · Configuration（SERVER/COMMON/CLIENT 类型表、`worldRestart`、`ModConfigEvent`）：https://docs.minecraftforge.net/en/1.18.x/misc/config/
- Forge 1.18.x · ForgeGradle 5.x 起步：https://docs.minecraftforge.net/en/fg-5.x/gettingstarted/
- NeoForge · Events（`receiveCanceled`、总线、生命周期、InterModComms）：https://docs.neoforged.net/docs/concepts/events/
- NeoForge · MDG 工具链：https://docs.neoforged.net/toolchain/docs/plugins/mdg/
- ModDevGradle LEGACY（Forge 1.17–1.20.1 的官方推荐替代）：https://cdn.jsdelivr.net/gh/neoforged/ModDevGradle@main/LEGACY.md
- NeoForge 20.2 发布（包名全量改名、真实证据）：https://neoforged.net/news/20.2release/
- NeoForge EventBus 变更（`ICancellableEvent`）：https://neoforged.net/news/20.2eventbus-changes/
- NeoForge 注册表重做：https://neoforged.net/news/20.2registry-rework/
- NeoForge 26.1 发布（Java 25、Gradle ≥9.1、MDG 2.0.141）：https://neoforged.net/news/26.1release/
- NeoForge 2024 回顾（放弃 1.20.1、tick 事件拆分）：https://neoforged.net/news/2024-retrospective/
- Forge 论坛 · 「有没有天气事件」（答案：没有；用 WorldTickEvent，别每 tick）：https://forums.minecraftforge.net/topic/76140-eventbus-weather-and-minecart-events/
- Forge 论坛 · 「Get weather change event」（同上）：https://forums.minecraftforge.net/topic/107331-get-weather-change-event/
- Forge 论坛 · 标签不可长期缓存（warjort）：https://forums.minecraftforge.net/topic/122482-how-to-get-an-array-or-similar-list-of-all-forge-biome-tags/
- Forge 论坛 · LexManos「只在最新稳定版开发」：https://forums.minecraftforge.net/topic/61939-should-we-even/
- Forge 论坛 · 分级支持政策：https://forums.minecraftforge.net/topic/144690-new-tiered-support-policy/

**多版本工具链**
- Stonecutter 插件页：https://plugins.gradle.org/plugin/dev.kikugie.stonecutter
- Stonecutter wiki（指令语法、多加载器指南、FAQ 限制）：https://stonecutter.kikugie.dev/wiki · https://stonecutter.kikugie.dev/wiki/v2/guides/tips/multiloader · https://stonecutter.kikugie.dev/wiki/v2/faq
- Architectury 起步（按 MC 版本切分 API；26.1 需 NeoForge 26.1.2.21-beta+）：https://docs.architectury.dev/api/26.1.x/getting-started/setup/
- ReplayMod preprocessor：https://github.com/ReplayMod/preprocessor
- essential-gradle-toolkit：https://cdn.jsdelivr.net/gh/DeDiamondPro/essential-gradle-toolkit@master/README.md
- WiIIiam278/PreProcessor：https://cdn.jsdelivr.net/gh/WiIIiam278/PreProcessor@master/README.md
- MultiLoader-Template：https://cdn.jsdelivr.net/gh/jaredlll08/MultiLoader-Template@main/README.md

**模组事实（Modrinth JSON API = 最高可信度）**
- `weathersync`（既有同名模组）：https://api.modrinth.com/v2/project/weathersync · https://modrinth.com/mod/weathersync
- Weather2 / WS&T：https://www.mcmod.cn/class/860.html · https://modrinth.com/mod/weather-storms-tornadoes · https://github.com/Corosauce/weather2
- Project Atmosphere：https://www.mcmod.cn/class/26161.html · https://github.com/xGabou/Project-Atmosphere
- ProtoManly's Weather：https://www.mcmod.cn/class/19930.html · https://modrinth.com/mod/protomanlys-weather
- Serene Seasons：https://www.mcmod.cn/class/1132.html · https://api.modrinth.com/v2/project/serene-seasons · https://modrinth.com/mod/serene-seasons
- Biomes O' Plenty：https://www.mcmod.cn/class/108.html · https://api.modrinth.com/v2/project/biomes-o-plenty · https://modrinth.com/mod/biomes-o-plenty
- Terralith：https://modrinth.com/mod/terralith · https://www.mcmod.cn/class/4557.html
- BYG（注意真实 slug）：https://modrinth.com/mod/biomesyougo
- Regions Unexplored：https://modrinth.com/mod/regions-unexplored · https://www.mcmod.cn/class/9118.html
- Tough As Nails：https://api.modrinth.com/v2/project/tough-as-nails · https://www.mcmod.cn/class/531.html
- Homeostatic：https://api.modrinth.com/v2/project/homeostatic
- Cold Sweat：https://www.mcmod.cn/class/4383.html
- Climate Adjuster（覆盖表范式）：https://cdn.jsdelivr.net/gh/cofl/ClimateAdjuster@master/README.md
- Clear Weather（每 tick 清天气）：https://modrinth.com/mod/clear-weather2
- Immersive Weathering（只读消费者）：https://api.modrinth.com/v2/project/immersive-weathering
- mcmod「天气」标签检索：https://www.mcmod.cn/s?key=天气&mold=1

---

## 附录 B · 未确证清单（不要在实现/文档里当事实用）

1. `Project Atmosphere` 的 modId；其 Modrinth 词条当前状态（API 反复返回非 JSON、网页 500）。
2. `Weather2` 的 modId 是否为 `weather2`（**强推断**：仓库名 + mcmod 命令文档；未能从 1.18.2 jar 的 `mods.toml` 读出）。
3. `Weather2` 是否提供面向第三方的正式 API（证据倾向「没有」）。
4. 既有 Modrinth `weathersync` 模组的 modId 是否字面等于 `weathersync`（**强推断**：命令前缀与系统属性名）。
5. `Serene Seasons` 是否直接写原版 rain flag（只确证它改变降水相态与季节天气概率，及它用 coremod 挂钩 `shouldFreeze/shouldSnow/tickChunk`）。
6. `Tough As Nails` / `Homeostatic` / `Frozify` 是否**读**下雨（是否写天气已足以判定为「不写」）。
7. `BYG` 的 modId 与温度处理；`Ecologics`/`TerraBlender`/`CorgiLib` 的 modId。
8. `BOP` 1.18.2 专属源文件内容（引用的是 1.18.1 线文件；「逐群系设置温度」与 modId 本身已确证）。
9. `Regions Unexplored` 的 `.temperature(...)` 字面值（仅常量池证据）；`Terralith` 的气候标签文件内容与「覆写原版群系」的完整数目（列表被截断，至少 35 项）。
10. 1.18.2 群系 JSON 省略 `temperature` 时的数值默认值（常说是 0.5，**无来源**）。
11. 「逐位置 `Level#getBiome(BlockPos)` 很贵」这一说法本身（只有结构性旁证）。
12. Forge 1.18.2 是否自带默认的 `forge:is_*` 群系标签成员关系。
13. `Better Weather Control` 的 CurseForge 链接与 modId；CurseForge "Weather 2 - Remastered" 与 Modrinth "Weather and Tornadoes Remastered" 是否为同一项目。
14. 「Dynamic Weather」/「Dynamic Weather and AA」作为 Minecraft 模组是否存在（**不要写进冲突清单**）。
15. Stonecutter 的最低 Gradle 版本；`essential-gradle-toolkit` 是否支持 NeoForge 1.21+/26.x；MultiLoader-Template 的维护状态。
16. NeoForge 官方从未使用过「LTS」这一术语（1.21.1 的「长期支持」是社区口径 + 官方「继续支持」表述）。
17. 任何候选新 modId 在各平台的可用性（必须在 Modrinth **与 CurseForge** 双平台人工核查）。

---

*报告完成。若本报告与仓库内其它文档（如 ROADMAP）冲突，以本报告中已标注来源的结论为准；对**【未确证】**项，应先补齐来源再写入设计文档。*

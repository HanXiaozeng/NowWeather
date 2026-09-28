# NowWeather

> 用**真实天气**驱动 Minecraft 的天气。
> 通过 [UApiPro](https://uapis.cn/docs/api-reference/get-misc-weather) 获取你所在地区的实时天气，
> 再按**每个生物群系自己的气候**（正常温度范围 + 可能出现的天气）判断这份数据该怎么落地到游戏里。

---

## 这个模组解决什么问题

直接把「杭州今天 30°C 晴」照搬到游戏里是不对的 —— 玩家可能正站在雪原上。
NowWeather 的做法是**三层处理**：

1. **真实天气**是锚点：告诉你此刻现实世界在发生什么（晴 / 雷阵雨 / 大雪 / 沙尘暴……）；
2. **生物群系气候**是约束：每个群系都有自己的「正常温度范围」和「可能出现的天气」白名单；
3. **预报系统 + 合理性校验**负责把两者调和 —— 沙漠里就算真实世界在下暴雨，游戏里也只会是霾或多云；
   雪原上的 30°C 会被拉回正常范围；丛林里该打雷就打雷。

```
UApiPro 实时天气 ──┐
                   ├─→ 气候合理性校验 ─→ 天气决策 ─→ Minecraft 天气（晴/雨/雷暴）
生物群系气候数据 ──┘        ↑                              │
                     预报系统（选定天气类型）               ↓
                                                   同步给客户端 / 联动其它天气模组
```

---

## 当前状态

| 模块 | 状态 |
| --- | --- |
| `core` 核心（与 Minecraft 完全解耦，自带断言全绿，`pwsh -File tools/test-core.ps1` 一条命令复跑） | ✅ 完成 |
| 生物群系气候数据：原版基线 + 后续版本新增 + 超多生物群系 + Terralith + Tectonic 全覆盖 | ✅ 完成 |
| 「正常温度范围」+「可能出现的天气」双重校验 | ✅ 完成 |
| 预报系统（真实天气锚点 + 昼夜曲线 + 马尔可夫链 + 气候约束） | ✅ 完成 |
| 数据源：彩云天气 / Open-Meteo / UApiPro（城市名 / adcode / 坐标 / IP 定位多种方式） | ✅ 完成 |
| Forge 1.18.2 / 1.18.1 平台层（配置界面、网络同步、命令、HUD） | ✅ 完成 |
| **中英双语**：命令 / 配置界面 / HUD / 提示全部走语言键，跟随 MC 客户端语言 | ✅ 完成 |
| 真机验收：全群系 × 全部天气类型的批量自检，**0 越界、0 回读失败** | ✅ 完成 |
| **地形订正**：把查询坐标处的数据按递减率/露点/气压换算到玩家实际海拔，而不是照搬原始值 | ✅ 完成 |
| **LLM 助手**（DeepSeek）：每日一次的 AI 天气推算 + 自然语言改配置，均已真机调通 | ✅ 完成 |
| **F3 调试信息**：把真实天气 / 气候 / 决策 / LLM 状态追加到原版 F3 右栏（**默认关闭**，用 `hud.showInDebugScreen` 打开） | ✅ 完成 |
| **原版 /weather 仍可用**，但效果最多持续 20 分钟，到期模组重新接管 | ✅ 完成 |
| **台风子系统**（core）：路径、落点判定、Rankine 风场、雷击落点预测 | ✅ 完成（数据源接入未做） |
| **模组联动**：Serene Seasons ✅、Weather2 只读+主动干预 ✅、AmbientSounds / SoundPhysics 待上游接口 | 🚧 进行中 |
| 其它天气模组联动（Project Atmosphere / ProtoManly's Weather 等，1.18.2 上根本装不上） | 🗺️ 已留接口，见 `docs/ROADMAP.md` |
| NeoForge 1.20.1+ / 1.21.x / 26.x | 🗺️ 见 `docs/ROADMAP.md` |

关于内置气候数据的规模与构成，见下面的「[用数据包覆盖气候数据](#用数据包覆盖气候数据)」一节
（这里不写死条目数：数据会随版本与联动模组增长，写进 README 就会漂移）。

---

## 目录结构

```
NowWeather/
├─ core/                    纯 Java 核心：零 Minecraft 依赖，可独立编译、独立测试
│   ├─ src/main/java/com/NowWeather/core/
│   │   ├─ climate/         生物群系气候：温度范围、天气白名单、气候大类、关键词推断
│   │   ├─ weather/         标准化天气类型、观测数据、风向风级
│   │   ├─ forecast/        预报引擎、昼夜温度模型、气候合理性校验
│   │   ├─ providers/       数据源实现（彩云天气、Open-Meteo、UApiPro、固定天气）
│   │   ├─ api/             ★ 对外扩展接口（WeatherProvider / WeatherBinding / PlayerHandle…）
│   │   ├─ text/            翻译缝：只产出语言键，由平台层解析成当前语言
│   │   ├─ sync/            同步策略与控制器
│   │   └─ json/ http/ log/ 自带零依赖 JSON、HTTP、日志
│   └─ src/test/java/       自带测试（不需要 JUnit，离线可跑）
├─ versions/
│   ├─ forge-common/        1.18.x 通用胶水代码 + 数据包资源 + 语言文件（zh_cn / en_us）
│   ├─ forge-1.18.2/        首要目标
│   └─ forge-1.18.1/        首要目标
├─ tools/gen/               气候数据集生成器（JSON + 人类可读总表）
└─ docs/                    架构、路线图、气候总表、调研报告
```

---

## 构建

### 环境要求

- **JDK 17**（Forge 1.18.x 必须用 17 编译；Gradle 7.5.1 本身也只能跑在 Java 8~18 上）
- 首次构建需要联网下载 Minecraft / Forge 依赖（约 500MB，之后有缓存）

> ⚠️ **1.18.x 的游戏也必须运行在 Java 17 上**（客户端与服务端都一样）。
> 实测用 Java 22 启动 1.18.1 服务端时，TerraBlender 的 mixin 会直接崩：
> `Unsupported class file major version 66`（66 = Java 22 的 class 文件版本）。

### 命令

```bash
# 完整构建 1.18.2
./gradlew :forge-1.18.2:build

# 完整构建 1.18.1
./gradlew :forge-1.18.1:build

# 只跑核心自测（不需要 Minecraft 依赖，几秒钟）
./gradlew :core:coreSelfTest

# 启动带模组的客户端 / 服务端
./gradlew :forge-1.18.2:runClient
./gradlew :forge-1.18.2:runServer
```

产物：`versions/forge-1.18.2/build/libs/NowWeather-1.18.2-<版本>.jar`

### 国内网络注意

- Gradle 发行包默认走腾讯云镜像（见 `gradle/wrapper/gradle-wrapper.properties` 里的说明）；
- 若 `git clone` 后第一次 `./gradlew` 报 `PKIX path building failed`，说明 Gradle 官方源被拦截，
  按该文件注释切回镜像即可；
- 若你的 JDK 17 不在默认位置，把它写进 `~/.gradle/gradle.properties`：
  `org.gradle.java.installations.paths=D:\\你的路径\\jdk-17`

### 离线验证核心逻辑

核心模块不依赖 Minecraft，可以脱离 Forge 单独验证（气候推断、预报合理性、天气接口解析、
天气白名单、同步链路……），推荐直接用仓库自带的脚本：

```powershell
pwsh -File tools/test-core.ps1
# JDK 17 不在常见位置时显式指定：
pwsh -File tools/test-core.ps1 -Jdk "D:\jdk-17"
```

脚本会自己找 JDK 17、编译 `core/src/main/java` + `core/src/test/java`，
**并把 `core/src/main/resources`（陆海掩码等资源）一起复制到输出目录**，然后运行自测。

> **手工用 `javac` 时，必须自己把资源复制过去。**
> 漏掉这一步不会报错，但陆海掩码加载不到，季节振幅会**静默退化成旧公式**（结果变差却没有任何提示），
> 是这条离线流程里最容易踩的坑：
>
> ```powershell
> $out = 'tools\out\selftest'
> javac -encoding UTF-8 --release 17 -d $out `
>   (Get-ChildItem -Recurse core\src\main\java, core\src\test\java -Filter *.java).FullName
> Copy-Item core\src\main\resources\* $out -Recurse -Force   # ← 不能省
> java -cp $out com.nowweather.core.test.CoreSelfTest
> ```

---

## 游戏内配置界面

```
主菜单 / 暂停菜单 → Mods（模组）→ NowWeather → Config（配置）
```

**全部配置项都能在这个界面里改**，按功能分成 **9 个标签页**：

| 标签页 | 内容 |
| --- | --- |
| 位置 | 决定查询哪个地区的真实天气：adcode / 城市名 / 坐标 / IP 定位 / 跨城播报 |
| 数据源 | 天气接口与刷新节奏：首选数据源、各家 Key / Token、刷新间隔、过期判定、超时、接口地址 |
| 天气映射 | 真实天气怎么变成游戏里的天气：总开关、尊重本地气候、雨/雷阈值、持续时长、下界末地保护、季节联动、地形订正、离线兜底 |
| 时间同步 | 游戏内一天 = 真实 24 小时：开关、时区偏移、校正间隔、月相跟随、光影连续气象强度 |
| 预报 | 本地预报的粒度与容差：预报格数、每格时长、温度容差、极端天气 |
| 同步与兼容 | 作用维度、客户端同步、强制群系、与其它天气模组共存 |
| LLM 助手 | AI 辅助推算天气 / 自然语言改配置（会产生费用，默认全关） |
| 调试 | 排查「为什么拿不到天气」：数据源日志、天气切换广播 |
| 客户端 HUD | 只影响你自己的界面：HUD 开关/位置/偏移、显示哪些内容、天色与云量、预报行数 |

> 这里**刻意不写每一项的条数**：配置项会随功能增加，写死的数字第二天就会变成错的。
> 想知道当前有多少项、各自叫什么，用 `/nowweather config` 直接列出来 —— 那个列表永远是最新的。

界面行为：

- 开关与枚举**点一下切换**；文本与数值用输入框，**保存时才校验** ——
  越界会当场告诉你「不能小于 30」，而不是被悄悄夹掉；
- 标签左边出现 `●` 表示**这一项已经不是默认值**，改过哪些一目了然；
- 鼠标悬停在标签上会显示该项的说明；
- 「恢复默认」把当前页改回默认值（只改内存），「保存」写盘并**立即热重载**；
- 「测试连接」会真的去请求一次接口，当场告诉你 API Key / 城市名能不能用。

配置文件位置：`config/nowweather-common.toml`、`config/nowweather-client.toml`（都能手改）。

### 专用服务器：`/nowweather config`

不想开客户端也能改。命令与界面共用**同一份描述表**
（`ConfigEntries`），所以界面里能改的、命令里也能改，取值范围也只有一处定义：

| 命令 | 说明 |
| --- | --- |
| `/nowweather config` | 列出全部可改项与当前值，非默认项会标出默认值 |
| `/nowweather config list [分组]` | 只看某个分组。分组名接受枚举名 / 配置键前缀 / 界面标签三种写法，例如 `time` / `timesync` / `时间同步` |
| `/nowweather config get <键>` | 查一项 |
| `/nowweather config set <键> <值>` | 改一项，写盘并立即生效 |
| `/nowweather config reset <键>` | 恢复默认值 |
| `/nowweather config reload` | 重新载入数据包/配置里的气候数据 |
| `/nowweather config dump` | 把内置气候数据导出到 `config/nowweather/climate/` 方便直接改 |
| `/nowweather config ask <一句话>` | 用自然语言改配置（AI 只能改白名单内的键，且走同一套范围校验） |

键名就是 toml 路径，例如 `api.refreshIntervalSeconds`、
`timesync.timezoneOffsetMinutes`（填 `AUTO` 表示跟随天气接口）。

`set` 之后还会做**交叉校验**，把「单看每项都合法、合起来却不对」的组合当场指出来，例如
「最短持续刻数 > 最长持续刻数」「过期判定 < 刷新间隔」「关掉了下界/末地保护」。

> **三个实测踩到的坑**（都写在代码注释里了）：
>
> 1. Forge 1.18.2 **不会**自动为模组生成配置界面 —— 不注册 `ConfigGuiFactory` 的话，
>    模组列表里的 Config 按钮是灰的（`ModListScreen.java:400`）。
>    「自动生成配置界面」是 1.19+ 才有的功能，所以这个界面是本模组自己实现的。
> 2. `ConfigValue#set()` 只写内存、`save()` 只写文件，**两者都不会触发
>    `ModConfigEvent.Reloading`**（读 Forge 源码确认；那个事件是文件监视器发现「外部改动」时才发的）。
>    所以保存后模组会显式调用自己的热重载，保证「点了保存就一定生效」，
>    而不是赌文件监视器的时序。
> 3. 默认值不要自己抄一遍 —— 用一份**不挂文件的「影子 spec」**去读：
>    它的 `childConfig == null`，所以 `ConfigValue#get()` 必定返回默认值。
>    注意别用 `spec.get(path)`，那会返回 `ValueSpec` 对象本身
>    （实机验证时真的打印出过 `ForgeConfigSpec$ValueSpec@499cfcfe`）。

---

## 命令

**整条 `/nowweather` 命令字面量都要求权限等级 2（OP）**，不只是 `config`：
所有子命令（含 `status`、`info`、`test`）都需要 OP —— 命令由服务端执行，且能看到玩家位置与本地气候数据。
**所有子命令都有 Tab 补全**（包括子命令本身与其可选分组 / 主题参数）。

| 命令 | 作用 |
| --- | --- |
| `/nowweather` | 概览：一句话说清「真实天气 / 本地气候 / 已落地 / 时间」 |
| `/nowweather status` | 完整状态报告 |
| `/nowweather forecast` | 预报列表（合成预报） |
| `/nowweather forecast sky` | 真实天色预报（逐小时云量 / 能见度 / 降水概率） |
| `/nowweather forecast ai` | AI 辅助推算一次（每天一次，有预算） |
| `/nowweather refresh` | 立刻重新拉取真实天气 |
| `/nowweather set <天气>` | 手动设定天气（暂停自动同步） |
| `/nowweather auto` | 恢复自动同步 |
| `/nowweather config` | 列出全部配置项（= `config list`） |
| `/nowweather config list [分组]` | 按分组列出 |
| `/nowweather config get <键>` | 查看单项 |
| `/nowweather config set <键> <值>` | 修改单项（立即生效） |
| `/nowweather config reset <键>` | 恢复默认 |
| `/nowweather config reload` | 重新载入数据包/配置里的气候数据 |
| `/nowweather config dump` | 把内置气候数据导出到 `config/nowweather/climate/` |
| `/nowweather config ask <一句话>` | 用自然语言改配置（AI 只能改白名单内的键，且走同一套范围校验） |
| `/nowweather info <主题>` | 查看某个主题的详细状态，主题见下表 |
| `/nowweather test [biome\|weather\|matrix\|all\|clear]` | 自检（不需要真实玩家也能跑） |
| `/nowweather help` | 帮助 |

### `info <主题>`

| 主题 | 展示什么 |
| --- | --- |
| `biome` | 当前生物群系的完整气候画像：气候大类、正常温度范围、可能出现的天气白名单 |
| `time` | 时区是怎么解析出来的（手动 / 接口 / 系统 / 经度估算）、所在地真实时间与游戏内钟点 |
| `terrain` | 当前位置的地形画像 + 地形订正前后的对比 |
| `shaders` | 光影联动状态：探测到的加载器与光影包、连续气象强度是否已写入、原版雨渲染是接管还是交还，以及 `rainStrength` 的**两段式区间编码**（0.00~0.60 云量 / 0.75~1.00 降水 / 0.60~0.75 缓冲带）。注意这里打出的是**契约本身**，不是「与光影包比对的结果」—— Iris / OptiFine 没有给第三方模组读光影包设置的 API，模组无法在运行时确认对方用的是不是同一套常量 |
| `providers` | 已注册的天气数据源、各自优先级与当前是否可用 |
| `cache` | 缓存目录、地点画像（坐标/海拔/气候带/半球）与缓存实况的年龄 |
| `acoustics` | 音频模组联动状态 + 按当前气候算出的混响建议值 |
| `weather2` | Weather2 联动状态、它当前的风暴列表、以及本模组具备的干预能力 |
| `llm` | LLM 开关状态、模型名与「每天一次」的预算剩余 |
| `offline` | 立刻演示一次离线推算，并打印推算依据（**不改动游戏天气**） |
| `diagnose` | 输出详细诊断信息到日志，排查「为什么拿不到天气」 |
| `sky` | 当前真实天色状态：云量、能见度、降水概率，以及它们是哪里来的 |

### `test` 自检

| 参数 | 作用 |
| --- | --- |
| `biome` | 强制用指定群系的气候做验证（没有玩家的服务器也能用） |
| `weather` | 灌入指定天气与温度，看它被怎么处理 |
| `matrix` | 按群系命名空间批量跑矩阵，一次看完整片群系的结果 |
| `all` | 全量自检：所有内置群系 × 所有天气类型 |
| `clear` | 清除强制状态，恢复按玩家所在群系自动判断 |

---

## 多语言 / Localization

所有**玩家可见文本**统一按语言键组织 —— 命令输出、配置界面（标签、悬浮说明、按钮）、
HUD 与聊天面板、悬浮提示，文案都由语言文件提供。

> 这是一条需要持续维护的约定：**新增任何玩家可见文案时，同时补 `zh_cn` 与 `en_us` 两个键**，
> 不要直接在代码里拼字符串 —— 漏了键虽然不会崩，但会按下面的规则把键名显示出来。

| 事项 | 说明 |
| --- | --- |
| 跟随什么 | **Minecraft 客户端的语言设置**。游戏内「选项 → 语言」切换即可，**不需要重启** |
| 已有的语言 | 简体中文 `zh_cn` 与英文 `en_us` |
| 语言文件 | `versions/forge-common/src/main/resources/assets/nowweather/lang/zh_cn.json` 与 `en_us.json` |

### 实现方式（为什么核心能参与翻译）

`core` 是**零 Minecraft 依赖**的纯 Java 模块，它不能引用 `Component.translatable(...)`。
所以这里留了一道「翻译缝」：`core/src/main/java/com/nowweather/core/text/Text.java` 只负责产出
**语言键 + 参数**（`Text.tr("nowweather.weather.storm_rain")`），Forge 层在启动时注入一个
用 MC 语言文件实现的 `Translator`，由它解析成当前语言。

好处是：核心的业务逻辑保持平台无关（换 MC 版本、甚至将来做 Fabric 平台层都不用重写），
语言却仍然跟着游戏走。

漏配的后果是**显式**的：默认实现是「原样返回键名」，**不是**回退到中文 ——
所以语言文件里漏了键时，界面上会直接出现 `nowweather.weather.heavy_rain` 这样的键名，一眼就能发现；
如果静默回退中文，英文用户只会以为「这模组没做英文」，很难排查。

### 日志不翻译

`LOGGER` 输出面向开发者，**恒为中文，不走翻译层**。两个原因：省事；
以及避免同一行日志随玩家语言变化而变得没法检索（排查问题时贴日志的人语言各不相同）。

### 如何新增一种语言

1. 在 `assets/nowweather/lang/` 下新建 `<语言代码>.json`（例如 `ja_jp.json`），
   键名与 `en_us.json` **完全一致**，值按同一套 `%s` 占位符填 —— 键名少一个都会退回显示键名；
2. 检查现有代码有没有漏抽的硬编码字符串——漏了的话，那种语言下就会露出原文。

> `tools/merge-lang.ps1` 是把各改造小组产出的语言片段（`tools/i18n/frag-*.json`，
> 格式为 `{ "键": { "zh": "...", "en": "..." } }`）合并、去重、排序后写回 `zh_cn.json` / `en_us.json`，
> `-Check` 只校验不写出。它只处理中英这一对，**新增第三种语言要手工维护**（或自行扩展该脚本）。

### 自测顺带检查语言文件完整性

`tools/test-core.ps1`（以及 `./gradlew :core:coreSelfTest`）会在跑用例之前，
**读取仓库里真实的 `zh_cn.json` 并把它装成翻译器**（见 `core/src/test/java/com/nowweather/core/test/TestLang.java`）。
这样做的好处是：那些断言「文案 === 某句中文」的用例，只要语言文件里漏了一个键就会失败 ——
失败信息形如 `期望 满月，实际 nowweather.moon_phase.0`，**一眼就能看出是漏键而不是逻辑错了**。

也就是说，**跑一次自测 = 顺带做了一次语言文件完整性检查**，不需要另写一套工具。
（这也是为什么自测必须读真实语言文件、而不是内置一张中文对照表：内置表会和真正发给玩家的文件脱钩，
语言文件漏键时自测照样全绿，玩家却在界面上看到键名。）

---

## 用数据包覆盖气候数据

内置数据位于 `data/nowweather/climate/*.json`。你可以在自己的数据包里放同名文件覆盖它，
或者新建文件追加自己的群系 —— 模组会按 id 排序加载全部 `*.json`
（**`_` 开头的文件是人工维护的数据源，不会被加载**）。

当前仓库里放了 6 个 json，其中 5 个会被加载：

| 文件 | 内容 |
| --- | --- |
| `vanilla_1.18.1-1.18.2.json` | 原版 1.18 基线群系 |
| `vanilla_later_1.19-26.x.json` | 1.19 之后新增的原版群系 |
| `biomesoplenty_16.0.0.134.json` | 超多生物群系 |
| `terralith_2.2.6.json` | Terralith |
| `tectonic_2.3.5a.json` | Tectonic |
| `_vanilla_source_1.18.2.json` | 人工维护的原版数据源明细（`_` 前缀，**不加载**） |

> **关于 Tectonic**：这份数据对应的是 Tectonic **v1.x / legacy 数据包**。
> Tectonic v2.x 默认**不注册任何 `tectonic:*` 群系**，所以装新版时这些条目不会被匹配到 ——
> 它们不会报错，只是暂时用不上；等 Tectonic 重新注册群系、或数据包自己带上这些 id 时就会重新生效。

```json
{
  "biomes": [
    {
      "id": "some_mod:my_biome",
      "climateClass": "TEMPERATE",
      "tempMinC": -8,
      "tempMaxC": 30,
      "precipitation": "RAIN",
      "humidity": 0.6,
      "windiness": 0.4,
      "thunderBias": 1.0,
      "possibleMcWeather": ["CLEAR", "RAIN", "THUNDER"]
    }
  ],
  "tags": [
    { "tag": "some_mod:is_cold_biome", "climateClass": "POLAR" }
  ],
  "categories": [
    { "category": "snowy", "climateClass": "POLAR" }
  ]
}
```

字段含义、解析优先级与全部内置条目见 **[docs/BIOME_CLIMATE.md](docs/BIOME_CLIMATE.md)** 与自动生成的
**[docs/BIOME_CLIMATE_TABLE.md](docs/BIOME_CLIMATE_TABLE.md)**。

---

## 给其它模组用的扩展接口

NowWeather 刻意把三组接口做成公开 API（`com.nowweather.core.api`）：

```java
// 1) 把你的天气数据喂给 NowWeather（入站）
NowWeatherApi.registerProvider(myWeatherProvider);   // 实现 WeatherProvider

// 2) 让别的模组跟着真实天气走（出站）
NowWeatherApi.registerBinding(myAtmosphereBinding);  // 实现 WeatherBinding

// 3) 按季节/维度改写某类群系的气候（覆盖）
NowWeatherApi.registerClimateResolver(mySeasonResolver);  // 实现 ClimateResolver
```

详细说明与示例见 **[docs/API.md](docs/API.md)**。

---

## 数据源

默认按优先级形成回退链，任何一个挂了都会自动落到下一个。
**数字小的先被询问**（升序优先），所以顺序是：彩云天气 → Open-Meteo → UApiPro。

| 优先级 | 数据源 | 覆盖 | 需要什么 | 说明 |
| --- | --- | --- | --- | --- |
| 80 | **彩云天气** | 🇨🇳 国内 | token：填 `api.caiyunToken`（**留空时回退用 `api.apiKey`**） | 最高优先级：雷达实测降水、1km 分辨率、自带云量与实时时区；两处都没填 token 时它不可用，回退链自动落到下一级 |
| 90 | **Open-Meteo** | 🌍 全球 | 城市名或坐标 | WMO 标准天气代码、无需密钥；数据每 15 分钟更新，默认 20 分钟刷新一次 |
| 100 | UApiPro | 🇨🇳 国内精确 | 城市名 / adcode / 什么都不填（按 IP） | 中文实况文案 + 风向风力；专用服务器按 IP 定位会落到机房所在地 |

配置示例（游戏内「模组 → NowWeather → 配置」，或 `config/nowweather-common.toml`）：

```toml
[location]
    # 填了就优先用坐标查询（Open-Meteo 全球覆盖，最准确）
    coordinates = "30.274,120.155"   # 纬度,经度
    city = ""                        # 或者填城市名，Open-Meteo 会自动地理编码（支持中文）
    adcode = "330106"                # 或者填行政区划代码（UApiPro 路径最精确）

[api]
    providerId = ""                  # 留空 = 按上面的优先级自动回退
    caiyunToken = ""                 # 彩云天气 token，最优先；留空则回退用 apiKey
    refreshIntervalSeconds = 1200    # 20 分钟
```

> **Open-Meteo 的免费额度**：非商业用途免费、无需注册，但请保留署名。
> 本模组默认 20 分钟刷新一次，一天约 72 次请求 —— 远低于免费额度上限。
> 使用 Open-Meteo 数据即表示你同意 [their terms](https://open-meteo.com/en/terms)。

## 数据来源与致谢

- 全球实时天气：[Open-Meteo](https://open-meteo.com/)（数据和 API 均由 Open-Meteo 提供）
- 国内实时天气：[UApiPro](https://uapis.cn/docs/api-reference/get-misc-weather)（免费天气 API）
- 国内雷达实测降水与云量：彩云天气（Caiyun Weather，需自备 token）
- 原版生物群系数值：Minecraft 1.18.2 数据包原文（`mcmeta`）与游戏源码逐字段核对
- 超多生物群系：[Glitchfiend/BiomesOPlenty](https://github.com/Glitchfiend/BiomesOPlenty) 源码
- 气候分类与温度范围的推导规则：见 `docs/BIOME_CLIMATE.md`（公开、可复算、可覆盖）

调研过程与原始数据留档在 `docs/research/` 下。

---

## 真实时间同步（游戏内一天 = 真实 24 小时）

开启后（默认开启）：**游戏内钟点与所在地的真实钟点一致**，太阳、月亮、生物生成的作息全部跟着真实时间走。
时区按「手动配置 → 天气接口返回 → 系统时区 → 按经度估算」四级解析，并写入本地缓存供离线使用。

```toml
[timesync]
    enabled = true
    timezoneOffsetMinutes = -2147483648   # 留空 = 自动解析（推荐）
    # intervalTicks 已废弃、不再生效：服务端现在每一刻都会写一次世界时间，
    # 不再按固定间隔校正。这一项保留只为兼容老配置文件，改了没有任何效果。
    # intervalTicks = 20
```

实测：实际 `dayTime` 与目标相差 **15 刻（约 0.75 秒）**。

> 注意：这个模式下「睡觉跳过夜晚」会被立刻校回真实时刻（本模式的定义就是与真实时间一致）。
> 想要原版作息请把 `enabled` 关掉。
>
> 用 `/nowweather info time` 可以随时查看时区来源、所在地真实时间与游戏内钟点。

## 离线也能用：本地缓存 + 轻量推算

模组**只在获取时区与天气时需要网络**，其余全部离线运行。三层数据来源按可用性依次降级：

```
① 在线实况（彩云 / Open-Meteo / UApiPro）
        ↓ 网络失败 / 数据过期
② 本地缓存（.minecraft/weather/nowweather/）   ← 重启后依然记得「你在哪个城市、什么时区、海拔多少」
        ↓ 缓存也过期（超过 24 小时）
③ 轻量离线推算（OfflineWeatherSynthesizer）
```

**离线推算用的是什么**（刻意保持轻量、可解释，不依赖任何模型）：

| 因素 | 处理 |
| --- | --- |
| 纬度 | 10° 一档的纬向年平均气温表 + 线性插值（赤道 ≈26.5°C，60° ≈0°C，极地 ≈−25°C） |
| 季节 | `T = 年均 + A(lat)·cos(2π(doy−峰值)/365.25)`，振幅随纬度增大；**南半球相位平移 182.6 天** |
| 海拔 | 标准大气递减率 **6.5°C/km** |
| 季节 | 优先用「纬度 + 距海岸距离」模型：`A = sin(|lat|)·(9.1 + 18.1·d/(d+400))`，`d` 由内置的 8.1 kB 陆海掩码（Natural Earth，公有领域）算出。46 站标定实测比「只按纬度」RMSE 从 5.06K 降到 2.51K；掩码不可用时退回纬度公式 |
| 昼夜 | **最低温落在日出、最高温落在太阳正午后 2.5 小时**（两段半余弦）。日出与昼长由 `SolarDaylight` 按太阳赤纬 + 时差 + 经度实时算出，不是写死的钟点。日较差 = `18.10 − 0.1398·RH% + 1.5`（R²=0.797），钳制在 4~20°C |
| 降水 | 气候带概率：赤道辐合带多雨、副热带（20°~35°）干旱、西风带（40°~60°）较湿、极地干燥 |
| 风向 | 行星风带：信风带偏东、西风带偏西、极地东风带偏东 |
| 持续性 | 缓存实况作为锚点融合，权重 `w(h) = 1/(1+(h/2.10)^1.20)`（实测拟合，h 为距今天数：1 天→71%，2 天→50%，3 天→39%），超过 3 天不再融合 |
| ★ 融合的是「距平」 | `T = T_clim(现在) + w·(T_obs − T_clim(观测时刻))`。**不能照搬绝对值**：早上实测 10°C 到傍晚照搬，会引入最多 7K 误差 |
| 相态 | 雨/雪分界**不是 0°C**：50% 概率点约 **1.2°C**，过渡带 −2~+4°C（Dai 2008 等实测） |
| 天气类型 | 一阶马尔可夫先验 P(雨在前一天是雨后)=0.679、P(雨在前一天是晴后)=0.187，与当地气候概率取平均 |

**它会诚实地报告可信度**：只有地点画像时 ≈35%，有当天实况锚点时可到 75%。上限刻意压在 0.75
—— 纯离线温度的 1σ 误差约 ±3K，与「明天和今天一样」的持续性基线同级，是**合理的默认值，不是预报**，
HUD 与 `/nowweather status` 都会显示，离线数据也会标注「（推算）」。

缓存目录（人类可读的 JSON，可直接手改）：

```
.minecraft/weather/nowweather/profile.json       地点：城市 / 坐标 / 海拔 / 时区 / 半球 / 数据源
.minecraft/weather/nowweather/observation.json   最近一次成功获取的实况
.minecraft/weather/nowweather/forecast.json      最近一次生成的预报
```

服务端对应 `<服务器目录>/weather/nowweather/`。相关命令：

| 命令 | 说明 |
| --- | --- |
| `/nowweather info cache` | 查看缓存目录、地点画像（坐标/海拔/气候带/半球）与缓存实况年龄 |
| `/nowweather info offline` | 立刻演示一次离线推算，并打印推算依据（不改动游戏天气） |

> 关于「接入 WeatherNext 这类 AI 预报模型」：它是云端模型（GCS/Vertex 凭据 + GB 级网格），
> 与「轻量离线」的定位冲突。可行的形态是把它当作**在线数据源**，拉取逐小时预报后缓存、
> 离线期间插值 —— 接口已预留（`WeatherProvider#isForecastProvider()`），详见 `docs/ROADMAP.md`。

## 下界与末地：完全保持原版

`leaveNetherAndEndAlone`（默认**开启**）会让下界、末地、虚空群系**完全不写入天气** ——
不是「少动」，而是连 `setWeatherParameters` 都不调用，原版的天气计时器、`doWeatherCycle`、
`/weather clear` 状态一点都不受影响。

真机自检里，下界 / 末地 / 虚空群系**全部走「保持原版」分支**，写入次数 0。
想改这个行为可以在配置里关掉该选项。

## 与季节模组联动（Serene Seasons）

内置了 `SereneSeasonsClimateModifier`：检测到 Serene Seasons 时，按当前的季节与季节内进度
**平移并收窄**每个生物群系的「正常温度范围」，从而自动得到正确语义：

- 温带群系：**冬天自动允许下雪、夏天自动禁止下雪**（因为天气白名单是从温度范围推导的）；
- 沙漠：哪个季节都不下雪；雪原：夏天依然可能下雪。

它走的是新增的 `ClimateModifier` 扩展点（**后处理**：无论基础气候来自内置数据、数据包还是启发式都会被调用），
这是季节这类需求的正确位置 —— 用 `ClimateResolver` 做不到，因为内置数据已经把解析链截断了。

## LLM 助手（可选，本项目唯一会产生费用的功能）

默认**全部关闭**。开启后有两个用途：

| 用途 | 命令 | 成本控制 |
| --- | --- | --- |
| AI 辅助推算天气 | `/nowweather forecast ai` | **每天最多一次**（时间戳记在 `weather/nowweather/llm.json`，删掉即重置） |
| 自然语言改配置 | `/nowweather config ask 把刷新间隔改成 600 秒` | 每次一句话，`llm.maxTokens` 默认 **1200**（范围 64~8192） |

安全边界：模型**只能返回白名单内的配置键**（本地再卡一次，不依赖模型守规矩），
且每一项都要过与游戏内配置界面**完全相同**的范围校验 —— AI 没有绕过校验的能力。
默认关闭思考模式（`llm.thinking = false`），因为它会显著增加输出 token。

真机实测（DeepSeek）：
```
/nowweather forecast ai    → 本地=晴 25.1°C 湿度66% | AI=CLEAR 25.1 湿度66% 置信度60% | 理由：…
/nowweather config ask 把天气刷新间隔改成 600 秒  → 配置文件真的从 1200 变成 600
```

## 与其它模组联动

| 模组 | 方式 | 状态 |
| --- | --- | --- |
| **静谧四季 Serene Seasons** | 反射调它的 `SeasonHelper` API，按季节平移群系的正常温度范围 | ✅ 真机验证（实测秋季平移 −9.0°C） |
| **Weather2 / 风暴** | 反射读它的风暴列表与风场；并用真实天气**主动指挥**它生成沙尘暴/雪暴 | ✅ 桥接 + 干预真机验证（催生后确实读到新风暴，并拿到它的尺寸） |
| **自然音效 AmbientSounds** | 用**资源包注入** region JSON + `reloadAsync()`（**不改它源码**） | 🚧 见 `D:\系统\桌面\sup_mods\AmbientSounds_change.log` |
| **物理声效 Sound Physics** | 它的混响参数是硬编码常量、无配置入口，需 Mixin 注入 | 🚧 见 `D:\系统\桌面\sup_mods\SoundPhysicsRemastered_change.log` |

**不修改上游模组源码**是这里的默认原则：能反射就反射，能数据包就数据包，**实在不行就用我们自己的 jar 带一个 Mixin 运行时注入**（用户照常装官方包，上游源码与 jar 一行都不动）。

> **Sound Physics 就是这样处理的**：它的混响参数是 `getReverb0()` 里每次 `new` 的硬编码常量，
> 没有配置/JSON/provider 入口，外部**没有**合法途径按环境改混响。
> 所以我们的 jar 里带 `nowweather.mixins.json` + `ReverbParamsMixin`，
> 在 `getReverb0..3` 的返回处把湿度/气温/海拔/暴露度的修正叠上去（倍率硬性夹在 ±15% 内，
> 没有数据时全为 1.0 = 与它原本完全一致）。Mixin 配置里 `defaultRequire: 0`，
> 没装 Sound Physics 时只是不注入、不会崩。

`D:\系统\桌面\sup_mods\` 下放着这两个模组的源码与**需求单**
（`D:\系统\桌面\sup_mods\<模组名>_change.log`：要什么 API、改哪个方法、怎么重建）。
注意它在仓库**之外**，不随本仓库分发。

## 已知限制

- 现阶段只在 Forge 1.18.2 / 1.18.1 上构建通过；其它版本见路线图；
- 原版天气只有「晴 / 雨 / 雷暴」三档，温度、湿度、雾、沙尘这些细分天气目前只体现在
  HUD、预报与决策依据上，要真正落地成视觉效果需要联动扩展天气模组；
- 生物群系气候数据中，1.19 之后新增的原版群系与部分模组群系仍属「按原版数据 + 现实气候常识推断」，
  欢迎提 issue 更正 —— 所有条目都可以用数据包覆盖。

## 作者与许可

- 作者：**Cluda**
- Mod ID：`nowweather`
- 版本：**26b39f**
- 版本号规则：`<年份后两位>b<当年第几周><小写字母 = 本周第几次修改>`，其中 **b = build（构建）**，
  字母就是「本周第几次」：`a` = 第 1 次。
  例如 `26b39a` = 2026 年第 39 周的第 1 次构建，当前版本 `26b39f` = 2026 年第 39 周的第 6 次构建。
- 许可：**Apache License 2.0**（全文见仓库根目录 `LICENSE`）

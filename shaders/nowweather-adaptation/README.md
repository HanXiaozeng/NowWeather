# NowWeather 光影适配归档（iterationT 3.2.0）

## 这是什么

本目录归档 NowWeather 模组与 **iterationT 3.2.0 by Tahnass** 之间那套「两段式信号」适配：

| 文件 | 内容 |
| --- | --- |
| `NowWeather.glsl` | **本项目自行编写**的 GLSL 头文件。定义了 `NW_CLOUD_BAND` / `NW_RAIN_THRESHOLD` / `PC_CLOUD_COVERY_GAIN` / `NW_CLOUD_DIM` / `NW_CLOUD_SHADOW` 五个常量，以及 `nwCloudCover()` / `nwPrecipitation()` / `nwPlanarCoverage(base, gain)` 三个函数。 |
| `PATCH.md` | 精确到「哪个文件、哪个锚点、插入或替换了什么、为什么」的适配说明，含原理、契约、注意事项与已知问题。 |
| `README.md` | 本文件。 |

模组侧通过覆写 Minecraft 的 `rainStrength`（`level.setRainLevel`）向光影传递两段式信号：

```
0.00 – 0.60   云量 0 → 1
0.60 – 0.75   缓冲带
0.75 – 1.00   降水强度 0 → 1
```

## 给谁用

- 想在自己下载的 iterationT 3.2.0 上手动接上 NowWeather 的玩家 / 整合包作者。
- 想了解这套编码契约、或想把它移植到**其它 Photon 系光影包**的光影作者。

## 怎么用（三步）

1. 自行下载 **iterationT 3.2.0 by Tahnass**，解压出它的 `shaders/` 目录。
2. 把本目录的 `NowWeather.glsl` 复制到 `shaders/Lib/IndividualFounctions/NowWeather.glsl`。
   > 注意目录名 **`IndividualFounctions`** 是原包自己的拼写，少一个 `c`，请照抄，不要「改正」。
3. 按 `PATCH.md` 的第 3 节，逐个文件按「锚点 + 代码片段」改动那 7 个文件。

改完后建议按 `PATCH.md` 第 7 节的验证清单自查。

## 说明

- `PATCH.md` 里描述的光影包行为，全部来自对 iterationT 3.2.0 源码的实际阅读（行号均已核对）。
  文中出现的目录名 `photon` 只是作者本地工作副本的目录名，**和光影包的实际身份无关**。
- `PATCH.md` 第 6 节列出了已知且**尚未修复**的问题，其中两条是本次适配引入的行为变化
  （`Ripple.glsl` 的雨点涟漪泄漏、「透明性」说法被夸大），请务必阅读。
- 本目录的 `NowWeather.glsl` 是逐字节原样复制，因此**仍带有第 6.1 条提到的 `<b>` 标签**。

## 免责声明

本目录只包含本项目自行编写的 GLSL 片段与说明，**不包含也不分发任何上游光影包的源码**；
请自行下载光影包后按 `PATCH.md` 应用。

`NowWeather.glsl` 与本目录的文档均为本项目原创内容，适用仓库根目录 `LICENSE` 的条款。
iterationT 是 Tahnass 的作品，其版权归原作者所有，与本项目无关。

---

## 变更记录

### 云量分级可辨识性（26b39g）

真实云量是线性的（0.2 / 0.5 / 0.7），但**画面上的云量不是** —— 直接线性映射时
「少云 22%」和「中云 50%」在屏幕上几乎看不出差别，玩家没法判断天色到底有没有跟着云走。

因此新增 `nwCloudVisual()`（`pow(nwCloudCover(), 0.7)`），并把三个消费点都改用它：
`nwPlanarCoverage()`（平面云覆盖率）、`PrecomputedAtmosphere.glsl` 的 `GetSkyRadiance()`（天色压制）、
`CloudShadow.glsl`（云影）。同时把 `PC_CLOUD_COVERY_GAIN` 默认值从 0.30 提到 0.55。

拉开之后的效果：

| 真实云量 | nwCloudVisual | 平面云覆盖率 | 天色亮度 |
|---|---|---|---|
| 0% | 0.00 | 0.50 | 100% |
| 20% | 0.32 | 0.68 | 85% |
| 50% | 0.62 | 0.84 | 72% |
| 70% | 0.78 | 0.93 | 65% |
| 100% | 1.00 | 1.00 | 55% |

两个端点仍然是 `pow(0)=0`、`pow(1)=1`，所以**没装模组时的观感不受影响**。
### 太阳 / 月亮在天色异常时必须真的消失（26b39i）

起因是两个实机反馈：**「下雨了还有太阳」** 和 **「大太阳没了，但变成一个细小的点」**。
它们其实是三个不同的地方：

| 现象 | 根因 | 改动 |
|---|---|---|
| 下雨还有大太阳 | `deferred3.fsh` 画 `RenderSunDisc()` 时**没有任何云雨遮蔽** | 日面、月面、水面月影倒影都乘上 `nwSunVisibility()` |
| 大太阳没了却剩一个**细小的点** | `celestial` 取自 colortex0 的**原版天空贴图**，里面自带一个太阳/月亮精灵；只压光影自己的圆盘，原版那个小太阳就露出来了 | `celestial *= nwSunVisibility()` |
| **下雨后太阳亮度还是太亮、影子清清楚楚** | 真正的「阳光总闸门」是 `composite.fsh` 的 `sunlightMult`（影子 = 有直射阳光处 − 无直射阳光处，直射不灭则影子必清晰）；而包里的 `NW_CLOUD_SHADOW` 写在 `#ifdef CLOUD_SHADOW` 的 `#else` 分支里，**默认设置下根本不参与编译** | `sunlightMult *= nwSunVisibility()` —— 写在所有 `#ifdef` 之外，任何设置下都生效 |

`nwSunVisibility()` 的取值（云透过率 × 雨透过率）：

| 天气 | 日面 / 直射阳光 |
|---|---|
| 晴 / 少云 | 1.00 |
| 多云 | 0.97 |
| 阴 | 0.13 |
| 毛毛雨 | 0.07 |
| 中雨 / 暴雨 | 0.04 / 0.01 |

晴天端仍是 1.0，所以晴朗天气的观感不受影响。

### 雨 / 雪量可精调（26b39i）

新增 `nwRainAmount()`：以前雨幕强度直接乘 `wetness`（≈`rainStrength`，只有 0.75~1.00、**25% 变化**），
现在改用真实雨量（**0.15~0.95，完整一段**），小雨与暴雨终于看得出差别；
`composite5.fsh` 的 `Rain()` 两个分支、以及 `composite.fsh` 的 cloudShadow 都改用它。

模组侧对应两个配置项（`[shaders]` 节，范围 0.0 ~ 2.0，默认 1.0）：
`rainAmountScale` / `snowAmountScale`。
倍率只作用在降水区间内部，编码值恒 ≥ 0.75，**不会掉进缓冲区**，因此不会出现
「模组说在下雨、光影一滴都不画」。
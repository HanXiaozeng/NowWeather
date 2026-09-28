# NowWeather × iterationT 3.2.0 适配说明（PATCH）

本文件说明如何把 NowWeather 的两段式信号接到 **iterationT 3.2.0 by Tahnass** 上。

- 本目录**不包含、也不分发任何上游光影包的源码**（见 `README.md` 的免责声明）。
- 请自行下载 iterationT 3.2.0，然后按本文档自行改动。
- 文中的路径都以光影包 `shaders/` 为根。**目录名以你本地的实际拼写为准。**

> **关于「photon」**：本次适配是在一份本地工作副本上完成的，那份副本的目录名叫 `photon`。
> 那**只是本地目录名，与光影包的实际身份无关**。该包的真正身份是 iterationT：
> `lang/en_us.lang:3` = `option.iterationT_VERSION=iterationT by Tahnass`，
> `lang/en_us.lang:4` = `value.iterationT_VERSION.AT=3.2.0`。
> 下文中一切涉及包行为的描述，都来自对这份 iterationT 3.2.0 源码的实际阅读。

---

## 1. 原理：为什么联动只能走 `rainStrength`

模组能写的只有原版 uniform —— `rainStrength`、`worldTime`、`moonPhase` 等等。
**Iris / OptiFine 没有给第三方模组注入自定义 uniform 的 API。**

这一点可以直接在光影包里验证：`shaders.properties` 里的自定义 uniform 走的是
`uniform.<类型>.<名字> = <表达式>` 语法，而表达式的输入**只有光影自己的内建变量**
（`biome`、`eyeBrightness`、`viewWidth`、`is_sneaking` ……）。例如 iterationT 3.2.0 的
`shaders.properties:201-229`：

```
uniform.float.eyeSnowySmooth = smooth(9, if(biome_precipitation > 1.5, 1.0, 0.0), 4.0, 7.0)
uniform.vec2.pixelSize  = vec2(1.0 / viewWidth, 1.0 / viewHeight)
```

也就是说：**自定义 uniform 只能在光影包自己的 `shaders.properties` 里定义，模组无权往里塞值。**
模组既不能新增一个 `nowweatherCloudCover`，也不能改 `shaders.properties`。

于是联动只剩一条通道：

```
NowWeather（模组）  --写-->  level.setRainLevel(strength)
                              |
                     Iris / OptiFine 转成 rainStrength
                              |
iterationT（光影）  <--读----  rainStrength
```

模组侧写入点：`ShaderSkyBridge` 调用 `level.setRainLevel(strength)`
（`versions/forge-common/.../client/ShaderSkyBridge.java:155`）。

既然只有一个标量可用，却要传两个互相独立的量（云量、降水），
就必须把这个标量**按区间切分成两段** —— 这就是两段式编码的由来。

**不这么做的后果**：阴天为了让天色变暗必须抬高 `rainStrength`，而光影只要看到
`rainStrength > 0` 就开始画雨 —— 于是出现「外面阴天，光影里在下雨」。

---

## 2. 契约：两段式编码

| `rainStrength` 区间 | 含义 |
| --- | --- |
| `0.00 – 0.60` | **云量** 0 → 1（阴天 / 多云：云多，但不下雨） |
| `0.60 – 0.75` | **缓冲带**，两边都不落。保证「阴天」与「小雨」有可分辨的界线 |
| `0.75 – 1.00` | **降水强度** 0 → 1（真的在下雨 / 下雪） |

### 两边常量必须一致

| 侧 | 常量 | 值 | 位置 |
| --- | --- | --- | --- |
| 模组 | `SkyMood.CLOUD_BAND_MAX` | `0.60` | `core/src/main/java/com/nowweather/core/weather/SkyMood.java:152` |
| 模组 | `SkyMood.RAIN_BAND_MIN` | `0.75` | 同上，`:154` |
| 模组 | 用户可调配置键 `shaders.cloudRainScale` | 默认 `0.60`，范围 `0.0–0.70` | `versions/forge-common/.../config/ForgeConfig.java:430` |
| 光影 | `NW_CLOUD_BAND` | `0.60` | `Lib/IndividualFounctions/NowWeather.glsl:27` |
| 光影 | `NW_RAIN_THRESHOLD` | `0.75` | `Lib/IndividualFounctions/NowWeather.glsl:30` |

模组用 `shaderRainStrength(cloudCover01, type, cloudBand)`（`SkyMood.java:159`）生成该值：

- 非降水天气：`strength = clamp(云量, 0, 1) * cloudRainScale` → 落在 `0.00–0.60`。
- 降水天气：`strength = 0.75 + 降水强度 * 0.25` → 落在 `0.75–1.00`；雷暴强制 `>= 0.95`。

光影侧解码（`NowWeather.glsl`）：

```glsl
float nwCloudCover()     { return clamp(rainStrength / NW_CLOUD_BAND, 0.0, 1.0); }
float nwPrecipitation()  { return clamp((rainStrength - NW_RAIN_THRESHOLD) / (1.0 - NW_RAIN_THRESHOLD), 0.0, 1.0); }
```

> **注意**：降水时 `rainStrength >= 0.75`，于是 `0.75 / 0.60 = 1.25` 会被钳成 `1.0` ——
> 「下雨必然满天云」这一点是编码自带的效果，不需要额外处理。

> **改动两侧必须同步**：模组侧若把 `cloudRainScale` 改成非 `0.60`，模组会打印一条警告
> （`ConfigBridge.warnIfCloudRainScaleDrifts`），但**光影侧的 `NW_CLOUD_BAND` 是写死的宏**，
> 不会自动跟随。改了配置就必须同步改 `NowWeather.glsl`。

---

## 3. 改动清单

共 **7 个文件被修改**，另 **新增 1 个文件**。以下逐文件说明。行号是**改动后**的行号。

### 3.0 新增文件：`Lib/IndividualFounctions/NowWeather.glsl`

把本目录的 `NowWeather.glsl` 原样复制到光影包的
`shaders/Lib/IndividualFounctions/NowWeather.glsl`。

该文件是**本项目自行编写**的，自带 `#ifndef NOWWEATHER_INCLUDED` 包含保护，
并自己声明 `uniform float rainStrength;`（第 22 行），因此不依赖其它头文件的包含顺序。

它对外提供：`nwCloudCover()`、`nwPrecipitation()`、`nwPlanarCoverage(base, gain)`
和 5 个宏 `NW_CLOUD_BAND` / `NW_RAIN_THRESHOLD` / `PC_CLOUD_COVERY_GAIN` / `NW_CLOUD_DIM` / `NW_CLOUD_SHADOW`。

> `nwPlanarCoverage(base, gain)` 刻意把 `base`、`gain` 做成**参数**而不是直接读 `PC_COVERAGE`：
> `NowWeather.glsl` 会比 `Settings.glsl` 更早被包含，直接引用宏会有「宏还没定义」的问题。

---

### 3.1 `composite.fsh`

**改动 1 — 插入 include。**
锚点：在 `#include "/Lib/UniformDeclare.glsl"` 之后（该行是第 7 行，执行完 `Settings.glsl` 与全部 uniform 声明）。

```glsl
#include "/Lib/UniformDeclare.glsl"
#include "/Lib/IndividualFounctions/NowWeather.glsl"   // ← 新增
#include "/Lib/Utilities.glsl"
```

**改动 2 — 体积云覆盖率改用真实云量（第 128 行）。**
锚点：`#ifdef VOLUMETRIC_CLOUDS` 块内、`skylight += skySunLight * ...` 之前。

```glsl
// 改前
float coverage = mix(CLOUD_CLEAR_COVERY, CLOUD_RAIN_COVERY, wetness);
// 改后
float coverage = mix(CLOUD_CLEAR_COVERY, CLOUD_RAIN_COVERY, nwCloudCover());	// NowWeather: 云量用真实值，不用有惯性的 wetness
```

**为什么**：`wetness` 有 200 刻惯性（见同文件第 49 行 `const float wetnessHalflife = 200.0;`），
它是为「地面慢慢变湿」设计的，拿来做云量会让云的反应慢好几分钟；而且它分不出「阴天」和「下雨」。
云量必须用瞬时的 `nwCloudCover()`。

**改动 3 — 云影（第 187 行）。**
锚点：`#ifdef VOLUMETRIC_CLOUDS` → `#else`（即 **未**开启 `CLOUD_SHADOW` 时）分支内。

```glsl
// 改前
float cloudShadow = 1.0 - wetness * RAIN_SHADOW;
// 改后
float cloudShadow = 1.0 - mix(nwCloudCover() * NW_CLOUD_SHADOW, RAIN_SHADOW, nwPrecipitation());	// NowWeather: 云量遮阳光；真下雨时再压到 RAIN_SHADOW
```

**为什么**：阴天要有云影（`nwCloudCover()` 起作用），真下雨时保持原包强烈的 `RAIN_SHADOW`，
用 `nwPrecipitation()` 在两者之间过渡。这一项直接影响阳光「刺不刺眼」。

---

### 3.2 `composite5.fsh`

**改动 1 — 插入 include。** 同 3.1，锚点为第 7 行 `#include "/Lib/UniformDeclare.glsl"` 之后。

**改动 2 — 雨幕只在真降水时绘制（第 282 行）。**
锚点：`#ifdef VFOG` 块结束之后、`VanillaFog(color, waterDist);` 之前。

```glsl
// 改前
if(isEyeInWater == 0.0 && wetness > 0.0) Rain(color, gbuffer.rainAlpha);
// 改后
if(isEyeInWater == 0.0 && nwPrecipitation() > 0.0) Rain(color, gbuffer.rainAlpha);	// NowWeather: 阴天不画雨
```

**为什么**：这是「阴天不下雨」最直接的落点。原来的门是 `wetness > 0.0`，
而阴天也要抬 `rainStrength`，`wetness` 会跟着上去，于是阴天照样画雨幕。
换成 `nwPrecipitation()` 后，`rainStrength <= 0.75`（纯云）时恒为 0，雨幕不出现。

---

### 3.3 `Lib/IndividualFounctions/NUBIS.glsl`

**改动 1 — 插入 include（新的第 1 行，原第 1 行起整体下移 1 行）。**

```glsl
#include "/Lib/IndividualFounctions/NowWeather.glsl"
```

**改动 2 — 体积云覆盖率（现第 32 行）。**
锚点：函数 `SampleDensity()` 内，`//Coverage` 注释之后、`coverage = remapSaturate(...)` 之前。

```glsl
// 改前
float coverage = mix(CLOUD_CLEAR_COVERY, CLOUD_RAIN_COVERY, wetness);
// 改后
float coverage = mix(CLOUD_CLEAR_COVERY, CLOUD_RAIN_COVERY, nwCloudCover());	// NowWeather: 云量
```

**为什么**：与 3.1 改动 2 同理。

> ⚠️ 注意：**只改这个文件，在默认设置下眼睛看不到任何变化**。原因见第 4 节。

---

### 3.4 `Lib/IndividualFounctions/PlanarClouds.glsl`

**改动 1 — 插入 include（新的第 1 行）。**

**改动 2 — 平面云覆盖率（两处：现第 69 行、第 101 行）。**
锚点：分别在 `GetPlanarCloudsDensity()` 与 `GetPlanarCloudsLightingDensity()` 内，
`density /= weights;` 之后、`density *= exp(-distortion);` 之前。

```glsl
// 改前
density = saturate(density + PC_COVERAGE - 1.0);
// 改后
density = saturate(density + nwPlanarCoverage(PC_COVERAGE, PC_CLOUD_COVERY_GAIN) - 1.0);	// NowWeather: 云量随真实云量变化
```

**为什么**：`PC_COVERAGE` 是个**静态**设置（`Lib/Settings.glsl:265`，默认 `0.5`），
原本**和天气毫无关系**。这就是「地面看到的云量」的来源（详见第 4 节）。
用「增量」而非替换（`base + nwCloudCover() * gain`）是为了让**没装模组的人行为完全不变**：
`rainStrength = 0` → 增量 0 → 与原包一模一样。

**两处都要改。** 漏掉第 101 行会导致平面云的**受光密度**与**遮蔽密度**不一致，云会看起来发灰、光照错位。

---

### 3.5 `Lib/IndividualFounctions/CloudShadow.glsl`

**改动 1 — 插入 include（新的第 1 行）。**

**改动 2 — 云影初值（现第 37 行）。**
锚点：`CloudShadowFromTex()` 内，`coord = coord * (1.0 / CLOUD_SHADOW_RANGE) + 0.5;` 之后、
`if (saturate(coord) == coord){` 之前。

```glsl
// 改前
float cloudShadow = 1.0 - wetness;
// 改后
float cloudShadow = 1.0 - nwCloudCover();	// NowWeather: 云影跟着云量
```

**为什么**：这是地面云影的「兜底」值 —— 当采样点落在云影贴图覆盖范围之外（下面的
`if (saturate(coord) == coord)` 不成立）或淡出之后，用的就是它。之前它只跟 `wetness` 走，
阴天根本没有云影。改后阴天也会有云影。

---

### 3.6 `Lib/BasicFounctions/PrecomputedAtmosphere.glsl`

**改动 1 — 插入 include（新的第 1 行，在 `#define TRANSMITTANCE_TEXTURE_WIDTH` 之前）。**

**改动 2 — 天空辐射（现第 425 行）。**
锚点：`GetSkyRadiance()` 的 `return` 语句（`rayleigh = mix(rayleigh, ...)` 之后一行）。

```glsl
// 改前
return (rayleigh + mie + groundDiffuse) * (1.0 - wetness * 0.4) * LMS;
// 改后
return (rayleigh + mie + groundDiffuse) * (1.0 - nwCloudCover() * NW_CLOUD_DIM) * LMS;	// NowWeather: 天色跟着云量
```

**为什么**：这是**整个天空**的辐射总量。云越多，大气整体越暗 —— 让「多云 / 阴天」压暗天色，
这是阴天观感（不至于太刺眼）的关键一项。

> ⚠️ **不要改错行**。同一个文件末尾的 `GetSkyRadianceToPoint()` 里还有一句形似的
> `return (rayleigh + mie) * (1.0 - wetness * 0.4) * LMS;` —— 它**没有** `groundDiffuse`，
> 本次适配**没有动它**。请按「含 `groundDiffuse`」来辨认上面那一行。

---

## 4. 为什么「地面看到的云量」必须改 `PlanarClouds`

这一节是本次适配里最容易踩的坑，值得单独说明。

> 本节的行号同样以**完成第 3 节改动之后**的文件为准。对**未被修改**的文件
> （`deferred3.fsh`、`Lib/Settings.glsl`），行号即原包行号。

**结论：在地面上抬头看天，你看到的那层云是 `PlanarClouds`（平面云），不是体积云。
只改 `NUBIS.glsl` 是看不到任何效果的。**

### 4.1 平面云在地面必然生效

调用点 `deferred3.fsh:169-172`：

```glsl
#ifdef PLANAR_CLOUDS
    if (cameraPosition.y < PC_ALTITUDE && !ray_r_mu_intersects_ground)
        PlanarClouds(finalComposite, worldDir, camera, noise_1.y, cloudTransmittance);
#endif
```

- `PLANAR_CLOUDS` 默认开启（`Lib/Settings.glsl:264`）。
- `PC_ALTITUDE` 默认 `6000.0`（`Lib/Settings.glsl:267`）。
- 站在地面上（`cameraPosition.y` 约 64）抬头看天：`64 < 6000` 成立，且视线不与地面相交，
  `!ray_r_mu_intersects_ground` 成立。

→ **条件恒真，地面看到的天空云层由它绘制。**

它的覆盖率**完全来自静态设置 `PC_COVERAGE`**（`Lib/Settings.glsl:265`，默认 `0.5`），
**原本与天气毫无关系** —— 所以这是必须要改的一处。

### 4.2 体积云在默认设置下其实「什么都没画」

调用点 `deferred3.fsh:164-167`：

```glsl
#ifdef VOLUMETRIC_CLOUDS
    if ((cameraPosition.y > cloudAltitude.x || !ray_r_mu_intersects_ground) && (cameraPosition.y < cloudAltitude.y || worldDir.y < 0.0))
        NubisCumulus(finalComposite, worldDir, cloudAltitude, windDirection, camera, noise_1, cloudTransmittance);
#endif
```

**注意**：这个 `if` 在地面上其实**也会进去** —— 因为 `!ray_r_mu_intersects_ground` 为真。
所以「体积云只在 `cameraPosition.y > 500` 时才走这条分支」这个说法**不符合代码实际**。
真正让它在地面看不见的是**云的厚度为 0**：

1. `deferred3.fsh:135` 构造云高度：
   ```glsl
   vec2 cloudAltitude = vec2(mix(CLOUD_CLEAR_ALTITUDE, CLOUD_RAIN_ALTITUDE, wetness));
   ```
   这是一个由**单个 float** 构造的 `vec2` —— 所以 **`cloudAltitude.x` 与 `cloudAltitude.y` 恒等**。

2. 默认两端相等：`CLOUD_CLEAR_ALTITUDE 500.0`（`Lib/Settings.glsl:273`）、
   `CLOUD_RAIN_ALTITUDE 500.0`（`:281`）→ 厚度 `cloudAltitude.y - cloudAltitude.x == 0`。

3. `Lib/IndividualFounctions/NUBIS.glsl:145`：
   ```glsl
   cloudDensityMul *= (cloudAltitude.y - cloudAltitude.x) * marchingStepSize;   // ← 乘了 0
   ```

4. 于是 `NUBIS.glsl:164`：`absorption = exp2(-cloudDensity.y * 0.0) = 1.0`，
   `cloudTransmittance` 恒为 `1.0`，`:196` 的 `if(cloudTransmittance < 0.9999)` **永不成立** ——
   云的颜色**一笔都没有写进 `color`**。

→ **在默认设置下，`NubisCumulus` 对画面零贡献。** 只改 `NUBIS.glsl` 的 coverage 是空转。

> 反过来：如果你把 `CLOUD_CLEAR_ALTITUDE` 与 `CLOUD_RAIN_ALTITUDE` 调成**不同的值**，
> 体积云就真的会出现（厚度 > 0），那时 3.3 的改动才会生效。两处都改才是稳的。

### 4.3 一句话总结

| | 默认设置下，地面看天空时 |
| --- | --- |
| `PlanarClouds`（平面云） | **实际生效** —— 必须改 |
| `NubisCumulus`（体积云） | 分支会进，但因厚度为 0 而**无可见产出** —— 仍然要改（配置一变就生效） |

---

## 5. 两条注意事项

### ① `#version` 必须仍是每个程序文件的第一行

**绝对不要在 `#version` 之前插入任何东西**（包括 `#include`、注释、空行）。
`#version` 一旦不在第一行，该程序会直接编译失败。

关于本包的实际写法 —— 有一点需要澄清，**不要照搬「一律 `#version 330 compatibility`」这个印象**：

iterationT 3.2.0 共 246 个程序文件（`.fsh` 123、`.vsh` 123、`.gsh` 4），第一行分两类：

| 扩展名 | `#version 330` | `#version 330 compatibility` |
| --- | --- | --- |
| `.fsh` | **58**（其中 23 个在 `shaders/` 根目录） | 65 |
| `.vsh` | 0 | 123 |
| `.gsh` | 0 | 4 |

也就是说：

- **`.vsh` / `.gsh` 一律是 `#version 330 compatibility`。**
- **`.fsh` 两种都有。** 具体到你这次要改的两个文件：
  `composite.fsh` 与 `composite5.fsh` 的第一行都是 **`#version 330`（没有 `compatibility`）**。
  根目录下同样用裸 `#version 330` 的还有 `composite1–15.fsh`、`deferred.fsh`、`deferred1–6.fsh`。

**规则只有一条：不要动那一行，也不要往它前面塞东西。** 各文件的 `#version` 保持原样即可。

### ② `#include` 的位置与路径大小写

**(a) 位置**：必须放在**首次使用** `nwCloudCover()` / `nwPrecipitation()` / `nwPlanarCoverage()` **之前**。
本次适配采用的放置点是：

- `composite.fsh`、`composite5.fsh`：紧接 `#include "/Lib/UniformDeclare.glsl"` **之后**（第 8 行）。
- `NUBIS.glsl`、`PlanarClouds.glsl`、`CloudShadow.glsl`、`PrecomputedAtmosphere.glsl`：文件**第 1 行**。
  这 4 个文件本身都是在各程序的 `UniformDeclare.glsl` **之后**被包含的，
  所以在整个翻译单元里 `UniformDeclare` 仍然排在前面。

> 补充说明（已核实）：`NowWeather.glsl` 是**自足**的 —— 它只用到 `rainStrength`，
> 自己声明了这个 uniform（第 22 行），不引用 `Settings.glsl` 里的任何宏。
> 因此它放在 `UniformDeclare.glsl` 之前或之后**都不会因为宏未定义而失败**；
> 但按上面的位置放是最稳妥、也与本次适配一致的做法。

**(b) 路径大小写必须与真实文件名完全一致。** Iris/OptiFine 在部分环境下对 `#include`
路径是**大小写敏感**的，写错只会得到「找不到文件」，不会给出有用的提示。正确的引用是：

```glsl
#include "/Lib/IndividualFounctions/NowWeather.glsl"
```

> ⚠️ **注意拼写**：目录名是原包里的 **`IndividualFounctions`** —— 少了一个 `c`
> （正确英文应为 `Functions`）。这是上游包自己的拼写。
> **我们的引用必须照抄这个拼写，不要「顺手改正」成 `Functions`**，否则会找不到文件。
> 同理，另一个目录是 `BasicFounctions`，也是少一个 `c`。
> 文件名 `NowWeather.glsl` 的 `N`、`W` 也要大写。

---

## 6. 已知问题

以下问题在本次适配中是**已知且尚未修复**的。前两条为核查确认，随后两条为附加核查结论。

### 6.1 `NowWeather.glsl` 注释里残留 HTML 标签

`NowWeather.glsl` **第 55 行**：

```glsl
// 用增量则默认行为<b>与原包完全一致</b> ——
```

`<b>` / `</b>` 是 JavaDoc/HTML 的加粗标签（模组侧 `SkyMood.java` 的注释里也是这个写法），
混进 GLSL 注释里属于笔误。虽然 `//` 之后的整行都是注释、**不影响编译**，但应当删掉：

```glsl
// 用增量则默认行为与原包完全一致 ——
```

> 本目录归档的 `NowWeather.glsl` 是**逐字节原样复制**的，因此**仍然带有这两个标签**，
> 请按上面一行自行删除。
> （已做字节级确认：该位置的字节为 `3C 62 3E`（`<b>`）与 `3C 2F 62 3E`（`</b>`）。）

### 6.2 「对没装模组的人是透明的」这句话被夸大了

`NowWeather.glsl` 第 17-19 行的原话是：

> 没装 NowWeather 时原版 rainLevel 只可能是 0 或 1，
> 落到 `nwCloudCover()` 会被钳成 0 或 1，也就是回到光影原本的表现，
> 所以这个文件对没装模组的人是透明的。

**这个说法只在晴天成立。** 实际情况是：

- **晴天（`rainStrength = 0`）**：`nwCloudCover() = 0`、`nwPrecipitation() = 0`，
  平面云增量 0、云影 0、天色压制 0 → **与原包完全一致**。这一半是对的。
- **原版下雨（`rainStrength = 1`）**：改动的三处原本走的是有惯性的 `wetness`，现在改成了**瞬时**的 `nwCloudCover()`：
  - `composite.fsh:128`（体积云覆盖率的 `skylight` 项）
  - `NUBIS.glsl:32`（体积云 coverage）
  - `CloudShadow.glsl:37`（云影初值）

  `wetness` 有 200 刻的时间常数（`composite.fsh:49` `const float wetnessHalflife = 200.0;`），
  会缓慢爬升；`nwCloudCover()` 则是**当帧即满**。
  → **雨的起止瞬间，云量与云影会跳变**（比原包更快拉满、停止时更快消失）。

**正确的措辞**：

> 晴天与原包完全一致；**原版下雨时，云 / 云影会比以前更快拉满**（少了 `wetness` 的缓动）。

要真正恢复缓动，需要在「未装模组」时回退到 `wetness`，例如按
`#ifdef` 或一个「模组是否在线」的判据在 `nwCloudCover()` 与 `wetness` 之间选择 ——
但光影侧无法可靠判断模组是否在线（这正是第 1 节所说「没有注入通道」的另一面），
因此本次适配**接受了这个行为变化**。

### 6.3 两段式信号泄漏：`Ripple.glsl` 仍在使用裸 `rainStrength`

`Lib/IndividualFounctions/Ripple.glsl` **未被修改**（与上游逐字节一致）。第 33-39 行：

```glsl
vec2 GetRainNormal(vec3 pos, float strength, inout float wet){
	pos.xyz *= 0.5;
	#ifdef DISABLE_LOCAL_PRECIPITATION
		strength *= rainStrength;                                       // ← 第 36 行，裸 rainStrength
	#else
		strength *= rainStrength * (1.0 - eyeSnowySmooth) * (1.0 - eyeNoPrecipitationSmooth);  // ← 第 38 行
	#endif
	...
```

**后果**：这里用的是**裸 `rainStrength`**，而它现在承载的是**两段式信号**。
于是在「**只有云、并还没下雨**」的时候（`rainStrength` 落在 `0.00–0.60`），
`strength` 仍会被乘上一个正数 —— 地面和水面照样出现**雨点涟漪**。

调用侧完全没有天气门槛：`Terrain_FS.glsl:305-306`、`Water_FS.glsl:108-109`、
`Entities_FS.glsl:323-324 / 331-332` 的门都只是几何条件
（`splashStrength = saturate(NdotU * 2.0 - 1.0) * lightMask * (...) > 0.0`），
不涉及降水。

**建议改法**：把裸 `rainStrength` 换成 `nwPrecipitation()`。

1. 在 `Ripple.glsl` **第 1 行**加 include：

   ```glsl
   #include "/Lib/IndividualFounctions/NowWeather.glsl"
   ```

2. 第 36 行改为：

   ```glsl
   strength *= nwPrecipitation();
   ```

3. 第 38 行改为：

   ```glsl
   strength *= nwPrecipitation() * (1.0 - eyeSnowySmooth) * (1.0 - eyeNoPrecipitationSmooth);
   ```

这样阴天（`rainStrength <= 0.75`）时 `nwPrecipitation() == 0`，涟漪彻底消失；
真下雨时与原来的 `rainStrength` 行为等价（降水区间内 `nwPrecipitation()` 从 0 线性到 1）。

> **一处提醒**：`Ripple.glsl` 由 `Lib/Programs/Gbuffers/Terrain_FS.glsl:54`、
> `Water_FS.glsl:54`、`Entities_FS.glsl:107` 三个程序包含，而这三个文件**自己已经声明过**
> `uniform float rainStrength;`（`Terrain_FS.glsl:17`、`Water_FS.glsl:14`、`Entities_FS.glsl:14`）；
> `NowWeather.glsl` 第 22 行会再声明一次。GLSL 允许同类型 uniform 的重复声明，
> 但如果你的驱动 / 编译器报「重复定义」，可把 `NowWeather.glsl:22` 包一层：

> ```glsl
> #ifndef NW_RAIN_STRENGTH_UNIFORM
> #define NW_RAIN_STRENGTH_UNIFORM
> uniform float rainStrength;
> #endif
> ```

### 6.4 附加核查：`Lib/Settings.glsl` 被整文件换成了 CRLF 行尾（内容一字未变）

`Lib/Settings.glsl` 在本次工作中被改动过（`Lib/Settings.glsl` 与上游逐字节不同），
但**逐行比对后内容没有任何差异** —— 它只是从 **LF** 被整体转成了 **CRLF**：

| 文件 | 上游行尾 | 改动后行尾 |
| --- | --- | --- |
| `Lib/Settings.glsl` | 497 × LF | **497 × CRLF，0 × LF** |

这是一个**无意的改动**（多半是编辑器「保存时转换行尾」引起的），
它会让 `Settings.glsl` 整个文件出现在 diff 里，污染版本历史。**建议还原为 LF。**

> 顺带一提，新增 include 的 4 个文件也存在**行尾混用**：
> `NUBIS.glsl`、`PlanarClouds.glsl`、`CloudShadow.glsl`、`PrecomputedAtmosphere.glsl`
> 的第 1 行（新加的 `#include`）以 CRLF 结尾，而文件其余部分仍是 LF。
> `composite.fsh` / `composite5.fsh` 的新增行则是 LF，与文件一致。
> GLSL 对此不敏感，但建议统一成上游的 LF。

### 6.5 附加核查：5 个新增宏没有登记进 `shaders.properties`

`NowWeather.glsl` 里的 5 个宏写了 OptiFine 的滑条语法（`// [取值列表]`）：

```glsl
#define NW_CLOUD_BAND 0.60 // [0.40 0.45 0.50 0.55 0.60 0.65 0.70]
#define NW_RAIN_THRESHOLD 0.75 // [0.70 0.72 0.74 0.75 0.76 0.78 0.80]
#define PC_CLOUD_COVERY_GAIN 0.30 // [0.0 0.10 0.15 0.20 0.25 0.30 0.35 0.40 0.50]
#define NW_CLOUD_DIM 0.45 // [0.20 0.30 0.40 0.45 0.50 0.60 0.70]
#define NW_CLOUD_SHADOW 1.0 // [0.4 0.6 0.8 0.9 1.0 1.1 1.2]
```

但 `shaders.properties` 里**没有任何 `screen.*` 分组登记它们**
（第 445 行只登记了 `PC_COVERAGE PC_NOISE_SCALE PC_ALTITUDE`；第 575 行同样是这三个）。
`NW_CLOUD_BAND` / `NW_RAIN_THRESHOLD` 出现在**任何**分组里都会有不妥（它们是契约值，
改了就必须同步改模组配置），但 `PC_CLOUD_COVERY_GAIN`、`NW_CLOUD_DIM`、`NW_CLOUD_SHADOW`
是**给人调的观感参数**，建议把它们加进 `screen.PC` 之类的分组，否则可能不会出现在光影设置 GUI 里。

---

## 7. 验证清单

改完后按顺序自查：

1. **`#version` 还在第一行** —— 逐个检查你改过的 `.fsh`（尤其 `composite.fsh`、`composite5.fsh`，
   它们是 `#version 330`）。把 `#include` 插到了 `#version` 之前是最常见的翻车点。
2. **include 路径大小写** —— 必须是 `/Lib/IndividualFounctions/NowWeather.glsl`（注意少一个 `c`）。
3. **两处 `PlanarClouds`** —— `density = saturate(...)` 那两处都改了（第 69、101 行）。
4. **`PrecomputedAtmosphere.glsl` 只改了含 `groundDiffuse` 的那一行**，末尾 `GetSkyRadianceToPoint()`
   里的形似语句保持原样。
5. **没装模组时**：晴天观感应与改前**完全一致**；雨天云/云影会比原包更快拉满（见 6.2）。
6. **装了模组后**，在游戏里让模组把 `rainStrength` 分别置于下列值，观察效果：

   | `rainStrength` | 期望现象 |
   | --- | --- |
   | `0.00` | 晴：无云影、天色正常、无雨、无水波涟漪 |
   | `0.30` | 多云：平面云变多、天色压暗、有云影，但**没有雨幕**、**没有雨点涟漪** |
   | `0.60` | 阴天（云量满）：最暗的云影与天色，仍然**不下雨** |
   | `0.70` | 缓冲带：与 `0.60` 观感基本相同（不落区间） |
   | `0.90` | 中雨：云满 + 雨幕开始出现 + 地面涟漪出现 |
   | `1.00` | 大雨：`RAIN_SHADOW` 全量、雨幕最浓 |

7. **若第 6 步中「多云但不下雨」时仍看到雨幕或涟漪** —— 直接跳到 6.3 修 `Ripple.glsl`；
   雨幕那一项对应 3.2（`composite5.fsh`）。

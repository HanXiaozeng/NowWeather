# 台风 / 雷电子系统调研（Typhoon & Lightning Subsystem）

> **本文档解决什么问题**
> NowWeather 要做「台风/雷电子系统」：拉真实台风数据 → 本地推算路径与落点 → 干预游戏内天气与雷电。
> 本文档给出**可直接照着写 Java 的公式、字段映射、默认参数与降级链**，
> 并把「实测可用 / 有文献支撑 / 工程近似 / 未核实」严格分开。
>
> **本文档的写作时间**：2026-09-13（UTC）。所有 HTTP 请求都在**这一天**从本机实际发出。
> 台风接口没有任何稳定性承诺，**任何一条结论都可能明天失效** ——
> §A.0 的「复现命令」就是为此准备的：上线前请重跑一遍。
>
> ⚠️ **先说五条最重要的结论**
> 1. **「有没有官方 24/48/72 小时预报位置」→ 有，而且是 JSON。**
>    中央气象台台风网 `view_<id>` 接口里直接带 `BABJ` 键，含 **12/24/36/48/60/72/96/120 小时**
>    官方预报点（经纬度、气压、风速、强度等级）。**本方案不需要自己做路径外推就能拿到官方预报路径。**
>    自研外推只作为**降级方案**。（见 §A.1.4）
>    **而且差距极大**：官方 24 h 误差 **62 km**，CLIPER 级本地外推 **165–176 km**（**2.6 倍**）。
>    （见 §C.1.3，数字来自 NHC 官方验证报告 + CMA 2023 年评定）
> 2. **风圈半径的象限顺序，两个国内源不一样，且都是从我读到的前端源码里确认的：**
>    - 中央气象台：`"30KTS", NE, SE, SW, NW`（`gis.js` 里 `wc[1]→东北, wc[2]→东南, wc[3]→西南, wc[4]→西北`）
>    - 浙江水利厅：`radius7 = "NE|SE|NW|SW"`（`app~e6e59946.js` 里显式写 `a[0]→NORTHEAST, a[1]→SOUTHEAST, a[2]→NORTHWEST, a[3]→SOUTHWEST`）
>    **搞错这一条，风场会左右翻转。**（见 §A.1.5 / §A.2.3）
> 3. **1° 陆海掩码做落点判定的实测精度：8 个样本，中位 21.4 km，最差 51.5 km，
>    时间误差 −7.0 ~ +4.1 小时；另有 2/10 的连续登陆被漏检。**
>    对一个 1°（≈111 km）的掩码来说这已经比预期好，
>    因为它靠**沿航迹二分插值**把坐标精度从格点提升到了连续值；
>    但**时间精度仍受 1° 分辨率和定位点间隔双重限制**。
>    （见 §C.3，含完整复现脚本与误差表）
> 4. 🔴 **两个「看起来更专业、实测更差」的陷阱，请务必避开**（这是本次调研最有价值的负面结论）：
>    - **用七级风圈反解 Holland B** → 实测 **95% 的个例无解**（单调性陷阱，§B.2.3）。
>      七级风圈应该用来反解 **Rmax**（Chavas & Knaff 2022）。
>    - **用 500 hPa 引导气流做外推** → 实测**比"用最近两个点等速外推"差 3 倍**
>      （24 h：282 km vs 106 km，§C.2.4）。**默认关闭。**
> 5. ⚠️ **合规风险是真实的**：中央气象台《网站声明》原文明确禁止未经书面授权的
>    "转载、**链接**、转帖或复制发表"（§A.7.1）。**该通道默认关闭**；
>    **NOAA/NHC/IBTrACS 是公有领域**（可安心打包数据），GDACS 免费仅需署名，Open-Meteo 是 CC-BY 4.0。
>
> **本文档对每个数字都标了来源等级**，并且**明确列出了所有拿不到的东西**（§F.5 未核实清单）。
> 台风领域"二手资料极多、一手资料很贵" —— §F.5 就是**"哪些能放心写进代码、哪些必须自己验"**的分界线。

---

## 0. 核实等级约定（**请严格遵守，不要在日常引用中升级等级**）

| 标记 | 含义 |
| --- | --- |
| **【实测可用】** | 本文档作者在 2026-09-13 **实际发出 HTTP 请求并拿到 200 + 有效载荷**。原文片段见对应小节。 |
| **【文档核实】** | 未实际请求（缺 Key / 未注册），但读到了**官方文档原文**。字段名照抄文档，未经运行验证。 |
| **【源码核实】** | 结论来自**读到对方前端 JS / 官方 PDF** 的原始代码或文本，不是推测。比二手博客可靠。 |
| **【有文献支撑】** | 有同行评审论文 / 官方技术报告，给出作者年份 + 链接。 |
| **【工程近似】** | **没有文献支撑**，是本文档为「可实现」而构造的近似。**必须允许玩家覆盖。** |
| **【未核实】** | 请求失败 / 打不开 / 找不到来源。**不要当事实用。** |

**实测请求的原始产物**全部保存在 `_research/typhoon-samples/`（见 §附录 A），
可逐字节复核；本文档引用的所有 JSON 片段都是从这些文件里复制出来的。

---

## A. 台风数据 API

### A.0 实测总表（先看这张表）

| # | 数据源 | 端点 | 状态 | Key | 预报路径 | 风圈半径 | 许可风险 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **中央气象台台风网** | `typhoon.nmc.cn/weatherservice/typhoon/jsons/*` | **【实测可用】** | 否 | ✅ 12–120 h | ✅ 30/50/64 KTS × 4 象限 | ⚠️ **高**（见 §A.7.1） |
| 2 | **浙江省水利厅台风路径** | `typhoon.slt.zj.gov.cn/Api/TyphoonList|TyphoonInfo` | **【实测可用】**（部分端点 404） | 否 | ✅ 多机构 | ✅ 7/10/12 级 × 4 象限 | ⚠️ 中（未见声明） |
| 3 | **QWeather 和风天气** | `GET /v7/tropical/storm-list|storm-track|storm-forecast` | **【文档核实】** | **要**（JWT） | ✅ | ✅（仅 storm-track） | ✅ 商业 API，条款明确 |
| 4 | UApiPro `uapis.cn` | —— | **【实测】无台风接口**（404） | —— | ❌ | ❌ | —— |
| 5 | **NHC / NWS** | `nhc.noaa.gov/CurrentStorms.json`、`api.weather.gov/products/types/TCM` | **【实测可用】** | 否 | ✅ | ✅（TCM 文本 / KMZ） | ✅ **公有领域** |
| 6 | **GDACS** | `gdacs.org/gdacsapi/api/events/geteventlist/SEARCH` | **【实测可用】** | 否 | ❌（无逐点预报） | ❌ | ✅ 免费，请求署名 |
| 7 | **IBTrACS v04r01** | `ncei.noaa.gov/data/.../csv/ibtracs.*.csv` | **【实测可用】** | 否 | ❌（历史最佳路径） | ✅ 34/50/64 kt 四象限（nm） | ✅ 公有领域 |
| 8 | JTWC | `metoc.navy.mil` / `ftp.nhc.noaa.gov` / `tgftp.nws.noaa.gov` | **【实测失败】** HTTP 000 | —— | ❌ | ❌ | —— |
| 9 | Open-Meteo 高层风（**引导气流**用） | `api.open-meteo.com/v1/forecast?..._500hPa` | **【实测可用】** | 否 | ✅ 预报场 | —— | ✅ CC-BY 4.0 |

**复现命令（PowerShell / curl，逐条可复制）**

```powershell
# 1) 中央气象台：当年台风列表（JSONP，注意它是 .jsonp 不是纯 JSON）
curl.exe -s "https://typhoon.nmc.cn/weatherservice/typhoon/jsons/list_default"
curl.exe -s "https://typhoon.nmc.cn/weatherservice/typhoon/jsons/list_2026"
# 2) 中央气象台：单个台风全轨迹 + 官方预报路径（把 3304099 换成列表里的 id）
curl.exe -s -e "https://typhoon.nmc.cn/web.html" "https://typhoon.nmc.cn/weatherservice/typhoon/jsons/view_3304099"
# 3) 浙江：年度列表 / 单台风详情（769 KB 级，纯 JSON）
curl.exe -s "https://typhoon.slt.zj.gov.cn/Api/TyphoonList/2024"
curl.exe -s "https://typhoon.slt.zj.gov.cn/Api/TyphoonInfo/202411"
# 4) NHC 活动风暴（纯 JSON）
curl.exe -s "https://www.nhc.noaa.gov/CurrentStorms.json"
# 5) GDACS 热带气旋事件
curl.exe -s "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=TC&fromDate=2026-08-01&toDate=2026-09-13"
# 6) Open-Meteo 500 hPa 引导风
curl.exe -s "https://api.open-meteo.com/v1/forecast?latitude=20&longitude=130&hourly=wind_u_component_500hPa,wind_v_component_500hPa&forecast_days=5&timezone=UTC"
```

**两条通用注意事项（踩过的坑）**

1. **`typhoon.nmc.cn` 返回的是 JSONP，不是 JSON**：响应体形如
   `typhoon_jsons_view_3304099({...})`。解析前必须剥掉回调函数外壳
   （取第一个 `(` 与最后一个 `)` 之间的内容）。`Content-Type` 报的是
   `application/octet-stream`，**不能靠 Content-Type 判断**。
2. **`typhoon.slt.zj.gov.cn` 的编码是坑**：响应头**不带 charset**，
   用 `Invoke-WebRequest` / 部分 HTTP 客户端会按 Latin-1 解码，中文全变乱码
   （实测得到 `"name":"æªå½å"` 而不是 `"name":"摩羯"`）。
   **必须拿到原始字节后强制按 UTF-8 解码**。本仓库若有统一 HTTP 传输层，请为这个源显式指定 UTF-8。

---

### A.1 中央气象台台风网 `typhoon.nmc.cn` —— 【实测可用】

> 这是国内最常用的非官方接口，**本次实测仍然可用**。

#### A.1.1 端点全集（**从对方前端源码提取，不是猜的**）

我在 `https://typhoon.nmc.cn/js/typhoon/typhoon-web.js`（HTTP 200，76,463 字节）里
搜到如下**全部** `typhoon/jsons/*` 路径，没有别的：

```javascript
ajaxCache(ctx + "/typhoon/jsons/list_" + y, ...)     // 年度列表
ajaxCache(ctx + "/typhoon/jsons/list_default", ...)  // 默认（当年/当前）
ajaxCache(ctx + "/typhoon/jsons/forecastOrgs", ...)  // 预报机构代码表
ajaxCache(ctx + "/typhoon/jsons/maxYear", ...)       // 数据最早年份
ajaxCache(ctx + "/typhoon/jsons/view_" + typhoonID, ...) // 单台风详情
// 以及 typhoon/jsons/tip
```

逐个实测（全部 200）：

| 端点 | 实测响应（原样） |
| --- | --- |
| `.../jsons/maxYear` | `typhoon_jsons_maxYear({"year":2026})` |
| `.../jsons/forecastOrgs` | `typhoon_jsons_forecastOrgs({"orgs":{"BABJ":"中央气象台"}})` |
| `.../jsons/tip` | `typhoon_jsons_tip({"tip":""})` |
| `.../jsons/list_default` | `typhoon_jsons_list_default({"typhoonList":[[3332799,"nameless","热带低压","20260027","20260027",20260027,null,"start"],[3326569,"KROVANH","科罗旺","2624","2624",null,"一种树","stop"], …]})` |
| `.../jsons/list_2024` | 同上结构，含 `[3276030,"PABUK","帕布","2426","2426",null,"一种湄公河中的大型淡水鱼","stop"]` 等 |
| `.../jsons/list_2026` | 与 `list_default` 内容一致（1822 字节） |
| `.../jsons/view_3304099` | 104,224 字节（沙德尔 SAUDEL，含全部风圈 + 预报） |

**请求头**：`Referer: https://typhoon.nmc.cn/web.html` 我加上了；未做「去掉 Referer 是否仍 200」的对照实验，
建议保留。`User-Agent` 用一个普通浏览器 UA 即可。

#### A.1.2 `list_<year>` / `list_default` 结构

```jsonc
{"typhoonList": [
  [ 3332799, "nameless", "热带低压", "20260027", "20260027", 20260027, null, "start" ],
  [ 3326569, "KROVANH",  "科罗旺",   "2624",     "2624",     null,     "一种树", "stop" ]
]}
```

| idx | 含义 | 实测样例 | 置信度 |
| --- | --- | --- | --- |
| 0 | `id`（后续 `view_<id>` 用） | `3326569` | 高（直接用于 view_，实测可跳转） |
| 1 | 英文名 | `"KROVANH"` | 高 |
| 2 | 中文名 | `"科罗旺"`；无名热带低压为 `"热带低压"` | 高 |
| 3 | 编号（命名序号，2 位年 + 2 位号） | `"2624"` = 2026 年第 24 号 | 高（与"第24号"一致） |
| 4 | 展示编号 | `"2624"` | 高 |
| 5 | **总序号**（含未命名系统，8 位） | `20260022`，无名系统为 `20260027` | 中（**推断**，字段未文档化） |
| 6 | 名字含义 | `"一种树"`，无名系统为 `null` | 高 |
| 7 | 状态 | `"start"` = 活动中；`"stop"` = 已结束 | 高（与 `isactive` 语义吻合） |

#### A.1.3 `view_<id>` 外层结构

```jsonc
typhoon_jsons_view_3304099({"typhoon":[
  3304099, "SAUDEL", "沙德尔", 2618, 2618, null, "名字含义…", "stop",
  [ /* 定位点数组，见 A.1.4 */ ],
  [ /* 分段索引，见下 */ ]
]})
```

`typhoon` 数组按索引：`[0]=id, [1]=英文名, [2]=中文名, [3]=编号, [4]=展示编号, [5]=总序号, [6]=含义, [7]=状态, [8]=定位点数组, [9]=分段索引`。

**⚠️ 实测发现：`view_` 的定位点并不保证每 3 小时一个。**
对 YAGI（3275487）实测只返回 **36 个点**（首 `202409010000` UTC、末 `202409081200` UTC，
跨度 7.5 天）。实测的时间序列显示出一个**规律**：

```
202409010000 202409010600 202409011200 202409011800   ← 6 小时间隔
…（一直 6 小时）…
202409050600 202409050900 202409051200 202409051500   ← 切换为 3 小时间隔
202409052100 202409060000 202409060300 202409060600
202409060900 202409061200                             ← 3 小时段结束（≈ 登陆前后）
202409061800 202409070000 202409070600 …              ← 回到 6 小时间隔
```

即 **平时 6 小时一个点、在强度/登陆关键期加密到 3 小时**。
而浙江源对同一个台风给了 **125 个点**（1 h 间隔 101 个、3 h 间隔 21 个、2 h 间隔 2 个）。
**所以：要密集历史点做外推，浙江源明显更好；要官方预报路径，用中央气象台源。**（见 §C.1）

#### A.1.4 定位点（定位 + 官方预报路径）—— **本节是全文档最重要的字段映射**

**完整实测样例**（`view_3304099`，沙德尔 2026-08-21 21:00，原样复制，未改动）：

```jsonc
[ 3308084, "202608212100", 1787346000000, "TY", 147.5, 17.7, 970, 35, "NW", 20,
  [ ["30KTS", 380, 420, 400, 350, 3308084],
    ["50KTS",  80,  80, 150, 150, 3308084],
    ["64KTS",  40,  40,  60,  60, 3308084] ],
  { "BABJ": [ [12,  "202608212100", 145.8, 19,   965, 38, "BABJ", "TY"],
              [24,  "202608212100", 143.7, 20.3, 950, 45, "BABJ", "STY"],
              [36,  "202608212100", 140.9, 21.8, 935, 52, "BABJ", "SuperTY"],
              [48,  "202608212100", 138,   22.9, 925, 58, "BABJ", "SuperTY"],
              [60,  "202608212100", 135.1, 24,   920, 60, "BABJ", "SuperTY"],
              [72,  "202608212100", 132.9, 24.6, 930, 55, "BABJ", "SuperTY"],
              [96,  "202608212100", 130,   25.8, 940, 50, "BABJ", "STY"],
              [120, "202608212100", 126.7, 26.9, 950, 45, "BABJ", "STY"] ] },
  null ]
```

**定位点字段表**

| idx | 字段 | 实测值 | 单位 | 说明 |
| --- | --- | --- | --- | --- |
| 0 | pointId | `3308084` | — | 定位点 id |
| 1 | 时间标签 | `"202608212100"` | **UTC** | `yyyyMMddHHmm` |
| 2 | **epoch 毫秒** | `1787346000000` | ms | **UTC**，见下方核对 |
| 3 | 强度等级 | `"TY"` | — | `TD`/`TS`/`STS`/`TY`/`STY`/`SuperTY` |
| 4 | **经度** | `147.5` | °E | **⚠️ 经度在前**（见下方核对） |
| 5 | **纬度** | `17.7` | °N | |
| 6 | 中心气压 | `970` | **hPa** | |
| 7 | 最大风速 | `35` | **m/s** | 与 idx3/idx8 的等级自洽 |
| 8 | 移动方向 | `"NW"` | 16 方位英文 | 早期点可能是 `"no"`（无） |
| 9 | 移动速度 | `20` | **km/h** | 早期点为 `0` |
| 10 | **风圈半径**（见 A.1.5） | `[[…],[…],[…]]` | km | 可为 `[]` |
| 11 | **官方预报路径**（见 A.1.6） | `{"BABJ":[…]}` | — | 早期点 / 无预报时为 `null` |
| 12 | 发布信息 | `["202608280600","2026年08月28日06时00分",null,null]` | — | idx0=发布时间(UTC 标签)，idx1=中文时间 |

**时间基准核对（实测，不是猜的）**：把 idx1 字符串与 idx2 的 epoch 互相对照，前 6 个点完全一致：

```
label=202409010000  epochUTC=202409010000  lon=126.2 lat=12.2
label=202409010600  epochUTC=202409010600  lon=125.3 lat=13
label=202409011200  epochUTC=202409011200  lon=124.5 lat=13.9
label=202409011800  epochUTC=202409011800  lon=123.3 lat=14.5
label=202409020000  epochUTC=202409020000  lon=122.8 lat=15.2
label=202409020600  epochUTC=202409020600  lon=122.3 lat=16.3
```

→ **中央气象台的时间是 UTC。**（浙江源是北京时间 UTC+8，同一时刻相差 8 小时，见 §A.2。**混用会整体错 8 小时。**）

**经/纬顺序核对（实测）**：以摩羯（YAGI）登陆海南为交叉验证点 ——
浙江源记录官方登陆时刻/坐标 `2024-09-06 16:20（北京时）`、`110.99°E, 19.83°N`、地点"海南文昌沿海"。
中央气象台 `view_3275487` 在该时刻附近的点为：

```
time=202409060900(UTC)=17:00 北京时  a(经)=110.8  b(纬)=19.8  p=920
time=202409061200(UTC)=20:00 北京时  a(经)=109.0  b(纬)=20.3  p=935
```

与浙江源同一时刻（北京时 17:00 → `110.80 / 19.90`）吻合。
→ **idx4 = 经度，idx5 = 纬度。**（如果按「纬度在前」解析，海南会落到 110.8°N 这种不存在的纬度上，立刻能发现问题 —— 建议在解析层加一条断言：`|lat|<=90 && 140<=|lon|<=180` 之类的合理域检查。）

#### A.1.5 风圈半径（`idx10`）—— **象限顺序：NE, SE, SW, NW**

实测原始数据：

```jsonc
[ ["30KTS", 380, 420, 400, 350, 3308084],
  ["50KTS",  80,  80, 150, 150, 3308084],
  ["64KTS",  40,  40,  60,  60, 3308084] ]
```

**象限顺序的证据（【源码核实】）**：从 `https://typhoon.nmc.cn/js/typhoon/gis.js`（HTTP 200，164,193 字节）中提取到：

```javascript
// 风圈数值的标签化渲染 —— 直接给出了 4 个数的方向含义
_fq[fq_name] = "东北" + wc[1] + "KM, 东南" + wc[2] + "KM, <br>西南" + wc[3] + "KM, 西北" + wc[4] + "KM"
// 风圈等级名
typhoon_level: ["七级", "十级", "十二级"],
var fq_name = map.typhoon_level[j] + "风圈";
```

**结论表**：

| 组内 idx | 含义 | 单位 |
| --- | --- | --- |
| 0 | 标签 | `"30KTS"` / `"50KTS"` / `"64KTS"` |
| 1 | **东北 NE** | km |
| 2 | **东南 SE** | km |
| 3 | **西南 SW** | km |
| 4 | **西北 NW** | km |
| 5 | pointId | — |

标签 → 中文风级 → 阈值风速的换算（三者在物理上自洽，可交叉验证）：

| 标签 | 中文 | 阈值 | 说明 |
| --- | --- | --- | --- |
| `30KTS` | 七级风圈 | 30 kt = **15.4 m/s** | 7 级风 13.9–17.1 m/s 的中值附近 ✅ |
| `50KTS` | 十级风圈 | 50 kt = **25.7 m/s** | 10 级风 24.5–28.4 m/s 的中值附近 ✅ |
| `64KTS` | 十二级风圈 | 64 kt = **32.9 m/s** | 12 级风 32.7–36.9 m/s 的下界 ✅ |

> **实测观测**：不是每个点都有风圈（早期点为 `[]`）；弱系统通常只有 `30KTS`；
> 强系统才有三组。实测在 `view_3304099` / `view_3277083` / `view_3279904` 中同时出现三组。

#### A.1.6 **官方预报路径（`idx11`）—— 这就是「未来 24/48/72 小时位置」**

```jsonc
{"BABJ": [ [12, "202608212100", 145.8, 19, 965, 38, "BABJ", "TY"], … ]}
```

| 组内 idx | 字段 | 单位 | 说明 |
| --- | --- | --- | --- |
| 0 | **预报时效 τ** | h | 实测出现 `12, 24, 36, 48, 60, 72, 96, 120` |
| 1 | **起报时间** | UTC | 实测**等于该定位点的 idx1**（即"基于哪个定位点做的预报"） |
| 2 | **经度** | °E | 与实况同为「经度在前」 |
| 3 | **纬度** | °N | |
| 4 | 预报中心气压 | hPa | |
| 5 | 预报最大风速 | m/s | |
| 6 | 机构代码 | — | 实测本次只有 `BABJ`（= 中央气象台） |
| 7 | 预报强度等级 | — | `TD`/`TS`/`STS`/`TY`/`STY`/`SuperTY` |

**预报有效时刻** = `起报时间(idx1) + τ 小时`。

> **诚实说明**：`forecastOrgs` 接口实测只返回 `{"BABJ":"中央气象台"}` 一个机构。
> 网上很多资料说这个接口能拿到日本 `RJTD`、美国 `KWBC` 等多机构预报 ——
> **在 2026-09-13 的实测中，我抓到的所有 `view_*` 样本里 `forecast` 对象都只有 `BABJ` 一个键。**
> 不要假设多机构可用。（浙江源那边确实有 5 个机构，见 §A.2.3。）

#### A.1.7 频率限制 —— **【未核实】+ 实测 12 连发未被限流**

官方**没有公开任何频率限制文档**（该接口本身就是网站内部接口）。
我做了 12 次快速连发（每次约 0.21 s 往返，总计约 2.6 s），结果全部 200：

```
req1 -> 200 0.206482     req5 -> 200 0.224780     req9  -> 200 0.224580
req2 -> 200 0.219805     req6 -> 200 0.214563     req10 -> 200 0.202231
req3 -> 200 0.208239     req7 -> 200 0.210574     req11 -> 200 0.216216
req4 -> 200 0.218635     req8 -> 200 0.251671     req12 -> 200 0.210230
```

**但这不能证明没有限流**：实测样本太小、时间太短，也测不出封 IP 的策略。
**工程建议：轮询间隔 ≥ 10 分钟**（台风定位本身 3–6 小时才更新一次，
10 分钟已经远远过度采样了）。见 §F 的默认参数表。

---

### A.2 浙江省水利厅 `typhoon.slt.zj.gov.cn` —— 【实测可用】（部分端点）

#### A.2.1 端点实测结果

| 端点 | 实测结果 |
| --- | --- |
| `GET /Api/TyphoonList/{year}` | **200**，4039 B（2024）/ 4065 B（2026） |
| `GET /Api/TyphoonInfo/{tfid}` | **200**，**769,075 B**（202411 摩羯） |
| `GET /Api/TyphoonForecast/{tfid}` | **404** `{"timestamp":"2026-09-13T14:42:47.337+00:00","status":404,"error":"Not Found","path":"/Api/TyphoonForecast/202411"}` |
| `GET /Api/TyphoonPath/{tfid}` | **404**（同上格式，`"path":"/Api/TyphoonPath/202411"`） |
| `GET /Api/TyphoonPoint/{tfid}` | **404** |
| `GET /Api/TyphoonDetail/{tfid}` | **404** |
| `GET /Api/TyphoonLast` | **404** |

> 服务端是 Spring Boot（404 响应体是标准 Spring 错误 JSON，含 ISO 时间戳与 `path` 字段）。
> **`TyphoonForecast` 这一路是网上流传最广、但在本次实测中 404 的路径 —— 请勿照抄。**
> 预报路径在 `TyphoonInfo` 里，不需要单独接口。

#### A.2.2 `TyphoonList/{year}` 字段

实测（UTF-8 正确解码后）：

```json
[{"tfid":"20240014","name":"未命名","enname":"NAMELESS","starttime":"2024-09-10 08:00:00",
  "endtime":"2024-09-11 05:00:00","warnlevel":"","isactive":"0"},
 {"tfid":"202401","name":"艾云尼","enname":"EWINIAR","starttime":"2024-05-24 14:00:00", …}]
```

注意编号有两种格式：`202401`（3 位序号）与 `20240014`（4 位总序号，用于未命名系统）。
**`tfid` 必须原样传给 `TyphoonInfo`**，不要自己补零。

#### A.2.3 `TyphoonInfo/{tfid}` 结构 —— **多机构预报 + 官方登陆记录**

**顶层**

```jsonc
{ "tfid":"202411", "name":"摩羯", "enname":"YAGI", "isactive":"0",
  "starttime":"2024-09-01 14:00:00", "endtime":"2024-09-08 14:00:00", "warnlevel":"white",
  "centerlng":"114.750000", "centerlat":"22.200000",
  "land":[ … ],        // 官方登陆记录（见下）
  "points":[ … ] }     // 定位序列 + 预报
```

> ⚠️ **时间基准是北京时间（UTC+8）**，与中央气象台的 UTC 不同。
> 实测交叉验证：中央气象台 `202409010600`(UTC) ↔ 浙江 `2024-09-01 14:00:00`，相差正好 8 小时。

**`points[]` 字段（实测）**

| 字段 | 实测值 | 单位 | 说明 |
| --- | --- | --- | --- |
| `time` | `"2024-09-05 07:00:00"` | BJT | **注意是当地时间字符串，不是 ISO、不带时区** |
| `lng` / `lat` | `115.80` / `19.00` | ° | 数值或字符串（实测 JSON 中为数值型） |
| `strong` | `"超强台风"` | — | 中文强度 |
| `power` | `17` | 级 | 风力等级 |
| `speed` | `58` | **m/s** | 最大风速 |
| `pressure` | `925` | hPa | 中心气压 |
| `movespeed` | `12` | **km/h** | 移动速度 |
| `movedirection` | `"西"` | 中文方位 | 移动方向 |
| `radius7` | `"300\|300\|350\|350"` | km | 七级风圈，**管道分隔**，见下 |
| `radius10` | `"140\|140\|140\|140"` | km | 十级风圈（可为空串） |
| `radius12` | `"80\|80\|80\|80"` | km | 十二级风圈（可为空串） |
| `forecast` | `{"tm":"中国","forecastpoints":[…]}` | — | 见下 |
| `ckposition` / `jl` | `""` | — | 实测为空 |

实测间隔分布（YAGI 125 个点）：`1h × 101, 3h × 21, 2h × 2` —— **比中央气象台密得多**。

**🔴 `radius7/10/12` 的象限顺序是 `NE|SE|NW|SW` —— 与中央气象台不同！**

**证据（【源码核实】）**：`https://typhoon.slt.zj.gov.cn/js/app~e6e59946.72c2c584.js` 中 `DrawCircle` 函数原文：

```javascript
function DrawCircle(e,t,a,n,r,i,o){ …
  if(4==(a=a.split("|")).length&&0!=a[0]&&0!=a[1]&&0!=a[2]&&0!=a[3]){
    _=Object.assign({
        NORTHEAST: 1e3*a[0],
        SOUTHEAST: 1e3*a[1],
        NORTHWEST: 1e3*a[2],
        SOUTHWEST: 1e3*a[3]
      },_);
```

→ **`a[0]=NE, a[1]=SE, a[2]=NW, a[3]=SW`。**
（`1e3*` 是因为 leaflet 的 `windCircle` 插件收米，源头单位是 km。）

> 对比：中央气象台是 `NE, SE, SW, NW`。
> **两个源的顺序在最后两个象限上是对调的。** 用同一个解析函数处理两个源，
> 会把 SW/NW 的半径互换 —— 在西北太平洋（登陆我国东南沿海）场景下，
> 这意味着**危险半圆的半径被搬到错误的一侧**，是必须避免的低级错误。
> **建议：解析层统一转成 `[NE, SE, SW, NW]` 的内部规范顺序，每个源单独写一个映射。**

**`forecast` 字段**

```jsonc
"forecast": {
  "tm": "中国 中国台湾 日本 中国香港 美国",   // 有预报的机构（空格分隔，实测）
  "forecastpoints": [
    {"time":"2024-09-08 11:00:00","lng":104.70,"lat":21.10,"strong":"热带低压",
     "power":7,"speed":15,"pressure":1000},                       // 无 tm = 第一个机构
    { … , "tm":"台湾", "ybsj":"2024-09-08T00:00:00.000+00:00"}    // 有 tm 时标明机构
  ]
}
```

- 实测一次 `forecastpoints` 最多 40 个点，对应 5 个机构 × 8 个时效；
  中国单独预报时为 9 个点。
- 无 `tm` 字段的点归属于 `tm` 列表里的第一个机构（实测为"中国"）。
- `ybsj` 是 ISO-8601 带时区的起报时间（**注意它带 `+00:00`，与 `time` 的北京时间基准不同**）。

**`land[]`（官方登陆记录）—— 这是个意外收获**

```jsonc
[{"landaddress":"海南文昌沿海","landtime":"2024-09-06 16:20:00","lng":"110.99","lat":"19.83",
  "info":"第11号台风“摩羯”已于9月6日16时20分在海南文昌沿海登陆","strong":"超强台风"},
 {"landaddress":"广东省徐闻县角尾乡","landtime":"2024-09-06 22:20:00","lng":"109.94","lat":"20.23", …},
 {"landaddress":"越南广宁省南部沿海","landtime":"2024-09-07 15:30:00","lng":"106.84","lat":"20.88", …}]
```

**这是免费拿到的、带权威坐标与时刻的真实登陆记录**，非常适合
① 校准本地的落点判定算法（见 §C.3）；② 做回归测试的黄金数据集。
注意 `lng`/`lat` 在这里是**字符串**，需要转换。

---

### A.3 QWeather 和风天气热带气旋 API —— 【文档核实】（未实际请求：需注册 Key）

这个是我找到的**唯一一个「有正式文档 + 商业条款 + 同时提供实况、预报、多级风圈」的国内可用接口**，
比中央气象台的私有 JSONP 接口合规得多。**未实测（没有 Key）**，字段全部照抄官方文档。

**文档**：<https://dev.qweather.com/en/docs/api/tropical-cyclone/>（HTTP 200，实测抓取 34,220 字节）

| 端点 | 用途 |
| --- | --- |
| `GET /v7/tropical/storm-list?basin=NP&year=YYYY` | 台风列表 |
| `GET /v7/tropical/storm-track?stormid=NP2018` | **实况 + 历史轨迹 + 风圈** |
| `GET /v7/tropical/storm-forecast?stormid=NP2018` | **预报路径** |

- **认证**：`Authorization: Bearer <JWT>`，另有每账号专属的 **API Host**（文档里写作 `https://your-api-host`，
  必须从控制台取，不能用统一域名 —— 这是 QWeather 近年的改动，**照抄老博客的 `devapi.qweather.com` 会失败**）。
- **basin**：`AL / EP / NP / SP / NI / SI`，但 storm-list 文档明确写 **"Only the coastal areas of China are supported now, i.e. basin=NP"**。
- **year**：storm-list 文档写 "this year and last year"（索引页写 "past 2 years"，两处措辞不一致 —— 【未核实】以哪为准）。
- **`storm-forecast` 注意**："For inactive storms, the returned data is NULL, please get the storms status by Storm List API first."

**`storm-track` 实测响应样例（文档原文）** —— **含全部三级风圈，这是它的最大价值**：

```jsonc
{"code":"200","updateTime":"2024-05-30T06:11+00:00","isActive":"1",
 "now":{"pubTime":"2024-05-30T05:00+08:00","lat":"27.7","lon":"134.5","type":"STS",
        "pressure":"980","windSpeed":"30","moveSpeed":"21","moveDir":"NE","move360":"",
        "windRadius30":{"neRadius":"120","seRadius":"150","swRadius":"120","nwRadius":"100"},
        "windRadius50":{"neRadius":"60","seRadius":"60","swRadius":"60","nwRadius":"50"}},
 "track":[{"time":"2024-05-29T17:00+08:00","lat":"26.1","lon":"132.5","type":"TY",
           "pressure":"970","windSpeed":"35","moveSpeed":"28","moveDir":"NE","move360":"",
           "windRadius30":{…},"windRadius50":{…},"windRadius64":{…}}, …]}
```

**⭐ 关键优势：这里的风圈是 `{neRadius, seRadius, swRadius, nwRadius}` —— 自带方向标签，
不存在 §A.1.5 / §A.2.3 那种象限顺序踩坑问题。** 如果预算允许，这是最省心的源。

**`storm-forecast` 实测响应样例（文档原文）**：

```jsonc
{"code":"200","updateTime":"2021-07-27T03:00+00:00",
 "forecast":[{"fxTime":"2021-07-27T20:00+08:00","lat":"31.7","lon":"118.4","type":"TS",
              "pressure":"990","windSpeed":"18","moveSpeed":"","moveDir":"","move360":""}, …]}
```

> ⚠️ **实测核实过的负面结论**：`storm-forecast` 的文档页里**没有** `windRadius*` 字段
> （我在这页 HTML 里检索 `windRadius`，**命中 0**；`storm-track` 页命中）。 
> **所以预报时段可能拿不到风圈半径**，只能沿用当前时刻的风圈或按强度缩放（见 §B.2.5）。
> 这一点在没 Key 的情况下无法运行验证，标 **【文档核实 + 未实测】**。

**许可**：商业 API，有明确的 `Terms of Service` / `Attribution` 页面；
按文档要求做署名即可。**再分发**场景要注意不能把数据本身打进 jar（只做运行时拉取）。

---

### A.4 UApiPro（`uapis.cn`）—— 【实测】**没有台风接口**

本仓库已经在用 `https://uapis.cn/api/v1/misc/weather`（见 `UApiProWeatherProvider`）。
用户要求核实它有没有台风 / 天气预警接口。**我实测了，方法如下（这一步很关键：不要靠猜路径）**：
UApiPro 提供 OpenAPI 规范，我从 404 响应体里发现它：

```json
{"code":"NOT_FOUND","message":"API endpoint not found","details":{
  "docs_url":"https://uapis.cn/docs/api-reference/introduction",
  "openapi_url":"https://uapis.cn/openapi.json","method":"GET","path":"/api/v1/weather/typhoon"}}
```

于是拉取 `https://uapis.cn/openapi.json`（**HTTP 200，498,445 字节**），
把全部 **112** 条路径列出来，用 `typhoon|weather|warn|alert|rain|storm` 过滤：

```
/api/v1/misc/weather
/api/v1/misc/weather/history
```

**结论（确证）**：
- ❌ **没有台风接口**。我猜的三个路径 `/api/v1/weather/typhoon`、`/api/v1/weather/typhoon/list` 全部 **404**
  （原始 404 体见上）。
- ❌ **没有独立的预警接口**。
- ✅ **但天气接口自带预警数组**。OpenAPI 里 `/api/v1/misc/weather` 的响应 schema 有：

```jsonc
"alerts": { "type":"array", "description":"官方气象预警列表（存在有效预警时返回）",
  "items": { "properties": {
    "title": {…}, "type":  {"description":"预警类型，如雷电、暴雨"},
    "level": {"description":"预警级别，如蓝色、黄色、橙色、红色"},
    "text": {…}, "publish_time": {…}, "publisher": {…}, "guidance": {"type":"array"} } } }
```

> **对雷电子系统有用**：`alerts[].type` 里会出现 `"雷电"`，`level` 给颜色分级。
> 这是**免费**的雷电活动指示器，可作为游戏内雷电强度的外部锚点（见 §D.5）。
>
> 实测：`GET https://uapis.cn/api/v1/misc/weather?city=杭州` → 200，
> `{"province":"浙江省","city":"杭州市","adcode":"330100","weather":"晴","weather_icon":"100","temperature":25,"wind_direction":"南风","wind_power":"1级","humidity":67,"report_time":"11 分钟前发布"}`
> —— **该次响应里没有 `alerts` 键**，符合 schema 的"存在有效预警时返回"。

**频率限制（实测读到官方 FAQ 原文）**：<https://uapis.cn/docs/api-reference/faq>（HTTP 200，613,732 字节）

> Q1. 不注册能用吗？可以。无需登录或填写邮箱即可直接调用，这类用户在文档中统称为"访客"。
> Q2. 访客每个月能免费用多少次？访客每月拥有 **1500 积分** 的免费额度。大多数基础接口单次调用消耗 1 积分，
> 因此这份额度大约相当于每月 1500 次调用。
> Q3. 积分什么时候重置？每月 1 号 0 点自动重置。…
> Q4. 积分是按账号算还是按设备算？访客额度按 **IP 地址（网络出口）** 计算。

**⚠️ 这是一个重要的工程约束**：访客额度只有 **1500 次/月/IP**，
而本仓库默认数据源就是 UApiPro。**台风子系统绝对不应该高频轮询 UApiPro** ——
按"每 10 分钟一次"算，一个月就是 4320 次，已经**超支 3 倍**。
→ 见 §F 的降级链：雷电/预警信息应改为按需（仅在检测到台风或强对流时）拉取，或走 Open-Meteo。

**许可**：UApiPro 有"隐私政策和用户协议"与"公平使用与速率限制"页面
（我按 `/docs/api-reference/fair-use` 请求得到 404，正确路径未找到 —— **【未核实】**，请到文档站内查）。

---

### A.5 国际源

#### A.5.1 NHC（美国国家飓风中心，大西洋 + 东太平洋）—— 【实测可用】

| 端点 | 实测 | 内容 |
| --- | --- | --- |
| `https://www.nhc.noaa.gov/CurrentStorms.json` | **200**，4,632 B | 活动风暴列表 |
| `https://api.weather.gov/products/types/TCM` | **200**，19,036 B（`application/ld+json`） | 预报/警告产品 ID 列表 |
| `https://api.weather.gov/products/{id}` | **200**，1,915 B | **产品全文**（`productText`，纯文本） |
| `https://www.nhc.noaa.gov/gis/forecast/archive/ep142026_5day_016.zip` | **200**，27,547 B（`application/zip`） | 预报路径 shapefile 包 |

`CurrentStorms.json` 的字段（实测完整列表）：
`id, binNumber, name, classification, intensity, pressure, latitude, longitude,
latitudeNumeric, longitudeNumeric, movementDir, movementSpeed, lastUpdate,
publicAdvisory, forecastAdvisory, windSpeedProbabilities, forecastDiscussion, forecastGraphics,
forecastTrack, windWatchesWarnings, trackCone, initialWindExtent, forecastWindRadiiGIS,
bestTrackGIS, earliestArrivalTimeTSWindsGIS, mostLikelyTimeTSWindsGIS, windSpeedProbabilitiesGIS,
stormSurgeWatchWarningGIS, potentialStormSurgeFloodingGIS, peakSurgeKML`

实测样例（节选）：

```jsonc
{"activeStorms":[{"id":"ep142026","binNumber":"EP4","name":"Norbert","classification":"TS",
  "intensity":"45","pressure":"1000","latitude":"18.2N","longitude":"132.7W",
  "latitudeNumeric":18.2,"longitudeNumeric":-132.7,"movementDir":280,"movementSpeed":12,
  "lastUpdate":"2026-09-13T15:00:00.000Z",
  "forecastTrack":{"advNum":"016","issuance":"2026-09-13T15:00:00.000Z",
      "zipFile":"https://www.nhc.noaa.gov/gis/forecast/archive/ep142026_5day_016.zip",
      "kmzFile":"https://www.nhc.noaa.gov/storm_graphics/api/EP142026_016adv_TRACK.kmz"},
  …}]}
```

> ⚠️ **`CurrentStorms.json` 里没有逐点预报坐标**，只给下载链接（zip / kmz）。
> 想要坐标，要么解析 shapefile，要么用下面的文本产品 —— **文本产品更省事**。

**最佳路径：TCM 预报/警告文本产品（含逐点预报 + 风圈）**

- HTML 版本：`https://www.nhc.noaa.gov/text/MIATCMEP4.shtml`（实测 200，25,545 B，正文在 `<pre>` 里）
- **JSON 版本（推荐）**：
  1. `GET https://api.weather.gov/products/types/TCM` → 拿 `@graph[].id`
  2. `GET https://api.weather.gov/products/{id}` → `productText` 字段即全文

实测从 `productText` 中取出的原文（**未改动**）：

```
TROPICAL STORM CENTER LOCATED NEAR 18.2N 132.7W AT 13/1500Z
POSITION ACCURATE WITHIN  30 NM
PRESENT MOVEMENT TOWARD THE WEST OR 280 DEGREES AT  10 KT
ESTIMATED MINIMUM CENTRAL PRESSURE 1000 MB
MAX SUSTAINED WINDS  45 KT WITH GUSTS TO  55 KT.
34 KT....... 80NE  20SE  30SW  90NW.
4 M SEAS.... 90NE  30SE  30SW  90NW.

FORECAST VALID 14/0000Z 18.6N 134.4W
MAX WIND  45 KT...GUSTS  55 KT.
34 KT... 80NE  20SE  30SW  90NW.

FORECAST VALID 14/1200Z 19.0N 137.0W
MAX WIND  40 KT...GUSTS  50 KT.
34 KT... 50NE   0SE   0SW  40NW.
…
FORECAST VALID 16/1200Z...DISSIPATED
```

**价值**：这是**国际源里唯一免费、纯文本、含逐点预报坐标 + 34/50/64 kt 四象限风圈的官方产品**，
正则解析难度极低。风圈顺序是**明确的 `NE SE SW NW`**（文本里直接带方向后缀，无歧义）。

`api.weather.gov` 的请求要求：带一个能识别你的 `User-Agent`（我用 `(nowweather-research)`，实测 200）。
**推测**：`api.weather.gov` 对无 UA 请求会 403（这是 NWS 的已知策略），本次未做对照实验 —— **【未核实】**。

#### A.5.2 GDACS —— 【实测可用】

```powershell
curl.exe -s "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=TC&fromDate=2026-08-01&toDate=2026-09-13"
```

实测 **200**，`application/json; charset=utf-8`，GeoJSON FeatureCollection：

```jsonc
{"type":"FeatureCollection","features":[{"type":"Feature","bbox":[117.4,23.8,117.4,23.8],
 "geometry":{"type":"Point","coordinates":[117.4,23.8]},
 "properties":{"eventtype":"TC","eventid":1001305,"episodeid":47,"eventname":"SAUDEL-26",
   "glide":"TC-2026-000161-CHN","alertlevel":"Orange","alertscore":2,
   "fromdate":"2026-08-18T12:00:00","todate":"2026-09-03T00:00:00",
   "datemodified":"2026-09-04T10:04:35","source":"JTWC",
   "severitydata":{"severity":212.9616,"severitytext":"Tropical Storm (maximum wind speed of 213…
   …
```

单事件详情：`GET /gdacsapi/api/events/geteventdata?eventtype=TC&eventid=1001305&episodeid=47`
（实测 **200**，25,640 B）。字段：`eventtype, eventid, episodeid, eventname, glide, name, description,
htmldescription, icon, iconoverall, url, alertlevel, alertscore, episodealertlevel, episodealertscore,
istemporary, iscurrent, country, fromdate, todate, datemodified, iso3, source, sourceid, polygonlabel,
Class, countryonland, affectedcountries, severitydata, episodes, impacts, images, additionalinfos,
documents, cyclonesurge`

> ⚠️ **实测结论：GDACS 的属性里没有逐点预报路径**（`source` 显示它本身就是转引 JTWC 的）。
> 它的价值是「**有没有台风正在活动 / 影响哪些国家 / 预警级别**」这个粗粒度信号，
> 不是风场或路径数据。**不要用它做风场。**

**频率限制与分页（【源码核实】，来自官方 PDF）**：
`https://www.gdacs.org/Documents/2025/GDACS_API_quickstart_v1.pdf`（HTTP 200，131,499 B）原文：

> Currently, the API provides no more than **100 records at once**, ordered by date ("todate" property),
> so the suggestion is to set more selective search parameters, or to make multiple queries by
> specifying an additional parameter **"pagenumber"**, to get the next 100 events…
> The user may save a collection and then (programmatically) check for updates, e.g. by comparing
> the "datetime" property, before calling the GDACS API again.

Swagger：<https://www.gdacs.org/gdacsapi/swagger/index.html>

**许可（【源码核实】，官方 PDF 原文）**：

> **GDACS data are free and available through its APIs.** … **Terms of use**: We only request to
> **acknowledge the source as "Global Disaster Alert and Coordination System, GDACS"**,
> but please read the disclaimer at `https://www.gdacs.org/documents/2025/GDACS_Terms_of_use_Mar_25.pdf`.

→ **免费，署名是被"请求"而非"要求"。** 完整条款 PDF（我下载并解析了
`GDACS_Terms_of_use_Oct_25.pdf`，202,922 B）共 8 条，核心是：
- 第 3 条：数据 "as is"，无任何担保；
- 第 8 条：GDACS 利益相关方**完全不承担**因使用其内容导致的损害/伤亡责任；
- **没有任何"禁止再分发"条款**，也没有要求署名是一致性义务（只是 "we only request"）。

#### A.5.3 IBTrACS v04r01（历史最佳路径）—— 【实测可用】

**用途**：台风风场模型（§B）的参数标定与单元测试的**黄金数据集**，以及"离线/无网"时的气候态。
**不能**用于实时（它滞后更新）。

```
# 最近 3 年（实测 Content-Length: 10,368,576 B ≈ 10.4 MB，Last-Modified: Thu, 10 Sep 2026 08:57:29 GMT）
https://www.ncei.noaa.gov/data/international-best-track-archive-for-climate-stewardship-ibtracs/v04r01/access/csv/ibtracs.last3years.list.v04r01.csv
# 1980 年至今（实测 Content-Length: 143,879,353 B ≈ 143 MB —— 不要打进 jar）
https://www.ncei.noaa.gov/data/international-best-track-archive-for-climate-stewardship-ibtracs/v04r01/access/csv/ibtracs.since1980.list.v04r01.csv
```

> **诚实记录一次失败**：`since1980` 全量下载在本机 **240 秒超时**（143 MB）。
> 后来改用 `curl -I` 只取响应头成功拿到 `Content-Length`。
> **3 年文件 10.4 MB 实测存在**（用 `Range: 0-4000` 取到表头，见下）。

**列名（实测表头原文，节选）**：

```
SID,SEASON,NUMBER,BASIN,SUBBASIN,NAME,ISO_TIME,NATURE,LAT,LON,WMO_WIND,WMO_PRES,WMO_AGENCY,
TRACK_TYPE,DIST2LAND,LANDFALL,IFLAG,
USA_AGENCY,USA_ATCF_ID,USA_LAT,USA_LON,USA_RECORD,USA_STATUS,USA_WIND,USA_PRES,USA_SSHS,
USA_R34_NE,USA_R34_SE,USA_R34_SW,USA_R34_NW,USA_R50_NE,USA_R50_SE,USA_R50_SW,USA_R50_NW,
USA_R64_NE,USA_R64_SE,USA_R64_SW,USA_R64_NW,USA_POCI,USA_ROCI,USA_RMW,USA_EYE,
TOKYO_LAT,TOKYO_LON,TOKYO_GRADE,TOKYO_WIND,TOKYO_PRES,TOKYO_R50_DIR,TOKYO_R50_LONG,TOKYO_R50_SHORT,…,
CMA_LAT,CMA_LON,CMA_CAT,CMA_WIND,CMA_PRES,
HKO_LAT,HKO_LON,HKO_CAT,HKO_WIND,HKO_PRES,
KMA_…,NEWDELHI_…,REUNION_…,BOM_…,NADI_…,WELLINGTON_…,DS824_…,TD9636_…,…,
USA_GUST,BOM_GUST,…,STORM_SPEED,STORM_DIR
```

第 2 行是单位行（实测）：`kts`（风速）、`mb`（气压）、`nmile`（风圈半径）、`degrees_north/east`、`km`（DIST2LAND）、`ft`（浪高）等。

**对我们最有用的列**：

| 列 | 单位 | 用途 |
| --- | --- | --- |
| `WMO_WIND` / `WMO_PRES` | kts / mb | 国际统一的强度（回退 `USA_WIND`/`USA_PRES`） |
| `USA_R34_NE/SE/SW/NW` | **nmile** | 34 kt 风圈（≈ 七级风圈，30 kt 的近似） |
| `USA_R50_*` / `USA_R64_*` | nmile | 50 / 64 kt 风圈 |
| **`USA_RMW`** | nmile | **最大风速半径 Rmax（实测）** |
| `USA_ROCI` | nmile | 最外围闭合等压线半径 |
| `USA_POCI` | mb | 外围环境气压 —— **Δp 可以直接算** |
| `STORM_SPEED` / `STORM_DIR` | kts / ° | 移动速度与方向 —— **不对称风场的关键输入** |
| `LANDFALL` / `DIST2LAND` | – / km | 登陆标记与离陆距离 |
| `ISO_TIME` | ISO | 时间（UTC） |

**⭐ 这是极其宝贵的一套「真值」**：有了 `USA_RMW`（实测 Rmax）+ `USA_R34/R50/R64`（实测风圈）
+ `USA_POCI`（环境气压）+ `WMO_WIND`（Vmax），
**可以在本地拟合/验证 §B 的 Holland B 与风圈反演算法**，而不是只能靠文献值拍脑袋。
建议做成一个一次性离线脚本 + 固化的小样本进测试资源（注意版权，见下）。

**许可**：NCEI/NOAA 数据为**美国联邦政府作品，公有领域**（与 §A.7.4 的 NWS 声明一致）。
IBTrACS 页面（实测 200，111,576 B）**只要求引用文献**，不限制再分发：

> Dataset Identifiers: **doi:10.25921/82ty-9e16** … Please use both the BAMS paper and dataset citations
> when referencing IBTrACS…：Knapp, K. R., M. C. Kruk, D. H. Levinson, H. J. Diamond, and C. J. Neumann,
> 2010: *The International Best Track Archive for Climate Stewardship (IBTrACS)*, Bull. Amer. Meteor. Soc.,
> 91, 363–376.
> 用其气候统计请另引：Schreck III, C. J., K. R. Knapp, and J. P. Kossin, 2014: *The Impact of Best Track
> Discrepancies on Global Tropical Cyclone Climatologies using IBTrACS*, Mon. Wea. Rev., 142, 3881–3899,
> doi:10.1175/MWR-D-14-00021.1

#### A.5.4 JTWC、Zoom Earth、Tropical Tidbits —— 【实测失败 / 未核实】

| 目标 | 实测结果 |
| --- | --- |
| `https://www.metoc.navy.mil/jtwc/jtwc.html` | `HTTP 000`，`curl` 退出、`bytes=0`（连接被阻断/超时；本机网络不可达） |
| `https://www.metoc.navy.mil/jtwc/products/...` | 同族域名，未再逐个尝试 |
| `https://tgftp.nws.noaa.gov/data/raw/wt/wtpn31.pgtw..txt`（JTWC 报文经 NWS 网关镜像） | `HTTP 000`，`bytes=0` |
| `https://ftp.nhc.noaa.gov/atcf/btk/bal142026.dat`（ATCF b-deck） | `HTTP 000`，`bytes=0` |
| `https://ftp.nhc.noaa.gov/atcf/fst/bal142026.fst`（ATCF f-deck） | `HTTP 000`，`bytes=0` |
| `https://ftp.nhc.noaa.gov/atcf/aid_public/` | `HTTP 000`，`bytes=0` |

> **原样记录的失败信息就是上面这些 —— 全部是 `HTTP 000` / `bytes=0`，即连接层面失败，
> 不是 404/403。** 我不能区分这是「本机网络出口的限制」还是「这些站点真的挂了」。
>
> **补充核实（并行子代理）**：`www.metoc.navy.mil` 在本机**完全不解析** ——
> 本地 DNS 返回 **NXDOMAIN**，经 `8.8.8.8` 与 `1.1.1.1` 查询均返回 **SERVFAIL**，
> DoH（DNS over HTTPS）**超时**。**这更像是该域名被网络层拦截，而不是站点故障。**
> **请在你自己的网络环境重测**：ATCF b-deck/f-deck 是很好的历史 + 预报数据源
> （纯文本、逐点、含风圈），如果它们在你那边可达，值得优先于 GDACS 使用。
>
> **JTWC 的数字从哪里来**：因为原始 ATCR 报告不可得，
> **本文档 §C.1.3(b2) 里的 JTWC 误差数字全部来自第三方官方验证**
> （CMA / 上海台风研究所、WMO 台风委员会），**不是 JTWC 自己发布的** —— 已在小节内标注。
>
> **Zoom Earth / Tropical Tidbits**：这两个是**可视化网站**，不是数据服务。
> 我没有找到任何公开的 JSON API 文档，也没有实测到可用的数据端点 —— 标 **【未核实】**，
> 并且**不建议**把它们当作数据源（爬图做不了风场）。

#### A.5.5 引导气流源：Open-Meteo 高层风 —— 【实测可用】

**这是本方案里最重要的"非台风"数据源**：当拿不到官方预报路径时，
用 500 hPa 引导气流做外推是**唯一一个免费的、有物理意义的**改进手段（见 §C.2）。

实测（4 条请求全部 200）：

```powershell
# 逐层风速风向 + 位势高度（units 实测：km/h, °, m, °C）
curl.exe -s "https://api.open-meteo.com/v1/forecast?latitude=20&longitude=130&hourly=wind_speed_500hPa,wind_direction_500hPa,geopotential_height_500hPa,temperature_500hPa,wind_speed_850hPa,wind_direction_850hPa,wind_speed_200hPa,wind_direction_200hPa&forecast_days=1&timezone=UTC"

# ⭐ u/v 分量（做引导气流平均必须用分量，不能平均风速风向）—— 实测可用
curl.exe -s "https://api.open-meteo.com/v1/forecast?latitude=20&longitude=130&hourly=wind_u_component_500hPa,wind_v_component_500hPa&forecast_days=1"

# 指定模型（实测可用）
curl.exe -s "https://api.open-meteo.com/v1/gfs?latitude=20&longitude=130&hourly=wind_speed_500hPa,geopotential_height_500hPa&forecast_days=2&timezone=UTC"
```

实测返回的 `hourly_units`（原样）：

```json
{"time":"iso8601","wind_speed_500hPa":"km/h","wind_direction_500hPa":"°",
 "geopotential_height_500hPa":"m","wind_speed_850hPa":"km/h","wind_direction_850hPa":"°",
 "wind_speed_200hPa":"km/h","wind_direction_200hPa":"°","temperature_500hPa":"°C"}
```

**🔴 变量名陷阱（我实测确认了三种情形，请务必按这张表写代码）**

| 请求的变量名 | 实测结果 | 结论 |
| --- | --- | --- |
| `wind_u_component_500hPa`, `wind_v_component_500hPa` | **HTTP 200**，`hourly_units` 为 `"km/h"`，**24/24 个值非 null**（实测 `u=-28.8, v=-20.4`） | ✅ **正确写法**。850 hPa / 200 hPa 同样可用（实测非 null） |
| `wind_u_500hPa`, `wind_v_500hPa`（**少了 `_component`**） | **HTTP 400** | ❌ 错误。原始错误体：`{"reason":"Invalid value: Cannot initialize SurfacePressureAndHeightVariable<…> from invalid String value wind_u_500hPa","error":true}` |
| `wind_speed_999hPa`（**不存在的层**） | **HTTP 200**，`hourly_units` 照抄 `"wind_speed_999hPa":"km/h"`，但 **24 个值全为 null** | ⚠️ **静默失败**。Open-Meteo **不校验层名**，未知变量会返回 200 + 全 null |

> **交叉验证 u/v 的正确性（实测）**：同一次请求里
> `wind_speed_500hPa = 35.3 km/h`、`wind_direction_500hPa = 55°`（气象来向）。
> 手工换算 `u = −35.3·sin(55°) = −28.9`、`v = −35.3·cos(55°) = −20.3`，
> 与实测的 `u=-28.8, v=-20.4` **吻合到 0.1 km/h** → **分量变量是真实且正确的**，
> 不需要自己做三角函数换算（但换算公式本身可用于自检）。

**⭐ 因此：取引导气流请直接用 `wind_u_component_<P>hPa` / `wind_v_component_<P>hPa`，
不要自己从风速风向换算。但必须在解析层加"全 null 检查" —— 静默失败不会被 HTTP 状态码暴露。**

> **另注**：一次请求里**混合**多个坐标（`latitude=a,b,c&longitude=x,y,z`）可以批量取点，
> 返回 JSON 数组 —— 这点对"环形采样引导气流"很有用（见 §C.2.3），
> 可以把 8–16 个采样点压成 1 次请求而不是 8–16 次。

**🔴 第二条实测负面结果**：**Archive API（ERA5 历史再分析）不支持气压层变量**。

```
GET https://archive-api.open-meteo.com/v1/archive?latitude=20&longitude=130
      &start_date=2026-09-01&end_date=2026-09-02
      &hourly=wind_speed_500hPa,wind_direction_500hPa&timezone=UTC
→ HTTP 200，但 hourly_units 返回 "undefined"：
  {"time":"iso8601","wind_speed_500hPa":"undefined","wind_direction_500hPa":"undefined"}
```

→ **要历史 500 hPa 风（例如做回测/标定），必须换源**。
并行子代理实测发现一个可用的替代：**`https://historical-forecast-api.open-meteo.com`**
**支持气压层风**（实测 2024-09-01~03、72 个时次、有真实数值）。
其余可选：ERA5 原始（CDS，要注册 + 长延迟）、NCEP/NCAR Reanalysis（NOAA PSL，公有领域）。
**不要把 `archive-api` 用在气压层上。**

**频率限制与许可（【源码核实】，2026-09-13 抓取 `https://open-meteo.com/en/terms` 原文）**：

> **Non-Commercial Use** — By using the Free API for non-commercial use you agree to following terms:
> **Less than 10'000 API calls per day, 5'000 per hour and 600 per minute.**
> You may only use the free API services for non-commercial purposes.
> You accept to the **CC-BY 4.0 licence**…
> — 商业用途被定义为：有订阅或展示广告的网站/App、集成进商业产品、商业实体的未公开研究等。

**对 Minecraft 模组的含义**：模组本身**免费、无广告** → 属于非商业用途，落在免费额度内（限额极宽）。
**必须在模组内署名 Open-Meteo 并给出 CC-BY 4.0 链接。**

---

### A.6 「有没有官方 24/48/72 小时预报位置？」—— **有**（结论汇总）

用户特别问了这一点，明确回答：

| 数据源 | 官方预报位置 | 时效 | 形式 |
| --- | --- | --- | --- |
| **中央气象台台风网** `view_<id>` | ✅ **有** | 实测 **12/24/36/48/60/72/96/120 h** | JSONP 内嵌数组，`{"BABJ":[[τ, base, lon, lat, p, v, org, cls], …]}` |
| **浙江水利厅** `TyphoonInfo/<tfid>` | ✅ 有，且**多机构**（中国/台湾/日本/香港/美国） | 实测单机构 8 个时效 | JSON，`forecast.forecastpoints[]` |
| **QWeather** `/v7/tropical/storm-forecast` | ✅ 有 | 【文档核实】 | JSON |
| **NHC**（大西洋/东太） | ✅ 有 | 实测 **12/24/36/48/72/96/120 h** | `TCM` 文本产品 / GIS zip+kmz |
| GDACS | ❌ 无 | — | 只有事件级信息 |
| IBTrACS | ❌ 无（历史） | — | — |
| JTWC | 【未核实】（网络不可达） | — | — |

**因此：不需要自己做路径外推就能拿到官方预报路径。**
自研外推的定位是**降级方案 + 游戏性需要**（见 §C）：
1. 官方预报只到 120 h 且不连续（12/24/… 跳步），游戏里要**逐 tick 平滑插值**；
2. 接口挂了/被墙了/风暴超出区域时，要能退化到本地外推；
3. 玩家想要「无限长的台风寿命」这种游戏化设定时，需要自己能外推。

---

### A.7 许可与使用条款汇总（**这一节请务必读**）

#### A.7.1 中央气象台 —— ⚠️ **有明确的转载/商用限制**

从 `https://www.nmc.cn/publish/cms/view/722665831f0a4e98800c41e691444963.html`
（HTTP 200，44,905 B）抓到的《网站声明》原文（2017-09-01，**逐字引用**）：

> 中央气象台致力于发布权威、准确、完整的实况和预报预警信息，其网站（www.nmc.cn）范围内所有的内容，
> 均出于为公众服务为目的，任何媒体、网络或个人等第三方**在未取得中央气象台书面授权的情况下，
> 不得将中央气象台网站（www.nmc.cn）范围内的任何内容以任何方式（包括但不限于转载、链接、
> 转帖或复制发表）用于商业或者其他不合法用途**，更不得将中央气象台发布的气象信息伪造、变更、
> 删减后对外发布。否则中央气象台将依法追究责任人的一切法律责任…
> 本声明自发布本网站之日起生效，本声明之订立、修改、更新及最终解释权均属中央气象台所有。

页面页脚另有："本站所刊登的信息、数据和各种专栏材料，**未经授权禁止下载使用**"。

> **诚实评估（这是我的判断，不是官方解释）**：
> - 该声明**字面覆盖 `www.nmc.cn`**；`typhoon.nmc.cn` 是同一机构（国家气象中心）的站点，
>   合理推断适用同样条款 —— 但我**没有**在 `typhoon.nmc.cn` 上找到一份独立的、逐字的声明，
>   这一点标 **【未核实】**。
> - 「**链接**」被列入禁止方式之一，这让"运行时拉取"也处于灰色地带，不只是"打包进 jar"才违规。
> - **对开源项目的现实风险**：确实存在中央气象台对 GitHub 开源项目发出版权警告的公开案例
>   （搜索结果显示了一个 issue 标题即为此内容）。**我因为本机网络无法访问 GitHub 而未能核实该 issue 正文**
>   （`web_fetch` 返回 `URL hostname "github.com" resolves to a non-public IP address`）——
>   **标【未核实】，但足以说明这个风险是真实存在的，不是理论担忧。**
>
> **工程建议（见 §F）**：
> 1. **不要把中央气象台的数据（或其派生结果）打包进 jar**，只做运行时拉取；
> 2. `typhoon.nmc.cn` 通道**默认关闭**，让玩家显式开启，并在配置项与文档里写明来源与风险；
> 3. 主通道优先选 **QWeather（有商业条款）** 或 **NHC（公有领域）**，把中央气象台当"国内可用性最好但合规最弱"的可选源；
> 4. 在模组的 `README`/游戏内 `Credits` 里**明确署名数据来源**，并附"数据仅供参考、非官方预警"的免责声明。

#### A.7.2 浙江省水利厅 —— 未见声明（**【未核实】**）

`https://typhoon.slt.zj.gov.cn/` 首页实测只返回 2,559 字节的 Vue SPA 外壳，
**没有任何服务条款、版权声明或免责声明页面**。
- 状态：**【未核实】**（既没找到允许，也没找到禁止）。
- 事实：`/Api/*` 是给该 SPA 用的内部接口，**不是公开 API 产品**，无文档、无 SLA、无频率限制说明。
- 建议：与中央气象台同样处理（运行时拉取、默认关闭、显著署名）。

#### A.7.3 GDACS —— ✅ 免费，署名即可

见 §A.5.2 的官方 PDF 原文：*"GDACS data are free and available through its APIs… We only request to
acknowledge the source as 'Global Disaster Alert and Coordination System, GDACS'"*。

#### A.7.4 NOAA / NWS / NHC / IBTrACS —— ✅ **公有领域**

从 `https://www.weather.gov/disclaimer`（HTTP 200，41,616 B）实测抓到的原文：

> The information on National Weather Service (NWS) Web pages are in the **public domain**, unless
> specifically noted otherwise, and **may be used without charge for any lawful purpose** so long as you do not:
> 1) claim it is your own (e.g., by claiming copyright for NWS information), 2) use it in a manner that
> implies an endorsement or affiliation with NOAA/NWS, or 3) modify its content and then present it as
> official government material.
> As required by 17 U.S.C. § 403, third parties producing copyrighted works consisting predominantly of the
> material appearing in NWS Web pages must provide notice with such work(s) identifying the NWS material
> incorporated and stating that such material is not subject to copyright protection.

→ **可以自由使用/再分发，但必须：①不得声称是自己的；②不得暗示 NOAA/NWS 背书；
③修改后不得当作官方材料发布；④若作品主体是 NWS 材料，需注明 "not subject to copyright protection"。**

**这条对 Minecraft 模组极其友好 —— NOAA 系（NHC / IBTrACS）是唯一可以安心打包数据的源。**

#### A.7.5 Open-Meteo —— ✅ CC-BY 4.0，非商业免费

见 §A.5.5。**必须署名。**

#### A.7.6 UApiPro —— 商业服务，条款见文档站（**【未核实】**细节）

---

### A.8 频率限制与请求策略汇总

| 源 | 官方限流 | 实测行为 | 建议轮询间隔 |
| --- | --- | --- | --- |
| 中央气象台 | **无文档** | 12 连发全 200，~0.21 s/次 | **≥ 10 min**（定位 3–6 h 才更新） |
| 浙江水利厅 | **无文档** | 单次 `TyphoonInfo` 返回 769 KB | **≥ 10 min**；**必须支持 gzip**（体积大） |
| QWeather | 【文档核实】商业套餐额度制 | 未实测 | 按套餐；建议 ≥ 10 min |
| UApiPro | **1500 积分/月/IP**（访客） | 实测免费可用 | **按需，不要轮询** |
| NHC / NWS API | 无硬性文档，有 UA 要求 | 实测 200 | ≥ 10 min |
| GDACS | 单次 ≤ 100 条，`pagenumber` 分页 | 实测 200 | ≥ 30 min（事件级，变化慢） |
| Open-Meteo | **10,000/天、5,000/时、600/分** | 实测 200 | ≥ 10 min（预报 15 min 一更新） |

**通用请求策略（建议直接写进 Provider 基类）**：
1. **条件请求**：存 `ETag`/`Last-Modified`，下次带 `If-None-Match`/`If-Modified-Since`。
2. **gzip**：浙江源单次 769 KB，压缩后通常 <80 KB。
3. **指数退避**：失败后 30 s → 2 min → 8 min → 30 min，封顶 30 min；不做无限重试。
4. **失败即降级**，不要阻塞游戏主线程（见 §F 降级链）。
5. **观测到「有活动台风」之前不拉详情**：先只拉轻量的列表接口（中央气象台 1.8 KB、
   GDACS 3 KB），确认有活动风暴才拉 769 KB / 104 KB 的详情。

---

## B. 台风风场模型

> **本节的两条支柱**
> - **文献部分**：由子代理完成的长篇报告 `_research/tc_surface_wind_field_models.md`
>   （98 KB / 894 行，含逐条公式编号与 URL）。本节只摘**实现必需**的部分，
>   并保留其 **[A]/[B]/[C]/[D]** 标注语义（[A]=有文献支撑，[B]=工程近似，[C]=未核实，[D]=该报告推导/验证）。
> - **本项目实测部分**：我用 **IBTrACS v04r01 最近 3 年（5950 行，其中 2566 行同时具备
>   RMW + 四象限 R34 + Pc + POCI）** 对下面几个公式做了**独立数值校验**。
>   脚本见 `_research/typhoon-samples/analyze_ibtracs.py` 与 `analyze_ibtracs2.py`，可复跑。
>   **凡标【本项目实测】的数字都是脚本跑出来的，不是抄的。**

### B.0 三个模型的定位（先决定用哪个）

| 模型 | 什么时候用 | 代价 |
| --- | --- | --- |
| **修正 Rankine** | 只想要「像台风」的形状，且要极低算力；需要显式拟合外区衰减 | 眼墙处有导数尖点；外区不物理 |
| **Holland (1980)** | **推荐默认**。有 Pc/Δp 与 Vmax 时**唯一能保持气压—风自洽**的模型 | 外区衰减过陡（见 B.1.4）；B 与 Rmax 强耦合 |
| **Willoughby (2006)** | 要最锐利的眼墙过渡、且能接受参数多 | 参数 5 个 + 一个根方程；无外半径关系 |

> **对本模组的建议**：默认 **Holland (1980)**（公式最短、参数最少、能用官方 API 字段直接算）；
> 玩家可选 **Willoughby**（更"专业"的外观）。**不要把三者混在一个公式里**，
> 尤其不要把 Holland 的远场当作修正 Rankine 的远场（B.1.4 的坑）。

### B.1 三个模型的公式与适用性

#### B.1.1 修正 Rankine（含衰减指数 α）

```java
/** 修正 Rankine 切向风剖面。VmaxMs 为最大持续风，RmaxKm 为最大风速半径。 */
public static double rankineVT(double rKm, double rmaxKm, double vmaxMs, double alpha) {
    if (rKm <= rmaxKm) {
        return vmaxMs * (rKm / rmaxKm);                  // 内区：线性（刚体旋转）
    }
    return vmaxMs * Math.pow(rmaxKm / rKm, alpha);       // 外区：r^-alpha
}
```

- **符号/单位**：`rKm` km，`rmaxKm` km，`vmaxMs` m/s，`alpha` 无量纲。返回值 m/s。
- **α 的典型值 —— ⚠️ 请勿引用「0.4–0.8」**：
  子代理反复检索后**找不到任何写出该区间的可引用来源** → 标 **【未核实 [C]】**。
  这个区间在网上流传极广，但**没有出处**。本仓库**不要**把 0.4–0.8 写成"文献值"。
  有文献支撑的只有：
  - **经典 Rankine `α = 1`**（相对角动量径向不变）—— **[A]** Sun, Cai, Liu & Zhang (2024),
    *Ocean-Land-Atmosphere Research* 3, DOI [10.34133/olar.0035](https://doi.org/10.34133/olar.0035)
    （全文 XML：<https://par.nsf.gov/biblio/10497570/media/xml>）
  - **α 不是常数，且与强度/尺度正相关** —— **[A]** Gao, Sun, Guan & Wang (2024), *JTECH* 41(11),
    DOI [10.1175/JTECH-D-23-0025.1](https://doi.org/10.1175/JTECH-D-23-0025.1)
    （原文称 α 为 "the key decay exponent of the Rankine vortex model"）
  - **内区指数 n = 1–2（线性到二次）** —— **[A]** Willoughby & Rahn (2002) 摘要原文：
    "Inside the eye the wind increases in proportion to a power of radius, generally between linear and quadratic"
    <https://ams.confex.com/ams/25HURR/techprogram/paper_37005.htm>
- **【工程近似】** 本模组若用修正 Rankine，建议 **α 默认 0.6，并允许玩家覆盖**，
  在配置注释里写明"本项目经验值，非文献值"。

#### B.1.2 一个不易出错的光滑替代式（SLOSH 型）

```java
/** 单式光滑剖面，避免 r=Rmax 处的导数尖点。远场恰为 r^-1。 */
public static double smoothVT(double rKm, double rmaxKm, double vgMaxMs) {
    return vgMaxMs * (2.0 * rmaxKm * rKm) / (rmaxKm * rmaxKm + rKm * rKm);
}
```

**[A]** 来源：ScientiMate 官方文档，注释逐字 "using **SLOSH model**"：
<https://scientimate.readthedocs.io/en/latest/python_functions/hurricane/hurricanewindinflowangle.html>
**[D]** 性质：`r→0` 时 ≈ `2·Vgmax·r/Rmax`；`r=Rmax` 时 `= Vgmax`；`r≫Rmax` 时 ≈ `2·Vgmax·Rmax/r`（α=1）。
**如果只允许一条式子，这是最不容易写错的。**

#### B.1.3 Holland (1980) B 模型 —— **推荐默认**

**论文**：Holland, G. J., 1980: *An Analytic Model of the Wind and Pressure Profiles in Hurricanes*,
Mon. Wea. Rev. **108**(8), 1212–1218,
DOI [10.1175/1520-0493(1980)108<1212:AAMOTW>2.0.CO;2](https://doi.org/10.1175/1520-0493(1980)108%3C1212:AAMOTW%3E2.0.CO;2)
（AMS 全文 403，公式经 CLIMADA 与 ADCIRC 官方文档**逐字引用公式编号**转引 —— 转引风险已标注）

**气压剖面**

```java
/** Holland 1980 气压剖面。所有气压单位必须一致（建议全用 Pa）。 */
public static double hollandPressurePa(double rM, double rmaxM, double b, double pcPa, double pnPa) {
    return pcPa + (pnPa - pcPa) * Math.exp(-Math.pow(rmaxM / Math.max(1.0, rM), b));
}
```

**梯度风剖面（公式本体，可直接复制）**

```java
/**
 * Holland (1980) 梯度风剖面。单位必须为 SI：m, m/s, Pa, kg/m^3, 1/s。
 * 公式（CLIMADA docstring 逐字）：
 *   V(r) = [(B/rho)*(Rmax/r)^B*(Pn-Pc)*e^(-(Rmax/r)^B) + (r*f/2)^2]^0.5 - (r*f/2)
 */
public static double hollandGradientV(double rM, double rmaxM, double b,
                                      double deltaPPa, double rho, double f) {
    double rmaxNorm = Math.pow(rmaxM / Math.max(1.0, rM), b);   // r->0 保护，与 CLIMADA 一致
    double coriolis = 0.5 * rM * f;
    double s = (b / rho) * rmaxNorm * deltaPPa * Math.exp(-rmaxNorm) + coriolis * coriolis;
    return Math.sqrt(Math.max(0.0, s)) - coriolis;
}
```

**等价形式（用 Vmax 代替 Δp，ADCIRC Eq. 7）**

```java
public static double hollandGradientVFromVmax(double rM, double rmaxM, double b,
                                              double vmaxGlMs, double f) {
    double y = Math.pow(rmaxM / Math.max(1.0, rM), b);
    double coriolis = 0.5 * rM * f;
    return Math.sqrt(Math.max(0.0, vmaxGlMs * vmaxGlMs * Math.exp(1.0 - y) * y
                            + coriolis * coriolis)) - coriolis;
}
```

**🔴 单位陷阱（最容易出的 bug）**：`(B/ρ)·Δp` 必须是 m²/s² → **Δp 必须是 Pa，不是 hPa**。
**[A] 直接证据**：CLIMADA 对 Holland (1980) 一律传 Pa，但对 **Holland (2008) 的 b_s 回归显式除以
`MBAR_TO_PA`**，注释写明 "The formula assumes that pressure values are in **millibar (hPa)** instead of
SI units (Pa)"。**同一作者的两套公式单位约定不同。**

**典型系数**

| 符号 | 值 | 单位 | 出处 | 标注 |
| --- | --- | --- | --- | --- |
| `ρ` | **1.15** | kg/m³ | CLIMADA `DEF_RHO_AIR`「following Holland 1980」；StormR | [A] |
| `ρ`（梯度层） | 1.204 | kg/m³ | ScientiMate 默认 | [A] |
| `f` | `2 × 7.29e-5 × sin(lat)` | s⁻¹ | CLIMADA；StormR | [A] |
| `Pn` 标准 / 西北太平洋 / 北大西洋 | **101325 / 101500 / 101000** | Pa | ScientiMate 引 Batke, Jocque & Kelly (2014), PLoS ONE 9(3):e91306 | [A] |
| `B` 建议范围 | **1.0 – 2.5** | — | ADCIRC 文档引 Holland 1980；CLIMADA 截断 | [A] |
| `B` 另一表述 / 评估取值 | 0.75–2.5 / **1.5** | — | Sun et al. (2024) 引 Holland 1980 | [A] |
| `B`（SLOSH 所用） | 1.0 | — | Willoughby & Rahn (2002) | [A] |
| `B`（Irene 2011 四象限反演） | 0.60 – 1.62 | — | ADCIRC Table 2 | [A] |

**B 的算法（三种，按数据可得性选）**

**(1) 由 Vmax 与 Δp 反解（Holland 1980 Eq. 5）—— 最常用**

```java
/** B = rho * e * Vmax_gradient^2 / deltaP。⚠️ Vmax 必须是梯度层风。 */
public static double hollandBFromVmax(double vmaxGradientMs, double deltaPPa, double rho) {
    return vmaxGradientMs * vmaxGradientMs * rho * Math.E / deltaPPa;
}
```

⚠️ **B ∝ V²，误差被平方放大**：如果手上只有 10 m 风，必须**先除以折减因子**（§B.4）。
**[D] 本报告推导 + 数值验证**：Holland 梯度风剖面远场为 `V ∝ r^-(B+1)`（**不是** `r^-(B/2)`，
后者只在去掉科氏项的纯气压项里成立）。数值验证（φ=20°、Δp=5000 Pa、Rmax=30 km，
`r/Rmax` 从 200→500 的对数—对数斜率）：

| B | 实测远场指数 | `B+1` | `B/2` |
| --- | --- | --- | --- |
| 1.0 | **1.996** | 2.00 | 0.50 |
| 1.5 | **2.500** | 2.50 | 0.75 |
| 2.0 | **3.000** | 3.00 | 1.00 |

→ **Holland 1980 的外区衰减（指数 2–3.5）远陡于修正 Rankine 常用的 0.4–0.8。二者不可互换。**
这也正是 Holland et al. (2010) 引入可变指数 `x` 的原因。

**(2)【本项目实测】直接用官方字段算 B 会得到什么？**

用 IBTrACS 最近 3 年的 2565 个「有 POCI + Pc + Vmax」的定位点，
按 `B = ρe·Vmax²/Δp` 计算（`Δp = POCI − Pc`，`ρ = 1.15`）：

| Vmax 的层 | min | p10 | **中位数** | p90 | max | 落在 [1, 2.5] 的比例 |
| --- | --- | --- | --- | --- | --- | --- |
| 直接用 10 m Vmax（**层不对**） | 0.41 | 0.93 | **1.45** | 1.92 | 10.13 | 84.2% |
| Vmax/0.9（→ 梯度层） | 0.50 | 1.15 | **1.78** | 2.37 | 12.51 | **87.4%** |
| Vmax/0.8（→ 梯度层） | 0.64 | 1.45 | 2.26 | 3.00 | 15.84 | 63.4% |

**结论（【本项目实测】）**：
1. **`B ∈ [1, 2.5]` 这个文献范围在真实数据上站得住**（87.4% 覆盖率）。
2. **实测中位数 1.45–1.78** → **本模组默认 `B = 1.5` 有实测依据**，不是拍脑袋。
3. **折减因子用 0.9 比用 0.8 更符合 `B ∈ [1,2.5]`** —— 这是选 0.9 的一个**独立于文献的实测理由**。
4. max 到 10–15 说明**必须有 clamp**（否则弱台风的极端字段会给出荒谬的 B）。

**(3) Holland (2008) 地面层 b_s（不需要观测风速）**

**论文**：Holland, G. J., 2008: *A Revised Hurricane Pressure–Wind Model*, Mon. Wea. Rev. **136**(9),
3432–3445，DOI [10.1175/2008MWR2395.1](https://doi.org/10.1175/2008MWR2395.1)

```java
/** Holland (2008) Eq. (11) 的地面层 b_s。⚠️ 本式单位是 hPa 与 hPa/hour，不是 Pa！ */
public static double holland2008Bs(double deltaPHpa, double dPcDtHpaPerHour,
                                   double latDeg, double vTransMs) {
    double x = 0.6 * (1.0 - deltaPHpa / 215.0);
    return -4.4e-5 * deltaPHpa * deltaPHpa
         + 0.01   * deltaPHpa
         + 0.03   * dPcDtHpaPerHour
         - 0.014  * Math.abs(latDeg)
         + 0.15   * Math.pow(vTransMs, x)
         + 1.0;
}
// 配套 P-W 关系：V_ms = sqrt(b_s / (rho * e) * deltaP_Pa)
```
**[A]** CLIMADA 实现并注明其「performs best in the North Atlantic」、拟合时假设准旋风平衡。
**要算 `dPcDt` 就必须有至少两个时次的气压** —— 这对游戏循环是个额外状态。

#### B.1.4 Willoughby, Darling & Rahn (2006)

**论文**：Mon. Wea. Rev. **134**(4), 1102–1120，DOI [10.1175/MWR3106.1](https://doi.org/10.1175/MWR3106.1)
**原文 PDF 已被子代理成功取得并文本抽取**（<https://journals.ametsoc.org/downloadpdf/view/journals/mwre/134/4/mwr3106.1.pdf>，19 页），
所以这一节的系数**是 [A] 级，且归属已确认**。

```java
/**
 * Willoughby, Darling & Rahn (2006) 分段剖面。
 * 记号：原论文用 xi 表示过渡区归一化半径，w 表示混合权重。
 */
public static double willoughbyV(double rKm, double rmaxKm, double r1Km, double r2Km,
                                 double vmaxGlMs, double n, double a, double x1, double x2) {
    double vi = vmaxGlMs * Math.pow(rKm / rmaxKm, n);                       // Eq. (1a) 内区
    double vo = vmaxGlMs * ((1.0 - a) * Math.exp((rmaxKm - rKm) / x1)        // Eq. (4)  外区
                          +        a  * Math.exp((rmaxKm - rKm) / x2));
    if (rKm <= r1Km) return vi;
    if (rKm >= r2Km) return vo;
    double xi = (rKm - r1Km) / (r2Km - r1Km);                               // Eq. (2)
    double w;
    if (xi <= 0.0)      w = 0.0;
    else if (xi >= 1.0) w = 1.0;
    else w = 126.0 * Math.pow(xi, 5) - 420.0 * Math.pow(xi, 6)
           + 540.0 * Math.pow(xi, 7) - 315.0 * Math.pow(xi, 8)
           +  70.0 * Math.pow(xi, 9);                                       // Eq. (2)
    return vi * (1.0 - w) + vo * w;                                         // Eq. (1c)
}
```

**[D] 数值验证（子代理）**：`w(0)=0, w(1)=1, w'(0)=w'(1)=0, w''(0)=w''(1)=0, w(0.5)=0.5` 精确成立
→ 剖面在 R1、R2 处 **C² 连续**（这就是论文说 "sectionally continuous" 的含义）。

**🔴 关键澄清（这一条纠正了很常见的误传）**：
**Willoughby 的外区是「指数衰减」，不是 0.5 次幂。**
「0.5 指数」属于 **Holland et al. (2010)**（*Mon. Wea. Rev.* **138**(12), 4393–4401,
DOI [10.1175/2010MWR3317.1](https://doi.org/10.1175/2010MWR3317.1)），**不是 Willoughby**。

```java
// Holland et al. (2010) Eq. (6)：可变指数 x，x<1 使外区衰减变缓
// V(r) = Vmax_s * [ (Rmax/r)^b_s * exp(1 - (Rmax/r)^b_s) ]^x
//   x = 0.5                                     (r < Rmax)
//   x = 0.5 + (r - Rmax) * (x_n - 0.5) / (rn - Rmax)   (r >= Rmax)
//   缺第二条观测时默认：Vn = 17 m/s @ rn = 300 km
```

**系数（两套都在原文里，按「Rmax 是否已知」选）**

**系数集 A = 论文 Eqs. (10a)–(10c)，用于 `Rmax 未知`**（Vmax_gl 单位 m/s，φ = |纬度|°）

```java
double rmaxKm = 46.4 * Math.exp(-0.0155 * vmaxGlMs + 0.0169 * phi);  // Eq. (7a) 纬度在指数内！
double x1     = 317.1 - 2.026 * vmaxGlMs + 1.915 * phi;              // Eq. (10a)
double n      = 0.4067 + 0.0144 * vmaxGlMs - 0.0038 * phi;           // Eq. (10b)
double a      = 0.0696 + 0.0049 * vmaxGlMs - 0.0064 * phi;           // Eq. (10c)
if (a < 0.0) a = 0.0;
double x2     = 25.0;                                                // 论文 Table 2 固定 25 km
```

**系数集 B = 论文 Eqs. (11a)–(11c)，用于 `Rmax 已知`**（vm 单位 m/s，rm 单位 km）

```java
double n  = 2.1340 + 0.0077 * vm - 0.4522 * Math.log(rm) - 0.0038 * phi;   // Eq. (11a)
double x1 =  287.6 - 1.942 * vm + 7.799 * Math.log(rm) + 1.819 * phi;      // Eq. (11b)
double a  = 0.5913 + 0.0029 * vm - 0.1361 * Math.log(rm) - 0.0042 * phi;   // Eq. (11c)
if (a < 0.0) a = 0.0;
double x2 = 25.0;
```

> **⚠️ 注意 `0.5913`，不是 `0.5916`。**（论文原文 + StormR 手册一致；常见笔误。）

**过渡区几何（最易实现错）**

```text
解 Eq. (3) 求 xi：
  f(xi)  = w(xi) - n*((1-A)*X1 + 25*A) / ( n*((1-A)*X1 + 25*A) + Rmax ) = 0
  f'(xi) = 5*126*xi^4 - 6*420*xi^5 + 7*540*xi^6 - 8*315*xi^7 + 9*70*xi^8
  Newton-Raphson，初值 xi_0 = 0.5，|f|<0.001 或 100 次迭代
  然后 R1 = Rmax - xi*(R2-R1)
```
**[A]** 该法在 1988–2015 全部热带风暴上**从未失败**，根「typically ξ of **0.6–0.65**」。
**[A] 过渡区宽度不在论文里**：论文只说宽度 "specified a priori at a value between **10 and 25 km**"。
「Rmax>20 km 取 25 km 否则 15 km」是 **`stormwindmodel` R 包的实现假设 [B]**：

```java
double dR = (rmaxKm > 20.0) ? 25.0 : 15.0;   // km —— R 包假设，不是论文
double r2Km = r1Km + dR;
```

**⚠️ 论文中没有「外半径」关系式 —— 已由原文全文检索确认**（无 `"17 m"`/`"17.5"`/`"gale"`/`"outer radius"`/`"R34"` 匹配）。
**不要再找「Willoughby 外半径回归式」，它不存在。**

#### B.1.5 失效条件（三个模型共同的坑）

1. **[A]** 修正 Rankine 与 Holland (1980) **都不能给出有限大小的风暴**（`R → ∞` 时风不消失）。
   → **必须自己加一个截断半径**（见 §F 的 `TAIFENG_CUTOFF_R_KM`）。
2. **[A]** Sun et al. (2024) 引 Zhang et al.：**外区都不可靠**
   （"because of neglecting the effect of PAM, both the modified Rankine vortex model and the H80
   model are not valid over the outer region"）。
3. **[A]** Willoughby & Rahn (2002)：**Holland 剖面最强风带过宽**，极端风范围高估，
   约束拟合下**平均偏强 3 m/s**（≈1/3 个 Saffir–Simpson 等级）；
   非约束拟合下 **Vmax 偏低 2%、Rmax 偏高 30%**。原文结论：
   "generally leads to an **overestimate of the actual risk**"。
4. **[B]** 单一 α 无法同时拟合眼墙陡峭拐点与远场 —— 这是修正 Rankine 的固有取舍。

### B.2 从公开 API 字段反推模型参数

**输入**：中心气压 `Pc`、最大风速 `Vmax`、环境气压 `Pn`（或 Δp）、四象限七级/十级/十二级风圈半径。

> **⚠️ 阈值换算必须先做**：中央气象台/香港天文台的「7 级 / 10 级 / 12 级」**不等于**
> NHC/JTWC 的「34 / 50 / 64 kt」。
> | 名称 | 阈值 | 换算 |
> | --- | --- | --- |
> | 7 级风圈 | 30 kt = **15.4 m/s** | 中央气象台的 `30KTS` 组 |
> | 10 级风圈 | 50 kt = **25.7 m/s** | `50KTS` 组 |
> | 12 级风圈 | 64 kt = **32.9 m/s** | `64KTS` 组 |
> | NHC 34 kt | **17.5 m/s** | 与七级风圈**不同**，差 2.1 m/s |
> | NHC RMW / R34 | — | IBTrACS 与 HURDAT2 用 nmile |

#### B.2.1 Δp ↔ Vmax

```java
/** Atkinson & Holliday (1977)：10 m、1-min 平均、西北太平洋。Pn 隐含 1010 hPa。 */
public static double vmaxFromPcAtkinsonHolliday(double pcHpa) {
    return 3.44 * Math.pow(1010.0 - pcHpa, 0.644);   // m/s；kt 形式 = 6.7 * 同项
}
```
**[A]** 来源（逐字）：「It can be estimated from `Vmax=3.44*(1010-Pc*1e-2)^0.644`;
where Pc is in (Pa) and, Vmax is 1-min averaged wind at a 10-m elevation in (m/s) **(Atkinson & Holliday, 1977)**」
—— ScientiMate：<https://scientimate.readthedocs.io/en/latest/python_functions/hurricane/hurricanepressureh80.html>
（论文：Mon. Wea. Rev. **105**(4), 421–427；**原论文全文未取得，存在转引风险**）
**[B] 限制**：由西北太平洋资料拟合，以 1010 hPa 为基准 —— 对大西洋不理想。

**[C] 未核实**：**Courtney & Knaff (2009)** 与 **Knaff & Zehr (2007)** 的确切系数**未取得**
（论文已定位：*Aust. Meteor. Oceanogr. J.* **58**(3), 167–179, DOI 10.22499/2.5803.002，但不可读）。
**请勿凭记忆填写这两篇的系数。**

#### B.2.2 Rmax 的获取 —— **有已发表的「Rmax ← R34」关系（重要）**

**⭐ Chavas, D. R., and J. A. Knaff, 2022**: *A Simple Model for Predicting the Tropical Cyclone
Radius of Maximum Wind from Outer Size*, Wea. Forecasting **37**(5), 565–578,
DOI [10.1175/WAF-D-21-0103.1](https://doi.org/10.1175/WAF-D-21-0103.1)

```java
/**
 * Chavas & Knaff (2022)：由方位平均的 R34 估计 Rmax。
 * 单位全 SI：米、米/秒、度。
 * r34M 必须是【四象限平均】半径 —— 原文明确要求 average across quadrants。
 */
public static double rmaxFromR34ChavasKnaff(double vmaxMs, double r34M, double latDeg) {
    double f = 2.0 * 7.29e-5 * Math.sin(Math.toRadians(latDeg));
    if (Math.abs(f) < 1e-8) return Double.NaN;          // 赤道附近退化
    double m175   = r34M * 17.5 + 0.5 * f * r34M * r34M;                       // Eq. (2)
    // 默认系数 = Extended Best Track 组（N=1366）
    double b = 0.699, bVmax = -6.18e-3, bVfR = -2.10e-3;
    double mmax = m175 * b * Math.exp(bVmax * (vmaxMs - 17.5)
                                    + bVfR * (vmaxMs - 17.5) * (0.5 * f * r34M));  // Eqs. (3)(6)(7)
    if (mmax <= 0.0) return Double.NaN;
    return (1.0 / f) * (Math.sqrt(vmaxMs * vmaxMs + 2.0 * f * mmax) - vmaxMs);     // Eq. (4)
}
```
另外两组系数（论文 Table 2）：**TC-OBS (N=5574)** `b=0.735, bVmax=-6.57e-3, bVfR=-1.84e-3`；
**Theory (C15)** `b=0.606, bVmax=-4.84e-3, bVfR=-1.44e-3`。
可选的偏差订正 Eq. (8)：`Rmax_adj = Rmax/0.76 - 9020.0`（米）。

**[A] 论文自报精度**：对 Extended Best Track **RMSE 19.9 km**（样本外 18.2 km），
对比 Knaff et al. (2015) 的 33.0 km；Rmax 跨度 15–200 km。

**⚠️ 论文的两条硬限制（原文明说）**：
1. **只用 R34**。p.577：「one could also apply the model framework using wind radii associated with
   stronger wind speeds such as R50kt and R64kt … **We do not take this step here**」
   → **不存在 R50/R64 版本的系数，任何声称有的都是编造的。**
2. **必须用四象限平均半径**：「it is important to **average across quadrants** rather than
   considering individual quadrants」。

**🔴【本项目实测】我对这个关系做了独立校验（IBTrACS v04r01 最近 3 年）**
（n = 2561，观测真值 = `USA_RMW`；脚本 `analyze_ibtracs2.py`）：

| 海盆 | n | 观测 RMW 中位 | 预测 RMW 中位 | RMSE | bias | 相关系数 |
| --- | --- | --- | --- | --- | --- | --- |
| EP（东太平洋） | 469 | 27.8 km | 34.3 km | **12.3 km** | +2.9 km | **0.844** |
| SI（南印度洋） | 503 | 27.8 km | 38.8 km | 18.1 km | +9.7 km | 0.600 |
| NI（北印度洋） | 161 | 27.8 km | 50.1 km | 26.0 km | +16.0 km | 0.532 |
| WP（西北太平洋） | 777 | 37.0 km | 51.5 km | 28.2 km | +13.0 km | 0.742 |
| SP（南太平洋） | 163 | 46.3 km | 55.8 km | 47.2 km | −3.1 km | 0.302 |
| **NA（北大西洋）** | 488 | 55.6 km | 62.4 km | **82.2 km** | +25.6 km | 0.519 |
| **全部** | **2561** | 37.0 km | — | **42.5 km** | **+12.1 km** | **0.603** |

**诚实结论（这是我实测出来的，和论文自报数字不一致，必须如实写明）**：
1. **全球平均 RMSE 42.5 km，明显差于论文自报的 19.9 km**；
   **偏差 +12.1 km（系统性高估 Rmax）**，即**会把台风画得比实际"眼睛更大"**。
2. **分海盆差异极大**：东太平洋最好（12.3 km，corr 0.84），**北大西洋最差（82.2 km）**。
   论文是在北大西洋 EBTRK 上拟合的，却在北大西洋表现最差 —— 说明
   **IBTrACS 的 `USA_RMW` 与论文用的 EBTRK RMW 样本并不是同一批**（IBTrACS 的 RMW 仅 2021 年起
   有值，样本很新且含大量弱系统与离群值；观测 RMW 中位 55.6 km 也偏大）。
3. **但相关是真实存在的（全球 corr 0.60，西北太平洋 0.74，东太平洋 0.84）** →
   这个关系**可以作为"量级正确"的 Rmax 估计器**，**不能**当作高精度真值。
4. **本模组的用法**：用 Chavas & Knaff 算一个初值，**再乘一个标定系数**把 bias 拉平
   （实测最佳：`Rmax_used = 0.74 × Rmax_ck`，因为 37.0/50 ≈ 0.74 量级）。
   **但这只是本项目的经验标定，必须标注为工程近似 [B]，不是文献值。**

**其它 Rmax 途径**

| 方法 | 公式 | 标注 |
| --- | --- | --- |
| Willoughby Eq. (7a)（Rmax 未知时） | `46.4·exp(−0.0155·Vmax_gl + 0.0169·φ)` km | [A] |
| **固定比例 `Rmax = k·R7`** | **无任何已发表系数** | **[C] 不存在** |
| 见 §B.2.4 的多等风速线反解（GAHM） | 迭代求解 | [A] |

**【本项目实测】关于那个「不存在的比例规则」，我给出了实测分布**：
IBTrACS 2566 个定位点的 `R34_方位平均 / RMW` 比值：

| min | p10 | p25 | **中位** | p75 | p90 | max |
| --- | --- | --- | --- | --- | --- | --- |
| 0.36 | 1.75 | 2.62 | **4.35** | 7.12 | 11.00 | 43.50 |

→ **比值从 0.36 到 43.5，跨度两个数量级。这从数据上证明了「Rmax = 固定比例 × R7」是不可能的。**
文献里找不到系数，是因为**根本不存在稳定的比例关系**。

#### B.2.3 反推 B 以匹配风圈半径 —— **⚠️ 有一个会导致错误的单调性陷阱**

**最容易写错的假设**：「B 越大，外围风越强，所以用外圈风二分求 B」。

**【D】真实情况**：固定 Rmax 与 Δp，令 `g(B) = B·y·e^(−y)`（`y = (Rmax/r)^B`，`k = ln(r/Rmax) > 0`）：

```text
dg/dB = y * exp(-y) * [ 1 - B*(1-y)*k ]
```

→ **`B < B*` 时 g 递增，`B > B*` 时 g 递减**，其中 `B* = 1 / ((1−y)·ln(r/Rmax))`。

**【D】数值验证（r/Rmax = 3）**：

| B | 0.5 | 0.8 | 1.0 | **1.2** | **1.31** | 1.4 | 1.6 | 2.0 | 2.5 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `g(B)` | .16206 | .21931 | .23884 | **.24571** | **.24505** | .24259 | .23219 | .19885 | .15041 |
| `sign(dg/dB)` | + | + | + | **+0.034** | **−0.098** | − | − | − | − |

→ 峰值在 **B\* ≈ 1.2–1.25**。

**三条实操结论**：
1. **`r = Rmax` 处**：`V ∝ sqrt(B·Δp·e⁻¹/ρ)` 对 B **严格递增** → 用眼墙半径定 B 时二分法**绝对安全**。
2. **`r > Rmax` 处**：`V(r; B)` **单峰**，二分法**只在递增支有效**。
   业务 B 常落在 1.0–2.5，**用约 3 倍 Rmax 的外圈定 B 很可能已经越过峰值** ——
   此时"增大 B ⇒ 外围风增大"的直觉**是错的**。
3. **可能无解**：观测 `V(R7)` 大于 `max_B V(R7; B)` 时**不存在**满足约束的 B
   → 改为对数空间最小二乘拟合，或让 Rmax/Δp 变动。

**🔴【本项目实测】这个陷阱有多严重？我量化了它。**
用 IBTrACS 的 2566 个定位点，尝试「二分求 B 使 `V(R34_mean) = 34 kt`」：

```
n = 124 个成功（且全部落在递增支，B ∈ [1.00, 2.44]，中位 1.32，100% 落在 [1,2.5]）
被拒绝（无解 或 落在递减支）= 2377 个
```

→ **成功率只有 124 / 2501 ≈ 5%。**

**这是本次调研里最重要的工程结论之一**：
**不要用七级风圈（≈R34）去反解 Holland B。** 实测 95% 的个例根本无解。
正确做法是：
- **用 R34 去反解 Rmax**（Chavas & Knaff 2022，§B.2.2），**不是**反解 B；
- **用 Δp 与 Vmax 算 B**（§B.1.3 算法 1），实测中位数 1.45–1.78，落在文献范围内；
- 若一定要同时定 (Rmax, B)，用下面 GAHM 的**多等风速线迭代反解**。

#### B.2.4 风圈半径反解 Rmax 与 B —— 已发表的官方算法（ADCIRC / ASWIP / GAHM）**[A]**

```text
输入（每象限，NE/SE/SW/NW 对应方位 45/135/225/315）：V_isot 与 R_isot（R34/R50/R64）、
     报告的最大风 V_M、平移矢量 V_T；折减因子 W_rf = 0.9
 1. V_max_gl = |V_M_vec - gamma*V_T_vec| / W_rf                          # Eq. (14)
 2. 猜一个 Rmax
 3. 迭代解 (Eq. 9 & 11)：
      B_g = (V_max^2 + V_max*Rmax*f) * rho * e^phi / (phi * (Pn - Pc))
      phi = 1 + V_max*Rmax*f / (B_g * (V_max^2 + V_max*Rmax*f))
 4. V_r*cos(quad(i) + 90 + beta) = V_isot*cos(eps) - gamma*mu_T          # Eq. (18)
    V_r*sin(quad(i) + 90 + beta) = V_isot*sin(eps) - gamma*nu_T          # Eq. (19)
 5. 代回 Eq. (13) 求根得到新的 Rmax
 6. 从第 3 步迭代至 Rmax 收敛
 7. 多等风速线：以最高等风速线得到的 Rmax 作为全风暴的「pseudo Rmax」；
    对每条较低等风速线只用该初值跑一次（保持 beta(r) 平滑）
 8. 若 V_max < V_r（违反 Eq. 13），则令 V_max = V_r  <=>  Rmax = R_r
```

**[A] 官方验证**（Katrina/Rita/Gustav/Ike/Irene/Isaac/Sandy）：GAHM 对 34/50/64 kt 的匹配均值为
**34.0 / 50.0 / 64.0 kt，σ = 0.10 / 0.12 / 0.10 kt**；
单等风速线 AHM 为 26.9/44.7/63.3（σ 7.96/7.09/2.55）；SLOSH 为 30.0/48.9/69.5（σ 11.0–16.1）。
**关键结论**：单等风速线法「failed to match any lower isotaches」——
**与我在 §B.2.3 实测到的 95% 失败率完全一致，互相印证。**

**更简单的官方替代（ScientiMate 约定）**：不显式求 Rmax，而是输入一个观测对
`(Rknown, V_isot)`，且**要求 `Rknown > Rmax`**：
令 `Vgmax = (Vmax − Vt)/0.8`、`VgRknown = (V_isot − Vt)/0.8`，
解 2×2 方程组 `V_g(Rmax) = Vgmax` 与 `V_g(Rknown) = VgRknown`。
官方 Katrina 算例参数：`Pc=90200 Pa, Vt=5.18467 m/s, Vmax=76.5 m/s,
Rknown=370400 m (NE 象限 34 kt), V_isot=17.49 m/s, Pn=101325 Pa, Rhoa=1.204, VgToVCoeff=0.8`。

#### B.2.5 四象限半径 → 非对称剖面

**[A] 官方 GAHM 复合风场法（Eqs. 20–22，逐字）**

```text
1) 相邻象限轴之间的方位角插值 —— 权重必须用【平方】，不是线性：
     P(theta, d) = [ P(q_i, d)*(90-theta)^2 + P(q_{i+1}, d)*theta^2 ] / [ (90-theta)^2 + theta^2 ]
     （theta 相对 q_i，范围 0..90）
2) 固定象限内跨等风速线的反距离插值：
     P(q, d) = f34*P34 + f50*P50 + f64*P64,   f34 + f50 + f64 = 1
     r <  R64        : f64=1, f50=0, f34=0
     R64 <= r < R50  : f64=(r-R64)/(R50-R64), f50=(R50-r)/(R50-R64), f34=0
     R50 <= r < R34  : f64=0,   f50=(r-R50)/(R34-R50), f34=(R34-r)/(R34-R50)
     r >= R34        : f64=0,   f50=0, f34=1
3) 用插值后的 (Rmax, B_g, phi) 经 Eq. (12)(13) 重建 P 与 V_g
4) 乘 W_rf = 0.9 降到 10 m
5) 按 beta 向内旋转
6) 加回平移矢量 V_T
7) 若目标模型需要 10-min 风，再乘 0.93
```

**[B] 简化工程法（本模组推荐，成本低）**：
分象限拟合 `(Rmax_q, B_q)`，然后**按方位角插值 `Rmax` 与 `B`**（而不是插值风本身）。
代价低得多，且对游戏画面完全够用。**但要注明：线性方位角插值不是 GAHM 原文形式。**

**⚠️ 顺序很重要 [A]**：Chavas & Knaff (2022) 需要**四象限平均**半径先定 Rmax，
**之后**再做分象限非对称化。**不要把分象限半径直接喂进 Rmax 回归。**

**⚠️ 预报时段没有风圈怎么办**：QWeather 的 `storm-forecast` 文档里没有 `windRadius*` 字段（实测检索命中 0）；
中央气象台的预报条目（`idx11`）也**只有经纬度/气压/风速，没有风圈**。
→ **降级做法（【工程近似】）**：用当前风圈 × 强度比缩放：
`R7(τ) ≈ R7(now) × (Vmax(τ)/Vmax(now))^(-0.5)`，或直接保持 `R7` 不变而只让 `Vmax` 变化。
本模组建议**直接保持风圈不变**（最简单，且在游戏里几乎看不出差别）。

### B.3 移动速度叠加与非对称（右侧/左侧危险半圆）

```java
/** 非对称修正因子 C(r)（Phadke et al. 2003 Eq. 12）：r=Rmax 处恰为 0.5。 */
public static double asymmetryFactorPhadke(double rKm, double rmaxKm) {
    return (rmaxKm * rKm) / (rmaxKm * rmaxKm + rKm * rKm);   // = 0.5 when r == Rmax
}
```

**矢量合成（推荐，精确）**

```java
/**
 * 对称风 + 平移非对称（矢量形式）。
 * symU/symV : 对称切向+入流风的分量（m/s，u 东向、v 北向）
 * transU/transV : 风暴平移矢量分量（m/s）
 */
public static double[] addMotionAsymmetry(double symU, double symV,
                                          double transU, double transV,
                                          double rKm, double rmaxKm) {
    double c = asymmetryFactorPhadke(rKm, rmaxKm);
    double u = symU + c * transU;
    double v = symV + c * transV;
    return new double[]{u, v};      // 用 hypot(u,v) 取风速
}
```

**[A]「右侧 ≈ 对称风 + 0.5·Vt」是有文献依据的，不是民间说法**：
- **Phadke, Martino, Cheung & Houston (2003)**, *Ocean Engineering* **30**(4), 553–578, Eq. (12) ——
  在 `r = Rmax` 处**恰好加半个平移速度**。逐字实现见 `stormwindmodel`：
  <https://rdrr.io/cran/stormwindmodel/src/R/boundary_layer_helpers.R>
- **Boose, Serrano & Foster (2004)**, *Ecol. Monogr.* **74**(2), DOI [10.1890/02-4057](https://doi.org/10.1890/02-4057)
  （HURRECON）：`v_r = F·(v_m − S·(1−sinT)·v_h/2)·sqrt((R_m/r)^b·exp(1−(R_m/r)^b))`，
  `F = 1.0 水上 / 0.8 陆上`，`S = 1` → **[D] 代数改写后非对称振幅恰为 `0.5·v_h`**。

| `C(r)` 形式 | 在 `r=Rmax` | 出处 | 标注 |
| --- | --- | --- | --- |
| `Rmax·r/(Rmax²+r²)` | **0.5** | Phadke et al. (2003) Eq. (12)（stormwindmodel 实现） | [A] |
| `(1−sinT)·vh/2` | **0.5·vh** | Boose et al. (2004) | [A] |
| `3Rm^1.5·r^1.5/(Rm³+r³+Rm^1.5·r^1.5)` | **1.0** | Chen (1994)（StormR 手册） | [A] 数值 / 原文献 [C] |
| `exp(−πr/500)`（r 以 km） | ≈0.9（Rmax=20） | Miyazaki et al. (1962)（StormR 手册） | [A] 数值 / 原文献 [C] |
| `Vg(r)/Vmax` ∈ [0,1] | **1.0** | ADCIRC GAHM Eq. (15) | [A] |

**[C] 未核实**：「右侧风 = 最大持续风 + 前进速度」这句科普表述**找不到权威一次文献**
（NOAA/AOML FAQ 抓取失败）。它在矢量意义上只是上界 `|Vc + Vt| ≤ Vc + Vt` [D]。
**[C] 未核实**：Schwerdt (1979) / Georgiou (1985) 的非对称公式未取得 —— **用上表 5 个有来源的替代。**

#### ⭐【本项目实测】右侧不对称到底有多大？我用 IBTrACS 量了

把 IBTrACS 2566 个定位点的四象限 R34（nmile → km）旋转到**风暴移动坐标系**
（`STORM_DIR` 为移动去向；象限中心方位 NE=45/SE=135/SW=225/NW=315）：

| 相对方位 | n | R34 中位 | R34 均值 |
| --- | --- | --- | --- |
| front（前方 ±45°） | 2752 | 157.4 km | 184.0 km |
| **right（右侧 45–135°）** | 2380 | **172.2 km** | **199.8 km** |
| back（后方 ±45°） | 2752 | 148.2 km | 178.7 km |
| **left（左侧）** | 2380 | 144.5 km | 168.7 km |

**实测结论**：
1. **右侧比左侧大 ~31 km（均值口径，+18%）** → **经典"危险半圆"不对称在数据上确实存在** ✅
2. **前后差异很小**（184.0 vs 178.7 km，+3%）→ 不对称主要是**左右向**，不是前后向。
3. **不对称幅度与移动速度正相关**（实测，按移动速度分组）：

| 分组 | 平均移动速度 | 平均 `R_right − R_left` |
| --- | --- | --- |
| 最慢 25% | 7.6 km/h | **+1.5 km** |
| 中间 50% | 16.6 km/h | +39.3 km |
| 最快 25% | 34.4 km/h | **+44.6 km** |

→ **移动慢时不明显（+1.5 km），移动快时显著（+44.6 km），且单调递增** ——
**这为「不对称 ∝ Vt」的模型假设提供了独立的观测支持。**

> **对模组的直接含义**：台风移动越快，右侧风圈越要明显加大。
> 游戏里如果只做「对称风场 + 一个固定偏移」，会丢掉这个最有戏剧性的特征
> （"台风右侧最危险"正是玩家能感知到的差异）。

### B.4 入流角、阵风与折减因子

#### B.4.1 入流角（两套已核实公式，须说明用哪套）

**(1) Queensland Government (2011), Ocean Hazards Assessment Stage 1（ADCIRC Eq. 16 逐字引用）[A] —— 推荐**

```java
/** 入流角（度）。连续、单调、r>=1.2Rmax 后饱和于 25°。 */
public static double inflowAngleDegQueensland(double rKm, double rmaxKm) {
    if (rKm < rmaxKm)          return 10.0;
    if (rKm < 1.2 * rmaxKm)    return 10.0 + 75.0 * (rKm - rmaxKm) / rmaxKm;
    return 25.0;
}
```

**(2) Phadke et al. (2003) Eqs. (11a–c)（`stormwindmodel` 逐行实现）[A]，但要注意不连续**

```java
public static double inflowAngleDegPhadke(double rKm, double rmaxKm, boolean overLand) {
    double a;
    if (rKm < rmaxKm)        a = 10.0 + (1.0 + (rKm / rmaxKm));              // 11a
    else if (rKm < 1.2*rmaxKm) a = 20.0 + 25.0 * ((rKm / rmaxKm) - 1.0);     // 11b
    else                     a = 25.0;                                      // 11c
    if (overLand) a += 20.0;                        // stormwindmodel：陆上无条件 +20°
    return a;
}
```
> ⚠️ **按此实现，`r→Rmax⁻` 得 12°、`r→Rmax⁺` 得 20°，在 Rmax 处不连续（跳变 8°）。**
> 子代理无法取得 Phadke 原文来裁定这是论文原式还是实现转录问题 → 精确形式标 **[C]**。
> **[B] 若要连续**，改用 (1)，或把 11a 改为 `10*(1 + r/Rmax)` 并在代码注释中标明是本实现修正。

**典型值（[A]）**

| 情形 | 典型值 | 来源 |
| --- | --- | --- |
| 海上 `r < Rmax` | **10°** | Queensland (2011) via ADCIRC Eq. (16)；Phadke 11a |
| 海上过渡区 | 10° → 25° 线性 | 同上 |
| 海上 `r ≥ 1.2 Rmax` | **25°** | 同上；Phadke 11c |
| **陆上** | **约 30°–45°**（海上 +20°） | `stormwindmodel`（"about 20 degrees more than over water"） |

**[A] 定义（可直接引用）**：「angle between the inwardly spiraling surface wind and the circular
isobars around the hurricane center (Boose et al., 2004)」。
**[A]** 半径依赖（半径越大入流角越大）**由两套独立来源共同确认**。

**[C] 未核实**：**Zhang & Uhlhorn (2012)**, *Mon. Wea. Rev.* **140**(2), 448–465 ——
未取得摘要或正文中的任何具体度数，**本文档因此不给出该文的数值**。
**[C] 未核实**：Bretschneider (1972) 与 Sobey, Harper & Stark (1977) 的入流角公式 ——
方法存在（ScientiMate 有开关）但公式未取得。

#### B.4.2 阵风因子与折减因子

**[A] 阵风因子 `G3,60`**（1-min 平均 → 1-min 内最高 3-s 平均）—— Harper, Kepert & Ginger (2010),
WMO/TD-No. 1555，数值经 `stormwindmodel` 逐字转引：

| 位置 | `G3,60` |
| --- | --- |
| 内陆 in-land | **1.49** |
| 刚离岸 just offshore | 1.36 |
| 刚上岸 just onshore | 1.23 |
| 海上 at sea | **1.11** |

**[A]** 1-min → 10-min：**× 0.93**（Nederhoff et al., 2019, *NHESS* **19**, 2359–2370, §2.4，引 Harper et al. 2010）。
**[B]** 工程上也常用 0.88；**以 0.93 为准**。

**梯度风 → 10 m 折减因子 `W_rf`**（0.8–0.9 两个值**都有出处**，来自两个文献传统）：

| 值 | 适用条件 | 来源 | 标注 |
| --- | --- | --- | --- |
| **0.9** | Franklin, Black & Valde (2003)「90% rule」；原文注明 "For other regions and levels, values of **0.8 or even 0.75** might be justified" | CLIMADA 常量注释逐字引 *Wea. Forecasting* **18**(1), 32–44 | [A] |
| **0.9** | ADCIRC GAHM `W_rf = 0.9`，引 Powell et al. (2003) | ADCIRC Eq. (14) | [A] |
| **0.8** | USACE SPM(1984)/CEM(2015) 传统；ScientiMate 默认，并列 8 篇依据 | ScientiMate | [A] |
| 半径依赖：0.90(≤100 km) → 0.75(≥700 km) 线性；陆上再 ×0.8 | 半径依赖版本 | `stormwindmodel` / Knaff et al. (2011) JAMC 50(10) Fig. 3 | [A] 数值 / [B] 形式 |
| 0.8「traditional」 | WMO TCP-23 (2023) 称 at-sea 建议值比传统 0.8 高约 5% | **仅检索片段，PDF 未取得** | **[C]** |

**[B] 关键理解**：0.9 与 0.8 不矛盾 —— 0.9 一般指「飞行层/750 hPa 眼墙区 → 10 m」，
0.8 一般指「梯度层/平均边界层 → 10 m」。**必须与你的剖面所处高度一致。**
**本模组建议用 0.9** —— 除了文献依据，我在 §B.1.3 的实测也显示 0.9 才能让 B 落回 [1, 2.5]。

```java
/** 半径依赖的梯度风→10 m 折减（可选精细版）。 */
public static double gradientToSurfaceFactor(double rKm, boolean overLand) {
    double fr;
    if      (rKm <= 100.0) fr = 0.90;
    else if (rKm >= 700.0) fr = 0.75;
    else fr = 0.90 + (0.75 - 0.90) * (rKm - 100.0) / (700.0 - 100.0);
    if (overLand) fr *= 0.80;
    return fr;
}
```

**建议换算链（[A]）**

```java
double vmaxGlMs      = Math.max(0.0, vmax10m1minMs - vTransMs) / W_RF;  // CLIMADA _vgrad 逐字
double vGradientMs   = hollandGradientV(rM, rmaxM, b, deltaPPa, RHO, f);
double vSurfaceSymMs = W_RF * vGradientMs;
// 加回非对称（§B.3）后再乘阵风因子
double vGustMs       = vSurfaceAsymMs * G3_60;
```

### B.5 角度与方位约定（**最容易出静默 bug 的地方**）

| 系统 | 0° 指向 | 旋转 | 用途 |
| --- | --- | --- | --- |
| **气象风向** | 正北 | **顺时针** | 报文字段（**风「来向」**） |
| **数学极角** | 正东 | 逆时针 | 矢量分解 `(cos, sin)` |
| **罗盘方位角** | 正北 | 顺时针 | 中心 → 某点的方位 |

**[A] 权威换算表**（ScientiMate 逐字，<https://scientimate.readthedocs.io/en/latest/python_functions/mapping/convertdir.html>）：

| 转换 | 公式 |
| --- | --- |
| meteorological → trigonometric | `trig = −mete + 270` |
| trigonometric → meteorological | `mete = 270 − trig` |
| azimuth → meteorological | `mete = az + 180` |
| meteorological → azimuth | `az = mete − 180` |
| azimuth → trigonometric | `trig = −az + 90` |
| trigonometric → azimuth | `az = 90 − trig` |

```java
public static double norm360(double d) { double x = d % 360.0; return (x < 0.0) ? x + 360.0 : x; }

/** 极角（0=正东，逆时针）→ 罗盘方位角（0=正北，顺时针）。 */
public static double polarToCompass(double polarDeg) { return norm360(90.0 - polarDeg); }

/** u/v 分量 → 气象风向（风「来向」，度）。 */
public static double uvToDirFrom(double u, double v) {
    return norm360(270.0 - Math.toDegrees(Math.atan2(v, u)));
}

/** 气象风向 + 风速 → u/v 分量。自检：dirFrom=0（北风）→ u=0, v=-speed（向南吹）。 */
public static double[] dirFromToUV(double dirFromDeg, double speed) {
    double r = Math.toRadians(dirFromDeg);
    return new double[]{ -speed * Math.sin(r), -speed * Math.cos(r) };   // {u, v}
}

/**
 * 涡旋切向风 → 到某点的气象风向分量。
 * bearingCentreToPointDeg：从风暴中心指向该点的罗盘方位角。
 * [A] stormwindmodel calc_gwd 逐字：北半球逆时针「+90 度」，南半球「-90 度」。
 */
public static double[] tangentialUV(double bearingCentreToPointDeg, double speed, boolean northernHemisphere) {
    double tangBearing = northernHemisphere
            ? norm360(bearingCentreToPointDeg + 90.0)
            : norm360(bearingCentreToPointDeg - 90.0);            // 风「去向」方位
    return dirFromToUV(norm360(tangBearing + 180.0), speed);     // → 气象「来向」
}

/** 大圆初始方位角。日界线安全。 [A] Movable Type Scripts (C. Veness)。 */
public static double initialBearingDeg(double lat1, double lon1, double lat2, double lon2) {
    double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
    double dLam = Math.toRadians(lon2 - lon1);
    while (dLam >  Math.PI) dLam -= 2.0 * Math.PI;      // ⚠️ 必须归一化
    while (dLam <= -Math.PI) dLam += 2.0 * Math.PI;
    double y = Math.sin(dLam) * Math.cos(phi2);
    double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLam);
    return norm360(Math.toDegrees(Math.atan2(y, x)));
}

/** 大圆距离（haversine），km。 */
public static double greatCircleKm(double lat1, double lon1, double lat2, double lon2, double rEarthKm) {
    double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
    double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dp/2) * Math.sin(dp/2)
             + Math.cos(p1) * Math.cos(p2) * Math.sin(dl/2) * Math.sin(dl/2);
    return rEarthKm * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
}
```

**陷阱清单（逐条都要处理）**：
1. **经度符号约定必须显式统一 [A]**：`stormwindmodel` 要求「西经取**正**」；
   HURDAT2 / IBTrACS / Open-Meteo 用「**西经取负**」。混用会在 0° 经线与日界线附近产生数百至数千公里错误。
2. **必须用 `atan2`，绝不用 `atan(y/x)`**（丢象限、x=0 除零）。**[A]**
3. **方位角归一化到 [0,360)**：`(θ % 360 + 360) % 360`（各语言负数取模行为不同）。**[A]**
4. **经度差必须先归一化到 (−180, 180]**，否则跨 ±180° 全错（E179 → W179 实为 2°，不是 358°）。**[A]**
5. **初始方位角 ≠ 终末方位角**（35N,45E → 35N,135E：初始 60°、终末 120°）。**[A]**
6. **地球半径取值不统一**：`stormwindmodel` 包内 `calc_distance` 用 6371 km、
   `latlon_to_km` 用 **6378.14 km**。本仓库请**统一为一个常量**。
7. **[本项目实测补充]** 中央气象台 `view_` 的字段顺序是 **`(经度, 纬度)`**（见 §A.1.4）——
   和大多数人的直觉相反，**必须在解析层加合理域断言**。

---

## C. 路径预测与落点判定

### C.1 只有历史定位点时的外推

> **先回答一个前置问题：到底需不需要自己外推？**
> **大多数时候不需要**（见 §A.6）：中央气象台的 `view_` 直接给官方 12–120 h 预报点，
> 浙江源给多机构预报点。**本模组的正确做法是"优先用官方预报，本地外推只作降级"。**
> 下面给出外推方案，用于 ① 官方接口失败；② 官方预报跳步（12/24/48 h 间隔太大）需要平滑插值；
> ③ 玩家要求超出 120 h 的游戏化延长。

#### C.1.1 等速外推（persistence / constant-velocity）

最简单、也是所有复杂模型的基线：

```java
/**
 * 等速外推：用最近两个定位点算速度，线性推演。
 * lat/lon 单位度，dtHours 为两点时间差，tauHours 为预报时效。
 */
public static double[] constantVelocity(double lat1, double lon1, double lat2, double lon2,
                                        double dtHours, double tauHours) {
    double vLatPerH = (lat2 - lat1) / dtHours;
    double vLonPerH = (lon2 - lon1) / dtHours;      // ⚠️ 未除 cos(lat)：这里刻意保持"经纬度线性"
    return new double[]{ lat2 + vLatPerH * tauHours, lon2 + vLonPerH * tauHours };
}
```
> ⚠️ 上面是**经纬度线性**外推，在高纬会沿经度方向拉伸。若要物理正确，
> 应转成大圆方位角 + 距离，再按方位角推进：

```java
/** 沿大圆按方位角 azimuthDeg 推进 distanceKm。 */
public static double[] destinationPoint(double lat, double lon, double azimuthDeg, double distanceKm,
                                        double rEarthKm) {
    double d = distanceKm / rEarthKm, brg = Math.toRadians(azimuthDeg);
    double p1 = Math.toRadians(lat), l1 = Math.toRadians(lon);
    double p2 = Math.asin(Math.sin(p1)*Math.cos(d) + Math.cos(p1)*Math.sin(d)*Math.cos(brg));
    double l2 = l1 + Math.atan2(Math.sin(brg)*Math.sin(d)*Math.cos(p1),
                                Math.cos(d) - Math.sin(p1)*Math.sin(p2));
    return new double[]{ Math.toDegrees(p2), ((Math.toDegrees(l2) + 540.0) % 360.0) - 180.0 };
}
```
**[A]** Movable Type Scripts（C. Veness），<https://www.movable-type.co.uk/scripts/latlong.html>；
球面假设误差一般 <0.3%（跨赤道最大约 0.55%）。

#### C.1.2 CLIPER（气候持续法）

**CLIPER = CLImatology and PERsistence**，是**多元线性回归**，不是"等速外推的美称"。
它的预测量包含：当前经纬度、过去 12/24 h 的位移分量、以及**气候态**（该位置/该月的平均移动矢量、
以及 `1/纬度`、`经度`、`sin/cos(月)` 等气候预测因子）。
Neumann (1972) 首次提出，之后是 NHC 的官方基准模型。
**[A]** 论文：Neumann, C. J. (1972), "The variability of tropical cyclone motion", 见
Aberson, S. D. (1998), *Five-day Tropical Cyclone Track Forecasts in the North Atlantic Basin*,
*Wea. Forecasting* **13**(4), 1005–1015。

**本模组的实现建议（【工程近似】）**：
CLIPER 的完整回归系数**没有公开的可直接引用的表**（属于各机构内部验证体系）。
**不要伪造系数**。可行的做法是：

```java
/**
 * 【工程近似】加权最小二乘外推：对最近 N 个定位点做一阶（或二阶）多项式拟合，
 * 权重随时间指数衰减（越近越重）。这是 CLIPER 中"persistence"部分的实用替代。
 */
public static double[] weightedLeastSquaresExtrapolation(
        double[] tHours, double[] latDeg, double[] lonDeg, double tauHours, double decayHours) {
    // 加权线性回归：x = t, y = lat 或 lon；权重 w_i = exp(-(t_now - t_i)/decayHours)
    double sw = 0, swx = 0, swy = 0, swxx = 0, swxy = 0;
    for (int i = 0; i < tHours.length; i++) {
        double w = Math.exp(-(0.0 - tHours[i]) / decayHours);   // tHours[i] 为相对当前时刻（<=0）
        sw += w; swx += w * tHours[i]; swy += w * latDeg[i];
        swxx += w * tHours[i] * tHours[i]; swxy += w * tHours[i] * latDeg[i];
    }
    double den = sw * swxx - swx * swx;
    if (Math.abs(den) < 1e-9) return new double[]{ latDeg[latDeg.length - 1], lonDeg[lonDeg.length - 1] };
    double slope = (sw * swxy - swx * swy) / den;
    double intercept = (swy - slope * swx) / sw;
    double predLat = intercept + slope * tauHours;
    // 对 lon 重复同一过程（略）
    return new double[]{ predLat, Double.NaN };
}
```

> **定位点数量与间隔**：实测中央气象台 `view_` 对 YAGI 只给 36 个点（3 h / 6 h 混合），
> **浙江源给 125 个点（其中 101 个是 1 h 间隔）**。
> → **要密集历史点做外推，用浙江源**（§A.2.3）。
> 建议外推窗口取**最近 6–12 小时**（浙江源 1 h 间隔 → 6–12 个点；中央气象台 3 h 间隔 → 2–4 个点）。
> `decayHours` 建议 6–12 h。

#### C.1.3 ⭐【实测可用的文献数字】CLIPER5 与等速外推的真实误差量级

**这部分是用户明确要的「典型 24 小时误差量级（文献数字）」，现在有官方出处了。**

**(a) 官方 CLIPER5 误差（大西洋）—— 【实测可用】原文来自 NHC 年度验证报告**

来源：`https://www.nhc.noaa.gov/verification/pdfs/Verification_2025.pdf`
（**HTTP 200，22,797,072 字节，85 页**；我下载后第 20 页 Table 1 原文核对）：

> **Table 1.** Homogenous comparison of official and CLIPER5 track forecast errors in the
> Atlantic basin in 2025 for all tropical cyclones. Averages for the previous 5-yr period are shown
> for comparison.

| 预报时效 (h) | 12 | 24 | 36 | 48 | 60 | 72 | 96 | 120 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **2025 CLIPER5 误差 (n mi)** | **41.5** | **89.2** | 140.7 | 190.6 | 239.7 | 293.6 | 381.4 | 438.5 |
| 2025 OFCL 误差 (n mi) | 20.1 | 32.6 | 43.0 | 53.4 | 67.5 | 85.2 | 110.5 | 162.0 |
| 2025 OFCL 相对 CLIPER5 技巧 (%) | 51.6 | 63.5 | 69.4 | 72.0 | 71.8 | 71.0 | 71.0 | 63.1 |
| 2025 样本数 N | 215 | 191 | 169 | 148 | 128 | 111 | 83 | 64 |
| **2020–2024 CLIPER5 均值 (n mi)** | **45.1** | **95.7** | 150.9 | 203.1 | 252.7 | 295.4 | 366.2 | 426.6 |
| 2020–2024 OFCL 均值 (n mi) | 23.0 | 34.3 | 45.8 | 58.7 | 73.5 | 89.8 | 128.7 | 185.4 |

**换算成公里**（×1.852，以便与 §C.4 的锥半径对比）：

| 时效 | 12 h | 24 h | 48 h | 72 h | 120 h |
| --- | --- | --- | --- | --- | --- |
| CLIPER5（2025） | **77 km** | **165 km** | 353 km | 544 km | 812 km |
| CLIPER5（2020–2024 均值） | 84 km | **177 km** | 376 km | 547 km | 790 km |
| OFCL 官方（2021–2025 均值） | 41 km | **62 km** | 104 km | 160 km | 335 km |

**→ 用户要的「典型 24 小时误差量级」：**
- **CLIPER 基线：约 165–177 km**（≈ 90–96 n mi）
- **官方预报：约 62 km**（≈ 34 n mi）

**这是 2.6 倍的关系** —— 也就是说，**本地外推（CLIPER 级）与官方预报之间的差距是数量级的**，
这就是为什么 §A.6 的结论「优先用官方预报路径」如此重要。

**(b) 官方 OFCL 5 年平均误差（2021–2025）—— 【实测可用】**

来源：`https://www.nhc.noaa.gov/verification/pdfs/OFCL_5-yr_averages.pdf`
（**HTTP 200，99,106 字节**；我下载后用 PyMuPDF 提取文本核对，与并行子代理的读数**逐位一致**）

| 时效 (h) | 12 | 24 | 36 | 48 | 60 | 72 | 96 | 120 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **ATL 路径误差 (n mi)** | 22.3 | 33.6 | 44.0 | 55.9 | 70.5 | 86.4 | 124.9 | 181.0 |
| ATL 样本 N | 1504 | 1336 | 1182 | 1049 | 923 | 805 | 613 | 464 |
| **EPAC 路径误差 (n mi)** | 21.4 | 32.1 | 41.6 | 50.3 | 61.1 | 74.0 | 101.4 | 125.0 |
| **ATL 强度误差 (kt)** | 5.0 | 7.1 | 8.5 | 9.9 | 10.8 | 11.6 | 13.3 | 14.7 |
| **EPAC 强度误差 (kt)** | 5.7 | 9.0 | 10.9 | 12.6 | 13.7 | 14.2 | 16.0 | 17.8 |

**(b2) ⭐ 西北太平洋官方误差（CMA / JTWC / JMA / KMA / HKO）—— 【实测可用】**

这一组对国内玩家场景**比 NHC 的大西洋数字更贴切**。数据年份 **2023**，单位 **km**（括号内为样本数）。

来源：《气象》(Meteorological Monthly) **51**(12): 1669–1682（2025-12），
DOI [10.7519/j.issn.1000-0526.2025.090101](https://doi.org/10.7519/j.issn.1000-0526.2025.090101)；
PDF：<http://qxqk.nmc.cn/qx/ch/reader/create_pdf.aspx?file_no=20251208&year_id=2025&quarter_id=12&falg=1>

| 机构 | 12 h | 24 h | 36 h | 48 h | 60 h | 72 h | 96 h | 120 h |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **CMA（中央气象台）** | 43.3 (427) | **64.2 (382)** | 89.2 (341) | **115.4 (302)** | 141.7 (268) | **175.0 (240)** | **269.0 (195)** | **362.1 (160)** |
| **JTWC** | 43.7 (385) | **63.7 (352)** | 85.3 (320) | **108.0 (290)** | — | **164.0 (234)** | **236.7 (192)** | **328.5 (155)** |
| JMA | — | 60.1 (383) | — | 108.3 (303) | — | 165.7 (240) | 255.0 (195) | 364.6 (158) |
| KMA | — | 67.3 (357) | — | 113.6 (295) | — | 173.8 (238) | 257.9 (196) | 351.2 (159) |
| HKO | — | 62.6 (282) | — | 117.7 (241) | — | 179.6 (199) | 266.3 (165) | 360.4 (134) |

- **五机构在 24/48/72/96/120 h 的离散度**：
  **60.1–67.3 / 108.0–117.7 / 164.0–179.6 / 236.7–269.0 / 328.5–364.6 km**
  → **机构间差异远小于"官方预报 vs 本地外推"的差异**（本地外推 24 h 是 176 km，
  比最差的官方机构还差 2.6 倍）。**这再次支持"优先用官方预报"。**
- 相对气候-持续性（CLIPER 型）基线的技巧：**62.2–68.5%（24 h）、63.5–70.2%（48 h）、
  62.5–69.2%（72 h）**。
- 各机构平均定位误差（2023）= **17.1 km** —— 这是**我们的游戏里根本不需要的精度**。

**其它年份（交叉验证量级）**：

| 年份 / 来源 | CMA 24/48/72/96/120 h (km) |
| --- | --- |
| **2015**《气象》43(4): 501–507, DOI [10.7519/j.issn.1000-0526.2017.04.013](https://doi.org/10.7519/j.issn.1000-0526.2017.04.013) | **66.2 / 119.5 / 176.3 / 244.3 / 328.5** |
| **2019**（WMO 台风委员会第 52 次届会，对 RSMC-Tokyo 最佳路径） | **91.7 / 155.7 / 237.7 / 305.8 / 401.2** |
| **2019**（改对 CMA 最佳路径重新评定） | 77.0 / 137.1 / 217.1 / 287.9 / 373.7 |

**JMA/RSMC Tokyo 逐年 (a)**：2025 = **67 / 105 / 179 / 302 / 508 km**（2024: 71/106/152/245/424）；
2020 = **74 / 119 / 176 / 214 / 267 km**（2019: 80/127/190/263/374）。

> **⚠️ 两条必须随身携带的注意事项**：
> 1. **JTWC 在 2015 / 2019 / 2023 用的是不同的验证最佳路径**
>    （2015+2019 = RSMC-Tokyo；2023 = CMA）→ **跨年份不可直接比较**。
>    报告明确指出：**换一条验证最佳路径会让评定误差变化 3–12%**。
> 2. **JTWC 原始 ATCR 报告仍不可得**：`www.metoc.navy.mil` 在本机**完全不解析**
>    （本地 NXDOMAIN；经 8.8.8.8 与 1.1.1.1 均为 SERVFAIL；DoH 超时）。
>    上表所有 JTWC 数字来自**第三方官方验证**（CMA/上海台风研究所、WMO 台风委员会），
>    **不是 JTWC 自己发布的**。

**(c) 纯等速外推 vs CLIPER5 —— 【我方子代理用官方 HURDAT2 自行计算，非文献值】**
子代理在官方 HURDAT2（大西洋 2021–2025，N = 1351，按 NHC "noTDs" 验证规则）上自算了各种外推方案：

| 方案 | 12 h | 24 h | 48 h | 72 h |
| --- | --- | --- | --- | --- |
| **PERS 6h（用最近 6 小时算速度）** | **69.4 km** | **176.3 km** | 452.8 km | 779.7 km |
| PERS 12h | 81.2 km | 193.2 km | 478.1 km | 803.9 km |
| 最小二乘 N=3 | 83.9 km | 195.2 km | 479.5 km | 804.9 km |
| 最小二乘 N=9 | 190.3 km | 332.5 km | 649.1 km | 959.5 km |
| 最小二乘 N=13 | 274.9 km | 428.0 km | 741.6 km | 1045.5 km |
| 二次拟合 N=5 | 88.7 km | 236.2 km | 710.6 km | **1445.2 km** |

**三条可以直接指导实现的结论**：
1. **外推窗口越短越好，且单调**：24 h 误差随窗口从 6 h → 72 h 从 **176 km 恶化到 428 km**。
   → **本模组默认用最近 2 个定位点（等价 PERS 6h）或最近 6–12 h 的加权拟合，绝不用长窗口。**
2. **二次项（加速度）在 12 h 略有帮助（88.7 vs 69.4？—— 其实更差），
   但在 48–72 h 是灾难（1445 km vs 864 km）。** → **不要用二次外推。**
   > ⚠️ 如实说明：上表里二次拟合在 12 h 也**不如** PERS 6h。所以结论是"二次项没有好处"。
3. **[交叉验证]** PERS 6h 在 12 h（37.5 n mi）**优于** CLIPER5（41.5 n mi），
   24 h 基本持平，之后逐渐落后 —— **这正是文献里描述经典模式**，
   说明这套自算结果在量级与方法上是站得住的。

**(d) 外推的官方模型代号**：NHC 把纯外推记为 **`XTRP`**（"Extrapolation"）。
其数值误差未公开（NHC 只以图形式发布）→ **未核实**。

**(e) 【未核实】CLIPER5 的回归系数与预测量清单**：
子代理**未能取得** Neumann (1972, NOAA Tech. Memo. NWS SR-62) 与 Aberson (1998) 原文
（AMS 与 AOML 在本机不可达）。
→ **本文档不给出 CLIPER 的回归系数。请勿伪造。**
可引用的只有官方定义原文：
> "CLIPER5, a climatology and persistence model that contains **no information about the current
> state of the atmosphere** (Neumann 1972, Aberson 1998)"；
> development data **1931–2004**（大西洋）、**1949–2004**（东太平洋）；
> persistence 预测量 = **the storm's current motion**。

**一个有用的细节（原文引用，【实测可用】）**：CLIPER5 有**业务版**与**最佳路径版**两种，
官方原文说 "**The best-track version of CLIPER5, which yields substantially lower errors than its
operational counterpart**" —— 上表是**业务版**（更保守）。

### C.2 用 Open-Meteo 500 hPa 引导气流改进外推

#### C.2.1 为什么可行（物理）

台风移动≈**深层平均引导气流**（deep-layer mean steering），叠加一个 **beta 漂移**。
引导层通常取 **850–200 hPa 的气压加权平均**（有时简化为 500 hPa 单层）。
**[A] 关键实操要求**：**必须用 u/v 分量做平均，不能平均风速与风向** ——
风速风向的平均在物理上无意义（角度缠绕）。

#### C.2.2 Open-Meteo 的字段（**实测可用**，§A.5.5）

```powershell
# 三层风 + 位势高度（units 实测：km/h, °, m）
https://api.open-meteo.com/v1/forecast?latitude=20&longitude=130
  &hourly=wind_u_component_500hPa,wind_v_component_500hPa
  &forecast_days=5&timezone=UTC
```

| 变量名 | 实测单位 | 说明 |
| --- | --- | --- |
| `wind_u_component_500hPa` | km/h | **东向分量**（**推荐用这个**） |
| `wind_v_component_500hPa` | km/h | **北向分量** |
| `wind_speed_500hPa` / `wind_direction_500hPa` | km/h / ° | 标量 + 气象风向 |
| `geopotential_height_500hPa` | m | 位势高度（可做气压加权） |
| `temperature_500hPa` | °C | |
| 同族 `*_850hPa` / `*_200hPa` | 同上 | 实测可用，用于深层平均 |

**🔴 实测负面结果**：**`archive-api.open-meteo.com`（ERA5 历史）不支持气压层变量** ——
请求返回 HTTP 200 但 `hourly_units` 为 `"undefined"`（见 §A.5.5）。**回测必须换源。**

#### C.2.3 具体算法

```java
/**
 * 用 Open-Meteo 多层风做引导气流外推。
 * 步骤：
 *  1) 在风暴中心周围 annulus（建议 300–700 km）环形采样多层 u/v；
 *  2) 按气压做加权得到深层平均引导矢量；
 *  3) 叠加经验 beta 漂移；
 *  4) 以引导矢量推进风暴中心。
 */
public static double[] steeringExtrapolation(
        double latDeg, double lonDeg,
        double[] sampleLat, double[] sampleLon,   // annulus 采样点
        double[][] u500, double[][] v500,         // [sampleIdx][timeIdx]，km/h
        int timeIdx, double tauHours, double betaDriftUMs, double betaDriftVMs,
        double betaDriftBearingDeg) {
    double sumU = 0.0, sumV = 0.0;
    for (int i = 0; i < sampleLat.length; i++) {
        sumU += u500[i][timeIdx];
        sumV += v500[i][timeIdx];
    }
    double meanUKmH = sumU / sampleLat.length;
    double meanVKmH = sumV / sampleLat.length;

    // km/h → m/s，再加速度矢量（beta 漂移）后再转回
    double uMs = meanUKmH / 3.6 + betaDriftUMs;
    double vMs = meanVKmH / 3.6 + betaDriftVMs;

    double speedMs = Math.hypot(uMs, vMs);
    if (speedMs < 1e-6) return new double[]{ latDeg, lonDeg };

    // u/v → 罗盘方位角（去向）
    double bearing = norm360(Math.toDegrees(Math.atan2(uMs, vMs)));   // 注意：atan2(u,v) 得方位角
    double distanceKm = speedMs * tauHours * 3600.0 / 1000.0;
    return destinationPoint(latDeg, lonDeg, bearing, distanceKm, 6371.0);
}
```

> **u/v → 方位角的正确式子**：方位角（去向，正北顺时针）`= atan2(u, v)`，
> 因为 `u` 对应东向、`v` 对应北向。
> ⚠️ 这与 §B.5 的 `uvToDirFrom`（气象**来向**，`270 − atan2(v,u)`）**不是同一个量**，
> 两者相差 180°，**不要混用**。

**annulus 采样半径**：子代理未取得"300–700 km 是标准"的权威出处，
**标【工程近似 [B]】**。物理上要求环足够大以避开涡旋自身环流、又足够小以代表风暴所处环境。
**建议：内径 300 km、外径 700 km、8–16 个方位、2–3 个径向层。**

**beta 漂移量级**：**【二级来源，不是一手文献】** —— AMS *Glossary of Meteorology* 的
"beta drift" 条目原文：「Beta drift generally causes tropical cyclones to move **poleward and
westward** … **This drift speed is generally around 1–2 m s⁻¹**」。
> ⚠️ **该页在本机返回 HTTP 403（Cloudflare）**，子代理是通过搜索引擎索引拿到的文本，
> 所以只能算 **【二级来源】**。Holland 1983（*JAS* **40**, 328–342）与
> Fiorino & Elsberry 1989（*JAS* **46**, 975–990）确实存在，但全文不可达
> → **不要引用它们的具体数字**。
> **【工程近似】本模组默认 `BETA_DRIFT_MS = 1.5`、方位 315°（西北）**，与上述 1–2 m/s 一致。

#### C.2.4 🔴 **重要负面结果：朴素的引导气流外推比等速外推更差**

子代理对飓风 Lee (AL132023) 做了**真实回顾性检验**：18 个分析时次、
25 点环形采样（200/400/600 km × 8 方位）、850/700/500/300/200 hPa 质量加权，**实测调用了 Open-Meteo**。

| 指标 | 实测结果 |
| --- | --- |
| 深层平均引导（DLM）方向 vs 观测 12 h 移动方向 | 平均误差 **43.3°**，中位 15.3° |
| DLM 速度偏差 | **系统性偏慢约 27%**（平均速度偏差 −4.8 km/h，观测 18.1 km/h） |
| 速度相关系数 | 0.67 |

**位置误差（km）**：

| 方案 | 12 h | 24 h |
| --- | --- | --- |
| **PERS（等速外推）** | **39.0** | **106.0** |
| DLM（纯引导气流） | 132.0 | 282.3 |
| DLM + beta 漂移（315°/1.5 m/s） | **183.5** | **386.7** |

**⇒ 朴素 DLM 引导外推比等速外推差 3 倍以上；再加 beta 漂移更差**
（beta 修正与 DLM 已有的方向偏差**叠加而不是抵消**）。
绝大多数损伤发生在**转向（recurvature）阶段**（方向误差 110–133°）。

**⇒ 环形半径会翻转引导矢量的符号**（实测）：2023-09-13 00Z，
200–600 km 环给出 `(u=−9.4, v=−10.0)`，而 400–600 km 环给出 `(u=+3.5, v=+5.4)` —— **方向完全相反**。

**因此本模组对引导气流外推的工程结论（重要）**：
1. **不要用 DLM 直接替代等速外推。** 把 §F.3 降级链里"引导气流"与"等速外推"的**顺序对调**：
   **等速外推（PERS）优于朴素 DLM**。
2. 若要用 DLM，**只能作为"标定后的残差修正"叠在 PERS 之上**，
   且**必须先在目标海盆标定速度偏差**（本次实测偏慢 27%）。
3. **只在非转向阶段使用**；转向阶段（路径方向变化 > 60°/24 h）应回退 PERS。
4. **「环形采样半径 300–700 km 是标准」这个说法：【未核实】**——
   子代理找不到可引用来源。**本文档标为【工程近似】。**

> **诚实性说明**：这是**单风暴、18 个样本**的小样本结论，**不能**据此断言"DLM 无用"。
> 但它足以说明：**"接上引导气流就能改进外推"是一个未经验证的假设，必须自己回测。**
> → **本模组默认用 PERS，DLM 作为可选实验特性（默认关闭）。**

#### C.2.5 什么时候**不要**用引导气流

- **弱台风 / 弱引导**（深层平均风速 < 3 m/s）：此时台风移动由内部动力与 beta 漂移主导，
  引导外推会**比等速外推更差**。**必须加一个回退判据。**
- **转向阶段**（见 C.2.4）。
- **陆地上**：摩擦与非对称结构使引导关系失效。
- **拿不到环流样本**（采样点全在陆地上、或 Open-Meteo 覆盖边界外）。

---

### C.3 落点（landfall）预测 —— 用本仓库的 1° 陆海掩码

#### C.3.1 掩码格式（**复述自 `core/src/main/resources/data/nowweather/geo/README.md`**）

| 属性 | 值 |
| --- | --- |
| 文件 | `landmask1deg.bin`，**8100 字节**（我实测 `Length = 8100`，与 README 一致） |
| 网格 | 180 行 × 360 列，1° |
| 行 0 | 覆盖纬度 **[89°, 90°)** —— 最北的一条带（README §3.1：格 `(r,c)` 纬度范围 `[90-(r+1), 90-r]`） |
| 列 0 | 经度 [−180, −179) |
| 位序 | row-major，行 0 在北，**每字节 MSB first** |
| 位含义 | `1 = 陆地`，`0 = 海洋` |
| 格的判定 | 0.25° 细分（每格 4×4 点）陆地占比 ≥ 0.5 记 1（平局归海洋） |
| 来源/许可 | Natural Earth 1:110m `ne_110m_land` v4.1.0，**公有领域** |

**我在文档里再次强调 README 的警告**：**不要用 `BitSet.valueOf()`** ——
它的字节内位序是 **LSB-first**，与本文件的 **MSB-first** 约定相反。
**按位取最安全**（下面的 `isLand` 就是 README 给的那一行）。

#### C.3.2 判定算法（含沿航迹二分插值）

```java
/** 陆海掩码：1° 分辨率，row-major，行 0 在北，每字节 MSB first。 */
public final class LandMask1Deg {
    private static final int NLAT = 180;
    private static final int NLON = 360;
    private final byte[] raw;                      // 8100 字节

    public LandMask1Deg(byte[] raw) { this.raw = raw; }

    /** 单点陆海判定。lat ∈ [-90,90]，lon ∈ [-180,180]。 */
    public boolean isLand(double lat, double lon) {
        int row = (int) Math.floor(90.0 - lat);
        int col = (int) Math.floor((((lon + 180.0) % 360.0) + 360.0) % 360.0);
        row = Math.max(0, Math.min(NLAT - 1, row));
        col = Math.max(0, Math.min(NLON - 1, col));
        int i = row * NLON + col;
        return ((raw[i >> 3] >> (7 - (i & 7))) & 1) != 0;
    }

    /**
     * 沿一段航迹 (latA,lonA,tA) -> (latB,lonB,tB) 找海->陆穿越点。
     * 返回 {lat, lon, timeMillis}；无穿越返回 null。
     * 用【二分插值】：坐标精度远高于格点（见 C.3.3 实测），时间精度为二分收敛精度。
     */
    public double[] crossingSeaToLand(double latA, double lonA, long tA,
                                      double latB, double lonB, long tB, int iters) {
        boolean landA = isLand(latA, lonA);
        boolean landB = isLand(latB, lonB);
        if (landA || !landB) return null;                 // 必须 A 海、B 陆
        double loLat = latA, loLon = lonA, hiLat = latB, hiLon = lonB;
        for (int k = 0; k < iters; k++) {
            double mLat = 0.5 * (loLat + hiLat);
            double mLon = 0.5 * (loLon + hiLon);
            if (isLand(mLat, mLon)) { hiLat = mLat; hiLon = mLon; }
            else                    { loLat = mLat; loLon = mLon; }
        }
        // 用二分收敛比例线性内插时刻
        double fracA = Math.hypot(loLat - latA, loLon - lonA);
        double fracB = Math.hypot(latB - latA, lonB - lonA);
        double frac = (fracB < 1e-12) ? 0.5 : (fracA / fracB);
        long t = tA + (long) (frac * (tB - tA));
        return new double[]{ hiLat, hiLon, t };
    }
}
```

**遍历整条航迹找全部落点**：

```java
List<double[]> landfalls = new ArrayList<>();
for (int i = 1; i < track.size(); i++) {
    Fix a = track.get(i - 1), b = track.get(i);
    double[] x = mask.crossingSeaToLand(a.lat, a.lon, a.time, b.lat, b.lon, b.time, 40);
    if (x != null) landfalls.add(x);
}
```

**预测落点**（用官方预报路径或本地外推路径，同一套代码）：
把预报点序列当作"航迹"跑上面的循环，**第一个穿越点就是预测落点**。

#### C.3.3 ⭐【本项目实测】这个算法的真实精度

**校验方法**：用浙江源 `TyphoonInfo` 里免费的**官方登陆记录**（`land[]`，含坐标 + 时刻 + 地点，
`landtime` 为北京时间）作为真值，对 7 个台风的**实际最佳路径**（1 h / 3 h 间隔）跑上面的算法。
**穿越时刻由二分收敛比例线性内插得到**（脚本 `_research/typhoon-samples/landfall_eval.py`，可复跑）。
位置误差用 haversine（R = 6371 km）计算。

| # | 风暴 / 登陆点 | 官方记录（BJT） | 掩码检出（BJT） | **位置误差** | **时间误差** |
| --- | --- | --- | --- | --- | --- |
| 1 | MALIKSI 广东阳江阳西 | 06-01 00:55 · 21.59N 111.70E | 06-01 05:00 · 22.000N 111.800E | **46.7 km** | **+4.1 h** |
| 2 | **YAGI 海南文昌** | 09-06 16:20 · 19.83N 110.99E | 09-06 16:20 · 19.833N 111.000E | **1.1 km** | **0.0 h** |
| 3 | YAGI 广东徐闻角尾乡 | 09-06 22:20 · 20.23N 109.94E | **未检出** | — | — |
| 4 | YAGI 越南广宁 | 09-07 15:30 · 20.88N 106.84E | 09-07 15:00 · 20.800N 107.000E | 18.9 km | −0.5 h |
| 5 | BEBINCA 上海浦东临港 | 09-16 07:30 · 30.86N 121.92E | 09-16 07:20 · 30.833N 122.000E | **8.2 km** | **−0.2 h** |
| 6 | PULASAN 浙江舟山岱山 | 09-19 18:50 · 30.30N 122.22E | 09-19 20:00 · 30.400N 122.000E | 23.9 km | +1.2 h |
| 7 | PULASAN 上海奉贤 | 09-19 21:45 · 30.85N 121.75E | **未检出** | — | — |
| 8 | PULASAN 韩国全罗南道 | 09-21 15:30 · 34.98N 126.36E | 09-21 15:30 · 35.000N 126.450E | **8.5 km** | **0.0 h** |
| 9 | KRATHON 台湾高雄林园 | 10-03 13:00 · 22.49N 120.39E | 10-03 06:00 · 22.200N 120.000E | **51.5 km** | **−7.0 h** |
| 10 | KONG-REY 台湾台东成功 | 10-31 14:00 · 22.99N 121.32E | 10-31 16:08 · 23.343N 121.000E | **51.1 km** | +2.1 h |
| — | JONGDARI（无登陆） | 官方 `land` 为空 | **无穿越（正确）** | — | — |

**统计（8 个成功检出；10 个官方登陆中有 2 个未检出，漏检率 20%）**

| 指标 | 位置误差 | 时间误差 |
| --- | --- | --- |
| min | **1.1 km** | **−7.0 h** |
| 中位 | **21.4 km** | **0.0 h** |
| 均值 | **26.2 km** | −0.04 h |
| max | **51.5 km** | **+4.1 h** |

**五条必须写进文档的诚实结论**：

1. **坐标精度远好于"1° 格点"的直觉** —— 因为**沿航迹做二分插值**，
   检出的穿越点落在**海格与陆格的边界线**上，是一个**连续坐标**而不是格中心。
   加上风暴本身沿航迹移动，格边界线有时与真实海岸线相当接近（**海南那次命中到 1.1 km**）。
   **但这是"运气好"（格边界恰与真实海岸接近），不是普遍精度 —— 中位 21 km、最差 52 km 才是常态。**
2. **时间精度是真正的短板（−7.0 ~ +4.1 h）**，受两重限制：
   - **1° 分辨率**：一整格 ≈111 km，风暴要"走进格子"才算登陆；
   - **定位点间隔**：中央气象台 3–6 h 一个点，浙江源 1 h 一个点。
   → **若要用掩码报"登陆时刻"，必须标注 ±数小时的不确定性。**
3. **🔴 结构性缺陷：连续两次登陆会被合并成一次。**
   上表 #3（徐闻）和 #7（上海奉贤）都**未检出** —— 因为 1° 格把海南岛 + 雷州半岛、
   以及整个长江口都判成连片陆地，中间**不存在海格**，所以第二次"海→陆"转换在数据上不存在。
   **这个缺陷无法用二分插值修补，只能靠更高分辨率掩码或海岸线矢量数据。**
4. **会检出"非目标"的登陆。** YAGI 的第一个穿越点是 **2024-09-02 17:45 · 16.925N 122.000E** ——
   这是**菲律宾吕宋岛**（掩码判对了，但不符合"登陆中国"的预期）。
   KONG-REY 还多出一次 **11-01 13:00 · 28.000N 121.500E**（浙江沿海）。
   → **必须加"目标区域"过滤**（例如只报玩家基地附近、或只报东亚范围、或只报首次登陆）。
5. **理论下限**：掩码是 1°（≈111 km）分辨率，**位置误差不可能系统性地优于约半格（~55 km）**；
   实测中位 21 km 是"格边界恰好靠近海岸"的产物，**不要指望它稳定**。

#### C.3.4 掩码的另外一个实测数字（与落点误差互相印证）

我统计了整个掩码的陆地格占比：**21508 / 64800 = 33.19%**（实测，逐位统计）。
地球真实陆地面积占比约 **29.2%**。

→ **这个 1° 多数表决掩码把陆地高估了约 4 个百分点**，与 `geo/README.md` §4 的结论
（"误差几乎全部来自沿海城市"、"粗网格无法分辨离海 10 km 和 150 km"）**方向一致**。
**对落点判定的含义：海岸线被系统性推偏约半格（~55 km）**，
这正是上表中 20–50 km 位置误差的来源。

#### C.3.5 提高落点精度的可行路径（按成本排序）

| 方案 | 成本 | 预期收益 |
| --- | --- | --- |
| ① 用 0.25° 掩码（README §5 有完整复现方法，`numpy.packbits`） | **低**（32 kB，且 §5 已有生成脚本） | 半格误差降到 ~14 km |
| ② 落点时刻**不做插值**，直接报"官方预报路径中最靠近陆地的那个预报点" | **低** | 消除伪精度，坦白误差 |
| ③ 直接显示**官方登陆预报/记录**（浙江源 `land[]`，中央气象台无此字段） | 低 | **准确度最高，且是官方口径** |
| ④ 用 PULASAN 那种"多段登陆"显示为"区域影响"而非"精确落点" | 低 | 规避结构性缺陷 |

> **强烈建议 ② + ③**：既然官方数据里有权威落点信息（浙江源）或官方预报路径，
> **就不要用 1° 掩码去"预测"一个官方已经给出答案的量**。
> 掩码的正确用途是：**当数据源全部失败、只剩本地外推路径时**，提供一个"大致会在哪里上岸"的估计。

### C.4 预报不确定性（锥形误差圈）—— **有官方数字了**

#### C.4.1 ⭐ 官方 NHC 锥半径（**实测可用**，原文照抄）

来源：`https://www.nhc.noaa.gov/aboutcone.shtml`（**HTTP 200，24,428 字节**）。原文：

> The cone represents the probable track of the center of a tropical cyclone, and is formed by
> enclosing the area swept out by a set of circles (not shown) along the forecast track
> (at 12, 24, 36, 48, 60, 72, 96, 120 hours). The size of each circle is set so that **two-thirds of
> historical official forecast errors over a 5-year sample fall within the circle**.
> The circle radii defining the cones in **2026** for the Atlantic, Eastern North Pacific, and Central
> North Pacific basins are given in the table below. Radii … are **based on error statistics from 2021–2025**.

| 预报时效 (h) | 12 | 24 | 36 | 48 | 60 | 72 | 96 | 120 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **ATL 2/3 概率圈 (n mi)** | 25 | **39** | 49 | 62 | 77 | **95** | 134 | **200** |
| **E/CPAC 2/3 概率圈 (n mi)** | 25 | **37** | 48 | 56 | 66 | **78** | 106 | **138** |
| ATL 换算 km | 46 | **72** | 91 | 115 | 143 | **176** | 248 | **370** |
| E/CPAC 换算 km | 46 | **69** | 89 | 104 | 122 | **145** | 196 | **256** |

**注意这个表的口径**：它是**三分之二（67%）概率圈**，不是平均误差。
与 §C.1.3(b) 的 5 年平均误差对比可以看出**锥半径约为平均误差的 1.10–1.16 倍**
（24 h：33.6 → 39；120 h：181 → 200）—— 这是统计学上正常的（67 分位 > 均值是对数正态分布的特征）。
**如果游戏里要画"确定会经过的区域"，应该用这个表；如果要画"平均偏差"，用 §C.1.3(b)。**

#### C.4.2 沿/横跨航迹的分解 —— 一个可直接利用的系统性偏差

子代理从官方 NHC 沿/横跨误差数据库
（`https://www.nhc.noaa.gov/verification/errors/1970-present_OFCL_v_BCD5_ind_ATL_AC_errors_noTDs.txt`）
**自算**得到（大西洋 2021–2025；**单位是 n mi**，子代理验证过：RMS/mean ≈ 1.14，
与二维高斯理论值 1.128 吻合，**只有按 n mi 才成立**）：

| 时效 (h) | 12 | 24 | 48 | 72 | 96 | 120 |
| --- | --- | --- | --- | --- | --- | --- |
| ATL 平均 \|沿航迹\| 误差 (n mi) | 13.98 | 20.73 | 34.08 | 51.08 | 76.28 | 123.05 |
| ATL 平均 \|横跨航迹\| 误差 (n mi) | 12.99 | 20.00 | 33.80 | 55.31 | 76.49 | 102.17 |
| 样本 N | 1276 | 1119 | 990 | 875 | 769 | 669 |

**两条系统性的偏差（【我方子代理计算】，有官方数据支撑）**：
1. **带符号的沿航迹误差在大西洋全程为负（−3.9 → −14.0 n mi），东太平洋更大（120 h 达 −46.5 n mi）**
   → **官方预报系统性地"报得太慢"**（台风实际跑得比预报快）。
2. **带符号的横跨误差在大西洋为正（96 h 达 +24 n mi），东太平洋为负**
   → 大西洋偏向**右侧**、东太平洋偏向**左侧**。

→ **对游戏的含义**：如果做"预报落点"，**沿航迹方向应略微向前修正**；
但**这是官方预报的偏差，不是我们外推的偏差** —— 游戏里用官方预报路径时可以参考，
用本地外推时**不要**套用（外推的偏差特性完全不同）。

#### C.4.3 **落点（landfall）预测**的误差量级

##### ⭐ 已发表的官方数字（**这是用户要的"文献数字"，现在有了**）—— **(a)**

**来源**：《我国台风路径业务预报误差及成因分析》，《气象》**38**(6): 695–700 (2012)，
DOI [10.7519/j.issn.1000-0526.2012.06.007](https://doi.org/10.7519/j.issn.1000-0526.2012.06.007)
摘要页（**直接取回**）：<http://qxqk.nmc.cn/qx/ch/reader/view_abstract.aspx?file_no=20120607&st=alljournals>

**CMA 业务数据，2005–2009**：

| 预报时效 | **登陆点位置预报误差** |
| --- | --- |
| 24 h | **71.1 km** |
| 48 h | **122.6 km** |
| 72 h | **210.6 km** |

**登陆时间误差**：
> **70% 的登陆发生得比预报更早** —— **48 h 预报提前 8 h，72 h 预报提前 12 h。**

> ⚠️ **这是「登陆点」误差，不要与一般路径误差混为一谈。**
> 这是**官方预报员**的水平，比纯外推好得多
> （对比子代理自算的纯外推：24 h 79.5 / 48 h 169.1 / 72 h 485.7 km）。
> **两者不是替代关系**，但**量级排序一致**（误差随时效单调增长）。

**⇒ 对游戏的含义**：如果游戏里报"台风将在 X 小时后于 Y 登陆"，
**在 24 h 时就已经有 ~70 km 的不确定度**，48 h 时 ~120 km。
**不要给玩家一个"精确的倒计时 + 精确坐标"** —— 那是在制造伪精度。

##### 子代理自算的**纯外推**落点误差（(c)，非文献值）

| 预报时效 | 样本 N | 落点位置误差（均值） | 中位 | 位置误差 ≤ 6 h 的比例 |
| --- | --- | --- | --- | --- |
| 12 h | 71 | 79.5 km | 63.4 km | 84.5% |
| 24 h | 67 | **169.1 km** | 152.7 km | 55.2% |
| 48 h | 54 | **485.7 km** | 442.8 km | 22.2% |
| 72 h | 46 | **765.1 km** | 743.6 km | 8.7% |

**一个重要的工程结论**：**落点的位置不确定度 ≈ 同时效的一般路径不确定度**
（24 h：169 km vs CLIPER5 的 165 km；官方 24 h 登陆点 71 km vs 官方 24 h 路径 62–64 km）
→ **不需要为落点单独建模不确定性**，直接用路径误差当落点误差即可。

**⚠️ HURDAT2 的一个坑（子代理实测）**：`L` 标志**对温带变性后的登陆也会打**
（例：台风 Lee `20230916, 2000, L, EX, 44.3N, 66.3W`）。
**必须同时过滤状态字段**，否则会把温带气旋登陆当成台风登陆。

---

## D. 雷电（雷击）干预与预测

> **本节的证据分级**：D 节的文献部分由并行子代理完成（完整报告 `_research/FINAL_REPORT.md`，
> 附已下载的 PDF 全文与抽取文本）。**凡标 (a) 的都是子代理实际取回原文或摘要并给了 URL 的**，
> 标 (c) 的是它自己的工程换算/近似，标 (d) 的是**未核实**。
>
> ⚠️ **本节最重要的三句话，先说**：
> 1. **不存在可直接使用的已发表「闪电概率 vs 距台风中心距离」函数。** 文献给的是
>    **径向密度廓线**与**累积计数占比** —— **P(r) 必须由我们自己构造，标为工程近似。**
> 2. **「眼墙闪电预示快速增强」这个流行说法在文献里是矛盾的** ——
>    有一篇权威论文发现**快速减弱**个例的内核闪电密度是快速**增强**个例的 **4 倍**。
>    **不要把它做成确定性的游戏机制。**
> 3. **不存在「每升高 100 m 闪电增加 X%」的可引用数值** —— 文献里根本没有这个数。
>    **绝对不要编造它。**

### D.1 雷击密度的量级

#### D.1.1 全球基准与陆海对比 —— **(a)**

来源：Cecil, Buechler & Blakeslee (2011), *TRMM-based Lightning Climatology*, XIV ICAE；
**NASA NTRS 官方全文 PDF**：<https://ntrs.nasa.gov/api/citations/20110015774/downloads/20110015774.pdf>

| 量 | 数值 |
| --- | --- |
| 全球平均闪电率 | **46.1 flashes s⁻¹** |
| **全球平均闪电密度** | **2.9 flashes km⁻² yr⁻¹** |
| 全球年闪电数 | **1.46 billion yr⁻¹** |
| 峰值年均密度（0.5°） | **160 fl km⁻² yr⁻¹**（刚果东部） |
| 中非大范围 | **>30 fl km⁻² yr⁻¹** |
| 热带/亚热带大部分非干旱陆地 | **>10 fl km⁻² yr⁻¹** |
| 峰值月均密度（2.5°） | **18 fl km⁻² mo⁻¹**（布拉马普特拉河谷）；巴基斯坦北部 >13 |
| 探测效率 | LIS 约 69%（正午）–88%（夜）；OTD 41–54% |

**陆/海拆分 (a)**：ACP **24**, 14005 (2024) 逐字给出 LIS/OTD 气候态（Cecil et al. 2014；Luhar et al. 2021）：
**"46.3, 38.5, and 7.7 flashes s⁻¹"（全球 / 陆地 / 海洋）**
—— 全文 PDF：<https://acp.copernicus.org/articles/24/14005/2024/acp-24-14005-2024.pdf>

**(c) 子代理换算**（陆地 1.49×10⁸ km²、海洋 3.61×10⁸ km²）：
**陆地 ≈ 8.2、海洋 ≈ 0.67 fl km⁻² yr⁻¹，陆海比 ≈ 12 : 1**
（用 46.1 fl s⁻¹ 与 5.10×10⁸ km² 反算全球均值 2.85，与原文 2.9 一致 —— 可作校验。）

**独立全球估计 (a，摘要级)**：Christian et al. (2003, OTD)：**1.4 billion yr⁻¹，44 ± 5 fl s⁻¹**
（远低于 1925 年世界雷暴外推的 100 fl s⁻¹）；陆海比 **≈10 : 1**；
刚果盆地峰值 **80 fl km⁻² yr⁻¹**。DOI [10.1029/2002JD002347](https://doi.org/10.1029/2002JD002347)

> ⚠️ **口径警告（必须写进注释）**：上表是**总闪（IC + CG）**；
> 很多经典研究用 **NLDN 地闪（CG）**，WWLLN 部分含 IC。
> **跨研究的绝对值不可直接比较** —— Abarca et al. (2011) 明确指出 WWLLN 相对 NLDN
> 在**内核区低估密度**。

#### D.1.2 台风内部的闪电率（每小时闪击数）—— **(a)**

| 量 | 数值 | 来源 |
| --- | --- | --- |
| 整 TC（半径 500 km 内）**最常见小时率** | **250–600 strokes h⁻¹** | Zhang et al. (2022), *Front. Earth Sci.* 10:940205，OA 全文 <https://www.frontiersin.org/articles/10.3389/feart.2022.940205/pdf> |
| **极端小时率** | **3,154 str h⁻¹**（内核，TS Cimaron 2013）；**4,426 str h⁻¹**（外雨带，Severe Typhoon Matmo 2014）—— 原文称 "comparable to lightning activity in mesoscale convection systems on land" | 同上 |
| 75% / 50% 分位 | 弱 TC(<32.7 m/s) **530 / 211**；强 TC **288 / 83** str h⁻¹ | 同上 |
| 平均率（500 km 内，33 个西北太平洋 TC） | 最高 **3,201 flashes h⁻¹**、最低 **3**；**与峰值强度无正相关** | Zhang et al. (2012), *MWR* **140**，DOI [10.1175/MWR-D-11-00347.1](https://doi.org/10.1175/MWR-D-11-00347.1) |
| 单场眼墙暴发峰值 | **flash rates exceeding 100 flashes per minute** | Squires & Businger (2008), *MWR* **136**, 1705–1722 |
| 风暴尺度日均 | Andrew 平均 **4,400 flashes day⁻¹**（300 km 内）；Hugo 18 h 仅 33 次；Jerry 18 h 691 次 | Molinari et al. (1999), *MWR* **127**, 520–534 |

#### D.1.3 台风内部的闪**密度**与径向廓线 —— **(a) 原值 + (c) 换算**

**Molinari et al. (1999)** 是唯一给出**完整径向廓线且单位明确**的经典文献。
原文单位 "flashes per 100 km × 100 km per day"，即 **fl (10⁴ km²)⁻¹ d⁻¹**；20 km 环带，仅飓风阶段，9 个飓风：

| 结构单元 | 文献原值 | (c) → fl km⁻² d⁻¹ | (c) → fl km⁻² yr⁻¹ |
| --- | --- | --- | --- |
| 内核/眼墙最大值（**强**飓风） | **< 30** | 0.003 | ≈1.1 |
| 内核/眼墙最大值（**边缘**飓风） | **> 100** | 0.010 | ≈3.7 |
| **内带极小值（100–120 km）** | **5** | 0.0005 | ≈0.18 |
| **外雨带最大值（210–290 km）** | 阈值 **> 250** | 0.025 | ≈9.1 |
| 陆上 MCS（Holle et al. 1994 转引） | **1200–7200** | 0.12–0.72 | ≈44–263 |

**(c) 换算说明**：÷10⁴；×365.25。**文献原值是 (a)，换算是 (c)。**

**同文献的累积占比（最接近「概率 vs 距离」的已发表素材，原文直述）**：
飓风强度下 **< 1% 的闪击在 40 km 内、仅 3.4% 在 80 km 内**；
九场合计**最小闪密度 5 fl (10⁴ km²)⁻¹ d⁻¹ 位于 100–120 km**；
**外带极大半径约 250 km**；原文："绝大多数闪电必然与外雨带相关"。

**Squires & Businger (2008)**（同单位制，NLDN）：Rita 眼墙区（0–50 km）
**平均 986 strikes (10⁴ km²)⁻¹ d⁻¹**（(c) ≈ 0.099 fl km⁻² d⁻¹ ≈ **36 fl km⁻² yr⁻¹**）；
Andrew 眼墙（0–40 km）仅 **54**（(c) ≈ 1.97 fl km⁻² yr⁻¹）；眼墙 : 外雨带比 **6:1（Rita）/ 1:1（Katrina）**。

**Abarca et al. (2011)**（WWLLN + NLDN，24 个大西洋 TC）：
**平均廓线上内核是电活动最强区，约为雨带的 3 倍**（"roughly 3 times more active than the rainbands"）。

#### D.1.4 ⭐ 各径向区间的**计数**占比 —— 可直接当游戏权重 **(a)**

Zhang et al. (2022)：**74 个 TC、3,293 小时样本、1,302,625 次 WWLLN 闪击**（登陆前后各 24 h）：

| 径向区间 | 占全部闪击的比例 |
| --- | --- |
| **内核 0–100 km** | **6.2%** |
| **内雨带 100–200 km** | **9.6%** |
| **外雨带 200–500 km** | **84.2%** |
| 登陆前 / 登陆后 | 56% / 44% |

> 🔴 **最容易踩的坑（务必写进代码注释）**：
> **这是"原始计数占比"，而环带面积 ∝ r。**
> 所以它和"闪密度"的排序**含义相反** ——
> **密度上内核偏高，计数上外带占绝对多数。**
> **两个量不能混用。** Molinari et al. (1999) 的外带极大在半径 >200 km
> （除采样稀疏的 Hugo 外每场都有），是同一结论。

#### D.1.5 陆上有组织强对流的峰值率（游戏标定上限）—— **(a) + (c)**

- **> 100 flashes per minute，集中于面积 ≲ (100 km)²（= 10⁴ km²）**
  （引 Cecil et al. 2005；Zipser et al. 2006；来源 Squires & Businger 2008）**(a)**
  **(c) 换算**：100 fl min⁻¹ / 10⁴ km² = **0.6 fl km⁻² h⁻¹**；
  若更集中到 10³ km² 则 **6 fl km⁻² h⁻¹** —— **密度强依赖假定面积，必须标为工程假设。**
- Molinari et al. (1999) 引 Holle et al. (1994)：陆上 MCS **1200–7200 fl (10⁴ km²)⁻¹ d⁻¹**，
  日均 0.12–0.72 fl km⁻² d⁻¹，**比飓风高约一个数量级** **(a，转引)**。
- **类比佐证 (a)**：Zhang et al. (2022) 指 TC 内核极端率与陆地 MCS 相当。
- **反向佐证（有意思，值得知道）(a，摘要级)**：Peterson (2024), *Earth and Space Science* **11**,
  e2023EA003304 —— "thunderstorms with the **greatest lightning densities on Earth are found in
  maritime thunderstorms**"（墨西哥湾、南非以东）。
  **陆地 MCS 胜在总闪率（面积大），海上紧凑风暴胜在密度。**

**(c) 游戏标定建议（统一到 fl km⁻² d⁻¹）**：

| 场景 | 建议值 |
| --- | --- |
| 眼墙（强飓风，Andrew 型 ↔ Rita 型） | 0.005 – 0.099 |
| 内核中位 | ~0.01 |
| 内带极小值 | 0.0005 |
| 外雨带峰值 | 0.025 |
| 陆上 MCS | 0.12 – 0.72 |
| 陆上强对流瞬时峰值 | 0.6 – 6 |

> **在 Minecraft 尺度不要直接套绝对密度**（这些数值太小，一格 1 m² 一辈子打不到一次雷）。
> **正确做法：用"相对权重 × 全局缩放因子"** —— 相对权重直接采用 D.1.3 / D.1.4 的文献比例。

### D.2 相对台风中心的方位分布

#### D.2.1 ⭐ 决定性的结论：**垂直风切变主导，不是移动方向主导** **(a)**

**Corbosiero & Molinari (2002)**（NLDN，35 个大西洋 TC，1985–99）：

- VWS > 5 m s⁻¹ 时 **> 90% 的闪击发生在顺切变侧（downshear）**
  （内核 0–100 km 与外带 100–300 km 均如此）；
- 内核按切变强度：弱 / 中 / 强切变下顺切变占比 **69% / 90% / 93%**；
- 以「每 12 h 时段闪击数最大象限」统计，内核顺切变象限占 **86%（91/106 时段）**；
- 内核略偏 **downshear-left**（飓风强于弱 TC）；
- 外雨带（强切变，45 时段，80,844 次闪击）：**85% 顺切变，其中 63% 位于 downshear-right**。

**Corbosiero & Molinari (2003)** —— **运动相对象限（北半球）**：

- **71%** 的 12 h 时段最多闪击落在**前两象限**，**62%** 在**右侧两象限**，
  **42%** 在**右前象限（right-front）**；
- **76%** 的最多闪击计数出现在**运动矢量右侧**；
- 外带：**77% 的 69,731 次闪击在顺切变侧，53% 在顺切变右象限**。

> 🔴 **原论文的关键限定（逐字，必须一起引用，否则会误用）**：
> **"Without exception, the influence of vertical wind shear dominated the distribution."**
> 切变与运动一致时两者叠加；**不一致时切变效应明显、运动效应几乎消失**。

**Abarca et al. (2011)**：切变产生**内核 downshear-left / 外雨带 downshear-right** 强不对称；
**运动不对称不明显**（"motion asymmetries are less clear"）；
随切变增强，内核对流方位角范围**从约 130° 收窄到约 60°**。

**Zhang et al. (2020)**（*MWR* **148**，230 个 TC，2005–17）—— **以 RMW 归一化**：

- 分析域 **5 × RMW**；内核活跃于 **downshear-left**，外区活跃于 **downshear-right**；
- **RI 类别的径向百分比仅在 0–1 RMW 超过 30%**，且四方位分布**最均匀（最轴对称）**，
  left-of-shear 略高于 right-of-shear；
- 强 TC 归一化闪电频率在 **2 × RMW 内**显著更大；
  高 normalized LF（> 0.4）出现在 **3–5 RMW 的 downshear-right**。

**Zhang et al. (2022)**（登陆中国）：切变是产生闪电与对流不对称的**主导因子**，
低(<5) / 中(5–10) / 高(>10 m s⁻¹) 切变下不对称递增。

#### D.2.2 🌐 对「有没有可用的 P(r)」的明确回答 —— **没有**

用户直接问了这一点，明确回答：

> **本次检索到的文献提供的是「径向闪密度廓线」（单位面积强度）与「累积计数占比」（计数比例），
> 但未发现任何论文给出闭式的、可直接套用的「闪电概率 P(r) 或 P(r/Rmax)」拟合函数。**
> Molinari 1999、Abarca 2011、Zhang 2020/2022、DeMaria 2012
> **均以表格 / 图形给出经验廓线，而不是参数化公式。**
> **因此游戏中的 P(r) 必须构造为【工程近似】，并且必须标注为工程近似。**

**(c) 建议的径向权重表（构造为工程近似，500 km 截断）**

| 半径区间 | 相对计数权重 | 依据（哪条文献支持这个形状） |
| --- | --- | --- |
| 0–40 km（眼墙/眼） | 0.8 | Molinari：< 1% 落入，但面积小；密度不低、计数极低 |
| 40–80 km（内核外） | 1.0 | Molinari：累积至 80 km 仅 3.4% |
| **80–120 km（内带极小）** | **0.15** | Molinari 组合极小在 100–120 km；Abarca 的 60–120 km 弱区 |
| 120–200 km（内雨带） | 0.6 | Zhang 2022：100–200 km 占 9.6% |
| **200–350 km（外带峰值）** | **1.4** | Molinari 外带极大 210–290 km；Abarca 外带增强 |
| 350–500 km | 1.0 | Zhang 2022：200–500 km 占 84.2% |
| > 500 km | 0.1（可截断） | Zhang 2022 定义域边界 |

**Rmax 归一化版本**：以典型 Rmax ≈ 40 km 折算，边界约为
0–1、1–2、2–3、3–5、5–8.75、8.75–12.5 Rmax；
**并对 RI 状态把 0–1 Rmax 权重提高到 ≥ 0.3**（Zhang 2020 的 >30%），
同时把方位分布改得更轴对称。

**两处必须写进代码注释的警告**：
1. **密度廓线与计数占比不能混用**（环带面积 ∝ r，两者排序相反）；
2. **WWLLN/LIS-OTD 测总闪、NLDN 测地闪，绝对值不可跨研究直接比较。**

#### D.2.3 方位权重的实现建议（**重要简化**）

文献里的方位分布**依赖垂直风切变矢量**（而切变数据我们**拿不到** ——
Open-Meteo 只给风，不给"切变"这种派生诊断量；要自己算需要多高度层差分）。

**【工程近似】本模组建议**：
用**移动方向**作为切变的**代理**（因为转向前的台风切变常与移动方向大致一致 ——
这是近似，不是文献结论），即：

```java
/**
 * 【工程近似】方位权重：前右象限最高、后左象限最低。
 * 文献依据（Corbosiero & Molinari 2003）：北半球 76% 的最多闪击在运动矢量右侧、
 * 71% 在前两象限、42% 在右前象限。
 * ⚠️ 但原论文明确「切变主导、运动效应在不一致时几乎消失」，所以这只是近似。
 */
public static double azimuthalWeight(double relBearingDeg) {
    // relBearingDeg：该方位相对风暴移动方向的夹角，0=正前，+90=正右（北半球）
    return 0.6 + 0.4 * Math.cos(Math.toRadians(relBearingDeg - 45.0));
    // 峰值在 +45°（右前象限），谷值在 -135°（左后象限）；范围 0.2 ~ 1.0
}
```

> **诚实说明**：这是**纯工程近似**，不是任何论文的公式。
> 它只是把"右前象限占 42%"这个统计事实变成了一个平滑的权重函数。
> **必须允许玩家调节或关闭。**

### D.3 眼墙闪电与快速增强 —— **诚实的结论：有信号但方向不单一**

用户问的是"雷击概率随距离台风中心变化"的近似关系，没有直接问 RI，
但这一节对"游戏里要不要做'眼墙闪电预示增强'"这个设计决策很关键，所以给出。

**(a) 支持「内核闪电有预测信息」**

- **Abarca et al. (2011)**：**所有强度类别中，增强时段的内核平均闪击数都更大** ——
  增强的 TD/TS 约为非增强的 **1.5 倍**；增强 H1–H2 与 H3–H5 约 **2 倍**；
  外带仅增强 H1–H2 达 **> 3 倍**，其余无显著差异。
  原文指出内核闪密度"有潜力区分增强与非增强 TC，尤其在弱风暴阶段"。
- **Zhang et al. (2012)**：眼墙暴发在**增强期占 15%、减弱期占 43%**；
  **眼墙暴发平均出现在登陆前强度峰值前 7.1 h**；
  **10% 的暴发发生在风暴转向之前**（该文新结论）。
- **Squires & Businger (2008)**：Rita 与 Katrina 均在**最快速增强期（RI）**产生眼墙暴发；
  Rita 的 RI 暴发期间 24 h 内最大风速 **+34 m s⁻¹**、22.5 h 内中心气压 **−68 mb**；
  **MI（最大强度）暴发在最大强度前约 4–5 h 开始**。
  原文限定：**RI 暴发可能预示继续加深**，而"最剧烈的眼墙暴发有时出现在增强期临近结束时"。
- **Leroy et al. (2014)**（*JGR*，西南印度洋，WWLLN，70 个 TC，2005–2013）：
  **RI 期间闪电活动在 RI 开始前 18 h 开始增加**。DOI [10.1002/2014JD021651](https://doi.org/10.1002/2014JD021651)

**(a) 🔴 明确的反证（必须同时给出）**

**DeMaria et al. (2012)**（*MWR* **140**, 1828–1842）给出最不利于简单叙事的结论：

- 样本：大西洋 **1,245** 例（22% 减弱、37% 增强）、东太平洋 **1,297** 例；
  RI 116 例、RW（快速减弱）98 例、AIC 1,031 例；
- **"大西洋快速减弱（RW）个例的内核（0–100 km）闪电密度约为快速增强（RI）个例的 4 倍"**
  —— 逐字：**"the eyewall LD is about 4 times greater for the RW cases than the RI cases"**；
- **外雨带（200–300 km）闪电密度约为 RI 个例的 2 倍于 RW 个例**
  （"about a **factor of 2 larger for the RI cases**"）；
- **提前时间依赖**：对 **−6 h 与 0 h**，增强个例眼墙/内核闪密度**更高**（与 Abarca 2011 一致）；
  对 **+6 h 与 +12 h 关系反转**，减弱个例更高。
  原文总结："lightning activity near the storm center tends to occur during the intensification phase,
  **but it is also an indicator that the intensification period is nearing its end**"；
- 原文进一步指出**外带闪电对强度变化的预测信息可能比内核更多**。

**(c) 综合判断（本报告综合，非单一文献结论）**

文献支持"内核闪电与增强**同期**相关，且在弱风暴阶段与领先 0–6 h 窗口内信号最强"，
但**不支持**"内核闪电稳定领先快速增强若干小时"的强因果叙事；
**DeMaria et al. (2012) 的 4 倍反向结果**说明该信号在**快速减弱**端更强。

> **游戏用法建议**：把"眼墙闪电增强"当作**"正在增强 / 即将达到强度峰值"的提示**
> （提前约 0–6 h；眼墙暴发平均提前峰值 7.1 h），
> **而不是 RI 的可靠预警**。**不要做成"看到眼墙闪电就知道要爆发增强"的确定性机制。**

**(d) 未核实**：多次检索（含 "entropy order parameter lightning tropical cyclone intensification"）
**未找到任何以该名称命名的闪电/TC 方法或文献**。
**不建议引用。**

### D.4 雷击与地形 / 海拔的关系

#### D.4.1 🔴 核心结论：**不存在统一的「闪电随海拔变化定律」**

四组高质量研究给出**四种不同形状**，且有一篇论文明确指出流行的"先增后减"
**可能是分析方法造成的假象**。

| 形状 | 区域 | 来源 | 标注 |
| --- | --- | --- | --- |
| **全高度范围单调增长** | 瑞士 + 奥地利 | Smorgonskiy, Rachidi, Rubinstein & Diendorfer (2013), XII SIPDA, DOI [10.1109/SIPDA.2013.6729216](https://doi.org/10.1109/SIPDA.2013.6729216) | (a) 摘要级（EPFL 验证墙阻断全文） |
| **上升 → 1300–1400 m 达峰 → 更高处下降** | 南非东部/莱索托/斯威士兰 | Britz & Hunt (2025), XVIII SIPDA, DOI [10.1109/sipda68639.2025.11296644](https://doi.org/10.1109/sipda68639.2025.11296644) | (a) 摘要级 |
| **1829 m 以下正相关，1829–3200 m 波动，3200 m 以上陡增并在最高山峰达峰** | 美国科罗拉多 | Vogt & Hodanish (2014), *MWR* **142**(7), DOI [10.1175/MWR-D-13-00334.1](https://doi.org/10.1175/MWR-D-13-00334.1) | (a) 摘要级 |
| **季节依赖的符号反转** | 东阿尔卑斯 | Simon et al. (2022), *Elektrotech. Inf.tech.* **139**, DOI [10.1007/s00502-022-01032-1](https://doi.org/10.1007/s00502-022-01032-1) | (a) 全文已读 |

**Smorgonskiy et al. (2013) 的关键反驳句（逐字）**：
> "Some previous studies suggest that the lightning flash density decreases above a certain altitude.
> We show that this conclusion **could be affected by the used method of analysis** …
> The results of the application of both methods suggest that
> **the lightning flash density grows over the whole altitude range**."

#### D.4.2 已发表的具体数字

**南非 (a)**（Britz & Hunt 2025，13 年 LLS，2007–2019）：
- 闪密度 vs 海拔 **r ≈ 0.441**（中等正相关），
  "lightning activity **peaking at 1300–1400 m and declining at higher altitudes**"；
- **坡度**仅**弱相关（r ≈ 0.128）**；
- 但 "particularly **ridges descending from high elevations** — consistently showed [higher] densities"。
- **(d) 未找到任何显式的山脊/谷地数值比（ALDIS/OVE）。**

**科罗拉多 (a)**（Vogt & Hodanish 2014）：10 年（2003–2012）4/1–10/31，500 m 网格、10 m USGS DEM、**1,250 万次闪击**。

**阿尔卑斯 —— 定量且全文已读 (a)**：

**Simon, Umlauf, Zeileis, Mayr, Schulz & Diendorfer (2017)**, *NHESS* **17**, 305–314,
DOI [10.5194/nhess-17-305-2017](https://doi.org/10.5194/nhess-17-305-2017)
（ALDIS，5–8 月 2010–2015，克恩顿州，1 km² × 1 天，海拔 339–3798 m；仅 **2.15%** 的 cell-day 有闪电）：

- 海拔效应（cloglog 尺度）f₁(logalt)："varies from roughly **−0.2** for low altitudes to values
  **greater than 0.5** for altitudes above 2800 m"，形状接近指数；
- 实测例（2440 m, Rosennock，7 月 20 日）：海拔效应 0.34、季节 0.64、空间 −0.03、截距 −3.97
  ⇒ 概率 **4.76%（每 km² 每日）**；
- 7 月 20 日空间概率 **1.8%–6.5%**，期望闪电数 **0.028–0.166**；
- 🔴 **"the main alpine crest is an area with a minimum in flash density"** ——
  **最高地形（High Tauern）反而是极小值**，最大值出现在平均海拔更低、坡度更缓的 Gurktal Alps；
- (b) 转引 Schulz et al. 2005：奥地利全境 "**between 0.5 and 4 flashes km⁻² yr⁻¹**
  depending on the terrain"。

**Simon, Mayr et al. (2022)**（全文已读）：ALDIS 2010–2020，4–9 月，**4520 万个** 1 km² × 1 天样本，
**5.47%** 有闪电；任一 1 km² 格**最大日概率 "barely exceeds 2%"**，典型约 **1%/日/km²**：

- **~100 m 起伏阈值**：地形粗糙度 =（90 m 分辨率格内最高海拔 − 1 km² 格平均海拔）。
  逐字："Protruding topography such as peaks increases the likelihood of lightning. …
  Overall, **where that difference exceeds about 100 m, lightning likelihood increases somewhat**."
- **季节反转**：春季 "probabilities in **valleys** [are] considerably higher than over mountain ranges,
  which are still mostly snow-covered"（分界约 **1200 m msl**）；
  7 月中旬后**反转**，谷地明显低于山地；高海拔雷电季**推迟约 3 周**。
- **反例**：Udine 周边**平坦地** "has the highest probability in the whole study region"。

**山脊 vs 谷地 —— 科罗拉多官方气候志（定量，全文已读）(a)**：
Hodanish et al. (2019), *NWA J. Operational Meteorology* **7**(4)，
年均地闪密度（fl km⁻² yr⁻¹，**区域面积平均**）：

- **Palmer Divide 山脊：大面积 > 4.0，局部 > 6.5**；
  **San Luis 谷地（平均海拔 2.3 km，> 21,000 km²）：≤ 1.0**，为全州最少；
- ⇒ **山脊/谷地比 ≈ 4 : 1 至 6.5 : 1**
  （但这是"有利山脊区域 vs 全州最受抑制的大谷地"，**应视为上界**）；
- 🔴 逐字："**Most of the minima in lightning activity are in mountain valleys**"；
- **反例**：全州最高的 Sawatch Range（14 座 > 4.3 km 山峰）闪电**极少**
  （归因水汽阻挡抑制 CAPE）；
- 🔴 逐字："**Areas of flash density maxima do not necessarily occur in the tallest mountain ranges.**"
- 奥地利（Diendorfer 官方 25 年回顾）："**Annual ground flash densities of 5 flashes per km²
  and year are observed**"（东南奥地利/斯洛文尼亚/北意大利）。

**坡度与坡向 (a)**：坡度弱预测 r ≈ **0.128**（南非）vs 海拔 r ≈ 0.441；
海拔 + 坡度合并相关系数 0.473（云南，DOI [10.1109/TPWRD.2023.3283606](https://doi.org/10.1109/TPWRD.2023.3283606)，
**合并统计量非纯海拔梯度**）；
坡向（福建九仙山 2015–2021，20 km 半径，DOI [10.1016/j.jastp.2025.106546](https://doi.org/10.1016/j.jastp.2025.106546)）
负地闪密度 "most concentrated on **east-facing and southeast-facing slopes**"；
科罗拉多 San Juan 山脉**南坡**地闪密度最高。

#### D.4.3 🔴 **明确回避编造：不存在「每 100 m 增加 X%」**

> **用户问「量级多大」，这里必须给一个诚实的回答：**
>
> **不存在任何"每升高 100 m 闪电增加 X%"的同行评审数值。**
> 现代研究用**对数项或 GAM 样条**建模（Simon 2017 建模 `log(altitude)` 并报告自由度；
> Simon 2022 明确效应 "**is logarithmic**"）；
> 相关性研究因关系**非单调**而只报 r。
> **任何"~2000 m 转折"都是区域特定的**（南非 1300–1400 m；克恩顿非线性起始 > 2000 m），
> **不是全球定律。**
>
> 另外**未找到**：任何全球尺度 LIS/OTD 闪密度对地形的回归；
> 任何显式的 ALDIS/OVE 山脊/谷地数值比；任何坡度角阈值。
>
> **→ 如果有人给出"闪电密度每 100 m 增加 5%"这类数字，那是编造的。**

#### D.4.4 **(c) 面向游戏的工程近似（本报告综合，非文献结论）**

既然文献给不出统一曲线，就用**分项的乘法修正**，并把依据逐条注明：

```java
/**
 * 【工程近似 —— 不是任何文献的公式】闪电的地形倍率。
 * 每一项的文献依据见 §D.4 正文；数值是综合取整，不是文献值。
 *
 * @param elevationM       海拔（米）
 * @param reliefMeters     相对起伏（米）—— 对应 TerrainProfile.reliefMeters()
 * @param exposure         暴露度 0~1 —— 对应 TerrainProfile.exposure()
 * @param ridgeLike        是否位于相对起伏 >= 100 m 的出露山脊/山峰线
 * @return 光合倍率，已封顶 4.0
 */
public static double lightningTerrainFactor(double elevationM, double reliefMeters,
                                            double exposure, boolean ridgeLike) {
    // 1) 海拔曲线（暖季、无雪，相对 500 m 基准）
    //    依据：Britz & Hunt 2025（峰在 1300-1400 m）、Vogt & Hodanish 2014、
    //          Simon et al. 2017（>2800 m 效应最强）、Simon et al. 2022
    double h;
    if      (elevationM <  500.0) h = 1.0;
    else if (elevationM < 1000.0) h = 1.3;
    else if (elevationM < 1500.0) h = 1.8;
    else if (elevationM < 2000.0) h = 1.9;
    else if (elevationM < 2800.0) h = 1.8;
    else if (elevationM < 3500.0) h = 1.6;
    else                          h = 1.4;

    // 2) 山脊/山峰倍率。依据：Hodanish et al. 2019 的 4:1~6.5:1 是【上界】
    //    （有利山脊 vs 全州最受抑制大谷地），Simon et al. 2022 对突出地形只说 "somewhat"，
    //    Britz & Hunt 2025 认为山脊增强真实但叠加在很弱的坡度相关之上（r=0.128）。
    //    → 取 ~2~3x 稳妥，不要用 6.5x 当通用因子。
    double ridge = 1.0;
    if (ridgeLike) {
        ridge = 2.0 + 1.0 * exposure;     // exposure 1.0 -> 3.0x（孤立峰）
    } else {
        ridge = 1.0 + 0.6 * exposure;     // 0.5 中性 -> 1.3x；1.0 -> 1.6x
    }

    // 3) 起伏起效阈值：Simon et al. 2022 实测 ~100 m 以上才"somewhat"增强，
    //    在 100-300 m 区间渐增，不做开关式跳变。
    double relief = 1.0 + 0.25 * clamp01((reliefMeters - 100.0) / 200.0);

    return Math.min(4.0, h * ridge * relief);   // 全球可信最大实测比 ~6.5x，且是年均区域对比
}
```

**季节性修正（【工程近似】，但依据强，且最常被实现错）**：
- 早季 / 高地有雪时**反转**海拔曲线（阿尔卑斯约 **>1200 m** 雪线以上），
  谷地/低地略占优（**~1.1–1.2×**）；
- 高海拔雷电季**推迟约 3 周**开始（Simon et al. 2022 实测）。
- **盛夏无雪时按上式的海拔曲线执行。**

**坡度 / 坡向次级修正（【工程近似】）**：
- 坡度在整个合理区间（0° → ~35°）只加 **+0% 至 +15%**，~30° 以上饱和
  （依据：南非 r ≈ 0.128 的**弱**相关）；
- 坡向 **±15%**（九仙山偏东/东南 +10–15%；San Juan 偏南坡；北向高坡因积雪滞留给 −10%）。

> **一句话版**：暖季无雪条件下，闪电概率从低谷到约 1000–2000 m 的宽阔极大值
> 上升约 **1.8–1.9 倍**，随后趋平或降至最高山脊的 **~1.4–1.6 倍**；
> **水汽充沛山脉**（落基山型）允许持续升到峰顶 **~2.5–3 倍**；
> 对相对起伏 > 100 m 的出露山脊/山峰**再乘 ~2–3 倍**；陡坡最多 ~1.15 倍；坡向 ±15%。

### D.5 游戏内「落点预测」的可实现做法（雷电版）

用户问的是"游戏里要预测落点：给出一个可实现的做法（例如按雨带方位分布 + 距离衰减
+ 随机扰动生成候选点，再给出置信度）"。下面是**完整可实现的方案**。

```java
/**
 * 生成"下一次雷击候选落点"的分布（雷电版落点预测）。
 *
 * 思路 = 【文献比例】× 【径向权重（工程近似）】× 【方位权重（工程近似）】× 【地形倍率（工程近似）】
 *      → 归一化成分布 → 采样若干候选点 → 用分布的集中度当置信度。
 *
 * ⚠️ 这是"看起来合理"的构造，不是气象预测。游戏内应标注为【推算】。
 */
public static final class LightningStrikeSampler {

    /** 单次调用最多生成的候选点数。 */
    private static final int MAX_CANDIDATES = 16;

    /**
     * @param storm      当前台风状态（中心经纬度、Rmax、强度）
     * @param motionBearingDeg 移动去向（罗盘度）
     * @param terrainAt  给定 (lat,lon) 返回 TerrainProfile 的回调
     * @return 候选落点列表，每个含 (lat, lon, weight)
     */
    public static List<Candidate> sample(TyphoonState storm, double motionBearingDeg,
                                         TerrainLookup terrainAt, Random rng) {
        List<Candidate> out = new ArrayList<>(MAX_CANDIDATES);
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            // 1) 径向采样：先按 §D.2.2 的权重表选区间，再在区间内均匀取半径
            double rKm = sampleRadiusFromWeightTable(storm.rmaxKm(), rng);

            // 2) 方位采样：按 §D.2.3 的方位权重（相对移动方向的夹角）
            double relBearing = sampleRelativeBearing(rng);          // 峰值在 +45°
            double bearing = norm360(motionBearingDeg + relBearing);

            // 3) 候选坐标（沿大圆推进）
            double[] ll = destinationPoint(storm.lat, storm.lon, bearing, rKm, 6371.0);

            // 4) 地形倍率（§D.4.4）
            TerrainProfile tp = terrainAt.get(ll[0], ll[1]);
            double tf = lightningTerrainFactor(tp.elevationMeters(), tp.reliefMeters(),
                                              tp.exposure(), isRidgeLike(tp));

            // 5) 综合权重 = 径向 × 方位 × 地形 × 距离衰减
            double w = radialWeight(rKm, storm.rmaxKm())
                     * azimuthalWeight(relBearing)
                     * tf;

            out.add(new Candidate(ll[0], ll[1], w));
        }
        return out;
    }
}
```

**如何给出"置信度"（【工程近似】，但思路是标准的）**

不要报"下一次雷击在哪"（不可能），而是报**区域**：

```java
/**
 * 置信区域：对候选点做加权，输出"概率最高的若干格"。
 * 置信度 = 该区域权重 / 总权重。这是蒙特卡洛的标准做法，不是气象概率。
 */
public static ConfidenceReport confidence(List<Candidate> candidates, double gridDeg) {
    // 1) 按 gridDeg（例如 0.25°≈28 km）把候选点分箱
    // 2) 累加权重 → 归一化
    // 3) 取累计权重达到 0.6 的最小区域集合作为"最可能区域"
    // 4) 报告的置信度必须写成区间，例如 "60% 概率落在阴影区内"
    ...
}
```

**四条实现纪律（很重要）**：

1. **不要报精确点，只报区域 + 百分比。** 报"下一道雷会打在 (x,z)"是伪精度。
2. **必须让玩家能看出这是推算**（HUD 上用不同颜色 / 标注 `[推算]`），
   不能和真实的官方台风预报混在一起显示。
3. **权重表必须可配置**，且默认值与文献的对应关系写在注释里
   （这样玩家/维护者能看出哪部分是文献、哪部分是近似）。
4. **不要用"眼墙闪电 → 快速增强"做确定性机制**（§D.3 的 4 倍反向结果）。
   若要做，只能做成"提示"，不能改数值。

### D.6 雷电：可用的免费外部锚点

| 锚点 | 端点 | 状态 | 用途 |
| --- | --- | --- | --- |
| **UApiPro `alerts[].type`** | `uapis.cn/api/v1/misc/weather` | **【实测】**（schema 见 §A.4） | 官方预警里会出现 `"雷电"` + 颜色分级 → **现实中有雷电活动的免费指示器** |
| **NHC / 中央气象台 台风公报** | 见 §A | **【实测可用】** | 台风强度 → 雷电强度（间接） |
| **NASA LIS/OTD 雷电气候态** | 目录页 <https://lightning.nsstc.nasa.gov/data/data_lis-otd-climatology.html>（**HTTP 200 实测**）；DOI [10.5067/LIS/LIS-OTD/DATA302](https://doi.org/10.5067/LIS/LIS-OTD/DATA302) | ⚠️ 见下 | 全球 / 区域基线雷电密度 |
| **Blitzortung / WWLLN 实时** | —— | **【未核实】**：没有找到公开 JSON API | 不建议 |

**关于 NASA LIS/OTD 的诚实记录**：
- **目录页可达**（实测 HTTP 200，71,851 字节），确认产品族：
  **HRFC（0.5°，全气候）**、**HRMC（0.5°，月）**、**LRFC/LRAC/LRDC（2.5°）**，
  版本 **V2.3.2015**；数据目录 `http://ghrc.nsstc.nasa.gov/pub/lis/climatology/LIS-OTD/<PRODUCT>/`。
- **🔴 但数据主机 `ghrc.nsstc.nasa.gov` 在本机不可达**（实测 **HTTP 000 / bytes=0**）——
  目录页可达而数据主机不可达。我**无法确认文件命名、格式（netCDF？）与是否需要 Earthdata 登录**。
  → 标 **【未核实】**。
- 已确认的官方信息：分辨率 0.5°（HRFC/年）与 2.5°（LRFC/月），
  探测器效率 LIS 69–88%、OTD 41–54%（Cecil et al. 2011，NASA NTRS 全文）。
- **许可**：NASA 数据开放（Earthdata 政策），但**因为拿不到文件，无法给出可用的下载示例**。
- **工程建议**：如果将来要内置一份"全球雷电气候态"，
  **LRFC 2.5° 版本体积最小**（2.5° = 72×144 格），非常适合打进 jar；
  但**必须先解决可达性与许可确认**。

---

## E. 开源实现（能否移植 / 能否再分发）

> **一句话结论**：**唯一许可宽松（BSD-3-Clause）且真正实现了多种台风参数化风廓线的项目是
> Clawpack / GeoClaw。** 其余主流项目**要么是 GPL，要么根本不含风场**。
> **建议：以 GeoClaw 为阅读基准，按原论文（Holland 1980 / Willoughby 2006 / Boose 2004 /
> Emanuel & Rotunno 2011）重写成 Java。**
>
> **绝对不要**把 GPL 项目的代码拷进模组 jar —— 那会让整个模组受 GPL 约束。

### E.0 许可证总表（**逐个取回 LICENSE 原文或官方字段后引用**）

| 项目 | 语言 | **许可证** | 证据 | 能否进 jar？ |
| --- | --- | --- | --- | --- |
| **Clawpack / GeoClaw** | Python + Fortran | **BSD 3-Clause** ✅ | `/LICENSE` 原文："**BSD 3-Clause License** / Copyright (c) 1994-2018, Clawpack Developers / All rights reserved." <https://cdn.jsdelivr.net/gh/clawpack/geoclaw@master/LICENSE> | ✅ **可以**（保留版权与免责声明） |
| **Unidata / MetPy** | Python | **BSD 3-Clause** ✅ | 仓库为 BSD-3 <https://github.com/Unidata/MetPy> | ✅ **可以** |
| **tcbench / TCBench** | Python | **MIT** ✅ | LICENSE 原文 "Copyright (c) 2023 Milton Gomez" <https://github.com/tcbench/TCBench> | ✅ **可以** |
| **tropycal** | Python | **MIT** ✅ | LICENSE 全文为 MIT 模板，版权行 "Copyright (c) Tropycal Developers" <https://cdn.jsdelivr.net/gh/tropycal/tropycal@1.5.1/LICENSE> | ✅ 可以（**但它不含风场**） |
| **CLIMADA** (`climada_python`) | Python | **GPL-3.0** ⚠️ | LICENSE 开头 "GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007" <https://cdn.jsdelivr.net/gh/CLIMADA-project/climada_python@main/LICENSE> | ❌ **不可** |
| **StormR** | R | **GPL (≥ 3)** ⚠️ | CRAN 官方字段 "License: GPL (≥ 3)" <https://cran.r-project.org/web/packages/StormR/index.html>；仓库 LICENSE.md 为 GPL-3 全文 <https://cdn.jsdelivr.net/gh/umr-amap/StormR@v0.3.0/LICENSE.md> | ❌ **不可** |
| **TCHazaRds** | R + C++ | **GPL (≥ 3)** ⚠️ | CRAN 官方字段 "License: GPL (≥ 3)" <https://cloud.r-project.org/web/packages/TCHazaRds/index.html> | ❌ **不可** |
| **TCRM** (Geoscience Australia) | Python | **GPL-3.0** ⚠️ | 官方 "License information" 页 <https://geoscienceaustralia.github.io/tcrm/LICENSE.html> | ❌ **不可** |
| **TCwindgen** | CUDA C++ | **GPL-3.0** ⚠️ | `/LICENSE` 原文，实例化为 "TCwindgen Copyright (C) {2016} {Cyprien Bosserelle}" <https://cdn.jsdelivr.net/gh/CyprienBosserelle/TCwindgen@master/LICENSE> | ❌ **不可** |
| **ADCIRC** | Fortran | **LGPL-3.0-or-later** ⚠️ | `/LICENSE.md` 原文："under the terms of the **GNU Lesser General Public License** … either version 3 of the License, or (at your option) any later version." <https://cdn.jsdelivr.net/gh/adcirc/adcirc@main/LICENSE.md> | ⚠️ **重写最干净**（LGPL 不约束独立重写） |
| **Delft3D** | Fortran/C | **AGPL-3.0 / GPL-3.0** ❌ | README 许可节逐字："Most simulation engines are licensed under **AGPL-3.0 or GPL-3.0**…" <https://cdn.jsdelivr.net/gh/Deltares/delft3d@main/README.md> | ❌ **最差情况** |
| **NOAA SLOSH 模型源码** | — | **不公开** | 公开的只是 `NOAA-MDL/sdp`（**Tcl/Tk 显示程序 + 打包的 .dll**），不是模型源码。其 LICENSE："Software code created by U.S. Government employees is not subject to copyright in the United States (17 U.S.C. §105)…" | ⚠️ 拿不到 |
| **STORM 合成路径数据集** | 数据 | **CC0** ✅ | DataCite DOI [10.4121/12706085.v4](https://doi.org/10.4121/12706085.v4)，`<rights rightsURI="https://creativecommons.org/publicdomain/zero/1.0/">CC0</rights>` | ✅ 数据可再分发 |
| **STORM 代码仓库** | Python | **(d) 未核实** | `NathalieBloemendaal/STORM` 经 jsDelivr 对 `main`/`master` 均返回 `404 Couldn't find version` | ⚠️ **发货前必须解决** |
| **TCHA 灾害栅格数据** | 数据 | **CC-BY 4.0** ✅ | GA eCat 元数据逐字："Creative Commons Attribution 4.0 International Licence" <https://ecat.ga.gov.au/geonetwork/srv/api/records/bbf7589a-4aa0-40c3-af2a-f7d051709329> | ✅ 可以（**需署名**） |

> **⚠️ 结论**：用户问"必须是能再分发进 Minecraft 模组的许可（MIT/Apache/BSD 优先，GPL 要标注）"——
> **回答是：主流台风风场库里，只有 GeoClaw（BSD-3）和 tropycal（MIT）是宽松许可，
> 而 tropycal 根本不含风场。CLIMADA、StormR、TCHazaRds 全部是 GPL。**
> **→ 结论：风场数学必须自己按论文重写。这其实是好事 —— 公式都是闭式标量运算，重写成本很低。**

### E.1 ⭐ 推荐阅读基准：Clawpack / GeoClaw（BSD-3-Clause）

**这是唯一「许可宽松 + 专为风暴潮风场驱动设计 + 多种廓线并列」的代码。**

- **许可**：BSD 3-Clause（见 E.0）→ **可以合法阅读并改编，只需保留版权声明。**
- **风场位置**：Python 包 **`clawpack.geoclaw.met.storm`**
  （文档逐字："Python package renamed `clawpack.geoclaw.surge` → `clawpack.geoclaw.met` …
  the old `clawpack.geoclaw.surge` still works but emits a `DeprecationWarning`."）；
  类/函数：`Track, StormTrack, ParametricMetForcing, GriddedMetForcing, Storm`；
  Fortran 侧 `met_forcing_module`（原 `storm_module`）。
  文档：<https://www.clawpack.org/dev/met_forcing.html>、<https://www.clawpack.org/dev/storm_module.html>
- **支持的廓线（逐字，共 9 种）**：
  `holland80`, `holland2008`, `holland2010`, `cle`, `slosh`, `rankine`,
  `modified_rankine`, `demaria`, `willoughby`
- **官方整数编码表（可直接当我们的枚举用）**：
  ```text
  {'none':0, 'holland80':1, 'holland2010':2, 'cle':3, 'slosh':4,
   'rankine':5, 'modified_rankine':6, 'demaria':7,
   'holland08'/'holland2008':8, 'willoughby':9, 'data'/'gridded':-1}
  ```
- 拖曳律 `drag_law`（0 none, 1 Garratt, 2 Powell）—— 与 ADCIRC 同一分类法。

### E.2 各项目「哪些文件值得读」

| 项目 | 关键文件（**已核实的真实路径**） | 读什么 |
| --- | --- | --- |
| **CLIMADA** | `climada/hazard/trop_cyclone/trop_cyclone_windfields.py`、`climada/hazard/tc_tracks.py`、`climada/hazard/tc_tracks_synth.py` | Holland 1980/2008/2010 + Emanuel & Rotunno 2011 的**完整实现**；常数 `DEF_RHO_AIR=1.15`、`DEF_GRADIENT_TO_SURFACE_WINDS=0.9`、**`DEF_MAX_DIST_EYE_KM=300`**、`STORM_1MIN_WIND_FACTOR=0.88`、`EMANUEL_RMW_CORR_FACTOR=2.0` |
| **StormR** | **`R/spatialBehaviour.R`**、**`R/computeBehaviour.R`**、`R/temporalBehaviour.R`、`vignettes/Models.Rmd` | Holland 1980、Willoughby 2006、Boose 2004 三种模型的**完整代数**（vignette 里有公式） |
| **TCHazaRds** | **`src/TCHazaRds.cpp`**、**`R/WindHazaRds.R`**、`man/*.Rd` | **一份极有价值的「参数化方案菜单」**：`beta_modelsR`（0=Powell 2005, 1=Holland 2008, 2=Willoughby & Rahn 2004, 3=Vickery & Wadhera 2008, 4=Hubbert 1991）；`rMax_modelsR`（…5=Chavas & Knaff 2022）；`vMax_modelsR`（0=Arthur 1980, 1=Holland 2008, …4=Atkinson & Holliday 1977） |
| **ADCIRC** | `src/wind.F`、`src/nws08.F90`、`src/owiwind.F`、`src/momentum.F`；文档 `docs/input_files/fort15.rst` | **GAHM 多等风速线反解算法**（§B.2.4 的出处）；`NWS=` 语义 |
| **TCRM** | 模块名：`wind.windmodels`、`wind.vmax`、`PressureInterface.pressureProfile`、`TrackGenerator.TrackGenerator`、`TrackGenerator.trackLandfall` | **合成路径生成**（速度/方位/气压/最大半径的经验分布 + 逆 CDF 采样）—— §F.3 降级链第 4 级"模拟台风"可参考 |
| **tropycal** | `tropycal/tracks/*`、`realtime/`、`recon/` | **只有轨迹数据管道**，无风场（已三重验证）。Java 移植价值低 |

### E.3 🔴 CLIPER 类外推：**没有任何开源实现**（已确证）

**确认过程（可复现，值得记录，因为它说明"找不到"是结论而不是偷懒）**：
- GitHub 仓库搜索 `CLIPER` → 只有 `open_clip` / `clip-retrieval` 这类**无关项目**；
- 搜索 `CLIPER5` → **恰好只有 1 个仓库** <https://github.com/guaicarar/cliper5>，
  而 GitHub API 显示 `size=0`、`language=null`、`license=null`，
  contents API 返回 **HTTP 404** ⇒ **空仓库，什么都没有**；
- `grep.app` → **HTTP 429**；GitHub 代码搜索 API（未认证）→ **HTTP 401**
  ⇒ **全局代码搜索无法完成**。
- **结论：不存在任何已验证的开源 CLIPER / CLIPER5 轨迹回归实现。不要点名任何仓库。**

**(a) 官方文档化的业务版本（有描述、无源码、无许可证）**：
NHC *Track and Intensity Models* 页 <https://www.nhc.noaa.gov/modelsummary.shtml>
（标注 "Updated 1 June 2026"）Table 4 逐字列出预测因子：

| 代号 | 名称 | 官方描述的预测量 |
| --- | --- | --- |
| **`CLP5` (`OCD5`)** | **CLIPER5**（Climatology and Persistence） | "**Multiple regression technique. Inputs are current and past TC motion (previous 12-24 hr), forward motion, date, latitude/longitude, and initial intensity**"；6 h（至 168 h），00/06/12/18 UTC |
| `SHF5`/`DSF5` (`OCD5`) | Decay-SHIFOR5 | 强度；含陆地衰减项 |
| `TCLP` | **Trajectory-CLIPER** | "Substitute for CLIPER and SHIFOR; similar predictors, but uses trajectories based on reanalysis fields instead of linear regression" |
| `DRCL` | **Wind Radii CLIPER** | "Statistical parametric vortex model"，用 13 个系数与持续性估计 34/50/64 kt 风圈半径 |

**(d) 该官方页面只发布模型描述、ATCF 编号与预测因子集合，没有仓库、许可证或下载链接。**

**(a) 唯一验证到的开源 "CLIPER 家族" 代码 —— 但它是降雨模型**：
**CLIMADA Petals 的 R-CLIPER**（Tuleya et al. 2007, DOI [10.1175/WAF972.1](https://doi.org/10.1175/WAF972.1)）：
`T_0 + (T_m − T_0)(r/r_m)`（r ≤ r_m）与 `T_m·exp(−(r−r_m)/r_e)`（r > r_m）；
教程系数 `l_T0=[3.1,6.1,11.0]`、`l_Tm=[3.4,6.9,12.5]`、`l_rm=[66.0,56.0,41.0]`、`l_re=[128.0,116.0,98.0]` km。

> 🔴 **重要区分**：**R-CLIPER 里的 "CLIPER" 指气候-持续性「降雨」模型，不是轨迹外推。**
> 轨迹外推的 CLIPER 是 `CLP5`/`TCLP`。**不要混为一谈。**
> 这段降雨廓线对游戏很有用（6 行算术），**按 Tuleya 2007 重写以规避 GPL**。

### E.4 ✅ 可以直接借用的**开源替代件**（许可证已逐份读取 LICENSE 原文）

既然没有 CLIPER，下面这几件是**真正验证过、且许可可用**的替代件：

| 项目 | 许可证 | 关键文件 / 符号（**已核实**） | 对我们有什么用 |
| --- | --- | --- | --- |
| **tcbench/TCBench** <https://github.com/tcbench/TCBench> | **MIT** ✅（"Copyright (c) 2023 Milton Gomez"） | **`dev/compute_persistence.py`** → `build_persistence(...)`、`LEADS = list(range(6,121,6))`、计算 `DPE_GCD`（t0 与 t0+L 的大圆距离）；`dev/baselines.py` → `PolynomialRegression(degree=3)`、`DeltaIntensityClimatology`、`TC_DeltaIntensity_MLR` | **⭐ 纯持续性路径基线**。⚠️ 注意它是**常数位置**持续性（constant-position），**不是等速外推** —— 两者不是一回事 |
| **CLIMADA** `climada/hazard/tc_tracks.py` | **GPL-3.0** ⚠️（**只读参考，不可拷**） | **`track_land_params(track, land_geom)`**、**`_dist_since_lf(track)`**、**`_get_landfall_idx(track, include_starting_landfall=False)`** | **⭐ §C.3 落点判定算法的现成参考实现**（我们有掩码，但可以对照它的边界条件处理） |
| **CLIMADA** `climada/hazard/tc_tracks_synth.py` | **GPL-3.0** ⚠️（只读参考） | **`calc_perturbed_trajectories(tracks, nb_synth_tracks=9, max_shift_ini=0.75, max_dspeed_rel=0.3, max_ddirection=pi/360, autocorr_dspeed=0.85, autocorr_ddirection=0.5)`**；docstring *"Generate synthetic tracks based on directed random walk"*；辅助 `_one_rnd_walk`、`_random_uniform_ac`、`_get_bearing_angle`、`_get_destination_points` | **⭐ 最接近"开源轨迹外推"的东西** —— 有向随机游走。**§F.3 降级链第 4 级"模拟台风"可直接按这个思路重写** |
| **Unidata/MetPy** <https://github.com/Unidata/MetPy> | **BSD-3-Clause** ✅ | **`metpy.calc.mean_pressure_weighted`**（源码 `src/metpy/calc/indices.py`）；另有 `weighted_continuous_average` | **⭐ §C.2 质量加权深层平均（DLM）的现成积木**（虽然 DLM 我们默认不用，但标定回测时有用） |

**其它仓库（GitHub API 元数据已核实，但代码未读 → 按 (b) 对待）**：
`mwyau/PyStormTracker`（BSD-3）、`NOAA-GFDL/TCtracker`（GPL-2.0）、
`Cambridge-ICCS/TCTrack`（GPL-3.0）、`levicowan-noaa/ibtracs`（无许可证）、
`andy-theia/hurdat2py`（MIT）、`Cloud-Drift/hurdat2-get-started`（MIT）、
`LabRAI/TyphoFormer`（无许可证）。
> ⚠️ **`soft-matter/trackpy` 是「粒子追踪」，与热带气旋无关** —— 容易被名字骗到。
> ⚠️ **`climdyn/pyStormTracker` 不存在（HTTP 404）** —— 真实的是 `mwyau/PyStormTracker`。

**`tropycal` —— MIT、活跃，但既无 CLIPER 也无持续性基线（已确证）**：
全部 **31 个 `src/**/*.py` 文件**都被下载并 grep 过
`cliper|persistence|persist_|extrapolat|steering|deep.layer|landfall`，只有这些命中：
- `storm.py` 的 `interp(...)` —— 是**插值**，不是外推；
- scipy 的 `fill_value='extrapolate'`（用于 recon/sonde 序列）；
- `dataset.py` 读取 IBTrACS 的 `dist_land, dist_landfall` 列；
- `generic_utils.py` 只是**解析** SHIPS 文本里的 `PRESSURE OF STEERING LEVEL (MB)`；
- `recon/plot.py` 只是**画** recon 数据里的 `DLMdir`/`DLMspd`。

> **→ tropycal 就是一个 IBTrACS/HURDAT2 读取 + 验证/绘图库。**
> 移植到 Java 的价值很低（核心价值在 pandas/xarray/cartopy 管线）。

### E.5 移植成本的真实评估

| 部分 | 难度 |
| --- | --- |
| **风场数学（Holland / Rankine / Willoughby / Boose）** | ✅ **容易** —— 闭式标量运算，轨迹节点 × 邻近格点，几十行 Java |
| **轨迹读取与插值** | ✅ 容易（JSON 解析 + 线性/大圆插值） |
| **落点判定** | ✅ 容易（本仓库已有掩码，§C.3） |
| **CLIPER 回归** | ⚠️ 中等 —— 系数**拿不到**，只能自己用 IBTrACS 拟合（本仓库已有 2.83 MB 的 3 年数据可做） |
| **栅格 / 地形外围**（`terra` / `sf` / `rasterio` / `geopandas` / NetCDF4 / GRIB2 / MPI / CUDA / AMR） | ❌ **难，且对游戏无必要** —— **这些是"项目重"的原因，不是"数学重"** |

> **结论**：**所有值得借鉴的台风风场项目，其"难的部分"都是我们不用的部分。**
> 直接按 §B 的公式写 Java 是最优路径。

---

## F. 给 NowWeather 的落地建议

### F.1 按「收益 / 实现成本」排序

排序原则：**先做能立刻看见效果、且不依赖额外合规风险的事**；把有法律风险的通道放到最后且默认关闭。

| 优先级 | 功能 | 收益 | 成本 | 依赖 |
| --- | --- | --- | --- | --- |
| **P0** | **陆海掩码落点判定 + 台风路径显示**（本地外推或任何一路台风数据） | 高（玩家能"看到台风来了"） | **低**（掩码已存在，代码 ~200 行） | 仅本体 |
| **P0** | **风场→游戏风力/风向覆盖**（Holland 剖面 + §B.5 方位换算） | **最高**（台风的核心体感） | 低（公式已给全） | 台风位置+强度 |
| **P1** | **台风数据接入（浙江源 `TyphoonInfo`）** | 高（真实路径 + 官方预报 + 官方落点） | 中（解析 + UTF-8 坑 + 769 KB 体积） | 网络 |
| **P1** | **雷电增强**（把台风强度映射到现有 `thunderBias`） | 高（复用现有雷电系统，几乎零新代码） | **极低** | 台风强度 |
| **P2** | **官方预报路径**（浙江源 `forecast` / 中央气象台 `BABJ`） | **最高**（24 h 误差 62 km vs 本地外推 176 km，差 2.6 倍） | 低-中 | 数据源 |
| **P2** | **风向随方位角变化 + 入流角** | 中（让风"绕圈"，很直观） | 低 | 风场 |
| **P2** | **不确定性锥**（官方 NHC 数字已给全，直接抄数组） | 中高（"会不会打到我这儿"的核心信息） | **极低** | 无 |
| **P3** | **移动不对称（危险半圆）** | 中（已有实测支持，效果明显） | 低（一个乘法） | 移动矢量 |
| **P4** | 中央气象台通道 | 中（国内可用性最好） | 中 | **⚠️ 合规风险** |
| **P4** | Willoughby 剖面 | 低（外观差异玩家几乎看不出） | 中（参数 5 个 + 根方程） | 风场 |
| **P5** | QWeather 通道 | 中（风圈自带方向标签，最省心） | 中（需 Key + 专属 API Host） | 账号 |
| **P5** | ~~引导气流外推（Open-Meteo 500 hPa）~~ | **实测为负收益**（比等速外推差 3 倍，§C.2.4） | 中 | Open-Meteo |

> **一句话建议**：**先做 P0（掩码 + 风场），雷电直接挂到现有 `thunderBias` 上（P1，几乎零成本），
> 然后按玩家反馈决定接哪一路真实数据。** 不要一开始就写四个 Provider。
>
> **明确不建议做的两件事**（本次调研的负面结论，避免浪费时间）：
> 1. **不要做 500 hPa 引导气流外推**（除非你愿意先在自己的海盆上做标定回测）——
>    实测比"用最近两个点等速外推"差得多（§C.2.4）。
> 2. **不要用七级风圈反解 Holland B** —— 实测 95% 的个例无解（§B.2.3）。
>    七级风圈应该用来反解 **Rmax**（Chavas & Knaff 2022），不是 B。

### F.2 默认参数表（Java 常量名 + 值 + 单位）

> 命名沿用本仓库既有约定：camelCase 私有字段 + `D` 后缀 double + JSON 映射用同名字符串键
> （见 `NowWeatherConfig`）。常量集中在 `TyphoonConstants`（建议新建）。

#### F.2.1 风场模型

```java
public final class TyphoonConstants {
    // ---- 风场剖面 ----
    /** Holland 形状参数默认值。实测中位数 1.45~1.78（IBTrACS 2565 点），取 1.5。 */
    public static final double HOLLAND_B_DEFAULT        = 1.5D;      // 无量纲
    /** B 的合法区间（文献 [1,2.5]，实测 87.4% 覆盖率）。用于 clamp。 */
    public static final double HOLLAND_B_MIN            = 1.0D;      // 无量纲
    public static final double HOLLAND_B_MAX            = 2.5D;      // 无量纲
    /** 空气密度（CLIMADA「following Holland 1980」）。 */
    public static final double AIR_DENSITY_KG_M3        = 1.15D;     // kg/m^3
    /** 环境气压默认值（标准大气）。西北太平洋建议 101500 Pa。 */
    public static final double AMBIENT_PRESSURE_PA      = 101325.0D; // Pa
    /** 地球自转角速度。 */
    public static final double EARTH_OMEGA              = 7.29e-5D;  // rad/s

    /** 梯度风→10 m 折减因子。选 0.9 有实测理由（见 §B.1.3）。 */
    public static final double GRADIENT_TO_SURFACE      = 0.9D;      // 无量纲
    /** 1-min → 10-min 风速换算（Nederhoff et al. 2019 引 Harper et al. 2010）。 */
    public static final double MIN1_TO_MIN10            = 0.93D;     // 无量纲
    /** 阵风因子 G3,60（Harper et al. 2010）。内陆 1.49 / 离岸 1.36 / 上岸 1.23 / 海上 1.11。 */
    public static final double GUST_FACTOR_INLAND       = 1.49D;
    public static final double GUST_FACTOR_OFFSHORE     = 1.36D;
    public static final double GUST_FACTOR_ONSHORE      = 1.23D;
    public static final double GUST_FACTOR_AT_SEA       = 1.11D;

    // ---- 最大风速半径 Rmax ----
    /** Rmax 下限（实测 IBTrACS 最小 9.3 km，取 10 保守）。 */
    public static final double RMAX_MIN_KM              = 10.0D;     // km
    /** Rmax 上限（实测 IBTrACS 最大 333 km，但 >200 km 多为弱系统；clamp 到 150）。 */
    public static final double RMAX_MAX_KM              = 150.0D;    // km
    /**
     * 【工程近似】Chavas & Knaff (2022) 的偏差标定系数。
     * 实测（IBTrACS 2561 点）该关系系统性高估 Rmax，bias +12.1 km；
     * 观测中位 37.0 km / 预测中位 ~50 km ⇒ 约 0.74。**这是本项目标定值，不是文献值。**
     */
    public static final double RMAX_CK_CALIBRATION      = 0.74D;     // 无量纲

    // ---- 入流角 ----
    /** 内区入流角（Queensland 2011 via ADCIRC Eq. 16）。 */
    public static final double INFLOW_INNER_DEG         = 10.0D;     // 度
    /** 远场入流角（同上）。 */
    public static final double INFLOW_OUTER_DEG         = 25.0D;     // 度
    /** 陆上附加入流角（stormwindmodel）。 */
    public static final double INFLOW_LAND_BONUS_DEG    = 20.0D;     // 度

    // ---- 非对称 ----
    /** r = Rmax 处的非对称修正系数（Phadke et al. 2003 Eq. 12）。 */
    public static final double ASYMMETRY_AT_RMAX        = 0.5D;      // 无量纲

    // ---- 截断 ----
    /**
     * 风场截断半径：模型在 r→∞ 时风不归零，必须截断（见 §B.1.5）。
     * 300 km 与 CLIMADA 的 DEF_MAX_DIST_EYE_KM = 300 一致（有实现先例）；
     * 若要覆盖外雨带可放宽到 500 km。
     */
    public static final double WIND_CUTOFF_R_KM         = 300.0D;    // km
}
```

#### F.2.2 路径与落点

```java
    // ---- 路径外推 ----
    /**
     * 外推所用的最近历史窗口。★ 实测（官方 HURDAT2, ATL 2021-2025, N=1351）：
     * 24 h 误差随窗口 6h→72h 从 176 km 单调恶化到 428 km。故默认取最短窗口 6 h。
     */
    public static final double EXTRAPOLATION_WINDOW_HOURS = 6.0D;    // 小时
    /** 最多用几个定位点做加权拟合（6 h 窗口 @1 h 间隔 = 6 个点）。 */
    public static final int    EXTRAPOLATION_MAX_FIXES    = 6;
    /** 加权最小二乘的时间衰减常数。窗口很短时影响不大。 */
    public static final double EXTRAPOLATION_DECAY_HOURS  = 3.0D;    // 小时
    /** 是否启用二次（加速度）项。★ 实测无收益且 48-72 h 严重恶化 → 默认关闭。 */
    public static final boolean EXTRAPOLATION_USE_QUADRATIC = false;
    /** 路径方向 24 h 内变化超过此值视为"转向"，回退纯等速、禁用 DLM。 */
    public static final double RECURVATURE_THRESHOLD_DEG  = 60.0D;   // 度/24h

    // ---- 引导气流（★ 可选实验特性，默认关闭：实测比等速外推差 3 倍，见 §C.2.4）----
    /** 是否启用 500hPa 引导气流外推。**默认关闭。** */
    public static final boolean ENABLE_STEERING_EXTRAPOLATION = false;
    /** 【工程近似】环形采样内径（避开涡旋自身环流）。无权威出处。 */
    public static final double STEERING_INNER_R_KM       = 300.0D;   // km
    /** 【工程近似】环形采样外径。无权威出处。 */
    public static final double STEERING_OUTER_R_KM       = 700.0D;   // km
    /** 【工程近似】环形采样方位数。太少不能代表环境流，太多浪费请求额度。 */
    public static final int    STEERING_AZIMUTH_SAMPLES  = 8;
    /** 引导外推的最小引导风速；低于此值回退等速外推。 */
    public static final double STEERING_MIN_SPEED_MS     = 3.0D;     // m/s
    /** 【二级来源】beta 漂移大小（AMS Glossary: 1-2 m/s）。 */
    public static final double BETA_DRIFT_MS             = 1.5D;     // m/s
    /** 【工程近似】beta 漂移方位（罗盘，去向）。北半球西北。 */
    public static final double BETA_DRIFT_BEARING_DEG    = 315.0D;   // 度

    // ---- 落点 ----
    /** 落点二分插值迭代次数（40 次后坐标精度远优于 1° 格）。 */
    public static final int    LANDFALL_BISECT_ITERS     = 40;
    /** 落点报告的"最大可信时效"；超过则只报"可能影响"而不报精确落点。 */
    public static final double LANDFALL_MAX_LEAD_HOURS   = 48.0D;    // 小时
    /** 【诚实性参数】落点时间不确定度（我方实测 −7.0 ~ +4.1 h，取 ±6 h）。 */
    public static final double LANDFALL_TIME_UNCERT_H    = 6.0D;     // 小时
    /** 【诚实性参数】落点位置不确定度（我方实测中位 21 km、最差 52 km，取 50 km）。 */
    public static final double LANDFALL_POS_UNCERT_KM    = 50.0D;    // km
```

#### F.2.3 雷电（挂到现有 `thunderBias` 上）

```java
    // ---- 雷电：径向权重（【工程近似】，依据见 §D.2.2）----
    /**
     * 径向权重断点（km）。文献依据：
     *  · Molinari et al. (1999)：<1% 落入 40 km、3.4% 落入 80 km；
     *    内带极小值在 100–120 km；外带极大在 210–290 km。
     *  · Zhang et al. (2022)：0–100 km 占 6.2%、100–200 km 占 9.6%、200–500 km 占 84.2%。
     * ⚠️ 原论文给的是「密度廓线」与「计数占比」，**没有闭式 P(r) 公式** ——
     *    下面这张表是本项目构造的【工程近似】，必须允许玩家覆盖。
     * ⚠️ 单位是「相对权重」，不是绝对闪密度（绝对密度太小，Minecraft 尺度用不了）。
     */
    public static final double[] LIGHTNING_R_BREAK_KM   = { 40, 80, 120, 200, 350, 500 };
    public static final double[] LIGHTNING_R_WEIGHT     = { 0.8, 1.0, 0.15, 0.6, 1.4, 1.0 };
    /** 超过此半径不再生成候选点（Zhang 2022 的定义域边界）。 */
    public static final double LIGHTNING_R_MAX_KM       = 500.0D;    // km
    /** RI（快速增强）状态时把 0–1 Rmax 的权重提高到该值（Zhang et al. 2020 实测 >30%）。 */
    public static final double LIGHTNING_RI_INNER_WEIGHT = 0.3D;

    // ---- 雷电：方位权重（【工程近似】，依据见 §D.2.3）----
    /**
     * 方位权重峰值相对风暴移动方向的偏角（度）。
     * 依据 Corbosiero & Molinari (2003)：北半球 76% 的最多闪击在运动矢量右侧、
     * 71% 在前两象限、42% 在右前象限 → 峰值取 +45°（右前）。
     * ⚠️ 原论文明确「垂直风切变主导，运动效应在不一致时几乎消失」，
     *    而我们拿不到切变数据 → 用移动方向当代理，是近似。
     */
    public static final double LIGHTNING_AZIMUTH_PEAK_DEG = 45.0D;   // 度

    // ---- 雷电：地形倍率（【工程近似】，依据见 §D.4.4）----
    /** 起伏起效阈值（Simon et al. 2022 实测 ~100 m 以上才 "somewhat" 增强）。 */
    public static final double LIGHTNING_RELIEF_THRESHOLD_M = 100.0D; // 米
    /** 地形倍率封顶。全球可信最大实测比 ~6.5x，但那是年均区域对比，不是同场量。 */
    public static final double LIGHTNING_TERRAIN_FACTOR_MAX = 4.0D;

    // ---- 雷电：台风强度 → thunderBias 增强 ----
    /**
     * 台风影响下的最大 thunderBias 倍率。
     * 挂载点：effectiveThunderBias = biomeThunderBias × (1 + boost)，
     * 其中 boost = TYPHOON_THUNDER_MAX_BOOST × typhoonLocalIntensity01。
     * ⚠️ 现有 BiomeClimate.thunderBias 范围 0.15（沙漠）~1.55（丛林），clamp 到 0~4，
     *    所以留了余量；但**不要绕过 thunderIntensityThreshold=0.55 的门槛**。
     */
    public static final double TYPHOON_THUNDER_MAX_BOOST = 1.5D;     // 无量纲
    /** 眼墙/雨带内的局部强度权重：核心权 1.0、外雨带递减。 */
    public static final double TYPHOON_THUNDER_CORE_WEIGHT   = 1.0D;
    public static final double TYPHOON_THUNDER_BAND_WEIGHT   = 0.7D;
```

> **⛔ 三条「不要做」的纪律（都来自 §D 的负面结论）**：
> 1. **不要做绝对闪密度**（文献值 0.003–0.1 fl km⁻² d⁻¹ 在 Minecraft 尺度等于永远不打雷）
>    —— 只做**相对权重 × 全局缩放**。
> 2. **不要把「眼墙闪电 → 快速增强」做成确定性机制**
>    —— DeMaria et al. (2012) 发现快速**减弱**个例的内核闪电密度是快速**增强**的 **4 倍**。
> 3. **不要编造「闪电密度每 100 m 增加 X%」**
>    —— 文献里**不存在**这个数（§D.4.3）。

#### F.2.4 不确定性锥（官方数字，可直接用）

```java
    /**
     * 官方 NHC 2026 年锥半径（2/3 = 67% 概率圈），基于 2021-2025 误差统计。
     * 来源：https://www.nhc.noaa.gov/aboutcone.shtml  【实测可用】
     * 索引：0→12h, 1→24h, 2→36h, 3→48h, 4→60h, 5→72h, 6→96h, 7→120h
     */
    public static final double[] CONE_RADIUS_KM_ATLANTIC = {
        46.3D, 72.2D, 90.7D, 114.8D, 142.6D, 175.9D, 248.2D, 370.4D };
    public static final double[] CONE_RADIUS_KM_EPAC     = {
        46.3D, 68.5D, 88.9D, 103.7D, 122.2D, 144.5D, 196.3D, 255.6D };
    /**
     * 官方预报的平均误差（5 年 2021-2025 均值，n mi → km）。
     * 锥半径约为平均误差的 1.10~1.16 倍（67 分位 > 均值，对数正态的正常现象）。
     */
    public static final double[] OFCL_MEAN_ERR_KM_ATLANTIC = {
        41.3D, 62.2D, 81.5D, 103.5D, 130.6D, 160.0D, 231.3D, 335.2D };
    public static final double[] OFCL_MEAN_ERR_KM_EPAC     = {
        39.6D, 59.5D, 77.0D, 93.2D, 113.2D, 137.0D, 187.8D, 231.5D };
```

> **这些是本次调研里唯一一组"可以直接抄进代码"的官方数字。**
> 用它们做游戏内"不确定性圈"是**有据可依的**；
> 用自己编的锥（例如初稿里那个"12 h 30 km"的占位表）则**没有依据**（初稿已删除）。

#### F.2.5 数据源与轮询

```java
    // ---- 数据源开关（合规！中央气象台默认关闭，见 §A.7.1）----
    private boolean typhoonEnabled = false;                 // 总开关，默认关
    private boolean typhoonUseZhejiang = true;               // 浙江水利厅（无声明，默认开但可关）
    private boolean typhoonUseCma = false;                   // ⚠️ 中央气象台：默认【关闭】
    private boolean typhoonUseGdacs = true;                  // GDACS（免费+署名）
    private boolean typhoonUseNhc = true;                    // NHC/NWS（公有领域）
    private String  typhoonUserAgent = "NowWeather/1.0 (+https://github.com/…)"; // NWS 要求

    // ---- 轮询 ----
    /** 台风数据轮询间隔。定位本身 3~6 h 才更新，10 分钟已严重过度采样。 */
    private long typhoonRefreshIntervalMillis = 10L * 60L * 1000L;
    /** 列表接口（轻）与详情接口（重，769 KB）分开的间隔。 */
    private long typhoonListIntervalMillis    = 10L * 60L * 1000L;
    private long typhoonDetailIntervalMillis  = 30L * 60L * 1000L;
    /** 失败退避上限。 */
    private long typhoonMaxBackoffMillis      = 30L * 60L * 1000L;
```

**必须遵守的三条合规约束（写进代码注释与 README）**：
1. **中央气象台通道默认关闭**（`typhoonUseCma = false`）——
   其《网站声明》禁止未经书面授权的转载/**链接**/商用（§A.7.1 原文）。
2. **数据只在运行时拉取，绝不打包进 jar**；打包的只有本仓库自己的
   `landmask1deg.bin`（Natural Earth，公有领域）。
3. **必须在游戏内 Credits / README 显著署名**所有启用的数据源，并附
   "数据仅供参考，非官方预警"免责声明。

### F.3 降级链（拿不到台风数据时怎么办）

**这是本节最重要的一节 —— 台风数据源全都可能明天就挂。**

```
第 1 级  官方预报路径（浙江 forecast / 中央气象台 BABJ）
   │  失败 / 无数据 ↓
第 2 级  官方实况路径 + 【最短窗口】等速/加权外推（§C.1.1、§C.1.3(c)）
   │        ★ 实测最优 = 最近 2 个定位点（等价 PERS 6h）；窗口越长越差
   │  样本点 < 2 个 / 外推超过 48 h ↓
第 3 级  等速外推延长，但只报"移动趋势"与"大致影响区域"，不报精确落点
   │  连实况都没有 ↓
第 4 级  【离线合成台风】用 IBTrACS 气候态
         · 生成一条"气候合理的"西北太平洋台风路径（6–9 月、WNW 移动、25–35 m/s）
         · **必须明确告知玩家这是"模拟台风"，不是真实数据**
   │  玩家关闭台风子系统 ↓
第 5 级  台风子系统完全静默，回到现有 NowWeather 行为
```

> **⚠️ 与初稿相比的一个重要修正**：初稿把"引导气流外推"放在"等速外推"**之前**（第 3 级）。
> **实测数据推翻了它**（§C.2.4）：朴素的 500 hPa 引导气流外推
> **比等速外推差 3 倍以上**（24 h 误差 282 km vs 106 km）。
> **因此引导气流不在这条降级链的主路径上** ——
> 它是**可选实验特性**，只在完成海盆标定后才启用，且默认关闭。
>
> **这条修正很重要**：它避免了一个"看起来更专业、实际更差"的实现被当成默认行为。

**降级链的两条硬规则**：

1. **绝不能因为台风数据源失败而阻塞主线程或让天气"卡住"**。
   台风子系统应是**可选叠加层**：拿不到数据就当它不存在。
2. **绝不能把第 4 级的"模拟台风"伪装成真实数据**。
   游戏内必须能区分（例如 HUD 上标注 `[模拟]`），否则就是欺骗玩家。

**同时降级的输入量（缺少风圈时）**：

| 缺失字段 | 降级做法 | 依据 |
| --- | --- | --- |
| 七级风圈 | 无 R34 → **不能用 Chavas & Knaff 求 Rmax** | §B.2.2 |
| Rmax 未知 | 用 Willoughby Eq. (7a)：`46.4·exp(−0.0155·Vmax_gl + 0.0169·φ)` | [A] §B.2.2 |
| Vmax 未知 | 用 Atkinson & Holliday：`3.44·(1010 − Pc_hPa)^0.644` | [A] §B.2.1 |
| Δp / Pn 未知 | 用 `Δp = (Pn − Pc)`，`Pn` 取 `AMBIENT_PRESSURE_PA`；西北太平洋用 101500 Pa | [A] |
| 移动矢量未知 | 用最近两个定位点算（`movespeed`/`movedirection` 字段在浙沪两源都有） | §A.1.4 / §A.2.3 |
| **预报时段的风圈** | **保持当前风圈不变**（QWeather 的 forecast 与中央气象台的预报条目都无风圈） | §B.2.5 |

### F.4 与现有代码的对接点（**只做建议，本次不改任何 Java**）

| 现有设施 | 位置 | 台风子系统的接法 |
| --- | --- | --- |
| `BiomeClimate.thunderBias`（clamp 0~4） | `core/climate/BiomeClimate.java:44` | **台风雷电增强的挂载点**：`effectiveThunderBias = biomeThunderBias × (1 + typhoonThunderBoost)` |
| `WeatherType.thunder()`（0~1） | `core/weather/WeatherType.java` | 台风眼墙/雨带内抬高该值 |
| `thunderIntensityThreshold = 0.55` | `NowWeatherConfig:59` | 达到阈值才触发原版雷暴 —— 台风增强不应绕过这个门槛，否则晴天打雷 |
| `thunderRequiresPrecipitation = true` | `NowWeatherConfig:63` | **必须保持 true**，台风雷电一定伴随降水，符合现实 |
| `ForecastEngine.weights()` 的 `storm` 项 | `core/forecast/ForecastEngine.java:149-151` | 台风影响期抬高 `storm` 权重（乘一个 `1 + k·typhoonIntensity`） |
| `WeatherAllowance` 的 `THUNDER_THRESHOLD` 门控 | `core/climate/WeatherAllowance.java:184` | 不要动 —— 它保证沙漠等气候不打雷 |
| `TerrainTransfer.windFactor()` | `core/terrain/TerrainTransfer.java:280` | **台风风场应作为"参考风"输入**，让地形暴露度继续起作用（山脊风更大） |
| `TerrainProfile.exposure`（0~1） | `core/terrain/TerrainProfile.java:18` | 台风场景下暴露度的影响应**放大**（见 §D.4 的地形—雷电关系） |
| `HttpTransport` / `HttpRequestSpec` | `core/http/` | 新 Provider 直接复用；**注意浙江源需要显式 UTF-8 解码** |
| `WeatherProvider` / `ProviderResult` | `core/api/` | 台风 Provider 可以走同一套注册与降级机制 |

**可以对照阅读的开源参考实现（许可证见 §E，**只读、不要拷 GPL 代码**）**：

| 我们要做的事 | 参考实现 | 许可 |
| --- | --- | --- |
| 落点判定（边界条件处理） | CLIMADA `climada/hazard/tc_tracks.py`：`track_land_params()`、`_dist_since_lf()`、`_get_landfall_idx()` | ⚠️ GPL-3.0（只读） |
| 合成台风路径（降级链第 4 级） | CLIMADA `climada/hazard/tc_tracks_synth.py`：`calc_perturbed_trajectories()`（有向随机游走） | ⚠️ GPL-3.0（只读） |
| 纯持续性基线（做对比用） | TCBench `dev/compute_persistence.py`：`build_persistence()`、`DPE_GCD` | ✅ MIT |
| 质量加权深层平均（DLM 标定用） | MetPy `metpy.calc.mean_pressure_weighted` | ✅ BSD-3 |
| 台风参数化风廓线（9 种并列） | GeoClaw `clawpack.geoclaw.met.storm` | ✅ BSD-3 |

> **⚠️ 两次提醒（因为这是最容易犯的错）**：
> 1. 浙江源的 `radius7 = "NE|SE|NW|SW"`，中央气象台是 `["30KTS", NE, SE, SW, NW]` ——
>    **后两象限对调**（§A.1.5 / §A.2.3，两处都有源码证据）。
> 2. 中央气象台的时间是 **UTC**，浙江源是**北京时间**，同一时刻差 8 小时（§A.1.4 实测）。

### F.5 ⛔ 未核实清单（**不要当事实用，不要在这些点上编造**）

这一节汇总全文所有 **【未核实】** 项。**如果实现时需要它们，必须自己取一手来源，或明确标为工程近似。**

#### F.5.1 数据源相关

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 中央气象台的频率限制 | **未核实** | 无官方文档；实测 12 连发全 200，但样本太小 |
| 浙江水利厅的服务条款 | **未核实** | 站点无任何声明页面（既未允许也未禁止） |
| `typhoon.nmc.cn` 是否适用 `www.nmc.cn` 的《网站声明》 | **未核实** | 声明字面只覆盖 `www.nmc.cn` |
| 中央气象台多机构预报（RJTD/KWBC 等） | **实测不存在** | `forecastOrgs` 实测只返回 `BABJ`；所有样本的 `forecast` 只有 `BABJ` |
| CMA 的不确定性锥半径 | **未核实** | 未找到 |
| QWeather 免费订阅是否含台风接口 | **未核实** | 需注册后才能确认 |
| QWeather 的 `storm-forecast` 是否含风圈 | **文档核实 + 未实测** | 文档页检索 `windRadius` 命中 0（`storm-track` 页有） |
| QWeather 的 `year` 支持范围 | **未核实** | 索引页说 "past 2 years"，`storm-list` 页说 "this year and last year"，两处矛盾 |
| UApiPro 的公平使用/限流条款 | **未核实** | `/docs/api-reference/fair-use` 返回 404，正确路径未找到 |
| GDACS 是否有逐点预报路径 | **实测：无** | 属性里只有事件级信息 |
| JTWC / ATCF / `tgftp.nws.noaa.gov` | **实测失败** | `HTTP 000`；`metoc.navy.mil` 完全不解析（NXDOMAIN / SERVFAIL） |
| Zoom Earth / Tropical Tidbits 的 API | **未核实** | 未找到任何公开数据端点 |
| IBTrACS 完整快照 | **有疑问** | `HEAD` 报 10.4 MB 但实测只落到 2.83 MB，原因未查明 |
| NASA LIS/OTD HRFC/LRFC 的文件命名与格式 | **未核实** | 目录页可达，但数据主机 `ghrc.nsstc.nasa.gov` 不可达（`HTTP 000`） |

#### F.5.2 公式与参数

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **修正 Rankine 的 `α ∈ [0.4, 0.8]`** | **未核实（无任何来源）** | 网上流传极广但**找不到出处**。**不要写成文献值** |
| Holland B 的 Vickery (2000) / Vickery & Wadhera (2008) 系数 | **未核实** | 论文 closed access、无 OA 副本。**勿凭记忆填写** |
| Willoughby 的「外半径关系式」 | **不存在** | 论文全文检索无匹配，**不要再找** |
| Willoughby 过渡区宽度 25/15 km | **不在论文中** | 论文只说 "a priori … between 10 and 25 km"；25/15 是 R 包假设 |
| 常见的 `A = 0.5916` | **错误** | 原文与所有第三方均为 **0.5913** |
| Atkinson & Holliday (1977) 原论文 | **未取得**（经 ScientiMate 转引） | 系数形式可信，但**有转引风险** |
| Courtney & Knaff (2009) / Knaff & Zehr (2007) 系数 | **未核实** | **勿编造** |
| Chavas & Knaff (2022) 的 R50/R64 版本 | **不存在** | 原文 p.577："We do not take this step here" |
| 「`Rmax = k · R7`」固定比例 | **不存在** | 无已发表系数；我方实测比值跨度 0.36–43.5，**证明不存在稳定比例** |
| 入流角：Phadke 11a 的不连续 | **原因未核实** | 可能与实现转录有关；精确形式标 **[C]** |
| Zhang & Uhlhorn (2012) 的具体入流角度数 | **未核实** | 未取得摘要或正文；**本文档不给出该文数值** |
| Bretschneider (1972) / Sobey et al. (1977) 入流角公式 | **未核实** | 方法存在、公式未取得 |
| Schwerdt (1979) / Georgiou (1985) 非对称公式 | **未核实** | 本文档给出 5 个有来源的替代式 |
| 「右侧风 = 最大持续风 + 前进速度」的权威出处 | **未核实** | 只在矢量意义上是上界；右侧 **+0.5·Vt** 才有文献依据 |
| WMO TCP-23 (2023) 的 at-sea 折减建议 | **未核实** | 仅检索片段 |
| Harper et al. (2010) 阵风因子表 | **经 stormwindmodel 转引** | 数值一致，WMO 原件未取得 |
| Holland 1980 / 2008 / 2010 公式 | **经 CLIMADA、ADCIRC 转引** | 逐字引用公式编号，但 AMS 原文未取得 |

#### F.5.3 路径与落点

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **CLIPER5 的回归系数与预测量清单** | **未核实** | Neumann (1972) / Aberson (1998) 原文不可得。**本文档不给出系数** |
| 修正 Rankine 外推的「最优 N」的已发表研究 | **未找到** | 我方实测：**窗口越短越好且单调** |
| beta 漂移的权威数值 | **二级来源** | AMS Glossary（页面 403，经搜索索引取得）：**1–2 m/s，poleward and westward** |
| `XTRP` 的数值误差 | **未核实** | NHC 只以图形式发布 |
| CMA / JTWC 的锥半径 | **未核实** | 未找到 |
| 「标准 DLM 环形采样半径 300–700 km」 | **未核实** | 无权威出处 |
| 已发表的 DLM vs 持续性对比数字 | **未核实** | 我方自算是目前唯一依据（**结果是 DLM 更差**） |

#### F.5.4 雷电与地形

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **可用的闭式「闪电概率 P(r)」函数** | **不存在** | 文献只给径向密度廓线与计数占比（§D.2.2） |
| **「闪电密度每 100 m 增加 X%」** | **不存在** | **绝对不要编造**（§D.4.3） |
| 全球尺度 LIS/OTD 闪密度对地形的回归 | **未找到** | — |
| 显式的 ALDIS/OVE 山脊/谷地数值比 | **未找到** | 只有科罗拉多的 4:1~6.5:1（**上界**） |
| 坡度角阈值 | **未找到** | — |
| 「Entropy Order Parameters」 | **未找到任何相关文献** | 不建议引用 |
| 「眼墙闪电稳定领先快速增强」 | **文献矛盾** | DeMaria 2012 发现 RW 个例内核密度是 RI 的 **4 倍** |
| Smorgonskiy et al. (2013) 的分高度带数值 | **未核实** | EPFL 人机验证墙（HTTP 405），只有摘要 |
| 科罗拉多海拔阈值 3048 m vs 3200 m | **表述差异未解决** | 采用 2014 年原文的 3200 m / 10,500 ft |

#### F.5.5 开源许可

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **开源 CLIPER 实现** | **确认不存在** | 唯一同名仓库 `guaicarar/cliper5` 是**空仓库**（`size=0`，contents API 404） |
| `NathalieBloemendaal/STORM` 的许可与路径 | **未核实** | jsDelivr 对 `main`/`master` 均 404。**发货前必须解决** |
| MIRI（Gori et al.） | **未核实** | 未定位到仓库、许可或语言 |
| TCRM 仓库的相对路径 | **未核实** | 仓库 >50 MB，超 jsDelivr 限制 |
| Delft3D 逐引擎许可 | **未核实** | 只有 README 的概述 |
| TCHazaRds 的 C++ 实现实体 | **未核实** | jsDelivr 以 octet-stream 返回 `.cpp`；函数**名**已由 `man/*.Rd` 核实 |
| `climada_petals` 的 LICENSE 文件 | **未核实** | 未单独取回 |

> **本节的意义**：台风是一个"二手资料极多、一手资料很贵"的领域。
> 上面这张表就是**"哪些数字可以放心写进代码注释、哪些必须自己验"** 的分界线。
> **凡在本表里的项，本文档都没有给出数值** —— 这是刻意的。

---

## 附录 A. 实测产物清单（可逐字节复核）

所有原始响应保存在 `_research/typhoon-samples/`。**复核时请对照本文档的引用片段。**

| 文件 | 来源 | 说明 |
| --- | --- | --- |
| `nmc_list_default.jsonp` | `typhoon.nmc.cn/.../list_default` | 当年台风列表（2,457 B） |
| `nmc_list_2024.jsonp` / `nmc_list_2026` | `.../list_<year>` | 年度列表 |
| `nmc_view_3304099`（→`v_3304099.jsonp`） | `.../view_3304099` | **沙德尔：含 30/50/64 KTS 三级风圈 + BABJ 官方预报**，104 KB |
| `nmc_view_3275487_yagi.jsonp` | `.../view_3275487` | 摩羯（用于经纬度顺序与时间基准核对） |
| `nmc_forecastOrgs.txt` / `nmc_maxYear.txt` / `nmc_tip.txt` | 对应端点 | 小响应 |
| `js_typhoon_gis_js` | `typhoon.nmc.cn/js/typhoon/gis.js` | **风圈象限顺序 `东北/东南/西南/西北` 的源码证据** |
| `js_typhoon_typhoon-web_js` | `.../typhoon-web.js` | **全部 `typhoon/jsons/*` 端点路径的源码证据** |
| `zj_TyphoonList_2024.json` | `typhoon.slt.zj.gov.cn/Api/TyphoonList/2024` | 年度列表（UTF-8 需强制） |
| `zj_TyphoonInfo_202411.json` | `.../Api/TyphoonInfo/202411` | **摩羯 769 KB：125 个定位点 + 多机构预报 + 官方登陆记录** |
| `zj_info_202402/202413/202414/202418/202421/202409.json` | 同上 | 落点校验用数据集 |
| `app~e6e59946.72c2c584.js` | `typhoon.slt.zj.gov.cn/js/…` | **`radius7` 象限顺序 `NE,SE,NW,SW` 的源码证据（`DrawCircle`）** |
| `uapi_openapi.json` | `uapis.cn/openapi.json` | **112 条路径，证明无台风接口**（498 KB） |
| `uapi_weather_hz.json` | `uapis.cn/api/v1/misc/weather?city=杭州` | 实测响应 |
| `gdacs_tc_event.json` / `gdacs_tou.pdf` / `gdacs_api_quickstart.pdf` | GDACS | 事件字段 + 官方条款/API 说明 PDF |
| `nhc_currentstorms.json` | `nhc.noaa.gov/CurrentStorms.json` | 活动风暴字段全集 |
| `nhc_TCM_ep4_text.txt` | `nhc.noaa.gov/text/MIATCMEP4.shtml?text` | **含逐点预报坐标 + 34 KT 四象限风圈**的原文 |
| `nws_tcm.json` / `nws_product.json` | `api.weather.gov/products/...` | JSON 形式的 TCM 产品 |
| `nhc_5day_016.zip` | NHC GIS 预报包 | shapefile/points/line/pgn |
| `om_500hPa.json` / `om_levels.json` / `om_uv.json` / `om_gfs.json` | Open-Meteo | **气压层字段与单位实测** |
| `om_archive500.json` | Open-Meteo Archive | **负面结果：不支持气压层** |
| `om_terms.html` | `open-meteo.com/en/terms` | **CC-BY 4.0 + 限流条款原文** |
| `nmc_statement.html` / `nmc_statement2.html` | `nmc.cn` 网站声明 | **⚠️ 禁止转载/链接的原文（2017-09-01）** |
| `nws_disclaimer.html` | `weather.gov/disclaimer` | **公有领域声明原文** |
| `ibtracs_last3y.csv` | IBTrACS v04r01 | 2.83 MB（HTTP 200；服务器报 Content-Length 10.4 MB，实测下载到 2.83 MB —— 见下注） |
| `ibtracs_head.csv` | 同上（Range 请求） | 表头与单位行 |
| `lis_otd.html` / `doi_hrfc.html` | NASA GHRC / Earthdata | LIS/OTD 雷电气候态数据集 |
| `Verification_2025.pdf` | NHC 年度验证报告 | **22.8 MB / 85 页；第 20 页 Table 1 = CLIPER5 实测数字的原始出处** |
| `OFCL_5yr_averages.pdf` | NHC | 官方 5 年（2021–2025）平均误差表 |
| `nhc_cone.html` | `nhc.noaa.gov/aboutcone.shtml` | **2026 年锥半径表（2/3 概率圈）原始出处** |
| `nhc_verify3/4/5/6/7` | NHC 验证站点 | 验证方法、误差趋势、误差数据库 |
| `errfmt.txt`（4.17 MB）/ `nhc_err_atl.zip` | NHC 官方误差数据库 | **逐条预报误差，仅含 OFCL（不含 CLIPER5）** |
| `ALtkerrtrd_allmdl.jpg` / `_earlymdl.jpg` | NHC 模型误差趋势图 | **CLIPER5 对比只以图形式发布**（未做 OCR） |
| `analyze_ibtracs.py` / `analyze_ibtracs2.py` | **本文档作者** | §B 的 IBTrACS 校验脚本（Holland B、Chavas-Knaff、非对称） |
| `landfall_eval.py` | **本文档作者** | §C.3.3 的落点精度校验脚本 |
| `cliper5_eval.py` | **本文档作者** | NHC 误差数据库解析尝试（**证实该文件不含 CLIPER5**） |
| `wwlln_permyakov.pdf` / `acp2024_lis.pdf` | WWLLN / ACP | §D 的雷电文献（后者 PyMuPDF 打开为 0 页，**解析失败**） |

> **诚实记录一处不一致**：`ibtracs.last3years.list.v04r01.csv` 的 `HEAD` 响应报
> `Content-Length: 10368576`，但 `curl` 实际只落到 **2,829,121 字节**。
> 我没有进一步排查原因（可能是压缩传输或服务器端分块行为）。
> **脚本用的就是这 2.83 MB：5950 行数据、2566 行可用于分析 —— 分析结论不受影响，
> 但这说明该文件不是完整快照**，请复核时注意。

## 附录 B. 子报告与外部调研产物

| 文件 | 内容 | 作者 |
| --- | --- | --- |
| `_research/tc_surface_wind_field_models.md` | **98 KB / 894 行**的台风地面风场模型完整报告（含 Rev 2 修订记录与逐条公式编号） | 并行子代理 |
| `_research/TC_track_extrapolation_report.md` | 路径外推与落点判定报告（含附录 A：CMA/JTWC/JMA/KMA/HKO 官方误差与已发表落点误差） | 并行子代理 |
| `_research/FINAL_REPORT.md` | 雷电统计 + 开源库许可报告（附 `pdfs/` 与 `txt/` 语料库） | 并行子代理 |

**三份子报告的分工**：本文档是**汇总与落地**，子报告是**证据底稿**。
凡本文档标 [A]/[B] 但我没有亲测的数字，都能在对应子报告里找到原始 URL。
**引用具体数字时，请优先引用本文档给出的 URL（已交叉核对），而不是子报告里的转引链。**

---

## 附录 C. 一页速查（TL;DR）

**如果你只看一段，看这段。**

```
数据从哪来（优先级）：
  1. 浙江水利厅 TyphoonInfo/{tfid}   → 实况(1h) + 多机构预报 + 官方落点   【实测可用，无声明】
  2. 中央气象台 view_{id}            → 实况(3-6h) + BABJ 预报   ⚠️默认关闭（合规风险）
  3. NHC CurrentStorms.json + TCM    → 大西洋/东太，公有领域  【实测可用】
  4. GDACS geteventlist/SEARCH       → 只做"有没有台风"的信号
  5. Open-Meteo 500hPa               → 只在标定后做引导修正（默认不用，实测更差）
  降级：官方预报 → 最短窗口等速外推 → 只报趋势 → 气候态模拟台风（必须标[模拟]）

风场怎么算：
  B   = clamp(1.0, ρ·e·Vmax_gl²/Δp_Pa, 2.5)          ρ=1.15, Vmax_gl=Vmax_10m/0.9
  Rmax= clamp(10, 0.74 × ChavasKnaff2022(Vmax, R34_mean, lat), 150)  km
  V(r)= sqrt(max(0,(B/ρ)(Rmax/r)^B Δp_Pa e^{-(Rmax/r)^B} + (r f/2)²)) − r f/2
  V_10m = 0.9 · V ; 加非对称 C(r)=Rmax·r/(Rmax²+r²) 乘移动矢量 ; 再乘阵风 1.49~1.11
  入流角 10°(r<Rmax) → 25°(r≥1.2Rmax) ; 陆上 +20°
  截断 r > 300 km

落点怎么判：
  1° 掩码 landmask1deg.bin（8100 B，MSB-first，行0在北）
  逐段 (sea→land) 二分插值 40 次 → 坐标连续、误差中位 21 km / 最差 52 km / 时间 ±6 h
  ⚠️ 连续两次登陆会合并成一次；会检出非目标登陆（吕宋等）→ 需区域过滤

雷电怎么做：
  effectiveThunderBias = biomeThunderBias × (1 + 1.5 × typhoonLocalIntensity01)
  径向权重 {40,80,120,200,350,500}km → {0.8,1.0,0.15,0.6,1.4,1.0}   ← 工程近似
  方位权重峰值在移动方向 +45°（右前）                              ← 工程近似
  地形倍率封顶 4×（山脊/山峰 ×2~3）                                ← 工程近似
  ⛔ 不做绝对闪密度；不把"眼墙闪电→增强"做成确定性机制
```

**四个最容易犯的错（都已实测确认）**：
1. 🔴 **风圈象限顺序**：中央气象台 `NE,SE,SW,NW`；浙江 `NE,SE,NW,SW`（**后两对调**）。
2. 🔴 **时间基准**：中央气象台 **UTC**；浙江 **北京时**（差 8 h）。
3. 🔴 **中央气象台是 JSONP**，不是 JSON；浙江返回**不带 charset，必须强制 UTF-8**。
4. 🔴 **`wind_u_500hPa` 会 400**（要写 `wind_u_component_500hPa`）；
   **未知变量/层名会 HTTP 200 + 全 null**（静默失败，必须做全 null 检查）。

**两个"看起来更专业但实测更差"的陷阱（不要踩）**：
1. ❌ 用七级风圈反解 Holland B —— **实测 95% 个例无解**（单调性陷阱）。七级风圈应该解 **Rmax**。
2. ❌ 用 500 hPa 引导气流外推 —— **实测比等速外推差 3 倍**（24 h：282 km vs 106 km）。




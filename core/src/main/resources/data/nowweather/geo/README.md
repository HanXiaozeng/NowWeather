# 陆海掩码（land mask）—— 格式、来源与用法

> 本目录的文件是**离线天气推算**的辅助输入，用于计算「地点到最近海洋的距离 `d`」，
> 进而推算季节温度振幅 `A`。**它不是地图素材**，不要用于渲染。
>
> 算法与验证见 `docs/research/offline-weather-synthesis.md` §2.4 方案 D。
> 标定数据见 `docs/research/continentality-calibration.csv`。

---

## 1. 文件

| 文件 | 分辨率 | 大小 | 说明 |
| --- | --- | --- | --- |
| `landmask1deg.bin` | 1° (180×360) | **8,100 B** | **推荐使用**。裸二进制，精度最好（见 §4） |
| `landmask2.json` | 2° (90×180) | **3,476 B** | 自描述 base64 版，体积最小；精度略差 |

两者内容一致（1° 掩膜按 2×2 多数表决降采样后与 2° 掩膜一致率 **98.45%**）。

**取哪个？**
- 只要内存/体积不吃紧 → `landmask1deg.bin`（多 4.6 kB，换掉约 1.5 K 的沿海误差）；
- 要极致轻量 → `landmask2.json`。

---

## 2. 来源与许可（**这是本目录最重要的一节**）

- **数据来源**：Natural Earth，1:110m 比例尺物理图层 `ne_110m_land`，**版本 4.1.0**
  - 下载地址：<https://naciscdn.org/naturalearth/110m/physical/ne_110m_land.zip>
  - 校验：该 zip 内含 `ne_110m_land.VERSION.txt`，内容为 `4.1.0`
- **许可：公有领域（Public Domain）**
  - <https://www.naturalearthdata.com/about/terms-of-use/> 原文：
    > "All versions of *Natural Earth* raster + vector map data found on this website are
    > in the public domain. You may use the maps in any manner, including modifying the
    > content and design, electronic dissemination, and offset printing. …
    > **No permission is needed to use Natural Earth.** Crediting the authors is unnecessary."
  - 即：**可自由再分发、可修改、可商用、无需署名**。随模组 jar 附带没有许可障碍。
- **本仓库做了什么**：把 Natural Earth 的陆地多边形栅格化成 **0.25° 陆地占比网格**
  （每格 4×4 子采样点，用 `shapely.contains_xy` 判定），再按 §3 的规则聚合到 1° / 2°。

> **为什么不直接用 GLOBE？** 本研究最初的 46 站标定用的是 NOAA GLOBE 1 km 掩膜
> （经 `global-land-mask`，MIT 代码）。NCEI 官方页面说明其 Unrestricted 版本
> 「no copyright or security distribution restrictions」，许可同样干净；
> 但为避免「MIT 代码 + 第三方数据」的两层溯源，**交付物统一改用许可表述最明确的
> Natural Earth**。两者的 2° 版本**只有 119 / 16,200 格不一致（0.73%）**，
> 且全部集中在南极冰架、格陵兰边缘与小岛屿，对 `d` 无实质影响。

---

## 3. 格式

### 3.1 网格定义

```
cellDegrees = 1（或 2）
网格：nlat × nlon = (180/cellDegrees) × (360/cellDegrees)
行（row）0 覆盖纬度 [90-cellDegrees, 90]     —— 即 行 0 是最北的一条带
列（col）0 覆盖经度 [-180, -180+cellDegrees] —— 即 列 0 是最西的一格

格 (r, c) 的纬度范围：[90-(r+1)*cellDegrees, 90-r*cellDegrees]
格 (r, c) 的经度范围：[-180+c*cellDegrees, -180+(c+1)*cellDegrees]
```

> ⚠️ **`landmask2.json` 里的 `originLat: -90` 不是「行 0 的纬度」**，
> 而是**整张网格的南界**（纬度区间 [-90, 90] 的下端，与 `originLon: -180` 是经度区间下端同理）。
> 该 json 的 `bitOrder` 字段自己也写着 `row 0 is the northernmost band`，与本节的约定一致。
> **已与运行时实现核对**：`CoastDistance.bitIndex()` 用的是
> `row = floor((90 - latitude) / 1)`，纬度 90（最北）→ row 0，纬度 -89.5→ row 179，
> 所以「行 0 在最北」是对的，`originLat` 与 `rowOrder: "north-to-south"` 描述的是同一条约定，
> 只是 `originLat` 写的是网格原点的南界而不是行的起点，看字段名容易读反。
> （运行时不加载这个 json —— 见 §3.2 末尾，所以它的元数据字段即使自相矛盾也不会影响计算，
> 但**也不要照着它去理解本节的网格约定**：以本节与 `CoastDistance.bitIndex()` 为准。）

**位序（bit order）**：`row-major`，行 0 在前；每字节内 **MSB first**。
即 bit `i` 对应格 `(i / nlon, i % nlon)`，且 `i` 落在字节 `i/8` 的位 `7-(i%8)`。
（这正是 Java `BitSet.valueOf(byte[])`、Python `numpy.unpackbits`、C 位运算的**共同约定**，
可直接互读。）

**位含义**：`1 = 陆地`，`0 = 海洋`。

**格级的陆/海判定**：`陆地占比 >= 0.5` 记 `1`，否则记 `0`（平局归海洋）。

Java 读取示例：

```java
byte[] raw = getResourceAsStream("/data/nowweather/geo/landmask1deg.bin").readAllBytes();
BitSet bits = BitSet.valueOf(raw);            // little-endian within byte -> see note
// 注意：BitSet.valueOf 每个字节内是 LSB-first，与上面的 MSB-first 约定相反。
// 若用 BitSet，请先反序每字节，或直接按位取：
boolean isLand(int nlat, int nlon, byte[] raw, int row, int col) {
    int i = row * nlon + col;
    return ((raw[i >> 3] >> (7 - (i & 7))) & 1) != 0;
}
```

> ⚠️ **务必按位取，不要用 `BitSet.valueOf`** —— `BitSet` 的字节内位序是 LSB-first，
> 与本文件的 MSB-first 约定相反。直接用上面的 `isLand` 表达式最安全。

### 3.2 坐标 → 格

```java
int row = clamp((int) Math.floor((90.0 - lat) / cellDegrees), 0, nlat - 1);
int col = clamp((int) Math.floor(((lon + 180.0) % 360.0 + 360.0) % 360.0 / cellDegrees), 0, nlon - 1);
```

`landmask2.json` 除 `bits` 外还带 `source` / `sourceLicence` / `cellRule` / `bitOrder`
等元数据字段。**这些字段只供人工核对与再生成使用，运行时不加载** ——
`CoastDistance` 只读 `landmask1deg.bin`（`MASK_RESOURCE`），
没有任何 Java 代码解析这个 json（`grep landmask2 *.java` 无命中），
所以别指望改它的元数据会影响游戏内的行为。

---

## 4. 精度代价（实测，**不要忽略这一节**）

用 46 个全球测站比较「使用粗掩膜算出的 `d`」与「使用 1 km 掩膜算出的 `d`」，
再比较两者导致的振幅预测差 `|ΔA|`（模型见报告 §2.4 方案 D）：

| 掩膜 | 文件大小 | 平均 \|ΔA\| | **最大 \|ΔA\|** | 最大误差出现在 |
| --- | --- | --- | --- | --- |
| 2° | 3,476 B (json) | 0.43 K | **3.32 K** | Seattle（沿海城市） |
| 1.5° | 4,800 B (base64) | 0.38 K | 2.55 K | BuenosAires |
| **1°（已交付）** | **8,100 B (bin)** | **0.23 K** | **1.78 K** | Seattle |
| 0.5° | ~32 kB | 0.19 K | 0.56 K | — |

**关键结论**：误差**几乎全部来自沿海城市**。
粗网格无法分辨「离海 10 km」和「离海 150 km」——
一格 1° 宽约 111 km，2° 约 222 km。内陆站点（`d > 400 km`）在 2° 下也能吻合到 100 km 以内。

- 平均误差很小（0.23 K @1°），**内陆地区几乎无损**；
- 但**沿海城市最坏可达 1.8 K（1°）或 3.3 K（2°）**，方向是**高估振幅**
  （因为 `d` 被高估 → `sat(d)` 偏大 → `A` 偏大）。
- 这个量级与本模型自身 2.5 K 的留一 RMSE 相当，属于可接受范围，
  但**沿海城市的误差应记录在测试里，不要假装它不存在**（见报告 §2.4 的失效区说明）。

---

## 5. 复现方法

```bash
# 1) 取 Natural Earth 110m 陆地多边形
curl -O https://naciscdn.org/naturalearth/110m/physical/ne_110m_land.zip
unzip ne_110m_land.zip -d ne110
cat ne110/ne_110m_land.VERSION.txt        # 应为 4.1.0

# 2) 栅格化为 0.25 度陆地占比（每格 4x4 子采样点，shapely.contains_xy 判定）
#    -> frac_fine.npy, shape (720, 1440), 行 0 = 90N..89.75N

# 3) 聚合到 1 度 / 2 度：cell = (fine-mean land fraction >= 0.5)

# 4) 位序：row-major、行 0 在北、每字节 MSB-first
#    numpy.packbits(land.ravel()) 即得本目录的 .bin
```

`d`（距海岸距离）的**精确定义**见 `docs/research/offline-weather-synthesis.md` §2.4 方案 D
的「`d` 的精确可复算定义」小节 —— 含径向搜索的步长、方位数、半径上限、
大圆公式与极区处理。**实现单元测试请以那一节为准。**

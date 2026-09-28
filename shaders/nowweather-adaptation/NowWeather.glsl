#ifndef NOWWEATHER_INCLUDED
#define NOWWEATHER_INCLUDED

/*
	NowWeather 联动适配
	
	把 rainStrength 拆成「云量」与「降水」两个互相独立的信号。
	
	模组 NowWeather 会每帧把真实气象数据写进客户端的 rainLevel，
	Iris / OptiFine 再把它变成 rainStrength 交给这里。约定（必须与
	模组的 SkyMood.shaderRainStrength 一致）：
	
	    rainStrength  0.00 ~ NW_CLOUD_BAND        云量 0 → 1   （阴天 / 多云：云多但不下雨）
	    rainStrength  NW_RAIN_THRESHOLD ~ 1.00    降水 0 → 1   （真的在下雨 / 雪）
	    中间留一段缓冲区，两边都不落 —— 这样「阴天」和「小雨」才有可分辨的界线。
	
	没装 NowWeather 时原版 rainLevel 只可能是 0 或 1，
	落到 nwCloudCover() 会被钳成 0 或 1，也就是回到光影原本的表现，
	所以这个文件对没装模组的人是透明的。
*/

uniform float rainStrength;

// ── NowWeather 联动设置（只在本文件定义，避免与其它文件重定义冲突）──
// 云量区间上界：rainStrength 在 0~此值 之间表示「云量」。
// 必须与模组 [shaders] cloudRainScale 一致。
#define NW_CLOUD_BAND 0.60 // 契约值：不是给玩家调的滑条，改它必须同时改模组的 [shaders] cloudRainScale
// 降水阈值：rainStrength 超过它才算「真的在下雨/雪」，阴天不会画雨。
// 必须与模组的 SkyMood.RAIN_BAND_MIN 一致。
#define NW_RAIN_THRESHOLD 0.75 // 契约值：不是给玩家调的滑条，改它必须同时改模组的 SkyMood.RAIN_BAND_MIN
// 平面云的云量增益：真实云量把平面云覆盖率抬高多少。
// 默认 0.30 → 没装模组时晴天与原包观感完全一致。
#define PC_CLOUD_COVERY_GAIN 0.55 // [0.20 0.30 0.40 0.50 0.55 0.60 0.70]
// 云量对天色的压制强度：云越多，大气整体越暗。觉得多云/阴天太刺眼就调大。
#define NW_CLOUD_DIM 0.45 // [0.20 0.30 0.40 0.45 0.50 0.60 0.70]
// 云量对直射阳光的压制：云越多，太阳越被遮住（这一项直接影响「刺不刺眼」）。
#define NW_CLOUD_SHADOW 1.0 // [0.4 0.6 0.8 0.9 1.0 1.1 1.2]


// 云量 0~1。
// 用 rainStrength 而不是 wetness：wetness 有 200 刻的惯性（为了「地面慢慢变湿」），
// 拿来当云量会让云的反应慢好几分钟；而且它分不出「阴天」和「下雨」。
float nwCloudCover() {
	return clamp(rainStrength / NW_CLOUD_BAND, 0.0, 1.0);
}

// 降水 0~1。只有真正在下雨 / 下雪时才大于 0 —— 阴天无论如何都是 0。
float nwPrecipitation() {
	return clamp((rainStrength - NW_RAIN_THRESHOLD) / (1.0 - NW_RAIN_THRESHOLD), 0.0, 1.0);
}

// 视觉云量 0~1：把线性云量做一点伽马校正。
//
// 为什么需要它：真实云量是线性的（0.2 / 0.5 / 0.7），但画面上的云量不是线性的 ——
// 直接线性映射时，「少云 22%」和「中云 50%」在屏幕上几乎看不出差别，
// 玩家没法判断天色到底跟没跟着云走。pow(x, 0.7) 把各级拉开：
//   云量 0.20 -> 0.35    云量 0.50 -> 0.62    云量 0.70 -> 0.78    云量 1.00 -> 1.00
// 两个端点仍然是 pow(0)=0、pow(1)=1，所以「没装模组」时的观感不受影响。
float nwCloudVisual() {
	return pow(nwCloudCover(), 0.7);
}
// 降雨强度 0~1（真实雨量）。
//
// 为什么要它：降水档只编码在 rainStrength 的 NW_RAIN_THRESHOLD~1.00 这一段里，
// 直接用这段值当强度的话，小雨(0.75)和暴雨(1.00)只差 25%，肉眼分不出「雨大不大」。
// 这里把它重新拉伸回 0~1，于是「毛毛雨 → 暴雨」在画面上是完整的一段。
//
// 没装模组时的兜底：原版 rainStrength 是 0→1 的平滑过渡（约 5 秒走完），
// smoothstep 让它几乎等于原值，观感与原包一致。
float nwRainAmount() {
	return max(nwPrecipitation(), smoothstep(NW_RAIN_THRESHOLD, 1.0, rainStrength));
}
// 日面 / 月面的可见度 0~1。
//
// 以前这里**没有任何遮蔽**：下着暴雨太阳照样挂在天上，非常出戏。
// 现实里阴天就见不到日面，降水时更是被完全遮住。这里按「云」与「雨」两段透过率相乘：
//   晴    云 0.00 雨 0.00 -> 1.00
//   多云  云 0.78 雨 0.00 -> 0.97
//   阴    云 0.96 雨 0.00 -> 0.16
//   毛毛雨 雨 0.15        -> 0.07
//   暴雨  雨 0.95        -> 0.01
// 晴天端仍是 1.0，所以晴朗天气（以及没装模组时）的观感不受影响。
float nwSunVisibility() {
	float cloudTrans = 1.0 - smoothstep(0.75, 1.0, nwCloudVisual()) * 0.92;
	float rainTrans = 1.0 - nwRainAmount() * 0.92;
	return cloudTrans * rainTrans;
}
// 平面云的覆盖率增量。
//
// 为什么不是「晴=0.3 / 雨=0.8」这种替换：那样会让没装模组的人阴晴两种天气都变样。
// 用增量则默认行为<b>与原包完全一致</b> ——
//   没装模组 + 晴天：rainStrength = 0 → 覆盖率为 0，云量 = PC_COVERAGE，和以前一模一样；
//   没装模组 + 下雨：rainStrength = 1 → 多 0.30，雨云更厚（这是改善，不是破坏）。
// 装了模组之后，覆盖量就随真实云量连续变化。
// base 与 gain 由调用方传入（PC_COVERAGE 定义在 Settings.glsl，
// 而本文件可能被更早包含 —— 用参数传就不会有「宏还没定义」或「重定义」的问题）。
float nwPlanarCoverage(float base, float gain) {
	return base + nwCloudVisual() * gain;
}
#endif

# 生物群系 × 天气 全矩阵压测报告

> 由 `BiomeWeatherMatrixTests` 自动生成。数据源是**真正打包进模组的**气候 JSON（`data/nowweather/climate/*.json`）。

对每个生物群系强行灌入 **全部 25 种天气** × 温度 **-50~60°C**（每 2.5°C 一点）× 4 组湿度风力，共 1,125,000 个用例。

### 结论

| 检查项 | 结果 |
| --- | --- |
| 天气输出越出白名单 | **0 次** |
| 容差内温度被改动 | **0 次**（426,700 次原样保留） |
| 超范围温度修正后偏离变大 | **0 次** |
| 天气类型被修正 | 658,892 次 |

### 违规类型统计

| 违规类型 | 触发次数 | 含义 |
| --- | --- | --- |
| `TEMPERATURE_BELOW_RANGE` | 397,800 | 温度低于该生物群系的正常温度范围 |
| `TEMPERATURE_ABOVE_RANGE` | 300,500 | 温度高于该生物群系的正常温度范围 |
| `WEATHER_NOT_POSSIBLE_IN_CLIMATE` | 264,491 | 该天气在此生物群系根本不会出现 |
| `PRECIPITATION_IN_DRY_CLIMATE` | 167,400 | 干旱气候下不应出现降水 |
| `WIND_TOO_STRONG_FOR_CLIMATE` | 129,600 | 风力超出该气候的常见范围 |
| `SNOW_ABOVE_FREEZING` | 125,272 | 温度高于冰点却在下雪 |
| `RAIN_BELOW_FREEZING` | 99,360 | 温度低于冰点却在下雨 |
| `THUNDER_TOO_STRONG_FOR_CLIMATE` | 16,844 | 雷暴强度超出该气候的合理上限 |
| `DUST_STORM_IN_WET_CLIMATE` | 5,400 | 湿润气候下出现沙尘暴 |
| `FOG_IN_DRY_CLIMATE` | 1,980 | 干旱气候下出现浓雾 |
| `FREEZING_RAIN_IN_WARM_CLIMATE` | 1,148 | 温暖气候下出现冻雨 |

### 各生物群系：正常温度范围 / 可能出现的天气 / 被拒的天气

「被拒的天气」= 无论温度怎么变，该群系都不可能出现的天气（会被自动替换成合法天气）。

| 生物群系 | 气候大类 | 正常温度范围 | 降水 | 可能出现的原版天气 | 可能出现的天气数 | 被拒的天气 |
| --- | --- | --- | --- | --- | --- | --- |
| `biomesoplenty:bamboo_grove` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:bayou` | 湿地/沼泽 | 18.0~36.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:bog` | 亚寒带 | -10.0~15.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `biomesoplenty:boreal_forest` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:cherry_blossom_grove` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:clover_patch` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:cold_desert` | 干旱/沙漠 | -20.0~15.0°C | 无 | 晴朗 | 7 | 无 |
| `biomesoplenty:coniferous_forest` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:crag` | 山地 | -20.0~20.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:crystalline_chasm` | 下界 | 40.0~90.0°C | 无 | 晴朗 | 5 | 无 |
| `biomesoplenty:dead_forest` | 亚寒带 | -15.0~12.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `biomesoplenty:dryland` | 亚热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:dune_beach` | 海岸 | 5.0~32.0°C | 雨 | 降雨/雷暴/晴朗 | 13 | 无 |
| `biomesoplenty:erupting_inferno` | 下界 | 40.0~90.0°C | 无 | 晴朗 | 5 | 无 |
| `biomesoplenty:field` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:fir_clearing` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:floodplain` | 湿地/沼泽 | 18.0~36.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:forested_field` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:fungal_jungle` | 热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:glowing_grotto` | 洞穴 | 5.0~20.0°C | 雨 | 晴朗 | 6 | 无 |
| `biomesoplenty:grassland` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:highland` | 山地 | -20.0~20.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:highland_moor` | 山地 | -20.0~20.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:jade_cliffs` | 山地 | -20.0~20.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:lavender_field` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:lavender_forest` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:lush_desert` | 热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:lush_savanna` | 热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:maple_woods` | 亚寒带 | -15.0~12.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `biomesoplenty:marsh` | 湿地/沼泽 | 5.0~28.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:mediterranean_forest` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:muskeg` | 极地/雪原 | -30.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:mystic_grove` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:old_growth_dead_forest` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:old_growth_woodland` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:ominous_woods` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:orchard` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:origin_valley` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:pasture` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:prairie` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:pumpkin_patch` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:rainbow_hills` | 极地/雪原 | -35.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:rainforest` | 热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:redwood_forest` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:rocky_rainforest` | 热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:rocky_shrubland` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:scrubland` | 亚热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:seasonal_forest` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:shrubland` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `biomesoplenty:snowy_coniferous_forest` | 极地/雪原 | -35.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:snowy_fir_clearing` | 极地/雪原 | -35.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:snowy_maple_woods` | 极地/雪原 | -35.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `biomesoplenty:spider_nest` | 洞穴 | 5.0~20.0°C | 雨 | 晴朗 | 6 | 无 |
| `biomesoplenty:tropics` | 热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:tundra` | 亚寒带 | -15.0~12.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `biomesoplenty:undergrowth` | 下界 | 40.0~90.0°C | 无 | 晴朗 | 5 | 无 |
| `biomesoplenty:visceral_heap` | 下界 | 40.0~90.0°C | 无 | 晴朗 | 5 | 无 |
| `biomesoplenty:volcanic_plains` | 火山/荒原 | 25.0~60.0°C | 无 | 晴朗 | 7 | 无 |
| `biomesoplenty:volcano` | 火山/荒原 | 25.0~60.0°C | 无 | 晴朗 | 7 | 无 |
| `biomesoplenty:wasteland` | 干旱/沙漠 | 25.0~48.0°C | 雨 | 降雨/晴朗 | 11 | 无 |
| `biomesoplenty:wetland` | 湿地/沼泽 | 5.0~28.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:withered_abyss` | 下界 | 40.0~90.0°C | 无 | 晴朗 | 5 | 无 |
| `biomesoplenty:wooded_scrubland` | 亚热带 | 18.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `biomesoplenty:wooded_wasteland` | 干旱/沙漠 | 25.0~48.0°C | 雨 | 降雨/晴朗 | 11 | 无 |
| `biomesoplenty:woodland` | 温带 | 0.0~25.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `minecraft:bamboo_jungle` | 热带 | 15.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `minecraft:basalt_deltas` | 火山/荒原 | 40.0~110.0°C | 无 | 晴朗 | 7 | 无 |
| `minecraft:beach` | 海岸 | -5.0~36.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `minecraft:birch_forest` | 温带 | -16.0~30.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:cherry_grove` | 温带 | -8.0~30.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:cold_ocean` | 亚寒带 | -14.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `minecraft:crimson_forest` | 下界 | 35.0~90.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:dappled_forest` | 温带 | -10.0~30.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:dark_forest` | 温带 | -12.0~31.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:deep_cold_ocean` | 亚寒带 | -16.0~16.0°C | 雨或雪 | 降雨/雷暴/晴朗 | 19 | 无 |
| `minecraft:deep_dark` | 洞穴 | 0.0~18.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:deep_frozen_ocean` | 极地/雪原 | -40.0~1.0°C | 雪 | 降雨/雷暴/晴朗 | 13 | 无 |
| `minecraft:deep_ocean` | 海洋 | -2.0~26.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:desert` | 干旱/沙漠 | 10.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `minecraft:dripstone_caves` | 洞穴 | 4.0~30.0°C | 无 | 晴朗 | 6 | 无 |
| `minecraft:end_barrens` | 末地 | -24.0~28.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:end_highlands` | 末地 | -22.0~28.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:end_midlands` | 末地 | -20.0~30.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:flower_forest` | 温带 | -10.0~32.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:forest` | 温带 | -14.0~32.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:frozen_ocean` | 极地/雪原 | -38.0~3.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:frozen_peaks` | 极地/雪原 | -45.0~-2.0°C | 雪 | 降雨/雷暴/晴朗 | 12 | 无 |
| `minecraft:frozen_river` | 极地/雪原 | -35.0~2.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:grove` | 亚寒带 | -24.0~15.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:ice_spikes` | 极地/雪原 | -42.0~0.0°C | 雪 | 降雨/雷暴/晴朗 | 12 | 无 |
| `minecraft:jagged_peaks` | 极地/雪原 | -45.0~2.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:jungle` | 热带 | 15.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `minecraft:lukewarm_ocean` | 亚热带 | 6.0~31.0°C | 雨 | 降雨/雷暴/晴朗 | 13 | 无 |
| `minecraft:lush_caves` | 洞穴 | 2.0~26.0°C | 无 | 晴朗 | 6 | 无 |
| `minecraft:mangrove_swamp` | 湿地/沼泽 | 12.0~38.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `minecraft:meadow` | 温带 | -12.0~26.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:mushroom_fields` | 蘑菇岛 | 0.0~30.0°C | 雨 | 降雨/雷暴/晴朗 | 15 | 无 |
| `minecraft:nether_wastes` | 下界 | 35.0~90.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:ocean` | 海洋 | -2.0~27.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:old_growth_birch_forest` | 温带 | -16.0~30.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:old_growth_pine_taiga` | 亚寒带 | -24.0~22.0°C | 雨或雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:old_growth_spruce_taiga` | 亚寒带 | -26.0~21.0°C | 雨或雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:pale_garden` | 温带 | -6.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 15 | 无 |
| `minecraft:plains` | 温带 | -16.0~36.0°C | 雨 | 降雨/雷暴/晴朗 | 15 | 无 |
| `minecraft:river` | 海岸 | -12.0~30.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:savanna` | 亚热带 | 8.0~42.0°C | 无 | 晴朗 | 6 | 无 |
| `minecraft:savanna_plateau` | 亚热带 | 6.0~38.0°C | 无 | 晴朗 | 6 | 无 |
| `minecraft:small_end_islands` | 末地 | -20.0~28.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:snowy_beach` | 极地/雪原 | -32.0~4.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:snowy_plains` | 极地/雪原 | -38.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:snowy_slopes` | 极地/雪原 | -38.0~3.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:snowy_taiga` | 极地/雪原 | -34.0~8.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:soul_sand_valley` | 下界 | 30.0~85.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:sparse_jungle` | 热带 | 13.0~39.0°C | 雨 | 降雨/雷暴/晴朗 | 12 | 无 |
| `minecraft:stony_peaks` | 山地 | -8.0~30.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:stony_shore` | 海岸 | -10.0~28.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:sulfur_caves` | 洞穴 | 8.0~34.0°C | 无 | 晴朗 | 6 | 无 |
| `minecraft:sunflower_plains` | 温带 | -14.0~35.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:swamp` | 湿地/沼泽 | -3.0~34.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `minecraft:taiga` | 亚寒带 | -26.0~22.0°C | 雨或雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `minecraft:the_end` | 末地 | -20.0~30.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:the_void` | 虚空 | -60.0~60.0°C | 无 | 晴朗 | 2 | 无 |
| `minecraft:warm_ocean` | 热带 | 12.0~34.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `minecraft:warped_forest` | 下界 | 30.0~80.0°C | 无 | 晴朗 | 5 | 无 |
| `minecraft:windswept_forest` | 山地 | -18.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:windswept_gravelly_hills` | 山地 | -18.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `minecraft:windswept_hills` | 山地 | -18.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 18 | 无 |
| `minecraft:windswept_savanna` | 亚热带 | 6.0~40.0°C | 无 | 晴朗 | 7 | 无 |
| `tectonic:alpine_meadow` | 山地 | -22.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `tectonic:ancient_river` | 洞穴 | 2.0~18.0°C | 无 | 晴朗 | 5 | 无 |
| `tectonic:autumn_birch_forest` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `tectonic:autumn_forest` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `tectonic:cold_beach` | 亚寒带 | -14.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `tectonic:cold_cliffs` | 亚寒带 | -14.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `tectonic:cold_plains` | 亚寒带 | -25.0~15.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `tectonic:cold_river` | 亚寒带 | -20.0~20.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `tectonic:coral_river` | 海洋 | -5.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `tectonic:desert_dunes` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `tectonic:dripstone_river` | 洞穴 | 2.0~18.0°C | 无 | 晴朗 | 5 | 无 |
| `tectonic:grasslands` | 温带 | -2.0~35.0°C | 雨 | 降雨/雷暴/晴朗 | 15 | 无 |
| `tectonic:icy_cliffs` | 山地 | -30.0~10.0°C | 雨或雪 | 降雨/雷暴/晴朗 | 17 | 无 |
| `tectonic:icy_river` | 海洋 | -16.0~-4.0°C | 雪 | 降雨/雷暴/晴朗 | 14 | 无 |
| `tectonic:island` | 温带 | -6.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 15 | 无 |
| `tectonic:lagoon` | 海洋 | -5.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `tectonic:lantern_river` | 洞穴 | 2.0~18.0°C | 无 | 晴朗 | 5 | 无 |
| `tectonic:lukewarm_beach` | 海岸 | -8.0~26.0°C | 雨 | 降雨/雷暴/晴朗 | 20 | 无 |
| `tectonic:lukewarm_cliffs` | 山地 | -14.0~22.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `tectonic:lukewarm_river` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `tectonic:lush_river` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `tectonic:old_growth_snowy_taiga` | 极地/雪原 | -36.0~5.0°C | 雨或雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `tectonic:red_desert` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `tectonic:red_desert_dunes` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `tectonic:sandstone_cliffs` | 山地 | 10.0~22.0°C | 无 | 晴朗 | 7 | 无 |
| `tectonic:warm_river` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `terralith:alpha_islands` | 温带 | -8.0~16.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:alpha_islands_winter` | 温带 | -12.0~2.0°C | 雪 | 降雨/雷暴/晴朗 | 15 | 无 |
| `terralith:alpine_grove` | 山地 | -30.0~6.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:alpine_highlands` | 山地 | -21.0~19.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:amethyst_canyon` | 干旱/沙漠 | 12.0~41.0°C | 雨 | 降雨/晴朗 | 10 | 无 |
| `terralith:amethyst_rainforest` | 热带 | 18.0~29.0°C | 雨 | 降雨/雷暴/晴朗 | 13 | 无 |
| `terralith:ancient_sands` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:arid_highlands` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:ashen_savanna` | 亚热带 | 5.0~30.0°C | 无 | 晴朗 | 6 | 无 |
| `terralith:basalt_cliffs` | 火山/荒原 | 5.0~32.0°C | 雨 | 降雨/晴朗 | 10 | 无 |
| `terralith:birch_taiga` | 亚寒带 | -25.6~14.4°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `terralith:blooming_plateau` | 高原 | -20.0~20.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:blooming_valley` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:brushland` | 亚热带 | 5.0~26.0°C | 无 | 晴朗 | 6 | 无 |
| `terralith:bryce_canyon` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:caldera` | 亚寒带 | -13.0~11.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `terralith:cave/andesite_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/crystal_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/deep_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/desert_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/diorite_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/frostfire_caves` | 极地/雪原 | -20.0~5.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:cave/fungal_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/granite_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/ice_caves` | 极地/雪原 | -16.0~5.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:cave/infested_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/mantle_caves` | 洞穴 | 10.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/thermal_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cave/tuff_caves` | 洞穴 | 2.0~22.0°C | 无 | 晴朗 | 5 | 无 |
| `terralith:cloud_forest` | 温带 | -12.0~15.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:cold_shrubland` | 亚寒带 | -27.2~12.8°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `terralith:desert_canyon` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:desert_oasis` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:desert_spires` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:emerald_peaks` | 山地 | -28.0~12.0°C | 雪 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:forested_highlands` | 高原 | -20.0~17.2°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:fractured_savanna` | 亚热带 | 5.0~32.0°C | 无 | 晴朗 | 6 | 无 |
| `terralith:frozen_cliffs` | 极地/雪原 | -36.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:glacial_chasm` | 极地/雪原 | -36.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:granite_cliffs` | 山地 | -22.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:gravel_beach` | 海岸 | -8.0~26.0°C | 雨 | 降雨/雷暴/晴朗 | 20 | 无 |
| `terralith:gravel_desert` | 干旱/沙漠 | 12.0~24.8°C | 雪 | 降雨/晴朗 | 10 | 无 |
| `terralith:haze_mountain` | 山地 | -24.0~16.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:highlands` | 高原 | -20.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:hot_shrubland` | 温带 | -12.0~26.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:ice_marsh` | 极地/雪原 | -33.2~5.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:jungle_mountains` | 热带 | 18.0~29.0°C | 雨 | 降雨/雷暴/晴朗 | 13 | 无 |
| `terralith:lavender_forest` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:lavender_valley` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:lush_valley` | 温带 | -12.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:mirage_isles` | 温带 | -8.0~16.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:moonlight_grove` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:moonlight_valley` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:mountain_steppe` | 山地 | -21.9~18.1°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:orchid_swamp` | 热带 | 12.0~36.0°C | 雨 | 降雨/雷暴/晴朗 | 13 | 无 |
| `terralith:painted_mountains` | 山地 | -10.0~22.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:red_oasis` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:rocky_jungle` | 热带 | 18.0~29.0°C | 雨 | 降雨/雷暴/晴朗 | 13 | 无 |
| `terralith:rocky_mountains` | 山地 | -10.0~22.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:rocky_shrubland` | 温带 | -12.0~12.8°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:sakura_grove` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:sakura_valley` | 温带 | -12.0~24.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:sandstone_valley` | 温带 | 10.0~35.0°C | 无 | 晴朗 | 6 | 无 |
| `terralith:savanna_badlands` | 干旱/沙漠 | 12.0~42.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:savanna_slopes` | 亚热带 | 5.0~30.0°C | 无 | 晴朗 | 6 | 无 |
| `terralith:scarlet_mountains` | 山地 | -28.0~12.0°C | 雪 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:shield` | 亚寒带 | -14.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `terralith:shield_clearing` | 温带 | -12.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:shrubland` | 温带 | -6.0~34.0°C | 无 | 晴朗 | 6 | 无 |
| `terralith:siberian_grove` | 温带 | -12.0~14.6°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:siberian_taiga` | 亚寒带 | -24.0~16.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `terralith:skylands` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `terralith:skylands_autumn` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `terralith:skylands_spring` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `terralith:skylands_summer` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `terralith:skylands_winter` | 海洋 | -5.0~4.0°C | 雪 | 降雨/雷暴/晴朗 | 18 | 无 |
| `terralith:snowy_badlands` | 极地/雪原 | -36.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:snowy_maple_forest` | 极地/雪原 | -34.0~5.0°C | 雪 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:snowy_shield` | 极地/雪原 | -34.0~5.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:steppe` | 高原 | -20.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:stony_spires` | 山地 | -8.0~16.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:temperate_highlands` | 高原 | -12.0~28.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:tropical_jungle` | 热带 | 18.0~29.0°C | 雨 | 降雨/雷暴/晴朗 | 13 | 无 |
| `terralith:valley_clearing` | 温带 | -12.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 16 | 无 |
| `terralith:volcanic_crater` | 火山/荒原 | 5.0~42.0°C | 雨 | 降雨/晴朗 | 10 | 无 |
| `terralith:volcanic_peaks` | 火山/荒原 | 5.0~42.0°C | 雨 | 降雨/晴朗 | 10 | 无 |
| `terralith:warm_river` | 海洋 | -5.0~10.0°C | 雨 | 降雨/雷暴/晴朗 | 19 | 无 |
| `terralith:warped_mesa` | 下界 | 35.0~56.0°C | 无 | 晴朗 | 4 | 无 |
| `terralith:white_cliffs` | 山地 | -22.0~18.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:white_mesa` | 干旱/沙漠 | 12.0~52.0°C | 无 | 晴朗 | 7 | 无 |
| `terralith:windswept_spires` | 山地 | -26.0~14.0°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:wintry_forest` | 温带 | -12.0~0.0°C | 雪 | 降雨/雷暴/晴朗 | 10 | 无 |
| `terralith:wintry_lowlands` | 亚寒带 | -32.0~-8.0°C | 雪 | 降雨/雷暴/晴朗 | 10 | 无 |
| `terralith:yellowstone` | 亚寒带 | -17.0~7.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |
| `terralith:yosemite_cliffs` | 山地 | -22.5~17.5°C | 雨 | 降雨/雷暴/晴朗 | 17 | 无 |
| `terralith:yosemite_lowlands` | 亚寒带 | -17.0~7.0°C | 雨 | 降雨/雷暴/晴朗 | 14 | 无 |

### 典型场景（温度取范围中点）

| 生物群系 | 输入 0°C 时的判定 |
| --- | --- |
| `biomesoplenty:bamboo_grove` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:bayou` | 湿地/沼泽（27.0°C → 晴，27.0°C） |
| `biomesoplenty:bog` | 亚寒带（2.5°C → 晴，2.5°C） |
| `biomesoplenty:boreal_forest` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:cherry_blossom_grove` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:clover_patch` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:cold_desert` | 干旱/沙漠（-2.5°C → 晴，-2.5°C） |
| `biomesoplenty:coniferous_forest` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:crag` | 山地（0.0°C → 晴，0.0°C） |
| `biomesoplenty:crystalline_chasm` | 下界（65.0°C → 晴，65.0°C） |
| `biomesoplenty:dead_forest` | 亚寒带（-1.5°C → 晴，-1.5°C） |
| `biomesoplenty:dryland` | 亚热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:dune_beach` | 海岸（18.5°C → 晴，18.5°C） |
| `biomesoplenty:erupting_inferno` | 下界（65.0°C → 晴，65.0°C） |
| `biomesoplenty:field` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:fir_clearing` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:floodplain` | 湿地/沼泽（27.0°C → 晴，27.0°C） |
| `biomesoplenty:forested_field` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:fungal_jungle` | 热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:glowing_grotto` | 洞穴（12.5°C → 晴，12.5°C） |
| `biomesoplenty:grassland` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:highland` | 山地（0.0°C → 晴，0.0°C） |
| `biomesoplenty:highland_moor` | 山地（0.0°C → 晴，0.0°C） |
| `biomesoplenty:jade_cliffs` | 山地（0.0°C → 晴，0.0°C） |
| `biomesoplenty:lavender_field` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:lavender_forest` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:lush_desert` | 热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:lush_savanna` | 热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:maple_woods` | 亚寒带（-1.5°C → 晴，-1.5°C） |
| `biomesoplenty:marsh` | 湿地/沼泽（16.5°C → 晴，16.5°C） |
| `biomesoplenty:mediterranean_forest` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:muskeg` | 极地/雪原（-12.5°C → 晴，-12.5°C） |
| `biomesoplenty:mystic_grove` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:old_growth_dead_forest` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:old_growth_woodland` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:ominous_woods` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:orchard` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:origin_valley` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:pasture` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:prairie` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:pumpkin_patch` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:rainbow_hills` | 极地/雪原（-15.0°C → 晴，-15.0°C） |
| `biomesoplenty:rainforest` | 热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:redwood_forest` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:rocky_rainforest` | 热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:rocky_shrubland` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:scrubland` | 亚热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:seasonal_forest` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:shrubland` | 温带（12.5°C → 晴，12.5°C） |
| `biomesoplenty:snowy_coniferous_forest` | 极地/雪原（-15.0°C → 晴，-15.0°C） |
| `biomesoplenty:snowy_fir_clearing` | 极地/雪原（-15.0°C → 晴，-15.0°C） |
| `biomesoplenty:snowy_maple_woods` | 极地/雪原（-15.0°C → 晴，-15.0°C） |
| `biomesoplenty:spider_nest` | 洞穴（12.5°C → 晴，12.5°C） |
| `biomesoplenty:tropics` | 热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:tundra` | 亚寒带（-1.5°C → 晴，-1.5°C） |
| `biomesoplenty:undergrowth` | 下界（65.0°C → 晴，65.0°C） |
| `biomesoplenty:visceral_heap` | 下界（65.0°C → 晴，65.0°C） |
| `biomesoplenty:volcanic_plains` | 火山/荒原（42.5°C → 晴，42.5°C） |
| `biomesoplenty:volcano` | 火山/荒原（42.5°C → 晴，42.5°C） |
| `biomesoplenty:wasteland` | 干旱/沙漠（36.5°C → 晴，36.5°C） |
| `biomesoplenty:wetland` | 湿地/沼泽（16.5°C → 晴，16.5°C） |
| `biomesoplenty:withered_abyss` | 下界（65.0°C → 晴，65.0°C） |
| `biomesoplenty:wooded_scrubland` | 亚热带（28.0°C → 晴，28.0°C） |
| `biomesoplenty:wooded_wasteland` | 干旱/沙漠（36.5°C → 晴，36.5°C） |
| `biomesoplenty:woodland` | 温带（12.5°C → 晴，12.5°C） |
| `minecraft:bamboo_jungle` | 热带（26.5°C → 晴，26.5°C） |
| `minecraft:basalt_deltas` | 火山/荒原（75.0°C → 晴，75.0°C） |
| `minecraft:beach` | 海岸（15.5°C → 晴，15.5°C） |
| `minecraft:birch_forest` | 温带（7.0°C → 晴，7.0°C） |
| `minecraft:cherry_grove` | 温带（11.0°C → 晴，11.0°C） |
| `minecraft:cold_ocean` | 亚寒带（2.0°C → 晴，2.0°C） |
| `minecraft:crimson_forest` | 下界（62.5°C → 晴，62.5°C） |
| `minecraft:dappled_forest` | 温带（10.0°C → 晴，10.0°C） |
| `minecraft:dark_forest` | 温带（9.5°C → 晴，9.5°C） |
| `minecraft:deep_cold_ocean` | 亚寒带（0.0°C → 晴，0.0°C） |
| `minecraft:deep_dark` | 洞穴（9.0°C → 晴，9.0°C） |
| `minecraft:deep_frozen_ocean` | 极地/雪原（-19.5°C → 晴，-19.5°C） |
| `minecraft:deep_ocean` | 海洋（12.0°C → 晴，12.0°C） |
| `minecraft:desert` | 干旱/沙漠（31.0°C → 晴，31.0°C） |
| `minecraft:dripstone_caves` | 洞穴（17.0°C → 晴，17.0°C） |
| `minecraft:end_barrens` | 末地（2.0°C → 晴，2.0°C） |
| `minecraft:end_highlands` | 末地（3.0°C → 晴，3.0°C） |
| `minecraft:end_midlands` | 末地（5.0°C → 晴，5.0°C） |
| `minecraft:flower_forest` | 温带（11.0°C → 晴，11.0°C） |
| `minecraft:forest` | 温带（9.0°C → 晴，9.0°C） |
| `minecraft:frozen_ocean` | 极地/雪原（-17.5°C → 晴，-17.5°C） |
| `minecraft:frozen_peaks` | 极地/雪原（-23.5°C → 晴，-23.5°C） |
| `minecraft:frozen_river` | 极地/雪原（-16.5°C → 晴，-16.5°C） |
| `minecraft:grove` | 亚寒带（-4.5°C → 晴，-4.5°C） |
| `minecraft:ice_spikes` | 极地/雪原（-21.0°C → 晴，-21.0°C） |
| `minecraft:jagged_peaks` | 极地/雪原（-21.5°C → 晴，-21.5°C） |
| `minecraft:jungle` | 热带（26.5°C → 晴，26.5°C） |
| `minecraft:lukewarm_ocean` | 亚热带（18.5°C → 晴，18.5°C） |
| `minecraft:lush_caves` | 洞穴（14.0°C → 晴，14.0°C） |
| `minecraft:mangrove_swamp` | 湿地/沼泽（25.0°C → 晴，25.0°C） |
| `minecraft:meadow` | 温带（7.0°C → 晴，7.0°C） |
| `minecraft:mushroom_fields` | 蘑菇岛（15.0°C → 晴，15.0°C） |
| `minecraft:nether_wastes` | 下界（62.5°C → 晴，62.5°C） |
| `minecraft:ocean` | 海洋（12.5°C → 晴，12.5°C） |
| `minecraft:old_growth_birch_forest` | 温带（7.0°C → 晴，7.0°C） |
| `minecraft:old_growth_pine_taiga` | 亚寒带（-1.0°C → 晴，-1.0°C） |
| `minecraft:old_growth_spruce_taiga` | 亚寒带（-2.5°C → 晴，-2.5°C） |
| `minecraft:pale_garden` | 温带（9.0°C → 晴，9.0°C） |
| `minecraft:plains` | 温带（10.0°C → 晴，10.0°C） |
| `minecraft:river` | 海岸（9.0°C → 晴，9.0°C） |
| `minecraft:savanna` | 亚热带（25.0°C → 晴，25.0°C） |
| `minecraft:savanna_plateau` | 亚热带（22.0°C → 晴，22.0°C） |
| `minecraft:small_end_islands` | 末地（4.0°C → 晴，4.0°C） |
| `minecraft:snowy_beach` | 极地/雪原（-14.0°C → 晴，-14.0°C） |
| `minecraft:snowy_plains` | 极地/雪原（-16.5°C → 晴，-16.5°C） |
| `minecraft:snowy_slopes` | 极地/雪原（-17.5°C → 晴，-17.5°C） |
| `minecraft:snowy_taiga` | 极地/雪原（-13.0°C → 晴，-13.0°C） |
| `minecraft:soul_sand_valley` | 下界（57.5°C → 晴，57.5°C） |
| `minecraft:sparse_jungle` | 热带（26.0°C → 晴，26.0°C） |
| `minecraft:stony_peaks` | 山地（11.0°C → 晴，11.0°C） |
| `minecraft:stony_shore` | 海岸（9.0°C → 晴，9.0°C） |
| `minecraft:sulfur_caves` | 洞穴（21.0°C → 晴，21.0°C） |
| `minecraft:sunflower_plains` | 温带（10.5°C → 晴，10.5°C） |
| `minecraft:swamp` | 湿地/沼泽（15.5°C → 晴，15.5°C） |
| `minecraft:taiga` | 亚寒带（-2.0°C → 晴，-2.0°C） |
| `minecraft:the_end` | 末地（5.0°C → 晴，5.0°C） |
| `minecraft:the_void` | 虚空（0.0°C → 晴，0.0°C） |
| `minecraft:warm_ocean` | 热带（23.0°C → 晴，23.0°C） |
| `minecraft:warped_forest` | 下界（55.0°C → 晴，55.0°C） |
| `minecraft:windswept_forest` | 山地（3.0°C → 晴，3.0°C） |
| `minecraft:windswept_gravelly_hills` | 山地（3.0°C → 晴，3.0°C） |
| `minecraft:windswept_hills` | 山地（3.0°C → 晴，3.0°C） |
| `minecraft:windswept_savanna` | 亚热带（23.0°C → 晴，23.0°C） |
| `tectonic:alpine_meadow` | 山地（-2.0°C → 晴，-2.0°C） |
| `tectonic:ancient_river` | 洞穴（10.0°C → 晴，10.0°C） |
| `tectonic:autumn_birch_forest` | 温带（6.0°C → 晴，6.0°C） |
| `tectonic:autumn_forest` | 温带（6.0°C → 晴，6.0°C） |
| `tectonic:cold_beach` | 亚寒带（5.0°C → 晴，5.0°C） |
| `tectonic:cold_cliffs` | 亚寒带（5.0°C → 晴，5.0°C） |
| `tectonic:cold_plains` | 亚寒带（-5.0°C → 晴，-5.0°C） |
| `tectonic:cold_river` | 亚寒带（0.0°C → 晴，0.0°C） |
| `tectonic:coral_river` | 海洋（6.5°C → 晴，6.5°C） |
| `tectonic:desert_dunes` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `tectonic:dripstone_river` | 洞穴（10.0°C → 晴，10.0°C） |
| `tectonic:grasslands` | 温带（16.5°C → 晴，16.5°C） |
| `tectonic:icy_cliffs` | 山地（-10.0°C → 晴，-10.0°C） |
| `tectonic:icy_river` | 海洋（-10.0°C → 晴，-10.0°C） |
| `tectonic:island` | 温带（6.0°C → 晴，6.0°C） |
| `tectonic:lagoon` | 海洋（6.5°C → 晴，6.5°C） |
| `tectonic:lantern_river` | 洞穴（10.0°C → 晴，10.0°C） |
| `tectonic:lukewarm_beach` | 海岸（9.0°C → 晴，9.0°C） |
| `tectonic:lukewarm_cliffs` | 山地（4.0°C → 晴，4.0°C） |
| `tectonic:lukewarm_river` | 海洋（2.5°C → 晴，2.5°C） |
| `tectonic:lush_river` | 海洋（2.5°C → 晴，2.5°C） |
| `tectonic:old_growth_snowy_taiga` | 极地/雪原（-15.5°C → 晴，-15.5°C） |
| `tectonic:red_desert` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `tectonic:red_desert_dunes` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `tectonic:sandstone_cliffs` | 山地（16.0°C → 晴，16.0°C） |
| `tectonic:warm_river` | 海洋（2.5°C → 晴，2.5°C） |
| `terralith:alpha_islands` | 温带（4.0°C → 晴，4.0°C） |
| `terralith:alpha_islands_winter` | 温带（-5.0°C → 晴，-5.0°C） |
| `terralith:alpine_grove` | 山地（-12.0°C → 晴，-12.0°C） |
| `terralith:alpine_highlands` | 山地（-1.0°C → 晴，-1.0°C） |
| `terralith:amethyst_canyon` | 干旱/沙漠（26.5°C → 晴，26.5°C） |
| `terralith:amethyst_rainforest` | 热带（23.5°C → 晴，23.5°C） |
| `terralith:ancient_sands` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:arid_highlands` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:ashen_savanna` | 亚热带（17.5°C → 晴，17.5°C） |
| `terralith:basalt_cliffs` | 火山/荒原（18.5°C → 晴，18.5°C） |
| `terralith:birch_taiga` | 亚寒带（-5.6°C → 晴，-5.6°C） |
| `terralith:blooming_plateau` | 高原（0.0°C → 晴，0.0°C） |
| `terralith:blooming_valley` | 温带（6.0°C → 晴，6.0°C） |
| `terralith:brushland` | 亚热带（15.5°C → 晴，15.5°C） |
| `terralith:bryce_canyon` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:caldera` | 亚寒带（-1.0°C → 晴，-1.0°C） |
| `terralith:cave/andesite_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/crystal_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/deep_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/desert_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/diorite_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/frostfire_caves` | 极地/雪原（-7.5°C → 晴，-7.5°C） |
| `terralith:cave/fungal_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/granite_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/ice_caves` | 极地/雪原（-5.5°C → 晴，-5.5°C） |
| `terralith:cave/infested_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/mantle_caves` | 洞穴（16.0°C → 晴，16.0°C） |
| `terralith:cave/thermal_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cave/tuff_caves` | 洞穴（12.0°C → 晴，12.0°C） |
| `terralith:cloud_forest` | 温带（1.5°C → 晴，1.5°C） |
| `terralith:cold_shrubland` | 亚寒带（-7.2°C → 晴，-7.2°C） |
| `terralith:desert_canyon` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:desert_oasis` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:desert_spires` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:emerald_peaks` | 山地（-8.0°C → 晴，-8.0°C） |
| `terralith:forested_highlands` | 高原（-1.4°C → 晴，-1.4°C） |
| `terralith:fractured_savanna` | 亚热带（18.5°C → 晴，18.5°C） |
| `terralith:frozen_cliffs` | 极地/雪原（-15.5°C → 晴，-15.5°C） |
| `terralith:glacial_chasm` | 极地/雪原（-15.5°C → 晴，-15.5°C） |
| `terralith:granite_cliffs` | 山地（-2.0°C → 晴，-2.0°C） |
| `terralith:gravel_beach` | 海岸（9.0°C → 晴，9.0°C） |
| `terralith:gravel_desert` | 干旱/沙漠（18.4°C → 晴，18.4°C） |
| `terralith:haze_mountain` | 山地（-4.0°C → 晴，-4.0°C） |
| `terralith:highlands` | 高原（-1.0°C → 晴，-1.0°C） |
| `terralith:hot_shrubland` | 温带（7.0°C → 晴，7.0°C） |
| `terralith:ice_marsh` | 极地/雪原（-14.1°C → 晴，-14.1°C） |
| `terralith:jungle_mountains` | 热带（23.5°C → 晴，23.5°C） |
| `terralith:lavender_forest` | 温带（6.0°C → 晴，6.0°C） |
| `terralith:lavender_valley` | 温带（6.0°C → 晴，6.0°C） |
| `terralith:lush_valley` | 温带（3.0°C → 晴，3.0°C） |
| `terralith:mirage_isles` | 温带（4.0°C → 晴，4.0°C） |
| `terralith:moonlight_grove` | 温带（6.0°C → 晴，6.0°C） |
| `terralith:moonlight_valley` | 温带（6.0°C → 晴，6.0°C） |
| `terralith:mountain_steppe` | 山地（-1.9°C → 晴，-1.9°C） |
| `terralith:orchid_swamp` | 热带（24.0°C → 晴，24.0°C） |
| `terralith:painted_mountains` | 山地（6.0°C → 晴，6.0°C） |
| `terralith:red_oasis` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:rocky_jungle` | 热带（23.5°C → 晴，23.5°C） |
| `terralith:rocky_mountains` | 山地（6.0°C → 晴，6.0°C） |
| `terralith:rocky_shrubland` | 温带（0.4°C → 晴，0.4°C） |
| `terralith:sakura_grove` | 温带（6.0°C → 晴，6.0°C） |
| `terralith:sakura_valley` | 温带（6.0°C → 晴，6.0°C） |
| `terralith:sandstone_valley` | 温带（22.5°C → 晴，22.5°C） |
| `terralith:savanna_badlands` | 干旱/沙漠（27.0°C → 晴，27.0°C） |
| `terralith:savanna_slopes` | 亚热带（17.5°C → 晴，17.5°C） |
| `terralith:scarlet_mountains` | 山地（-8.0°C → 晴，-8.0°C） |
| `terralith:shield` | 亚寒带（-2.0°C → 晴，-2.0°C） |
| `terralith:shield_clearing` | 温带（3.0°C → 晴，3.0°C） |
| `terralith:shrubland` | 温带（14.0°C → 晴，14.0°C） |
| `terralith:siberian_grove` | 温带（1.3°C → 晴，1.3°C） |
| `terralith:siberian_taiga` | 亚寒带（-4.0°C → 晴，-4.0°C） |
| `terralith:skylands` | 海洋（2.5°C → 晴，2.5°C） |
| `terralith:skylands_autumn` | 海洋（2.5°C → 晴，2.5°C） |
| `terralith:skylands_spring` | 海洋（2.5°C → 晴，2.5°C） |
| `terralith:skylands_summer` | 海洋（2.5°C → 晴，2.5°C） |
| `terralith:skylands_winter` | 海洋（-0.5°C → 晴，-0.5°C） |
| `terralith:snowy_badlands` | 极地/雪原（-15.5°C → 晴，-15.5°C） |
| `terralith:snowy_maple_forest` | 极地/雪原（-14.5°C → 晴，-14.5°C） |
| `terralith:snowy_shield` | 极地/雪原（-14.5°C → 晴，-14.5°C） |
| `terralith:steppe` | 高原（-1.0°C → 晴，-1.0°C） |
| `terralith:stony_spires` | 山地（4.0°C → 晴，4.0°C） |
| `terralith:temperate_highlands` | 高原（8.0°C → 晴，8.0°C） |
| `terralith:tropical_jungle` | 热带（23.5°C → 晴，23.5°C） |
| `terralith:valley_clearing` | 温带（3.0°C → 晴，3.0°C） |
| `terralith:volcanic_crater` | 火山/荒原（23.5°C → 晴，23.5°C） |
| `terralith:volcanic_peaks` | 火山/荒原（23.5°C → 晴，23.5°C） |
| `terralith:warm_river` | 海洋（2.5°C → 晴，2.5°C） |
| `terralith:warped_mesa` | 下界（45.5°C → 晴，45.5°C） |
| `terralith:white_cliffs` | 山地（-2.0°C → 晴，-2.0°C） |
| `terralith:white_mesa` | 干旱/沙漠（32.0°C → 晴，32.0°C） |
| `terralith:windswept_spires` | 山地（-6.0°C → 晴，-6.0°C） |
| `terralith:wintry_forest` | 温带（-6.0°C → 晴，-6.0°C） |
| `terralith:wintry_lowlands` | 亚寒带（-20.0°C → 晴，-20.0°C） |
| `terralith:yellowstone` | 亚寒带（-5.0°C → 晴，-5.0°C） |
| `terralith:yosemite_cliffs` | 山地（-2.5°C → 晴，-2.5°C） |
| `terralith:yosemite_lowlands` | 亚寒带（-5.0°C → 晴，-5.0°C） |

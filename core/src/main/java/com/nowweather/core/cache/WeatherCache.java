package com.nowweather.core.cache;

import com.nowweather.core.forecast.Forecast;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.text.Text;
import com.nowweather.core.weather.WeatherObservation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 本地天气缓存 —— 「离线也要能用」的地基。
 *
 * <p>缓存目录由平台层给出（客户端是 {@code .minecraft/nowweather/}，服务端是 {@code <服务器目录>/nowweather/}），
 * 里面是三份人类可读的 JSON：</p>
 *
 * <pre>
 * profile.json      地点画像：城市 / 坐标 / 海拔 / 时区 / 半球 / 数据源
 * observation.json  最近一次成功获取的实况（含时间戳）
 * forecast.json     最近一次生成的预报
 * </pre>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li><b>永不抛异常</b>：缓存读写出问题只是「没缓存」，绝不能让游戏崩或主循环中断；</li>
 *   <li><b>原子写入</b>：先写 {@code .tmp} 再改名，避免断电/崩溃留下半个文件；</li>
 *   <li><b>人类可读</b>：出问题时服主能直接打开看，也能手改（例如手动填坐标）；</li>
 *   <li><b>带 schema 版本</b>：将来改格式时可以识别并丢弃旧文件。</li>
 * </ul>
 */
public final class WeatherCache {

    public static final long SCHEMA_VERSION = 1L;

    private static final String PROFILE_FILE = "profile.json";
    private static final String OBSERVATION_FILE = "observation.json";
    private static final String FORECAST_FILE = "forecast.json";

    private final Path directory;
    private final SyncLogger logger;

    public WeatherCache(Path directory, SyncLogger logger) {
        this.directory = directory;
        this.logger = logger == null ? com.nowweather.core.log.Loggers.noop() : logger;
    }

    public Path directory() {
        return directory;
    }

    // ------------------------------------------------------------------ 地点画像

    public Optional<LocationProfile> loadProfile() {
        return readJson(PROFILE_FILE).map(LocationProfile::fromJson)
                .filter(profile -> profile.updatedAtEpochMillis() > 0L || profile.isUsable());
    }

    public boolean saveProfile(LocationProfile profile) {
        return profile != null && writeJson(PROFILE_FILE, profile.toJson());
    }

    // ------------------------------------------------------------------ 实况

    public Optional<WeatherObservation> loadObservation() {
        return readJson(OBSERVATION_FILE).map(WeatherObservation::fromJson)
                .filter(observation -> observation.weatherType()
                        != com.nowweather.core.weather.WeatherType.UNKNOWN);
    }

    public boolean saveObservation(WeatherObservation observation) {
        return observation != null && writeJson(OBSERVATION_FILE, observation.toJson());
    }

    // ------------------------------------------------------------------ 预报

    public Optional<Forecast> loadForecast() {
        return readJson(FORECAST_FILE).map(Forecast::fromJson)
                .filter(forecast -> !forecast.isEmpty());
    }

    public boolean saveForecast(Forecast forecast) {
        return forecast != null && !forecast.isEmpty() && writeJson(FORECAST_FILE, forecast.toJson());
    }

    // ------------------------------------------------------------------ 维护

    /** 缓存目录里是否已经有可用的地点画像。 */
    public boolean hasProfile() {
        return loadProfile().map(LocationProfile::isUsable).orElse(false);
    }

    /** 清空缓存（命令 / 切换地区时用）。 */
    public boolean clear() {
        boolean ok = true;
        for (String name : new String[]{PROFILE_FILE, OBSERVATION_FILE, FORECAST_FILE}) {
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (IOException e) {
                ok = false;
                logger.warn(e, "删除缓存文件 {} 失败", name);
            }
        }
        return ok;
    }

    /** 供诊断输出的一行描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(directory.toString());
        sb.append(" [");
        sb.append(loadProfile().map(LocationProfile::describe)
                .orElse(Text.tr("nowweather.cache.no_profile")));
        loadObservation().ifPresent(observation -> sb.append(Text.tr("nowweather.cache.observation",
                observation.weatherType().zhName(), observation.observedAtDisplay())));
        sb.append(']');
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内部读写

    private Optional<JsonObject> readJson(String fileName) {
        Path file = directory.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject json = JsonValue.parse(text).asObjectOrNull();
            if (json == null) {
                return Optional.empty();
            }
            long schema = json.optLong("schema", SCHEMA_VERSION);
            if (schema != SCHEMA_VERSION) {
                logger.warn("缓存文件 {} 的格式版本为 {}（当前 {}），已忽略", fileName, schema, SCHEMA_VERSION);
                return Optional.empty();
            }
            return Optional.of(json);
        } catch (IOException | RuntimeException e) {
            logger.warn("读取缓存文件 {} 失败（当作没有缓存继续运行）：{}", fileName, e.toString());
            return Optional.empty();
        }
    }

    private boolean writeJson(String fileName, JsonObject json) {
        Path file = directory.resolve(fileName);
        Path temp = directory.resolve(fileName + ".tmp");
        try {
            Files.createDirectories(directory);
            JsonObject withSchema = json;
            if (!withSchema.has("schema")) {
                // 补上 schema 字段（JsonObject 不是 Map，逐个条目拷过去）
                withSchema = new JsonObject();
                withSchema.put("schema", SCHEMA_VERSION);
                for (var entry : json.entrySet()) {
                    withSchema.put(entry.getKey(), entry.getValue());
                }
            }
            Files.writeString(temp, withSchema.toPrettyJson() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 某些文件系统不支持原子移动，退化普通覆盖
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warn("写入缓存文件 {} 失败（不影响游戏运行）：{}", fileName, e.toString());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败无所谓
            }
            return false;
        }
    }
}

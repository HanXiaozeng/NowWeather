package com.nowweather.core.providers.openmeteo;

import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.http.HttpException;
import com.nowweather.core.http.HttpRequestSpec;
import com.nowweather.core.http.HttpTransport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open-Meteo 地理编码：城市名 → 经纬度。
 *
 * <p>Open-Meteo 的天气接口只吃经纬度，所以「按城市名查天气」这一步需要先做地理编码。
 * 它自带一个免费的地理编码接口，支持中文城市名（实测「杭州」能直接查到浙江省杭州市）。</p>
 *
 * <p>查询结果会缓存在内存里（同一个城市名只会查一次），避免每 20 分钟都去问一遍。</p>
 */
public final class OpenMeteoGeocoder {

    public static final String DEFAULT_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search";

    private final HttpTransport transport;
    private final String endpoint;
    private final Map<String, Result> cache = new ConcurrentHashMap<>();

    public OpenMeteoGeocoder(HttpTransport transport) {
        this(transport, DEFAULT_ENDPOINT);
    }

    public OpenMeteoGeocoder(HttpTransport transport, String endpoint) {
        this.transport = transport;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
    }

    /**
     * 解析一个位置。
     *
     * @param query 城市名（中英文均可），也支持 {@code "39.9,116.4"} 这种「纬度,经度」写法
     * @return 解析结果；失败返回 null
     */
    public Result resolve(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String key = query.trim().toLowerCase(java.util.Locale.ROOT);
        Result cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // 支持直接写坐标
        Result direct = tryParseCoordinates(query);
        if (direct != null) {
            cache.put(key, direct);
            return direct;
        }

        Result fetched = geocode(query.trim());
        if (fetched == null) {
            // ★ 中文行政区划后缀必须逐个剥掉再试。
            // 实测（2026-09 对官方接口逐项核对）：
            //   name=杭州市  -> 无结果
            //   name=杭州    -> 杭州 / 浙江 / 中国 (30.29365, 120.16142)  ✅
            //   name=西湖区  -> 无结果
            // 而 UApiPro 按 IP 定位返回的城市名恰好带「市」字 ——
            // 于是「IP 定位解析出杭州市」这一步的结果永远喂不进 Open-Meteo，
            // 只能一直退回 UApiPro。剥后缀 + 逐级缩短就能接上。
            for (String candidate : candidateQueries(query.trim())) {
                fetched = geocode(candidate);
                if (fetched != null) {
                    break;
                }
            }
        }
        if (fetched != null) {
            cache.put(key, fetched);
        }
        return fetched;
    }

    /** 中文行政区划后缀，长的必须排在前面（否则「自治区」会被「区」先匹配掉）。 */
    private static final String[] ADMIN_SUFFIXES = {
            "特别行政区", "自治区", "自治州", "自治县", "市辖区", "地区", "盟", "旗",
            "省", "市", "区", "县", "镇", "乡", "街道"
    };

    /**
     * 生成候选查询词，<b>按「歧义从小到大」排序</b>。
     *
     * <h2>为什么顺序至关重要</h2>
     * 地理编码是「模糊匹配 + 取第一条」，错误匹配会<b>静默成功</b>。
     * 实测（2026-09 对官方接口逐项核对）：
     * <pre>
     *   杭州市      -> 30.29, 120.16（浙江省）  ✅
     *   浙江省杭州市 -> 22.41, 108.78（广西）    ❌ 顺序不对时命中这个
     *   西湖区      -> 29.10, 106.40（重庆市）  ❌ 全国有几十个「西湖」
     * </pre>
     * 一个错到 1000 公里外的坐标，比「查不到、退回 UApiPro」严重得多。
     * 所以策略是：
     * <ol>
     *   <li><b>城市级优先</b>：取最后一段里带「市 / 自治州 / 地区 / 盟」的那段剥掉后缀。
     *       城市名重名率远低于区县名。</li>
     *   <li>其次：最后一段剥后缀（可能是区/县名，歧义大，但总好过没有）。</li>
     *   <li>最后：逐级剥尾缀，兜底。</li>
     * </ol>
     */
    public static java.util.List<String> candidateQueries(String original) {
        String text = original == null ? "" : original.trim();
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        java.util.List<String> segments = splitSegments(text);

        // ① 城市级：从后往前找第一段带城市后缀的
        for (int i = segments.size() - 1; i >= 0; i--) {
            String segment = segments.get(i);
            if (isCityLevel(segment)) {
                String stripped = stripSuffix(segment);
                if (stripped.length() >= 2 && !stripped.equals(segment)) {
                    candidates.add(stripped);
                }
                break;
            }
        }

        // ② 最后一段剥后缀
        if (!segments.isEmpty()) {
            String stripped = stripSuffix(segments.get(segments.size() - 1));
            if (stripped.length() >= 2 && !stripped.equals(segments.get(segments.size() - 1))) {
                candidates.add(stripped);
            }
        }

        // ③ 逐级剥尾缀兜底
        String current = text;
        for (int round = 0; round < 4; round++) {
            String stripped = stripSuffix(current);
            if (stripped.equals(current) || stripped.length() < 2) {
                break;
            }
            candidates.add(stripped);
            current = stripped;
        }

        candidates.remove(text);
        return new java.util.ArrayList<>(candidates);
    }

    /** 把地名按行政区划后缀切成「省 / 市 / 区」这样的段。 */
    private static java.util.List<String> splitSegments(String text) {
        java.util.List<String> segments = new java.util.ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            boolean matched = false;
            for (String suffix : ADMIN_SUFFIXES) {
                if (text.startsWith(suffix, i)) {
                    segments.add(text.substring(start, i + suffix.length()));
                    start = i + suffix.length();
                    i = start;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                i++;
            }
        }
        if (start < text.length()) {
            segments.add(text.substring(start));
        }
        return segments;
    }

    private static boolean isCityLevel(String segment) {
        return segment.endsWith("市") || segment.endsWith("自治州")
                || segment.endsWith("地区") || segment.endsWith("盟");
    }
    private static String stripSuffix(String value) {
        for (String suffix : ADMIN_SUFFIXES) {
            if (value.length() > suffix.length() && value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    /** {@code "39.9,116.4"} → 结果。 */
    public static Result tryParseCoordinates(String raw) {
        String text = raw.trim();
        int comma = text.indexOf(',');
        if (comma <= 0) {
            return null;
        }
        try {
            double lat = Double.parseDouble(text.substring(0, comma).trim());
            double lon = Double.parseDouble(text.substring(comma + 1).trim());
            if (lat < -90.0D || lat > 90.0D || lon < -180.0D || lon > 180.0D) {
                return null;
            }
            return new Result(lat, lon, text, "", "", "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Result geocode(String query) {
        String url = endpoint
                + (endpoint.contains("?") ? "&" : "?")
                + "name=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=1&language=zh&format=json";
        try {
            var response = transport.execute(HttpRequestSpec.get(url)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .build());
            if (!response.isSuccess()) {
                return null;
            }
            return parse(response.body());
        } catch (HttpException | RuntimeException e) {
            return null;
        }
    }

    /** 解析地理编码响应（单独抽出来便于单元测试）。 */
    public static Result parse(String body) {
        JsonObject root = JsonValue.parseOr(body, null) == null ? null
                : JsonValue.parseOr(body, null).asObjectOrNull();
        if (root == null) {
            return null;
        }
        JsonArray results = root.optArray("results");
        if (results == null || results.isEmpty()) {
            return null;
        }
        JsonObject first = results.optObject(0);
        if (first == null || !first.hasNonNull("latitude") || !first.hasNonNull("longitude")) {
            return null;
        }
        String name = first.optString("name", "");
        String admin1 = first.optString("admin1", "");
        String country = first.optString("country", "");
        String display = buildDisplay(name, admin1, country);
        return new Result(first.optDouble("latitude", Double.NaN), first.optDouble("longitude", Double.NaN),
                display, name, admin1, country);
    }

    private static String buildDisplay(String name, String admin1, String country) {
        StringBuilder sb = new StringBuilder();
        if (!country.isBlank() && !"中国".equals(country)) {
            sb.append(country);
        }
        if (!admin1.isBlank() && !admin1.equals(name)) {
            sb.append(admin1);
        }
        sb.append(name);
        return sb.toString();
    }

    /** 一次地理编码的结果。 */
    public record Result(double latitude, double longitude, String display, String name,
                         String admin1, String country) {

        public boolean isValid() {
            return !Double.isNaN(latitude) && !Double.isNaN(longitude);
        }
    }
}

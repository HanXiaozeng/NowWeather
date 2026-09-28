package com.nowweather.core.test;

import com.nowweather.core.json.JsonArray;
import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;

/** JSON 解析 / 序列化测试。 */
final class JsonTests {

    private JsonTests() {
    }

    static void run() {
        T.section("JSON 解析与序列化");

        JsonValue parsed = JsonValue.parse("""
                {
                  "province": "北京市", "city": "北京", "adcode": "110000",
                  "weather": "晴", "weather_icon": "100", "temperature": 30,
                  "wind_direction": "西风", "wind_power": "4级", "humidity": 16,
                  "report_time": "11 分钟前发布"
                }
                """);
        JsonObject obj = parsed.asObject();
        T.eq("中文字段解析", "北京", obj.optString("city", ""));
        T.eq("整数字段", 30L, obj.optLong("temperature", -1L));
        T.eq("带单位的文本抽数字", 4.0D, obj.optNumberish("wind_power", -1.0D));

        // 转义与嵌套
        JsonObject nested = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add("a\"b").add(1.5D).add(true).add(JsonValue.nul());
        nested.put("text", "换行\n制表\t结束").put("arr", arr).put("neg", -12.25D);
        String json = nested.toJson();
        JsonObject round = JsonValue.parse(json).asObject();
        T.eq("字符串转义往返", "换行\n制表\t结束", round.optString("text", ""));
        T.eq("数组往返长度", 4, round.optArray("arr").size());
        T.eq("布尔往返", true, round.optArray("arr").get(2).asBoolean());
        T.eq("null 往返", true, round.optArray("arr").get(3).isNull());
        T.near("浮点往返", -12.25D, round.optDouble("neg", 0.0D), 1.0E-9);

        // 容错：尾随逗号 / 单引号
        JsonObject lenient = JsonValue.parse("{'a': 1, 'b': [1,2,], }").asObject();
        T.eq("容错解析(单引号+尾随逗号)", 1L, lenient.optLong("a", -1L));
        T.eq("容错数组长度", 2, lenient.optArray("b").size());

        // 出错时抛异常
        T.throwsException("非法 JSON 抛异常", com.nowweather.core.json.JsonException.class,
                () -> JsonValue.parse("{oops"));

        // 严格模式下的类型错误
        T.throwsException("类型不匹配抛异常", com.nowweather.core.json.JsonException.class,
                () -> JsonValue.parse("[1,2]").asObject());

        T.eq("宽松取字符串(数字)", "30", JsonValue.parse("{\"v\":30}").asObject().get("v").asString());
        T.near("字符串转数字", 12.5D, JsonValue.parse("{\"v\":\"12.5\"}").asObject().optDouble("v", 0), 1.0E-9);

        // pretty 输出可再次解析
        String pretty = nested.toPrettyJson();
        T.check("缩进输出可被重新解析", JsonValue.parse(pretty).asObject().has("arr"));
    }
}

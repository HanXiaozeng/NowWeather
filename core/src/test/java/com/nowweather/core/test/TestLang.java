package com.nowweather.core.test;

import com.nowweather.core.json.JsonObject;
import com.nowweather.core.json.JsonValue;
import com.nowweather.core.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 给核心自测装上一个<b>真实</b>的翻译器：直接读仓库里的 {@code zh_cn.json}。
 *
 * <h2>为什么自测要装翻译器</h2>
 * <p>{@link Text} 的默认实现是「原样返回键名」，这是刻意的设计：漏装翻译器、或语言文件里漏了键，
 * 都要在界面上<b>显式暴露</b>，而不是静默回退到中文。但自测跑在没有 Minecraft 的环境里，
 * 如果什么都不装，那么所有断言「文案 === 某句中文」的用例都会拿到键名而失败 ——
 * 失败的其实是「环境」，不是「被测逻辑」。</p>
 *
 * <h2>为什么是读真实的语言文件，而不是内置一张中文对照表</h2>
 * <p>内置对照表看起来更省事，但它会让自测与真正发给玩家的语言文件脱钩：
 * 语言文件里漏了键、或者键名被改过，自测照样全绿，玩家却在界面上看到
 * {@code nowweather.status.header} 这种键名。<b>直接读真文件之后，这套自测就顺带变成了
 * 「语言文件完整性检查」</b> —— 漏一个键，对应的断言必然失败。</p>
 *
 * <h2>找不到文件时会怎样</h2>
 * <p>不静默兜底：打印一条明确的警告，然后保持「返回键名」的默认行为，
 * 于是断言会以「期望 满月，实际 nowweather.moon_phase.0」这种形式失败。
 * 失败信息本身就指明了原因，比悄悄跳过检查强。</p>
 */
final class TestLang {

    private static final String RELATIVE = "versions/forge-common/src/main/resources/assets/nowweather/lang/zh_cn.json";

    private TestLang() {
    }

    /** 装载 zh_cn.json 并安装为当前翻译器。应在任何测试用例之前调用一次。 */
    static void install() {
        Path file = locate();
        if (file == null) {
            System.out.println("[!] 找不到 " + RELATIVE
                    + "（从当前目录逐级向上找了 6 层）。本次自测将显示语言键而非中文，"
                    + "相关断言会失败 —— 这是刻意的，漏装翻译器必须显式暴露。");
            return;
        }
        Map<String, String> table;
        try {
            table = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("[!] 读取语言文件失败：" + file + " -> " + e);
            return;
        }
        if (table.isEmpty()) {
            System.out.println("[!] 语言文件是空的：" + file);
            return;
        }
        Text.install((key, args) -> {
            String pattern = table.get(key);
            if (pattern == null) {
                // 语言文件里没有这个键：返回键名，让对应的断言失败并把它暴露出来。
                return key;
            }
            if (args == null || args.length == 0) {
                return pattern;
            }
            try {
                // 与 Minecraft 的 TranslatableComponent 同语义：语言文件用 %s 占位符，
                // 由 String.format 填充。固定 Locale.ROOT，避免数字格式随系统区域变化。
                return String.format(Locale.ROOT, pattern, args);
            } catch (RuntimeException e) {
                return key + "（占位符不匹配：" + e.getMessage() + "）";
            }
        });
        System.out.println("已装载测试语言包：zh_cn.json（" + table.size() + " 个键）");
    }

    /** 从当前目录逐级向上找语言文件；找不到返回 null。 */
    private static Path locate() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++) {
            Path candidate = dir.resolve(RELATIVE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        // 兜底：允许用系统属性显式指定（CI 里换工作目录时用得上）
        String explicit = System.getProperty("nowweather.lang");
        if (explicit != null) {
            Path path = Path.of(explicit);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static Map<String, String> parse(String text) {
        Map<String, String> table = new HashMap<>();
        JsonObject root = JsonValue.parse(text).asObjectOrNull();
        if (root == null) {
            return table;
        }
        for (String key : root.keys()) {
            String value = root.optString(key, null);
            if (value != null) {
                table.put(key, value);
            }
        }
        return table;
    }
}

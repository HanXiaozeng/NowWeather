package com.nowweather.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nowweather.core.NowWeatherCore;
import com.nowweather.core.api.ProviderContext;
import com.nowweather.core.api.ProviderResult;
import com.nowweather.core.http.JdkHttpTransport;
import com.nowweather.core.providers.uapipro.UApiProWeatherProvider;
import com.nowweather.core.text.Text;
import com.nowweather.forge.NowWeatherLog;
import com.nowweather.forge.NowWeatherMod;
import com.nowweather.forge.config.ConfigEntries;
import com.nowweather.forge.config.ForgeConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * NowWeather 游戏内配置界面（模组列表 → NowWeather → 配置）。
 *
 * <h2>为什么需要自己写这个界面</h2>
 * <p>Forge 1.18.2 <b>不会</b>为注册了 {@code ForgeConfigSpec} 的模组自动生成配置界面
 * （自动生成是 1.19.x+ 才有的功能）。没注册 {@code ConfigGuiFactory} 的话，
 * 模组列表里那个「Config」按钮是灰的；注册了也只是拿到一个回调，界面得自己画。
 * 所以「玩家能在设置里改所有选项」这件事必须自己实现 —— 本界面覆盖
 * {@link ConfigEntries} 里的<b>全部</b>配置项。</p>
 *
 * <h2>设计</h2>
 * <ul>
 *   <li><b>标签页</b>按功能分组（位置 / 数据源 / 天气映射 / 时间同步 / 预报 / 同步与兼容 / 调试 / HUD），
 *       而不是把 50 多项摊成一长条；</li>
 *   <li>每页条数按窗口高度动态算（小窗口少显示几条），超出用上一页/下一页翻页 ——
 *       比手写滚动条可靠得多，也不会在低分辨率下把控件挤出屏幕；</li>
 *   <li>开关与枚举点一下切换，文本与数值用输入框，<b>保存时才校验</b>，
 *       越界的值当场提示「最小/最大是多少」，而不是被悄悄夹掉；</li>
 *   <li>标签左侧有 {@code ●} 表示「这一项已经不等于默认值」，改了哪些一目了然；</li>
 *   <li>悬停显示该项说明，「恢复本页默认」只改内存，点「保存并关闭」才写盘。</li>
 * </ul>
 *
 * <h2>热重载为什么不能只依赖 Forge</h2>
 * <p>实测（读 Forge 1.18.2 源码确认）{@code ConfigValue#set()} 只写内存、
 * {@code save()} 只写文件，<b>两者都不会触发 {@code ModConfigEvent.Reloading}</b> ——
 * 那个事件是文件监视器发现「外部改动」时才发的。所以保存后这里显式调用
 * {@link NowWeatherMod#reloadConfigNow()}，保证「点了保存就一定生效」，
 * 而不是赌文件监视器的时序。</p>
 */
public final class NowWeatherConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int LABEL_WIDTH = 148;
    private static final int CONTROL_WIDTH = 104;
    private static final int TAB_HEIGHT = 16;
    private static final int MIN_PAGE_SIZE = 3;
    private static final int MAX_PAGE_SIZE = 9;

    private final Screen parent;
    private final List<ConfigEntries.Section> sections = new ArrayList<>();
    private int sectionIndex;
    private int page;
    private int pageSize = 6;

    /** 当前页的控件与它对应的配置项。 */
    private final List<Row> rows = new ArrayList<>();

    private String statusLine = "";
    private int statusColor = 0xAAAAAA;
    private boolean testing;
    private int rowsTop;
    private String hoveredHint = "";

    /** 一行：配置项 + 它的控件（开关是按钮，其余是输入框）。 */
    private static final class Row {
        private final ConfigEntries.Entry entry;
        private final EditBox box;

        private Row(ConfigEntries.Entry entry, EditBox box) {
            this.entry = entry;
            this.box = box;
        }
    }

    public NowWeatherConfigScreen(Screen parent) {
        // 界面标题走 Minecraft 的语言文件（1.18.x 的 Component 没有静态工厂方法，只能 new）
        super(new TranslatableComponent("nowweather.config.title"));
        this.parent = parent;
        for (ConfigEntries.Section section : ConfigEntries.Section.values()) {
            sections.add(section);
        }
    }

    /**
     * 重建控件。
     *
     * <p>1.18.2 的 {@code Screen} <b>没有</b> 1.19+ 的 {@code rebuildWidgets()}，
     * 官方的做法是调用 {@code resize(...)} —— 它内部会 {@code clearWidgets()} 之后再
     * {@code init()}，所以翻页/切标签时用它重建是正确且安全的。</p>
     *
     * <p><b>注意</b>：正因为重建会销毁全部 EditBox，{@link #resize} 里先做了一次
     * {@link #flushCurrentPage()} 回写 —— 否则上一页输入到一半、还没点保存的文本会被静默丢掉。</p>
     */
    private void rebuild() {
        if (this.minecraft != null) {
            this.resize(this.minecraft, this.width, this.height);
        }
    }

    /**
     * 窗口尺寸变化 / 重建界面时的钩子。
     *
     * <p>这是<b>唯一</b>会销毁并重建 EditBox 的地方（{@code rebuild()} 与 Minecraft 的窗口缩放
     * 都走它），所以「把输入框内容写回配置」放在这里最稳：翻页、切分组、拉窗口都会被它兜住。</p>
     */
    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        flushCurrentPage();
        super.resize(minecraft, width, height);
    }

    @Override
    protected void init() {
        buildTabs();
        buildPage();
        buildBottomBar();
        if (statusLine.isEmpty()) {
            setStatus(Text.tr("nowweather.config.hint.save_required"), 0xAAAAAA);
        }
    }

    // ------------------------------------------------------------------ 标签页

    private void buildTabs() {
        int count = sections.size();
        int tabWidth = Math.min(78, Math.max(30, (this.width - 16) / count - 2));
        int total = count * (tabWidth + 2);
        int startX = (this.width - total) / 2;
        for (int i = 0; i < count; i++) {
            final int index = i;
            ConfigEntries.Section section = sections.get(i);
            Button button = new Button(startX + i * (tabWidth + 2), 34, tabWidth, TAB_HEIGHT,
                    new TextComponent(tabLabel(section, index)), b -> {
                sectionIndex = index;
                page = 0;
                setStatus("§7" + section.hint(), 0xAAAAAA);
                rebuild();
            });
            addRenderableWidget(button);
        }
    }

    private String tabLabel(ConfigEntries.Section section, int index) {
        return (index == sectionIndex ? "§e§l" : "§7") + section.zhName();
    }

    private List<ConfigEntries.Entry> currentEntries() {
        return ConfigEntries.of(sections.get(sectionIndex));
    }

    // ------------------------------------------------------------------ 内容页

    private void buildPage() {
        rows.clear();
        List<ConfigEntries.Entry> entries = currentEntries();

        // 每页条数按可用高度算：小窗口少显示几条，保证底部按钮永远在屏幕内
        int available = (this.height - 56) - rowsTop();
        pageSize = Math.max(MIN_PAGE_SIZE,
                Math.min(MAX_PAGE_SIZE, available / ROW_HEIGHT));

        int pageCount = pageCount(entries.size());
        page = Math.max(0, Math.min(page, pageCount - 1));
        rowsTop = rowsTop();

        int labelX = this.width / 2 - (LABEL_WIDTH + CONTROL_WIDTH) / 2;
        int controlX = labelX + LABEL_WIDTH + 6;

        int from = page * pageSize;
        int to = Math.min(entries.size(), from + pageSize);
        for (int i = from; i < to; i++) {
            ConfigEntries.Entry entry = entries.get(i);
            int y = rowsTop + (i - from) * ROW_HEIGHT;
            if (entry.kind() == ConfigEntries.Kind.TOGGLE) {
                Button button = new Button(controlX, y, CONTROL_WIDTH, 18,
                        new TextComponent(toggleText(entry)), b -> {
                    String error = entry.toggle();
                    if (error != null) {
                        setStatus("§c" + error, 0xFF5555);
                    } else {
                        setStatus("§7" + Text.tr("nowweather.config.status.changed",
                                entry.label(), entry.display()), 0xAAAAAA);
                    }
                    b.setMessage(new TextComponent(toggleText(entry)));
                });
                addRenderableWidget(button);
                rows.add(new Row(entry, null));
            } else {
                EditBox box = new EditBox(this.font, controlX, y, CONTROL_WIDTH, 18,
                        new TextComponent(""));
                box.setMaxLength(160);
                box.setValue(entry.display());
                addRenderableWidget(box);
                rows.add(new Row(entry, box));
            }
        }
    }

    private int rowsTop() {
        return 34 + TAB_HEIGHT + 12;
    }

    private int pageCount(int size) {
        return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    private String toggleText(ConfigEntries.Entry entry) {
        if (entry.enumZhName() != null) {
            return new TextComponent("§b" + entry.enumZhName()).getString();
        }
        return "true".equalsIgnoreCase(entry.display())
                ? Text.tr("nowweather.config.toggle.on")
                : Text.tr("nowweather.config.toggle.off");
    }

    // ------------------------------------------------------------------ 底部按钮

    private void buildBottomBar() {
        int centerX = this.width / 2;
        List<ConfigEntries.Entry> entries = currentEntries();
        int pageCount = pageCount(entries.size());

        int actionY = this.height - 50;
        int width = Math.min(88, (this.width - 30) / 4);
        int total = width * 4 + 6 * 3;
        int x = centerX - total / 2;
        addRenderableWidget(new Button(x, actionY, width, 20,
                new TranslatableComponent("nowweather.config.button.save")
                        .withStyle(ChatFormatting.GREEN),
                b -> {
                    if (save()) {
                        onClose();
                    }
                }));
        addRenderableWidget(new Button(x + width + 6, actionY, width, 20,
                new TranslatableComponent("nowweather.config.button.restore"),
                b -> restorePageDefaults()));
        addRenderableWidget(new Button(x + (width + 6) * 2, actionY, width, 20,
                new TranslatableComponent("nowweather.config.test"), b -> testConnection()));
        addRenderableWidget(new Button(x + (width + 6) * 3, actionY, width, 20,
                new TranslatableComponent("nowweather.config.button.cancel"), b -> onClose()));

        int pageY = this.height - 26;
        Button prev = new Button(centerX - 96, pageY, 52, 18,
                new TranslatableComponent("nowweather.config.button.prev_page")
                        .withStyle(ChatFormatting.GRAY), b -> {
            if (page > 0) {
                page--;
                rebuild();
            }
        });
        Button next = new Button(centerX + 44, pageY, 52, 18,
                new TranslatableComponent("nowweather.config.button.next_page")
                        .withStyle(ChatFormatting.GRAY), b -> {
            if (page + 1 < pageCount) {
                page++;
                rebuild();
            }
        });
        Button indicator = new Button(centerX - 40, pageY, 80, 18,
                new TranslatableComponent("nowweather.config.page_indicator",
                        page + 1, pageCount, entries.size()).withStyle(ChatFormatting.GRAY), b -> {
        });
        indicator.active = false;

        prev.active = page > 0;
        next.active = page + 1 < pageCount;
        addRenderableWidget(prev);
        addRenderableWidget(next);
        addRenderableWidget(indicator);
    }

    // ------------------------------------------------------------------ 保存 / 恢复 / 测试

    /**
     * 把当前页输入框里的文本写回配置（只改内存，不写盘）。
     *
     * <p><b>为什么必须单独抽出来</b>：翻页 / 切分组 / 缩放窗口都会重建界面并销毁 EditBox。
     * 开关是点一下即时写内存的，只有文本框会丢 ——
     * 之前翻页直接走 {@code rebuild() → resize()}，上一页输入到一半、还没点「保存」的文本
     * 就被静默丢掉了，玩家只会觉得「我明明填了」。所以任何重建路径之前都要先调它。</p>
     *
     * @return 校验失败的说明；空列表表示当前页的输入全部合法（非法值本来就写不进去）
     */
    private List<String> flushCurrentPage() {
        List<String> errors = new ArrayList<>();
        for (Row row : rows) {
            if (row.box == null) {
                continue;
            }
            String error = row.entry.setFromString(row.box.getValue());
            if (error != null) {
                errors.add(Text.tr("nowweather.config.status.error_item", row.entry.label(), error));
            }
        }
        return errors;
    }

    /** 把当前页的输入框写回配置，然后写盘并热重载。 */
    private boolean save() {
        List<String> errors = flushCurrentPage();
        if (!errors.isEmpty()) {
            setStatus("§c" + String.join(Text.tr("nowweather.config.status.error_separator"), errors),
                    0xFF5555);
            return false;
        }
        try {
            ForgeConfig.COMMON_SPEC.save();
            ForgeConfig.CLIENT_SPEC.save();
            // ★ Forge 不会因为我们 set()/save() 就发 Reloading（已读源码确认），必须自己叫一次
            NowWeatherMod.reloadConfigNow();
            setStatus(Text.tr("nowweather.config.status.saved"), 0x55FF55);
            // 日志面向开发者、保持中文不翻译，所以这里用稳定的枚举名而不是随语言变化的显示名
            NowWeatherLog.LOGGER.info("配置已通过游戏内界面保存（分组={}）",
                    sections.get(sectionIndex).name());
            return true;
        } catch (RuntimeException e) {
            setStatus("§c" + Text.tr("nowweather.config.status.save_failed", String.valueOf(e.getMessage())),
                    0xFF5555);
            NowWeatherLog.LOGGER.warn("保存配置失败", e);
            return false;
        }
    }

    /**
     * 只把<b>本页</b>的项恢复默认值 —— 与按钮/提示文案一致。
     *
     * <p>以前这里重置的是整个分组（{@code ConfigEntries.of(section)}）：HUD 分组有十几项、
     * 每页最多 9 项，点一次会把看不见的下一页一起重置掉，玩家根本不知道自己动了什么。</p>
     */
    private void restorePageDefaults() {
        // 先记下本页的项再清空 rows：清空是为了让 resize() 里的回写变成空操作，
        // 否则还在输入框里的旧文本会把刚重置出来的默认值又盖回去。
        List<ConfigEntries.Entry> currentPage = new ArrayList<>();
        for (Row row : rows) {
            currentPage.add(row.entry);
        }
        rows.clear();
        for (ConfigEntries.Entry entry : currentPage) {
            entry.resetToDefault();
        }
        setStatus(Text.tr("nowweather.config.status.page_restored"), 0xFFFF55);
        rebuild();
    }

    /** 后台线程真去请求一次接口，确认 API Key / 城市可用。 */
    private void testConnection() {
        if (testing) {
            return;
        }
        testing = true;
        setStatus("§e" + Text.tr("nowweather.config.test.running"), 0xFFFF55);

        // 先把当前页的输入框写回内存配置，保证测的是「刚填的内容」而不是旧值
        flushCurrentPage();
        String city = ForgeConfig.COMMON.city.get();
        String adcode = ForgeConfig.COMMON.adcode.get();
        String apiKey = ForgeConfig.COMMON.apiKey.get();

        Thread worker = new Thread(() -> {
            boolean success;
            String message;
            try {
                UApiProWeatherProvider provider = new UApiProWeatherProvider(new JdkHttpTransport());
                ProviderResult result = provider.fetch(ProviderContext.builder()
                        .city(city)
                        .adcode(adcode)
                        .apiKey(apiKey)
                        .timeoutMillis(8_000L)
                        .build());
                if (result.isSuccess() && result.observation() != null) {
                    success = true;
                    message = "§a" + Text.tr("nowweather.config.test.ok",
                            result.observation().describeForPlayer());
                } else {
                    success = false;
                    message = "§c" + Text.tr("nowweather.config.test.fail",
                            result.status() + (result.message().isEmpty() ? "" : " — " + result.message()));
                }
            } catch (RuntimeException e) {
                success = false;
                message = "§c" + Text.tr("nowweather.config.test.error",
                        e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
            }
            boolean finalSuccess = success;
            String finalMessage = message;
            Minecraft.getInstance().execute(() -> {
                setStatus(finalMessage, finalSuccess ? 0x55FF55 : 0xFF5555);
                testing = false;
            });
        }, "NowWeather-ConfigTest");
        worker.setDaemon(true);
        worker.start();
    }

    private void setStatus(String text, int color) {
        statusLine = text;
        statusColor = color;
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        fill(pose, 0, 0, this.width, this.height, 0xE0101010);

        int centerX = this.width / 2;
        drawCenteredString(pose, this.font, title, centerX, 10, 0xFFFFFF);
        drawCenteredString(pose, this.font,
                new TranslatableComponent("nowweather.config.subtitle",
                        com.nowweather.forge.NowWeatherMod.displayVersion())
                        .withStyle(ChatFormatting.GRAY),
                centerX, 22, 0xAAAAAA);

        hoveredHint = "";
        int labelX = centerX - (LABEL_WIDTH + CONTROL_WIDTH) / 2;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = rowsTop + i * ROW_HEIGHT;
            boolean modified = !row.entry.isDefault();
            drawString(pose, this.font, row.entry.label(), labelX, y + 5,
                    modified ? 0xFFD070 : 0xDDDDDD);
            if (modified) {
                drawString(pose, this.font, "§6●", labelX - 10, y + 5, 0xFFD070);
            }
            if (mouseX >= labelX - 12 && mouseX <= labelX + LABEL_WIDTH
                    && mouseY >= y && mouseY <= y + 18) {
                String hint = row.entry.hint();
                hoveredHint = hint == null || hint.isEmpty() ? row.entry.key() : hint;
            }
        }

        super.render(pose, mouseX, mouseY, partialTick);

        if (!hoveredHint.isEmpty()) {
            drawCenteredString(pose, this.font, new TextComponent("§b" + hoveredHint),
                    centerX, this.height - 68, 0x55FFFF);
        }
        if (!statusLine.isEmpty()) {
            drawCenteredString(pose, this.font, new TextComponent(statusLine), centerX,
                    this.height - 82, statusColor);
        }
    }

    @Override
    public void renderBackground(PoseStack pose) {
        // 用纯色背景而不是原版的全屏模糊/全景图：这个界面信息密度高，背景越素越好读
        fill(pose, 0, 0, this.width, this.height, 0xE0101010);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}

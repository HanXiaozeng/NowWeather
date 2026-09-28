# Forge 1.18.2 (40.x) API 速查表 —— 逐字核对版

> 适用版本：**Minecraft 1.18.2 + Forge 40.x**（本文所有核对以 `net.minecraftforge:forge:1.18.2-40.3.12` 为准，
> 与本仓库 `versions/forge-1.18.2/build.gradle` 中声明的版本一致）。
> 目标模组：`modId = weathersync`（配置 / 网络包 / 服务端 tick 天气 / 命令 / 数据包 JSON / 生物群系 / HUD）。

## 0. 核对方法与置信度标记

本次核对的**一手来源**（不是教程、不是记忆）：

| 编号 | 来源 | 用途 |
| :--- | :--- | :--- |
| **S1** | Forge 1.18.x 源码分支 `https://github.com/MinecraftForge/MinecraftForge/tree/1.18.x`（本人 clone 到本地逐文件 grep） | Forge 侧类/方法/注解的**逐字**签名 |
| **S2** | **字节码核对**：`forge-1.18.2-40.3.12-universal.jar` / `fmlcore-1.18.2-40.3.12.jar` / `javafmllanguage-1.18.2-40.3.12.jar` / `eventbus-5.0.3.jar`（Maven 下载后用 `javap` 反查） | 确认「某个类/方法在 40.3.12 里到底存不存在」 |
| **S3** | Forge 官方文档 1.18.x：<https://docs.minecraftforge.net/en/1.18.x/>（concepts/sides、concepts/events、concepts/resources、networking/simpleimpl、misc/config、datastorage/capabilities、resources/server） | 官方行为说明（配置类型表、包优先级、事件总线、事件优先级…） |
| **S4** | Forge 1.18.2 javadoc 镜像：`https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.18.2/`（forge **1.18.2-40.2.1** 的 javadoc） | **Minecraft 原版类**（`ServerLevel` / `Biome` / `ResourceManager` …）的签名与修饰符 |
| **S5** | Mojang 官方 1.18.2 映射 `client.txt`（经 `piston-meta` 版本清单下载）+ <https://mappings.dev/1.18.2/…> | 原版方法的返回值/存在性、参数名的交叉验证 |
| **S6** | Forge 仓库内 `patches/minecraft/**`（Forge 对原版打的补丁，带原版源码上下文） | 原版代码的逐字上下文（如 `Holder` 被 Forge 扩展、`SimpleJsonResourceReloadListener` 的路径规则） |

置信度标记：

- ✅ **明确** = S1/S2（源码或字节码）或 S3（官方文档原文）直接确认；
- ◐ **推断** = 来自 S4 javadoc / S5 官方映射 / S6 补丁上下文推断（可信度高，但没有官方文档正文背书）；
- ❓ **不确定** = 未能逐字核对，**不要当成事实使用**。

---

## 1. 模组入口（@Mod / 事件总线 / Dist 隔离）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x）

```java
package com.weathersync;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

// ✅ @Mod 只有一个 value()（modid）。1.18.2 的 javafml 只支持「public 无参构造」。
@Mod(WeatherSync.MODID)
public class WeatherSync {

    public static final String MODID = "weathersync";

    /** 模组总线：注册 DeferredRegister、生命周期事件、RegisterCapabilitiesEvent、ModConfigEvent */
    public static final IEventBus MOD_BUS = FMLJavaModLoadingContext.get().getModEventBus();
    /** FORGE 总线：注册 gameplay / server / tick / command / reload 事件 */
    public static final IEventBus FORGE_BUS = MinecraftForge.EVENT_BUS;

    public WeatherSync() {                       // ⚠️ 必须无参；构造期就在 CONSTRUCT 阶段
        // —— 配置：必须在构造期（或 mod 总线的生命周期事件里）注册
        com.weathersync.config.WeatherConfig.register();

        // —— 网络：在 FMLCommonSetupEvent 里注册消息
        MOD_BUS.addListener(this::onCommonSetup);

        // —— 服务端逻辑：FORGE 总线
        FORGE_BUS.addListener(ServerWeatherDriver::onServerTick);
        FORGE_BUS.addListener(WeatherCommands::onRegisterCommands);
        FORGE_BUS.addListener(ClimateDataReloadListener::onAddReloadListener);

        // —— 客户端专用代码：物理端隔离（见下文 DistExecutor）
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientInit::init);
    }

    private void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        // ParallelDispatchEvent 是并行的，跨线程/触碰游戏状态的东西必须 enqueueWork 到主线程
        event.enqueueWork(WeatherNetwork::register);
    }
}
```

**构造参数：1.18.2 只支持无参构造（重要）。**

```java
// S1: javafmllanguage/src/main/java/net/minecraftforge/fml/javafmlmod/FMLModContainer.java:67
this.modInstance = modClass.getDeclaredConstructor().newInstance();
```

- ✅ **明确**：`FMLModContainer#constructMod()` 只调用 `getDeclaredConstructor()`（零参数）。
- ❌ **不要写** `public WeatherSync(IEventBus bus)` / `(ModContainer c)` / `(Dist d)`：那是 **1.20.2+/NeoForge 的构造器注入**，在 1.18.2 会直接 `NoSuchMethodException` → 模组加载失败。
- 1.18.2 里拿事件总线的唯一正确写法：`FMLJavaModLoadingContext.get().getModEventBus()`（mod 总线）与 `MinecraftForge.EVENT_BUS`（Forge 总线）。

**静态字段里能不能调 `FMLJavaModLoadingContext.get()`？可以。** ✅
`FMLJavaModLoadingContext.get()` → `ModLoadingContext.get().extension()`（`FMLJavaModLoadingContext.java:33`），而
`ModLoadingContext` 是 `ThreadLocal`，其 `activeContainer` 由 `ModContainer.java:123` 在**执行 CONSTRUCT 阶段的那个线程上**设置：
`ModLoadingContext.get().setActiveContainer(target)`。类的静态初始化发生在 `newInstance()` 期间，因此静态字段可用。
但**注意**：`ModLoadingContext` 是线程本地量，只能在「模组加载线程 + 当前 mod 的加载阶段」内使用（见 §2 的坑）。

**`@Mod.EventBusSubscriber`**（✅ S2 字节码）：

```java
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// value = 物理端数组（默认 {Dist.CLIENT, Dist.DEDICATED_SERVER}）
// modid = 建议显式写（注解处理器/自动注册靠它定位）
// bus   = Bus.FORGE（默认）或 Bus.MOD
@Mod.EventBusSubscriber(modid = WeatherSync.MODID,
                        bus = Mod.EventBusSubscriber.Bus.FORGE,
                        value = Dist.CLIENT)
public final class ClientForgeEvents {
    @SubscribeEvent
    public static void onTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) { /* ... */ }
}
```

注解成员（`javap net.minecraftforge.fml.common.Mod$EventBusSubscriber`）：

```java
Dist[] value();                                   // 默认 { CLIENT, DEDICATED_SERVER }
String modid();                                   // 默认 ""
Mod.EventBusSubscriber.Bus bus();                 // 默认 Bus.FORGE
// Bus 枚举：FORGE, MOD
```

> 注意：`@Mod.EventBusSubscriber` 注册的是**类**，处理方法必须 `static`。

**手动注册（构造期）的四种 `IEventBus` 重载**（✅ S2，`eventbus-5.0.3.jar`）：

```java
void register(Object target);
<T extends Event> void addListener(Consumer<T> listener);
<T extends Event> void addListener(EventPriority priority, Consumer<T> listener);
<T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> listener);
<T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> listener);
```

**`@SubscribeEvent`**（✅ S2）：`EventPriority priority();`（默认 `NORMAL`）+ `boolean receiveCanceled();`（默认 `false`）。

**客户端专用代码的隔离（重要）** ✅ S3 + S2：

```java
// 方式 A（官方推荐的 safe* 形式，注意下面的坑）
DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientInit::init);

// 方式 B（1.18.2 里更省心的写法，避免 Java 9+ invokedynamic 包装异常的问题）
DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientInit::init);

// 方式 C（最直白：物理端判断，1.18.2 可用）
if (net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
    ClientInit.init();
}
```

完整签名（✅ S2，`fmlcore`）：

```java
public static <T> T unsafeCallWhenOn(Dist dist, Supplier<Callable<T>> toRun);
public static <T> T safeCallWhenOn(Dist dist, Supplier<DistExecutor.SafeCallable<T>> toRun);
public static void unsafeRunWhenOn(Dist dist, Supplier<Runnable> toRun);
public static void safeRunWhenOn(Dist dist, Supplier<DistExecutor.SafeRunnable> toRun);
public static <T> T unsafeRunForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget);
public static <T> T safeRunForDist(Supplier<DistExecutor.SafeSupplier<T>> clientTarget, Supplier<DistExecutor.SafeSupplier<T>> serverTarget);
```

- `SafeRunnable` / `SafeCallable` / `SafeSupplier` 是 `DistExecutor` 的**嵌套专用函数式接口**（不是 `Runnable`/`Supplier` 的别名），所以 `safeRunWhenOn(Dist.CLIENT, () -> () -> Foo.bar())` 这种双层 lambda **编译不过**，必须写成方法引用 `() -> ClientInit::init`。
- ✅ S3 原文警告：Java 9+ 下 `safe*` 在**开发环境**会把原异常包进 `BootstrapMethodError`；官方建议改用 `unsafe*` 或 `FMLEnvironment.dist` 判断。
- 关键规则：`Dist` 是**物理端**；单机存档里逻辑服务端也跑在 `Dist.CLIENT` 里。判断逻辑端请用 `Level#isClientSide`（S3）。

### 来源与置信度

- ✅ 无参构造：S1 `javafmllanguage/.../FMLModContainer.java:67`；S2 `javap -p` 只有 `private void constructMod()`。**这条是本次核对中最大的「以为是但实际上不是」。**
- ✅ `@Mod` 只有 `value()`；`@Mod.EventBusSubscriber` 的 `value/modid/bus`：S2 `javap`。
- ✅ `FMLJavaModLoadingContext.get()` / `getModEventBus()`：S1 + S2。
- ✅ `DistExecutor` 全部重载：S2 `javap net.minecraftforge.fml.DistExecutor`；safe/unsafe 语义：S3 <https://docs.minecraftforge.net/en/1.18.x/concepts/sides/>。
- ✅ `IEventBus#addListener` 重载、`SubscribeEvent#priority/receiveCanceled`：S2 `javap`（`eventbus-5.0.3.jar`，Forge 1.18.x `build.gradle` 声明 `EVENTBUS_VERSION = '5.0.3'`）。
- ◐ `EventPriority` 顺序（HIGHEST 最先，LOWEST 最后）：S3 <https://docs.minecraftforge.net/en/1.18.x/concepts/events/>。

---

## 2. 配置（ForgeConfigSpec / 三种类型 / 游戏内 GUI）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x）

```java
package com.weathersync.config;

import java.util.List;
import com.mojang.datafixers.util.Pair;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class WeatherConfig {

    public enum SyncMode { SERVER_AUTHORITATIVE, CLIENT_PREDICTIVE }

    public static final ForgeConfigSpec SPEC;
    private static final WeatherConfig INSTANCE;

    // 注意：configure(Function<Builder,T>) 模式下，值放在「配置对象」的实例字段里。
    // 不要试图在实例构造器里给 static final 赋值（Java 不允许）。
    public final ForgeConfigSpec.BooleanValue enabled;
    public final ForgeConfigSpec.IntValue syncIntervalTicks;
    public final ForgeConfigSpec.DoubleValue rainChance;
    public final ForgeConfigSpec.EnumValue<SyncMode> mode;
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> blacklistedDimensions;

    static {
        Pair<WeatherConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                .comment("WeatherSync configuration")     // comment(String...) 只能给「下一个 push/value」用
                .configure(WeatherConfig::new);           // ✅ 官方文档推荐的 configure 模式
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private WeatherConfig(ForgeConfigSpec.Builder b) {
        b.comment("General settings").push("general");
        enabled           = b.comment("Master switch").define("enabled", true);
        syncIntervalTicks = b.comment("Sync interval in ticks (20 = 1s)")
                             .defineInRange("syncIntervalTicks", 20, 1, 72000);
        mode              = b.defineEnum("mode", SyncMode.SERVER_AUTHORITATIVE);
        b.pop();

        b.comment("Climate settings").push("climate");
        rainChance = b.comment("0.0 - 1.0").defineInRange("rainChance", 0.25D, 0.0D, 1.0D);
        blacklistedDimensions = b
                .comment("Dimensions that WeatherSync will not touch")
                .defineList("blacklistedDimensions",
                            List.of("minecraft:the_end"),   // 默认值
                            o -> o instanceof String);      // 元素校验 Predicate<Object>
        b.pop();
    }

    public static WeatherConfig get() { return INSTANCE; }

    /** 在模组构造期调用（见 §0 的线程/阶段限制）。 */
    public static void register() {
        ModLoadingContext ctx = ModLoadingContext.get();
        ctx.registerConfig(ModConfig.Type.COMMON, SPEC);                           // 也可传第三个参数 fileName
        // ctx.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC, "weathersync-server.toml");
    }
}
```

**`ForgeConfigSpec.Builder` 关键签名**（✅ S2 `javap`，`40.3.12`）：

```java
// 构建 / 分节
public <T> Pair<T, ForgeConfigSpec> configure(java.util.function.Function<Builder, T> consumer);
public ForgeConfigSpec build();
public Builder push(String path);
public Builder push(List<String> path);
public Builder pop();
public Builder pop(int count);
// 上下文（必须在 define* 之前调用）
public Builder comment(String comment);
public Builder comment(String... comment);
public Builder translation(String translationKey);
public Builder worldRestart();

// 值
public BooleanValue  define(String path, boolean defaultValue);
public <T> ConfigValue<T> define(String path, T defaultValue);
public <T> ConfigValue<T> define(String path, T defaultValue, java.util.function.Predicate<Object> validator);
public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(String path, V defaultValue, V min, V max, Class<V> clazz);
public <T> ConfigValue<List<? extends T>> defineList(String path, List<? extends T> defaultValue, java.util.function.Predicate<Object> elementValidator);
public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(List<String> path, Supplier<List<? extends T>> defaultSupplier, java.util.function.Predicate<Object> elementValidator);
public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue);
public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue, V... acceptableValues);

// 值对象
public T get();
public void set(T value);     // ⚠️ 只能改「当前已加载」的配置；改完要 save()
public void save();
public void clearCache();
```

**注册签名**（✅ S2）：`ModLoadingContext#registerConfig(ModConfig.Type, IConfigSpec<?>)` 和 `registerConfig(ModConfig.Type, IConfigSpec<?>, String fileName)`。

**三种类型（✅ S3 官方文档表格 + ✅ S1 `ModConfig.Type` javadoc）**

| Type | 加载位置 | 是否同步到客户端 | 客户端文件位置 | 服务端文件位置 | 默认后缀 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `CLIENT` | 仅客户端 | 否 | `.minecraft/config` | — | `-client` |
| `COMMON` | 双端 | 否 | `.minecraft/config` | `<server_folder>/config` | `-common` |
| `SERVER` | 仅服务端 | **是** | `.minecraft/saves/<level>/serverconfig` | `<server_folder>/world/serverconfig` | `-server` |

**游戏内配置 GUI（重点，1.18.2 的真相）**

```java
// 客户端专用类（用 DistExecutor 调用，避免专用服务端加载 net.minecraft.client.*）
package com.weathersync.client;

import net.minecraftforge.client.ConfigGuiHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class ClientInit {
    public static void init() {
        // ✅ 1.18.2 的唯一正规做法：把 ConfigGuiFactory 作为 IExtensionPoint 注册
        ModLoadingContext.get().registerExtensionPoint(
            ConfigGuiHandler.ConfigGuiFactory.class,
            () -> new ConfigGuiHandler.ConfigGuiFactory(
                     (mc, modsScreen) -> new WeatherConfigScreen(modsScreen)));
    }
}
```

`ConfigGuiHandler` 的**完整签名**（✅ S2 `javap net.minecraftforge.client.ConfigGuiHandler`，包名 **`net.minecraftforge.client`**）：

```java
public class ConfigGuiHandler {
    // 记录类型（record），实现了 IExtensionPoint<ConfigGuiFactory>
    public static record ConfigGuiFactory(
        java.util.function.BiFunction<Minecraft, Screen, Screen> screenFunction
    ) implements IExtensionPoint<ConfigGuiFactory> {
        public ConfigGuiFactory(java.util.function.Function<Screen, Screen> screenFunction); // 便捷构造器
    }

    public static java.util.Optional<java.util.function.BiFunction<Minecraft, Screen, Screen>>
        getGuiFactoryFor(net.minecraftforge.forgespi.language.IModInfo selectedMod);
}
```

**三个必须记住的事实：**

1. ❌ **不存在** `ConfigGuiHandler.registerModConfigScreenFactory(...)`。那是 1.12/MCP 时代的 API。
2. ❌ **Forge 1.18.2 不会自动生成配置界面。** 没注册 `ConfigGuiFactory` 的模组，模组列表里的 **Config 按钮是灰的（禁用）**：
   ✅ S1 `src/main/java/net/minecraftforge/client/gui/ModListScreen.java:400`
   ```java
   this.configButton.active = ConfigGuiHandler.getGuiFactoryFor(selectedMod).isPresent();
   ```
   （点击时：`ModListScreen.java:304`，`displayModConfig()` → `getGuiFactoryFor(...).map(f -> f.apply(this.minecraft, this))`，异常只会记日志。）
   「自动生成配置界面」是 **1.19.x+** 才有的东西（`ConfigScreenHandler` / `ConfigurationScreen` 这套），本次已用字节码确认 **`net.minecraftforge.client.ConfigScreenHandler` 与 `net.minecraftforge.client.gui.VanillaGuiOverlay` 在 40.3.12 中都不存在**。
3. 可编辑性：`ConfigValue#set(...)` 只改内存中的 `CommentedConfig`，`save()` 才落盘。
   真正能「在游戏内改并持久化」的是 **`CLIENT` / `COMMON`**（文件在本地 `.minecraft/config`）。
   `SERVER` 类型在**客户端**是内存态（`ConfigTracker#loadDefaultServerConfigs()` 用 `CommentedConfig.inMemory()` 建，✅ S1），
   客户端改了既不落盘、也会被服务端下次同步覆盖 → 要改 SERVER 配置请在服务端控制台/文件里改，或做一个发到服务端的自定义包。
   ◐（此段由 S1 `ConfigTracker.java` + `ForgeConfigSpec$ConfigValue#set/save` 源码推断，官方文档没有明说「哪些类型能在游戏内编辑」。）

**`ModConfigEvent`（包名很容易写错）**

```java
import net.minecraftforge.fml.event.config.ModConfigEvent;   // ✅ 注意包是 fml.event.config

@Mod.EventBusSubscriber(modid = WeatherSync.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ConfigEvents {
    @SubscribeEvent
    public static void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getType() == ModConfig.Type.SERVER) { /* 重建缓存 */ }
    }
    @SubscribeEvent
    public static void onReloading(ModConfigEvent.Reloading event) { /* 重载后刷新 */ }
}
```

- ✅ S1：`net.minecraftforge.fml.event.config.ModConfigEvent extends Event implements IModBusEvent`，嵌套 `Loading` / `Reloading`，`getConfig()` 返回 `ModConfig`。
- ✅ 字节码确认：`net.minecraftforge.fml.config.ModConfigEvent` **不存在**（`ModConfig.Loading` 是 1.16 的写法）。
- ✅ 因为是 `IModBusEvent`，必须注册到 **mod 总线**（`Bus.MOD`）。S3 警告：事件对所有配置都会触发，要用 `event.getConfig()` 自行区分。

### 来源与置信度

- ✅ `configure/push/pop/define*/defineInRange/defineList/defineEnum/build`：S2 `javap 'net.minecraftforge.common.ForgeConfigSpec$Builder'`。
- ✅ `configure` 模式与 `ConfigValue` 用法：S3 <https://docs.minecraftforge.net/en/1.18.x/misc/config/>。
- ✅ 三类型表：S3 同页表格。✅ `CLIENT/COMMON/SERVER` 枚举与后缀：S2 `javap 'net.minecraftforge.fml.config.ModConfig$Type'`。
- ✅ `ConfigGuiHandler` 两个构造器 + `getGuiFactoryFor`：S2 `javap`。✅ Config 按钮禁用逻辑：S1 `ModListScreen.java:400`。
- ✅ `ModConfigEvent` 包名/层级/mod 总线：S1 + S2。
- ◐ 「哪些类型可游戏内编辑」：S1 源码推断（官方文档未直接说明）。
- ❓ 未核对：`ConfigValue#set` 在「配置尚未加载」时的具体异常消息（源码是 `Preconditions.checkNotNull(spec.childConfig, "Cannot set config value without assigned Config object present")`，✅ 这条其实是明确的，见 S1 `ForgeConfigSpec.java:868-874`）。

---

## 3. 网络（SimpleChannel / PacketDistributor）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x）

```java
package com.weathersync.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;   // ⚠️ 注意包名里有 .simple

public final class WeatherNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("weathersync", "main"),  // 通道名（必须唯一）
            () -> PROTOCOL,                               // 我方协议版本
            PROTOCOL::equals,                             // 客户端接受服务端版本
            PROTOCOL::equals);                            // 服务端接受客户端版本

    public static void register() {      // 在 FMLCommonSetupEvent#enqueueWork 里调用
        int id = 0;
        CHANNEL.registerMessage(id++, WeatherSyncPacket.class,
                WeatherSyncPacket::encode,     // BiConsumer<MSG, FriendlyByteBuf>
                WeatherSyncPacket::decode,     // Function<FriendlyByteBuf, MSG>
                WeatherSyncPacket::handle);    // BiConsumer<MSG, Supplier<NetworkEvent.Context>>
    }
}
```

完整泛型签名（✅ S2 `javap net.minecraftforge.network.simple.SimpleChannel`）：

```java
public <MSG> IndexedMessageCodec.MessageHandler<MSG> registerMessage(
        int index, Class<MSG> messageType,
        java.util.function.BiConsumer<MSG, FriendlyByteBuf> encoder,
        java.util.function.Function<FriendlyByteBuf, MSG> decoder,
        java.util.function.BiConsumer<MSG, java.util.function.Supplier<NetworkEvent.Context>> messageConsumer);

public <MSG> IndexedMessageCodec.MessageHandler<MSG> registerMessage(
        int index, Class<MSG> messageType,
        BiConsumer<MSG, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, MSG> decoder,
        BiConsumer<MSG, Supplier<NetworkEvent.Context>> messageConsumer,
        java.util.Optional<net.minecraftforge.network.NetworkDirection> networkDirection);

public <MSG> void sendToServer(MSG message);
public <MSG> void send(net.minecraftforge.network.PacketDistributor.PacketTarget target, MSG message);
public <MSG> void sendTo(MSG message, net.minecraft.network.Connection manager, NetworkDirection direction);
public <MSG> void reply(MSG msgToReply, NetworkEvent.Context context);
```

`NetworkRegistry.newSimpleChannel` 四参数含义（✅ S2 + S3）：

```java
public static SimpleChannel newSimpleChannel(
    ResourceLocation name,                              // 通道名，必须是唯一 ResourceLocation
    Supplier<String> networkProtocolVersion,            // 我方协议版本（登录时下发）
    Predicate<String> clientAcceptedVersions,           // 客户端侧校验：收到服务端版本 → 是否兼容
    Predicate<String> serverAcceptedVersions);          // 服务端侧校验：收到客户端版本 → 是否兼容
```

三个「元版本」常量（✅ S2：`public static String ABSENT` / `ACCEPTVANILLA`，注意**不是 final**）：`NetworkRegistry.ABSENT`（对端是 Forge 但没有这个通道）、`NetworkRegistry.ACCEPTVANILLA`（对端是原版/非 Forge）。

**1.18.2 是否弃用 `newSimpleChannel`？—— 没有弃用。** ✅
`NetworkRegistry.java` 全文**没有任何 `@Deprecated`**；`newSimpleChannel` 只是把参数转给 `ChannelBuilder`。

`ChannelBuilder` 在 1.18.2 **存在**，但收尾方法名和 javadoc 里写的不一样（✅ S2 `javap`，源码 S1 `NetworkRegistry.java:359-442`）：

```java
// ✅ 1.18.2 真实 API
SimpleChannel ch = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation("weathersync", "main"))
        .networkProtocolVersion(() -> "1")
        .clientAcceptedVersions("1"::equals)
        .serverAcceptedVersions("1"::equals)
        .simpleChannel();          // ← 是 simpleChannel()
// 也有 eventNetworkChannel()
```

> ⚠️ 源码 javadoc 里 `@see ChannelBuilder#newSimpleChannel(...)` 是**过时的错误引用**：`ChannelBuilder` 里根本没有 `newSimpleChannel` 方法。

**包处理器（服务端→客户端）**

```java
package com.weathersync.net;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

public class WeatherSyncPacket {
    private final boolean raining;
    private final boolean thundering;
    private final int rainTicks;

    public WeatherSyncPacket(boolean raining, boolean thundering, int rainTicks) { /* ... */ }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(raining);
        buf.writeBoolean(thundering);
        buf.writeVarInt(rainTicks);
    }

    public static WeatherSyncPacket decode(FriendlyByteBuf buf) {
        return new WeatherSyncPacket(buf.readBoolean(), buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(WeatherSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        // ⚠️ 包处理器默认跑在网络线程，不能直接碰游戏对象
        c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> WeatherClientState.accept(msg)));
        c.setPacketHandled(true);   // 必须调用，否则 Forge 会认为包没处理
    }
}
```

`NetworkEvent.Context` 完整签名（✅ S2 `javap 'net.minecraftforge.network.NetworkEvent$Context'`）：

```java
public NetworkDirection getDirection();
public NetworkEvent.PacketDispatcher getPacketDispatcher();
public <T> io.netty.util.Attribute<T> attr(io.netty.util.AttributeKey<T> key);
public void setPacketHandled(boolean packetHandled);
public boolean getPacketHandled();
public java.util.concurrent.CompletableFuture<Void> enqueueWork(Runnable runnable);
public net.minecraft.server.level.ServerPlayer getSender();     // 仅服务端侧有效（客户端侧为 null）
public net.minecraft.network.Connection getNetworkManager();
```

**发送（服务端→客户端）** —— `PacketDistributor` 常量与泛型（✅ S2）：

```java
// 单个玩家（客户端玩家对象用 ServerPlayer）
WeatherNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), packet);

// 所有人
WeatherNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);

// 整个维度（注意泛型是 ResourceKey<Level>，不是 DimensionType）
WeatherNetwork.CHANNEL.send(PacketDistributor.DIMENSION.with(() -> Level.OVERWORLD), packet);

// 其它可用：NEAR.with(() -> PacketDistributor.TargetPoint.p(x,y,z,r2,Level.OVERWORLD))
//           TRACKING_CHUNK.with(() -> levelChunk)、TRACKING_ENTITY.with(() -> entity)
```

`public static final PacketDistributor<ServerPlayer> PLAYER;` / `<Void> ALL;` / `<ResourceKey<Level>> DIMENSION;` / `<TargetPoint> NEAR;` / `<Void> SERVER;` / `<Entity> TRACKING_ENTITY`、`TRACKING_ENTITY_AND_SELF` / `<LevelChunk> TRACKING_CHUNK`（✅ S2 全部存在）。

**客户端 → 服务端**

```java
// 客户端侧拿 LocalPlayer：Minecraft.getInstance().player（@Nullable）
LocalPlayer player = Minecraft.getInstance().player;
if (player != null) {
    WeatherNetwork.CHANNEL.sendToServer(new RequestWeatherPacket());
}
// 服务端侧在 handle 里用 ctx.get().getSender() 拿发件人 ServerPlayer
```

### 来源与置信度

- ✅ `newSimpleChannel` 四参数与语义、`ABSENT`/`ACCEPTVANILLA`：S1 `NetworkRegistry.java:104` + S3 <https://docs.minecraftforge.net/en/1.18.x/networking/simpleimpl/> + S2。
- ✅ 「1.18.2 未弃用 `newSimpleChannel`」：S1 全文无 `@Deprecated`；S2 中该方法存在。
- ✅ `ChannelBuilder` 收尾方法为 `simpleChannel()` / `eventNetworkChannel()`（不是 `newSimpleChannel`）：S1 `NetworkRegistry.java:431/439` + S2 `javap`。
- ✅ `SimpleChannel` 包名 `net.minecraftforge.network.simple`、`registerMessage` 两个重载：S2 `javap`。
- ✅ `NetworkEvent.Context`、`PacketDistributor`：S2 `javap`。
- ✅ 包处理器在网络线程、需 `enqueueWork` + `setPacketHandled`：S3 simpleimpl 页原文。
- ◐ 协议版本不匹配会导致登录被拒（FML deny login）：S3 原文。

---

## 4. 事件（Tick / 生命周期 / 命令 / ReloadListener）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x）

**服务端 tick（⚠️ `ServerTickEvent` 没有 `getServer()`）**

```java
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;   // ⚠️ 包是 net.minecraftforge.server

public final class ServerWeatherDriver {
    private static int ticks;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;          // phase 是 public final 字段，没有 getPhase()
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;                              // 可能为 null
        if (++ticks < com.weathersync.config.WeatherConfig.get().syncIntervalTicks.get()) return;
        ticks = 0;

        ServerLevel overworld = server.overworld();
        // ... 推进天气、发包
    }
}
```

**`TickEvent` 家族的完整公开成员（✅ S2 `javap`，逐字节核对）**

```java
public class TickEvent extends Event {
    public enum Type { WORLD, PLAYER, CLIENT, SERVER, RENDER; }
    public enum Phase { START, END; }
    public final Type type;
    public final net.minecraftforge.fml.LogicalSide side;
    public final Phase phase;                     // ← 是字段，不是 getter
    public TickEvent(Type type, LogicalSide side, Phase phase);

    public static class ServerTickEvent extends TickEvent {
        public ServerTickEvent(Phase phase);                       // @Deprecated(forRemoval, since = "1.18.1")
        public ServerTickEvent(Phase phase, java.util.function.BooleanSupplier haveTime);
        public boolean haveTime();
        // ❗ 没有 getServer()、没有 getWorld()
    }
    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase);
    }
    public static class WorldTickEvent extends TickEvent {
        public final Level world;                                  // 这个有 world 字段
        public WorldTickEvent(LogicalSide side, Phase phase, Level world);
        public WorldTickEvent(LogicalSide side, Phase phase, Level world, BooleanSupplier haveTime);
        public boolean haveTime();
    }
    public static class PlayerTickEvent extends TickEvent { public final Player player; }
    public static class RenderTickEvent extends TickEvent { public final float renderTickTime; }
}
```

→ **服务端 tick 里拿 `MinecraftServer` 的正确姿势**：
`ServerLifecycleHooks.getCurrentServer()`（✅ S2：`public static MinecraftServer getCurrentServer()`），
或者改用 `WorldTickEvent`（有 `world` 字段，`world.getServer()`），或自己在 `ServerStartedEvent` 里缓存。

**服务端生命周期**

```java
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@SubscribeEvent
public static void onServerStarted(ServerStartedEvent event) {
    MinecraftServer server = event.getServer();      // ✅ ServerLifecycleEvent#getServer()
    // 可在这里读数据包、初始化天气缓存
    server.getResourceManager();
    server.registryAccess();
}

@SubscribeEvent
public static void onServerStopping(ServerStoppingEvent event) {
    MinecraftServer server = event.getServer();
    // 保存状态（世界还在，可以写）
}
```

✅ S1：`net.minecraftforge.event.server` 下共有 `ServerAboutToStartEvent`、`ServerStartingEvent`、`ServerStartedEvent`、`ServerStoppingEvent`、`ServerStoppedEvent`，全部继承 `ServerLifecycleEvent`，公共方法 `public MinecraftServer getServer()`。

**注册命令**

```java
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;      // ⚠️ 1.18.2 没有 Component.literal()，用 TextComponent
import net.minecraftforge.event.RegisterCommandsEvent;

@SubscribeEvent
public static void onRegisterCommands(RegisterCommandsEvent event) {
    CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
    dispatcher.register(Commands.literal("weathersync")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("reload").executes(ctx -> {
                ClimateDataReloadListener.reload(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(new TextComponent("WeatherSync reloaded"), true);
                return 1;
            }))
            .then(Commands.literal("status").executes(ctx -> {
                ctx.getSource().sendSuccess(new TextComponent("..."), false);
                return 1;
            })));
}
```

✅ S2 `javap net.minecraftforge.event.RegisterCommandsEvent`：
`public CommandDispatcher<CommandSourceStack> getDispatcher();` + `public Commands.CommandSelection getEnvironment();`
（事件在 FORGE 总线；每次 `ReloadableServerResources` 重建都会重新触发 → 命令要设计成可重复注册。）
✅ S4：`Commands.literal(String)`、`Commands.argument(String, ArgumentType<T>)`；
`CommandSourceStack#sendSuccess(Component, boolean)`、`sendFailure(Component)`、`hasPermission(int)`、`getServer()`、`getLevel()`、`getPlayerOrException()`。
✅ S4 + S2 交叉确认：1.18.2 的 `net.minecraft.network.chat.Component` **没有** `literal(String)` 静态工厂（那是 1.19+），用 `new TextComponent(String)`。

**数据包资源重载监听（1.18.2 必须用 `PreparableReloadListener`）**

```java
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@SubscribeEvent
public static void onAddReloadListener(AddReloadListenerEvent event) {
    event.addListener(ClimateDataReloadListener.INSTANCE);
    // 还能拿到：event.getServerResources()、event.getConditionContext()
}
```

✅ S2 `javap net.minecraftforge.event.AddReloadListenerEvent`：

```java
public void addListener(net.minecraft.server.packs.resources.PreparableReloadListener listener);
public java.util.List<PreparableReloadListener> getListeners();
public net.minecraft.server.ReloadableServerResources getServerResources();
public net.minecraftforge.common.crafting.conditions.ICondition.IContext getConditionContext();
```

- ✅ **1.18.2 的接口是 `PreparableReloadListener`**（不是 `ResourceManagerReloadListener`）。
- ◐ `ResourceManagerReloadListener` 仍然**存在**、`onResourceManagerReload(ResourceManager)` 也还能用，但 javadoc 页面标了 **`@Deprecated`**（S4），不建议新代码使用。
- ✅ `SimplePreparableReloadListener<T>` 的抽象方法：`protected abstract T prepare(ResourceManager, ProfilerFiller)`、`protected abstract void apply(T, ResourceManager, ProfilerFiller)`。

**玩家登录 / 实体加入世界**

```java
// ✅ 1.18.2：类名是 EntityJoinWorldEvent（不是 EntityJoinLevelEvent）
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

@SubscribeEvent
public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
    if (event.getPlayer() instanceof ServerPlayer sp) {
        WeatherNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), WeatherStatePacket.current());
    }
}

@SubscribeEvent
public static void onEntityJoin(EntityJoinWorldEvent event) {
    Level level = event.getWorld();               // ✅ getWorld()，不是 getLevel()
    boolean fromDisk = event.loadedFromDisk();    // ✅ 1.18.2 有这个方法
}
```

✅ S1：`EntityJoinWorldEvent(Entity, Level)` / `(Entity, Level, boolean loadedFromDisk)`，`getWorld()`、`loadedFromDisk()`，实体用继承来的 `getEntity()`。
✅ S2：`net.minecraftforge.event.entity.EntityJoinLevelEvent` **在 40.3.12 中不存在**（1.19+ 的名字）。
✅ S1：`PlayerEvent.PlayerLoggedInEvent` / `PlayerLoggedOutEvent`，父类 `PlayerEvent#getPlayer()` 返回 `Player`（服务端是 `ServerPlayer`）。这两个**不可取消**（不是 `@Cancelable`）。

### 来源与置信度

- ✅ `TickEvent` 全部分支与「没有 getServer()」：S2 `javap`（40.3.12）+ S1 `TickEvent.java`。
- ✅ `ServerLifecycleHooks.getCurrentServer()` 与包名 `net.minecraftforge.server`：S1 `src/main/java/net/minecraftforge/server/ServerLifecycleHooks.java:64,134` + S2。
- ✅ `ServerLifecycleEvent#getServer()`：S1 + S2。
- ✅ `RegisterCommandsEvent`：S1 + S2。✅ `Commands.literal/argument`、`CommandSourceStack#sendSuccess`：S4。
- ✅ `AddReloadListenerEvent#addListener(PreparableReloadListener)`：S1 + S2。
- ◐ `ResourceManagerReloadListener` 已 `@Deprecated`（但可用）：S4 javadoc 页。
- ✅ `EntityJoinWorldEvent` 存在 / `EntityJoinLevelEvent` 不存在：S2 字节码 + S1。
- ✅ 1.18.2 无 `Component.literal`：S4 javadoc（`Component` 只有 `nullToEmpty` 等静态方法）+ `TextComponent(String)` 存在。

---

## 5. 天气相关（ServerLevel / Level）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x，原版 API）

```java
ServerLevel level = server.overworld();

// 立刻下雨 6000 tick（第 1 个参数 = 强制晴天剩余 tick，第 2 个 = 降雨/雷暴剩余 tick）
level.setWeatherParameters(0, 6000, true, false);

// 立刻放晴，并保持 6000 tick 不下雨
level.setWeatherParameters(6000, 0, false, false);

// 雷暴
level.setWeatherParameters(0, 6000, true, true);

// 查询
boolean raining    = level.isRaining();
boolean thundering = level.isThundering();
float rainLevel    = level.getRainLevel(0.0F);      // 需要 partialTick；0.0F 在服务端 tick 里够用
float thunderLevel = level.getThunderLevel(0.0F);

long gameTime = level.getGameTime();   // long：世界总 tick（永不重置）
long dayTime  = level.getDayTime();    // long：当天时间（0..24000 循环，可被 /time set 改）
long seed     = level.getSeed();       // long：世界种子
```

**精确签名与返回值**（✅ S4 javadoc + S5 官方映射交叉确认）：

```java
// net.minecraft.server.level.ServerLevel
public void setWeatherParameters(int, int, boolean, boolean);
public long getSeed();
public MinecraftServer getServer();
public RegistryAccess registryAccess();

// net.minecraft.world.level.Level（ServerLevel/ClientLevel 继承）
public boolean isRaining();
public boolean isRainingAt(BlockPos pos);
public boolean isThundering();
public float getRainLevel(float partialTicks);
public float getThunderLevel(float partialTicks);
public long getGameTime();       // 返回 long  ✅
public long getDayTime();        // 返回 long  ✅
public LevelData getLevelData();
```

**参数含义**：`setWeatherParameters(clearTime, rainTime, raining, thundering)`，即
**第 1 个 = 「晴天」持续 tick**，**第 2 个 = 「降雨」持续 tick**。
◐ 置信度说明：官方 javadoc/Mojang 官方映射**不给参数名**（映射文件里是 `p_8607_` 这种），
参数名 `clearDuration` / `rainDuration` 来自 <https://mappings.dev/1.18.2/net/minecraft/server/level/ServerLevel.html>
的 Yarn/社区映射列；同时这与原版 `/weather clear <t>` → `setWeatherParameters(t, 0, false, false)`、
`/weather rain <t>` → `setWeatherParameters(0, t, true, false)` 的语义一致（❓ 原版 `WeatherCommand` 源码未逐字核对）。
**实践建议**：想「让雨下 N tick」用 `(0, N, true, false)`；想「放晴 N tick」用 `(N, 0, false, false)`，这是社区通用且不会踩坑的写法。

**另一个可用的入口**（◐）：`net.minecraft.world.level.Level` 上还有

```java
public void setRainLevel(float rainLevel);      // ✅ S4：Level 方法详情 public void setRainLevel(float)
public void setThunderLevel(float thunderLevel); // ✅ S4：Level 方法详情 public void setThunderLevel(float)
protected void prepareWeather();                 // ✅ S4：protected
```

它们直接改渲染用的雨/雷**强度**（0.0~1.0），不改变 `isRaining()` 的权威状态；
⚠️ 服务端权威天气请用 `setWeatherParameters(...)`；`setRainLevel` 更适合客户端视觉/预测。
❓ 这些方法在逻辑服务端 vs 逻辑客户端上的确切副作用（以及是否被每 tick 的天气推进覆盖）**未逐字核对**，用前先在开发环境验证。

### 来源与置信度

- ✅ `setWeatherParameters(int,int,boolean,boolean)` 存在与返回类型 `void`：S5 官方映射 `253:258:void setWeatherParameters(int,int,boolean,boolean) -> a` + S4。
- ✅ `isRaining/isThundering/getRainLevel(float)/getThunderLevel(float)`：S4 `Level` javadoc（`public boolean isRaining()` 等）。
- ✅ `getGameTime`/`getDayTime` 返回 `long`、`getSeed` 返回 `long`：S4 方法详情（`public long getGameTime()`）。
- ◐ 参数含义（clearTime / rainTime）：S5 mappings.dev 映射列推断（Mojang 官方映射不含参数名）。
- ✅ `Level#setRainLevel(float)` / `setThunderLevel(float)` 是 `public`、`prepareWeather()` 是 `protected`：S4 `Level` javadoc 方法详情（逐字）。
- ❓ 这三个方法在服务端/客户端上的运行时副作用未被官方文档说明，未逐字核对。

---

## 6. 生物群系（1.18.2 的 Holder 体系）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x，原版 API）

```java
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

// 1) 按坐标取生物群系：返回的是 Holder<Biome>，不是 Biome ✅
Holder<Biome> holder = level.getBiome(blockPos);       // LevelReader#getBiome(BlockPos) -> Holder<Biome>
Biome biome = holder.value();                          // 需要真正的 Biome 时调 .value()

// 2) 取生物群系 id（两种都行）
ResourceLocation idA = holder.unwrapKey()                      // Optional<ResourceKey<Biome>>
        .map(ResourceKey::location)
        .orElse(null);

Registry<Biome> biomeRegistry = level.registryAccess()          // RegistryAccess
        .registryOrThrow(Registry.BIOME_REGISTRY);              // ResourceKey<Registry<Biome>>
ResourceLocation idB = biomeRegistry.getKey(biome);             // 可能为 null

// 3) 温度 / 降水 / 类别
float baseTemp   = biome.getBaseTemperature();                  // ✅ public final float
float downfall   = biome.getDownfall();                         // ✅ public final float
Biome.Precipitation   precip = biome.getPrecipitation();        // ✅ public
Biome.BiomeCategory    cat   = biome.getBiomeCategory();        // ✅ public
// 便捷静态方法：Biome.getBiomeCategory(Holder<Biome>) ✅ public static

// 4) 该生物群系所属标签
Set<ResourceLocation> tagIds = holder.tags()                    // ✅ Stream<TagKey<Biome>>（Forge 让 Holder 实现 IReverseTag）
        .map(TagKey::location)                                  // TagKey#location() -> ResourceLocation
        .collect(Collectors.toSet());
// TagKey 是 record：TagKey#registry() 返回 ResourceKey<? extends Registry<T>>
// 构造：TagKey.create(ResourceKey<? extends Registry<T>>, ResourceLocation)
// 判断：holder.containsTag(TagKey) / holder.getTagKeys()（Forge IReverseTag 接口方法）
```

**枚举取值**（✅ S4 javadoc）：

```java
public static enum Biome.Precipitation { NONE, RAIN, SNOW }                     // 只有 3 个
public static enum Biome.BiomeCategory  {
    BEACH, DESERT, EXTREME_HILLS, FOREST, ICY, JUNGLE, MESA, MOUNTAIN, MUSHROOM,
    NETHER, NONE, OCEAN, PLAINS, RIVER, SAVANNA, SWAMP, TAIGA, THEEND            // 共 18 个
}
```

**⚠️ 温度相关的大坑：高度修正温度在 1.18.2 拿不到**

```java
public final float getBaseTemperature();                    // ✅ public final（基础温度，不含高度修正）
public final float getDownfall();                           // ✅ public final
private float getTemperature(BlockPos pos);                 // ❌ private！
private float getHeightAdjustedTemperature(BlockPos pos);   // ❌ private！
// 另有 nested enum Biome.TemperatureModifier（FROZEN / NONE）用于 climateSettings
```

- ✅ S4 方法详情逐字显示这两个方法是 `private`；S5 官方映射只证明方法存在（`float getTemperature(BlockPos)`），**存在 ≠ 可访问**。
- Forge 1.18.x **没有**提供公开的高度修正温度访问器（已在整个 Forge 源码 + `patches/` 中检索 `getTemperature(BlockPos`、`getHeightAdjustedTemperature`、`getBiomeTemperature`，**零命中**）。
- 想拿「含高度修正的温度」只能：① 自己实现等同算法（用 `getBaseTemperature()` + 高度 noise）；② 写 AccessTransformer/Mixin 打开私有方法（❓ 具体实现未核对）。**不要**以为 `biome.getTemperature(pos)` 能编译。

### 来源与置信度

- ✅ `LevelReader#getBiome(BlockPos)` 返回 `Holder<Biome>`：S4 javadoc（`default Holder<Biome> getBiome(BlockPos)`）+ S5 官方映射（`net.minecraft.core.Holder getBiome(net.minecraft.core.BlockPos)`）。
- ✅ `Holder#tags()` 返回 `Stream<TagKey<T>>`、`unwrapKey()` 返回 `Optional<ResourceKey<T>>`：S4 `Holder` javadoc；
  ✅ Forge 让 `Holder extends net.minecraftforge.registries.tags.IReverseTag`（`containsTag` / `getTagKeys`）：S6 `patches/minecraft/net/minecraft/core/Holder.java.patch`（逐字）。
- ✅ `Registry.BIOME_REGISTRY` 是 `public static final ResourceKey<Registry<Biome>>`、`Registry#getKey(T)` 返回 `ResourceLocation`、`RegistryAccess#registryOrThrow` 返回 `Registry<E>`：S4。
- ✅ `Biome#getBaseTemperature()` / `getDownfall()` 是 `public final float`；`getPrecipitation()` / `getBiomeCategory()` 是 public；`getTemperature` / `getHeightAdjustedTemperature` 是 private：S4 方法详情。
- ✅ `Biome.Precipitation` 3 值、`Biome.BiomeCategory` 18 值：S4 枚举页。
- ◐ 「Forge 未提供公开高度修正温度」：对 S1 全仓库（源码 + patches）检索无命中（阴性证据）。
- ✅ `TagKey` 是 record，`location()` / `registry()` / `TagKey.create(...)`：S4。

---

## 7. 数据包资源（读自己的 data/weathersync/**.json）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x，原版 API）

**推荐写法：继承 `SimpleJsonResourceReloadListener`（Forge 帮你遍历目录 + 解析 JSON）**

```java
package com.weathersync.data;

import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 读取 data/<任意命名空间>/climate/*.json
 * 例：data/weathersync/climate/desert.json  →  键为 weathersync:desert
 */
public final class ClimateDataReloadListener extends SimpleJsonResourceReloadListener {
    private static final String DIR = "climate";
    public static final ClimateDataReloadListener INSTANCE = new ClimateDataReloadListener();

    private ClimateDataReloadListener() {
        super(new Gson(), DIR);                       // ✅ 构造器 (Gson, String directory)
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        // objects 的 key 已经去掉目录前缀与 .json；value 是解析好的 JsonElement
        // 这里可以整体替换内存里的气候表（原子替换，避免半成品状态）
    }
}
```

✅ S4：`public abstract class SimpleJsonResourceReloadListener extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>>`，
构造器 `public SimpleJsonResourceReloadListener(Gson, String)`，并额外提供 Forge 补丁的 `protected ResourceLocation getPreparedPath(ResourceLocation rl)`。
✅ S6（Forge 补丁逐字）：`getPreparedPath` 的实现是
`new ResourceLocation(rl.getNamespace(), this.directory + "/" + rl.getPath() + ".json")`
→ **说明监听器拿到的 key 里不含目录前缀，实际文件路径才含目录**。

**按需读取单个文件（比如某个维度临时查表）**

```java
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.server.ServerLifecycleHooks;

MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
ResourceManager rm = server.getResourceManager();                       // ✅ 返回 ResourceManager
ResourceLocation loc = new ResourceLocation("weathersync", "climate/desert.json");

if (rm.hasResource(loc)) {                                             // ✅ 先判存在，最稳
    try (Resource resource = rm.getResource(loc)) {                     // ⚠️ 接口声明 throws IOException；可能返回 null
        if (resource != null) {
            try (InputStream in = resource.getInputStream()) {          // ⚠️ 1.18.2 是 getInputStream()，不是 open()
                ClimateTable table = GsonHelper.fromJson(new Gson(),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        ClimateTable.class);
            }
        }
    } catch (java.io.IOException e) {
        // 记日志，别让 tick 崩
    }
}
```

**1.18.2 的精确 API（关键差异，务必按此写）**

```java
// net.minecraft.server.packs.resources.ResourceProvider（ResourceManager 的父接口）
Resource getResource(ResourceLocation location) throws java.io.IOException;   // ✅ 返回 Resource，不是 Optional！
boolean hasResource(ResourceLocation location);

// net.minecraft.server.packs.resources.ResourceManager
java.util.Set<String> getNamespaces();
List<Resource> getResources(ResourceLocation location);                       // ✅ List，不是 Map
Collection<ResourceLocation> listResources(String path, java.util.function.Predicate<String> filter);  // ⚠️ filter 收 String

// net.minecraft.server.packs.resources.Resource
InputStream getInputStream();                                                 // ✅ Resource extends Closeable
boolean hasMetadata();
<T> T getMetadata(MetadataSectionSerializer<T> serializer);

// net.minecraft.server.MinecraftServer
public ResourceManager getResourceManager();                                  // ✅
public PackRepository getPackRepository();                                    // ⚠️ 不是 getResourcePackRepository()
public RegistryAccess.Frozen registryAccess();
public Commands getCommands();
```

> ⚠️ 三处与「网上常见的 1.19/1.20 写法」不同，务必按 1.18.2 写：
> 1. `getResource` 返回 **`Resource`（可为 null）+ 接口上 `throws IOException`**，**不是 `Optional<Resource>`**（1.19 才改成 Optional）；
> 2. `Resource#open()` **不存在**，1.18.2 是 **`getInputStream()`**；
> 3. `listResources(String, Predicate<String>)` 返回 **`Collection<ResourceLocation>`**，过滤器收的是**文件名字符串**，不是 `ResourceLocation`（1.19+ 才变成 `Map<ResourceLocation, Resource>`）。
> 安全写法：`hasResource(loc)` → `getResource(loc)` → 判空 → try-with-resources。

**让玩家数据包覆盖内置文件（优先级规则）** ✅ S3 <https://docs.minecraftforge.net/en/1.18.x/concepts/resources/> 原文：

> “When multiple resource packs or data packs are enabled, they are merged. **Generally, files from packs at the top of the stack override those below** … Mods define resource and data packs in their `resources` directories, but they are seen as subsets of the “Mod Resources” pack. **Mod resource packs cannot be disabled, but they can be overridden by other resource packs.** **Mod datapacks can be disabled with the vanilla `/datapack` command.**”

实践要点：

- 你的内置文件放 `src/main/resources/data/weathersync/climate/xxx.json`；玩家在数据包里放**同路径同名**文件即可覆盖（数据包在高优先级 → 覆盖模组资源）。
- `ResourceManager#getResource(loc)` 返回的是**最终生效的那一个**；想拿到所有候选（多包叠加/调试）用 `getResources(loc)`（返回 `List<Resource>`，◐ 顺序应为高优先级在前，未在官方文档中明说）。
- 想「按目录发现所有玩家新增文件」→ 用 `listResources("climate", name -> name.endsWith(".json"))`（会跨命名空间列出，如 `weathersync:climate/desert.json`、`mypack:climate/volcano.json`）；◐ 返回的 `ResourceLocation#getPath()` **包含目录前缀**（依据 S6 `getPreparedPath` 逐字逻辑推断：实际路径 = `namespace:climate/xxx.json`）。
- `listResources` 的返回路径与命名空间：不同命名空间的文件各自成项，需要自己按 `getNamespace()` 过滤（例如只接受 `weathersync` 命名空间 + 白名单其它命名空间）。

### 来源与置信度

- ✅ `MinecraftServer#getResourceManager()` / `getPackRepository()`：S4 `MinecraftServer` javadoc。
- ✅ `ResourceProvider#getResource(ResourceLocation) throws IOException` 返回 `Resource`、`hasResource`：S4 `ResourceProvider` javadoc（ResourceManager 的父接口）。
- ✅ `ResourceManager#getResources` → `List<Resource>`、`listResources(String, Predicate<String>)` → `Collection<ResourceLocation>`：S4 `ResourceManager` javadoc。
- ✅ `Resource#getInputStream()` 且 `Resource extends Closeable`（无 `open()`）：S4 `Resource` javadoc。
- ✅ `GsonHelper.fromJson(Gson, String, Class<T>)` 与 `(Gson, Reader, Class<T>)` 存在：S4 `GsonHelper` javadoc。
- ✅ `SimpleJsonResourceReloadListener(Gson, String)` 与 `getPreparedPath`：S4 + S6。
- ✅ 数据包/资源包覆盖优先级与「模组数据包可被 /datapack 关闭」：S3 原文。
- ◐ `getResources` 的顺序、`listResources` 返回路径含目录前缀：由 S6 补丁逻辑推断。
- ❓ 未核对：`MultiPackResourceManager#getResource` 在文件缺失时是返回 `null` 还是抛 `FileNotFoundException`（两种说法都能在 Forge 自己的代码里找到用例：`B3DLoader` 捕 `FileNotFoundException`，`OBJLoader` 直接 try-with-resources）。**因此上文的 `hasResource` + 判空 + catch IOException 三保险写法是必须的。**

---

## 8. 客户端渲染（HUD 叠加层）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x）

**1.18.2 推荐做法：`IIngameOverlay` + `OverlayRegistry`（在客户端 setup 时注册）**

```java
package com.weathersync.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.ForgeIngameGui;
import net.minecraftforge.client.gui.IIngameOverlay;
import net.minecraftforge.client.gui.OverlayRegistry;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = "weathersync",
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientHudSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> OverlayRegistry.registerOverlayAbove(
                ForgeIngameGui.EXPERIENCE_BAR_ELEMENT,   // 排在某原版元素之上
                "WeatherSync HUD",
                WeatherHudOverlay.INSTANCE));
    }
}

final class WeatherHudOverlay implements IIngameOverlay {
    static final WeatherHudOverlay INSTANCE = new WeatherHudOverlay();

    @Override
    public void render(ForgeIngameGui gui, PoseStack poseStack,
                       float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;  // ✅ hideGui 是 public 字段
        Font font = mc.font;                                                      // ✅ Minecraft#font 是 public final Font
        poseStack.pushPose();
        font.drawShadow(poseStack, "WeatherSync: rain", 4.0F, 4.0F, 0xFFFFFF);    // ✅ 1.18.2 是 float 坐标
        poseStack.popPose();
    }
}
```

**轻量替代做法：监听 `RenderGameOverlayEvent.Post`（`ElementType.ALL`）**

```java
@Mod.EventBusSubscriber(modid = "weathersync",
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientOverlayEvents {
    @SubscribeEvent
    public static void onRenderOverlay(net.minecraftforge.client.event.RenderGameOverlayEvent.Post event) {
        if (event.getType() != net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL) return;
        if (!(event instanceof net.minecraftforge.client.event.RenderGameOverlayEvent.Post)) return;
        // ⚠️ Post 不可取消（isCancelable() == false），这里只能画东西
        PoseStack ps = event.getMatrixStack();
        Minecraft mc = Minecraft.getInstance();
        mc.font.drawShadow(ps, "WeatherSync", 4.0F, 4.0F, 0xFFFFFF);
    }
}
```

**1.18.2 的真实 API 清单（✅ 全部经字节码核对）**

```java
// net.minecraftforge.client.event.RenderGameOverlayEvent —— 存在，类上有 @Cancelable
public PoseStack getMatrixStack();
public float getPartialTicks();
public net.minecraft.client.gui.screens.Window getWindow();  // 实为 com.mojang.blaze3d.platform.Window
public ElementType getType();

public static enum ElementType {
    ALL, LAYER, BOSSINFO, TEXT, CHAT, PLAYER_LIST, DEBUG       // ⚠️ 只有这 7 个！
}

public static class Pre  extends RenderGameOverlayEvent { /* 可取消 */ }
public static class Post extends RenderGameOverlayEvent {
    @Override public boolean isCancelable() { return false; }  // ⚠️ Pre 可取消，Post 不能
}
public static class PreLayer / PostLayer extends Pre / Post { public IIngameOverlay getOverlay(); }
public static class Text / Chat / BossInfo extends Pre { ... }  // TEXT/CHAT/BOSSINFO 的专用子类
```

```java
// net.minecraftforge.client.gui.OverlayRegistry（✅ 1.18.2 存在）
public static synchronized IIngameOverlay registerOverlayBottom(String displayName, IIngameOverlay overlay);
public static synchronized IIngameOverlay registerOverlayBelow(IIngameOverlay other, String displayName, IIngameOverlay overlay);
public static synchronized IIngameOverlay registerOverlayAbove(IIngameOverlay other, String displayName, IIngameOverlay overlay);
public static synchronized IIngameOverlay registerOverlayTop(String displayName, IIngameOverlay overlay);
public static synchronized void enableOverlay(IIngameOverlay overlay, boolean enable);

// net.minecraftforge.client.gui.IIngameOverlay
void render(ForgeIngameGui gui, PoseStack poseStack, float partialTick, int width, int height);

// net.minecraftforge.client.gui.ForgeIngameGui 的公开锚点常量（可用来定位排序）
public static final IIngameOverlay VIGNETTE_ELEMENT, SPYGLASS_ELEMENT, HELMET_ELEMENT, FROSTBITE_ELEMENT,
    PORTAL_ELEMENT, HOTBAR_ELEMENT, CROSSHAIR_ELEMENT, BOSS_HEALTH_ELEMENT, PLAYER_HEALTH_ELEMENT,
    ARMOR_LEVEL_ELEMENT, FOOD_LEVEL_ELEMENT, MOUNT_HEALTH_ELEMENT, AIR_LEVEL_ELEMENT, JUMP_BAR_ELEMENT,
    EXPERIENCE_BAR_ELEMENT, ITEM_NAME_ELEMENT, SLEEP_FADE_ELEMENT, HUD_TEXT_ELEMENT, FPS_GRAPH_ELEMENT,
    POTION_ICONS_ELEMENT, RECORD_OVERLAY_ELEMENT, SUBTITLES_ELEMENT, TITLE_TEXT_ELEMENT, SCOREBOARD_ELEMENT,
    CHAT_PANEL_ELEMENT, PLAYER_LIST_ELEMENT;
```

```java
// com.mojang.blaze3d.vertex.PoseStack（✅ S4）
public void pushPose();  public void popPose();
public void translate(double x, double y, double z);
public void scale(float x, float y, float z);

// net.minecraft.client.gui.Font（✅ S4，注意 1.18.2 是 float 坐标）
public int draw(PoseStack poseStack, String text, float x, float y, int color);
public int drawShadow(PoseStack poseStack, String text, float x, float y, int color);
public int width(String text);
public final int lineHeight;      // 字段

// net.minecraft.client.Minecraft
public static Minecraft getInstance();
public final Font font;
@Nullable public ClientLevel level;
@Nullable public LocalPlayer player;
public Options options;           // Options#hideGui 是 public 字段
```

**⚠️ 三个致命坑**

1. **`ElementType` 里没有 `HEALTH` / `FOOD` / `AIR` / `HOTBAR` / `EXPERIENCE` / `ARMOR` / `CROSSHAIRS` / `PORTAL` / `HELMET` / `POTION_ICONS`**（那是 1.12 时代）。1.18.2 只有 `ALL, LAYER, BOSSINFO, TEXT, CHAT, PLAYER_LIST, DEBUG`；原版元素都变成了 `ForgeIngameGui` 里的 `IIngameOverlay` 常量。
2. **没有 `RenderGuiOverlayEvent`**（1.19+ 才有）；1.18.2 只有 `RenderGameOverlayEvent`，且 `net.minecraftforge.client.gui.VanillaGuiOverlay` 这个枚举**在 40.3.12 中不存在**（字节码确认）——想「排在血条之上」请用 `ForgeIngameGui.PLAYER_HEALTH_ELEMENT` / `HOTBAR_ELEMENT` 这类常量。
3. `RenderGameOverlayEvent.Pre` 可以取消（会跳过后续绘制），`Post` **不能取消**（`isCancelable()` 返回 `false`，对它调 `setCanceled` 会抛 `UnsupportedOperationException`）。

### 来源与置信度

- ✅ `RenderGameOverlayEvent` 的成员、`ElementType` 7 个值、`Post.isCancelable()==false`、`PostLayer/PreLayer`：S2 `javap` + S1 `RenderGameOverlayEvent.java`。
- ✅ `RenderGuiOverlayEvent` / `VanillaGuiOverlay` 在 40.3.12 不存在：S2 字节码（`javap` 返回 ClassNotFound）。
- ✅ `OverlayRegistry` 四个注册方法、`IIngameOverlay#render` 签名、`ForgeIngameGui` 26 个公开 overlay 常量：S2 `javap` + S1。
- ✅ `RenderGameOverlayEvent.Post`（`ElementType.ALL`）确实会被 post：S1 `ForgeIngameGui.java:336/359`（`pre(ALL, ...)` / `post(ALL, ...)`）。
- ✅ `Font#draw/drawShadow(PoseStack, String, float, float, int)`、`PoseStack` 方法：S4 javadoc。
- ✅ `Minecraft.getInstance()`、`public final Font font`、`@Nullable public ClientLevel level` / `LocalPlayer player`：S4 javadoc。
- ✅ `event.enqueueWork` 在 `FMLClientSetupEvent` 上可用（`ParallelDispatchEvent#enqueueWork`）：S2 `javap`。
- ◐ `Options#hideGui` 是 public 字段：S4 `Options` javadoc 中 `hideGui` 存在（未逐字确认修饰符）。

---

## 9. `mods.toml`（1.18.2 / Forge 40.x）

### 结论（可直接抄的代码）

> 适用于 1.18.2（`META-INF/mods.toml`）。字段名与语义逐字来自 MDK 模板 + FML 解析器源码。

```toml
modLoader="javafml"                      # 必填：javafml（普通 @Mod 模组）
loaderVersion="[40,)"                    # 必填：**FML/Forge 的版本范围**（不是 Minecraft 版本！）
license="MIT"                            # 必填（缺失会告警）
issueTrackerURL="https://example.invalid/issues"   # 可选
# clientSideOnly=true                    # 可选（默认 false）：纯客户端模组，Forge 会替你设置 displayTest
# displayTest="MATCH_VERSION"            # 可选：MATCH_VERSION / IGNORE_SERVER_VERSION / IGNORE_ALL_VERSION / NONE

[[mods]]                                 # 必填
modId="weathersync"                      # 必填
version="${file.jarVersion}"             # 必填（这里由 Gradle processResources 展开）
displayName="WeatherSync"                # 必填
description='''动态天气同步'''            # 必填（多行用三引号）
authors="WeatherSync Team"               # 可选
credits=""                               # 可选
logoFile="weathersync.png"               # 可选（jar 根目录下的文件名）
displayURL="https://example.invalid"     # 可选
updateJSONURL="https://example.invalid/updates.json"  # 可选

# —— 依赖：表名 [[dependencies.<本模组的 modId>]]，表内 modId 才是「被依赖的模组」 ——
[[dependencies.weathersync]]
    modId="forge"                        # 必填
    mandatory=true                       # 必填（必须是布尔字面量；缺了直接 InvalidModFileException）
    versionRange="[40,)"                 # 可选（缺省即无上界约束）——Maven 版本范围语法
    ordering="NONE"                      # 可选：NONE / BEFORE / AFTER（缺省 NONE）
    side="BOTH"                          # 可选：BOTH / CLIENT / SERVER（缺省 BOTH）

[[dependencies.weathersync]]
    modId="minecraft"
    mandatory=true
    versionRange="[1.18.2,1.19)"
    ordering="NONE"
    side="BOTH"

# —— 可选依赖（缺失也不能崩）——
[[dependencies.weathersync]]
    modId="sereneseasons"                # 别的天气/季节模组
    mandatory=false                      # ⚠️ 非必填必须写 false
    versionRange="[1.18.2,)"             # 非 mandatory 时按官方模板要求给出 ordering
    ordering="AFTER"                     # 本模组在它之后加载
    side="BOTH"
```

**字段语义（✅ 来自 MDK 模板注释 + FML 解析源码）**

| 键 | 位置 | 必填 | 语义 / 取值 |
| :--- | :--- | :--- | :--- |
| `modLoader` | 顶层 | ✅ | 普通 `@Mod` 模组固定 `"javafml"` |
| `loaderVersion` | 顶层 | ✅ | **Forge/FML 的 Maven 版本范围**（如 `"[40,)"`），不是 MC 版本 |
| `license` | 顶层 | ✅ | 缺失时 `missingLicense()` 为真（仅告警） |
| `issueTrackerURL` | 顶层 | ❌ | URL |
| `clientSideOnly` | 顶层 | ❌ | `true` 时 Forge 注入 `__FORGE_clientSideOnly` 并自动设 displayTest（**注意：这是「纯客户端」声明，跟 `side` 无关**） |
| `showAsResourcePack` | 顶层 | ❌ | 默认 `false` |
| `services` | 顶层 | ❌ | `List<String>`（服务文件） |
| `properties` | 顶层 | ❌ | 任意 map |
| `[[mods]]` | 表 | ✅ | `modId` / `version` / `displayName` / `description` 必填；`authors`/`credits`/`logoFile`/`displayURL`/`updateJSONURL` 可选 |
| `[[dependencies.<本模组 modId>]]` | 表 | ❌ | 依赖列表；**表名的 `<modId>` 必须是你自己的 modId** |
| `modId`（依赖内） | ✅ | 被依赖模组 id（缺 → `InvalidModFileException("Missing required field modid in dependency")`） |
| `mandatory` | ✅ | **必须显式给布尔值**（缺 → `InvalidModFileException("Missing required field mandatory in dependency")`） |
| `versionRange` | ❌ | Maven 版本范围；缺省为 `UNBOUNDED` |
| `ordering` | ❌ | `NONE`（默认）/ `BEFORE` / `AFTER`；非 mandatory 依赖通常要写 `AFTER` |
| `side` | ❌ | `BOTH`（默认）/ `CLIENT` / `SERVER` |

**可选依赖「不缺失也崩」的正确姿势**

```toml
# 1) mods.toml：声明成可选 + 加载顺序
[[dependencies.weathersync]]
    modId="sereneseasons"
    mandatory=false
    ordering="AFTER"
    side="BOTH"
```

```java
// 2) 代码里永远先判存在，再通过反射/可选类访问对方 API（不要直接 import 对方类！）
if (net.minecraftforge.fml.ModList.get().isLoaded("sereneseasons")) {
    // ✅ 只做「不引用对方类」的事；要调对方 API 请用反射或单独的兼容层类
}
```

- ✅ **`ModList.get().isLoaded(String)` 在模组构造期可用**（为什么可用见下）；但如果只是想「加载顺序」，用 `mods.toml` 的 `ordering="AFTER"` 更稳。
- ⚠️ **不要用 `mandatory=true` 声明一个真正可选的依赖**，否则玩家没装对方模组时游戏直接起不来。
- 需要「访问对方对象」时：`ModList.get().getModObjectById("sereneseasons")` → `Optional<T>`（✅ S2）。
- 跨模组通信的官方机制：`InterModComms`（S3 events 页提到），但强类型 API 互调建议反射/独立 compat 类隔离。

### 来源与置信度

- ✅ 全部字段名与注释：S1 `mdk/src/main/resources/META-INF/mods.toml`（逐字，含 `clientSideOnly`、`displayTest`、ordering/side 说明）。
- ✅ 解析与必填校验：S1 `fmlloader/.../moddiscovery/ModFileInfo.java`（`modLoader` / `loaderVersion` / `license` / `clientSideOnly` / `showAsResourcePack` / `services` / `properties` / `issueTrackerURL` / `mods` / `dependencies`）与 `ModInfo.java:175-187`（依赖四项：`modId` 必填、`mandatory` 必填、`versionRange` 默认 `UNBOUNDED`、`ordering` 默认 `NONE`、`side` 默认 `BOTH`）。
- ✅ `mods.toml` 有版本范围语义（Maven `VersionRange`）、`loaderVersion` 是 Forge 版本：S3 <https://docs.minecraftforge.net/en/1.18.x/gettingstarted/versioning/>（未逐字引用，但字段语义与 MDK 注释一致）。
- ❓ 不确定：Mixin 配置。1.18.2 的 `mods.toml` 中**没有** `[[mixins]]` 段（那是更晚版本的写法；我在 Forge 1.18.x 源码里检索 `mixins` 无命中）；1.18.2 通常靠 JAR 清单属性声明 Mixin 配置，但我**没有逐字核对**，WeatherSync 目前也不需要 Mixin。

---

## 10. 模组间兼容（ModList / 事件优先级 / Capability 概述）

### 结论（可直接抄的代码）

> 适用于 Forge 1.18.2（40.x）

**(1) `ModList.get().isLoaded()` 的位置与时机的真相** ✅

```java
// 模组构造期就能用（实例在构造器之前就已建好并填充）
public WeatherSync() {
    if (ModList.get().isLoaded("sereneseasons")) {
        LOGGER.info("SereneSeasons detected");
    }
}
```

依据：`ModList.INSTANCE` 由 `ModList.of(modFiles, sortedList)` 创建（构造器里就建好了 `fileById` 等索引），
而真正的容器表在 `setLoadedMods(List<ModContainer>)` 里填充；`isLoaded(modTarget)` 的实现是
`this.indexedMods.containsKey(modTarget)`（✅ S1 `ModList.java:183-186`）。
模组实例化发生在加载流程更晚的阶段，所以构造期查询是安全的。

- ✅ 可用：`ModList.get()` / `isLoaded` / `getModContainerById` / `getMods` / `getModObjectById`。
- ⚠️ 但**不要**在构造期去「调用对方模组的类」——那时对方可能还没构造完。真正安全的互操作时机是
  `InterModEnqueueEvent` / `InterModProcessEvent`（官方四条常见的 mod 总线生命周期事件之一，S3），
  或 `FMLCommonSetupEvent`（双方都构造完了）。

**(2) 避免与其它天气模组冲突（推荐机制）**

```java
// 优先级 + 是否接收已取消的事件，都写在注解上（✅ 见 §1 的 javap 签名）
@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
public static void onServerTickLate(TickEvent.ServerTickEvent event) {
    // LOWEST = 最后执行
    // receiveCanceled = true → 即使别的模组取消了该事件，我仍然收到（可以「观测」它们的决定）
    if (event.phase != TickEvent.Phase.END) return;
    // ... 以本模组的权威状态覆盖天气
}
```

```java
// 或者是代码注册（构造期）
FORGE_BUS.addListener(EventPriority.LOWEST, true, (TickEvent.ServerTickEvent e) -> { /* ... */ });
```

- `EventPriority`：`HIGHEST, HIGH, NORMAL, LOW, LOWEST`；**HIGHEST 最先执行，LOWEST 最后**（✅ S2；顺序说明 S3 events 页原文）。
- `receiveCanceled = true`：可取消事件被取消后仍会调用你的处理器（✅ S2 `SubscribeEvent#receiveCanceled()`）。
- **注意：Forge 1.18.2 没有「天气事件」**。天气是 `ServerLevel` 上的状态（见 §5），其它模组也是直接调 `setWeatherParameters`。
  因此「不冲突」靠的是**约定与执行顺序**，而不是事件取消：
  1. 只在 `TickEvent.ServerTickEvent` 的 `Phase.END` 且用 `EventPriority.LOWEST` 写最终状态；
  2. 与本模组状态比较后再写（`if (level.isRaining() != desired) level.setWeatherParameters(...)`），避免每 tick 抖动；
  3. 给用户一个 `COMMON`/`SERVER` 配置开关（如「让其它天气模组优先」），胜过强行抢占；
  4. 检测到已知天气/季节模组时降级（`ModList.get().isLoaded(...)`）。
- ◐ 是否存在其它模组（如 SereneSeasons）暴露的公开 API 可对接：**未核对**，需要时单独查对方文档。

**(3) Capability / 扩展点机制概述（✅ S3 官方 capabilities 页）**

```java
// 查询现有 capability
static Capability<IItemHandler> ITEM_HANDLER_CAPABILITY = CapabilityManager.get(new CapabilityToken<>(){});
// provider 侧
LazyOptional<IItemHandler> lazy = LazyOptional.of(() -> handler);
@Override public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
    if (cap == ITEM_HANDLER_CAPABILITY) return lazy.cast();
    return super.getCapability(cap, side);       // ⚠️ 必须回退到 super，否则会破坏已附加的 capability
}
// 注册自定义 capability（mod 总线）
@SubscribeEvent
public static void registerCaps(RegisterCapabilitiesEvent event) { event.register(IExampleCapability.class); }
// 附加到已有对象（Entity / BlockEntity / ItemStack / Level / LevelChunk 五类泛型）
@SubscribeEvent
public static void attach(AttachCapabilitiesEvent<Entity> event) { event.addCapability(id, provider); }
```

要点（S3 原文）：

- Forge 自带的三个 capability：`IItemHandler`、`IFluidHandler`、`IEnergyStorage`。
- `CapabilityManager.get(new CapabilityToken<>(){})` 拿到的引用**非 null**，但不代表已注册 → 用 `Capability#isRegistered` 判断。
- `getCapability` 返回 `LazyOptional<T>`；不可用时是空 `LazyOptional`（用 `.orElse(...)` / `.ifPresent(...)`，不要 `.get()`）。
- `LazyOptional` 必须在对象生命周期结束时 `invalidate()`（BlockEntity/Entity 在 `invalidateCaps()`；非自有对象用 `AttachCapabilitiesEvent#addListener` 回传的 Runnable）。
- Capability 数据**默认不同步到客户端**，需要你自己发包（正好可以用 §3 的 SimpleChannel）。
- 死亡时默认不保留，需要监听 `PlayerEvent.Clone`（`#isWasDeath`）手动复制。
- Forge 还有一种轻量扩展机制：`IExtensionPoint`（`ModLoadingContext#registerExtensionPoint`，`ConfigGuiFactory` 和 `DisplayTest` 都是它的实现）——本模组就用它注册配置界面。

**纯客户端/纯服务端模组：`DisplayTest`**（✅ S1/S2）

```java
// 服务端专用模组：让客户端不要因为缺少本模组而显示红叉
ModLoadingContext.get().registerExtensionPoint(
    net.minecraftforge.fml.IExtensionPoint.DisplayTest.class,
    () -> new net.minecraftforge.fml.IExtensionPoint.DisplayTest(
            () -> net.minecraftforge.network.NetworkConstants.IGNORESERVERONLY,   // ✅ 常量存在
            (remoteVersion, isFromServer) -> true));
// 也有现成 Supplier：IExtensionPoint.DisplayTest.IGNORE_SERVER_VERSION / IGNORE_ALL_VERSION
```

### 来源与置信度

- ✅ `ModList#isLoaded` 实现与填充时机：S1 `ModList.java:156-186` + S2 `javap`。
- ✅ `EventPriority` 次序、`receiveCanceled`：S2 `javap`（`EventPriority` 枚举 + `SubscribeEvent`）；次序说明 S3 events 页。
- ✅ 「Forge 1.18.2 无天气事件」：对 S1 全仓库事件类检索（`net.minecraftforge.event.**`）无天气相关事件；S3 事件文档目录也未列出。
- ✅ Capability 全节：S3 <https://docs.minecraftforge.net/en/1.18.x/datastorage/capabilities/> 原文。
- ✅ `IExtensionPoint.DisplayTest` record 与 `NetworkConstants.IGNORESERVERONLY`：S1 `IExtensionPoint.java:47`、`NetworkConstants.java:48` + S2。
- ◐ `InterModEnqueueEvent`/`InterModProcessEvent` 作为跨模组互操作时机：S3 events 页。
- ❓ 不确定：具体第三方天气模组的兼容 API（未核对）。

---

## 11. 最容易踩的坑（会导致加载失败或运行时不工作）

> 以下每一条都是本次核对中**发现与常见过时写法不同**的地方。

### A. 会导致**模组加载直接失败**的

1. **给 `@Mod` 类写带参构造器。** 1.18.2 的 `FMLModContainer` 只调 `modClass.getDeclaredConstructor().newInstance()`（`FMLModContainer.java:67`）。
   `IEventBus` / `ModContainer` / `Dist` 构造器注入是 1.20.2+/NeoForge 的东西。→ 用 `FMLJavaModLoadingContext.get().getModEventBus()`。
2. **`mods.toml` 的依赖少写 `mandatory`。** 解析期直接 `InvalidModFileException`：`Missing required field mandatory in dependency`；缺 `modid` 同理。
3. **`loaderVersion` 写成 Minecraft 版本。** 它要的是 **Forge/FML 版本范围**，例如 `"[40,)"`。
4. **依赖表名写错。** 是 `[[dependencies.<你自己的 modId>]]`；写成 `[[dependencies.forge]]` 会被当成「声明 forge 模组的依赖表」，你的依赖全部不生效。
5. **在专用服务端加载了客户端类。** 例如在公共类里 `static final Font F = Minecraft.getInstance().font;` 或在构造期直接 `new MyConfigScreen()`。
   → 用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientInit::init)` 或 `FMLEnvironment.dist == Dist.CLIENT` 隔离。
6. **`@Mod.EventBusSubscriber` 的 `value` 写成 `Dist.CLIENT` 却把服务端事件处理也放进去**：专用服务器上整个类不会被注册。
7. **`safeRunWhenOn(Dist.CLIENT, () -> () -> Foo.bar())` 编译不过**：`SafeRunnable` 不是 `Runnable` 的别名，必须 `() -> ClientInit::init` 这种方法引用形式；
   而且 S3 明确警告：Java 9+ 下 `safe*` 在开发环境会把原异常包成 `BootstrapMethodError`（建议 `unsafe*` 或 `FMLEnvironment.dist`）。

### B. 会导致**运行时不工作 / 静默失效**的

8. **配置界面：以为 Forge 会自动生成。** 1.18.2 **不会**；没注册 `ConfigGuiFactory` 时模组列表里的 Config 按钮是**灰的**
   （`ModListScreen.java:400`：`configButton.active = ConfigGuiHandler.getGuiFactoryFor(selectedMod).isPresent()`）。
   也**没有** `ConfigGuiHandler.registerModConfigScreenFactory(...)`、没有 `ConfigScreenHandler` / `ConfigurationScreen`（字节码确认不存在）。
9. **`ModConfigEvent` 包名/总线错。** 正确是 `net.minecraftforge.fml.event.config.ModConfigEvent`（`.Loading` / `.Reloading`），
   且它是 `IModBusEvent` → 必须注册到 **mod 总线**（`Bus.MOD`）。`net.minecraftforge.fml.config.ModConfigEvent` 不存在；`ModConfig.Loading` 是 1.16 的写法。
10. **在 `ServerTickEvent` 上调 `event.getServer()`。** 这个方法**不存在**（只有 `haveTime()`）。
    → `ServerLifecycleHooks.getCurrentServer()`（包 `net.minecraftforge.server`，**不是** `net.minecraftforge.event.server`；返回值可能为 null），或改用 `WorldTickEvent.world.getServer()`。
    另外 `phase` 是**公开字段**（`event.phase`），没有 `getPhase()`。
11. **`SimpleChannel` 的包名。** 是 `net.minecraftforge.network.simple.SimpleChannel`（中间有 `.simple`），
    写成 `net.minecraftforge.network.SimpleChannel` 编译/运行都会找不到类。
12. **忘了 `ctx.get().setPacketHandled(true)`**，或直接在处理器里操作游戏对象（处理器在**网络线程**）→ 必须 `enqueueWork(...)`。
13. **`ResourceManager` 的读取 API 用了 1.19 写法。** 1.18.2 里：
    `getResource` 返回 **`Resource`（可为 null 且接口声明 `throws IOException`）**，**不是 `Optional<Resource>`**；
    `Resource#open()` **不存在**，要用 **`getInputStream()`**；
    `listResources(String, Predicate<String>)` 返回 `Collection<ResourceLocation>`（过滤器收 **String**），不是 `Map`。
14. **把重载监听器写成 `ResourceManagerReloadListener`。** 1.18.2 的 `AddReloadListenerEvent#addListener` 收的是 **`PreparableReloadListener`**；
    `ResourceManagerReloadListener` 已被标 `@Deprecated`（还能用，但新代码别写）。
15. **`EntityJoinLevelEvent` 不存在。** 1.18.2 是 **`EntityJoinWorldEvent`**，取世界用 **`getWorld()`**（不是 `getLevel()`）。
16. **`Level#getBiome(pos)` 当成返回 `Biome`。** 它返回 **`Holder<Biome>`**；要 `Biome` 得 `.value()`，要 id 用 `.unwrapKey().map(ResourceKey::location)` 或 `registryAccess().registryOrThrow(Registry.BIOME_REGISTRY).getKey(biome)`。
17. **用 `biome.getTemperature(pos)` 算高度修正温度。** 1.18.2 里 `getTemperature(BlockPos)` 与 `getHeightAdjustedTemperature(BlockPos)` 都是 **private**，Forge 也没补公开访问器。可用的只有 `getBaseTemperature()`（和 `getDownfall()`）。
18. **HUD 用了 1.12 的 `ElementType`。** 1.18.2 只有 `ALL, LAYER, BOSSINFO, TEXT, CHAT, PLAYER_LIST, DEBUG`；
    没有 `RenderGuiOverlayEvent`、没有 `VanillaGuiOverlay`。想在血条上方画 → `OverlayRegistry.registerOverlayAbove(ForgeIngameGui.PLAYER_HEALTH_ELEMENT, ...)`。
19. **试图取消 `RenderGameOverlayEvent.Post`。** `Post.isCancelable()` 返回 `false`，调 `setCanceled(true)` 会抛 `UnsupportedOperationException`（只有 `Pre` 可取消）。
20. **`Component.literal("x")`。** 1.18.2 的 `Component` **没有** `literal` 静态方法（1.19+ 才有静态工厂）→ 用 `new TextComponent("x")` / `new TranslatableComponent(key, args)`。
21. **`MinecraftServer#getResourcePackRepository()` / `ServerResourceManager`。** 1.18.2 是 **`getPackRepository()`** 和 **`ReloadableServerResources`**。

### C. 容易误判的语义

22. **`Dist` ≠ 逻辑端。** 单机存档里逻辑服务端也跑在 `Dist.CLIENT`；判断游戏逻辑该不该跑请用 `Level#isClientSide`（S3 原文）。
23. **`ModLoadingContext` 是 `ThreadLocal`。** 注册配置/扩展点必须在「模组加载线程 + 当前 mod 的加载阶段」内（构造期，或 mod 总线的生命周期事件里）；在 tick 里调用拿到的是错误的（甚至空的）活动容器。
24. **`EventPriority.HIGHEST` 是「最先执行」，不是「最高优先级=最后兜底」。** 想覆盖别人的决定请用 `LOWEST`；配合 `receiveCanceled = true` 才能看到被取消的事件。
25. **`setWeatherParameters` 的两个 int 顺序。** 是 `(clearTime, rainTime, raining, thundering)`；把 rainTime 写到第一个参数上会让雨「下不出来/永远不下」。
26. **`ConfigValue#set` 只改内存。** 想持久化必须 `save()`；`SERVER` 类型配置在客户端是内存态（`CommentedConfig.inMemory()`），改了也会被服务端同步覆盖。

---

## 12. 与本仓库现有 `versions/forge-common` 代码的交叉核对（只读检查，未修改任何源码）

用上面核对出的 1.18.2 事实去扫现有源码（`versions/forge-common/src/main/java/com/weathersync/forge/**`），发现 2 个**必然编译失败**的点 + 1 个建议：

| 位置 | 现有写法 | 问题 | 1.18.2 正确写法 |
| :--- | :--- | :--- | :--- |
| `WeatherSyncMod.java:97` | `ConfigGuiHandler.registerModConfigScreenFactory(MOD_ID, parent -> new WeatherSyncConfigScreen(parent))` | ✅ 该方法在 40.3.12 中**不存在**（字节码确认 `ConfigGuiHandler` 只有 `getGuiFactoryFor`） → 编译失败 | `ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class, () -> new ConfigGuiHandler.ConfigGuiFactory(parent -> new WeatherSyncConfigScreen(parent)))` |
| `WeatherSyncClientEvents.java`(8 处)、`WeatherSyncConfigScreen.java`(8 处)、`WeatherSyncCommand.java`(5 处)、`ForgePlayerHandle.java`(1 处) | `Component.literal("…")` | ✅ 1.18.2 的 `Component` **没有** `literal` 静态工厂（javadoc 全页 0 处 "literal"） → 编译失败（1.19+ 才有） | `new net.minecraft.network.chat.TextComponent("…")`（翻译文本用 `new TranslatableComponent(key, args)`；拼字符串用 `Component.nullToEmpty(s)`） |
| `WeatherSyncMod.java:275` | `event.addListener((ResourceManagerReloadListener) manager -> {…})` | ◐ 能编译（该接口继承 `PreparableReloadListener`），但它已 **`@Deprecated`** | 直接传 `PreparableReloadListener`，或继承 `SimpleJsonResourceReloadListener`（见 §7，省掉手写遍历） |

已核对**写法正确**的部分（可放心保留）：
`TickEvent.ServerTickEvent` + `event.phase` + `ServerLifecycleHooks.getCurrentServer()`（`WeatherSyncMod.java:147-158`）、
`RenderGameOverlayEvent.Post` + `ElementType.ALL`（`WeatherSyncClientEvents.java:62-63`）、
`ServerStartedEvent/ServerStoppingEvent#getServer()`、`PlayerEvent.PlayerLoggedInEvent#getPlayer()`、
`ModLoadingContext.get().registerConfig(Type, spec, fileName)`、
`import net.minecraftforge.fml.event.config.ModConfigEvent` + mod 总线注册（`WeatherSyncMod.java:36,86-87`）。

---

## 附录：本次核对用到的原始命令（可复现）

```powershell
# 1) 拉 Forge 1.18.x 源码逐字核对
git clone --depth 1 -b 1.18.x https://github.com/MinecraftForge/MinecraftForge.git
# 2) 拉对应 jar 用 javap 反查（40.3.12 == 本仓库 build.gradle 声明的版本）
#    https://maven.minecraftforge.net/net/minecraftforge/forge/1.18.2-40.3.12/forge-1.18.2-40.3.12-universal.jar
#    https://maven.minecraftforge.net/net/minecraftforge/fmlcore/1.18.2-40.3.12/fmlcore-1.18.2-40.3.12.jar
#    https://maven.minecraftforge.net/net/minecraftforge/javafmllanguage/1.18.2-40.3.12/javafmllanguage-1.18.2-40.3.12.jar
#    https://maven.minecraftforge.net/net/minecraftforge/eventbus/5.0.3/eventbus-5.0.3.jar
javap -cp forge-1.18.2-40.3.12-universal.jar net.minecraftforge.client.ConfigGuiHandler
javap -cp forge-1.18.2-40.3.12-universal.jar 'net.minecraftforge.event.TickEvent$ServerTickEvent'
# 3) 原版类：javadoc 镜像 + Mojang 官方 1.18.2 映射 client.txt（经 piston-meta 下载）
```

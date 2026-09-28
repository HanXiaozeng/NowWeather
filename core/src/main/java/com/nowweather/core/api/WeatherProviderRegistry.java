package com.nowweather.core.api;

import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.weather.WeatherObservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 天气数据源注册表 + 回退链。
 *
 * <p>回退逻辑是「真实天气模组」的生命线：API 挂了要有缓存，缓存过期了要有离线生成，
 * 所以这里按优先级依次尝试，直到有一个 provider 成功。</p>
 */
public final class WeatherProviderRegistry {

    private final List<WeatherProvider> providers = new CopyOnWriteArrayList<>();
    private final List<RegistrationListener> listeners = new CopyOnWriteArrayList<>();

    public interface RegistrationListener {
        void onRegistered(WeatherProvider provider);

        default void onUnregistered(WeatherProvider provider) {
        }
    }

    public WeatherProviderRegistry register(WeatherProvider provider) {
        providers.removeIf(p -> p.id().equalsIgnoreCase(provider.id()));
        providers.add(provider);
        providers.sort(Comparator.comparingInt(WeatherProvider::priority));
        listeners.forEach(l -> l.onRegistered(provider));
        return this;
    }

    public boolean unregister(String providerId) {
        Optional<WeatherProvider> found = byId(providerId);
        if (found.isEmpty()) {
            return false;
        }
        providers.remove(found.get());
        listeners.forEach(l -> l.onUnregistered(found.get()));
        return true;
    }

    public void addListener(RegistrationListener listener) {
        listeners.add(listener);
    }

    public Optional<WeatherProvider> byId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return providers.stream().filter(p -> p.id().equalsIgnoreCase(providerId.trim())).findFirst();
    }

    /** 按优先级排序的全部 provider（不可变视图）。 */
    public List<WeatherProvider> all() {
        return List.copyOf(providers);
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    /**
     * 依次询问所有可用 provider，返回第一个成功结果。
     *
     * @param context 查询上下文
     * @param logger  诊断日志（可为 null）
     */
    public Attempt attempt(ProviderContext context, SyncLogger logger) {
        List<ProviderResult> attempts = new ArrayList<>();
        for (WeatherProvider provider : providers) {
            if (!provider.isEnabled(context)) {
                attempts.add(ProviderResult.failure(provider.id(), ProviderStatus.DISABLED, "provider 已禁用"));
                continue;
            }
            ProviderResult result;
            try {
                result = provider.fetch(context);
            } catch (RuntimeException e) {
                result = ProviderResult.failure(provider.id(), ProviderStatus.PARSE_ERROR,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (result == null) {
                result = ProviderResult.failure(provider.id(), ProviderStatus.NO_DATA, "provider 返回 null");
            }
            attempts.add(result);
            if (result.isSuccess()) {
                return new Attempt(result, attempts);
            }
            if (logger != null) {
                logger.debug("数据源 {} 未命中: {}", provider.id(), result.status() + " " + result.message());
            }
        }
        return new Attempt(null, attempts);
    }

    /** 逐个询问所有 provider，收集全部结果（诊断命令用）。 */
    public Map<String, ProviderResult> probeAll(ProviderContext context) {
        Map<String, ProviderResult> out = new LinkedHashMap<>();
        for (WeatherProvider provider : providers) {
            ProviderResult result;
            try {
                result = provider.isEnabled(context)
                        ? provider.fetch(context)
                        : ProviderResult.failure(provider.id(), ProviderStatus.DISABLED, "provider 已禁用");
            } catch (RuntimeException e) {
                result = ProviderResult.failure(provider.id(), ProviderStatus.PARSE_ERROR, String.valueOf(e.getMessage()));
            }
            out.put(provider.id(), result == null
                    ? ProviderResult.failure(provider.id(), ProviderStatus.NO_DATA, "null")
                    : result);
        }
        return out;
    }

    /** 一次回退链尝试的结果。 */
    public record Attempt(ProviderResult success, List<ProviderResult> attempts) {

        public boolean hasObservation() {
            return success != null && success.observation() != null;
        }

        public WeatherObservation observation() {
            return success == null ? null : success.observation();
        }

        public String successProviderId() {
            return success == null ? "" : success.providerId();
        }

        /** 把失败原因压缩成一行，方便写进日志 / 命令输出。 */
        public String failureSummary() {
            if (attempts.isEmpty()) {
                return "没有注册任何天气数据源";
            }
            StringBuilder sb = new StringBuilder();
            for (ProviderResult result : attempts) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(result.providerId()).append('=').append(result.status());
                if (!result.message().isEmpty()) {
                    sb.append('(').append(result.message()).append(')');
                }
            }
            return sb.toString();
        }
    }
}

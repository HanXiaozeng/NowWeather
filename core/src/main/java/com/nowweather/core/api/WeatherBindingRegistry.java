package com.nowweather.core.api;

import com.nowweather.core.log.SyncLogger;
import com.nowweather.core.weather.WeatherObservation;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 出站联动注册表：管理「把天气推给谁」。 */
public final class WeatherBindingRegistry {

    private final List<WeatherBinding> bindings = new CopyOnWriteArrayList<>();
    private final Set<String> activated = ConcurrentHashMap.newKeySet();

    public WeatherBindingRegistry register(WeatherBinding binding) {
        bindings.removeIf(b -> b.id().equalsIgnoreCase(binding.id()));
        bindings.add(binding);
        bindings.sort(Comparator.comparingInt(WeatherBinding::priority));
        return this;
    }

    public boolean unregister(String bindingId) {
        Optional<WeatherBinding> found = bindings.stream()
                .filter(b -> b.id().equalsIgnoreCase(bindingId)).findFirst();
        found.ifPresent(bindings::remove);
        found.ifPresent(b -> activated.remove(b.id()));
        return found.isPresent();
    }

    public List<WeatherBinding> all() {
        return List.copyOf(bindings);
    }

    /** 当前环境下真正可用的联动。 */
    public List<WeatherBinding> available(ModPresence presence) {
        return bindings.stream().filter(b -> b.isAvailable(presence)).toList();
    }

    public void activate(BindingContext context, SyncLogger logger) {
        for (WeatherBinding binding : available(context.presence())) {
            if (activated.add(binding.id())) {
                try {
                    binding.onActivate(context);
                    logger.info("联动已启用: {} -> {}", binding.id(), binding.targetModId());
                } catch (RuntimeException e) {
                    logger.error(e, "联动 {} 初始化失败", binding.id());
                    activated.remove(binding.id());
                }
            }
        }
    }

    public void dispatchWeatherChanged(BindingContext context, PlatformWeather applied,
                                      WeatherObservation observation, SyncLogger logger) {
        for (WeatherBinding binding : available(context.presence())) {
            try {
                binding.onWeatherChanged(context, applied, observation);
            } catch (RuntimeException e) {
                logger.error(e, "联动 {} 处理天气变更失败", binding.id());
            }
        }
    }

    public void dispatchTick(BindingContext context, PlatformWeather applied, SyncLogger logger) {
        for (WeatherBinding binding : available(context.presence())) {
            try {
                binding.onServerTick(context, applied);
            } catch (RuntimeException e) {
                logger.error(e, "联动 {} 处理 tick 失败", binding.id());
            }
        }
    }

    public void deactivateAll(BindingContext context, SyncLogger logger) {
        for (WeatherBinding binding : all()) {
            if (activated.remove(binding.id())) {
                try {
                    binding.onDeactivate(context);
                } catch (RuntimeException e) {
                    logger.error(e, "联动 {} 停用失败", binding.id());
                }
            }
        }
    }
}

package com.nowweather.core.api;

import com.nowweather.core.json.JsonObject;
import com.nowweather.core.weather.WeatherObservation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider 查询结果：状态 + 观测数据 + 诊断信息。 */
public final class ProviderResult {

    private final String providerId;
    private final ProviderStatus status;
    private final WeatherObservation observation;
    private final String message;
    private final Map<String, String> diagnostics;
    private final long retryAfterMillis;

    private ProviderResult(String providerId, ProviderStatus status, WeatherObservation observation,
                           String message, Map<String, String> diagnostics, long retryAfterMillis) {
        this.providerId = providerId;
        this.status = status;
        this.observation = observation;
        this.message = message == null ? "" : message;
        this.diagnostics = Map.copyOf(diagnostics);
        this.retryAfterMillis = retryAfterMillis;
    }

    public static ProviderResult ok(String providerId, WeatherObservation observation) {
        return new ProviderResult(providerId, ProviderStatus.OK, observation, "", Map.of(), 0L);
    }

    public static ProviderResult failure(String providerId, ProviderStatus status, String message) {
        return new ProviderResult(providerId, status, null, message, Map.of(), 0L);
    }

    public static ProviderResult failure(String providerId, ProviderStatus status, String message,
                                         long retryAfterMillis) {
        return new ProviderResult(providerId, status, null, message, Map.of(), retryAfterMillis);
    }

    public String providerId() {
        return providerId;
    }

    public ProviderStatus status() {
        return status;
    }

    public WeatherObservation observation() {
        return observation;
    }

    public boolean isSuccess() {
        return status.isSuccess() && observation != null;
    }

    public String message() {
        return message;
    }

    public Map<String, String> diagnostics() {
        return diagnostics;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public ProviderResult withDiagnostic(String key, String value) {
        Map<String, String> next = new LinkedHashMap<>(diagnostics);
        next.put(key, value);
        return new ProviderResult(providerId, status, observation, message, next, retryAfterMillis);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("provider", providerId);
        json.put("status", status.name());
        if (!message.isEmpty()) {
            json.put("message", message);
        }
        if (observation != null) {
            json.put("observation", observation.toJson());
        }
        if (!diagnostics.isEmpty()) {
            JsonObject diag = new JsonObject();
            diagnostics.forEach(diag::put);
            json.put("diagnostics", diag);
        }
        if (retryAfterMillis > 0L) {
            json.put("retryAfterMillis", retryAfterMillis);
        }
        return json;
    }

    @Override
    public String toString() {
        return "ProviderResult[" + providerId + " " + status + (message.isEmpty() ? "" : " " + message) + "]";
    }
}

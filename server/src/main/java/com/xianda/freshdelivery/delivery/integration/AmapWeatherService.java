package com.xianda.freshdelivery.delivery.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.routing.AmapWebKeyResolver;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmapWeatherService implements WeatherService {
    public static final String NAME = "AMAP";

    private static final Logger log = LoggerFactory.getLogger(AmapWeatherService.class);
    private static final String WEATHER_ENDPOINT = "https://restapi.amap.com/v3/weather/weatherInfo";
    private static final String REGEO_ENDPOINT = "https://restapi.amap.com/v3/geocode/regeo";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final List<String> BAD_KEYWORDS =
            List.of("雨", "雪", "雹", "雾", "霾", "沙", "尘", "冰", "台风", "风暴");
    private static final List<String> SEVERE_KEYWORDS =
            List.of("暴雨", "大暴雨", "特大暴雨", "暴雪", "大雪", "台风", "冰雹", "雷暴");
    private static final double BAD_MULTIPLIER = 1.2d;
    private static final double SEVERE_MULTIPLIER = 1.4d;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Supplier<String> webKeySupplier;
    private final Double storeLat;
    private final Double storeLng;

    private volatile String cityCode;
    private volatile WeatherSnapshot cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public AmapWeatherService(ObjectMapper objectMapper, String webKey, Double storeLat, Double storeLng) {
        this(objectMapper, () -> webKey, storeLat, storeLng);
    }

    public AmapWeatherService(
            ObjectMapper objectMapper,
            AmapWebKeyResolver webKeyResolver,
            Double storeLat,
            Double storeLng
    ) {
        this(objectMapper, webKeyResolver::resolve, storeLat, storeLng);
    }

    private AmapWeatherService(
            ObjectMapper objectMapper,
            Supplier<String> webKeySupplier,
            Double storeLat,
            Double storeLng
    ) {
        this.objectMapper = objectMapper;
        this.webKeySupplier = webKeySupplier;
        this.storeLat = storeLat;
        this.storeLng = storeLng;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public WeatherSnapshot snapshot(LocalDateTime at) {
        WeatherSnapshot local = cached;
        if (local != null && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) {
            return local;
        }
        WeatherSnapshot fetched = fetch();
        cached = fetched;
        cachedAt = Instant.now();
        return fetched;
    }

    private WeatherSnapshot fetch() {
        String webKey = webKeySupplier.get();
        if (webKey == null || webKey.isBlank()) {
            return WeatherSnapshot.clear(NoopWeatherService.NAME);
        }
        try {
            String city = resolveCityCode(webKey);
            if (city == null || city.isBlank()) {
                return WeatherSnapshot.clear(NoopWeatherService.NAME);
            }
            String url = WEATHER_ENDPOINT + "?key=" + encode(webKey) + "&city=" + encode(city) + "&extensions=base";
            JsonNode root = objectMapper.readTree(get(url));
            if (!"1".equals(root.path("status").asText())) {
                log.warn("高德天气查询失败：{}", root.path("info").asText(""));
                return WeatherSnapshot.clear(NoopWeatherService.NAME);
            }
            JsonNode lives = root.path("lives");
            if (!lives.isArray() || lives.isEmpty()) {
                return WeatherSnapshot.clear(NoopWeatherService.NAME);
            }
            String condition = lives.get(0).path("weather").asText("晴");
            return classify(condition);
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("高德天气查询异常，降级为晴：{}", exception.getMessage());
            return WeatherSnapshot.clear(NoopWeatherService.NAME);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return WeatherSnapshot.clear(NoopWeatherService.NAME);
        }
    }

    static WeatherSnapshot classify(String condition) {
        String text = condition == null ? "" : condition.trim();
        if (text.isEmpty()) {
            return WeatherSnapshot.clear(NAME);
        }
        for (String keyword : SEVERE_KEYWORDS) {
            if (text.contains(keyword)) {
                return new WeatherSnapshot(text, true, SEVERE_MULTIPLIER, NAME);
            }
        }
        for (String keyword : BAD_KEYWORDS) {
            if (text.contains(keyword)) {
                return new WeatherSnapshot(text, true, BAD_MULTIPLIER, NAME);
            }
        }
        return new WeatherSnapshot(text, false, 1.0d, NAME);
    }

    private String resolveCityCode(String webKey) throws java.io.IOException, InterruptedException {
        String local = cityCode;
        if (local != null) {
            return local;
        }
        if (storeLat == null || storeLng == null || (storeLat == 0d && storeLng == 0d)) {
            return null;
        }
        String location = String.format(Locale.ROOT, "%.6f,%.6f", storeLng, storeLat);
        String url = REGEO_ENDPOINT + "?key=" + encode(webKey) + "&location=" + encode(location);
        JsonNode root = objectMapper.readTree(get(url));
        if (!"1".equals(root.path("status").asText())) {
            return null;
        }
        String adcode = root.path("regeocode").path("addressComponent").path("adcode").asText(null);
        if (adcode != null && !adcode.isBlank()) {
            cityCode = adcode;
        }
        return cityCode;
    }

    private String get(String url) throws java.io.IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("高德天气服务 HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

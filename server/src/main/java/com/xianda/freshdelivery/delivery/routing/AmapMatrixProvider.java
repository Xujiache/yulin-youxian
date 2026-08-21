package com.xianda.freshdelivery.delivery.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AmapMatrixProvider implements DistanceMatrixProvider {
    public static final String NAME = "AMAP";

    static final URI DISTANCE_ENDPOINT = URI.create("https://restapi.amap.com/v3/distance");
    static final URI BICYCLE_ENDPOINT = URI.create("https://restapi.amap.com/v5/direction/bicycling");
    static final URI EBIKE_ENDPOINT = URI.create("https://restapi.amap.com/v5/direction/electrobike");
    static final int MAX_REQUESTS_PER_MATRIX = 64;

    private static final Logger log = LoggerFactory.getLogger(AmapMatrixProvider.class);
    private static final int MAX_ORIGINS_PER_DISTANCE_REQUEST = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration PER_REQUEST_TIMEOUT_CAP = Duration.ofSeconds(3);

    private final AmapWebKeyResolver webKeyResolver;
    private final RoutingSettings settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI distanceEndpoint;
    private final URI bicycleEndpoint;
    private final URI ebikeEndpoint;

    @Autowired
    public AmapMatrixProvider(AmapWebKeyResolver webKeyResolver,
                              RoutingSettings settings,
                              ObjectMapper objectMapper) {
        this(webKeyResolver, settings, objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                DISTANCE_ENDPOINT, BICYCLE_ENDPOINT, EBIKE_ENDPOINT);
    }

    public AmapMatrixProvider(ObjectProvider<DeliveryProperties> deliveryProperties, ObjectMapper objectMapper) {
        this(new AmapWebKeyResolver(deliveryProperties, null), null, objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                DISTANCE_ENDPOINT, BICYCLE_ENDPOINT, EBIKE_ENDPOINT);
    }

    AmapMatrixProvider(ObjectProvider<DeliveryProperties> deliveryProperties,
                       RoutingConfigSource configSource,
                       RoutingSettings settings,
                       ObjectMapper objectMapper,
                       HttpClient httpClient,
                       URI distanceEndpoint,
                       URI bicycleEndpoint,
                       URI ebikeEndpoint) {
        this(new AmapWebKeyResolver(deliveryProperties, configSource), settings, objectMapper,
                httpClient, distanceEndpoint, bicycleEndpoint, ebikeEndpoint);
    }

    private AmapMatrixProvider(AmapWebKeyResolver webKeyResolver,
                               RoutingSettings settings,
                               ObjectMapper objectMapper,
                               HttpClient httpClient,
                               URI distanceEndpoint,
                               URI bicycleEndpoint,
                               URI ebikeEndpoint) {
        this.webKeyResolver = webKeyResolver;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.distanceEndpoint = distanceEndpoint;
        this.bicycleEndpoint = bicycleEndpoint;
        this.ebikeEndpoint = ebikeEndpoint;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return webKeyResolver.enabled() && webKey() != null;
    }

    @Override
    public MatrixResult compute(GeoPoint origin, List<GeoPoint> destinations, TravelMode mode) {
        requireAvailable();
        TravelMode resolvedMode = resolvedMode(mode);
        RequestBudget budget = new RequestBudget(destinations.size(), maxRequests(), totalTimeoutMillis());
        int[][] distance = new int[1][destinations.size()];
        int[][] duration = new int[1][destinations.size()];
        for (int j = 0; j < destinations.size(); j++) {
            Edge edge;
            if (usesRouteApi(resolvedMode)) {
                edge = queryRoute(origin, destinations.get(j), resolvedMode, budget);
            } else {
                edge = queryDistance(List.of(origin), destinations.get(j), resolvedMode, budget)[0];
            }
            distance[0][j] = edge.distanceMeters();
            duration[0][j] = edge.durationSeconds();
        }
        return new MatrixResult(NAME, resolvedMode, distance, duration);
    }

    @Override
    public MatrixResult computeFull(List<GeoPoint> points, TravelMode mode) {
        requireAvailable();
        TravelMode resolvedMode = resolvedMode(mode);
        int n = points.size();
        long requiredRequests = usesRouteApi(resolvedMode)
                ? (long) n * Math.max(0, n - 1)
                : distanceRequestCount(n);
        RequestBudget budget = new RequestBudget(requiredRequests, maxRequests(), totalTimeoutMillis());
        int[][] distance = new int[n][n];
        int[][] duration = new int[n][n];

        if (usesRouteApi(resolvedMode)) {
            for (int from = 0; from < n; from++) {
                for (int to = 0; to < n; to++) {
                    if (from == to) {
                        continue;
                    }
                    Edge edge = queryRoute(points.get(from), points.get(to), resolvedMode, budget);
                    distance[from][to] = edge.distanceMeters();
                    duration[from][to] = edge.durationSeconds();
                }
            }
            return new MatrixResult(NAME, resolvedMode, distance, duration);
        }

        for (int destinationIndex = 0; destinationIndex < n; destinationIndex++) {
            List<GeoPoint> origins = new ArrayList<>(Math.max(0, n - 1));
            List<Integer> rowIndexes = new ArrayList<>(Math.max(0, n - 1));
            for (int row = 0; row < n; row++) {
                if (row != destinationIndex) {
                    origins.add(points.get(row));
                    rowIndexes.add(row);
                }
            }
            for (int offset = 0; offset < origins.size(); offset += MAX_ORIGINS_PER_DISTANCE_REQUEST) {
                int end = Math.min(origins.size(), offset + MAX_ORIGINS_PER_DISTANCE_REQUEST);
                Edge[] edges = queryDistance(
                        origins.subList(offset, end), points.get(destinationIndex), resolvedMode, budget);
                for (int i = 0; i < edges.length; i++) {
                    int row = rowIndexes.get(offset + i);
                    distance[row][destinationIndex] = edges[i].distanceMeters();
                    duration[row][destinationIndex] = edges[i].durationSeconds();
                }
            }
        }
        return new MatrixResult(NAME, resolvedMode, distance, duration);
    }

    private Edge[] queryDistance(List<GeoPoint> origins,
                                 GeoPoint destination,
                                 TravelMode mode,
                                 RequestBudget budget) {
        URI uri = distanceUri(origins, destination, mode);
        String body = send(uri, budget);
        return parseDistance(body, origins.size());
    }

    private Edge queryRoute(GeoPoint origin, GeoPoint destination, TravelMode mode, RequestBudget budget) {
        URI endpoint = mode == TravelMode.BICYCLE ? bicycleEndpoint : ebikeEndpoint;
        URI uri = routeUri(endpoint, origin, destination);
        return parseRoute(send(uri, budget));
    }

    private String send(URI uri, RequestBudget budget) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(budget.acquireRequestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new DistanceMatrixException("高德路径服务返回 HTTP " + response.statusCode() + "，将回落直线估算");
            }
            return response.body();
        } catch (java.io.IOException ex) {
            throw new DistanceMatrixException("高德路径服务请求失败，将回落直线估算", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DistanceMatrixException("高德路径服务请求被中断，将回落直线估算", ex);
        }
    }

    private Edge[] parseDistance(String body, int expectedRows) {
        try {
            JsonNode root = objectMapper.readTree(body);
            requireSuccess(root);
            Edge[] edges = new Edge[expectedRows];
            for (JsonNode node : root.path("results")) {
                int index = node.path("origin_id").asInt(0) - 1;
                if (index >= 0 && index < expectedRows) {
                    edges[index] = edgeOf(node);
                }
            }
            for (int i = 0; i < expectedRows; i++) {
                if (edges[i] == null) {
                    throw new DistanceMatrixException("高德距离测量返回缺少第 " + (i + 1) + " 个起点");
                }
            }
            return edges;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new DistanceMatrixException("高德距离测量响应解析失败", ex);
        }
    }

    private Edge parseRoute(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            requireSuccess(root);
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                paths = root.path("data").path("paths");
            }
            if (!paths.isArray() || paths.isEmpty()) {
                throw new DistanceMatrixException("高德骑行路径响应缺少可用路线，将回落直线估算");
            }
            JsonNode path = paths.get(0);
            double distance = path.path("distance").asDouble(-1d);
            double duration = path.path("cost").path("duration").asDouble(-1d);
            if (duration < 0d) {
                duration = path.path("duration").asDouble(-1d);
            }
            if (distance < 0d || duration < 0d) {
                throw new DistanceMatrixException("高德骑行路径响应缺少距离或时长，将回落直线估算");
            }
            return new Edge((int) Math.round(distance), (int) Math.round(duration));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new DistanceMatrixException("高德骑行路径响应解析失败", ex);
        }
    }

    private void requireSuccess(JsonNode root) {
        if (root.has("status") && !"1".equals(root.path("status").asText())) {
            throw new DistanceMatrixException("高德路径服务失败：" + root.path("info").asText("未知错误"));
        }
        if (root.has("errcode") && root.path("errcode").asInt(-1) != 0) {
            throw new DistanceMatrixException("高德路径服务失败：" + root.path("errmsg").asText("未知错误"));
        }
    }

    private static Edge edgeOf(JsonNode node) {
        return new Edge(
                (int) Math.round(node.path("distance").asDouble(0d)),
                (int) Math.round(node.path("duration").asDouble(0d)));
    }

    private URI distanceUri(List<GeoPoint> origins, GeoPoint destination, TravelMode mode) {
        List<String> tokens = new ArrayList<>(origins.size());
        for (GeoPoint origin : origins) {
            tokens.add(coordinate(origin));
        }
        String query = "key=" + encode(webKey())
                + "&origins=" + encode(String.join("|", tokens))
                + "&destination=" + encode(coordinate(destination))
                + "&type=" + distanceType(mode)
                + "&output=JSON";
        return withQuery(distanceEndpoint, query);
    }

    private URI routeUri(URI endpoint, GeoPoint origin, GeoPoint destination) {
        String query = "key=" + encode(webKey())
                + "&origin=" + encode(coordinate(origin))
                + "&destination=" + encode(coordinate(destination))
                + "&show_fields=cost";
        return withQuery(endpoint, query);
    }

    private static URI withQuery(URI endpoint, String query) {
        String value = endpoint.toString();
        return URI.create(value + (value.contains("?") ? "&" : "?") + query);
    }

    private static String coordinate(GeoPoint point) {
        return String.format(Locale.ROOT, "%.6f,%.6f", point.lng(), point.lat());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static TravelMode resolvedMode(TravelMode mode) {
        return mode == null ? TravelMode.EBIKE : mode;
    }

    private static boolean usesRouteApi(TravelMode mode) {
        return mode == TravelMode.EBIKE || mode == TravelMode.BICYCLE;
    }

    private static int distanceType(TravelMode mode) {
        return switch (mode) {
            case CAR -> 1;
            case WALK -> 3;
            case EBIKE, BICYCLE ->
                    throw new IllegalArgumentException("骑行模式必须使用高德 direction API，不得使用 /v3/distance type=4");
        };
    }

    private static long distanceRequestCount(int pointCount) {
        if (pointCount <= 1) {
            return 0L;
        }
        long batchesPerDestination =
                ((long) pointCount - 2L + MAX_ORIGINS_PER_DISTANCE_REQUEST)
                        / MAX_ORIGINS_PER_DISTANCE_REQUEST;
        return (long) pointCount * batchesPerDestination;
    }

    private long totalTimeoutMillis() {
        long configured = settings == null ? 5_000L : settings.solveTimeoutMillis();
        return Math.max(1L, Math.min(10_000L, configured));
    }

    private int maxRequests() {
        return settings == null ? MAX_REQUESTS_PER_MATRIX : settings.amapMaxRequests();
    }

    private String webKey() {
        return webKeyResolver.resolve();
    }

    private void requireAvailable() {
        if (!available()) {
            log.warn("高德路径服务未启用或缺少服务端 Key，拒绝提供矩阵");
            throw new DistanceMatrixException("高德路径服务未启用或缺少服务端 Key");
        }
    }

    private record Edge(int distanceMeters, int durationSeconds) {
    }

    private static final class RequestBudget {
        private final long deadlineNanos;
        private int remainingRequests;

        private RequestBudget(long requiredRequests, int maxRequests, long timeoutMillis) {
            if (requiredRequests > maxRequests) {
                throw new DistanceMatrixException(
                        "高德路径矩阵需要 " + requiredRequests + " 次请求，超过单次规划上限 "
                                + maxRequests + "，将回落直线估算");
            }
            this.remainingRequests = (int) requiredRequests;
            this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
        }

        private Duration acquireRequestTimeout() {
            if (remainingRequests <= 0) {
                throw new DistanceMatrixException("高德路径请求数超过预算，将回落直线估算");
            }
            remainingRequests--;
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new DistanceMatrixException("高德路径请求超过总时限，将回落直线估算");
            }
            return Duration.ofNanos(Math.max(1L,
                    Math.min(remainingNanos, PER_REQUEST_TIMEOUT_CAP.toNanos())));
        }
    }
}

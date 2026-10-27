package com.xianda.freshdelivery.delivery.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xianda.freshdelivery.delivery.common.DeliveryProperties;
import com.xianda.freshdelivery.delivery.common.GeoPoint;
import com.xianda.freshdelivery.delivery.common.TravelMode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AmapMatrixProviderTests {
    private final List<URI> requestedUris = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private AmapMatrixProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        DeliveryProperties properties = new DeliveryProperties(
                true, null, new DeliveryProperties.Amap("", null, true),
                null, null, null, null, null);
        RoutingSettings settings = RoutingTestSupport.settings(Map.of(
                RoutingConfigKeys.SOLVE_TIMEOUT_MILLIS, 2_000));
        provider = new AmapMatrixProvider(
                new RoutingFakes.SingletonObjectProvider<>(properties),
                RoutingTestSupport.configSource(Map.of("amap.web_key", "db-server-key")),
                settings,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                root.resolve("/v3/distance"),
                root.resolve("/v5/direction/bicycling"),
                root.resolve("/v5/direction/electrobike"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ebikeUsesOfficialElectrobikeDirectionApiAndNeverDistanceTypeFour() {
        DistanceMatrixProvider.MatrixResult result = provider.compute(
                new GeoPoint(30.100000d, 120.700000d),
                List.of(new GeoPoint(30.110000d, 120.710000d)),
                TravelMode.EBIKE);

        assertEquals(1234, result.distanceMeters(0, 0));
        assertEquals(321, result.durationSeconds(0, 0));
        assertEquals(1, requestedUris.size());
        URI uri = requestedUris.get(0);
        assertEquals("/v5/direction/electrobike", uri.getPath());
        assertTrue(uri.getRawQuery().contains("origin="));
        assertTrue(uri.getRawQuery().contains("destination="));
        assertFalse(uri.getRawQuery().contains("type=4"));
    }

    @Test
    void bicycleUsesOfficialBicyclingDirectionApi() {
        provider.compute(
                new GeoPoint(30.100000d, 120.700000d),
                List.of(new GeoPoint(30.110000d, 120.710000d)),
                TravelMode.BICYCLE);

        assertEquals(1, requestedUris.size());
        assertEquals("/v5/direction/bicycling", requestedUris.get(0).getPath());
        assertFalse(requestedUris.get(0).getRawQuery().contains("type=4"));
    }

    @Test
    void carKeepsUsingOnlyTheSupportedDistanceTypeOne() {
        DistanceMatrixProvider.MatrixResult result = provider.compute(
                new GeoPoint(30.100000d, 120.700000d),
                List.of(new GeoPoint(30.110000d, 120.710000d)),
                TravelMode.CAR);

        assertEquals(2345, result.distanceMeters(0, 0));
        assertEquals(456, result.durationSeconds(0, 0));
        assertEquals("/v3/distance", requestedUris.get(0).getPath());
        assertTrue(requestedUris.get(0).getRawQuery().contains("type=1"));
        assertFalse(requestedUris.get(0).getRawQuery().contains("type=4"));
    }

    @Test
    void oversizedDirectionMatrixFallsBackBeforeSendingAnyHttpRequest() {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            points.add(new GeoPoint(30.10d + i * 0.001d, 120.70d + i * 0.001d));
        }

        assertThrows(DistanceMatrixException.class, () -> provider.computeFull(points, TravelMode.EBIKE));
        assertTrue(requestedUris.isEmpty());
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestedUris.add(exchange.getRequestURI());
        String response = "/v3/distance".equals(exchange.getRequestURI().getPath())
                ? """
                  {"status":"1","info":"OK","results":[
                    {"origin_id":"1","distance":"2345","duration":"456"}
                  ]}
                  """
                : """
                  {"status":"1","info":"OK","route":{"paths":[
                    {"distance":"1234","cost":{"duration":"321"}}
                  ]}}
                  """;
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}

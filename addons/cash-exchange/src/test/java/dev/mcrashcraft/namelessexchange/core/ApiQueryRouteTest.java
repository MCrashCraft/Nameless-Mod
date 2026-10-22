/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The deployed site's index.php route must behave like the pretty /api/v2 route. */
final class ApiQueryRouteTest {
    @Test void indexPhpQueryRouteVerifiesActualUuidAndPostsWholeCreditsOnce() throws Exception {
        UUID uuid = UUID.fromString("00000000-0000-4000-8000-000000009001");
        AtomicInteger gets = new AtomicInteger(), posts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/index.php", exchange -> {
            try {
                assertEquals("Bearer query-route-fixture-only", exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("query-route-fixture-only", exchange.getRequestHeaders().getFirst("X-API-Key"));
                String response;
                if (exchange.getRequestMethod().equals("GET")) {
                    gets.incrementAndGet();
                    assertEquals("route=/api/v2/users/integration_id%3AMinecraft%3A"
                            + uuid.toString().replace("-", ""), exchange.getRequestURI().getRawQuery());
                    response = "{\"exists\":true,\"validated\":true,\"banned\":false,\"id\":901,"
                            + "\"integrations\":[{\"integration\":\"Minecraft\",\"identifier\":\""
                            + uuid.toString().replace("-", "") + "\",\"verified\":true}]}";
                } else {
                    posts.incrementAndGet();
                    assertEquals("POST", exchange.getRequestMethod());
                    assertEquals("route=/api/v2/users/id:901/add-credits", exchange.getRequestURI().getRawQuery());
                    assertEquals(JsonParser.parseString("{\"credits\":100}"), JsonParser.parseString(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    response = "{\"message\":\"Successfully added credits to user\"}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable error) {
                failure.set(error);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var api = new ExchangeApiClient("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/index.php?route=/api/v2", "query-route-fixture-only");
            var account = api.verify(uuid);
            assertEquals(901, account.userId());
            assertEquals(uuid, account.playerId());
            api.credit(account, 100);
            assertNull(failure.get());
            assertEquals(1, gets.get());
            assertEquals(1, posts.get());
        } finally { server.stop(0); }
    }
}

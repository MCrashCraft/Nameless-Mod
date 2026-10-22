/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Narrow, single-application-attempt transport. It never uses the core API's retrying POST helper. */
public final class ExchangeApiClient {
    public record Account(int userId, UUID playerId) {
        public Account { if (userId <= 0 || playerId == null) throw new IllegalArgumentException("Invalid linked account"); }
    }
    private static final int MAX_RESPONSE = 65_536;
    private static final String SUCCESS = "Successfully added credits to user";
    private final String base;
    private final String key;
    private final int budgetMillis;

    public ExchangeApiClient(String apiBase, String key) {
        this(apiBase, key, 15_000);
    }
    ExchangeApiClient(String apiBase, String key, int budgetMillis) {
        if (budgetMillis <= 0 || budgetMillis > 15_000) throw new IllegalArgumentException("Invalid request budget");
        if (apiBase == null || key == null || key.isBlank() || key.length() > 8192 || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Missing or invalid API configuration");
        String candidate = apiBase.trim();
        URI uri = URI.create(candidate);
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) && loopback))
            throw new IllegalArgumentException("Exchange API requires HTTPS or literal loopback HTTP");
        if (uri.getRawQuery() != null) {
            if (!uri.getRawQuery().matches("route=/api/v2/?")) throw new IllegalArgumentException("Unsupported API route query");
        } else if (uri.getPath() == null || !uri.getPath().matches(".*/api/v2/?")) {
            throw new IllegalArgumentException("API base must end with /api/v2");
        }
        this.base = candidate.endsWith("/") ? candidate : candidate + "/";
        this.key = key;
        this.budgetMillis = budgetMillis;
    }

    /** Fresh read: both website-account validation and the exact Minecraft integration must be verified. */
    public Account verify(UUID actualPlayerUuid) throws IOException {
        if (actualPlayerUuid == null) throw new IOException("Invalid player identity");
        JsonObject result = request("users/integration_id%3AMinecraft%3A" + actualPlayerUuid.toString().replace("-", ""), null);
        try {
            if (!flag(result.get("exists")) || !flag(result.get("validated")) || flag(result.get("banned")))
                throw new IOException("Website account is unavailable or unverified");
            int id = result.get("id").getAsBigDecimal().intValueExact();
            if (id <= 0 || result.getAsJsonArray("integrations").size() > 32) throw new IOException("Invalid website account");
            int matching = 0;
            for (JsonElement raw : result.getAsJsonArray("integrations")) {
                JsonObject integration = raw.getAsJsonObject();
                if ("Minecraft".equals(integration.get("integration").getAsString())) {
                    matching++;
                    String identifier = integration.get("identifier").getAsString().toLowerCase(Locale.ROOT);
                    if (!identifier.matches("[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                            || !identifier.replace("-", "").equals(actualPlayerUuid.toString().replace("-", ""))
                            || !flag(integration.get("verified"))) throw new IOException("Minecraft account link is unverified or mismatched");
                }
            }
            if (matching != 1) throw new IOException("A unique verified Minecraft link is required");
            return new Account(id, actualPlayerUuid);
        } catch (RuntimeException malformed) { throw new IOException("Malformed website account response"); }
    }

    /** Exactly one application submission. A thrown exception is never permission to retry or refund. */
    public void credit(Account account, int wholeCredits) throws IOException {
        if (account == null || wholeCredits <= 0 || wholeCredits > ExchangeJournal.MAX_DOLLARS) throw new IOException("Invalid credit request");
        JsonObject body = new JsonObject();
        body.addProperty("credits", wholeCredits);
        JsonObject result = request("users/id:" + account.userId() + "/add-credits", body);
        try {
            if (!result.has("message") || !result.get("message").isJsonPrimitive()
                    || !result.get("message").getAsJsonPrimitive().isString()
                    || !SUCCESS.equals(result.get("message").getAsString())) throw new IOException("Unconfirmed credit response requires review");
        } catch (RuntimeException malformed) { throw new IOException("Unconfirmed credit response requires review"); }
    }

    private JsonObject request(String route, JsonObject body) throws IOException {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        HttpURLConnection connection = (HttpURLConnection) URI.create(base + route).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.min(5_000, budgetMillis));
        connection.setReadTimeout(Math.min(10_000, budgetMillis));
        connection.setUseCaches(false);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("X-API-Key", key);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json");
                // Fixed-length streaming prevents automatic replay to satisfy redirects/authentication.
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            } else connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Website did not confirm the request");
            long expectedLength = connection.getContentLengthLong();
            if (expectedLength > MAX_RESPONSE) throw new IOException("Invalid website response size");
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new IOException("Website response exceeded its total time budget");
                    connection.setReadTimeout((int) Math.max(1, Math.min(10_000, remaining / 1_000_000L)));
                    int count = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE + 1 - output.size()));
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                    if (output.size() > MAX_RESPONSE) throw new IOException("Invalid website response size");
                }
                bytes = output.toByteArray();
            }
            if (bytes.length == 0 || bytes.length > MAX_RESPONSE || expectedLength >= 0 && expectedLength != bytes.length)
                throw new IOException("Invalid or incomplete website response size");
            try {
                JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("error")) throw new IOException("Website rejected the request");
                return json;
            } catch (RuntimeException malformed) { throw new IOException("Malformed website response"); }
        } finally { connection.disconnect(); }
    }

    private static boolean flag(JsonElement raw) throws IOException {
        if (raw == null || !raw.isJsonPrimitive()) throw new IOException("Missing account verification flag");
        return switch (raw.getAsString()) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IOException("Invalid account verification flag");
        };
    }
}

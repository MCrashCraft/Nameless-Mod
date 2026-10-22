package com.namelessmc.plugin.common;

import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.NamelessVersion;
import com.namelessmc.java_api.Website;
import com.namelessmc.java_api.exception.ApiException;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.java_api.exception.UnknownNamelessVersionException;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import com.namelessmc.plugin.common.logger.AbstractLogger;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.time.Duration;

/** Forge port: immutable configuration generations; never hold a monitor over HTTP. */
public class ApiProvider implements Reloadable {
    private static final String USER_AGENT = "Nameless-Plugin/" + MavenConstants.PROJECT_VERSION;
    private static final long RETRY_DELAY_NANOS = Duration.ofSeconds(30).toNanos();
    private final AbstractScheduler scheduler;
    private final AbstractLogger logger;
    private final ConfigurationHandler config;
    private volatile Generation generation = new Generation(null, "", false, Duration.ofSeconds(10), false, false);

    private static final class Generation {
        final @Nullable URL url;
        final String key;
        final boolean debug;
        final Duration timeout;
        final boolean bypassVersionCheck;
        final boolean forceHttp1;
        volatile @Nullable NamelessAPI api;
        volatile boolean evaluated;
        // Only read or written while synchronized on this generation.
        boolean loading;
        boolean permanentFailure;
        long retryAtNanos;

        Generation(@Nullable URL url, String key, boolean debug, Duration timeout,
                   boolean bypassVersionCheck, boolean forceHttp1) {
            this.url = url;
            this.key = key;
            this.debug = debug;
            this.timeout = timeout;
            this.bypassVersionCheck = bypassVersionCheck;
            this.forceHttp1 = forceHttp1;
        }
    }

    public ApiProvider(AbstractScheduler scheduler, AbstractLogger logger, ConfigurationHandler config) {
        this.scheduler = scheduler;
        this.logger = logger;
        this.config = config;
    }

    @Override
    public void unload() {
        // Invalidates HTTP initialization still completing in an old worker.
        this.generation = new Generation(null, "", false, Duration.ofSeconds(10), false, false);
    }

    @Override
    public void load() {
        CommentedConfigurationNode config = this.config.main().node("api");
        URL url = null;
        String rawUrl = config.node("url").getString("");
        if (!rawUrl.isBlank()) {
            try {
                URI uri = URI.create(rawUrl);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("Expected an HTTP(S) website API URL");
                }
                url = uri.toURL();
            } catch (IllegalArgumentException | MalformedURLException e) {
                this.logger.severe("Invalid NamelessMC API URL. Copy the API URL from StaffCP > Configuration > API.");
            }
        }
        Duration timeout = ConfigurationHandler.getDuration(config.node("timeout"));
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            this.logger.warning("Invalid API timeout; use a positive duration up to 30 seconds. Using 10 seconds.");
            timeout = Duration.ofSeconds(10);
        }
        Generation next = new Generation(url, config.node("key").getString(""), config.node("debug").getBoolean(),
                timeout, config.node("bypass-version-check").getBoolean(), config.node("force-http-1").getBoolean());
        this.generation = next;
        this.scheduler.runAsync(() -> this.api(next));
    }

    @Deprecated
    public @Nullable String isApiWorkingMetric() {
        Generation current = this.generation;
        return !current.evaluated ? "Unknown" : current.api != null ? "Working" : "Not working";
    }

    /** Blocking network initialization: call only from a bounded asynchronous worker. */
    public @Nullable NamelessAPI api() {
        return this.api(this.generation);
    }

    private @Nullable NamelessAPI api(Generation current) {
        synchronized (current) {
            if (this.generation != current) return null;
            if (current.api != null) return current.api;
            // Concurrent callers never wait behind an initializer's HTTP request.
            if (current.loading || current.permanentFailure
                    || current.retryAtNanos != 0 && System.nanoTime() - current.retryAtNanos < 0) return null;
            current.loading = true;
        }
        NamelessAPI candidate = null;
        boolean permanentFailure = false;
        try {
            if (current.url == null || current.key.isEmpty()) {
                this.logger.warning("NamelessMC is not configured. Set api.url, api.key and api.server-id in its private server configuration.");
                permanentFailure = true;
            } else {
                var builder = NamelessAPI.builder(current.url, current.key)
                        .userAgent(USER_AGENT)
                        .customDebugLogger(current.debug ? this.logger.getApiLogger() : null)
                        .timeout(current.timeout);
                if (current.forceHttp1) builder.httpVersion(HttpClient.Version.HTTP_1_1);
                NamelessAPI built = builder.build();
                Website website = built.website();
                if (current.bypassVersionCheck) {
                    this.logger.warning("NamelessMC API version checks are bypassed in configuration.");
                    candidate = built;
                } else {
                    try {
                        NamelessVersion version = website.parsedVersion();
                        if (NamelessVersion.isSupportedByJavaApi(version)) {
                            candidate = built;
                        } else {
                            this.logger.severe("The website's NamelessMC version is unsupported by this API library. Update the website or port.");
                            permanentFailure = true;
                        }
                    } catch (UnknownNamelessVersionException e) {
                        this.logger.severe("The website's NamelessMC version is unrecognized. Check the website and port versions.");
                        permanentFailure = true;
                    }
                }
            }
        } catch (NamelessException e) {
            this.logger.logException(e);
            permanentFailure = e instanceof ApiException;
        } catch (RuntimeException e) {
            // A malformed response must not leave initialization permanently in flight.
            this.logger.logException(e);
        } finally {
            synchronized (current) {
                current.loading = false;
                if (this.generation == current) {
                    current.api = candidate;
                    current.evaluated = true;
                    current.permanentFailure = permanentFailure;
                    current.retryAtNanos = candidate == null && !permanentFailure
                            ? System.nanoTime() + RETRY_DELAY_NANOS : 0;
                }
            }
        }
        return this.generation == current ? candidate : null;
    }

    /** Nonblocking publication read; safe for the Minecraft server thread. */
    public @Nullable NamelessAPI apiIfCached() {
        return this.generation.api;
    }
}

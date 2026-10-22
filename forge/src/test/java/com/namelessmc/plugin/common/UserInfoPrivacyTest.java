/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.namelessmc.plugin.common;

import com.namelessmc.plugin.common.audiences.AbstractAudienceProvider;
import com.namelessmc.plugin.common.audiences.NamelessConsole;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import com.namelessmc.plugin.common.command.UserInfoCommand;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual common command and real API parser against loopback-only fixtures. */
class UserInfoPrivacyTest {
    @TempDir Path temporary;

    static final class Scheduler extends AbstractScheduler {
        final Queue<Runnable> background = new ArrayDeque<>();
        final Queue<Runnable> callbacks = new ArrayDeque<>();
        Runnable timer;
        @Override public void runAsync(Runnable runnable) { background.add(runnable); }
        @Override public void runSync(Runnable runnable) { callbacks.add(runnable); }
        @Override public AbstractScheduledTask runTimer(Runnable runnable, Duration interval) { timer = runnable; return null; }
        @Override public AbstractScheduledTask runDelayed(Runnable runnable, Duration delay) { return null; }
        void drainBackground() {
            for (int i = 0; !background.isEmpty(); i++) {
                assertTrue(i < 20, "fixture work must remain bounded");
                background.remove().run();
            }
        }
        void drainCallbacks() {
            for (int i = 0; !callbacks.isEmpty(); i++) {
                assertTrue(i < 20, "fixture callbacks must remain bounded");
                callbacks.remove().run();
            }
        }
    }

    static final class Website implements AutoCloseable {
        private static final String USER = """
                {"exists":true,"id":1,"username":"OtherCitizen","displayname":"Other Citizen",
                 "registered_timestamp":0,"banned":false,"validated":true,"groups":[],"integrations":[],
                 "profile_fields":{
                   "1":{"name":"Public biography","type":1,"public":true,"required":false,"description":"Biography","value":"PUBLIC_BIO_MARKER"},
                   "2":{"name":"Private staff note","type":1,"public":false,"required":false,"description":"Staff note","value":"PRIVATE_STAFF_NOTE_MARKER"}}}
                """;
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        Website() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                // The shared API appends parameters with '&' for its StaffCP route-style URL.
                // This fixture uses a plain loopback base path, so separate those suffixes here.
                String path = exchange.getRequestURI().getPath().split("&", 2)[0];
                String json = path.endsWith("/users") ? "{\"users\":[" + USER + "]}"
                        : path.contains("/users/") ? USER
                        : "{\"nameless_version\":\"2.1.2\",\"modules\":[],\"locale\":\"en_UK\"}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/"; }
        @Override public void close() { server.stop(0); }
    }

    static final class CapturedMessages implements Audience {
        final List<String> messages = new ArrayList<>();
        @Override public void sendMessage(Identity source, Component message, MessageType type) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        String joined() { return String.join("\n", messages); }
    }

    NamelessPlugin plugin(Scheduler scheduler, Website website) throws Exception {
        NamelessPlugin plugin = new NamelessPlugin(temporary.resolve("plugin"), scheduler,
                CoreHardeningTest.QuietLogger::new, null, "forge-privacy-test", "1.20.1");
        plugin.config().load();
        plugin.language().load();
        plugin.dateFormatter().load();
        CoreHardeningTest.configure(plugin.config(), website.url(), "PT1S");
        plugin.setAudienceProvider(new AbstractAudienceProvider() {
            @Override public NamelessConsole console() {
                return new NamelessConsole(Audience.empty()) {
                    @Override public void dispatchCommand(String command) { fail("No console commands expected"); }
                };
            }
            @Override public Audience broadcast() { return Audience.empty(); }
            @Override public NamelessPlayer player(UUID uuid) { return null; }
            @Override public NamelessPlayer playerByUsername(String username) { return null; }
            @Override public Collection<NamelessPlayer> onlinePlayers() { return List.of(); }
        });
        return plugin;
    }

    NamelessPlayer player(NamelessPlugin plugin, CapturedMessages messages, boolean canInspectOthers) {
        return new NamelessPlayer(plugin.config(), messages, UUID.randomUUID(), "FixturePlayer") {
            @Override public boolean hasPermission(Permission permission) {
                return permission != Permission.COMMAND_USER_INFO_OTHERS || canInspectOthers;
            }
        };
    }

    @Test void deniedOtherAccountLookupDoesNotQueueWorkOrSendHttp() throws Exception {
        try (Website website = new Website()) {
            Scheduler scheduler = new Scheduler();
            NamelessPlugin plugin = plugin(scheduler, website);
            CapturedMessages messages = new CapturedMessages();
            new UserInfoCommand(plugin).verifyPermissionThenExecute(player(plugin, messages, false), new String[]{"OtherCitizen"});
            assertEquals(0, scheduler.background.size(), "permission denial must happen before any API continuation");
            assertEquals(0, website.requests.get());
            assertFalse(messages.messages.isEmpty(), "the player should receive the permission denial");
        }
    }

    @Test void anExplicitSelfNameStillNeedsOtherLookupPermission() throws Exception {
        try (Website website = new Website()) {
            Scheduler scheduler = new Scheduler();
            NamelessPlugin plugin = plugin(scheduler, website);
            CapturedMessages messages = new CapturedMessages();
            new UserInfoCommand(plugin).verifyPermissionThenExecute(player(plugin, messages, false), new String[]{"FixturePlayer"});
            assertEquals(0, scheduler.background.size());
            assertEquals(0, website.requests.get());
        }
    }

    @Test void selfViewKeepsPublicProfileFieldsAndHidesPrivateStaffNotes() throws Exception {
        try (Website website = new Website()) {
            Scheduler scheduler = new Scheduler();
            NamelessPlugin plugin = plugin(scheduler, website);
            plugin.apiProvider().load();
            scheduler.drainBackground();
            CapturedMessages messages = new CapturedMessages();
            new UserInfoCommand(plugin).verifyPermissionThenExecute(player(plugin, messages, false), new String[0]);
            assertEquals(1, scheduler.background.size(), "self lookup must remain asynchronous and available");
            scheduler.drainBackground();
            int requestsAfterWorker = website.requests.get();
            scheduler.drainCallbacks();
            assertEquals(requestsAfterWorker, website.requests.get(), "Minecraft message delivery must not resolve more API fields");
            assertTrue(messages.joined().contains("PUBLIC_BIO_MARKER"), messages.joined());
            assertFalse(messages.joined().contains("PRIVATE_STAFF_NOTE_MARKER"), messages.joined());
        }
    }

    @Test void explicitStaffPermissionAllowsTheDetailedNamedAccountView() throws Exception {
        try (Website website = new Website()) {
            Scheduler scheduler = new Scheduler();
            NamelessPlugin plugin = plugin(scheduler, website);
            plugin.apiProvider().load();
            scheduler.drainBackground();
            CapturedMessages messages = new CapturedMessages();
            new UserInfoCommand(plugin).verifyPermissionThenExecute(player(plugin, messages, true), new String[]{"OtherCitizen"});
            assertEquals(1, scheduler.background.size());
            scheduler.drainBackground();
            scheduler.drainCallbacks();
            assertTrue(messages.joined().contains("PUBLIC_BIO_MARKER"), messages.joined());
            assertTrue(messages.joined().contains("PRIVATE_STAFF_NOTE_MARKER"), messages.joined());
        }
    }

    @Test void ordinaryPlayersDoNotReceiveWebsiteUserNameCompletions() throws Exception {
        try (Website website = new Website()) {
            Scheduler scheduler = new Scheduler();
            NamelessPlugin plugin = plugin(scheduler, website);
            plugin.apiProvider().load();
            scheduler.drainBackground();
            plugin.userCache().load();
            scheduler.timer.run();
            scheduler.drainBackground();
            assertEquals(List.of("OtherCitizen"), plugin.userCache().usernames());
            UserInfoCommand command = new UserInfoCommand(plugin);
            assertTrue(command.complete(player(plugin, new CapturedMessages(), false), new String[]{"O"}).isEmpty());
            assertEquals(List.of("OtherCitizen"), command.complete(player(plugin, new CapturedMessages(), true), new String[]{"O"}));
        }
    }
}

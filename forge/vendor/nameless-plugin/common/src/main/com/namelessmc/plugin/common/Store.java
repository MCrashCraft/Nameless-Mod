package com.namelessmc.plugin.common;

import com.google.gson.JsonObject;
import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.java_api.modules.store.PendingCommandsResponse;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Forge Store delivery: durable receipts, verified native results and serialized polling. */
public class Store implements Reloadable {
    private static final int MAX_COMMANDS = 256;
    private static final long MAX_POLL_NANOS = Duration.ofMinutes(2).toNanos();
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern UUID_TEXT = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z_][A-Za-z0-9_]*\\}");
    private final NamelessPlugin plugin;
    private final AtomicReference<Poll> inFlight = new AtomicReference<>();
    private volatile Session active;

    private static final class Session {
        final int connectionId;
        final StoreDeliveryLedger ledger;
        final Set<Integer> warned = ConcurrentHashMap.newKeySet();
        final AtomicBoolean fetchedNotice = new AtomicBoolean();
        volatile AbstractScheduledTask timer;
        volatile boolean failed;
        Session(int connectionId, StoreDeliveryLedger ledger) { this.connectionId = connectionId; this.ledger = ledger; }
    }
    private static final class Poll {
        final Session session;
        final long started = System.nanoTime();
        Poll(Session session) { this.session = session; }
    }

    public Store(NamelessPlugin plugin) { this.plugin = plugin; }

    @Override public void unload() {
        Session previous = this.active;
        this.active = null;
        this.inFlight.set(null);
        if (previous != null && previous.timer != null) previous.timer.cancel();
    }

    @Override public void load() {
        this.unload();
        var config = this.plugin.config().modules().node("store");
        if (!config.node("command-executor", "enabled").getBoolean()) return;
        int connectionId = config.node("connection-id").getInt();
        Duration interval = ConfigurationHandler.getDuration(config.node("command-executor", "interval"));
        if (connectionId <= 0 || interval == null || interval.isZero() || interval.isNegative()
                || interval.compareTo(Duration.ofDays(1)) > 0) {
            this.plugin.logger().warning("Store requires a positive connection-id and a positive polling interval up to one day.");
            return;
        }
        try {
            Session session = new Session(connectionId, new StoreDeliveryLedger(this.plugin.config().dataDirectory(), connectionId));
            this.active = session;
            session.timer = this.plugin.scheduler().runTimer(this::retrievePendingCommands, interval);
        } catch (IOException failure) {
            this.plugin.logger().severe("Store receipts could not be loaded; delivery remains disabled for operator review.");
        }
    }

    private boolean current(Poll poll) {
        return this.active == poll.session && !poll.session.failed && this.inFlight.get() == poll;
    }
    private void release(Poll poll) { this.inFlight.compareAndSet(poll, null); }

    public void retrievePendingCommands() {
        Session session = this.active;
        if (session == null || session.failed) return;
        Poll previous = this.inFlight.get();
        if (previous != null) {
            if (System.nanoTime() - previous.started <= MAX_POLL_NANOS || !this.inFlight.compareAndSet(previous, null)) return;
            this.plugin.logger().warning("Store poll expired; durable receipts will prevent blind command replay.");
        }
        this.plugin.scheduler().runAsync(() -> {
            Poll poll = new Poll(session);
            if (this.active != session || session.failed || !this.inFlight.compareAndSet(null, poll)) return;
            try {
                NamelessAPI api = this.plugin.apiProvider().api();
                if (api == null || !this.current(poll)) { this.release(poll); return; }
                PendingCommandsResponse response = api.store().pendingCommands(session.connectionId);
                int count = 0;
                if (response.customers().size() > MAX_COMMANDS) throw new IOException("Store response limit");
                for (var customer : response.customers()) {
                    count += customer.pendingCommands().size();
                    if (count > MAX_COMMANDS) throw new IOException("Store response limit");
                }
                if (!this.current(poll)) { this.release(poll); return; }
                if (session.fetchedNotice.compareAndSet(false, true)) {
                    this.plugin.logger().info("Store connection " + session.connectionId + " fetched successfully; pending commands=" + count);
                }
                if (count == 0) { this.release(poll); return; }
                this.plugin.scheduler().runSync(() -> this.deliver(poll, api, response));
            } catch (IOException failure) {
                this.stopForReview(poll, "Store response or receipt limit requires operator review");
            } catch (NamelessException | RuntimeException failure) {
                if (this.current(poll)) this.plugin.logger().warning("Store poll failed for connection " + session.connectionId + "; no new commands were dispatched.");
                this.release(poll);
            }
        });
    }

    private void deliver(Poll poll, NamelessAPI api, PendingCommandsResponse response) {
        if (!this.current(poll)) { this.release(poll); return; }
        Session session = poll.session;
        Map<Integer, PendingCommandsResponse.PendingCommand> acknowledge = new LinkedHashMap<>();
        Map<Integer, String> fingerprints = new HashMap<>();
        try {
            for (var customer : response.customers()) {
                for (var command : customer.pendingCommands()) {
                    if (!this.current(poll)) { this.release(poll); return; }
                    String fingerprint = fingerprint(session.connectionId, customer, command);
                    StoreDeliveryLedger.State state = session.ledger.state(command.id(), fingerprint);
                    if (state == StoreDeliveryLedger.State.EXECUTED || state == StoreDeliveryLedger.State.ACKNOWLEDGED) {
                        acknowledge.put(command.id(), command);
                        fingerprints.put(command.id(), fingerprint);
                        continue;
                    }
                    if (state != null) {
                        this.warnHeld(session, command.id());
                        break;
                    }
                    String prepared;
                    try {
                        prepared = prepareCommand(this.plugin, response.shouldUseUuids(), customer, command);
                    } catch (IllegalArgumentException invalid) {
                        if (session.ledger.prepare(command.id(), fingerprint)) session.ledger.failed(command.id(), fingerprint);
                        this.warnHeld(session, command.id());
                        break;
                    }
                    if (prepared == null) break; // Preserve this customer's order until its online requirement is met.
                    if (!session.ledger.prepare(command.id(), fingerprint)) break;
                    boolean success;
                    try {
                        success = this.plugin.audiences().console().dispatchStoreCommand(prepared);
                    } catch (RuntimeException failed) {
                        success = false;
                    }
                    if (!success) {
                        session.ledger.failed(command.id(), fingerprint);
                        this.warnHeld(session, command.id());
                        break;
                    }
                    session.ledger.executed(command.id(), fingerprint);
                    acknowledge.put(command.id(), command);
                    fingerprints.put(command.id(), fingerprint);
                }
            }
        } catch (IOException | RuntimeException failure) {
            // PREPARED remains durable if execution or its receipt became ambiguous.
            this.stopForReview(poll, "Store delivery receipts require operator review; pending commands were not blindly retried");
            return;
        }
        if (acknowledge.isEmpty()) { this.release(poll); return; }
        this.plugin.scheduler().runAsync(() -> {
            try {
                if (!this.current(poll)) return;
                api.store().markCommandsExecuted(acknowledge.values());
                if (this.current(poll)) {
                    for (int id : acknowledge.keySet()) session.ledger.acknowledged(id, fingerprints.get(id));
                }
            } catch (IOException failure) {
                this.stopForReview(poll, "Store acknowledgement receipt could not be persisted; operator review required");
            } catch (NamelessException | RuntimeException failure) {
                if (this.current(poll)) this.plugin.logger().warning("Store acknowledgement failed for connection " + session.connectionId
                        + "; successful commands remain recorded and will not be dispatched again.");
            } finally {
                this.release(poll);
            }
        });
    }

    private void warnHeld(Session session, int id) {
        if (session.warned.add(id)) this.plugin.logger().warning("Store command ID " + id
                + " is held for operator review; keep its receipt and do not blindly retry it.");
    }
    private void stopForReview(Poll poll, String message) {
        poll.session.failed = true;
        if (poll.session.timer != null) poll.session.timer.cancel();
        if (this.active == poll.session) this.plugin.logger().severe(message + " (connection " + poll.session.connectionId + ").");
        this.release(poll);
    }

    static String prepareCommand(NamelessPlugin plugin, boolean useUuids,
                                 PendingCommandsResponse.PendingCommandsCustomer customer,
                                 PendingCommandsResponse.PendingCommand pending) {
        String command = pending.command();
        if (command == null || command.isBlank() || command.length() > 4096 || command.indexOf('\n') >= 0
                || command.indexOf('\r') >= 0 || command.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid Store command");
        UUID supplied = null;
        if (customer.identifier() != null) {
            if (!UUID_TEXT.matcher(customer.identifier()).matches()) throw new IllegalArgumentException("Invalid Store UUID");
            supplied = UUID.fromString(customer.identifier());
        }
        if (useUuids && supplied == null) throw new IllegalArgumentException("Missing Store UUID binding");
        NamelessPlayer player = useUuids ? plugin.audiences().player(supplied) : plugin.audiences().playerByUsername(customer.username());
        if (pending.isOnlineRequired() && player == null) return null;
        String username = player != null ? player.username() : customer.username();
        if (username == null || !USERNAME.matcher(username).matches()) throw new IllegalArgumentException("Invalid Minecraft username");
        UUID uuid = player != null ? player.permissionUuid() : supplied;
        if (command.contains("{uuid}")) {
            if (uuid == null) throw new IllegalArgumentException("Missing Store UUID placeholder binding");
            command = command.replace("{uuid}", uuid.toString());
        }
        command = command.replace("{username}", username);
        if (PLACEHOLDER.matcher(command).find()) throw new IllegalArgumentException("Unresolved Store placeholder");
        return command;
    }

    static String fingerprint(int connection, PendingCommandsResponse.PendingCommandsCustomer customer,
                              PendingCommandsResponse.PendingCommand command) {
        JsonObject value = new JsonObject();
        value.addProperty("connection", connection);
        value.addProperty("customer", customer.id());
        value.addProperty("username", customer.username());
        value.addProperty("identifier", customer.identifier());
        value.addProperty("order", command.orderId());
        value.addProperty("command", command.command());
        value.addProperty("online", command.isOnlineRequired());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

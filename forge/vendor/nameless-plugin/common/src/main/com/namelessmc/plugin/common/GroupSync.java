package com.namelessmc.plugin.common;

import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.exception.ApiError;
import com.namelessmc.java_api.exception.ApiException;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Forge port: bounded async sends, concurrent retry state and reload generations. */
public class GroupSync implements Reloadable {
    private final ConcurrentMap<UUID, Set<String>> playerGroups = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<String>> latestGroups = new ConcurrentHashMap<>();
    private final AtomicBoolean sending = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final Object stateLock = new Object();
    private final NamelessPlugin plugin;
    private volatile @Nullable AbstractScheduledTask task;
    private volatile boolean versionWarningLogged;

    GroupSync(NamelessPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void unload() {
        AbstractScheduledTask previous;
        synchronized (this.stateLock) {
            this.generation.incrementAndGet();
            previous = this.task;
            this.task = null;
            this.playerGroups.clear();
            this.latestGroups.clear();
            this.versionWarningLogged = false;
        }
        if (previous != null) previous.cancel();
    }

    @Override
    public void load() {
        this.unload();
        final long epoch = this.generation.get();
        ConfigurationNode config = this.plugin.config().main();
        if (!config.node("group-sync", "enabled").getBoolean()) return;
        final int serverId = config.node("api", "server-id").getInt(0);
        if (serverId <= 0) {
            this.plugin.logger().warning("Group sync requires a positive api.server-id in main.yaml.");
            return;
        }
        if (this.plugin.permissions() == null) {
            this.plugin.logger().warning("Group sync requires a usable permissions adapter such as LuckPerms.");
            return;
        }
        final Duration interval = ConfigurationHandler.getDuration(config.node("group-sync", "interval"));
        if (interval == null || interval.isZero() || interval.isNegative()) {
            this.plugin.logger().warning("Invalid group-sync interval; group sync remains disabled.");
            return;
        }
        // Keep the timer alive during website outages. Each failed send requeues its
        // snapshot, while ApiProvider independently limits connection retries.
        this.task = this.plugin.scheduler().runTimer(() -> this.syncGroups(epoch, serverId), interval);
    }

    private void syncGroups(long epoch, int serverId) {
        if (this.generation.get() != epoch) return;
        AbstractPermissions permissions = this.plugin.permissions();
        if (permissions == null) return;
        Map<UUID, Set<String>> groupsToSend = new HashMap<>();
        Set<UUID> online = new HashSet<>();
        for (NamelessPlayer player : this.plugin.audiences().onlinePlayers()) {
            online.add(player.uuid());
            Set<String> groups = permissions.getPlayerGroups(player);
            if (groups == null) continue;
            Set<String> snapshot = Set.copyOf(groups);
            this.latestGroups.put(player.uuid(), snapshot);
            Set<String> previous = this.playerGroups.get(player.uuid());
            if (!snapshot.equals(previous)) groupsToSend.put(player.uuid(), snapshot);
        }
        // Do not retain every UUID that has ever visited the server.
        this.playerGroups.keySet().retainAll(online);
        this.latestGroups.keySet().retainAll(online);
        if (groupsToSend.isEmpty()) return;
        final Map<UUID, Set<String>> pending = Map.copyOf(groupsToSend);
        this.plugin.scheduler().runAsync(() -> {
            if (this.generation.get() != epoch) return;
            // Acquire inside the worker: a task dropped by a full bounded queue
            // must not leave an in-flight flag or a falsely acknowledged cache.
            if (!this.sending.compareAndSet(false, true)) return;
            try {
                if (this.generation.get() != epoch) return;
                Map<UUID, Set<String>> fresh = new HashMap<>();
                pending.forEach((uuid, groups) -> {
                    if (groups.equals(this.latestGroups.get(uuid)) && !groups.equals(this.playerGroups.get(uuid))) {
                        fresh.put(uuid, groups);
                    }
                });
                if (fresh.isEmpty()) return;
                NamelessAPI api = this.plugin.apiProvider().api();
                if (api == null) return;
                if (api.website().parsedVersion().minor() < 1) {
                    if (!this.versionWarningLogged) {
                        this.plugin.logger().warning("Group sync requires NamelessMC 2.1.0 or newer.");
                        this.versionWarningLogged = true;
                    }
                    this.requeue(epoch, pending);
                    return;
                }
                api.sendMinecraftGroups(serverId, fresh);
                this.acknowledge(epoch, fresh);
            } catch (NamelessException e) {
                this.plugin.logger().warning("Unable to send Minecraft groups; changed groups will be retried.");
                if (e instanceof ApiException && ((ApiException) e).apiError() == ApiError.CORE_INVALID_SERVER_ID) {
                    this.plugin.logger().warning("Check api.server-id and the selected group-sync server in NamelessMC StaffCP.");
                } else {
                    this.plugin.logger().logException(e);
                }
                this.requeue(epoch, pending);
            } finally {
                this.sending.set(false);
            }
        });
    }

    void acknowledge(long epoch, Map<UUID, Set<String>> sent) {
        synchronized (this.stateLock) {
            if (this.generation.get() != epoch) return;
            sent.forEach((uuid, groups) -> {
                if (groups.equals(this.latestGroups.get(uuid))) this.playerGroups.put(uuid, groups);
            });
        }
    }

    void requeue(long epoch, Map<UUID, Set<String>> pending) {
        synchronized (this.stateLock) {
            if (this.generation.get() != epoch) return;
            // An older failed send must not erase a newer group change or reload.
            pending.forEach((uuid, groups) -> this.playerGroups.remove(uuid, groups));
        }
    }

    public void resetGroups(NamelessPlayer player) {
        this.playerGroups.remove(player.uuid());
    }
}

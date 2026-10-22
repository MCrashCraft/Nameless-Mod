package com.namelessmc.plugin.common;

import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.NamelessUser;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Forge port: immutable publication and reload-isolated asynchronous website cache. */
public class UserCache implements Reloadable {
    private final NamelessPlugin plugin;
    private volatile @Nullable AbstractScheduledTask task;
    private volatile Generation generation = new Generation();

    private static final class Snapshot {
        final List<String> usernames;
        final List<String> minecraftUsernames;
        Snapshot(List<String> usernames, List<String> minecraftUsernames) {
            this.usernames = List.copyOf(usernames);
            this.minecraftUsernames = List.copyOf(minecraftUsernames);
        }
    }

    private static final class Generation {
        volatile Snapshot snapshot = new Snapshot(List.of(), List.of());
    }

    UserCache(NamelessPlugin plugin) { this.plugin = plugin; }

    @Override public void unload() {
        // Old workers only hold the previous generation, so cannot repopulate this one.
        this.generation = new Generation();
        AbstractScheduledTask previous = this.task;
        this.task = null;
        if (previous != null) previous.cancel();
    }

    @Override public void load() {
        this.unload();
        Generation current = this.generation;
        this.task = this.plugin.scheduler().runTimer(() -> this.update(current), Duration.ofMinutes(5));
    }

    private void update(Generation current) {
        if (this.generation != current) return;
        this.plugin.scheduler().runAsync(() -> {
            if (this.generation != current) return;
            NamelessAPI api = this.plugin.apiProvider().api();
            if (api == null) return;
            try {
                List<NamelessUser> users = api.users().makeRequest();
                List<String> usernames = new ArrayList<>(users.size());
                List<String> minecraftUsernames = new ArrayList<>(users.size());
                for (NamelessUser user : users) {
                    usernames.add(user.username());
                    String minecraftUsername = user.minecraftUsername();
                    if (minecraftUsername != null) minecraftUsernames.add(minecraftUsername);
                }
                if (this.generation == current) {
                    current.snapshot = new Snapshot(usernames, minecraftUsernames);
                }
            } catch (NamelessException e) {
                this.plugin.logger().logException(e);
            }
        });
    }

    private List<String> search(List<String> original, @Nullable String part) {
        if (part == null) return List.of();
        return original.stream().filter(value -> value.startsWith(part)).collect(Collectors.toUnmodifiableList());
    }

    public List<String> usernames() { return this.generation.snapshot.usernames; }
    public List<String> usernamesSearch(@Nullable String part) { return search(this.usernames(), part); }
    public List<String> minecraftUsernames() { return this.generation.snapshot.minecraftUsernames; }
    public List<String> minecraftUsernamesSearch(@Nullable String part) { return search(this.minecraftUsernames(), part); }
}

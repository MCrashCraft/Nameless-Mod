package com.namelessmc.plugin.common;

import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import net.kyori.adventure.audience.Audience;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.query.QueryOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Test the adapter contract without a global LuckPerms provider or website access. */
class LuckPermsPermissionsTest {
    @TempDir Path temporary;

    static <T> T stub(Class<T> type, BiFunction<Method, Object[], Object> implementation) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Stub" + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == arguments[0];
                    default -> null;
                };
            }
            return implementation.apply(method, arguments == null ? new Object[0] : arguments);
        });
        return type.cast(value);
    }

    NamelessPlayer player(boolean offline, UUID actual) throws Exception {
        ConfigurationHandler config = new ConfigurationHandler(temporary.resolve(offline ? "offline" : "online"));
        config.load();
        config.main().node("api", "offline-uuids").set(offline);
        return new NamelessPlayer(config, Audience.empty(), actual, "FixturePlayer") {
            @Override public boolean hasPermission(Permission permission) { return false; }
        };
    }

    static Group group(String name) {
        return stub(Group.class, (method, args) -> {
            if (method.getName().equals("getName")) return name;
            throw new AssertionError("Unexpected Group access: " + method.getName());
        });
    }

    static LuckPermsPermissions adapter(LuckPerms provider) throws Exception {
        LuckPermsPermissions result = new LuckPermsPermissions();
        Field field = LuckPermsPermissions.class.getDeclaredField("api");
        field.setAccessible(true);
        field.set(result, provider);
        return result;
    }

    @Test void groupSyncUsesTheActualPermissionUuidAndEffectiveContextualInheritance() throws Exception {
        UUID actual = UUID.randomUUID();
        NamelessPlayer player = player(true, actual);
        assertEquals(actual, player.permissionUuid());
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:FixturePlayer".getBytes(StandardCharsets.UTF_8)), player.uuid());
        assertNotEquals(player.uuid(), player.permissionUuid(), "website identity compatibility must not change LuckPerms identity");
        QueryOptions contextual = stub(QueryOptions.class, (method, args) -> { throw new AssertionError("Options should only be passed to the provider"); });
        AtomicInteger rawNodeReads = new AtomicInteger();
        AtomicReference<QueryOptions> resolvedWith = new AtomicReference<>();
        User user = stub(User.class, (method, args) -> {
            if (method.getName().equals("getInheritedGroups")) {
                resolvedWith.set((QueryOptions) args[0]);
                // The resolver excludes false, expired or out-of-context staff nodes.
                return List.of(group("member"), group("inherited-citizen"));
            }
            if (method.getName().equals("getNodes")) {
                rawNodeReads.incrementAndGet();
                throw new AssertionError("Raw nodes can contain denied/expired/out-of-context staff groups");
            }
            throw new AssertionError("Unexpected User access: " + method.getName());
        });
        ContextManager contexts = stub(ContextManager.class, (method, args) -> {
            if (method.getName().equals("getQueryOptions")) {
                assertSame(user, args[0]);
                return Optional.of(contextual);
            }
            throw new AssertionError("A live user's context must not fall back to static or non-contextual options");
        });
        UserManager users = stub(UserManager.class, (method, args) -> {
            if (method.getName().equals("getUser")) {
                assertEquals(actual, args[0], "must use the real online UUID, not the website's offline UUID");
                return user;
            }
            throw new AssertionError("Unexpected UserManager access");
        });
        LuckPerms provider = stub(LuckPerms.class, (method, args) -> switch (method.getName()) {
            case "getUserManager" -> users;
            case "getContextManager" -> contexts;
            default -> throw new AssertionError("Unexpected provider access: " + method.getName());
        });
        Set<String> effective = adapter(provider).getPlayerGroups(player);
        assertEquals(Set.of("member", "inherited-citizen"), effective);
        assertSame(contextual, resolvedWith.get());
        assertEquals(0, rawNodeReads.get());
        assertFalse(effective.contains("staff"));
    }

    @Test void usersWithoutLiveContextUseStaticQueryOptionsInsteadOfIgnoringContexts() throws Exception {
        UUID actual = UUID.randomUUID();
        NamelessPlayer player = player(false, actual);
        assertEquals(actual, player.uuid());
        assertEquals(actual, player.permissionUuid());
        QueryOptions staticOptions = stub(QueryOptions.class, (method, args) -> { throw new AssertionError("Unexpected QueryOptions access"); });
        AtomicReference<QueryOptions> resolvedWith = new AtomicReference<>();
        User user = stub(User.class, (method, args) -> {
            if (method.getName().equals("getInheritedGroups")) {
                resolvedWith.set((QueryOptions) args[0]);
                return List.of(group("default"));
            }
            throw new AssertionError("Must use effective groups, not raw nodes");
        });
        UserManager users = stub(UserManager.class, (method, args) -> user);
        ContextManager contexts = stub(ContextManager.class, (method, args) -> switch (method.getName()) {
            case "getQueryOptions" -> Optional.empty();
            case "getStaticQueryOptions" -> staticOptions;
            default -> throw new AssertionError("Unexpected context access");
        });
        LuckPerms provider = stub(LuckPerms.class, (method, args) -> switch (method.getName()) {
            case "getUserManager" -> users;
            case "getContextManager" -> contexts;
            default -> throw new AssertionError("Unexpected provider access");
        });
        assertEquals(Set.of("default"), adapter(provider).getPlayerGroups(player));
        assertSame(staticOptions, resolvedWith.get());
    }

    @Test void missingLoadedUsersAreSkippedAndUnloadedProvidersCannotSupplyGroups() throws Exception {
        UserManager users = stub(UserManager.class, (method, args) -> null);
        LuckPerms provider = stub(LuckPerms.class, (method, args) -> {
            if (method.getName().equals("getUserManager")) return users;
            throw new AssertionError("Context resolution must not run without a loaded user");
        });
        LuckPermsPermissions permissions = adapter(provider);
        NamelessPlayer player = player(false, UUID.randomUUID());
        assertTrue(permissions.isUsable());
        assertNull(permissions.getPlayerGroups(player));
        permissions.unload();
        assertFalse(permissions.isUsable());
        assertThrows(IllegalStateException.class, () -> permissions.getPlayerGroups(player));
    }
}

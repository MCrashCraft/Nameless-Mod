/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.util.Tristate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.fml.ModList;

/** Separate staff and self-service permissions, with explicit LuckPerms denies honored. */
public final class ExchangePermissions {
    public static boolean use(CommandSourceStack source) {
        return source.getEntity() instanceof net.minecraft.server.level.ServerPlayer
                && permission(source, "namelessexchange.use", true);
    }

    public static boolean admin(CommandSourceStack source) {
        return permission(source, "namelessexchange.admin", source.hasPermission(2));
    }

    private static boolean permission(CommandSourceStack source, String node, boolean fallback) {
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return fallback;
        if (!ModList.get().isLoaded("luckperms")) return fallback;
        try {
            return LuckPermsBridge.check(player.getUUID(), node, fallback);
        } catch (IllegalStateException | LinkageError unavailable) {
            // An installed but unavailable permission provider must not silently bypass a deny.
            return false;
        }
    }

    private static final class LuckPermsBridge {
        static boolean check(java.util.UUID uuid, String node, boolean fallback) {
            var user = LuckPermsProvider.get().getUserManager().getUser(uuid);
            if (user == null) return false;
            var value = user.getCachedData().getPermissionData().checkPermission(node);
            return value == Tristate.UNDEFINED ? fallback : value == Tristate.TRUE;
        }
    }
    private ExchangePermissions() { }
}

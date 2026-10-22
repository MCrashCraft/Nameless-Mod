/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.namelessmc.plugin.common.Permission;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Immutable identity/permission fallback captured on the server thread. */
public final class ForgePlayer extends NamelessPlayer {
    private final UUID actualUuid;
    private final boolean operator;

    ForgePlayer(ForgeAudienceProvider audiences, ServerPlayer player) {
        super(audiences.plugin().config(), audiences.playerAudience(player),
                player.getUUID(), MinecraftCompatibility.playerName(player));
        actualUuid = player.getUUID();
        operator = MinecraftCompatibility.hasPermission(player.createCommandSourceStack(), 2);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        boolean fallback = fallbackPermission(permission, operator);
        try {
            return LuckPermsBridge.hasPermission(actualUuid, permission.toString(), fallback);
        } catch (IllegalStateException | LinkageError unavailable) {
            return fallback;
        }
    }

    static boolean fallbackPermission(Permission permission, boolean operator) {
        return switch (permission) {
            case COMMAND_GET_NOTIFICATIONS, COMMAND_REGISTER, COMMAND_REPORT,
                 COMMAND_STORE_VIEW_CREDITS, COMMAND_SUGGEST, COMMAND_USER_INFO, COMMAND_VERIFY -> true;
            default -> operator;
        };
    }

    private static final class LuckPermsBridge {
        private static boolean hasPermission(UUID uuid, String permission, boolean fallback) {
            User user = LuckPermsProvider.get().getUserManager().getUser(uuid);
            if (user == null) {
                return fallback;
            }
            Tristate result = user.getCachedData().getPermissionData().checkPermission(permission);
            return result == Tristate.UNDEFINED ? fallback : result == Tristate.TRUE;
        }
    }
}

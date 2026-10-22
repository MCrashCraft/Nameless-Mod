/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.namelessmc.plugin.common.AbstractDataSender;
import com.namelessmc.plugin.common.NamelessPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

public final class FabricDataSender extends AbstractDataSender {
    private final MinecraftServer server;
    public FabricDataSender(NamelessPlugin plugin, MinecraftServer server) { super(plugin); this.server = server; }
    @Override protected void registerCustomProviders() {
        registerGlobalInfoProvider(json -> {
            json.addProperty("platform", FabricLoader.getInstance().isModLoaded("quilt_loader") ? "Quilt" : "Fabric");
            json.addProperty("version", MinecraftCompatibility.minecraftVersion());
            json.addProperty("loader_version", FabricLoader.getInstance().getModContainer("fabricloader")
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
            json.addProperty("max_players", server.getMaxPlayers());
            json.addProperty("online_players", getPlugin().audiences().onlinePlayers().size());
            json.addProperty("motd", server.getMotd());
        });
    }
}

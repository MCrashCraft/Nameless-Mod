package dev.mcrashcraft.namelessforge;

import com.namelessmc.plugin.common.AbstractDataSender;
import com.namelessmc.plugin.common.NamelessPlugin;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.versions.forge.ForgeVersion;

public final class ForgeDataSender extends AbstractDataSender {
    private final MinecraftServer server;
    public ForgeDataSender(NamelessPlugin plugin, MinecraftServer server) {
        super(plugin);
        this.server = server;
    }

    @Override protected void registerCustomProviders() {
        registerGlobalInfoProvider(json -> {
            json.addProperty("platform", "Forge");
            json.addProperty("version", MinecraftCompatibility.minecraftVersion());
            json.addProperty("forge_version", ForgeVersion.getVersion());
            json.addProperty("max_players", server.getMaxPlayers());
            json.addProperty("online_players", getPlugin().audiences().onlinePlayers().size());
            json.addProperty("motd", server.getMotd());
        });
    }
}

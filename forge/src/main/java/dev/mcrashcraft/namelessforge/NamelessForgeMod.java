package dev.mcrashcraft.namelessforge;

import com.mojang.brigadier.CommandDispatcher;
import com.namelessmc.plugin.common.Metrics;
import com.namelessmc.plugin.common.NamelessPlugin;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.event.NamelessJoinEvent;
import com.namelessmc.plugin.common.event.NamelessPlayerQuitEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Unofficial native platform adapter; it registers no blocks, items or network channels. */
@Mod(NamelessForgeMod.MOD_ID)
public final class NamelessForgeMod {
    public static final String MOD_ID = "namelessforge";
    private static final Logger LOGGER = LoggerFactory.getLogger("NamelessForge");
    private MinecraftServer server;
    private NamelessPlugin plugin;
    private ForgeScheduler scheduler;
    private ForgeAudienceProvider audiences;
    private ForgeCommands commands;
    private boolean loaded;
    private int snapshotTicks;

    public NamelessForgeMod() {
        // Physical clients can load the JAR harmlessly, but do not start the integration.
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(this::registerCommands);
        bus.addListener(this::started);
        bus.addListener(this::stopping);
        bus.addListener(this::joined);
        bus.addListener(this::left);
        bus.addListener(this::tick);
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        scheduler = new ForgeScheduler(server, error ->
                LOGGER.warn("Nameless background task failed ({}); use /nlpl last_error for API diagnostics",
                        error.getClass().getSimpleName()));
        try {
            plugin = new NamelessPlugin(FMLPaths.CONFIGDIR.get().resolve(MOD_ID), scheduler,
                    config -> new ForgeLogger(config, LOGGER),
                    FMLPaths.GAMEDIR.get().resolve("logs/latest.log"), "forge", "1.20.1");
            // Upstream's telemetry is unconditional. This unofficial adapter never enables it.
            plugin.unregisterReloadable(Metrics.class);
            audiences = new ForgeAudienceProvider(server, plugin);
            plugin.setAudienceProvider(audiences);
            plugin.registerReloadable(new ForgeDataSender(plugin, server));
            plugin.load();
            loaded = true;
            audiences.refreshPlayers();
            commands = new ForgeCommands(plugin, audiences, this::reload);
            commands.register(server.getCommands().getDispatcher());
            LOGGER.info("Nameless Forge 0.1.1 initialized; website configured={}, permissions={}",
                    configured(), plugin.permissions() != null ? "LuckPerms" : "vanilla operator fallback");
        } catch (RuntimeException | LinkageError error) {
            loaded = false;
            if (plugin != null) {
                plugin.logger().logException(error);
                try { plugin.unload(); } catch (RuntimeException ignored) { }
            }
            if (audiences != null) audiences.close();
            if (scheduler != null) scheduler.close();
            // A broken optional integration must not crash the Minecraft world or reveal credentials.
            LOGGER.error("Nameless Forge initialization failed ({}). Check config/namelessforge and installed dependencies.",
                    error.getClass().getSimpleName());
        }
    }

    private boolean configured() {
        if (plugin == null) return false;
        try {
            String url = plugin.config().main().node("api", "url").getString("");
            String key = plugin.config().main().node("api", "key").getString("");
            return !url.isBlank() && !key.isBlank();
        } catch (RuntimeException ignored) { return false; }
    }

    private void reload() {
        if (server == null || plugin == null || scheduler == null) return;
        if (!server.isSameThread()) {
            server.execute(this::reload);
            return;
        }
        loaded = false;
        try {
            plugin.unload();
            scheduler.nextGeneration();
            plugin.load();
            audiences.refreshPlayers();
            loaded = true;
            LOGGER.info("Nameless Forge configuration reloaded; website configured={}", configured());
        } catch (RuntimeException error) {
            plugin.logger().logException(error);
            try { plugin.unload(); } catch (RuntimeException ignored) { }
            scheduler.nextGeneration();
            LOGGER.error("Nameless Forge reload failed ({}). Correct its configuration and reload again.",
                    error.getClass().getSimpleName());
            throw error;
        }
    }

    private void registerCommands(RegisterCommandsEvent event) {
        registerStatus(event.getDispatcher());
        if (commands != null && loaded) commands.register(event.getDispatcher());
    }

    private void registerStatus(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("namelessforge:status")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    String state = !loaded ? "not initialized" : !configured() ? "not configured"
                            : plugin.apiProvider().apiIfCached() != null ? "connected" : "connecting or unavailable";
                    context.getSource().sendSuccess(() -> Component.literal("Nameless Forge: " + state
                            + "; LuckPerms: " + (loaded && plugin.permissions() != null ? "available" : "unavailable")
                            + ". Configuration: config/namelessforge/main.yaml"), false);
                    return 1;
                });
        dispatcher.register(root);
    }

    private void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!loaded || event.getEntity().getServer() != server) return;
        audiences.refreshPlayers();
        NamelessPlayer player = audiences.playerByUsername(event.getEntity().getGameProfile().getName());
        if (player != null) plugin.events().post(new NamelessJoinEvent(player));
    }

    private void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!loaded || event.getEntity().getServer() != server) return;
        NamelessPlayer player = audiences.playerByUsername(event.getEntity().getGameProfile().getName());
        if (player != null) plugin.events().post(new NamelessPlayerQuitEvent(player.uuid()));
        audiences.removePlayer(event.getEntity().getUUID());
        // Forge may fire the logout event before removal from PlayerList completes.
        server.execute(() -> { if (loaded) audiences.refreshPlayers(); });
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !loaded) return;
        if (++snapshotTicks >= 20) {
            snapshotTicks = 0;
            audiences.refreshPlayers();
        }
    }

    private void stopping(ServerStoppingEvent event) {
        if (server != event.getServer()) return;
        loaded = false;
        try { if (plugin != null) plugin.unload(); }
        catch (RuntimeException error) { LOGGER.warn("Nameless shutdown cleanup: {}", error.getClass().getSimpleName()); }
        finally {
            if (audiences != null) audiences.close();
            if (scheduler != null) scheduler.close();
            commands = null;
            audiences = null;
            plugin = null;
            scheduler = null;
            server = null;
        }
    }
}

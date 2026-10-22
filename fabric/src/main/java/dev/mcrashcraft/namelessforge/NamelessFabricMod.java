/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.mojang.brigadier.CommandDispatcher;
import com.namelessmc.plugin.common.Metrics;
import com.namelessmc.plugin.common.NamelessPlugin;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.event.NamelessJoinEvent;
import com.namelessmc.plugin.common.event.NamelessPlayerQuitEvent;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native dedicated-server entrypoint. The website core is shared with Forge/NeoForge. */
public final class NamelessFabricMod implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NamelessMC");
    private MinecraftServer server;
    private NamelessPlugin plugin;
    private ForgeScheduler scheduler;
    private ForgeAudienceProvider audiences;
    private ForgeCommands commands;
    private boolean loaded;
    private int snapshotTicks;

    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(this::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::stopping);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, activeServer) -> joined(handler.player, activeServer));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, activeServer) -> left(handler.player, activeServer));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }
    private void started(MinecraftServer activeServer) {
        server = activeServer;
        scheduler = new ForgeScheduler(server, error -> LOGGER.warn("Nameless background task failed ({}); use /nlpl last_error for API diagnostics", error.getClass().getSimpleName()));
        try {
            plugin = new NamelessPlugin(FabricLoader.getInstance().getConfigDir().resolve("namelessforge"), scheduler,
                    config -> new ForgeLogger(config, LOGGER), FabricLoader.getInstance().getGameDir().resolve("logs/latest.log"),
                    FabricLoader.getInstance().isModLoaded("quilt_loader") ? "quilt" : "fabric", MinecraftCompatibility.minecraftVersion());
            plugin.unregisterReloadable(Metrics.class);
            audiences = new ForgeAudienceProvider(server, plugin);
            plugin.setAudienceProvider(audiences);
            plugin.registerReloadable(new FabricDataSender(plugin, server));
            plugin.load();
            loaded = true;
            audiences.refreshPlayers();
            commands = new ForgeCommands(plugin, audiences, this::reload);
            commands.register(server.getCommands().getDispatcher());
            LOGGER.info("NamelessMC 0.2.0 initialized on Fabric-compatible loader; Minecraft={}, website configured={}, permissions={}",
                    MinecraftCompatibility.minecraftVersion(), configured(), plugin.permissions() != null ? "LuckPerms" : "vanilla operator fallback");
        } catch (RuntimeException | LinkageError error) {
            loaded = false;
            if (plugin != null) { plugin.logger().logException(error); try { plugin.unload(); } catch (RuntimeException ignored) { } }
            if (audiences != null) audiences.close();
            if (scheduler != null) scheduler.close();
            LOGGER.error("NamelessMC initialization failed ({}). Check private configuration and dependencies.", error.getClass().getSimpleName());
        }
    }
    private boolean configured() {
        if (plugin == null) return false;
        try { return !plugin.config().main().node("api", "url").getString("").isBlank()
                && !plugin.config().main().node("api", "key").getString("").isBlank(); }
        catch (RuntimeException ignored) { return false; }
    }
    private void reload() {
        if (server == null || plugin == null || scheduler == null) return;
        if (!server.isSameThread()) { server.execute(this::reload); return; }
        loaded = false;
        try {
            plugin.unload(); scheduler.nextGeneration(); plugin.load(); audiences.refreshPlayers(); loaded = true;
            LOGGER.info("NamelessMC configuration reloaded; website configured={}", configured());
        } catch (RuntimeException error) {
            plugin.logger().logException(error);
            try { plugin.unload(); } catch (RuntimeException ignored) { }
            scheduler.nextGeneration();
            LOGGER.error("NamelessMC reload failed ({}). Correct configuration and reload again.", error.getClass().getSimpleName());
            throw error;
        }
    }
    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("namelessforge:status")
                .requires(source -> MinecraftCompatibility.hasPermission(source, 2)).executes(context -> {
                    String state = !loaded ? "not initialized" : !configured() ? "not configured"
                            : plugin.apiProvider().apiIfCached() != null ? "connected" : "connecting or unavailable";
                    context.getSource().sendSuccess(() -> Component.literal("NamelessMC: " + state
                            + "; LuckPerms: " + (loaded && plugin.permissions() != null ? "available" : "unavailable")
                            + ". Configuration: config/namelessforge/main.yaml"), false);
                    return 1;
                }));
        if (commands != null && loaded) commands.register(dispatcher);
    }
    private void joined(ServerPlayer joined, MinecraftServer activeServer) {
        if (!loaded || activeServer != server) return;
        audiences.refreshPlayers();
        NamelessPlayer player = audiences.playerByUsername(MinecraftCompatibility.playerName(joined));
        if (player != null) plugin.events().post(new NamelessJoinEvent(player));
    }
    private void left(ServerPlayer departed, MinecraftServer activeServer) {
        if (!loaded || activeServer != server) return;
        NamelessPlayer player = audiences.playerByUsername(MinecraftCompatibility.playerName(departed));
        if (player != null) plugin.events().post(new NamelessPlayerQuitEvent(player.uuid()));
        audiences.removePlayer(departed.getUUID());
        server.execute(() -> { if (loaded) audiences.refreshPlayers(); });
    }
    private void tick(MinecraftServer activeServer) {
        if (!loaded || activeServer != server) return;
        if (++snapshotTicks >= 20) { snapshotTicks = 0; audiences.refreshPlayers(); }
    }
    private void stopping(MinecraftServer activeServer) {
        if (server != activeServer) return;
        loaded = false;
        try { if (plugin != null) plugin.unload(); }
        catch (RuntimeException error) { LOGGER.warn("Nameless shutdown cleanup: {}", error.getClass().getSimpleName()); }
        finally {
            if (audiences != null) audiences.close();
            if (scheduler != null) scheduler.close();
            commands = null; audiences = null; plugin = null; scheduler = null; server = null;
        }
    }
}

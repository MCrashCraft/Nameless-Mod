/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessexchange;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mcrashcraft.namelessexchange.core.ExchangeApiClient;
import dev.mcrashcraft.namelessexchange.core.ExchangeJournal;
import dev.mcrashcraft.namelessexchange.core.ExchangeService;
import dev.mcrashcraft.namelessexchange.forge.ForgeCashWallet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Independent server-only addon: the NamelessMC core and original currency mod are unmodified. */
@Mod(NamelessExchangeMod.MOD_ID)
public final class NamelessExchangeMod {
    public static final String MOD_ID = "namelessexchange";
    public static final String CONFIG_NAME = "namelessexchange-common.toml";
    private static final Logger LOGGER = LoggerFactory.getLogger("NamelessCashExchange");
    private static final List<String> BILL_NAMES = List.of("one", "five", "ten", "twenty", "fifty", "hundred");
    private volatile MinecraftServer server;
    private volatile ExchangeApiClient api;
    private volatile boolean ready;
    private boolean currencyAvailable;
    private ThreadPoolExecutor workers;
    private ExchangeService service;

    public NamelessExchangeMod() {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ExchangeConfig.SPEC, CONFIG_NAME);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::configChanged);
        var events = MinecraftForge.EVENT_BUS;
        events.addListener(this::registerCommands);
        events.addListener(this::started);
        events.addListener(this::stopping);
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        try {
            currencyAvailable = BILL_NAMES.stream().allMatch(name -> ForgeRegistries.ITEMS.containsKey(
                    new ResourceLocation("justenoughdrugz", name + "_dollar_bill")));
            if (!currencyAvailable) throw new IllegalStateException("Required dollar bills are missing");
            var sequence = new AtomicLong();
            workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
                var thread = new Thread(task, "nameless-exchange-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            workers.allowCoreThreadTimeOut(true);
            var journal = new ExchangeJournal(FMLPaths.CONFIGDIR.get().resolve(MOD_ID));
            service = new ExchangeService(journal, () -> api, server::execute, workers,
                    this::enabled, message -> LOGGER.info("{}", message));
            loadConnection();
            ready = true;
            LOGGER.info("NamelessMC Cash Exchange 0.1.0 initialized; enabled={}, connection configured={}, held={}",
                    enabled(), api != null, service.heldCount());
        } catch (Exception | LinkageError error) {
            ready = false;
            LOGGER.error("Cash exchange initialization failed ({}); exchanges are disabled.", error.getClass().getSimpleName());
            if (workers != null) workers.shutdown();
        }
    }

    private boolean enabled() {
        return ready && server != null && !server.isStopped() && currencyAvailable && api != null
                && ExchangeConfig.ENABLED.get();
    }

    /** Reload copies only the connection settings in memory; never rewrite/export the private core file. */
    private void loadConnection() {
        if (service != null) service.invalidatePreflights();
        api = null;
        try {
            var path = FMLPaths.CONFIGDIR.get().resolve("namelessforge/main.yaml");
            if (!Files.isRegularFile(path) || Files.size(path) > 131_072) return;
            var config = YamlConfigurationLoader.builder().path(path).build().load();
            var url = config.node("api", "url").getString("");
            var key = config.node("api", "key").getString("");
            if (!url.isBlank() && !key.isBlank()) api = new ExchangeApiClient(url, key);
        } catch (Exception | LinkageError error) {
            LOGGER.warn("Cash exchange could not load the existing NamelessMC connection ({}).", error.getClass().getSimpleName());
        }
    }

    private void configChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() != ExchangeConfig.SPEC) return;
        var activeServer = server;
        if (activeServer != null && ready) activeServer.execute(() -> {
            if (service != null) service.invalidatePreflights();
            LOGGER.info("Cash exchange toggle updated; enabled={}", enabled());
        });
    }

    private void registerCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), "namelessexchange:exchange");
        if (event.getDispatcher().getRoot().getChild("exchange") == null) register(event.getDispatcher(), "exchange");
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        if (dispatcher.getRoot().getChild(name) != null) return;
        var root = Commands.literal(name)
                .requires(source -> ExchangePermissions.use(source) || ExchangePermissions.admin(source))
                .executes(context -> help(context.getSource()))
                .then(Commands.argument("dollars", IntegerArgumentType.integer(1, 230_400))
                        .requires(ExchangePermissions::use)
                        .executes(context -> exchange(context.getSource(), IntegerArgumentType.getInteger(context, "dollars"))))
                .then(Commands.literal("all").requires(ExchangePermissions::use)
                        .executes(context -> exchange(context.getSource(), -1)))
                .then(Commands.literal("status").requires(ExchangePermissions::use).executes(context -> {
                    if (!ready || service == null) return unavailable(context.getSource());
                    service.status(new ForgeCashWallet(context.getSource().getPlayerOrException()));
                    return 1;
                }))
                .then(admin());
        dispatcher.register(root);
    }

    private LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin").requires(ExchangePermissions::admin)
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("reload").executes(context -> {
                    loadConnection();
                    return status(context.getSource());
                }))
                .then(Commands.literal("enable").executes(context -> toggle(context.getSource(), true)))
                .then(Commands.literal("disable").executes(context -> toggle(context.getSource(), false)));
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Cash exchange: $1 in Just Enough Drugz bills = 1 Store credit. "
                + "Use /namelessexchange:exchange <whole dollars>, all, or status. "
                + "A verified linked website account is required. Uses plain bills in your hotbar/main inventory; "
                + "survival/adventure only. Exact bills required; no change is minted."), false);
        return 1;
    }

    private int exchange(CommandSourceStack source, int requested) {
        if (!ExchangePermissions.use(source)) return 0;
        if (!enabled() || service == null) return unavailable(source);
        try {
            ServerPlayer player = source.getPlayerOrException();
            var wallet = new ForgeCashWallet(player);
            int dollars = requested == -1 ? wallet.availableDollars() : requested;
            if (dollars <= 0) throw new IllegalArgumentException("No eligible cash bills in your hotbar/main inventory.");
            return service.begin(wallet, wallet.quote(dollars)) ? 1 : 0;
        } catch (IllegalArgumentException error) {
            // Wallet validation messages are local fixed strings, never website response text.
            source.sendFailure(Component.literal(error.getMessage()));
            return 0;
        } catch (Exception error) {
            source.sendFailure(Component.literal("The exchange could not be started. No new credit request was submitted; check /exchange status."));
            LOGGER.warn("Cash exchange command failed ({}).", error.getClass().getSimpleName());
            return 0;
        }
    }

    private int toggle(CommandSourceStack source, boolean enabled) {
        if (!ExchangePermissions.admin(source)) return 0;
        boolean old = ExchangeConfig.ENABLED.get();
        try {
            if (service != null) service.invalidatePreflights();
            ExchangeConfig.ENABLED.set(enabled);
            ExchangeConfig.SPEC.save();
        } catch (RuntimeException error) {
            ExchangeConfig.ENABLED.set(old);
            source.sendFailure(Component.literal("Could not save the exchange switch; its previous value was restored."));
            return 0;
        }
        return status(source);
    }

    private int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Cash exchange: " + (enabled() ? "enabled" : "disabled/unavailable")
                + "; switch=" + ExchangeConfig.ENABLED.get()
                + "; initialized=" + ready + "; connection configured=" + (api != null)
                + "; held receipts=" + (service == null ? "unavailable" : service.heldCount())
                + "; rate=$1 → 1 credit. Config: config/" + CONFIG_NAME), false);
        return 1;
    }

    private int unavailable(CommandSourceStack source) {
        source.sendFailure(Component.literal("Cash exchange is disabled or unavailable. Your bills have not been taken by this command."));
        return 0;
    }

    private void stopping(ServerStoppingEvent event) {
        if (server != event.getServer()) return;
        ready = false;
        if (service != null) service.invalidatePreflights();
        // Do not interrupt an in-flight credit request. Its outcome must still reach the durable journal.
        // If the JVM exits first, POSTING remains held on the next launch and is never replayed.
        if (workers != null) workers.shutdown();
        server = null;
    }
}

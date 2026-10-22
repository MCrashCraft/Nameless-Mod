/* SPDX-License-Identifier: GPL-3.0-or-later */
package dev.mcrashcraft.namelessforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.namelessmc.plugin.common.NamelessPlugin;
import com.namelessmc.plugin.common.audiences.NamelessCommandSender;
import com.namelessmc.plugin.common.command.CommonCommand;
import com.namelessmc.plugin.common.command.NamelessPluginCommand;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Brigadier bridge: preserves existing mod command roots and reuses upstream permissions. */
public final class ForgeCommands {
    private static final String NAMESPACE = "namelessforge:";
    private static final List<String> CONFIG_KEYS = List.of("get-notifications", "plugin", "register", "report",
            "user-info", "store-change-credits", "store-view-credits", "suggest", "verify");
    private final NamelessPlugin plugin;
    private final ForgeAudienceProvider audiences;
    private final Runnable reloadAction;
    private final Set<CommandDispatcher<CommandSourceStack>> registered =
            Collections.newSetFromMap(new WeakHashMap<>());

    public ForgeCommands(NamelessPlugin plugin, ForgeAudienceProvider audiences, Runnable reloadAction) {
        this.plugin = plugin;
        this.audiences = audiences;
        this.reloadAction = reloadAction;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!registered.add(dispatcher)) {
            return;
        }
        Map<String, String> namespaceAliases = new HashMap<>();
        Set<String> claimed = new HashSet<>();
        for (String key : CONFIG_KEYS) {
            var node = plugin.config().commands().node(key);
            String requested = node.getString();
            if (requested == null) {
                continue;
            }
            if (!validName(requested)) {
                plugin.logger().warning("Skipping invalid NamelessMC command name for " + key);
                node.raw(null);
                continue;
            }
            String namespaced = requested.startsWith(NAMESPACE) ? requested : NAMESPACE + requested;
            if (dispatcher.getRoot().getChild(namespaced) != null || claimed.contains(namespaced)) {
                // A deterministic per-feature fallback also distinguishes duplicate configured aliases.
                namespaced = NAMESPACE + key;
            }
            if (dispatcher.getRoot().getChild(namespaced) != null || claimed.contains(namespaced)) {
                plugin.logger().warning("Skipping NamelessMC command because its namespace is occupied: " + key);
                node.raw(null);
                continue;
            }
            String effective = requested;
            if (dispatcher.getRoot().getChild(requested) != null || claimed.contains(requested)) {
                effective = namespaced;
                plugin.logger().warning("Command /" + requested + " is already owned; use /" + effective + " for NamelessMC");
                // Runtime-only adjustment keeps usage and join hints correct; do not rewrite commands.yaml.
                node.raw(effective);
            }
            claimed.add(effective);
            claimed.add(namespaced);
            namespaceAliases.put(effective, namespaced);
        }
        for (CommonCommand command : CommonCommand.commands(plugin)) {
            String actual = command.actualName();
            if (actual == null) {
                continue;
            }
            String namespaced = namespaceAliases.get(actual);
            if (namespaced == null) {
                continue;
            }
            dispatcher.register(literal(actual, command));
            if (!namespaced.equals(actual)) {
                dispatcher.register(literal(namespaced, command));
            }
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> literal(String name, CommonCommand command) {
        return Commands.literal(name)
                .requires(source -> permitted(source, command))
                .executes(context -> execute(context.getSource(), command, new String[0]))
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .suggests((context, builder) -> complete(context.getSource(), command, builder))
                        .executes(context -> execute(context.getSource(), command,
                                arguments(StringArgumentType.getString(context, "arguments"), false))));
    }

    private boolean permitted(CommandSourceStack source, CommonCommand command) {
        NamelessCommandSender sender = audiences.sender(source);
        return sender != null && sender.hasPermission(command.permission());
    }

    private int execute(CommandSourceStack source, CommonCommand command, String[] args) {
        audiences.refreshPlayers();
        NamelessCommandSender sender = audiences.sender(source);
        if (sender == null || !sender.hasPermission(command.permission())) {
            return 0;
        }
        if (command instanceof NamelessPluginCommand && args.length == 1
                && ("reload".equals(args[0]) || "rl".equals(args[0]))) {
            plugin.scheduler().runSync(() -> {
                reloadAction.run();
                sender.sendMessage(plugin.language().get(
                        com.namelessmc.plugin.common.LanguageHandler.Term.COMMAND_PLUGIN_OUTPUT_RELOAD_SUCCESSFUL));
            });
        } else {
            // Some optional common commands call api() before creating their own async continuation.
            plugin.scheduler().runAsync(() -> {
                try {
                    command.verifyPermissionThenExecute(sender, args);
                } catch (RuntimeException error) {
                    if (plugin.scheduler() instanceof ForgeScheduler scheduler && !scheduler.isCurrentTask()) {
                        return;
                    }
                    plugin.logger().severe("NamelessMC command failed: " + error.getClass().getSimpleName());
                    sender.sendMessage(Component.text("The website command could not be completed. Check the server log."));
                }
            });
        }
        return 1;
    }

    private CompletableFuture<Suggestions> complete(CommandSourceStack source, CommonCommand command,
                                                    SuggestionsBuilder builder) {
        NamelessCommandSender sender = audiences.sender(source);
        if (sender == null || !sender.hasPermission(command.permission())) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemaining();
        String[] args = arguments(remaining, true);
        int lastSpace = Math.max(remaining.lastIndexOf(' '), remaining.lastIndexOf('\t'));
        SuggestionsBuilder target = builder.createOffset(builder.getStart() + lastSpace + 1);
        // The common implementation searches its local user cache; it does not make HTTP requests.
        for (String suggestion : command.complete(sender, args)) {
            target.suggest(suggestion);
        }
        return target.buildFuture();
    }

    static boolean validName(String name) {
        return !name.isBlank() && name.length() <= 96 && name.matches("[a-zA-Z0-9_:.-]+");
    }

    static String[] arguments(String raw, boolean completing) {
        if (raw.length() > 4_096) {
            throw new IllegalArgumentException("Command arguments exceed the size limit");
        }
        String trimmed = raw.stripLeading();
        if (trimmed.isBlank()) {
            return completing ? new String[]{""} : new String[0];
        }
        return trimmed.split("\\s+", completing ? -1 : 0);
    }
}

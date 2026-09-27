package com.safeloginstg.command;

import com.safeloginstg.auth.AuthService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class SlstgCommand implements CommandExecutor, TabCompleter {

    private final AuthService authService;

    public SlstgCommand(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!isOpOnly(sender)) {
            sender.sendMessage(Component.text("This command is OP-only.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("setpswd") || sub.equals("setpassword")) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /SLSTG setpswd <player> <new password>", NamedTextColor.YELLOW));
                return true;
            }
            String playerName = args[1];
            String password = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            if (!authService.setPassword(playerName, password)) {
                sender.sendMessage(Component.text(
                        "Password length is invalid. Check min/max in config.yml.",
                        NamedTextColor.RED
                ));
                return true;
            }
            sender.sendMessage(Component.text(
                    "Password updated for " + playerName + " (stored hashed).",
                    NamedTextColor.GREEN
            ));
            return true;
        }

        if (sub.equals("reset")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /SLSTG reset <player>", NamedTextColor.YELLOW));
                return true;
            }
            String playerName = args[1];
            if (!authService.resetAccount(playerName)) {
                sender.sendMessage(Component.text(
                        "No account found for " + playerName + ".",
                        NamedTextColor.RED
                ));
                return true;
            }
            sender.sendMessage(Component.text(
                    "Account reset for " + playerName + ". They must create a new password on next login.",
                    NamedTextColor.GREEN
            ));
            return true;
        }

        sendUsage(sender);
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /SLSTG setpswd <player> <new password>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /SLSTG reset <player>", NamedTextColor.YELLOW));
    }

    /**
     * OP-only: players must be op; console / RCON are allowed.
     */
    private static boolean isOpOnly(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            return true;
        }
        return sender.isOp();
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (!isOpOnly(sender)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(List.of("setpswd", "reset"), args[0]);
        }
        if (args.length == 2
                && (args[0].equalsIgnoreCase("setpswd") || args[0].equalsIgnoreCase("reset"))) {
            return filter(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                    args[1]
            );
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}

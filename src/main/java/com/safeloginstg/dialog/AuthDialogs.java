package com.safeloginstg.dialog;

import com.safeloginstg.SafeLoginSTG;
import com.safeloginstg.auth.AuthService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Builds Paper Dialog screens that match the register / login flow.
 */
public final class AuthDialogs {

    private final SafeLoginSTG plugin;

    public AuthDialogs(SafeLoginSTG plugin) {
        this.plugin = plugin;
    }

    public void showRegister(Player player, String errorMessage) {
        AuthService auth = plugin.getAuthService();
        int maxLength = plugin.getConfig().getInt("max-password-length", 64);

        Component body = Component.text()
                .append(Component.text("Welcome, ", NamedTextColor.GOLD))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text("!", NamedTextColor.GOLD))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("You must ", NamedTextColor.GOLD))
                .append(Component.text("create a password", NamedTextColor.AQUA))
                .append(Component.text(" to log in to the server.", NamedTextColor.GOLD))
                .append(Component.newline())
                .append(Component.text("Please fill in the fields below to continue.", NamedTextColor.GOLD))
                .append(errorBody(errorMessage))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Creating a Password", NamedTextColor.GOLD)
                                .decorate(TextDecoration.BOLD))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .inputs(List.of(
                                DialogInput.text("password", Component.text("Password", NamedTextColor.WHITE))
                                        .width(300)
                                        .maxLength(maxLength)
                                        .build(),
                                DialogInput.text("confirmation", Component.text("Confirmation", NamedTextColor.WHITE))
                                        .width(300)
                                        .maxLength(maxLength)
                                        .build()
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                Component.text("Create Account", NamedTextColor.WHITE),
                                Component.text("Create your account with the password above."),
                                200,
                                DialogAction.customClick(
                                        (view, audience) -> handleRegisterSubmit(player, view, auth),
                                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()
                                )
                        ),
                        ActionButton.create(
                                Component.text("Logout", NamedTextColor.WHITE),
                                Component.text("Disconnect from the server."),
                                200,
                                DialogAction.customClick(
                                        (view, audience) -> auth.kickLogout(player),
                                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()
                                )
                        )
                ))
        );

        player.showDialog(dialog);
    }

    public void showLogin(Player player, String errorMessage) {
        AuthService auth = plugin.getAuthService();
        int maxLength = plugin.getConfig().getInt("max-password-length", 64);

        Component body = Component.text()
                .append(Component.text("Welcome back, ", NamedTextColor.GOLD))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text("!", NamedTextColor.GOLD))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Enter your password to log in to the server.", NamedTextColor.GOLD))
                .append(errorBody(errorMessage))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Login", NamedTextColor.GOLD)
                                .decorate(TextDecoration.BOLD))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .inputs(List.of(
                                DialogInput.text("password", Component.text("Password", NamedTextColor.WHITE))
                                        .width(300)
                                        .maxLength(maxLength)
                                        .build()
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                Component.text("Login", NamedTextColor.WHITE),
                                Component.text("Submit your password."),
                                200,
                                DialogAction.customClick(
                                        (view, audience) -> handleLoginSubmit(player, view, auth),
                                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()
                                )
                        ),
                        ActionButton.create(
                                Component.text("Logout", NamedTextColor.WHITE),
                                Component.text("Disconnect from the server."),
                                200,
                                DialogAction.customClick(
                                        (view, audience) -> auth.kickLogout(player),
                                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()
                                )
                        )
                ))
        );

        player.showDialog(dialog);
    }

    private void handleRegisterSubmit(Player player, DialogResponseView view, AuthService auth) {
        if (!player.isOnline() || auth.isAuthenticated(player)) {
            return;
        }
        String password = nullToEmpty(view.getText("password"));
        String confirmation = nullToEmpty(view.getText("confirmation"));
        auth.handleRegister(player, password, confirmation);
    }

    private void handleLoginSubmit(Player player, DialogResponseView view, AuthService auth) {
        if (!player.isOnline() || auth.isAuthenticated(player)) {
            return;
        }
        String password = nullToEmpty(view.getText("password"));
        auth.handleLogin(player, password);
    }

    private static Component errorBody(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return Component.empty();
        }
        return Component.newline()
                .append(Component.newline())
                .append(Component.text(errorMessage, NamedTextColor.RED));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

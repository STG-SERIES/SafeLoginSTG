package com.safeloginstg;

import com.safeloginstg.auth.AuthService;
import com.safeloginstg.auth.ClientTokenService;
import com.safeloginstg.auth.PasswordHasher;
import com.safeloginstg.command.SlstgCommand;
import com.safeloginstg.dialog.AuthDialogs;
import com.safeloginstg.listener.AuthListener;
import com.safeloginstg.mojang.MojangPremiumChecker;
import com.safeloginstg.storage.YamlAuthRepository;
import org.bukkit.plugin.java.JavaPlugin;

public final class SafeLoginSTG extends JavaPlugin {

    private AuthService authService;
    private YamlAuthRepository repository;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.repository = new YamlAuthRepository(getDataFolder(), getLogger());
        this.repository.init();

        PasswordHasher hasher = new PasswordHasher();
        MojangPremiumChecker premiumChecker = new MojangPremiumChecker(this);
        AuthDialogs dialogs = new AuthDialogs(this);
        ClientTokenService tokens = new ClientTokenService(this);
        this.authService = new AuthService(this, repository, hasher, premiumChecker, dialogs, tokens);

        getServer().getPluginManager().registerEvents(new AuthListener(authService), this);

        SlstgCommand command = new SlstgCommand(authService);
        var pluginCommand = getCommand("SLSTG");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        getLogger().info("SafeLoginSTG enabled.");
    }

    @Override
    public void onDisable() {
        if (authService != null) {
            authService.shutdown();
        }
        if (repository != null) {
            repository.close();
        }
    }

    public AuthService getAuthService() {
        return authService;
    }
}

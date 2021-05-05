/*
 * #%L
 * SkinsRestorer
 * %%
 * Copyright (C) 2021 SkinsRestorer
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package net.skinsrestorer.velocity;

import co.aikar.commands.VelocityCommandManager;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.Getter;
import net.kyori.text.TextComponent;
import net.kyori.text.serializer.legacy.LegacyComponentSerializer;
import net.skinsrestorer.api.SkinsRestorerAPI;
import net.skinsrestorer.data.PluginData;
import net.skinsrestorer.shared.interfaces.SRApplier;
import net.skinsrestorer.shared.interfaces.SRPlugin;
import net.skinsrestorer.shared.storage.Config;
import net.skinsrestorer.shared.storage.Locale;
import net.skinsrestorer.shared.storage.MySQL;
import net.skinsrestorer.shared.storage.SkinStorage;
import net.skinsrestorer.shared.update.UpdateChecker;
import net.skinsrestorer.shared.update.UpdateCheckerGitHub;
import net.skinsrestorer.shared.utils.*;
import net.skinsrestorer.velocity.command.SkinCommand;
import net.skinsrestorer.velocity.command.SrCommand;
import net.skinsrestorer.velocity.listener.GameProfileRequest;
import net.skinsrestorer.velocity.utils.SkinApplierVelocity;
import org.bstats.charts.SingleLineChart;
import org.bstats.velocity.Metrics;
import org.inventivetalent.update.spiget.UpdateCallback;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Plugin(id = "skinsrestorer", name = PluginData.NAME, version = PluginData.VERSION, description = PluginData.DESCRIPTION, url = PluginData.URL, authors = {"Blackfire62", "McLive"})
public class SkinsRestorer implements SRPlugin {
    @Getter
    private static final String CONFIG_PATH = "plugins" + File.separator + "SkinsRestorer" + File.separator + "";
    @Getter
    private static SkinsRestorer instance;
    @Getter
    private final ProxyServer proxy;
    @Getter
    private final SRLogger srLogger;
    @Getter
    private final Path dataFolder;
    @Getter
    private final ExecutorService service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final Metrics.Factory metricsFactory;
    @Getter
    private SkinApplierVelocity skinApplierVelocity;
    private CommandSource console;
    private UpdateChecker updateChecker;
    @Getter
    private SkinStorage skinStorage;
    @Getter
    private MojangAPI mojangAPI;
    @Getter
    private MineSkinAPI mineSkinAPI;
    @Getter
    private SkinsRestorerAPI skinsRestorerVelocityAPI;

    @Inject
    public SkinsRestorer(ProxyServer proxy, Logger logger, Metrics.Factory metricsFactory, @DataDirectory Path dataFolder) {
        this.proxy = proxy;
        srLogger = new SRLogger(dataFolder.toFile());
        this.dataFolder = dataFolder;
        this.metricsFactory = metricsFactory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent e) {
        instance = this;
        srLogger.logAlways("Enabling SkinsRestorer v" + getVersion());
        console = proxy.getConsoleCommandSource();
        File updaterDisabled = new File(CONFIG_PATH, "noupdate.txt");

        int pluginId = 10606; // SkinsRestorer's ID on bStats, for Bungeecord
        Metrics metrics = metricsFactory.make(this, pluginId);
        metrics.addCustomChart(new SingleLineChart("mineskin_calls", MetricsCounter::collectMineskinCalls));
        metrics.addCustomChart(new SingleLineChart("minetools_calls", MetricsCounter::collectMinetoolsCalls));
        metrics.addCustomChart(new SingleLineChart("mojang_calls", MetricsCounter::collectMojangCalls));
        metrics.addCustomChart(new SingleLineChart("backup_calls", MetricsCounter::collectBackupCalls));

        // Check for updates
        if (!updaterDisabled.exists()) {
            updateChecker = new UpdateCheckerGitHub(2124, getVersion(), getSrLogger(), "SkinsRestorerUpdater/Velocity");
            checkUpdate(true);

            getProxy().getScheduler().buildTask(this, this::checkUpdate).repeat(60, TimeUnit.MINUTES).delay(60, TimeUnit.MINUTES).schedule();
        } else {
            srLogger.logAlways(Level.INFO, "Updater Disabled");
        }

        skinStorage = new SkinStorage(SkinStorage.Platform.VELOCITY);

        // Init config files
        Config.load(CONFIG_PATH, getClass().getClassLoader().getResourceAsStream("config.yml"));
        Locale.load(CONFIG_PATH);

        mojangAPI = new MojangAPI(srLogger);
        mineSkinAPI = new MineSkinAPI(srLogger);

        skinStorage.setMojangAPI(mojangAPI);
        // Init storage
        if (!initStorage())
            return;

        mojangAPI.setSkinStorage(skinStorage);
        mineSkinAPI.setSkinStorage(skinStorage);

        // Init listener
        proxy.getEventManager().register(this, new GameProfileRequest(this));

        // Init commands
        initCommands();

        // Init SkinApplier
        skinApplierVelocity = new SkinApplierVelocity(this);

        // Init API
        skinsRestorerVelocityAPI = new SkinsRestorerAPI(mojangAPI, skinStorage, this);

        srLogger.logAlways("Enabled SkinsRestorer v" + getVersion());

        // Run connection check
        ServiceChecker checker = new ServiceChecker();
        checker.setMojangAPI(mojangAPI);
        checker.checkServices();
        ServiceChecker.ServiceCheckResponse response = checker.getResponse();

        if (response.getWorkingUUID() == 0 || response.getWorkingProfile() == 0) {
            console.sendMessage(deserialize("§c[§4Critical§c] ------------------[§2SkinsRestorer §cis §c§l§nOFFLINE§c] --------------------------------- "));
            console.sendMessage(deserialize("§c[§4Critical§c] §cPlugin currently can't fetch new skins due to blocked connection!"));
            console.sendMessage(deserialize("§c[§4Critical§c] §cSee http://skinsrestorer.net/firewall for steps to resolve your issue!"));
            console.sendMessage(deserialize("§c[§4Critical§c] ------------------------------------------------------------------------------------------- "));
        }
    }

    @Subscribe
    public void onShutDown(ProxyShutdownEvent ev) {
        srLogger.logAlways("Disabling SkinsRestorer v" + getVersion());
        srLogger.logAlways("Disabled SkinsRestorer v" + getVersion());
    }

    @SuppressWarnings({"deprecation"})
    private void initCommands() {
        VelocityCommandManager manager = new VelocityCommandManager(getProxy(), this);
        // optional: enable unstable api to use help
        manager.enableUnstableAPI("help");

        CommandReplacements.permissions.forEach((k, v) -> manager.getCommandReplacements().addReplacement(k, v));
        CommandReplacements.descriptions.forEach((k, v) -> manager.getCommandReplacements().addReplacement(k, v));
        CommandReplacements.syntax.forEach((k, v) -> manager.getCommandReplacements().addReplacement(k, v));
        CommandReplacements.completions.forEach((k, v) -> manager.getCommandCompletions().registerAsyncCompletion(k, c ->
                Arrays.asList(v.split(", "))));

        new CommandPropertiesManager(manager, CONFIG_PATH, getClass().getClassLoader().getResourceAsStream("command-messages.properties"));

        SharedMethods.allowIllegalACFNames();

        manager.registerCommand(new SkinCommand(this));
        manager.registerCommand(new SrCommand(this));
    }

    private boolean initStorage() {
        // Initialise MySQL
        if (Config.MYSQL_ENABLED) {
            try {
                MySQL mysql = new MySQL(
                        Config.MYSQL_HOST,
                        Config.MYSQL_PORT,
                        Config.MYSQL_DATABASE,
                        Config.MYSQL_USERNAME,
                        Config.MYSQL_PASSWORD,
                        Config.MYSQL_CONNECTIONOPTIONS
                );

                mysql.openConnection();
                mysql.createTable();

                skinStorage.setMysql(mysql);
            } catch (Exception e) {
                srLogger.logAlways("§cCan't connect to MySQL! Disabling SkinsRestorer.");
                return false;
            }
        } else {
            skinStorage.loadFolders(getDataFolder().toFile());
        }

        // Preload default skins
        getService().execute(skinStorage::preloadDefaultSkins);
        return true;
    }

    private void checkUpdate() {
        checkUpdate(false);
    }

    private void checkUpdate(boolean showUpToDate) {
        getService().execute(() -> updateChecker.checkForUpdate(new UpdateCallback() {
            @Override
            public void updateAvailable(String newVersion, String downloadUrl, boolean hasDirectDownload) {
                updateChecker.getUpdateAvailableMessages(newVersion, downloadUrl, hasDirectDownload, getVersion(), false).forEach(msg ->
                        console.sendMessage(deserialize(msg)));
            }

            @Override
            public void upToDate() {
                if (!showUpToDate)
                    return;

                updateChecker.getUpToDateMessages(getVersion(), false).forEach(msg ->
                        console.sendMessage(deserialize(msg)));
            }
        }));
    }

    public TextComponent deserialize(String string) {
        return LegacyComponentSerializer.legacy().deserialize(string);
    }

    public String getVersion() {
        Optional<PluginContainer> plugin = getProxy().getPluginManager().getPlugin("skinsrestorer");

        if (!plugin.isPresent())
            return "";

        Optional<String> version = plugin.get().getDescription().getVersion();

        return version.orElse("");
    }

    @Override
    public SRApplier getApplier() {
        return skinApplierVelocity;
    }
}

package ru.taptima.phalyfusion.configuration;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.php.composer.ComposerConfigUtils;
import com.jetbrains.php.composer.ComposerDataService;
import com.jetbrains.php.config.interpreters.PhpInterpreter;
import com.jetbrains.php.tools.quality.QualityToolConfigurationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PhalyfusionConfigurationManager extends QualityToolConfigurationManager<PhalyfusionConfiguration> {
    public PhalyfusionConfigurationManager(@Nullable Project project) {
        super(project);
        if (project != null) {
            this.myProjectManager = ServiceManager.getService(project, PhalyfusionConfigurationManager.PhalyfusionProjectConfigurationBaseManager.class);
        }

        this.myApplicationManager = ApplicationManager.getApplication().getService(PhalyfusionConfigurationManager.PhalyfusionAppConfigurationBaseManager.class);
    }

    private static final String GROUP_ID = "PHP External Quality Tools";

    private static class CodeAnalyzer {
        public CodeAnalyzer(String name, String fullName, String args) {
            this.name = name;
            this.fullName = fullName;
            this.args = args;
        }

        private final String name;
        private final String fullName;
        private final String args;
    }

    private final static Object FILE_LOCK = new Object();

    private static final ArrayList<CodeAnalyzer> CODE_ANALYZERS = new ArrayList<>(Arrays.asList(
            new CodeAnalyzer("phpstan", "phpstan/phpstan", "analyse --level 7 --memory-limit 1G"),
            new CodeAnalyzer("phan", "phan/phan", "-k .phan/config.php"),
            new CodeAnalyzer("psalm", "vimeo/psalm", "-c psalm.xml"),
            new CodeAnalyzer("phpmd", "phpmd/phpmd", "src text cleancode"),
            new CodeAnalyzer("php-cs-fixer", "friendsofphp/php-cs-fixer", "fix --config=.php_cs")));

    public void checkNeonConfiguration() throws IOException {
        if (this.myProject == null) {
            return;
        }

        File neonConfig = new File(this.myProject.getBasePath() + "/" + "phalyfusion.neon");
        if (neonConfig.isFile()) {
            return;
        }

        var config= ComposerDataService.getInstance(this.myProject).getConfigFile();
        Pair<String, String> vendors = getVendorAndBinDirs(config);
        if (vendors == null) return;

        var packagesList = ComposerConfigUtils.getInstalledPackagesFromConfig(config);
        List<Pair<CodeAnalyzer, String>> existingAnalysers = new ArrayList<>();
        StringBuilder analyzersString = new StringBuilder();

        synchronized (FILE_LOCK) {
            for (var pkg : packagesList) {
                var res = CODE_ANALYZERS.stream().filter((it) -> it.fullName.equals(pkg.getName())).findFirst();

                if (res.isEmpty()) {
                    continue;
                }

                var analyzerPath = vendors.second + "/" + res.get().name;

                if (!new File(this.myProject.getBasePath() + "/" + analyzerPath).isFile()) {
                    Notifications.Bus.notify(new Notification(
                            GROUP_ID, "Phalyfusion", "Can not find " + res.get().name + " package bin",
                            NotificationType.ERROR, null));
                    continue;
                }

                if (SystemInfo.isWindows) {
                    analyzerPath = analyzerPath.replace("/", "\\\\");
                }
                existingAnalysers.add(new Pair<>(res.get(), analyzerPath));
                analyzersString.append(res.get().name).append(" ");
            }

            createNeonConfiguration(neonConfig, existingAnalysers);
        }

        Notifications.Bus.notify(new Notification(
                GROUP_ID, "Phalyfusion", "Phalyfusion configuration created with " + analyzersString,
                NotificationType.INFORMATION, null));
    }

    private void createNeonConfiguration(@NotNull File neonFile, @NotNull List<Pair<CodeAnalyzer, String>> analyzers) throws IOException {
        try (var writer = new BufferedWriter(new FileWriter(neonFile))) {
            writer.write("plugins:\n");
            String indent = "    ";
            writer.write(indent + "usePlugins:\n");
            for (var analyzer : analyzers) {
                writer.write(indent + indent + "- " + analyzer.first.name + "\n");
            }

            writer.write(indent + "runCommands:\n");

            for (var analyzer : analyzers) {
                writer.write(indent + indent + analyzer.first.name + ": \"" + analyzer.second + " " + analyzer.first.args + "\"\n");
            }
        }
    }

    private void findPhalyfusion(@Nullable PhalyfusionConfiguration configuration) {
        if (configuration == null || (configuration.getToolPath() != null && !configuration.getToolPath().isEmpty())) {
            return;
        }

        if (this.myProject == null) {
            return;
        }

        var config= ComposerDataService.getInstance(this.myProject).getConfigFile();
        Pair<String, String> vendors = getVendorAndBinDirs(config);
        if (vendors == null) return;

        var packagesList = ComposerConfigUtils.getInstalledPackagesFromConfig(config);
        if (packagesList.stream().anyMatch(it -> it.getName().equals("taptima/phalyfusion"))) {
            var phalyfusionPath = vendors.second + "/phalyfusion" + (SystemInfo.isWindows ? ".bat" : "");
            Notifications.Bus.notify(new Notification(
                    GROUP_ID, "Phalyfusion", "Phalyfusion detected at " + phalyfusionPath,
                    NotificationType.INFORMATION, null));
            configuration.setToolPath(config.getParent().getPath() + "/" + phalyfusionPath);
        }
    }

    @Nullable
    private Pair<String, String> getVendorAndBinDirs(@Nullable VirtualFile config) {
        if (config == null) {
            return null;
        }

        return ComposerConfigUtils.getVendorAndBinDirs(config);
    }

    @Override
    public List<PhalyfusionConfiguration> getAllSettings() {
        var settings = super.getAllSettings();
        for (var setting : settings) {
            findPhalyfusion(setting);
        }

        return settings;
    }

    @Override
    public @NotNull PhalyfusionConfiguration getLocalSettings() {
        var configuration = super.getLocalSettings();
        findPhalyfusion(configuration);
        return configuration;
    }

    @Override
    public @NotNull PhalyfusionConfiguration getOrCreateConfigurationByInterpreter(@Nullable PhpInterpreter interpreter, boolean onTheFly) {
        var configuration = super.getOrCreateConfigurationByInterpreter(interpreter, onTheFly);
        findPhalyfusion(configuration);
        return configuration;
    }

    @Override
    public @Nullable PhalyfusionConfiguration getConfigurationByInterpreter(@Nullable PhpInterpreter interpreter) {
        var configuration = super.getConfigurationByInterpreter(interpreter);
        findPhalyfusion(configuration);
        return configuration;
    }

    public static PhalyfusionConfigurationManager getInstance(@NotNull Project project) {
        return ServiceManager.getService(project, PhalyfusionConfigurationManager.class);
    }

    @State(
        name = "Phalyfusion",
        storages = {@Storage("php.xml")}
    )
    static class PhalyfusionAppConfigurationBaseManager extends PhalyfusionConfigurationBaseManager {
        PhalyfusionAppConfigurationBaseManager() {}
    }

    @State(
        name = "Phalyfusion",
        storages = {@Storage("php.xml")}
    )
    static class PhalyfusionProjectConfigurationBaseManager extends PhalyfusionConfigurationBaseManager {
        PhalyfusionProjectConfigurationBaseManager() {}
    }
}

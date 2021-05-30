package ru.taptima.phalyfusion;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.execution.ExecutionException;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.config.interpreters.PhpSdkFileTransfer;
import com.jetbrains.php.lang.PhpLanguage;
import com.jetbrains.php.lang.psi.PhpFile;
import com.jetbrains.php.tools.quality.*;
import ru.taptima.phalyfusion.blacklist.PhalyfusionBlackList;
import ru.taptima.phalyfusion.configuration.PhalyfusionConfiguration;
import ru.taptima.phalyfusion.configuration.PhalyfusionConfigurationManager;
import ru.taptima.phalyfusion.configuration.PhalyfusionProjectConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.taptima.phalyfusion.issues.IssueCacheManager;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PhalyfusionAnnotator extends QualityToolAnnotator {
    public static final PhalyfusionAnnotator INSTANCE = new PhalyfusionAnnotator();

    @NotNull
    @Override
    protected String getTemporaryFilesFolder() {
        return "phalyfusion_temp.tmp";
    }

    @NotNull
    @Override
    protected String getInspectionId() {
        return (new PhalyfusionValidationInspection()).getID();
    }

    @Override
    protected QualityToolMessageProcessor createMessageProcessor(@NotNull QualityToolAnnotatorInfo qualityToolAnnotatorInfo) {
        return new PhalyfusionMessageProcessor(qualityToolAnnotatorInfo);
    }

    public static void launchQualityTool(@NotNull PsiFile[] files, @NotNull QualityToolAnnotatorInfo annotatorInfo, @NotNull QualityToolMessageProcessor messageProcessor,
                                         @NotNull PhpSdkFileTransfer transfer) throws ExecutionException {
        PhalyfusionBlackList blackList = PhalyfusionBlackList.getInstance(annotatorInfo.getProject());
        String[] filesPaths = Arrays.stream(files).filter(psiFile -> isFileSuitable(psiFile, blackList))
                .map(psiFile -> psiFile.getVirtualFile().getPath()).toArray(String[]::new);

        if (filesPaths.length == 0) {
            return;
        }

        List<String> params = getCommandLineOptions(filesPaths);
        String workingDir = QualityToolUtil.getWorkingDirectoryFromAnnotator(annotatorInfo);
        var configurationManager = PhalyfusionConfigurationManager.getInstance(annotatorInfo.getProject());

        try {
            configurationManager.checkNeonConfiguration();
        } catch (IOException e) {
            logWarning(annotatorInfo, "Failed to create phalyfusion configuration file", e);
        }

        QualityToolProcessCreator.runToolProcess(annotatorInfo, blackList, messageProcessor, workingDir, transfer, params);

        if (messageProcessor.getInternalErrorMessage() != null) {
            if (annotatorInfo.isOnTheFly()) {
                String message = messageProcessor.getInternalErrorMessage().getMessageText();
                showProcessErrorMessage(annotatorInfo, message);
            }

            messageProcessor.setFatalError();
        }
    }

    private static boolean isFileSuitable(@NotNull PsiFile file, @NotNull PhalyfusionBlackList blackList) {
        return file instanceof PhpFile && file.getViewProvider().getBaseLanguage() == PhpLanguage.INSTANCE
                && file.getContext() == null && !blackList.containsFile(file.getVirtualFile())
                && file.getVirtualFile().isValid();
    }

    private static List<String> getCommandLineOptions(String[] filePaths) {
        ArrayList<String> options = new ArrayList<>();
        options.add("analyse");
        options.add("--format=checkstyle");
        options.addAll(Arrays.asList(filePaths));
        return options;
    }

    protected void runTool(@NotNull QualityToolMessageProcessor messageProcessor, @NotNull QualityToolAnnotatorInfo annotatorInfo,
                           @NotNull PhpSdkFileTransfer transfer) throws ExecutionException {
        // This inspection is only for on-fly mode. Batch inspections are provided with PhalyfusionGlobal
        if (!annotatorInfo.isOnTheFly()) {
            return;
        }

        PhalyfusionConfiguration configuration = (PhalyfusionConfiguration) getConfiguration(annotatorInfo.getProject(), annotatorInfo.getInspection());
        IssueCacheManager issuesCache = ServiceManager.getService(annotatorInfo.getProject(), IssueCacheManager.class);

        if (configuration == null || !configuration.getOnFlyMode()) {
            if (messageProcessor instanceof PhalyfusionMessageProcessor) {
                ((PhalyfusionMessageProcessor)messageProcessor)
                        .loadFromCache(issuesCache.getCachedResultForFile(annotatorInfo.getOriginalFile()), annotatorInfo);
            }
            return;
        }

        launchQualityTool(new PsiFile[] { annotatorInfo.getPsiFile() }, annotatorInfo, messageProcessor, transfer);
        issuesCache.setCachedResultsForFile(annotatorInfo.getOriginalFile(), messageProcessor.getMessages());
    }

    @Nullable
    protected QualityToolConfiguration getConfiguration(@NotNull Project project, @NotNull LocalInspectionTool inspection) {
        try {
            return PhalyfusionProjectConfiguration.getInstance(project).findSelectedConfiguration(project);
        } catch (QualityToolValidationException e) {
            return null;
        }
    }
}

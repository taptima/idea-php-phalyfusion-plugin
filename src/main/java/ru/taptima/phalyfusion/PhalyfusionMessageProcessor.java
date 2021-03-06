package ru.taptima.phalyfusion;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.tools.quality.QualityToolAnnotatorInfo;
import com.jetbrains.php.tools.quality.QualityToolMessage;
import com.jetbrains.php.tools.quality.QualityToolType;
import com.jetbrains.php.tools.quality.QualityToolXmlMessageProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import ru.taptima.phalyfusion.configuration.PhalyfusionConfiguration;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * All common tools all output as "checkstyle" format
 *
 * <?xml version="1.0" encoding="UTF-8"?>
 * <checkstyle>
 * <file name="src/Foo.php">
 *   <error line="8" column="1" severity="error" message="Undefined variable: $td" />
 *   <error line="26" column="1" severity="error" message="Undefined variable: $testsssss" />
 * </file>
 * </checkstyle>
 *
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class PhalyfusionMessageProcessor extends QualityToolXmlMessageProcessor {
    private final HighlightDisplayLevel myWarningsHighlightLevel;
    private final Set<String> lineMessages = new HashSet<>();
    private int myPrevLine = -1;
    private String myFilePath = "";
    private boolean isInFile = false;
    private final Project myProject;
    private final Map<String, PsiFile> pathToPsi;

    public PhalyfusionMessageProcessor(QualityToolAnnotatorInfo info) {
        super(info);

        this.myWarningsHighlightLevel = HighlightDisplayLevel.WARNING;
        myProject = info.getProject();
        pathToPsi = Arrays.stream(new PsiFile[] {getFile()}).map(it -> Pair.create(it.getVirtualFile().getPath(), it)).collect(Collectors.toMap(it -> it.first, it -> it.second));
    }

    public PhalyfusionMessageProcessor(QualityToolAnnotatorInfo info, PsiFile[] files) {
        super(info);

        this.myWarningsHighlightLevel = HighlightDisplayLevel.WARNING;
        myProject = info.getProject();
        pathToPsi = Arrays.stream(files).map(it -> Pair.create(it.getVirtualFile().getPath(), it)).collect(Collectors.toMap(it -> it.first, it -> it.second));
    }

    protected XMLMessageHandler getXmlMessageHandler() {
        return new CheckstyleXmlMessageHandler();
    }

    public int getMessageStart(@NotNull String line) {
        if (isInFile) {
            int end = processFile(line, 0);
            if (end == -1) {
                return -1;
            }

            line = line.substring(end);
        }

        int messageStart = line.indexOf("<file");
        if (messageStart < 0) {
            messageStart = line.indexOf("</file");
            if (messageStart > -1) {
                myFilePath = "";
                return -1;
            }
            messageStart = line.indexOf("<error");
            if (messageStart < 0) {
                messageStart = line.indexOf("<warning");
            }
        } else {
            int nameStart = line.indexOf("name=") + 6;
            processFile(line, nameStart);
            messageStart = -1;
        }

        return messageStart;
    }

    private int processFile(@NotNull String line, int start) {
        int end = line.indexOf('\"', start);

        if (end == -1) {
            myFilePath += line.substring(start);
            isInFile = true;
            return -1;
        }

        myFilePath += line.substring(start, end);

        if (SystemInfo.isWindows && !FileUtil.isAbsolute(myFilePath)) {
            myFilePath = myProject.getBasePath() + "/" + FileUtil.toCanonicalPath(myFilePath);
        }

        myFilePath = myFilePath.replace('\\', '/');
        if (pathToPsi.containsKey(myFilePath)) {
            myFile = pathToPsi.get(myFilePath);
        }

        isInFile = false;
        return end;
    }

    public int getMessageEnd(@NotNull String line) {
        return line.indexOf("/>");
    }

    protected IntentionAction @NotNull [] getQuickFix(XMLMessageHandler messageHandler) {
        return IntentionAction.EMPTY_ARRAY;
    }

    @Nullable
    protected String getMessagePrefix() {
        return null;
    }

    @Nullable
    protected HighlightDisplayLevel severityToDisplayLevel(@NotNull QualityToolMessage.Severity severity) {
        return QualityToolMessage.Severity.WARNING.equals(severity) ? this.myWarningsHighlightLevel : null;
    }

    @Override
    protected @IntentionFamilyName QualityToolType<PhalyfusionConfiguration> getQualityToolType() {
        return PhalyfusionQualityToolType.INSTANCE;
    }

    public boolean processStdErrMessages() {
        return true;
    }

    /**
     * Convert to extract the attributes
     *
     * <error line="8" column="1" severity="error" message="Undefined variable: $td" />
     */
    private static class CheckstyleXmlMessageHandler extends XMLMessageHandler {
        private String message;

        protected void parseTag(@NotNull String tagName, @NotNull Attributes attributes) {
            if ("error".equals(tagName)) {
                this.mySeverity = QualityToolMessage.Severity.ERROR;
            } else if ("warning".equals(tagName)) {
                this.mySeverity = QualityToolMessage.Severity.WARNING;
            }

            this.myLineNumber = parseLineNumber(attributes.getValue("line"));
            this.message = attributes.getValue("message");
        }

        public String getMessageText() {
            return this.message;
        }

        public boolean isStatusValid() {
            return this.myLineNumber >= -1;
        }
    }

    @Override
    protected void processMessage(InputSource source) throws SAXException, IOException {
        QualityToolXmlMessageProcessor.XMLMessageHandler messageHandler = this.getXmlMessageHandler();
        this.mySAXParser.parse(source, messageHandler);
        if (messageHandler.isStatusValid()) {
            PhalyfusionMessage qualityToolMessage = new PhalyfusionMessage(this, messageHandler.getLineNumber(),
                    messageHandler.getSeverity(), messageHandler.getMessageText(), myFile, this.getQuickFix(messageHandler));
            int currLine = qualityToolMessage.getLineNum();
            if (currLine != this.myPrevLine) {
                this.lineMessages.clear();
                this.myPrevLine = currLine;
            }

            String messageText = qualityToolMessage.getMessageText();
            if (this.lineMessages.add(messageText)) {
                this.addMessage(qualityToolMessage);
            }
        }
    }

    @Override
    protected void addMessage(QualityToolMessage message) {
        if (message.isInternalError()) {
            message = new QualityToolMessage(this, message.getLineNum(), QualityToolMessage.Severity.INTERNAL_ERROR,
                    message.getMessageText());
        }

        super.addMessage(message);
    }

    @Override
    public PsiFile getFile() {
        return myFile;
    }
}
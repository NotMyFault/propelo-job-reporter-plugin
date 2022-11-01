package io.jenkins.plugins.propelo.commons.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.jenkins.plugins.propelo.commons.models.JenkinsStatusInfo;
import io.jenkins.plugins.propelo.commons.utils.FileUtils;
import io.jenkins.plugins.propelo.commons.utils.JsonUtils;

import org.jetbrains.annotations.NotNull;
import sun.nio.cs.US_ASCII;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.jenkins.plugins.propelo.commons.plugins.Common.JENKINS_HEARTBEAT_INFO_FILE;
import static io.jenkins.plugins.propelo.commons.plugins.Common.UTF_8;


public class JenkinsStatusService {

    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());

    private static final JenkinsStatusService instance = new JenkinsStatusService();

    private static final ObjectMapper mapper = JsonUtils.buildObjectMapper();

    public static JenkinsStatusService getInstance() {
        return instance;
    }

    public void markDataEvent(File expandedLevelOpsPluginDir, boolean isDataEventSuccess) {
        JenkinsStatusInfo instanceDetails = getStatus(new File(expandedLevelOpsPluginDir,
                JENKINS_HEARTBEAT_INFO_FILE));
        File inputFile = loadFile(expandedLevelOpsPluginDir);
        if (!isDataEventSuccess) {
            instanceDetails.setLastFailedDataEvent(new Date());
            save(instanceDetails, inputFile);
            return;
        }
        instanceDetails.setLastSuccessfulDataEvent(new Date());
        save(instanceDetails, inputFile);
    }

    public void markHeartbeat(File expandedLevelOpsPluginDir, boolean isHeartbeatSuccess) {
        File inputFile = loadFile(expandedLevelOpsPluginDir);
        JenkinsStatusInfo instanceDetails = getStatus(new File(expandedLevelOpsPluginDir,
                JENKINS_HEARTBEAT_INFO_FILE));
        if (!isHeartbeatSuccess) {
            instanceDetails.setLastFailedHeartbeat(new Date());
            save(instanceDetails, inputFile);
            return;
        }
        instanceDetails.setLastSuccessfulHeartbeat(new Date());
        save(instanceDetails, inputFile);
    }

    public JenkinsStatusInfo getStatus(File resultFile) {
        try {
            String inputStatusString = org.apache.commons.io.FileUtils.readFileToString(resultFile,
                    UTF_8);
            return mapper.readValue(inputStatusString, JenkinsStatusInfo.class);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error reading Jenkins Status File..", e);
        }
        return null;
    }

    @NotNull
    public File loadFile(File inputStatusFile) {
        File inputFile = null;
        try {
            inputFile = createFileIfNotExists(inputStatusFile);
            if (inputFile.length() == 0) {
                mapper.writeValue(inputFile, new JenkinsStatusInfo());
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "JenkinsStatusService.createFileIfNotExists Error creating expandedLevelOpsPluginDir!", e);
        }
        return Objects.requireNonNull(inputFile);
    }

    private void save(JenkinsStatusInfo instanceDetails, File resultFile) {
        try {
            mapper.writeValue(resultFile, instanceDetails);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Jenkins Status could not be written into file..", e);
        }
    }

    private File createFileIfNotExists(File expandedLevelOpsPluginDir) throws IOException {
        try {
            FileUtils.createDirectoryRecursively(expandedLevelOpsPluginDir);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "JenkinsStatusService.createFileIfNotExists Error creating expandedLevelOpsPluginDir!", e);
        }
        File file = new File(expandedLevelOpsPluginDir, JENKINS_HEARTBEAT_INFO_FILE);
        return FileUtils.createFileRecursively(file);
    }
}

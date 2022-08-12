package io.levelops.plugins.levelops_job_reporter.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import io.levelops.plugins.commons.models.JobRunCompleteData;
import io.levelops.plugins.commons.models.JobRunDetail;
import io.levelops.plugins.commons.models.blue_ocean.JobRun;
import io.levelops.plugins.commons.service.JenkinsConfigSCMService;
import io.levelops.plugins.commons.service.JenkinsInstanceGuidService;
import io.levelops.plugins.commons.service.JenkinsLocationConfigParserService;
import io.levelops.plugins.commons.service.ProxyConfigService;
import io.levelops.plugins.commons.service.JobLogsService;
import io.levelops.plugins.commons.service.JobRunCompleteNotificationService;
import io.levelops.plugins.commons.service.JobRunGitChangesService;
import io.levelops.plugins.commons.service.JobRunParserService;
import io.levelops.plugins.commons.service.JobRunPerforceChangesService;
import io.levelops.plugins.commons.service.JobSCMService;
import io.levelops.plugins.commons.service.LevelOpsPluginConfigService;
import io.levelops.plugins.commons.utils.JsonUtils;
import io.levelops.plugins.levelops_job_reporter.plugins.LevelOpsPluginImpl;
import io.levelops.plugins.levelops_job_reporter.service.JobRunCodeCoverageResultsService;
import io.levelops.plugins.levelops_job_reporter.service.JobRunCodeCoverageXmlResultsService;
import io.levelops.plugins.levelops_job_reporter.service.JobRunCompleteDataService;
import io.levelops.plugins.levelops_job_reporter.service.JobRunPostBuildPublisherResultsService;
import io.levelops.plugins.levelops_job_reporter.service.JobRunTestResultsService;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.levelops.plugins.commons.plugins.Common.JOBS_DATA_DIR_NAME;
import static io.levelops.plugins.commons.plugins.Common.RUN_HISTORY_COMPLETE_DATA_DIRECTORY;
import static io.levelops.plugins.commons.plugins.Common.RUN_HISTORY_COMPLETE_DATA_ZIP_FILE;

@Extension
public class LevelOpsRunListener extends RunListener<Run> {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    private final LevelOpsPluginImpl plugin = LevelOpsPluginImpl.getInstance();
    private static final ObjectMapper mapper = JsonUtils.buildObjectMapper();
    private static final ObjectMapper xmlMapper = new XmlMapper();
    private static final String JOB_SUCCESSFUL = "SUCCESS";

    public LevelOpsRunListener() {
    }


    private String getJenkinsInstanceUrl() {
        JenkinsLocationConfigParserService jenkinsLocationConfigParserService = new JenkinsLocationConfigParserService();
        String jenkinsInstanceUrl = jenkinsLocationConfigParserService.parseJenkinsInstanceUrl(plugin.getHudsonHome());
        return jenkinsInstanceUrl;
    }

    private String getJenkinsInstanceGuid() {
        JenkinsInstanceGuidService jenkinsInstanceGuidService = new JenkinsInstanceGuidService(plugin.getExpandedLevelOpsPluginDir(), plugin.getDataDirectory(), plugin.getDataDirectoryWithVersion());
        return jenkinsInstanceGuidService.createOrReturnInstanceGuid();
    }

    private File buildJobRunCompleteDataDirectory(final String jobFullName, final Long jobRunNumber) throws IOException {
        File jobRunCompleteDataDirectory = Paths.get(plugin.getDataDirectoryWithRotation().getAbsolutePath(), JOBS_DATA_DIR_NAME, jobFullName, String.format(RUN_HISTORY_COMPLETE_DATA_DIRECTORY, jobRunNumber)).toFile();
        return jobRunCompleteDataDirectory;
        //return FileUtils.createDirectoryRecursively(jobRunCompleteDataDirectory);
    }

    private File buildJobRunCompleteDataZipFile(final String jobFullName, final Long jobRunNumber) {
        File jobRunCompleteDataZipFile = Paths.get(plugin.getDataDirectoryWithRotation().getAbsolutePath(), JOBS_DATA_DIR_NAME, jobFullName, String.format(RUN_HISTORY_COMPLETE_DATA_ZIP_FILE, jobRunNumber)).toFile();
        return jobRunCompleteDataZipFile;
    }

    private JobRunCompleteData gatherJobRunCompleteData(Run run, JobRunDetail jobRunDetail) {
        File jobRunCompleteDataDirectory;
        try {
            jobRunCompleteDataDirectory = buildJobRunCompleteDataDirectory(jobRunDetail.getJobFullName(), jobRunDetail.getBuildNumber());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error creating job run complete data directory, cannot gather complete data", e);
            return null;
        }

        final ProxyConfigService.ProxyConfig proxyConfig = ProxyConfigService.generateConfigFromJenkinsProxyConfiguration(Jenkins.getInstanceOrNull());

        File jobRunCompleteDataZipFile = buildJobRunCompleteDataZipFile(jobRunDetail.getJobFullName(), jobRunDetail.getBuildNumber());
        JobRunCompleteDataService jobRunCompleteDataService = new JobRunCompleteDataService(plugin, mapper, proxyConfig);
        JobRun jobRun = jobRunCompleteDataService.gatherJobRunData(jobRunDetail, jobRunCompleteDataDirectory);
        LOGGER.log(Level.FINEST, "jobRun = {0}", jobRun);

        JobRunTestResultsService jobRunTestResultsService = new JobRunTestResultsService();
        LOGGER.log(Level.FINEST, "gatherJobRunTestResults starting");
        jobRunTestResultsService.gatherJobRunTestResults(run, jobRunCompleteDataDirectory);
        LOGGER.log(Level.FINEST, "gatherJobRunTestResults completed");

        JobRunCodeCoverageResultsService jobRunCodeCoverageResultsService = new JobRunCodeCoverageResultsService();
        LOGGER.log(Level.FINEST, "gatherJobRunCodeCoverageResults starting");
        jobRunCodeCoverageResultsService.gatherJobRunCodeCoverageResults(run, jobRunCompleteDataDirectory);
        LOGGER.log(Level.FINEST, "gatherJobRunCodeCoverageResults completed");

        LOGGER.log(Level.FINEST, "gatherJobRunCodeCoverageResults for copying files from job to data directory started");
        JobRunPostBuildPublisherResultsService jobRunPostBuildPublisherResultsService = new JobRunPostBuildPublisherResultsService();
        boolean publishedResultsGathered = jobRunPostBuildPublisherResultsService.gatherJobRunCodeCoverageResults(run, jobRunCompleteDataDirectory);
        LOGGER.log(Level.FINEST, "gatherJobRunCodeCoverageResults for copying files from job to data directory completed");
        if (!publishedResultsGathered) {
            JobRunCodeCoverageXmlResultsService jobRunCodeCoverageXmlResultsService = new JobRunCodeCoverageXmlResultsService();
            LOGGER.log(Level.FINEST, "gatherJobRunCodeCoverageXmlResults starting");
            jobRunCodeCoverageXmlResultsService.gatherJobRunCodeCoverageXmlResults(run, plugin.getBullseyeXmlResultPaths(), jobRunCompleteDataDirectory);
            LOGGER.log(Level.FINEST, "gatherJobRunCodeCoverageXmlResults completed");
        }
        return new JobRunCompleteData(jobRun, jobRunCompleteDataDirectory, jobRunCompleteDataZipFile);
    }

    public void onFinalized(Run run) {
        try {
            LOGGER.finer("LevelOpsRunListener.onCompleted");
            if (plugin.isExpandedLevelOpsPluginPathNullOrEmpty()) {
                LOGGER.log(Level.SEVERE, "LevelOps Plugin Directory is invalid, cannot process job run completed event! path: " + plugin.getLevelOpsPluginPath());
                return;
            }
            if (StringUtils.isBlank(plugin.getLevelOpsApiKey())) {
                LOGGER.log(Level.FINE, "LevelOps Api Key is null or empty, will not collect data");
                return;
            }

            if (run == null) {
                LOGGER.finest("Incomplete input received");
                return;
            }
            LOGGER.log(Level.FINEST, "run = {0}", run);

            JobRunParserService jobRunParserService = new JobRunParserService();
            JobRunDetail jobRunDetail = jobRunParserService.parseJobRun(run, plugin.getHudsonHome());
            if (jobRunDetail == null) {
                return;
            }
            LOGGER.log(Level.FINE, "Starting processing complete event jobFullName={0}, build number = {1}", new Object[]{jobRunDetail.getJobFullName(), jobRunDetail.getBuildNumber()});
            LOGGER.finest("jobRunDetail = " + jobRunDetail);
            JobRunGitChangesService jobRunGitChangesService = new JobRunGitChangesService(mapper, plugin.getDataDirectoryWithRotation());
            List<String> scmCommitIds = jobRunGitChangesService.parseGitCommitsForRun(run, jobRunDetail);
            if (scmCommitIds == null)
                scmCommitIds = new ArrayList<>();
            LOGGER.log(Level.FINEST, "scmCommitIds = {0}", scmCommitIds);

            JobRunPerforceChangesService jobRunPerforceChangesService = new JobRunPerforceChangesService(xmlMapper);
            List<String> perforceCommitIds = jobRunPerforceChangesService.parsePerforceCommitsForRun(run);
            if (perforceCommitIds == null)
                perforceCommitIds = new ArrayList<>();
            JenkinsConfigSCMService jenkinsConfigSCMService = new JenkinsConfigSCMService();
            JobSCMService jobSCMService = new JobSCMService(jenkinsConfigSCMService);
            JenkinsConfigSCMService.SCMResult scmResult = jobSCMService.parseSCMConfigForJob(plugin.getHudsonHome(), jobRunDetail.getJobFullName());
            LOGGER.log(Level.FINEST,"scmResult = {0}", scmResult);

            JobRunCompleteData jobRunCompleteData = gatherJobRunCompleteData(run, jobRunDetail);
            scmCommitIds.addAll(perforceCommitIds);
            JobLogsService jobLogsService = new JobLogsService();
            UUID failedLogFileUUID = null;

            if (jobRunCompleteData != null && !jobRunDetail.getResult().equalsIgnoreCase(JOB_SUCCESSFUL) && jobRunCompleteData.getJobRun() == null) {
                //jobRun == null, if BlueOcean is not installed
                failedLogFileUUID = jobLogsService.getLogFailedString(run, jobRunCompleteData.getCompleteDataDirectory());
            }

            ProxyConfigService.ProxyConfig proxyConfig = ProxyConfigService.generateConfigFromJenkinsProxyConfiguration(Jenkins.getInstanceOrNull());

            performJobRunCompleteNotification(jobRunDetail, scmResult.getUrl(), scmResult.getUserName(),
                    getJenkinsInstanceGuid(), plugin.getJenkinsInstanceName(), getJenkinsInstanceUrl(),
                    jobRunCompleteData, scmCommitIds, failedLogFileUUID, proxyConfig);
            //performJobRunCompleteNotification fn logs have this.
            //LOGGER.log(Level.INFO, "Completed processing complete event jobFullName={0}, build number = {1}", new Object[]{jobRunDetail.getJobFullName(), jobRunDetail.getBuildNumber()});
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error in RunListener onCompleted!", e);
        }
    }

    private void performJobRunCompleteNotification(JobRunDetail jobRunDetail, String scmUrl, String scmUserId, String jenkinsInstanceGuid, String jenkinsInstanceName,
                                                   String jenkinsInstanceUrl, JobRunCompleteData jobRunCompleteData, List<String> scmCommitIds, UUID failedLogFileUUID, final ProxyConfigService.ProxyConfig proxyConfig) {

        LOGGER.finest("Send Job Runs Completed Notifications to LevelOps is true, performing job run complete notification");

        JobRunCompleteNotificationService jobRunCompleteNotificationService = new JobRunCompleteNotificationService(LevelOpsPluginConfigService.getInstance().getLevelopsConfig().getApiUrl(), mapper);
        List<String> runIds;
        try {
            String jobFullName = (jobRunDetail != null) ? jobRunDetail.getJobFullName() : null;
            long jobRunNumber = (jobRunDetail != null) ? jobRunDetail.getBuildNumber() : 0;
            runIds = jobRunCompleteNotificationService.submitJobRunCompleteRequest(plugin.getLevelOpsApiKey(), jobRunDetail,
                    scmUrl, scmUserId, jenkinsInstanceGuid, jenkinsInstanceName, jenkinsInstanceUrl, plugin.isTrustAllCertificates(), jobRunCompleteData,
                    scmCommitIds, failedLogFileUUID, proxyConfig);
            LOGGER.log(Level.FINE, "Successfully submitted job run complete event to LevelOps, jobFullName = {0}, jobRunNumber = {1}, runIds = {2}", new Object[]{jobFullName, jobRunNumber, runIds});
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error sending job run complete event!", e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending job run complete event!", e);
        }
    }
}
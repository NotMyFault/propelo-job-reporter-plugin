package io.levelops.plugins.levelops_job_reporter.plugins;

import antlr.ANTLRException;
import hudson.Plugin;
import hudson.scheduler.CronTab;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.VersionNumber;
import io.levelops.plugins.commons.models.JenkinsStatusInfo;
import io.levelops.plugins.commons.models.PluginVersion;
import io.levelops.plugins.commons.models.blue_ocean.Organization;
import io.levelops.plugins.commons.service.BlueOceanRestClient;
import io.levelops.plugins.commons.service.JenkinsInstanceGuidService;
import io.levelops.plugins.commons.service.JenkinsStatusService;
import io.levelops.plugins.commons.service.LevelOpsPluginConfigValidator;
import io.levelops.plugins.commons.service.ProxyConfigService;
import io.levelops.plugins.commons.utils.DateUtils;
import io.levelops.plugins.commons.utils.EnvironmentVariableNotDefinedException;
import io.levelops.plugins.commons.utils.JsonUtils;
import io.levelops.plugins.commons.utils.Utils;
import io.levelops.plugins.levelops_job_reporter.extensions.LevelOpsMgmtLink;
import jenkins.model.Jenkins;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

import javax.net.ssl.SSLException;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.levelops.plugins.commons.plugins.Common.REPORTS_DIR_NAME;

public class LevelOpsPluginImpl extends Plugin {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());
    private static final String DATA_DIR_NAME = "run-complete-data";
    public static final String PLUGIN_SHORT_NAME = "propelo-job-reporter";
    private Secret levelOpsApiKey = Secret.fromString("");
    private String levelOpsPluginPath = "${JENKINS_HOME}/levelops-jenkin";
    private boolean trustAllCertificates = false;
    private String productIds = "";
    private String jenkinsInstanceName = "Jenkins Instance";
    public Boolean isRegistered = false;
    private String jenkinsStatus = "";
    private String jenkinsUserName = "";
    private String jenkinsBaseUrl = "";
    private Secret jenkinsUserToken = Secret.fromString("");
    private long heartbeatDuration = 60;
    private String bullseyeXmlResultPaths = "";
    private long configUpdatedAt = System.currentTimeMillis();
    private static LevelOpsPluginImpl instance = null;
    private static final Pattern OLDER_DIRECTORIES_PATTERN = Pattern.compile("^(run-complete-data-)");

    //ToDo: This is deprecated! Fix soon.
    public LevelOpsPluginImpl() {
        instance = this;
    }

    @Override
    public void start() throws Exception {
        super.start();
        load();
        LOGGER.info("Deleting Older directories during plugin initialization.Started");
        deleteOlderDirectories();
        LOGGER.info("Deleting Older directories during plugin initialization.Completed");
        LOGGER.fine("'" + LevelOpsMgmtLink.PLUGIN_DISPLAY_NAME + "' plugin initialized.");
    }

    public static LevelOpsPluginImpl getInstance() {
//        if(instance == null){
//            instance = new LevelOpsPluginImpl();
//        }
        return instance;
    }

    public File getHudsonHome() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return (jenkins == null) ? null : jenkins.getRootDir();
    }

    public void setJenkinsBaseUrl(final String jenkinsBaseUrl){
        this.jenkinsBaseUrl = jenkinsBaseUrl;
    }

    public String getJenkinsBaseUrl(){
        return this.jenkinsBaseUrl;
    }

    public Secret getLevelOpsApiKey() {
        return levelOpsApiKey;
    }

    public void setLevelOpsApiKey(Secret levelOpsApiKey) {
        this.levelOpsApiKey = levelOpsApiKey;
    }

    /**
     * Get the Propelo plugin path as entered by the user. May contain environment variables.
     *
     * If you need a path that can be used as is (env. vars expanded), please use @link{getExpandedLevelOpsPluginPath}.
     *
     * @return the path as entered by the user.
     */
    public String getLevelOpsPluginPath() {
        return levelOpsPluginPath;
    }

    public void setLevelOpsPluginPath(String levelOpsPluginPath) {
        this.levelOpsPluginPath = levelOpsPluginPath;
    }

    /**
     * @return the levelOpsPluginPath path with possibly contained environment variables expanded.
     */
    public String getExpandedLevelOpsPluginPath() {
        if (StringUtils.isBlank(levelOpsPluginPath)) {
            return levelOpsPluginPath;
        }
        String expandedPath = "";
        try {
            expandedPath = Utils.expandEnvironmentVariables(levelOpsPluginPath);
        } catch (final EnvironmentVariableNotDefinedException evnde) {
            LOGGER.log(Level.SEVERE, evnde.getMessage() + " Using unexpanded path.");
            expandedPath = levelOpsPluginPath;
        }

        return expandedPath;
    }

    public String getJenkinsStatus() {
        return jenkinsStatus;
    }

    public void setJenkinsStatus(String jenkinsStatus) {
        this.jenkinsStatus = jenkinsStatus;
    }

    public long getConfigUpdatedAt() {
        return configUpdatedAt;
    }

    public void setConfigUpdatedAt(long configUpdatedAt) {
        this.configUpdatedAt = configUpdatedAt;
    }

    public long getHeartbeatDuration() {
        return heartbeatDuration;
    }

    public void setHeartbeatDuration(long heartbeatDuration) {
        this.heartbeatDuration = heartbeatDuration;
    }

    public File getExpandedLevelOpsPluginDir() {
        return new File(this.getExpandedLevelOpsPluginPath());
    }


    public Boolean isRegistered() {
        return isRegistered;
    }

    public boolean isExpandedLevelOpsPluginPathNullOrEmpty(){
        return StringUtils.isEmpty(getExpandedLevelOpsPluginPath());
    }

    private File buildReportsDirectory(String levelOpsPluginPath){
        return new File(levelOpsPluginPath,REPORTS_DIR_NAME);
    }
    public File getReportsDirectory() {
        return buildReportsDirectory(this.getExpandedLevelOpsPluginPath());
    }

    private void deleteOlderDirectories() {
        File currentDataDirectoryWithVersion = getDataDirectoryWithVersion();
        File expandedLevelOpsPluginDir = getExpandedLevelOpsPluginDir();
        if (expandedLevelOpsPluginDir != null && expandedLevelOpsPluginDir.exists()
                && currentDataDirectoryWithVersion != null) {
            for (File file : Objects.requireNonNull(expandedLevelOpsPluginDir.listFiles())) {
                Matcher matcher = OLDER_DIRECTORIES_PATTERN.matcher(file.getName());
                if (matcher.find() && !file.getName().equalsIgnoreCase(currentDataDirectoryWithVersion.getName())) {
                    FileUtils.deleteQuietly(file);
                }
            }
        }
    }

    public String getPluginVersionString() {
        LOGGER.log(Level.FINEST, "getPluginVersionString starting");
        String pluginVersionString = Jenkins.get().getPluginManager().getPlugin(LevelOpsPluginImpl.PLUGIN_SHORT_NAME).getVersion();
        LOGGER.log(Level.FINEST, "getPluginVersionString completed pluginVersionString = {0}", pluginVersionString);
        return pluginVersionString;
    }

    private File buildDataDirectory(String levelOpsPluginPath) {
        LOGGER.log(Level.FINEST, "buildDataDirectory starting");
        File dataDirectory = new File(levelOpsPluginPath, DATA_DIR_NAME);
        LOGGER.log(Level.FINEST, "buildDataDirectory completed = {0}", dataDirectory);
        return dataDirectory;
    }
    public File getDataDirectory() {
        return buildDataDirectory(this.getExpandedLevelOpsPluginPath());
    }

    private File buildDataDirectoryWithVersion(String levelOpsPluginPath) {
        LOGGER.log(Level.FINEST, "buildDataDirectoryWithVersion starting");
        String dataDirWithVersionName = DATA_DIR_NAME + "-" + getPluginVersionString();
        LOGGER.log(Level.FINEST, "dataDirWithVersionName = {0}", dataDirWithVersionName);
        File dataDirectoryWithVersion = new File(levelOpsPluginPath, dataDirWithVersionName);
        LOGGER.log(Level.FINEST, "buildDataDirectoryWithVersion completed = {0}", dataDirectoryWithVersion);
        return dataDirectoryWithVersion;
    }
    public File getDataDirectoryWithVersion() {
        return buildDataDirectoryWithVersion(this.getExpandedLevelOpsPluginPath());
    }

    public File getDataDirectoryWithRotation() {
        File dataDirWithVersion = buildDataDirectoryWithVersion(this.getExpandedLevelOpsPluginPath());
        LOGGER.log(Level.FINEST, "dataDirWithVersion = {0}", dataDirWithVersion);
        File dataDirWithRotation = new File(dataDirWithVersion, DateUtils.getDateFormattedDirName());
        LOGGER.log(Level.FINEST, "dataDirWithRotation = {0}", dataDirWithRotation);
        return dataDirWithRotation;
    }

    public String getJenkinsUserName() {
        return jenkinsUserName;
    }

    public void setJenkinsUserName(String jenkinsUserName) {
        this.jenkinsUserName = jenkinsUserName;
    }

    public Secret getJenkinsUserToken() {
        return jenkinsUserToken;
    }

    public void setJenkinsUserToken(Secret jenkinsUserToken) {
        this.jenkinsUserToken = jenkinsUserToken;
    }

    public String getBullseyeXmlResultPaths() {
        return bullseyeXmlResultPaths;
    }

    public void setBullseyeXmlResultPath(String bullseyeXmlResultPaths) {
        this.bullseyeXmlResultPaths = bullseyeXmlResultPaths;
    }

    public boolean isTrustAllCertificates() {
        return trustAllCertificates;
    }

    public void setTrustAllCertificates(boolean trustAllCertificates) {
        this.trustAllCertificates = trustAllCertificates;
    }

    public String getProductIds() {
        return productIds;
    }

    public void setProductIds(String productIds) {
        this.productIds = productIds;
    }

    private List<String> parseProductIdsList(String productIds){
        if((productIds == null) || (productIds.length() == 0)){
            return Collections.emptyList();
        }
        String[] productIdsSplit = productIds.split(",");
        if((productIdsSplit == null) || (productIdsSplit.length ==0)){
            return Collections.emptyList();
        }
        return Arrays.asList(productIdsSplit);
    }
    public List<String> getProductIdsList(){
        return parseProductIdsList(this.productIds);
    }

    public String getJenkinsInstanceName() {
        return jenkinsInstanceName;
    }

    public void setJenkinsInstanceName(String jenkinsInstanceName) {
        this.jenkinsInstanceName = jenkinsInstanceName;
    }

    @POST
    public FormValidation doCheckLevelOpsApiKey(final StaplerRequest res, final StaplerResponse rsp,
                                                @QueryParameter("value") final Secret levelOpsApiKey) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JenkinsInstanceGuidService jenkinsInstanceGuidService = new JenkinsInstanceGuidService(
                instance.getExpandedLevelOpsPluginDir(),
                instance.getDataDirectory(), instance.getDataDirectoryWithVersion());
        ProxyConfigService.ProxyConfig proxyConfig = ProxyConfigService.generateConfigFromJenkinsProxyConfiguration(Jenkins.getInstanceOrNull());
        return LevelOpsPluginConfigValidator.performApiKeyValidation(levelOpsApiKey, trustAllCertificates,
                jenkinsInstanceGuidService.createOrReturnInstanceGuid(), instance.getJenkinsInstanceName(), instance.getPluginVersionString(), proxyConfig);
    }

    public FormValidation doCheckLevelOpsStatus(final StaplerRequest res, final StaplerResponse rsp,
                                                @QueryParameter("value") final String levelOpsStatus) {
        File resultFile = JenkinsStatusService.getInstance().loadFile(instance.getExpandedLevelOpsPluginDir());
        JenkinsStatusInfo details = JenkinsStatusService.getInstance().getStatus(resultFile);
        if (isRegistered()) { // Instance is registered...
            if (details.getLastFailedHeartbeat().after(details.getLastSuccessfulHeartbeat())) {
                setJenkinsStatus("Trying to connect to LevelOps. Disconnected since " + details.getLastSuccessfulHeartbeat());
            } else {
                setJenkinsStatus("Success, connected since " + details.getLastSuccessfulHeartbeat());
            }
            return FormValidation.ok();
        } else {
            setJenkinsStatus("Registering Jenkins Instance....");
        }
        return FormValidation.ok();
    }

    public FormValidation doCheckMonitoringSchedule(final StaplerRequest res, final StaplerResponse rsp,
                                                    @QueryParameter("value") final String schedule) {
        if ((schedule != null) && !schedule.isEmpty()) {
            String message;
            try {
                message = new CronTab(schedule).checkSanity();
            } catch (final ANTLRException e) {
                return FormValidation.error("Invalid cron schedule. " + e.getMessage());
            }
            if (message != null) {
                return FormValidation.warning("Cron schedule warning: " + message);
            } else {
                return FormValidation.ok();
            }
        } else {
            return FormValidation.ok();
        }
    }

    @POST
    public FormValidation performBlueOceanRestValidation(String jenkinsBaseUrl, String jenkinsUserName, String jenkinsUserToken, boolean baseUrlValidation, boolean userNameValidation, boolean userTokenValidation){
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        LOGGER.log(Level.FINEST, "jenkinsBaseUrl = {0}", jenkinsBaseUrl );
        LOGGER.log(Level.FINEST, "jenkinsUserName = {0}", jenkinsUserName );
        LOGGER.log(Level.FINEST, "jenkinsUserToken = {0}", jenkinsUserToken );
        LOGGER.log(Level.FINEST, "baseUrlValidation = {0},userNameValidation = {1},userTokenValidation = {2}", new Object[]{baseUrlValidation, userNameValidation, userTokenValidation} );

        ProxyConfigService.ProxyConfig proxyConfig = ProxyConfigService.generateConfigFromJenkinsProxyConfiguration(Jenkins.getInstanceOrNull());

        BlueOceanRestClient restClient = new BlueOceanRestClient(jenkinsBaseUrl, jenkinsUserName, jenkinsUserToken, trustAllCertificates, JsonUtils.buildObjectMapper(), proxyConfig);
        List<Organization> organizations = null;
        try {
            organizations = restClient.getOrganizations();
            return FormValidation.ok();
        } catch (SSLException e) {
            if(baseUrlValidation) {
                return FormValidation.error("SSL Exception connecting to jenkins api. Please check http/https setting in jenkins base url! : " + jenkinsBaseUrl);
            } else {
                return FormValidation.ok();
            }
        }catch (UnknownHostException e) {
            if(baseUrlValidation) {
                return FormValidation.error("jenkins base url is not correct. host cannot be reached! : " + jenkinsBaseUrl);
            } else {
                return FormValidation.ok();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IOException!!", e);
            String msg = e.getMessage();
            if(msg.startsWith("Response not successful: 404")) {
                if(baseUrlValidation) {
                    return FormValidation.error("blue ocean rest api is not reachable! Please check if plugin REST Implementation for Blue Ocean is installed!");
                } else {
                    return FormValidation.ok();
                }
            } else if (msg.startsWith("Response not successful: 401")) {
                if(userNameValidation || userTokenValidation) {
                    return FormValidation.error("jenkins user name and/or user token is not valid.");
                } else {
                    return FormValidation.ok();
                }
            } else if (msg.startsWith("Response not successful: 403")) {
                if(baseUrlValidation) {
                    return FormValidation.error("SSL Exception connecting to jenkins api. Please check http/https setting in jenkins base url! : " + jenkinsBaseUrl);
                } else {
                    return FormValidation.ok();
                }
            } else {
                if(baseUrlValidation) {
                    return FormValidation.error("Validation failed! Error " + msg);
                }
            }
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckJenkinsBaseUrl(final StaplerRequest res, final StaplerResponse rsp,
                                                @QueryParameter("value") final String jenkinsBaseUrl) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if(StringUtils.isBlank(jenkinsBaseUrl)) {
            return FormValidation.error("Jenkins Base Url cannot be null or empty!");
        } else {
            return performBlueOceanRestValidation(Jenkins.get().getRootUrl(), jenkinsUserName, jenkinsUserToken.getPlainText(), true, false, false);
        }
    }

    @POST
    public FormValidation doCheckJenkinsUserName(final StaplerRequest res, final StaplerResponse rsp,
                                                 @QueryParameter("value") final String jenkinsUserName) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if(StringUtils.isBlank(jenkinsUserName)) {
            return FormValidation.error("Jenkins User Name cannot be null or empty!");
        } else {
            return performBlueOceanRestValidation(Jenkins.get().getRootUrl(), jenkinsUserName, jenkinsUserToken.getPlainText(), false, true, false);
        }
    }

    @POST
    public FormValidation doCheckJenkinsUserToken(final StaplerRequest res, final StaplerResponse rsp,
                                                  @QueryParameter("value") final Secret jenkinsUserToken) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        LOGGER.log(Level.FINEST, "jenkinsUserToken = {0}", jenkinsUserToken);
        if(jenkinsUserToken != null && StringUtils.isBlank(jenkinsUserToken.getPlainText())) {
            return FormValidation.error("Jenkins User Token cannot be null or empty!");
        } else {
            //return performBlueOceanRestValidation(jenkinsBaseUrl, jenkinsUserName, jenkinsUserToken, false, false, true);
            return FormValidation.ok();
        }
    }

    public FormValidation doCheckJenkinsInstanceName(final StaplerRequest res, final StaplerResponse rsp,
                                                     @QueryParameter("value") final String jenkinsInstanceName) {
        if((jenkinsInstanceName == null) || (jenkinsInstanceName.length() == 0)){
            return FormValidation.error("Jenkins Instance Name should not be null or empty.");
        } else {
            return FormValidation.ok();
        }
    }

    @POST
    public FormValidation doCheckLevelOpsPluginPath(final StaplerRequest res, final StaplerResponse rsp,
                                                    @QueryParameter("value") final String path) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if ((path == null) || path.trim().isEmpty()) {
            return FormValidation.error("Reports path must not be empty.");
        }

        String expandedPathMessage = "";
        String expandedPath = "";
        try {
            expandedPath = Utils.expandEnvironmentVariables(path);
        } catch (final EnvironmentVariableNotDefinedException evnd) {
            return FormValidation.error(evnd.getMessage());
        }
        if (!expandedPath.equals(path)) {
            expandedPathMessage = String.format("The path will be expanded to '%s'.%n%n", expandedPath);
        }

        final File expandedLevelOpsPluginPath = new File(expandedPath);
        if (expandedLevelOpsPluginPath.exists()){
            if (!expandedLevelOpsPluginPath.isDirectory()) {
                return FormValidation.error(expandedPathMessage
                        + "A file with this name exists, thus a directory with the same name cannot be created.");
            }
        } else {
            if (!expandedPath.trim().equals(expandedPath)) {
                return FormValidation.warning(expandedPathMessage
                        + "Path contains leading and/or trailing whitespaces - is this intentional?");
            }
            File reportsDirectory = buildReportsDirectory(expandedPath);
            if(!reportsDirectory.exists()) {
                if(!reportsDirectory.mkdirs()){
                    return FormValidation.error(reportsDirectory + " The Reports directory could not be created. Please check path and write permissions.");
                }
            }
            File dataDirectoryWithVersion = buildDataDirectoryWithVersion(expandedPath);
            if(!dataDirectoryWithVersion.exists()) {
                if(!dataDirectoryWithVersion.mkdirs()){
                    return FormValidation.error(dataDirectoryWithVersion + " The data directory with version could not be created. Please check path and write permissions.");
                }
            }
        }
        if (!expandedPathMessage.isEmpty()) {
            return FormValidation.ok(expandedPathMessage.substring(0, expandedPathMessage.length() - 2));
        } else {
            return FormValidation.ok();
        }
    }

    public FormValidation doCheckBullseyeXmlResultPaths(final StaplerRequest res, final StaplerResponse rsp,
                                            @QueryParameter("value") final String bullseyeXmlResultPaths) {
        return LevelOpsPluginConfigValidator.validateBullseyeXmlResultPaths(bullseyeXmlResultPaths);
    }

}
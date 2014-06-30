/*
 * Copyright 2009-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cloudfoundry.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.cloudfoundry.client.lib.domain.CloudApplication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Alexander Orlov
 * @goal delete-obsolete-builds
 * @since 1.0.6
 */
public class DeleteObsoleteBuilds extends AbstractApplicationAwareCloudFoundryMojo {
    private static final String BUILD_NUMBER_GROUP = "buildNumber";
    private static final String APP_NAME_WITHOUT_BUILD_NUM_GROUP = "appNameWithoutBuildNumber";
    private static final String APP_NAME_INFIX_REGEX = "[\\w\\d-]+-b";

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        List<CloudApplication> appBuilds = getAllAppInstances();

        if (!appBuilds.isEmpty()) {
            executeGoal(appBuilds);
        }
    }

    private void executeGoal(List<CloudApplication> appBuilds) {
        List<Integer> buildNumbers = new LinkedList<>();
        Map<Integer, CloudApplication> appBuildsAssignment = new HashMap<>();
        for (CloudApplication appBuild : appBuilds) {
            Integer buildNumber = extractBuildNumber(appBuild.getName());
            buildNumbers.add(buildNumber);
            appBuildsAssignment.put(buildNumber, appBuild);
        }

        reverseSortBuildNumbers(buildNumbers);

        deleteObsoleteBuilds(buildNumbers, appBuildsAssignment);
        removePrimaryUrlFromRetiredBuilds(buildNumbers, appBuildsAssignment); // should be called before deleteObsoleteBuilds
    }

    private void reverseSortBuildNumbers(List<Integer> buildNumbers) {
        Collections.sort(buildNumbers);
        Collections.reverse(buildNumbers);
    }

    private void deleteObsoleteBuilds(List<Integer> buildNumbers, Map<Integer, CloudApplication> appBuildsMap) {
        if (getNumberOfBuildsToRetain() < buildNumbers.size()) {
            List<Integer> buildsToDelete = buildNumbers.subList(getNumberOfBuildsToRetain(), buildNumbers.size());
            for (Integer buildNum : buildsToDelete) {
                deleteAppBuild(appBuildsMap.get(buildNum));
            }
        }
    }

    private void removePrimaryUrlFromRetiredBuilds(List<Integer> buildNumbers, Map<Integer, CloudApplication> appBuildsMap) {
        int idxToStartUrlRemappingFrom = 1;
        List<Integer> buildNumbersToRemap = buildNumbers.subList(idxToStartUrlRemappingFrom, buildNumbers.size());
        for (Integer buildNum : buildNumbersToRemap) {
            CloudApplication retiredApp = appBuildsMap.get(buildNum);
            List<String> retiredAppUrls = retiredApp.getUris();
            List<String> secondaryUrlsOfRetiredApp = getSecondaryUrls(retiredAppUrls);
            updateAppUrls(retiredApp, secondaryUrlsOfRetiredApp);
        }
    }

    private List<String> getSecondaryUrls(List<String> urls) {
        // TODO check whether DeleteObsoleteBuilds#getCiDeployedAppName() could be used here
        Pattern secondaryUrl = Pattern.compile("(?<" + APP_NAME_WITHOUT_BUILD_NUM_GROUP + ">" + getArtifactId() + APP_NAME_INFIX_REGEX + ")\\d+");
        List<String> secondaryUrls = new ArrayList<>();
        for (String url : urls) {
            Matcher ciDeployed = secondaryUrl.matcher(url);
            if (ciDeployed.find()) {
                secondaryUrls.add(url);
            }
        }
        return secondaryUrls;
    }

    private void updateAppUrls(CloudApplication retiredApp, List<String> secondaryUrls) {
        System.out.println("Update app URLs: " + retiredApp.getName());
        try {
            // TODO might fail when application is not "started" => assure that app is "started"
            getClient().updateApplicationUris(retiredApp.getName(), secondaryUrls);
        } catch (Exception e) {
            System.out.println(String.format("An error occurred updating URLs of '%s': %s. This app might not exist anymore.", retiredApp.getName(), e.getMessage()));
        }
    }

    private void deleteAppBuild(CloudApplication appBuild) {
        System.out.println("Delete obsolete app: " + appBuild.getName());
        getClient().deleteApplication(appBuild.getName());
    }

    private List<CloudApplication> getAllAppInstances() {
        List<CloudApplication> applications = getClient().getApplications();
        List<CloudApplication> appBuilds = new ArrayList<>();
        for (CloudApplication application : applications) {
            if (isBuildOfApp(application.getName())) {
                appBuilds.add(application);
            }
        }
        return appBuilds;
    }

    /**
     * TODO Using build numbers is a workaround for missing application timestamps (API insufficiency)
     * that show when an application has been deployed. So build numbers are used to infer the deployment order.
     */
    private int extractBuildNumber(String appName) {
        Matcher buildNumber = getCiDeployedAppName().matcher(appName);

        if (buildNumber.find()) {
            return Integer.parseInt(buildNumber.group(BUILD_NUMBER_GROUP));
        } else {
            throw new NumberFormatException("App name format is not supported. A malfunction in the deployment process may have occurred.");
        }
    }

    private boolean isBuildOfApp(String someDeployedAppName) {
        String versionAgnosticBuildRegex = "[\\w\\d-]+-v)[\\d-]+-b";
        String versionAgnosticAppNameWithoutBuildNumber = "versionAgnosticAppNameWithoutBuildNumber";
        Pattern versionAgnosticBuild = Pattern.compile("(?<" + versionAgnosticAppNameWithoutBuildNumber + ">" + getArtifactId() + versionAgnosticBuildRegex + "(?<" + BUILD_NUMBER_GROUP + ">\\d+)$");
        Matcher ciDeployed = versionAgnosticBuild.matcher(getAppname());
        if (ciDeployed.find()) {
            String appNameWithoutVersionAndBuildNumber = ciDeployed.group(versionAgnosticAppNameWithoutBuildNumber);
            return someDeployedAppName.startsWith(appNameWithoutVersionAndBuildNumber);
        } else {
            return false;
        }
    }

    private Pattern getCiDeployedAppName() {
        // TODO making this a configuration property would make this goal more generic
        return Pattern.compile("(?<" + APP_NAME_WITHOUT_BUILD_NUM_GROUP + ">" + getArtifactId() + APP_NAME_INFIX_REGEX + ")(?<" + BUILD_NUMBER_GROUP + ">\\d+)$");
    }
}

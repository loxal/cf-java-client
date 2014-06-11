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
import org.cloudfoundry.client.lib.CloudFoundryClient;
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
 * Push and optionally start an application without forking the build to execute 'package'.
 *
 * @author Alexander Orlov
 * @goal cleanup
 * @since 1.0.4
 */
public class Cleanup extends AbstractApplicationAwareCloudFoundryMojo {

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        List<CloudApplication> appBuilds = getAllAppInstances();

        List<Integer> buildNumbers = new LinkedList<>();
        Map<Integer, CloudApplication> appBuildsAssignment = new HashMap<>();
        for (CloudApplication appBuild : appBuilds) {
            Integer buildNumber = extractBuildNumber(appBuild.getName());
            buildNumbers.add(buildNumber);
            appBuildsAssignment.put(buildNumber, appBuild);
        }

        Collections.sort(buildNumbers);
        Collections.reverse(buildNumbers);

        // should be called before deleteObsoleteBuilds, otherwise an exception occurs
        removePrimaryUrlFromRetiredBuilds(buildNumbers, appBuildsAssignment);

        deleteObsoleteBuilds(buildNumbers, appBuildsAssignment);
    }

    private void deleteObsoleteBuilds(List<Integer> buildNumbers, Map<Integer, CloudApplication> appBuildsMap) {
        List<Integer> buildsToDelete = buildNumbers.subList(getNumberOfBuildsToRetain(), buildNumbers.size());
        for (Integer buildNum : buildsToDelete) {
            deleteAppBuild(appBuildsMap.get(buildNum));
        }
    }

    private void removePrimaryUrlFromRetiredBuilds(List<Integer> buildNumbers, Map<Integer, CloudApplication> appBuildsMap) {
        int idxToStartUrlRemappingFrom = 1;
        List<Integer> buildNumbersToRemap =  buildNumbers.subList(idxToStartUrlRemappingFrom, buildNumbers.size());
        for (Integer buildNum : buildNumbersToRemap) {
            CloudApplication retiredApp =  appBuildsMap.get(buildNum);
            List<String> retiredAppUrls = retiredApp.getUris();
            List<String> secondaryUrlsOfRetiredApp = getSecondaryUrls(retiredAppUrls);
            updateAppUrls(retiredApp, secondaryUrlsOfRetiredApp);
        }
    }

    private List<String> getSecondaryUrls(List<String> urls) {
        Pattern ciDeployedAppName = Pattern.compile("(?<" + appNameWithoutBuildNumGroup + ">" + getArtifactId() + appNameInfixRegex + ")\\d+"); // TODO don't duplicate this
        List<String> secondaryUrls = new ArrayList<>();
        for (String url : urls) {
            Matcher ciDeployed = ciDeployedAppName.matcher(url);
            if (ciDeployed.find()) {
                secondaryUrls.add(url);
            }
        }
        return secondaryUrls;
    }

    private void updateAppUrls(CloudApplication retiredApp, List<String> secondaryUrls) {
        System.out.println("Update app URLs: " + retiredApp.getName());
        getClient().updateApplicationUris(retiredApp.getName(), secondaryUrls); // TODO make getClient() a field
    }

    private void deleteAppBuild(CloudApplication appBuild) {
        System.out.println("Delete obsolete app: " + appBuild.getName());
        getClient().deleteApplication(appBuild.getName()); // TODO make getClient() a field
    }


    private List<CloudApplication> getAllAppInstances() {
        CloudFoundryClient cfClient = getClient(); // TODO make getClient() a field
        List<CloudApplication> applications = cfClient.getApplications();
        List<CloudApplication> appBuilds = new ArrayList<>();
        for (CloudApplication application : applications) {
            // TODO consider renaming it to isBuildOfApp
            if (isVersionBuildOfApp(application.getName())) {
                appBuilds.add(application);
            }
        }
        return appBuilds;
    }

    /**
     * TODO Using build numbers is a workaround for missing application timestamps (API insufficiency)
     * that show when an application has been deployed. So build numbers are used to infer the deployment order.
     *
     * @param appName
     * @return
     */
    private int extractBuildNumber(String appName) {
        String buildNumberGroup = "buildNumber";
        Pattern appBuildNumber = Pattern.compile(getArtifactId() + appNameInfixRegex + "(?<" + buildNumberGroup + ">\\d+)$");
        Matcher buildNumber = appBuildNumber.matcher(appName);

        if (buildNumber.find()) {
            return Integer.parseInt(buildNumber.group(buildNumberGroup));
        } else {
            throw new NumberFormatException("App name format is not supported. A malfunction in the deployment process may have occurred.");
        }
    }

    String appNameWithoutBuildNumGroup = "appNameWithoutBuildNumber";
    String appNameInfixRegex = "[\\w\\d-]+-b";

    private boolean isVersionBuildOfApp(String appName) {
        Pattern ciDeployedAppName = Pattern.compile("(?<" + appNameWithoutBuildNumGroup + ">" + getArtifactId() + appNameInfixRegex + ")\\d+$"); // TODO don't duplicate this
        Matcher ciDeployed = ciDeployedAppName.matcher(getAppname());
        if (ciDeployed.find()) {
            String appNameWithoutBuildNum = ciDeployed.group(appNameWithoutBuildNumGroup);
            return appName.startsWith(appNameWithoutBuildNum);
        } else {
            return false;
        }
    }
}

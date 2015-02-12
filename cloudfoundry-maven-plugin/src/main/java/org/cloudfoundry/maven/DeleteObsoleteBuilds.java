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
	private static final String APP_NAME_INFIX_REGEX = ".+-b";

	private List<Integer> buildNumbers = new ArrayList<>();
	private Map<Integer, CloudApplication> appBuildsAssignment = new HashMap<>();

	@Override
	protected void doExecute() throws MojoExecutionException, MojoFailureException {
		List<CloudApplication> appBuilds = getAllAppInstances();

		if (!appBuilds.isEmpty()) {
			executeGoal(appBuilds);
		}
	}

	private void executeGoal(List<CloudApplication> appBuilds) {
		for (CloudApplication appBuild : appBuilds) {
			Integer buildNumber = extractBuildNumber(appBuild.getName());
			buildNumbers.add(buildNumber);
			appBuildsAssignment.put(buildNumber, appBuild);
		}

		deleteObsoleteBuilds();
		removePrimaryUrlFromRetiredBuilds();
		if (getStopNonPrimaryApps()) {
			stopAllNonPrimaryApps();
		}
	}

	private void stopAllNonPrimaryApps() {
		Collections.sort(buildNumbers);
		for (int idx = 0; idx < buildNumbers.size() - 1; idx++) {
			final String appToStop = appBuildsAssignment.get(buildNumbers.get(idx)).getName();
			getClient().stopApplication(appToStop);
			getLog().info("Stopping app (if not already stopped)… " + appToStop);
		}
	}

	private void reverseSortBuildNumbers() {
		Collections.sort(buildNumbers);
		Collections.reverse(buildNumbers);
	}

	private void deleteObsoleteBuilds() {
		reverseSortBuildNumbers();
		if (getNumberOfBuildsToRetain() < buildNumbers.size()) {
			List<Integer> buildsToDelete = buildNumbers.subList(getNumberOfBuildsToRetain(), buildNumbers.size());
			for (Integer buildNum : buildsToDelete) {
				deleteAppBuild(appBuildsAssignment.get(buildNum));
				updateBuildNumberToAppMapping(buildNum);
			}
		}
	}

	private void updateBuildNumberToAppMapping(Integer buildNum) {
		appBuildsAssignment.remove(buildNum);
		buildNumbers.remove(buildNum);
	}

	private void removePrimaryUrlFromRetiredBuilds() {
		reverseSortBuildNumbers();
		int idxToStartUrlRemappingFrom = 1;
		List<Integer> buildNumbersToRemap = buildNumbers.subList(idxToStartUrlRemappingFrom, buildNumbers.size());
		for (Integer buildNum : buildNumbersToRemap) {
			CloudApplication retiredApp = appBuildsAssignment.get(buildNum);
			List<String> retiredAppUrls = retiredApp.getUris();
			List<String> secondaryUrlsOfRetiredApp = getSecondaryUrls(retiredAppUrls);
			updateAppUrls(retiredApp, secondaryUrlsOfRetiredApp);
		}
	}

	private List<String> getSecondaryUrls(List<String> urls) {
		Pattern secondaryUrl = getCiDeployedAppName();
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
		getLog().info("Update app URLs: " + retiredApp.getName());
		try {
			// TODO might fail when application is not "started" => assure that app is "started"
			getClient().updateApplicationUris(retiredApp.getName(), secondaryUrls);
		} catch (Exception e) {
			getLog().info(String.format("An error occurred updating URLs of '%s': %s. This app might not exist anymore.", retiredApp.getName(), e.getMessage()));
		}
	}

	private void deleteAppBuild(CloudApplication appBuild) {
		getLog().info("Delete obsolete app: " + appBuild.getName());
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
	 * TODO Using build numbers is a workaround for missing application timestamps (CF v2 API insufficiency)
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
		String versionAgnosticBuildRegex = ".+-v)"+ APP_NAME_INFIX_REGEX;
		String versionAgnosticAppNameWithoutBuildNumber = "versionAgnosticAppNameWithoutBuildNumber";
		Pattern versionAgnosticBuild = Pattern.compile("(?<" + versionAgnosticAppNameWithoutBuildNumber + ">" + getAppIdPrefix() + versionAgnosticBuildRegex + "(?<" + BUILD_NUMBER_GROUP + ">\\d+)$");
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
		return Pattern.compile("(?<" + APP_NAME_WITHOUT_BUILD_NUM_GROUP + ">" + getAppIdPrefix() + APP_NAME_INFIX_REGEX + ")(?<" + BUILD_NUMBER_GROUP + ">\\d+)$");
	}
}

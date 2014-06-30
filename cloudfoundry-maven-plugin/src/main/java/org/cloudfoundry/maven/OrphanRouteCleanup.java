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
import org.cloudfoundry.client.lib.domain.CloudDomain;
import org.cloudfoundry.client.lib.domain.CloudRoute;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Alexander Orlov
 * @goal orphan-route-cleanup
 * @since 1.1.5
 */
public class OrphanRouteCleanup extends AbstractApplicationAwareCloudFoundryMojo {
    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {
        List<CloudRoute> orphanRoutes = new ArrayList<>();
        for (CloudDomain cloudDomain : fetchOrgDomains()) {
            orphanRoutes.addAll(fetchOrphanRoutes(cloudDomain.getName()));
        }

        for (CloudRoute orphanRoute : orphanRoutes) {
            deleteRoute(orphanRoute.getHost(), orphanRoute.getDomain());
        }
    }

    private void deleteRoute(String host, CloudDomain domain) {
        System.out.printf("Delete route: %s.%s\n", host, domain.getName());
        getClient().deleteRoute(host, domain.getName());
    }

    private List<CloudDomain> fetchOrgDomains() {
        return getClient().getDomainsForOrg();
    }


    private List<CloudRoute> fetchOrphanRoutes(String domainName) {
        List<CloudRoute> orphanRoutes = new ArrayList<>();
        for (CloudRoute cloudRoute : getClient().getRoutes(domainName)) {
            if (isOrphanRoute(cloudRoute)) {
                orphanRoutes.add(cloudRoute);
            }
        }

        return orphanRoutes;
    }

    private boolean isOrphanRoute(CloudRoute cloudRoute) {
        return cloudRoute.getAppsUsingRoute() == 0;
    }
}

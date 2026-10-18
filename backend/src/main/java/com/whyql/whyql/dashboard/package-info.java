/**
 * Dashboard: pinned job results ("cards") on a user's dashboard. Cross-module
 * surface: {@code services} (DashboardService) and {@code domain}
 * (DashboardCard entity, mapped by the api layer). Repository is internal.
 */
@ApplicationModule(displayName = "Dashboard",
    allowedDependencies = { "jobs::domain" })
package com.whyql.whyql.dashboard;

import org.springframework.modulith.ApplicationModule;

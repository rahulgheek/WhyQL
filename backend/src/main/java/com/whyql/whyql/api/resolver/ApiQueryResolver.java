package com.whyql.whyql.api.resolver;

import java.util.List;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import com.whyql.whyql.api.dashboard.DashboardCardMapper;
import com.whyql.whyql.api.model.DataSource;
import com.whyql.whyql.api.model.DashboardCard;
import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.api.job.JobMapper;
import com.whyql.whyql.dashboard.service.DashboardService;
import com.whyql.whyql.datasource.service.DataSourceService;
import com.whyql.whyql.jobs.service.JobService;

/**
 * Query resolvers: data source listing and authorized job views.
 */
@Controller
public class ApiQueryResolver {

  private final DataSourceService dataSourceService;
  private final JobService jobService;
  private final JobMapper jobMapper;
  private final DashboardService dashboardService;
  private final DashboardCardMapper dashboardCardMapper;

  public ApiQueryResolver(DataSourceService dataSourceService, JobService jobService,
      JobMapper jobMapper, DashboardService dashboardService,
      DashboardCardMapper dashboardCardMapper) {
    this.dataSourceService = dataSourceService;
    this.jobService = jobService;
    this.jobMapper = jobMapper;
    this.dashboardService = dashboardService;
    this.dashboardCardMapper = dashboardCardMapper;
  }

  @QueryMapping
  public List<DataSource> dataSources(Authentication auth) {
    AuthGuards.requireUser(auth);
    return dataSourceService.list(auth.getName()).stream()
        .map(DataSource::from)
        .toList();
  }

  @QueryMapping
  public Job job(@Argument java.util.UUID id, Authentication auth) {
    com.whyql.whyql.jobs.domain.Job job = AuthGuards.requireOwnJob(jobService, id, auth);
    return jobMapper.toGql(job);
  }

  @QueryMapping
  public List<DashboardCard> dashboard(Authentication auth) {
    AuthGuards.requireUser(auth);
    return dashboardService.list(auth.getName()).stream()
        .map(dashboardCardMapper::toGql)
        .toList();
  }
}

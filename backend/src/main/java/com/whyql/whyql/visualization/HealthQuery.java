package com.whyql.whyql.visualization;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/** Minimal GraphQL surface so the Boot GraphQL auto-configuration boots in Phase 1; the real schema lands in Phase 6. */
@Controller
public class HealthQuery {

  @QueryMapping
  public String health() {
    return "UP";
  }
}

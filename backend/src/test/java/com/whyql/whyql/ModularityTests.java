package com.whyql.whyql;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build on illegal inter-module dependencies. */
class ModularityTests {

  @Test
  void verifiesModularStructure() {
    ApplicationModules.of(WhyqlApplication.class).verify();
  }
}

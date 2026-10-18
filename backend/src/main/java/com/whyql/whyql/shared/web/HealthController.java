package com.whyql.whyql.shared.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** App-level liveness probe; /actuator/health remains the ops-oriented check. */
@RestController
@RequestMapping("/api")
public class HealthController {

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}

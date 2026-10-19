package com.whyql.whyql.datasource.adapter.profile;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.adapter.DataSourceAdapter;
import com.whyql.whyql.datasource.domain.Engine;

/**
 * Registry mapping each engine to its {@link EngineProfile}. New engines are
 * added by contributing a profile bean; the adapter machinery itself stays
 * untouched. Read-only equality: only engines the shared JDBC machinery can
 * run are mapped.
 */
@Component
public class EngineProfiles {

  private final Map<Engine, EngineProfile> profiles;

  public EngineProfiles(List<EngineProfile> implementations) {
    this.profiles = implementations.stream()
        .collect(Collectors.toUnmodifiableMap(EngineProfile::engine, Function.identity()));
  }

  /** The profile for an engine; throws ENGINE_MISMATCH when unsupported. */
  public EngineProfile profile(Engine engine) throws DataSourceAdapter.AdapterException {
    EngineProfile profile = engine == null ? null : profiles.get(engine);
    if (profile == null) {
      throw new DataSourceAdapter.AdapterException("ENGINE_MISMATCH",
          "No SQL profile configured for engine " + engine
              + " — supported engines: " + profiles.keySet());
    }
    return profile;
  }

  /** Same as {@link #profile(Engine)} where the caller cannot throw. */
  public boolean supports(Engine engine) {
    return profiles.containsKey(engine);
  }
}

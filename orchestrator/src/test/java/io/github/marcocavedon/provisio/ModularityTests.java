package io.github.marcocavedon.provisio;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies module boundaries (ADR 0016) and generates module documentation.
 */
class ModularityTests {

  private final ApplicationModules modules = ApplicationModules.of(ProvisioApplication.class);

  @Test
  void verifiesModuleBoundaries() {
    modules.verify();
  }

  @Test
  void writesModuleDocumentation() {
    // Output in target/spring-modulith-docs, published as a CI artifact
    new Documenter(modules).writeDocumentation();
  }
}

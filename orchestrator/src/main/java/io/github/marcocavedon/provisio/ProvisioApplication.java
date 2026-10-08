package io.github.marcocavedon.provisio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Provisio orchestrator: a Spring Modulith modular monolith.
 *
 * <p>Each direct sub-package of this package is an application module (see ADR 0016). Modules are added with the
 * first feature that needs them, never as empty placeholders.
 */
@SpringBootApplication
public class ProvisioApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProvisioApplication.class, args);
  }
}

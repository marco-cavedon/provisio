package io.github.marcocavedon.provisio.spike;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spike 001: deploys the agent process at startup and serves the agent's tool as a job worker.
 */
@SpringBootApplication
@Deployment(resources = "classpath*:bpmn/*.bpmn")
public class SpikeWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpikeWorkerApplication.class, args);
  }
}

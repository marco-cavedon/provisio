package io.github.marcocavedon.provisio;

import io.camunda.client.annotation.Deployment;
import org.springframework.context.annotation.Configuration;

/**
 * Deploys the process models of the top-level {@code processes/} folder (ADR 0009) when the Camunda client starts.
 *
 * <p>The POM packages them on the classpath under {@code processes/}. Every model goes in a single deployment, so a
 * business rule task bound to the decision {@code deployment} finds it; unchanged resources create no new version.
 * The application fails to start if no model is found, rather than running without processes. Tests disable this
 * ({@code camunda.client.deployment.enabled=false} in the test resources) and deploy what they need themselves.
 */
@Configuration(proxyBeanMethods = false)
@Deployment(resources = {ProcessDeploymentConfiguration.BPMN, ProcessDeploymentConfiguration.DMN})
class ProcessDeploymentConfiguration {

  static final String BPMN = "classpath*:processes/*.bpmn";
  static final String DMN = "classpath*:processes/*.dmn";
}

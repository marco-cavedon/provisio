package io.github.marcocavedon.provisio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The models deployed at startup are on the classpath: the POM packages the top-level {@code processes/} folder, and
 * the patterns of {@link ProcessDeploymentConfiguration} find them (ADR 0009). It fails if the packaging breaks,
 * before an application starts without processes. No Docker needed.
 */
class ProcessResourcesTests {

  @Test
  void theDeploymentPatternsFindTheModelsOfTheProcessesFolder() throws IOException {
    var resolver = new PathMatchingResourcePatternResolver();

    Stream<Resource> resources = Stream.concat(
        Arrays.stream(resolver.getResources(ProcessDeploymentConfiguration.BPMN)),
        Arrays.stream(resolver.getResources(ProcessDeploymentConfiguration.DMN)));

    assertThat(resources.map(Resource::getFilename)).containsExactlyInAnyOrder(
        "provisio-forecast-batch.bpmn",
        "provisio-forecast-run-assessment.dmn");
  }
}

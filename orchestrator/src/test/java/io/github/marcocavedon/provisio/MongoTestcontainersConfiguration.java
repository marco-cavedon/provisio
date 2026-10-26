package io.github.marcocavedon.provisio;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MongoDB in Docker for integration tests, wired into Spring through {@code @ServiceConnection}.
 *
 * <p>Same image as the local stack (keep in sync with {@code MONGODB_VERSION} in docker-compose.yml), started as a
 * single-node replica set: multi-document transactions require one. No access control: credentials are covered by
 * the local stack verification, not by these tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MongoTestcontainersConfiguration {

  static final DockerImageName MONGODB_IMAGE =
      DockerImageName.parse("mongodb/mongodb-community-server:9.0.2-ubi8").asCompatibleSubstituteFor("mongo");

  @Bean
  @ServiceConnection
  MongoDBContainer mongoDbContainer() {
    return new MongoDBContainer(MONGODB_IMAGE).withReplicaSet();
  }
}

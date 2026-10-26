package io.github.marcocavedon.provisio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * The application must refuse to start without a MongoDB password, instead of failing later at the first query.
 * No Docker needed: the database factory is a mock and no connection is made.
 */
class MongoConfigurationTests {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(MongoConfiguration.class)
      .withBean(MongoDatabaseFactory.class, () -> mock(MongoDatabaseFactory.class));

  @Test
  void startsWhenThePasswordIsSet() {
    contextRunner
        .withPropertyValues(MongoConfiguration.PASSWORD_PROPERTY + "=secret")
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(MongoTransactionManager.class));
  }

  @Test
  void failsFastWhenThePasswordPlaceholderCannotBeResolved() {
    // Same shape as application.yaml, with a variable that is never defined
    contextRunner
        .withPropertyValues(MongoConfiguration.PASSWORD_PROPERTY + "=${PROVISIO_UNDEFINED_TEST_VARIABLE}")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("PROVISIO_UNDEFINED_TEST_VARIABLE"));
  }

  @Test
  void failsFastWhenThePasswordIsEmpty() {
    contextRunner
        .withPropertyValues(MongoConfiguration.PASSWORD_PROPERTY + "=")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("MONGODB_APP_PASSWORD"));
  }
}

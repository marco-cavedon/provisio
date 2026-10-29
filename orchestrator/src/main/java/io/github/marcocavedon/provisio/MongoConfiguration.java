package io.github.marcocavedon.provisio;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.util.StringUtils;

/**
 * Shared MongoDB infrastructure, used by every module.
 *
 * <p>Spring Data MongoDB does not register a transaction manager on its own: without this bean,
 * {@code @Transactional} is silently ignored and multi-document writes are not atomic. The write path of the
 * inventory ledger depends on it (ADR 0022). Transactions require a replica set, which the local stack provides
 * (ADR 0007).
 */
@Configuration(proxyBeanMethods = false)
class MongoConfiguration {

  static final String PASSWORD_PROPERTY = "spring.mongodb.password";

  /**
   * Fails at startup if the MongoDB password is missing.
   *
   * <p>Configuration properties binding leaves unresolvable placeholders as literal text: without this check a
   * missing {@code MONGODB_APP_PASSWORD} would make the password the string {@code "${MONGODB_APP_PASSWORD}"}, and the
   * error would surface only at the first database operation, as an authentication failure. The environment resolves
   * placeholders strictly, so reading the property here fails fast.
   */
  MongoConfiguration(Environment environment) {
    String password;
    try {
      password = environment.getProperty(PASSWORD_PROPERTY);
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      throw missingPassword(unresolvedPlaceholder);
    }
    if (!StringUtils.hasText(password)) {
      throw missingPassword(null);
    }
  }

  private static IllegalStateException missingPassword(Throwable cause) {
    return new IllegalStateException(
        "MongoDB password is not set: export MONGODB_APP_PASSWORD (its value is in .env), "
            + "see orchestrator/README.md", cause);
  }

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
    return new MongoTransactionManager(databaseFactory);
  }

  /**
   * Calendar days are stored at midnight UTC whatever the JVM's time zone (ADR 0026).
   *
   * <p>Replaces Spring Boot's default bean, whose only customisation is the
   * {@code spring.data.mongodb.representation.big-decimal} property: Provisio does not set it, and that property stops
   * having an effect while this bean exists.
   */
  @Bean
  MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
        LocalDateConversions.LocalDateToDate.INSTANCE,
        LocalDateConversions.DateToLocalDate.INSTANCE));
  }
}

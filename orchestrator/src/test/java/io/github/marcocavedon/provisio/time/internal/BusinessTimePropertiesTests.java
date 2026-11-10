package io.github.marcocavedon.provisio.time.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marcocavedon.provisio.time.internal.BusinessTimeProperties.Mode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code provisio.business-time.*} as Spring binds it from configuration (ADR 0031): lower-case modes, ISO dates, and
 * every invalid combination refused, so the application does not start. No Docker needed.
 */
class BusinessTimePropertiesTests {

  @Test
  void bindsTheMappedModeAsInApplicationYaml() {
    BusinessTimeProperties properties = bind(Map.of("mode", "mapped"));

    assertThat(properties.mode()).isEqualTo(Mode.MAPPED);
    assertThat(properties.fixedDate()).isNull();
    assertThat(properties.mapped().year()).isEqualTo(2011);
    assertThat(properties.mapped().latest()).isEqualTo(LocalDate.of(2011, 12, 9));
  }

  @Test
  void bindsTheFixedModeWithItsDate() {
    BusinessTimeProperties properties = bind(Map.of("mode", "fixed", "fixed-date", "2011-12-05"));

    assertThat(properties.mode()).isEqualTo(Mode.FIXED);
    assertThat(properties.fixedDate()).isEqualTo(LocalDate.of(2011, 12, 5));
  }

  @Test
  void theFixedModeNeedsADate() {
    assertThatThrownBy(() -> bind(Map.of("mode", "fixed")))
        .isInstanceOf(BindException.class)
        .rootCause().hasMessageContaining("fixed-date is required");
  }

  @Test
  void aDateInTheMappedModeWouldBeIgnoredSoItIsRefused() {
    assertThatThrownBy(() -> bind(Map.of("mode", "mapped", "fixed-date", "2011-12-05")))
        .isInstanceOf(BindException.class)
        .rootCause().hasMessageContaining("would be ignored");
  }

  @Test
  void theLatestDayMustBeInTheMappedYear() {
    assertThatThrownBy(() -> bind(Map.of("mode", "mapped", "mapped.latest", "2012-01-31")))
        .isInstanceOf(BindException.class)
        .rootCause().hasMessageContaining("is not in");
  }

  @Test
  void anUnknownModeIsRefused() {
    assertThatThrownBy(() -> bind(Map.of("mode", "wall-clock")))
        .isInstanceOf(BindException.class);
  }

  /** The defaults of {@code application.yaml}, overridden by {@code values} (relative to the prefix). */
  private static BusinessTimeProperties bind(Map<String, String> values) {
    Map<String, String> source = new HashMap<>();
    source.put("provisio.business-time.mapped.year", "2011");
    source.put("provisio.business-time.mapped.latest", "2011-12-09");
    values.forEach((key, value) -> source.put("provisio.business-time." + key, value));
    return new Binder(new MapConfigurationPropertySource(source))
        .bind("provisio.business-time", BusinessTimeProperties.class)
        .get();
  }
}

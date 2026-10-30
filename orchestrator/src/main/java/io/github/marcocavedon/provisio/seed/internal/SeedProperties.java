package io.github.marcocavedon.provisio.seed.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed configuration.
 *
 * @param dir folder of one dataset, holding {@code manifest.json} (e.g. {@code seed/data/online-retail-ii});
 *     the seed is not loaded when it is unset. Environment variable: {@code PROVISIO_SEED_DIR}.
 */
@ConfigurationProperties("provisio.seed")
public record SeedProperties(Path dir) {
}

package io.github.marcocavedon.provisio.seed.internal;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * {@code seed_loads}: which version of each dataset is loaded (ADR 0026).
 *
 * @param dataset the {@code _id}, as named in the manifest
 * @param manifestSha256 checksum of the loaded {@code manifest.json}, which itself holds the checksum of every file
 * @param rows data rows loaded
 * @param loadedAt wall-clock time of the load: a technical timestamp, not business time
 */
@Document(collection = SeedLoadDocument.COLLECTION)
public record SeedLoadDocument(@Id String dataset, String manifestSha256, int rows, Instant loadedAt) {

  public static final String COLLECTION = "seed_loads";
}

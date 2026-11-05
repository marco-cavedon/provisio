package io.github.marcocavedon.provisio.seed.internal;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * {@code seed_loads}: which version of each dataset is loaded (ADR 0026).
 *
 * @param dataset the {@code _id}, as named in the manifest
 * @param manifestSha256 checksum of the loaded {@code manifest.json}, which itself holds the checksum of every file
 * @param format the loader's {@link SeedLoader#SEED_FORMAT} at the time of the load; {@code null} in records written
 *     before the field existed, which means format 1
 * @param rows data rows loaded
 * @param loadedAt wall-clock time of the load: a technical timestamp, not business time
 */
@Document(collection = SeedLoadDocument.COLLECTION)
public record SeedLoadDocument(@Id String dataset, String manifestSha256, Integer format, int rows,
    Instant loadedAt) {

  public static final String COLLECTION = "seed_loads";

  /** The format of the load, 1 for records written before the field existed. */
  int formatOrFirst() {
    return format == null ? 1 : format;
  }
}

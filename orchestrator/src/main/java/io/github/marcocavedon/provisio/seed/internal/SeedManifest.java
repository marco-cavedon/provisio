package io.github.marcocavedon.provisio.seed.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;

/**
 * A dataset folder whose files have been checked against its {@code manifest.json} (written by {@code seed/derive}).
 *
 * <p>The manifest is parsed with the JSON reader of the MongoDB driver, already on the classpath.
 *
 * @param dataset dataset name, the key of its {@code seed_loads} record
 * @param sha256 checksum of {@code manifest.json}
 * @param files verified content of every file listed in the manifest, by name
 */
record SeedManifest(String dataset, String sha256, Map<String, byte[]> files) {

  static final String MANIFEST = "manifest.json";

  /**
   * Reads the manifest and every file it lists, and checks their checksums and row counts.
   *
   * @throws IllegalStateException if the folder, the manifest or a file is missing, or a file does not match
   */
  static SeedManifest read(Path dir) {
    byte[] manifestBytes = readFile(dir.resolve(MANIFEST));
    Document manifest = Document.parse(new String(manifestBytes, StandardCharsets.UTF_8));
    String dataset = manifest.getString("dataset");
    Document listed = manifest.get("files", Document.class);
    if (dataset == null || listed == null || listed.isEmpty()) {
      throw new IllegalStateException(dir.resolve(MANIFEST) + " has no dataset name or no files");
    }

    Map<String, byte[]> files = new LinkedHashMap<>();
    for (String name : listed.keySet()) {
      Document expected = listed.get(name, Document.class);
      byte[] content = readFile(dir.resolve(name));
      String actualSha256 = sha256(content);
      if (!actualSha256.equals(expected.getString("sha256"))) {
        throw new IllegalStateException(dir.resolve(name) + " does not match its manifest (SHA-256 " + actualSha256
            + "): regenerate the seed with seed/derive instead of editing it by hand");
      }
      int rows = countLines(content) - 1;
      if (rows != expected.getInteger("rows")) {
        throw new IllegalStateException(dir.resolve(name) + " has " + rows + " rows, its manifest says "
            + expected.getInteger("rows"));
      }
      files.put(name, content);
    }
    return new SeedManifest(dataset, sha256(manifestBytes), Map.copyOf(files));
  }

  /** Verified content of a file listed in the manifest. */
  byte[] file(String name) {
    byte[] content = files.get(name);
    if (content == null) {
      throw new IllegalStateException("The manifest of dataset " + dataset + " does not list " + name);
    }
    return content;
  }

  /**
   * Data rows of a CSV file listed in the manifest, after checking its header.
   *
   * <p>The files are machine-written by {@code seed/derive} with a fixed header and no quoting, and their checksums
   * have been verified, so a plain split is enough. A description containing a comma would need quoting: the
   * derivation would have to change first, and the column count check below would catch it.
   */
  List<String[]> csvRows(String name, String header) {
    String[] lines = new String(file(name), StandardCharsets.UTF_8).split("\n");
    if (!lines[0].equals(header)) {
      throw new IllegalStateException(name + ": expected header '" + header + "', found '" + lines[0] + "'");
    }
    int columns = header.split(",").length;
    List<String[]> rows = new ArrayList<>(lines.length - 1);
    for (int i = 1; i < lines.length; i++) {
      String[] row = lines[i].split(",", -1);
      if (row.length != columns) {
        throw new IllegalStateException(name + " line " + (i + 1) + ": expected " + columns + " columns");
      }
      rows.add(row);
    }
    return rows;
  }

  private static byte[] readFile(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (NoSuchFileException missing) {
      throw new IllegalStateException("Seed file not found: " + path
          + " (check provisio.seed.dir / PROVISIO_SEED_DIR, ADR 0026)", missing);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read seed file " + path, e);
    }
  }

  private static int countLines(byte[] content) {
    int lines = 0;
    for (byte b : content) {
      if (b == '\n') {
        lines++;
      }
    }
    return lines;
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}

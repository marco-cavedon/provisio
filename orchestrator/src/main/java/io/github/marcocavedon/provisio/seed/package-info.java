/**
 * Seed module: loads the demo data committed in {@code seed/data} at startup (ADR 0026).
 *
 * <p>It verifies the files against their manifest and hands the data to the modules that own it, through their public
 * APIs, in one transaction; it writes only {@code seed_loads}, the record of what was loaded. Active only when
 * {@code provisio.seed.dir} is set. It exposes no API.
 */
package io.github.marcocavedon.provisio.seed;

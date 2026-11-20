package com.allanlewis.homelabwatchman.resolve;

import java.util.Optional;

/**
 * The latest known value for a dependency's identifier, as seen upstream.
 * {@code digest} is only present for OCI image refs.
 */
public record ResolvedVersion(String value, Optional<String> digest, String sourceUrl) {
}

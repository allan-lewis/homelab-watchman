package com.allanlewis.homelabwatchman.resolve;

import java.util.List;
import java.util.Optional;

/**
 * Speaks the standard OCI Distribution HTTP API. One implementation covers
 * docker.io, ghcr.io, and lscr.io, since all three implement the same spec.
 */
public interface OciRegistryClient {

    List<String> listTags(String repository);

    Optional<String> resolveDigest(String repository, String tag);
}

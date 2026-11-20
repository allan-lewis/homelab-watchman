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

    /**
     * Normalizes an already-pinned digest to its linux/amd64 form: if the
     * digest refers to a multi-arch index, returns the amd64 child's
     * digest instead of the index's own. A digest that's already
     * platform-specific (not an index) is returned unchanged. Needed to
     * compare like-for-like against resolveDigest()'s result - comparing
     * an un-normalized index digest against a freshly-resolved amd64
     * digest looks like a change even when the image is identical.
     */
    Optional<String> normalizeDigest(String repository, String digest);
}

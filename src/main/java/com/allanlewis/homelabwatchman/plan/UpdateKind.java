package com.allanlewis.homelabwatchman.plan;

/**
 * DIGEST_REFRESH: same tag, upstream rebuilt the digest (routine, low risk).
 * VERSION_BUMP: a newer tag/release exists. Always opened as a separate PR
 * from any DIGEST_REFRESH on the same dependency.
 */
public enum UpdateKind {
    DIGEST_REFRESH,
    VERSION_BUMP
}

package com.allanlewis.homelabwatchman.scan;

/**
 * Explicit resolver dispatch for UpdatePlanner. Used to be inferred from
 * currentDigest() presence (only OCI images had one), but that stopped
 * being enough once a third kind - Terraform providers - also has no
 * digest.
 */
public enum Ecosystem {
    OCI_IMAGE,
    GITHUB_RELEASE,
    TERRAFORM_PROVIDER
}

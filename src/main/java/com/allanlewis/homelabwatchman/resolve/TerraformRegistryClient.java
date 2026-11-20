package com.allanlewis.homelabwatchman.resolve;

import java.util.List;

/** All available versions for a Terraform provider (e.g. "bpg/proxmox"), via the public Terraform Registry. */
public interface TerraformRegistryClient {

    List<String> listVersions(String providerIdentifier);
}

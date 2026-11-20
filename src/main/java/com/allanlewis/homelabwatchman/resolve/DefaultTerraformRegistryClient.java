package com.allanlewis.homelabwatchman.resolve;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Public, unauthenticated API - no token needed. */
@Component
public class DefaultTerraformRegistryClient implements TerraformRegistryClient {

    private final RestClient restClient = RestClient.create();

    @Override
    public List<String> listVersions(String providerIdentifier) {
        String url = "https://registry.terraform.io/v1/providers/" + providerIdentifier + "/versions";

        VersionsResponse response = restClient.get().uri(url).retrieve().body(VersionsResponse.class);

        return response == null || response.versions() == null
                ? List.of()
                : response.versions().stream().map(ProviderVersion::version).toList();
    }

    private record VersionsResponse(List<ProviderVersion> versions) {
    }

    private record ProviderVersion(String version) {
    }
}

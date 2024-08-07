/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.repositories.blobstore.testkit;

import fixture.azure.AzureHttpFixture;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.test.TestTrustStore;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;

public class AzureSnapshotRepoTestKitIT extends AbstractSnapshotRepoTestKitRestTestCase {
    private static final boolean USE_FIXTURE = Booleans.parseBoolean(System.getProperty("test.azure.fixture", "true"));
    private static final String AZURE_TEST_ACCOUNT = System.getProperty("test.azure.account");
    private static final String AZURE_TEST_CONTAINER = System.getProperty("test.azure.container");
    private static final String AZURE_TEST_KEY = System.getProperty("test.azure.key");
    private static final String AZURE_TEST_SASTOKEN = System.getProperty("test.azure.sas_token");

    private static AzureHttpFixture fixture = new AzureHttpFixture(
        USE_FIXTURE ? AzureHttpFixture.Protocol.HTTPS : AzureHttpFixture.Protocol.NONE,
        AZURE_TEST_ACCOUNT,
        AZURE_TEST_CONTAINER,
        AzureHttpFixture.sharedKeyForAccountPredicate(AZURE_TEST_ACCOUNT)
    );

    private static TestTrustStore trustStore = new TestTrustStore(
        () -> AzureHttpFixture.class.getResourceAsStream("azure-http-fixture.pem")
    );

    private static ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .module("repository-azure")
        .module("snapshot-repo-test-kit")
        .keystore("azure.client.repository_test_kit.account", AZURE_TEST_ACCOUNT)
        .keystore(
            "azure.client.repository_test_kit.key",
            () -> AZURE_TEST_KEY,
            s -> AZURE_TEST_KEY != null && AZURE_TEST_KEY.isEmpty() == false
        )
        .keystore(
            "azure.client.repository_test_kit.sas_token",
            () -> AZURE_TEST_SASTOKEN,
            s -> AZURE_TEST_SASTOKEN != null && AZURE_TEST_SASTOKEN.isEmpty() == false
        )
        .setting(
            "azure.client.repository_test_kit.endpoint_suffix",
            () -> "ignored;DefaultEndpointsProtocol=http;BlobEndpoint=" + fixture.getAddress(),
            s -> USE_FIXTURE
        )
        .apply(c -> {
            if (USE_FIXTURE) {
                // test fixture does not support CAS yet; TODO fix this
                c.systemProperty("test.repository_test_kit.skip_cas", "true");
            }
        })
        .systemProperty("javax.net.ssl.trustStore", () -> trustStore.getTrustStorePath().toString(), s -> USE_FIXTURE)
        .build();

    @ClassRule(order = 1)
    public static TestRule ruleChain = RuleChain.outerRule(fixture).around(trustStore).around(cluster);

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    @Override
    protected String repositoryType() {
        return "azure";
    }

    @Override
    protected Settings repositorySettings() {
        final String container = System.getProperty("test.azure.container");
        assertThat(container, not(blankOrNullString()));

        final String basePath = System.getProperty("test.azure.base_path");
        assertThat(basePath, not(blankOrNullString()));

        return Settings.builder().put("client", "repository_test_kit").put("container", container).put("base_path", basePath).build();
    }
}

package com.optimizely.ab;

import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Test-only factory that wraps a fully-configured Apache {@link CloseableHttpClient} in an
 * {@link OptimizelyHttpClient}. It lives in the {@code com.optimizely.ab} package specifically so it can
 * call the package-private {@code OptimizelyHttpClient(CloseableHttpClient)} constructor — the same
 * technique the production {@link ObservingOptimizelyHttpClient} uses (issue #267).
 *
 * <p>The SDK's public {@code OptimizelyHttpClient.Builder} exposes only connection-pool / timeout /
 * retry knobs — no custom {@code SSLContext} or proxy. The Rift TLS-MITM intercept test needs both (trust
 * Rift's CA, route {@code cdn.optimizely.com:443} through the intercept listener), so it builds the Apache
 * client itself and wraps it here rather than going through the closed builder.
 */
public final class OptimizelyHttpClients {

    private OptimizelyHttpClients() {}

    public static OptimizelyHttpClient fromApache(CloseableHttpClient underlying) {
        return new OptimizelyHttpClient(underlying);
    }
}

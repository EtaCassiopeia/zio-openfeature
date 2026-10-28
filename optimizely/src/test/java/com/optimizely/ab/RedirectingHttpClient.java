package com.optimizely.ab;

import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/**
 * Test-only {@link OptimizelyHttpClient} that sends every request, whatever its URL, to a plain-HTTP server on
 * localhost. The SDK posts events to a fixed {@code https://logx.optimizely.com} endpoint; routing them to a local
 * WireMock lets a test run the real {@code AsyncEventHandler} without the network. It lives in the
 * {@code com.optimizely.ab} package to reach the package-private {@code OptimizelyHttpClient(CloseableHttpClient)}
 * constructor, as {@link ObservingOptimizelyHttpClient} does.
 */
public final class RedirectingHttpClient {

    private RedirectingHttpClient() {}

    public static OptimizelyHttpClient toLocalhost(int port) {
        HttpHost local = new HttpHost("localhost", port, "http");
        CloseableHttpClient apache =
            HttpClients.custom().setRoutePlanner((target, request, context) -> new HttpRoute(local)).build();
        return new OptimizelyHttpClient(apache);
    }
}

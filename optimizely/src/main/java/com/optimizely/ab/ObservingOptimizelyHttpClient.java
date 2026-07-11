package com.optimizely.ab;

import java.io.IOException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * An {@link OptimizelyHttpClient} that observes datafile-fetch outcomes so the OpenFeature provider can detect that
 * datafile polling has stopped succeeding after a successful init (issue #267).
 *
 * <p>The Optimizely SDK exposes no public fetch-success/failure callback, but {@code HttpProjectConfigManager} lets you
 * inject a custom {@code OptimizelyHttpClient} via {@code withOptimizelyHttpClient}. This subclass lives in the
 * {@code com.optimizely.ab} package specifically so it can call the package-private
 * {@code OptimizelyHttpClient(CloseableHttpClient)} constructor and reuse an existing client's fully-configured
 * underlying Apache client (connection pool, request timeouts, retry handler) <em>verbatim</em>. It adds observation
 * only; it changes nothing about how fetches are performed, so production keeps the SDK's exact default HTTP behaviour.
 *
 * <p>A fetch is counted as successful when the CDN returns an HTTP status below 400: 2xx is a new/updated datafile and
 * 304 is "not modified" — both mean the CDN was reachable and the poll worked. A 4xx/5xx response (auth failure, server
 * error) or a thrown {@link IOException} (connection refused/reset/timeout) is a failure and does <em>not</em> advance
 * the success signal, which is exactly what the staleness watchdog keys off.
 *
 * <p>Only the single-argument {@link #execute(HttpUriRequest)} overload is observed, because that is the one
 * {@code HttpProjectConfigManager.poll()} uses to fetch the datafile. The two-arg / response-handler overloads are left
 * to the superclass untouched.
 */
public final class ObservingOptimizelyHttpClient extends OptimizelyHttpClient {

    /** Invoked once per successful datafile fetch (HTTP status &lt; 400). */
    private final Runnable onSuccessfulFetch;

    private ObservingOptimizelyHttpClient(CloseableHttpClient underlying, Runnable onSuccessfulFetch) {
        super(underlying);
        this.onSuccessfulFetch = onSuccessfulFetch;
    }

    /**
     * Wrap an existing {@link OptimizelyHttpClient}, reusing its underlying Apache client so fetch behaviour is
     * byte-for-byte identical to the wrapped client. Used to observe a caller/test-injected client.
     */
    public static ObservingOptimizelyHttpClient wrapping(OptimizelyHttpClient delegate, Runnable onSuccessfulFetch) {
        // getHttpClient() returns the CloseableHttpClient the delegate was constructed with (typed as HttpClient).
        return new ObservingOptimizelyHttpClient((CloseableHttpClient) delegate.getHttpClient(), onSuccessfulFetch);
    }

    /**
     * Wrap the SDK's default client — the very client production would otherwise get when it injects nothing — so the
     * observing path preserves the SDK's default connection pool, timeouts, and retry semantics.
     */
    public static ObservingOptimizelyHttpClient wrappingDefault(Runnable onSuccessfulFetch) {
        return wrapping(OptimizelyHttpClient.builder().build(), onSuccessfulFetch);
    }

    @Override
    public CloseableHttpResponse execute(HttpUriRequest request) throws IOException {
        // Delegate to the real fetch first; if it throws (network failure) we simply never record a success.
        CloseableHttpResponse response = super.execute(request);
        if (response.getStatusLine().getStatusCode() < 400) {
            onSuccessfulFetch.run();
        }
        return response;
    }
}

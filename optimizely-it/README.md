# optimizely-it — local integration suite

Local-only integration tests for the Optimizely provider. Drives the **real Optimizely Java SDK** against a self-contained `docker-compose` stack (nginx + Toxiproxy) on the developer's machine.

This module is **not aggregated into the root build** and is **not run in CI** — `sbt test` from the repo root ignores it. The in-process WireMock-backed `OptimizelyProviderIntegrationSpec` in the `optimizely` module is the canonical CI-friendly integration coverage; this suite is for richer real-backend scenarios that need a real HTTP server and a real datafile.

## Requirements

- Docker daemon reachable from the host (Docker Desktop, Colima, OrbStack, etc.).
- No GitHub secrets, no Optimizely account credentials at runtime — the committed fixtures under `src/test/resources/datafiles/` are served by the local nginx.

## Running

```bash
sbt optimizelyIt/test
```

The first run pulls `nginx:1.27-alpine` and `ghcr.io/shopify/toxiproxy:2.9.0` (a few MB). Subsequent runs reuse the cached images.

If Docker isn't reachable, every spec in this module self-skips and `sbt optimizelyIt/test` exits 0 — the module is safe to clone and build on machines without Docker.

## Poking around the stack manually

```bash
cd optimizely-it
cp -R src/test/resources/datafiles/. datafiles/
docker-compose up
```

Then in another shell:

```bash
# Direct to nginx
curl "http://localhost:$(docker-compose port datafile-server 80 | awk -F: '{print $2}')/datafiles/health.txt"

# Through Toxiproxy (after the proxy is created — the test JVM does this; for manual runs use the admin API)
curl -s -X POST -d '{"name":"datafile","listen":"0.0.0.0:8666","upstream":"datafile-server:80","enabled":true}' \
  "http://localhost:$(docker-compose port toxiproxy 8474 | awk -F: '{print $2}')/proxies"
curl "http://localhost:$(docker-compose port toxiproxy 8666 | awk -F: '{print $2}')/datafiles/health.txt"
```

## Layout

```
optimizely-it/
├── docker-compose.yml           # nginx + Toxiproxy stack
├── src/test/resources/datafiles/  # committed fixtures (source of truth; checked in)
└── src/test/scala/zio/openfeature/optimizely/it/
    ├── OptimizelyItStack.scala   # DockerComposeContainer lifecycle + Toxiproxy helpers
    ├── RealOptimizelySupport.scala  # shared constants and provider-building helpers
    └── StackSmokeSpec.scala       # foundation smoke test (this PR)
```

At test-JVM startup `OptimizelyItStack` copies everything under `src/test/resources/datafiles/` into `./datafiles/` (gitignored), which is what nginx serves. `swapDatafile(sdkKey, content)` mutates the runtime directory in place; the committed source-of-truth fixtures are never written to.

## What this suite owns vs. the WireMock spec

| Scenario | Owner |
|---|---|
| Synthesized HTTP status codes (401 / 403 / 404 / 500) | `OptimizelyProviderIntegrationSpec` (WireMock, runs in CI) |
| Real-shape decisions (boolean / string / int / double / object) | this module |
| Audience / attribute targeting | this module |
| Real CDN-shaped transport faults (latency, RESET_PEER, connection refused) | this module |
| Datafile churn / malformed payloads | this module |

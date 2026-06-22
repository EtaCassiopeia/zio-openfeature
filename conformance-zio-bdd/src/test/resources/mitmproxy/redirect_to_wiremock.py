"""mitmdump addon used by ProxyFlagMatrixSpec.

Acts as the forward HTTP proxy the test JVM is pointed at (via http.proxyHost/
http.proxyPort). Any request whose Host matches MATCH_HOST -- the fake,
RFC 2606 .invalid CDN host the test's OptimizelyProvider is configured with --
is rewritten in-flight to TARGET_HOST:TARGET_PORT (WireMock, reachable from
inside the container via Testcontainers' host.testcontainers.internal alias)
before mitmproxy opens the upstream connection. Every other request passes
through untouched.
"""

import os

MATCH_HOST = os.environ.get("MATCH_HOST", "cdn.optimizely.invalid")
TARGET_HOST = os.environ["TARGET_HOST"]
TARGET_PORT = int(os.environ["TARGET_PORT"])


class RedirectToWiremock:
    def request(self, flow):
        if flow.request.pretty_host != MATCH_HOST:
            return
        flow.request.host = TARGET_HOST
        flow.request.port = TARGET_PORT
        flow.request.headers["Host"] = f"{TARGET_HOST}:{TARGET_PORT}"


addons = [RedirectToWiremock()]

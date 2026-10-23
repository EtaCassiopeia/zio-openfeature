"""Testing real providers: the vendor SDK's requests go through rift's intercept proxy, whose rules answer them.

A serve rule answers at once (here, a 401); a rule that redirects to an imposter can stall. Meanwhile FeatureFlags,
built with fromAcquireAsync, keeps answering from its fallback.
"""

from hud import AMBER, BLUE, CYAN, DIM, GREEN, RED, Scene

APP_X, PROV_X, PROXY_X, END_X = 28, 270, 500, 860
PANEL_W, TOP, H = 170, 104, 210
MID = TOP + H / 2
SERVE_Y, IMP_Y = 150, 270
W = END_X + 200


def request(sc: Scene, label, start, lane_y, until, color=BLUE):
    """A request leaves the provider, crosses the proxy and travels down a rule's lane."""
    sc.add(sc.chip(label, color, sc.path([
        (start - 0.2, PROV_X + PANEL_W - 30, MID, 0), (start, PROV_X + PANEL_W - 30, MID),
        (start + 0.8, PROXY_X + 75, MID), (start + 1.3, PROXY_X + 150, lane_y),
        (start + 2.2, END_X - 58, lane_y), (until, END_X - 58, lane_y), (until + 0.2, END_X - 58, lane_y, 0),
    ]), w=96))


def build() -> Scene:
    T = 12.0
    sc = Scene(
        W, 400, T,
        "Testing a real provider",
        "vendor SDK → rift intercept proxy → your rules",
        "each rule decides what the real provider gets; FeatureFlags keeps answering from its fallback meanwhile",
        "A real provider's HTTPS requests go through the rift intercept proxy. A serve rule answers directly with a "
        "status and a body, such as a 401; a rule that redirects to an imposter can stall the connection. "
        "FeatureFlags keeps answering from its fallback throughout.",
    )
    sc.add(sc.text(APP_X, 88, "YOUR APP", "tiny"), sc.text(PROV_X, 88, "REAL PROVIDER", "tiny"),
           sc.text(PROXY_X, 88, "RIFT INTERCEPT PROXY", "tiny"), sc.text(END_X, 88, "RULES", "tiny"))
    sc.add(sc.panel(APP_X, TOP, PANEL_W, H), sc.panel(PROV_X, TOP, PANEL_W, H), sc.panel(PROXY_X, TOP, 150, H))
    sc.add(sc.text(APP_X + 14, TOP + 26, "FeatureFlags", "big"),
           sc.text(APP_X + 14, TOP + 46, "fromAcquireAsync", "hud"),
           sc.text(APP_X + 14, TOP + 74, "ANSWERS · FALLBACK", "tiny"),
           sc.text(PROV_X + 14, TOP + 26, "vendor SDK", "big"),
           sc.text(PROV_X + 14, TOP + 46, "HTTP client · TLS", "hud"),
           sc.text(PROXY_X + 14, TOP + 26, "TLS-MITM", "big"),
           sc.text(PROXY_X + 14, TOP + 46, "rule per host", "hud"))
    # rule lanes
    sc.add(sc.rail(PROXY_X + 150, SERVE_Y, END_X, RED), sc.rail(PROXY_X + 150, IMP_Y, END_X, AMBER))
    sc.add(sc.panel(END_X, SERVE_Y - 26, 172, 52), sc.text(END_X + 12, SERVE_Y - 4, "serve(...)", "tag", RED),
           sc.text(END_X + 12, SERVE_Y + 14, "status 401", "hud"))
    sc.add(sc.panel(END_X, IMP_Y - 26, 172, 52), sc.text(END_X + 12, IMP_Y - 4, "redirectTo(imposter)", "tag", AMBER),
           sc.text(END_X + 12, IMP_Y + 14, "reply .after(30 s)", "hud"))

    # Scenario 1: rejected credentials
    request(sc, "GET /datafile", 1.0, SERVE_Y, 3.3, BLUE)
    sc.add(sc.pulse(END_X, SERVE_Y, RED, 3.2))
    sc.add(sc.chip("401", RED, sc.path([
        (3.3, END_X - 30, SERVE_Y, 0), (3.5, END_X - 30, SERVE_Y), (4.3, PROXY_X + 150, SERVE_Y),
        (4.8, PROXY_X + 75, MID), (5.5, PROV_X + PANEL_W - 30, MID), (5.7, PROV_X + PANEL_W - 30, MID, 0),
    ]), w=40))
    sc.add(sc.badge(PROV_X + 14, TOP + 82, "never ready", RED, sc.shown([(5.6, 6.6)])))

    # Scenario 2: stalled endpoint
    request(sc, "GET /datafile", 6.6, IMP_Y, 11.8, BLUE)
    sc.add(sc.text(END_X - 58, IMP_Y + 30, "waiting…", "hud", AMBER, "middle", sc.shown([(8.9, 11.8)])))
    sc.add(sc.badge(PROV_X + 14, TOP + 82, "stalled", AMBER, sc.shown([(8.9, 11.9)])))

    # The app keeps answering from the fallback throughout
    asks = [0.4, 2.6, 4.9, 7.4, 9.9]
    for i, t in enumerate(asks):
        sc.add(sc.chip("my-flag?", CYAN, sc.path([(t - 0.1, APP_X + 60, TOP + H + 20, 0), (t, APP_X + 60, TOP + H + 20),
                                                 (t + 0.4, APP_X + 60, TOP + 92 + i * 22), (t + 0.5, APP_X + 60, TOP + 92 + i * 22, 0)]),
                       w=72))
        sc.add(f'<text x="{APP_X + 14}" y="{TOP + 96 + i * 22}" class="log" style="{sc.shown([(t + 0.45, 11.9)], 0.2)}">'
               f'<tspan fill="{DIM}">my-flag →</tspan> <tspan fill="{GREEN}">true</tspan></text>')
    return sc

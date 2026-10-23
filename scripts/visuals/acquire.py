"""fromAcquireAsync: the fallback answers from the start while a background fiber builds, verifies and swaps in the real
provider."""

from hud import AMBER, CYAN, DIM, GREEN, RED, TEXT, Scene

CALLER_X, FF_X, FF_W = 70, 230, 170
PROV_X, PROV_W = 660, 440
EY = 160                       # the evaluation lane
RY = 352                       # the background fiber's rail
SWAP, LIVE = 8.6, 9.4          # the swap runs between these


def build() -> Scene:
    T = 14.0
    sc = Scene(
        PROV_X + PROV_W + 28, 450, T,
        "FeatureFlags.fromAcquireAsync",
        "Ready at once on the fallback · real provider built in the background · then swapped in",
        "acquire and verify are retried with constructionRetry; on terminal failure the fallback keeps serving",
        "fromAcquireAsync: from the first moment evaluations are answered by the fallback provider while a background "
        "fiber acquires the real provider, retries after a failed attempt, verifies the candidate knows a sentinel flag, "
        "and swaps it in; AcquireStatus moves from Constructing to Live, and later evaluations get real values.",
    )
    sc.add(sc.text(28, 88, "CALLERS", "tiny"), sc.text(FF_X, 88, "FEATUREFLAGS", "tiny"),
           sc.text(PROV_X, 88, "SERVING PROVIDER", "tiny"),
           sc.text(28, RY - 62, "BACKGROUND FIBER", "tiny"))

    sc.add(sc.rail(CALLER_X + 20, EY, FF_X, CYAN), sc.rail(FF_X + FF_W, EY, PROV_X, CYAN))

    # Evaluations keep coming; each is answered by whichever provider is serving
    for t in (0.2, 1.7, 3.2, 4.7, 6.2, 7.7, 9.7, 11.2, 12.7):
        real = t > LIVE
        sc.add(sc.chip("eval", CYAN, sc.path(
            [(t, CALLER_X, EY - 14, 0), (t + 0.1, CALLER_X, EY - 14), (t + 0.6, PROV_X - 20, EY - 14),
             (t + 0.65, PROV_X - 20, EY - 14, 0)], "linear"), w=36))
        label, color, w = ("real", CYAN, 40) if real else ("fallback", AMBER, 64)
        sc.add(sc.chip(label, color, sc.path(
            [(t + 0.65, PROV_X - 34, EY + 14, 0), (t + 0.7, PROV_X - 34, EY + 14), (t + 1.25, CALLER_X + 10, EY + 14),
             (t + 1.35, CALLER_X + 10, EY + 14, 0)], "linear"), w=w))

    # FeatureFlags and its status (drawn over the chips, which pass behind it)
    sc.add(sc.panel(FF_X, EY - 34, FF_W, 68), sc.text(FF_X + FF_W / 2, EY - 8, "FeatureFlags", "tag", TEXT, "middle"))
    sc.add(sc.text(FF_X + FF_W / 2, EY + 14, "status Ready", "hud", GREEN, "middle",
                   sc.shown([(0, SWAP), (LIVE, T)], 0.08)),
           sc.text(FF_X + FF_W / 2, EY + 14, "NotReady: swapping", "hud", AMBER, "middle",
                   sc.shown([(SWAP, LIVE)], 0.08)))

    # The serving provider, before and after the swap
    sc.add(sc.panel(PROV_X, EY - 34, PROV_W, 68))
    sc.add(sc.text(PROV_X + PROV_W / 2, EY - 6, "fallback", "tag", AMBER, "middle", sc.shown([(0, SWAP)])),
           sc.text(PROV_X + PROV_W / 2, EY + 14, "e.g. EnvVarProvider()", "hud", None, "middle", sc.shown([(0, SWAP)])),
           sc.text(PROV_X + PROV_W / 2, EY - 6, "real  →  fresh fallback", "tag", CYAN, "middle", sc.shown([(LIVE, T)])),
           sc.text(PROV_X + PROV_W / 2, EY + 14, "compose = first successful answer wins", "hud", None, "middle",
                   sc.shown([(LIVE, T)])),
           sc.text(PROV_X + PROV_W / 2, EY + 4, "swapping…", "hud", AMBER, "middle", sc.shown([(SWAP, LIVE)], 0.08)))

    # The background fiber: one failed attempt, a backoff, a good attempt, verify, swap
    steps = [  # (x, label, start, end, outcome color, outcome)
        (70, "acquire #1", 0.4, 2.6, RED, "✗ fails"),
        (230, "backoff", 2.6, 4.0, DIM, "retry"),
        (370, "acquire #2", 4.0, 6.4, GREEN, "✓"),
        (530, "verify kill-switch", 6.4, 8.2, GREEN, "✓ known"),
        (730, "setProvider", SWAP, LIVE, GREEN, "✓ swapped"),
    ]
    sc.add(sc.rail(70, RY, 900, DIM))
    marker = [(0.0, 70, 0)]
    for x, label, start, end, color, outcome in steps:
        sc.add(sc.text(x, RY - 26, label, "tag", TEXT, style=sc.shown([(start, T - 0.3)], 0.15)))
        sc.add(sc.text(x, RY + 30, outcome, "hud", color, style=sc.shown([(end, T - 0.3)], 0.15)))
        marker += [(start, x, 0), (end, x + 120, 0)]
    sc.add(f'<g style="{sc.path(marker)}">'
           f'<circle cx="0" cy="{RY}" r="7" fill="{CYAN}" fill-opacity="0.25" stroke="{CYAN}" stroke-width="1.6" '
           f'filter="url(#glow)"/></g>')
    # the swap reaches up to the serving provider
    sc.add(f'<path d="M{790} {RY - 40} V{EY + 40}" class="arrow" stroke="{GREEN}" stroke-dasharray="4 4" '
           f'style="{sc.shown([(SWAP, LIVE + 0.6)], 0.1)}"/>')
    sc.add(sc.pulse(PROV_X + PROV_W / 2, EY, GREEN, LIVE, 30))

    # AcquireStatus
    sc.add(sc.text(950, RY - 26, "AcquireStatus", "tiny"),
           sc.badge(950, RY, "Constructing", AMBER, sc.shown([(0, LIVE)], 0.1)),
           sc.badge(950, RY, "Live", GREEN, sc.shown([(LIVE, T)], 0.1)))
    return sc

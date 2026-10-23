"""CircuitBreakerProvider inside a firstSuccessful MultiProvider: failures trip it open, the fallback answers while it
is open, and after resetTimeout one probe closes it again."""

from hud import AMBER, BLUE, CYAN, DIM, GREEN, RED, Scene

SRC = (96, 215)                  # where calls come from
BRK_X, BRK_W = 300, 170          # the breaker panel
BRK = (BRK_X + BRK_W / 2, 215)   # a call waits here while the breaker decides
TGT_X = 742                      # left edge of the delegate and fallback panels
DEL_Y, FB_Y = 145, 290           # their rows
LOG_X = 940

# (number, start, route): "ok" via the delegate, "fail" via the delegate then the fallback, "reject" straight to it
CALLS = [
    (1, 0.4, "ok"), (2, 1.6, "ok"),
    (3, 3.4, "fail"), (4, 4.4, "fail"), (5, 5.4, "fail"),
    (6, 7.0, "reject"), (7, 8.0, "reject"), (8, 9.0, "reject"),
    (9, 10.6, "probe"), (10, 12.6, "ok"), (11, 13.8, "ok"),
]
OPENED, HALF_OPEN, CLOSED = 6.6, 11.2, 11.8   # when call 5 fails; when call 9 reaches the breaker; when it succeeds
DOWN, UP = 3.0, 10.0                          # the delegate's outage


def call(sc: Scene, n, t0, route):
    color = AMBER if route == "probe" else CYAN
    dx, dy = TGT_X - 16, DEL_Y
    fx, fy = TGT_X - 16, FB_Y
    pts = [(t0 - 0.01, *SRC, 0), (t0, *SRC), (t0 + 0.6, *BRK), (t0 + 0.8, *BRK)]
    if route in ("ok", "probe"):
        pts += [(t0 + 1.2, dx, dy), (t0 + 1.4, dx, dy, 0)]
        sc.add(sc.pulse(TGT_X + 60, DEL_Y, GREEN, t0 + 1.2, 16))
    elif route == "fail":
        pts += [(t0 + 1.2, dx, dy), (t0 + 1.35, dx, dy), (t0 + 1.75, fx, fy), (t0 + 1.9, fx, fy, 0)]
        sc.add(sc.pulse(TGT_X + 60, DEL_Y, RED, t0 + 1.2, 16), sc.pulse(TGT_X + 60, FB_Y, BLUE, t0 + 1.75, 16))
    else:
        pts += [(t0 + 1.2, fx, fy), (t0 + 1.35, fx, fy, 0)]
        sc.add(sc.pulse(BRK[0], BRK[1], RED, t0 + 0.6, 14), sc.pulse(TGT_X + 60, FB_Y, BLUE, t0 + 1.2, 16))
    sc.add(sc.chip(n, color, sc.path(pts, "linear"), w=24))


def build() -> Scene:
    T = 16.0
    sc = Scene(
        LOG_X + 330, 420, T,
        "CircuitBreakerProvider",
        "failureThreshold = 3 · resetTimeout · halfOpenMaxCalls = 1",
        "infrastructure failures count toward the threshold; FLAG_NOT_FOUND and other application errors reset it",
        "CircuitBreakerProvider wrapping a remote provider, with a fallback behind it in a firstSuccessful "
        "MultiProvider: three consecutive failures open the circuit, open-circuit calls are rejected at once and "
        "answered by the fallback without calling the delegate, and after resetTimeout one probe call succeeds and "
        "closes the circuit.",
    )
    sc.add(sc.text(28, 88, "CALLERS", "tiny"), sc.text(BRK_X, 88, "BREAKER", "tiny"),
           sc.text(TGT_X, 88, "MULTIPROVIDER", "tiny"), sc.text(TGT_X + 60, 352, "strategy: firstSuccessful", "tiny", anchor="middle"), sc.text(LOG_X + 14, 88, "EVALUATIONS", "tiny"))

    # Guide lines from the callers through the breaker to both providers
    for y in (DEL_Y, FB_Y):
        sc.add(f'<path d="M{BRK_X + BRK_W} {BRK[1]} C {BRK_X + BRK_W + 120} {BRK[1]}, {TGT_X - 150} {y}, {TGT_X} {y}" '
               f'class="rail" fill="none"/>')
    sc.add(f'<line x1="{SRC[0]}" y1="{SRC[1]}" x2="{BRK_X}" y2="{SRC[1]}" class="rail"/>')

    # The breaker: its outline and state follow the circuit
    sc.add(sc.panel(BRK_X, 120, BRK_W, 190))
    states = [("CLOSED", GREEN, [(0, OPENED), (CLOSED, T)]), ("OPEN", RED, [(OPENED, HALF_OPEN)]),
              ("HALF-OPEN", AMBER, [(HALF_OPEN, CLOSED)])]
    for label, color, windows in states:
        style = sc.shown(windows, 0.1)
        sc.add(f'<rect x="{BRK_X}" y="120" width="{BRK_W}" height="190" rx="10" fill="none" stroke="{color}" '
               f'stroke-width="2" filter="url(#glow)" style="{style}"/>',
               sc.text(BRK[0], 152, label, "big", color, "middle", style))
    counts = [(0, [(0, 4.6), (CLOSED, T)]), (1, [(4.6, 5.6)]), (2, [(5.6, OPENED)]), (3, [(OPENED, CLOSED)])]
    for n, windows in counts:
        sc.add(sc.text(BRK[0], 278, f"failures {n}/3", "hud", RED if n else DIM, "middle", sc.shown(windows, 0.05)))

    # While open: the reset timer runs down, and calls are turned away without reaching the delegate
    circ = 2 * 3.1416 * 13
    ring = sc.anim([(0, f"opacity:0;stroke-dashoffset:0"), (OPENED, "opacity:0;stroke-dashoffset:0"),
                    (OPENED + 0.1, "opacity:1;stroke-dashoffset:0"), (HALF_OPEN - 0.2, f"opacity:1;stroke-dashoffset:{circ:.1f}"),
                    (HALF_OPEN, f"opacity:0;stroke-dashoffset:{circ:.1f}"), (T, f"opacity:0;stroke-dashoffset:{circ:.1f}")])
    rx, ry = BRK[0], 70 + 120
    sc.add(f'<circle cx="{rx}" cy="{ry}" r="13" fill="none" stroke="#1e293b" stroke-width="3" '
           f'style="{sc.shown([(OPENED, HALF_OPEN)])}"/>',
           f'<circle cx="{rx}" cy="{ry}" r="13" fill="none" stroke="{RED}" stroke-width="3" stroke-dasharray="{circ:.1f}" '
           f'transform="rotate(-90 {rx} {ry})" style="{ring}"/>',
           sc.text(BRK[0], 342, "open: rejected in under 1 ms", "hud", RED, "middle", sc.shown([(7.0, 10.6)])),
           sc.text(BRK[0], 342, "resetTimeout over: one probe", "hud", AMBER, "middle", sc.shown([(10.6, CLOSED + 0.4)])))

    # The delegate (down for a while) and the fallback
    sc.add(sc.panel(TGT_X, DEL_Y - 40, 120, 80), sc.panel(TGT_X, FB_Y - 40, 120, 80))
    sc.add(sc.text(TGT_X + 60, DEL_Y - 8, "delegate", "tag", None, "middle", "fill:#e2e8f0"),
           sc.text(TGT_X + 60, DEL_Y + 12, "remote provider", "tiny", anchor="middle"),
           sc.text(TGT_X + 60, FB_Y - 8, "fallback", "tag", None, "middle", "fill:#e2e8f0"),
           sc.text(TGT_X + 60, FB_Y + 12, "EnvVarProvider", "tiny", anchor="middle"))
    sc.add(sc.badge(TGT_X + 38, DEL_Y + 28, "UP", GREEN, sc.shown([(0, DOWN), (UP, T)], 0.1)),
           sc.badge(TGT_X + 30, DEL_Y + 28, "DOWN", RED, sc.shown([(DOWN, UP)], 0.1)))

    for n, t0, route in CALLS:
        call(sc, n, t0, route)

    # The log, one line per answered call
    log = [(1.6, "#1  delegate ok", GREEN), (2.8, "#2  delegate ok", GREEN),
           (4.6, "#3  failed → fallback", RED), (5.6, "#4  failed → fallback", RED),
           (6.6, "#5  failed → fallback · OPEN", RED), (8.2, "#6  rejected → fallback", BLUE),
           (9.2, "#7  rejected → fallback", BLUE), (10.2, "#8  rejected → fallback", BLUE),
           (CLOSED, "#9  probe ok · CLOSED", AMBER), (13.8, "#10 delegate ok", GREEN), (15.0, "#11 delegate ok", GREEN)]
    sc.add(sc.panel(LOG_X, 104, sc.w - 28 - LOG_X, 262, "#070b16", "#1e293b"))
    for i, (t, s, color) in enumerate(log):
        sc.add(f'<text x="{LOG_X + 14}" y="{128 + i * 22}" class="log" style="{sc.shown([(t, T - 0.2)], 0.2)}">'
               f'<tspan fill="{DIM}">{t:04.1f}s</tspan>  <tspan fill="{color}">{s}</tspan></text>')
    return sc

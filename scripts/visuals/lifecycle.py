"""Provider status: events move the status between states, and each state decides whether an evaluation runs."""

from hud import AMBER, CYAN, DIM, EDGE, GREEN, RED, TEXT, Scene

NW, NH = 140, 46
NODES = {  # top-left corners
    "NotReady": (70, 196),
    "Ready": (330, 196),
    "Stale": (590, 112),
    "Error": (590, 280),
    "Fatal": (70, 330),
    "ShuttingDown": (330, 330),
}
LOG_X = 800

# (from, to, event label, label position, path through the gap)
EDGES = [
    ("NotReady", "Ready", "READY", (270, 202)),
    ("Ready", "Stale", "STALE", (528, 150)),
    ("Stale", "Ready", "READY", (548, 196)),
    ("Ready", "Error", "ERROR", (512, 292)),
    ("Error", "Ready", "READY", (548, 232)),
    ("Error", "Stale", "STALE", (690, 222)),
    ("NotReady", "Fatal", "init timeout", (148, 300)),
    ("Ready", "ShuttingDown", "shutdown", (408, 300)),
    ("ShuttingDown", "NotReady", "done", (262, 340)),
]

# The scenario: (start, state, evaluation result, event that ended the previous state)
STEPS = [
    (0.0, "NotReady", "fail", None),
    (2.0, "Ready", "ok", ("NotReady", "Ready")),
    (4.0, "Stale", "ok", ("Ready", "Stale")),
    (6.0, "Ready", "ok", ("Stale", "Ready")),
    (8.0, "Error", "ok", ("Ready", "Error")),
    (10.0, "Ready", "ok", ("Error", "Ready")),
    (12.0, "ShuttingDown", "fail", ("Ready", "ShuttingDown")),
    (13.5, "NotReady", "fail", ("ShuttingDown", "NotReady")),
]
RESULT = {
    "NotReady": "ProviderNotReady",
    "ShuttingDown": "ProviderNotReady",
    "Fatal": "ProviderFatal",
}


def centre(name):
    x, y = NODES[name]
    return x + NW / 2, y + NH / 2


def edge_points(a, b):
    """From the border of node a to the border of node b, along the line between their centres."""
    (ax, ay), (bx, by) = centre(a), centre(b)
    dx, dy = bx - ax, by - ay

    def clip(cx, cy, sx, sy):
        tx = (NW / 2 + 4) / abs(sx) if sx else 1e9
        ty = (NH / 2 + 4) / abs(sy) if sy else 1e9
        t = min(tx, ty)
        return cx + sx * t, cy + sy * t

    # Two edges between the same pair are drawn side by side
    off = 7 if (b, a) in {(e[0], e[1]) for e in EDGES} else 0
    nx, ny = -dy, dx
    n = (nx * nx + ny * ny) ** 0.5
    ox, oy = nx / n * off, ny / n * off
    x1, y1 = clip(ax + ox, ay + oy, dx, dy)
    x2, y2 = clip(bx + ox, by + oy, -dx, -dy)
    return x1, y1, x2, y2


def arrow(x1, y1, x2, y2, color, style="", width=1.6):
    dx, dy = x2 - x1, y2 - y1
    n = (dx * dx + dy * dy) ** 0.5
    ux, uy = dx / n, dy / n
    hx, hy = x2 - ux * 9, y2 - uy * 9
    head = f"M{x2:.1f} {y2:.1f} L{hx - uy * 4.5:.1f} {hy + ux * 4.5:.1f} L{hx + uy * 4.5:.1f} {hy - ux * 4.5:.1f} Z"
    return (
        f'<g style="{style}"><line x1="{x1:.1f}" y1="{y1:.1f}" x2="{hx:.1f}" y2="{hy:.1f}" stroke="{color}" '
        f'stroke-width="{width}"/><path d="{head}" fill="{color}"/></g>'
    )


def build() -> Scene:
    T = 16.0
    sc = Scene(
        LOG_X + 360, 476, T,
        "Provider status",
        "events move the status · the status gates every evaluation",
        "Ready, Stale and Error evaluate · NotReady and ShuttingDown fail with ProviderNotReady · Fatal with ProviderFatal",
        "Provider status: the provider starts NotReady and evaluations fail; a READY event makes it Ready; STALE and "
        "ERROR events move it to Stale or Error, where evaluations still run, and STALE also leads from Error to "
        "Stale; READY brings it back; shutdown passes "
        "through ShuttingDown to NotReady. Fatal, reached by an init timeout before the first Ready or a fatal error, "
        "fails every evaluation until setProvider installs a new provider.",
    )
    sc.add(sc.text(28, 88, "STATUS", "tiny"), sc.text(LOG_X + 14, 88, "EVALUATION OF A FLAG IN EACH STATE", "tiny"))

    # Edges, dim, with their event labels
    for a, b, label, (lx, ly) in EDGES:
        x1, y1, x2, y2 = edge_points(a, b)
        vertical = abs(x1 - x2) < 1
        sc.add(arrow(x1, y1, x2, y2, EDGE))
        sc.add(sc.text(lx, ly, label, "tiny", anchor="start" if vertical else "middle"))

    # Nodes
    for name, (x, y) in NODES.items():
        fails = name in RESULT
        sc.add(sc.panel(x, y, NW, NH))
        sc.add(sc.text(x + NW / 2, y + 20, name, "tag", TEXT, "middle"))
        sc.add(sc.text(x + NW / 2, y + 36, "fails fast" if fails else "evaluates", "tiny", anchor="middle"))
    sc.add(sc.text(70, 400, "reached by an init timeout or a fatal error;", "hud", RED),
           sc.text(70, 416, "only setProvider leaves it", "hud", RED))

    # The scenario: highlight the current node, light the edge that led there, log the evaluation
    ends = [s[0] for s in STEPS[1:]] + [T]
    for i, ((start, name, result, via), end) in enumerate(zip(STEPS, ends)):
        x, y = NODES[name]
        color = GREEN if result == "ok" else RED
        sc.add(f'<rect x="{x - 4}" y="{y - 4}" width="{NW + 8}" height="{NH + 8}" rx="12" fill="none" stroke="{color}" stroke-width="2" filter="url(#glow)" '
               f'style="{sc.shown([(start, end)], 0.12)}"/>')
        if via:
            x1, y1, x2, y2 = edge_points(*via)
            sc.add(arrow(x1, y1, x2, y2, AMBER, sc.shown([(start - 0.45, start + 0.6)], 0.1), 2.4))
        # an evaluation probe drops into the node, then the result is logged
        cx, _ = centre(name)
        sc.add(sc.chip("eval", CYAN, sc.path(
            [(start + 0.2, cx, y - 34, 0), (start + 0.4, cx, y - 34), (start + 0.8, cx, y + 2, 0.2),
             (start + 0.85, cx, y + 2, 0)]), w=38))
        outcome = "value served" if result == "ok" else RESULT[name]
        mark = "✓" if result == "ok" else "✗"
        sc.add(f'<text x="{LOG_X + 14}" y="{124 + i * 30}" class="log" style="{sc.shown([(start + 0.8, T - 0.2)], 0.2)}">'
               f'<tspan fill="{DIM}">{start:04.1f}s</tspan>  <tspan fill="{TEXT}">{name:<12}</tspan> '
               f'<tspan fill="{color}">{mark} {outcome}</tspan></text>')
    sc.add(sc.panel(LOG_X, 104, sc.w - 28 - LOG_X, 262, "#070b16", "#1e293b"))
    # The log panel is drawn after its lines in source order, so move it behind them
    sc.body.insert(0, sc.body.pop())
    return sc

"""The home page's scene: one evaluation through the pipeline, three ways.

1. a plain read goes all the way to the provider and back through the after hooks;
2. inside a transaction, an override answers at the transaction stage: no status gate, no provider, hooks still run;
3. a flag the provider does not know comes back with an error code: the error hooks run, the typed tier fails and the
   total tier serves the default.
"""

from hud import AMBER, CYAN, DIM, EDGE, GREEN, PINK, RED, TEXT, Scene

TOP, BOTTOM = 210, 334                      # outbound and return rows of stages
UP, DOWN = TOP - 52, BOTTOM + 52            # the lanes the evaluation travels in, above and below them
CALLER_X = 86
OUT = {"context": 236, "before": 392, "transaction": 548, "status": 704, "provider": 862}
RET = {"after": 548, "finally": 314}
BOX_W, BOX_H = 132, 62
RUN = 6.5                                # seconds per run
LABELS = {
    "context": ("context", "6 levels merged"),
    "before": ("before hooks", "in order"),
    "transaction": ("transaction", "override · cache"),
    "status": ("status gate", "fail fast?"),
    "provider": ("provider", "default 1 s timeout"),
    "after": ("after · error", "reverse order"),
    "finally": ("finallyAfter", "always runs"),
}


def box(sc: Scene, x, y, name):
    title, sub = LABELS[name]
    sc.add(sc.panel(x - BOX_W / 2, y - BOX_H / 2, BOX_W, BOX_H),
           sc.text(x, y - 4, title, "tag", TEXT, "middle"),
           sc.text(x, y + 15, sub, "hud", None, "middle"))


def glow(sc: Scene, x, y, color, windows):
    """Lights a stage's outline while the evaluation is in it."""
    sc.add(f'<rect x="{x - BOX_W / 2}" y="{y - BOX_H / 2}" width="{BOX_W}" height="{BOX_H}" rx="10" fill="none" '
           f'stroke="{color}" stroke-width="2" filter="url(#glow)" style="{sc.shown(windows, 0.1)}"/>')


def run(sc: Scene, k, key, color, result, result_color, via_provider, captions, outcome):
    o = k * RUN
    stops = ["context", "before", "transaction"] + (["status", "provider"] if via_provider else [])
    # outbound: the call travels right along the top row
    t, pts = o + 0.3, [(o + 0.1, CALLER_X, UP, 0), (o + 0.3, CALLER_X, UP)]
    for s in stops:
        t += 0.4
        pts += [(t, OUT[s], UP), (t + 0.12, OUT[s], UP)]
        glow(sc, OUT[s], TOP, color, [(t - 0.1, t + 0.35)])
        t += 0.12
    turn = OUT[stops[-1]]
    hold = t + (0.6 if not via_provider else 0.3)
    pts += [(hold, turn, UP), (hold + 0.15, turn, UP + 20, 0)]
    sc.add(sc.chip(key, color, sc.path(pts, "ease-in-out"), w=len(key) * 7 + 16))
    if via_provider:
        sc.add(sc.pulse(turn, UP, color, t - 0.3, 20))
    else:
        sc.add(sc.text(turn + 8, (TOP + BOTTOM) / 2 + 4, "override → true", "tag", AMBER, "start", sc.shown([(t - 0.2, hold + 0.3)])))
    # return: the answer drops to the bottom row and travels back left, through the after/error and finally stages
    t = hold
    back = [(t, turn, DOWN - 20, 0), (t + 0.15, turn, DOWN), (t + 0.4, turn, DOWN)]
    t += 0.4
    for s in ("after", "finally"):
        t += 0.45 if s == "after" else 0.4
        back += [(t, RET[s], DOWN), (t + 0.12, RET[s], DOWN)]
        glow(sc, RET[s], BOTTOM, result_color, [(t - 0.1, t + 0.35)])
        t += 0.12
    t += 0.4
    assert t < o + RUN - 1.2, f"run {k} overflows its window"
    back += [(t, CALLER_X, DOWN), (o + RUN - 0.25, CALLER_X, DOWN), (o + RUN - 0.1, CALLER_X, DOWN, 0)]
    sc.add(sc.chip(result, result_color, sc.path(back, "ease-in-out"), w=len(result) * 7 + 16))
    # what the caller sees
    for i, (line, c) in enumerate(outcome):
        sc.add(sc.text(CALLER_X - 50, 434 + i * 20, line, "log", c, "start", sc.shown([(t - 0.1, o + RUN - 0.05)])))
    sc.add(sc.text(CALLER_X - 50, 92, captions, "log", TEXT, "start", sc.shown([(o + 0.05, o + RUN - 0.05)])))


def build() -> Scene:
    sc = Scene(
        960, 530, 3 * RUN,
        "FeatureFlags.boolean(key, default)",
        "one evaluation, three outcomes",
        "hooks run on every path; only the provider path passes the status gate and the timeout",
        "One flag evaluation through the pipeline. The context is merged and the before hooks run in order. A "
        "transaction override answers at the transaction stage without calling the provider; otherwise the status "
        "gate is checked and the provider is called on the blocking pool with a timeout. The answer returns through "
        "the after hooks, or the error hooks when it carries an error code, in reverse order, then finallyAfter. "
        "For a flag the provider does not know, the typed tier fails with FlagNotFound and the total tier serves the "
        "default.",
    )
    sc.add(sc.text(CALLER_X, 124, "CALLER", "tiny", anchor="middle"),
           sc.text(OUT["context"], 124, "FEATUREFLAGS", "tiny", anchor="middle"),
           sc.text(OUT["provider"], 124, "OPENFEATURE SDK", "tiny", anchor="middle"))
    sc.add(sc.panel(CALLER_X - 44, UP - 18, 88, DOWN - UP + 36, "#070b16", "#1e293b"),
           sc.text(CALLER_X, (TOP + BOTTOM) / 2 + 4, "your code", "tiny", anchor="middle"))
    # rails between stages
    sc.add(sc.rail(CALLER_X + 34, TOP, OUT["provider"] - BOX_W / 2, "#60a5fa"),
           sc.rail(CALLER_X + 34, BOTTOM, OUT["provider"] + 2, "#60a5fa"),
           f'<line x1="{OUT["provider"]}" y1="{TOP + BOX_H / 2}" x2="{OUT["provider"]}" y2="{BOTTOM}" class="rail"/>',
           f'<line x1="{OUT["transaction"]}" y1="{TOP + BOX_H / 2}" x2="{OUT["transaction"]}" '
           f'y2="{BOTTOM - BOX_H / 2}" stroke="{EDGE}" stroke-dasharray="3 5"/>')
    for name, x in OUT.items():
        box(sc, x, TOP, name)
    for name, x in RET.items():
        box(sc, x, BOTTOM, name)

    run(sc, 0, '"new-checkout"', CYAN, "true", GREEN, True,
        'FeatureFlags.boolean("new-checkout", false)',
        [("→ true   reason TARGETING_MATCH", GREEN)])
    run(sc, 1, '"new-checkout"', AMBER, "true", AMBER, False,
        'inside FeatureFlags.transaction(overrides = Map("new-checkout" -> true))',
        [("→ true   reason CACHED, provider never called", AMBER)])
    run(sc, 2, '"dark-mode"', PINK, "FLAG_NOT_FOUND", RED, True,
        'FeatureFlags.boolean("dark-mode", false)   — the provider does not know it',
        [("boolean          → fails with FeatureFlagError.FlagNotFound", RED),
         ("booleanOrDefault → false   reason ERROR, errorCode FLAG_NOT_FOUND", DIM)])
    return sc

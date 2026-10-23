"""Hooks: before runs api, client, invocation; the provider answers; after (or error) and finallyAfter run in reverse.

Two passes: a clean answer runs `after`; an answer with an error code runs `error` instead.
"""

from hud import CYAN, DIM, EDGE, GREEN, RED, TEXT, Scene

ROWS = {"api": 150, "client": 205, "invocation": 260}
CELL_W, CELL_H = 160, 38
BEFORE_X, PROVIDER_X, MIDDLE_X, FINALLY_X = 360, 535, 720, 900
CALLER_X, CALLER_Y = 214, 205
PASS = 8.0


def cell(sc: Scene, x, y):
    return (f'<rect x="{x - CELL_W / 2}" y="{y - CELL_H / 2}" width="{CELL_W}" height="{CELL_H}" rx="8" '
            f'fill="#0b1222" stroke="{EDGE}"/>')


def lit(sc: Scene, x, y, color, on, off):
    return (f'<rect x="{x - CELL_W / 2}" y="{y - CELL_H / 2}" width="{CELL_W}" height="{CELL_H}" rx="8" '
            f'fill="{color}" fill-opacity="0.12" stroke="{color}" stroke-width="1.5" filter="url(#glow)" '
            f'style="{sc.shown([(on, off)])}"/>')


def run(sc: Scene, o: float, ok: bool):
    """One evaluation, starting `o` seconds into the loop."""
    answer = GREEN if ok else RED
    end = o + PASS - 0.15
    before = [("api", 0.9), ("client", 1.4), ("invocation", 1.9)]
    middle = [("invocation", 3.4), ("client", 3.9), ("api", 4.4)]
    finally_ = [("invocation", 4.9), ("client", 5.4), ("api", 5.9)]
    stage_order = [(BEFORE_X, before, CYAN), (MIDDLE_X, middle, answer), (FINALLY_X, finally_, answer)]

    n = 0
    for x, visits, color in stage_order:
        for row, t in visits:
            n += 1 if x != MIDDLE_X or n != 3 else 2  # 4 is the provider
            y = ROWS[row]
            sc.add(lit(sc, x, y, color, o + t, end))
            sc.add(sc.text(x + CELL_W / 2 - 8, y + 5, str(n), "tag", color, "end", sc.shown([(o + t, end)])))
            sc.add(sc.pulse(x, y, color, o + t, 14))
    sc.add(sc.text(PROVIDER_X, 120, "4", "tag", CYAN, "middle", sc.shown([(o + 2.5, end)])))
    sc.add(sc.pulse(PROVIDER_X, 205, answer, o + 2.6, 22))

    # the evaluation goes in...
    def hold(row_x, t, row):
        return [(o + t, row_x, ROWS[row]), (o + t + 0.2, row_x, ROWS[row])]

    going = [(o + 0.2, CALLER_X, CALLER_Y, 0), (o + 0.35, CALLER_X, CALLER_Y)]
    for row, t in before:
        going += hold(BEFORE_X + 30, t, row)
    going += [(o + 2.5, PROVIDER_X, 205), (o + 2.75, PROVIDER_X, 205), (o + 2.8, PROVIDER_X, 205, 0)]
    sc.add(sc.chip("new-ui", CYAN, sc.path(going), w=58))

    # ...and its answer comes back out, in reverse
    label = "true" if ok else "error"
    coming = [(o + 2.75, PROVIDER_X, 205, 0), (o + 2.8, PROVIDER_X, 205)]
    for row, t in middle:
        coming += hold(MIDDLE_X + 30, t, row)
    for row, t in finally_:
        coming += hold(FINALLY_X + 30, t, row)
    coming += [(o + 6.5, FINALLY_X + 30, 320), (o + 6.9, CALLER_X, 320), (o + 7.05, CALLER_X, 320, 0)]
    sc.add(sc.chip(label, answer, sc.path(coming), w=44 if ok else 50))

    sc.add(sc.text(PROVIDER_X, 290, "value = true" if ok else "FLAG_NOT_FOUND", "tiny", anchor="middle",
                   style=sc.shown([(o + 2.6, end)])))
    sc.add(sc.text(MIDDLE_X, 112, "after" if ok else "error", "tag", answer, "middle", sc.shown([(o + 3.2, end)])))
    result = "→ true" if ok else "→ FlagNotFound"
    sc.add(sc.text(28, 330, result, "tag", answer, style=sc.shown([(o + 7.0, end)])))
    if not ok:
        sc.add(sc.text(28, 348, "(OrDefault: your default)", "tiny", style=sc.shown([(o + 7.0, end)])))


def build() -> Scene:
    sc = Scene(
        1100, 440, 2 * PASS,
        "Hooks",
        "ZIO API → client → invocation · Java SDK hooks run inside the provider call",
        "before runs in registration order; after, error and finallyAfter run in reverse, like nested try / finally",
        "One evaluation through three hooks: before runs the api, client and invocation hooks in order; the provider "
        "is called; after and finallyAfter run in reverse order. On the second pass the provider answers with an "
        "error code, so the error stage runs instead of after.",
    )
    sc.add(sc.text(28, 84, "CALLER", "tiny"), sc.text(BEFORE_X, 84, "BEFORE", "tiny", anchor="middle"),
           sc.text(PROVIDER_X, 84, "PROVIDER", "tiny", anchor="middle"),
           sc.text(MIDDLE_X, 84, "AFTER | ERROR", "tiny", anchor="middle"),
           sc.text(FINALLY_X, 84, "FINALLYAFTER", "tiny", anchor="middle"))
    sc.add(sc.text(28, CALLER_Y - 6, "boolean(", "tag", TEXT), sc.text(28, CALLER_Y + 12, '"new-ui")', "tag", TEXT))
    for row, y in ROWS.items():
        for x in (BEFORE_X, MIDDLE_X, FINALLY_X):
            sc.add(cell(sc, x, y), sc.text(x - CELL_W / 2 + 12, y + 4, row, "hud"))
    sc.add(sc.panel(PROVIDER_X - 50, 128, 100, 154), sc.text(PROVIDER_X, 196, "Java API", "tiny", anchor="middle"),
           sc.text(PROVIDER_X, 210, "+ provider", "tiny", anchor="middle"),
           sc.text(PROVIDER_X, 224, "hooks", "tiny", anchor="middle"))
    run(sc, 0.0, True)
    run(sc, PASS, False)
    return sc

"""One missing flag, two answers: the typed tier fails with FlagNotFound; the total tier serves the default and says why.

Both tiers run the same evaluation; the total tier only folds a failure into the caller's default afterwards.
"""

from hud import AMBER, CYAN, DIM, GREEN, PINK, RED, Scene

ROWS = {"typed": 128, "total": 262}
START_X = 404                       # where a caller's chip sets off
JOIN_X, PIPE_X2 = 470, 680          # the shared evaluation
PROV_X = 900                        # the provider panel's left edge
MID_Y = (ROWS["typed"] + ROWS["total"]) / 2


def build() -> Scene:
    T = 12.0
    sc = Scene(
        1100, 440, T,
        "Two tiers, one evaluation",
        "flag \"dark-mode\" is not defined in the provider",
        "the typed tier reports why it could not read the flag; the total tier serves your default and says why",
        "Two callers evaluate the missing flag dark-mode through the same evaluation path. The provider answers "
        "FLAG_NOT_FOUND. The typed tier, boolean, fails with FeatureFlagError.FlagNotFound. The total tier, "
        "booleanOrDefault, serves the default false with reason ERROR and error code FLAG_NOT_FOUND, and logs a "
        "rate-limited warning.",
    )
    sc.add(sc.text(28, 88, "CALLER", "tiny"), sc.text(JOIN_X, 88, "SAME EVALUATION PATH", "tiny"),
           sc.text(PROV_X + 30, 88, "PROVIDER", "tiny", anchor="middle"))

    # The callers
    sc.add(sc.text(28, ROWS["typed"] - 4, 'boolean("dark-mode", false)', "key", CYAN),
           sc.text(28, ROWS["typed"] + 15, "typed tier · IO[FeatureFlagError, Boolean]", "mode", DIM),
           sc.text(28, ROWS["total"] - 4, 'booleanOrDefault("dark-mode", false)', "key", PINK),
           sc.text(28, ROWS["total"] + 15, "total tier · UIO[Boolean]", "mode", DIM))

    # The shared evaluation and the provider
    sc.add(sc.panel(JOIN_X, MID_Y - 34, PIPE_X2 - JOIN_X, 68),
           sc.text((JOIN_X + PIPE_X2) / 2, MID_Y - 6, "context · hooks · timeout", "hud", None, "middle"),
           sc.text((JOIN_X + PIPE_X2) / 2, MID_Y + 14, "→ provider call", "hud", None, "middle"),
           sc.rail(PIPE_X2, MID_Y, PROV_X, CYAN),
           sc.panel(PROV_X, 104, 60, 206), sc.text(PROV_X + 30, MID_Y - 40, "resolve", "tiny", anchor="middle"))

    # Both callers set off together and join the one evaluation
    for name, y, color in (("typed", ROWS["typed"], CYAN), ("total", ROWS["total"], PINK)):
        sc.add(sc.chip("dark-mode", color, sc.path(
            [(0.3, START_X - 20, y, 0), (0.6, START_X, y), (1.0, START_X, y), (1.6, JOIN_X - 10, MID_Y),
             (1.7, JOIN_X + 10, MID_Y, 0)]), w=78))
    sc.add(sc.chip("dark-mode", CYAN, sc.path(
        [(1.6, JOIN_X + 10, MID_Y, 0), (1.7, JOIN_X + 40, MID_Y), (2.9, PROV_X - 44, MID_Y),
         (3.1, PROV_X - 30, MID_Y, 0)], "linear"), w=78))
    sc.add(sc.pulse(PROV_X + 30, MID_Y, AMBER, 3.1, 18))

    # The provider answers with an error code, not an exception
    sc.add(sc.chip("FLAG_NOT_FOUND", AMBER, sc.path(
        [(3.2, PROV_X - 60, MID_Y, 0), (3.5, PROV_X - 60, MID_Y), (4.8, JOIN_X + 60, MID_Y),
         (5.1, JOIN_X - 50, MID_Y), (5.25, JOIN_X - 50, MID_Y, 0)], "linear"), w=112))

    # ... which each tier answers its own way
    sc.add(sc.chip("FlagNotFound", RED, sc.path(
        [(5.1, JOIN_X - 50, MID_Y, 0), (5.2, JOIN_X - 50, MID_Y), (6.0, START_X + 40, ROWS["typed"]),
         (11.4, START_X + 40, ROWS["typed"]), (11.8, START_X + 40, ROWS["typed"], 0)]), w=100))
    sc.add(sc.chip("false", GREEN, sc.path(
        [(5.1, JOIN_X - 50, MID_Y, 0), (5.2, JOIN_X - 50, MID_Y), (6.0, START_X + 40, ROWS["total"]),
         (11.4, START_X + 40, ROWS["total"]), (11.8, START_X + 40, ROWS["total"], 0)]), w=60))
    sc.add(sc.text(START_X + 100, ROWS["typed"] + 4, "fails: no value", "tag", RED, "start", sc.shown([(6.0, 11.8)])),
           sc.badge(28, ROWS["typed"] + 44, 'Fail(FeatureFlagError.FlagNotFound("dark-mode"))', RED,
                    sc.shown([(6.3, 11.8)])),
           sc.text(START_X + 80, ROWS["total"] + 4, "serves the default", "tag", GREEN, "start",
                   sc.shown([(6.0, 11.8)])),
           sc.badge(28, ROWS["total"] + 44, "reason = ERROR · errorCode = FLAG_NOT_FOUND", GREEN,
                    sc.shown([(6.3, 11.8)])))

    # The total tier leaves a rate-limited breadcrumb
    sc.add(sc.panel(28, 336, 1044, 34, "#070b16", "#1e293b"),
           f'<text x="42" y="358" class="log" style="{sc.shown([(6.8, 11.8)], 0.2)}">'
           f'<tspan fill="{AMBER}">WARN</tspan>  <tspan fill="{DIM}">Flag \'dark-mode\' fell back to its default '
           f'false (FlagNotFound: Flag \'dark-mode\' not found)</tspan></text>')
    return sc

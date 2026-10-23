"""Evaluation context: the levels merge lowest first, a higher level replaces `plan`, a before hook's addition lands last."""

from hud import AMBER, BLUE, CYAN, DIM, EDGE, GREEN, PINK, Scene

VIOLET, ORANGE = "#a78bfa", "#fb923c"

ROW_Y0, ROW_DY = 108, 46           # the levels' rows
CHIP_X, CHIP_W = 330, 118          # where a level's attributes sit
PANEL_X, PANEL_W = 700, 372        # the effective context
SLOT_X = PANEL_X + 250             # where a merged attribute sits
SLOTS = {"targetingKey": 140, "env": 190, "service": 240, "plan": 290, "region": 340}
END = 13.6

# level, how it is set, colour, attributes (slot, label)
LEVELS = [
    ("global", "setGlobalContext", BLUE, [("env", "env=prod"), ("plan", "plan=free")]),
    ("transaction", "transaction(context = …)", AMBER, [("plan", "plan=trial")]),
    ("client", "setClientContext", CYAN, [("service", "service=checkout")]),
    ("context source", "ContextSource", GREEN, [("targetingKey", "key=user-42")]),
    ("scoped", "withContext { … }", PINK, [("plan", "plan=pro")]),
    ("invocation", "the call's ctx argument", VIOLET, [("plan", "plan=premium")]),
    ("before hook", "FeatureHook.before", ORANGE, [("region", "region=eu")]),
]


def build() -> Scene:
    sc = Scene(
        1100, 500, 14.0,
        "Evaluation context",
        "merged lowest first · a higher level wins on each key",
        "global → transaction → client → context source → scoped → invocation, then a before hook's additions on top",
        "Six context levels merge into the one context the provider sees: each level adds its attributes in turn, a "
        "higher level replaces the plan set by a lower one, and a before hook's addition lands last.",
    )
    sc.add(sc.text(28, 84, "LEVELS · LOWEST FIRST", "tiny"), sc.text(PANEL_X, 84, "EFFECTIVE CONTEXT", "tiny"))
    sc.add(sc.panel(PANEL_X, 104, PANEL_W, 276))
    for name, y in SLOTS.items():
        sc.add(sc.text(PANEL_X + 24, y + 4, name, "hud"),
               f'<line x1="{PANEL_X + 24}" y1="{y + 18}" x2="{PANEL_X + PANEL_W - 24}" y2="{y + 18}" stroke="#1e293b"/>')

    # When each slot's current chip is replaced, so it can fade as the next one lands
    arrivals: dict[str, list[float]] = {}
    for i, (_, _, _, attrs) in enumerate(LEVELS):
        t = 0.8 + i * 1.4
        for slot, _ in attrs:
            arrivals.setdefault(slot, []).append(t + 0.8)

    for i, (name, how, color, attrs) in enumerate(LEVELS):
        y = ROW_Y0 + i * ROW_DY
        t = 0.8 + i * 1.4
        # the row, lit while its level is merged
        sc.add(f'<rect x="20" y="{y - 20}" width="{PANEL_X - 60}" height="40" rx="8" fill="{color}" '
               f'fill-opacity="0.08" stroke="{color}" stroke-opacity="0.5" style="{sc.shown([(t - 0.2, t + 1.1)])}"/>')
        sc.add(sc.text(36, y - 2, name, "tag", color), sc.text(36, y + 13, how, "tiny"))
        for k, (slot, label) in enumerate(attrs):
            x = CHIP_X + k * (CHIP_W + 12)
            sc.add(sc.chip(label, color, "opacity:.45", x, y, CHIP_W))
            land = t + 0.8
            later = [a for a in arrivals[slot] if a > land]
            gone = later[0] if later else END
            sc.add(sc.chip(label, color, sc.path([
                (t, x, y, 0), (t + 0.05, x, y), (land, SLOT_X, SLOTS[slot]),
                (gone - 0.05, SLOT_X, SLOTS[slot]), (gone, SLOT_X, SLOTS[slot], 0),
            ]), w=CHIP_W))
            sc.add(sc.pulse(SLOT_X, SLOTS[slot], color, land, 14))

    sc.add(sc.text(PANEL_X + PANEL_W - 24, 120, "a later level replaces plan", "tiny", anchor="end",
                   style=sc.shown([(2.9, 8.9)])))
    done = 0.8 + 6 * 1.4 + 1.2
    sc.add(f'<rect x="{PANEL_X}" y="104" width="{PANEL_W}" height="276" rx="10" fill="none" stroke="{GREEN}" '
           f'stroke-width="2" filter="url(#glow)" style="{sc.shown([(done, END)])}"/>')
    sc.add(sc.text(PANEL_X + PANEL_W / 2, 412, "→ sent to the provider", "tag", GREEN, "middle",
                   sc.shown([(done, END)])))
    return sc

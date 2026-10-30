"""FeatureFlags.setProvider: the status goes NotReady while the new provider initializes. If initialize() succeeds the
new provider serves; if it throws the old provider is restored; if it times out the new provider stays registered
and the status is Error."""

from hud import AMBER, CYAN, DIM, GREEN, RED, TEXT, Scene

NH = 54
N1, N2, N3 = (250, 210), (560, 250), (900, 262)  # (left x, width) of the before / during / after columns
LANES = [  # (y centre, title, subtitle, arrow label, outcome, detail, colour)
    (160, "initialize succeeds", "setProvider returns", "ready", "Ready", "new provider serves", GREEN),
    (264, "initialize throws", "ProviderInitializationFailed", "throws", "Ready", "old provider restored", GREEN),
    (368, "initialize times out", "ProviderInitializationFailed", "initTimeout", "Error",
     "new provider kept, not rolled back", RED),
]
SLOT = 5.0  # seconds per scenario


def centre(col):
    return col[0] + col[1] / 2


def build() -> Scene:
    T = SLOT * len(LANES)
    sc = Scene(
        N3[0] + N3[1] + 28, 470, T,
        "FeatureFlags.setProvider",
        "status leaves Ready while the new provider initializes · three ways it ends",
        "on a timeout the status turns Ready by itself if the new provider finishes later · setProvider again recovers",
        "setProvider: the status moves from Ready to NotReady while the new provider initializes, and evaluations fail "
        "with ProviderNotReady. If initialize succeeds the new provider serves and the status is Ready (or Stale if the "
        "provider reports stale); if it throws, "
        "the old provider is restored and the status returns to Ready; if it times out, the new provider stays "
        "registered and the status is Error.",
    )
    sc.add(sc.text(28, 88, "SCENARIO", "tiny"), sc.text(N1[0], 88, "BEFORE", "tiny"),
           sc.text(N2[0], 88, "DURING", "tiny"), sc.text(N3[0], 88, "AFTER", "tiny"))

    for i, (y, title, sub, label, outcome, detail, color) in enumerate(LANES):
        t0 = i * SLOT
        top = y - NH / 2
        sc.add(sc.text(28, y - 4, title, "tag", TEXT), sc.text(28, y + 14, sub, "tiny"))
        sc.add(sc.rail(N1[0] + N1[1], y, N2[0], CYAN), sc.rail(N2[0] + N2[1], y, N3[0], CYAN))
        sc.add(sc.text((N2[0] + N2[1] + N3[0]) / 2, y - 14, label, "tiny", anchor="middle"))
        for col, name, line, c in (
            (N1, "Ready", "old provider serves", GREEN),
            (N2, "NotReady", "evaluations fail fast", AMBER),
            (N3, outcome, detail, color),
        ):
            sc.add(sc.panel(col[0], top, col[1], NH),
                   sc.text(centre(col), y - 4, name, "tag", c, "middle"),
                   sc.text(centre(col), y + 14, line, "hud", None, "middle"))
        # the active node glows while the marker is on it
        for col, c, on, off in ((N1, GREEN, 0.0, 1.0), (N2, AMBER, 1.0, 3.0), (N3, color, 3.0, SLOT - 0.2)):
            sc.add(f'<rect x="{col[0] - 4}" y="{top - 4}" width="{col[1] + 8}" height="{NH + 8}" rx="12" fill="none" '
                   f'stroke="{c}" stroke-width="2" filter="url(#glow)" style="{sc.shown([(t0 + on, t0 + off)], 0.12)}"/>')
        sc.add(sc.chip("setProvider", CYAN, sc.path([
            (t0, centre(N1), y - 40, 0), (t0 + 0.3, centre(N1), y - 40), (t0 + 1.0, centre(N2), y - 40),
            (t0 + 3.0, centre(N2), y - 40), (t0 + 3.6, centre(N3), y - 40), (t0 + SLOT - 0.2, centre(N3), y - 40, 0),
        ]), w=84))
    return sc

"""Under the hood: evaluations run down from your fibers to the provider; provider events run back up to subscribers."""

from hud import AMBER, BLUE, CYAN, GREEN, PINK, TEXT, Scene

EVAL_Y, EVENT_Y = 176, 330
COLS = {"fibers": 126, "flags": 392, "sdk": 646, "provider": 856}
WIDTHS = {"fibers": 196, "flags": 230, "sdk": 170, "provider": 130}
T = 12.0


def column(sc: Scene, name, title, rows):
    x, w = COLS[name], WIDTHS[name]
    sc.add(sc.panel(x - w / 2, 104, w, 300), sc.text(x, 96, title, "tiny", anchor="middle"))
    for y, label, sub in rows:
        sc.add(sc.text(x, y, label, "tag", TEXT, "middle"))
        if sub:
            sc.add(sc.text(x, y + 16, sub, "hud", None, "middle"))


def build() -> Scene:
    sc = Scene(
        960, 480, T,
        "Under the hood",
        "evaluations go down · events come back up",
        "the provider call runs on ZIO's blocking pool; events arrive on the SDK's threads and are bridged into ZIO",
        "Evaluations start on your fiber, pass through FeatureFlags (context, hooks, transaction, status gate) and "
        "call the OpenFeature client on ZIO's blocking pool, which asks the provider. Provider events arrive on the "
        "SDK's own threads; FeatureFlags bridges them into ZIO, updates the provider status and publishes them on an "
        "event hub that feeds the events stream and the registered handlers.",
    )
    column(sc, "fibers", "YOUR CODE", [(EVAL_Y - 40, "fiber", "FeatureFlags.boolean"),
                                       (EVENT_Y - 10, "stream", "FeatureFlags.events"),
                                       (EVENT_Y + 50, "handler", "onConfigurationChanged")])
    column(sc, "flags", "FEATUREFLAGS (ZIO)", [(EVAL_Y - 40, "context · hooks · transaction", "Ref + FiberRef"),
                                               (EVAL_Y + 30, "status gate", None),
                                               (EVENT_Y - 10, "status machine", "NotReady … ShuttingDown"),
                                               (EVENT_Y + 50, "event hub", "sliding, 256 events")])
    column(sc, "sdk", "OPENFEATURE SDK", [(EVAL_Y - 40, "Client", "get*Details"),
                                          (EVENT_Y - 10, "event dispatch", "SDK threads")])
    column(sc, "provider", "PROVIDER", [(EVAL_Y - 40, "evaluate", None), (EVENT_Y - 10, "emit events", None)])
    sc.add(sc.rail(COLS["fibers"] + 98, EVAL_Y, COLS["provider"] - 70, BLUE),
           sc.rail(COLS["fibers"] + 98, EVENT_Y + 20, COLS["provider"] - 70, PINK))
    sc.add(sc.badge(COLS["sdk"] - 64, EVAL_Y + 28, "blocking pool · timeout", BLUE))

    # two evaluations, out and back
    for o, key, value in ((0.3, "checkout", "true"), (5.8, "banner", '"sale"')):
        xs = [COLS["fibers"], COLS["flags"], COLS["sdk"], COLS["provider"]]
        pts, t = [(o, xs[0], EVAL_Y, 0), (o + 0.2, xs[0], EVAL_Y)], o + 0.2
        for x in xs[1:]:
            t += 0.6
            pts += [(t, x, EVAL_Y), (t + 0.25, x, EVAL_Y)]
            t += 0.25
        pts.append((t + 0.1, xs[-1], EVAL_Y, 0))
        sc.add(sc.chip(key, CYAN, sc.path(pts), w=len(key) * 7 + 16), sc.pulse(xs[-1], EVAL_Y, CYAN, t - 0.2, 16))
        back, b = [(t, xs[-1], EVAL_Y, 0), (t + 0.1, xs[-1], EVAL_Y)], t + 0.1
        for x in reversed(xs[:-1]):
            b += 0.5
            back += [(b, x, EVAL_Y), (b + 0.2, x, EVAL_Y)]
            b += 0.2
        back.append((b + 0.4, xs[0], EVAL_Y, 0))
        sc.add(sc.chip(value, GREEN, sc.path(back), w=len(value) * 7 + 16))

    # a configuration change travels up from the provider, through the bridge, to the stream and the handler
    o = 3.2
    up = [(o, COLS["provider"], EVENT_Y + 20, 0), (o + 0.2, COLS["provider"], EVENT_Y + 20),
          (o + 0.9, COLS["sdk"], EVENT_Y + 20), (o + 1.2, COLS["sdk"], EVENT_Y + 20),
          (o + 1.9, COLS["flags"], EVENT_Y + 20), (o + 2.3, COLS["flags"], EVENT_Y + 20),
          (o + 2.35, COLS["flags"], EVENT_Y + 20, 0)]
    sc.add(sc.chip("CONFIG_CHANGED", PINK, sc.path(up), w=116), sc.pulse(COLS["flags"], EVENT_Y - 14, PINK, o + 1.9, 18))
    for y in (EVENT_Y - 34, EVENT_Y + 26):
        fan = [(o + 2.3, COLS["flags"], EVENT_Y + 20, 0), (o + 2.4, COLS["flags"] - 60, EVENT_Y + 20),
               (o + 3.2, COLS["fibers"], y), (o + 4.6, COLS["fibers"], y),
               (o + 4.8, COLS["fibers"], y, 0)]
        sc.add(sc.chip("changed: banner", PINK, sc.path(fan), w=116))
    sc.add(sc.text(COLS["flags"], EVENT_Y - 46, "status stays Ready", "tag", AMBER, "middle",
                   sc.shown([(o + 1.9, o + 4.2)])))
    return sc

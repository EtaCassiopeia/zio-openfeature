"""Transactions: an override is served locally, a first read goes to the provider and is cached, a repeat read is
served from the cache, and every read is recorded in the TransactionResult."""

from hud import AMBER, BLUE, CYAN, DIM, EDGE, GREEN, TEXT, Scene

READ_Y = [140, 190, 240, 290]
ANSWER_X = 236
TX_X, TX_W = 300, 330
OVERRIDE = (TX_X + 230, 150)
CACHE_Y = 262
CACHE_SLOTS = [TX_X + 140, TX_X + 258]
PROV_X = 700
LOG_X = 812
END = 15.6


def build() -> Scene:
    sc = Scene(
        1100, 470, 16.0,
        "FeatureFlags.transaction",
        "overrides · one value per flag · every read recorded",
        "an override never reaches the provider; a repeat read is served from the transaction and not recorded again",
        "A transaction block: an overridden flag is served without calling the provider; the first read of another "
        "flag calls the provider and is cached; the repeat read is served from the cache; every read is recorded and "
        "returned in the TransactionResult.",
    )
    sc.add(sc.text(28, 96, "THE BLOCK", "tiny"), sc.text(TX_X, 96, "TRANSACTION", "tiny"),
           sc.text(PROV_X + 30, 96, "PROVIDER", "tiny", anchor="middle"), sc.text(LOG_X, 96, "RECORDED", "tiny"))

    reads = ['boolean("feature-a")', 'int("max-items")', 'int("max-items")', 'boolean("feature-b")']
    for y, r in zip(READ_Y, reads):
        sc.add(sc.text(28, y + 4, r, "hud", TEXT))

    # the transaction: its overrides and its cache
    sc.add(sc.panel(TX_X, 110, TX_W, 200))
    sc.add(sc.text(TX_X + 18, 154, "overrides", "hud"), sc.chip("feature-a → true", AMBER, "", OVERRIDE[0], OVERRIDE[1], 120))
    sc.add(f'<line x1="{TX_X + 18}" y1="200" x2="{TX_X + TX_W - 18}" y2="200" stroke="{EDGE}"/>')
    sc.add(sc.text(TX_X + 18, CACHE_Y + 4, "cache", "hud"))
    sc.add(sc.panel(PROV_X, 116, 60, 188), sc.text(PROV_X + 30, 214, "eval", "tiny", anchor="middle"))
    sc.add(sc.panel(LOG_X, 110, sc.w - 28 - LOG_X, 200, "#070b16", "#1e293b"))

    def ask(label, y, to, t0, t1, w=76):
        sc.add(sc.chip(label, CYAN, sc.path([(t0, ANSWER_X, y, 0), (t0 + 0.05, ANSWER_X, y), (t1, *to),
                                             (t1 + 0.1, *to, 0)]), w=w))

    def answer(label, color, frm, y, t0, t1, w=40):
        sc.add(sc.chip(label, color, sc.path([(t0, *frm, 0), (t0 + 0.05, *frm), (t1, ANSWER_X, y),
                                              (END - 0.1, ANSWER_X, y), (END, ANSWER_X, y, 0)]), w=w))

    def cache(label, slot, t):
        x = CACHE_SLOTS[slot]
        sc.add(sc.chip(label, GREEN, sc.path([(t - 0.6, PROV_X + 30, 210, 0), (t - 0.55, PROV_X + 30, 210),
                                              (t, x, CACHE_Y), (END - 0.1, x, CACHE_Y), (END, x, CACHE_Y, 0)]),
                       w=len(label) * 6.4 + 18))

    def record(i, text, t):
        sc.add(sc.text(LOG_X + 14, 134 + i * 26, text, "log", TEXT, style=sc.shown([(t, END)])))

    prov = (PROV_X + 30, 210)
    # 1. an override: answered by the transaction itself
    ask("feature-a", READ_Y[0], OVERRIDE, 0.6, 1.4)
    sc.add(sc.pulse(*OVERRIDE, AMBER, 1.4, 16))
    answer("true", AMBER, OVERRIDE, READ_Y[0], 1.5, 2.2)
    record(0, f'feature-a = true  <tspan fill="{AMBER}">override</tspan>', 2.2)

    # 2. a first read: the provider answers, and the answer is cached
    ask("max-items", READ_Y[1], prov, 3.0, 4.0)
    sc.add(sc.pulse(*prov, BLUE, 4.0, 18))
    cache("max-items 20", 0, 4.7)
    answer("20", BLUE, prov, READ_Y[1], 4.1, 5.0, 32)
    record(1, f'max-items = 20    <tspan fill="{BLUE}">provider</tspan>', 5.0)

    # 3. a repeat read: served from the cache, reason Cached, not recorded again
    ask("max-items", READ_Y[2], (CACHE_SLOTS[0], CACHE_Y), 6.0, 6.8)
    sc.add(sc.pulse(CACHE_SLOTS[0], CACHE_Y, GREEN, 6.8, 16))
    answer("20", GREEN, (CACHE_SLOTS[0], CACHE_Y), READ_Y[2], 6.9, 7.6, 32)
    sc.add(sc.text(ANSWER_X, READ_Y[2] + 24, "Cached", "tiny", anchor="middle", style=sc.shown([(7.6, END)])))
    sc.add(sc.text(LOG_X + 14, 134 + 2 * 26, "repeat read: from the cache", "tiny", style=sc.shown([(7.6, 9.6)])))

    # 4. another first read
    ask("feature-b", READ_Y[3], prov, 9.4, 10.4)
    sc.add(sc.pulse(*prov, BLUE, 10.4, 18))
    cache("feature-b false", 1, 11.1)
    answer("false", BLUE, prov, READ_Y[3], 10.5, 11.4, 44)
    record(2, f'feature-b = false <tspan fill="{BLUE}">provider</tspan>', 11.4)

    # the result
    shown = sc.shown([(12.4, END)])
    sc.add(sc.text(28, 350, "TransactionResult", "tag", GREEN, style=shown))
    sc.add(sc.text(196, 350, "result · flagCount = 3 · overrideCount = 1 · providerEvaluatedKeys = {max-items, feature-b}",
                   "hud", style=shown))
    sc.add(sc.text(28, 372, "every recorded read, with its value, resolution and timestamp", "tiny", style=shown))
    return sc

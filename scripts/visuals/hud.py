"""A tiny toolkit for the docs' animated SVGs: a dark HUD frame, and elements moved by CSS keyframes.

Every animation in a scene shares one loop of `T` seconds. Times are seconds into that loop. An element is drawn
at the origin and placed by `path`, so moving it means listing where it is at which time.
"""

MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

CYAN, PINK, AMBER = "#22d3ee", "#e879f9", "#fbbf24"
GREEN, RED, BLUE = "#34d399", "#f87171", "#60a5fa"
TEXT, DIM, FAINT, EDGE, LINE = "#e2e8f0", "#64748b", "#475569", "#334155", "#1e293b"


class Scene:
    def __init__(self, w: int, h: int, T: float, title: str, hud: str, footer: str, label: str):
        self.w, self.h, self.T = w, h, T
        self.title, self.hud, self.footer, self.label = title, hud, footer, label
        self.css: list[str] = []
        self.body: list[str] = []
        self.uid = 0

    # --- timing -------------------------------------------------------------------------------------------------

    def _pct(self, t: float) -> str:
        return f"{max(0.0, min(100.0, t / self.T * 100)):.3f}%"

    def anim(self, frames: list[tuple[float, str]], ease: str = "linear") -> str:
        """Registers keyframes `(time, declarations)`; returns the style that plays them."""
        self.uid += 1
        name = f"k{self.uid}"
        frames = sorted(frames, key=lambda f: f[0])
        steps = "".join(f"{self._pct(t)}{{{d}}}" for t, d in frames)
        self.css.append(f"@keyframes {name}{{{steps}}}")
        return f"animation:{name} {self.T}s {ease} infinite;"

    def shown(self, windows: list[tuple[float, float]], fade: float = 0.15) -> str:
        """Visible during each `(on, off)` window, fading in and out."""
        frames = [(0.0, "opacity:0"), (self.T, "opacity:0")]
        for on, off in windows:
            if on <= 0:
                frames = [f for f in frames if f[0] != 0.0] + [(0.0, "opacity:1")]
            else:
                frames += [(on, "opacity:0"), (on + fade, "opacity:1")]
            if off >= self.T:
                frames = [f for f in frames if f[0] != self.T] + [(self.T, "opacity:1")]
            else:
                frames += [(off - fade, "opacity:1"), (off, "opacity:0")]
        return self.anim(frames)

    def path(self, points: list[tuple], ease: str = "ease-in-out") -> str:
        """Moves an element drawn at the origin through `(t, x, y[, opacity])` points; holds the ends."""
        pts = sorted((p if len(p) == 4 else (*p, 1.0)) for p in points)
        frames = [(0.0, *pts[0][1:]), *pts, (self.T, *pts[-1][1:])]
        return self.anim(
            [(t, f"transform:translate({x:.1f}px,{y:.1f}px);opacity:{o}") for t, x, y, o in frames], ease
        )

    # --- drawing ------------------------------------------------------------------------------------------------

    def add(self, *parts: str) -> None:
        self.body.extend(parts)

    def text(self, x, y, s, cls="hud", fill=None, anchor="start", style="", weight=None) -> str:
        f = f' fill="{fill}"' if fill else ""
        wgt = f' font-weight="{weight}"' if weight else ""
        st = f' style="{style}"' if style else ""
        return f'<text x="{x}" y="{y}" class="{cls}" text-anchor="{anchor}"{f}{wgt}{st}>{s}</text>'

    def chip(self, label, color, style="", x=0, y=0, w=20) -> str:
        """A glowing input, centred at (x, y)."""
        return (
            f'<g style="{style}"><rect x="{x - w / 2}" y="{y - 10}" width="{w}" height="20" rx="4" fill="{color}" '
            f'fill-opacity="0.18" stroke="{color}" stroke-width="1.5" filter="url(#glow)"/>'
            f'<text x="{x}" y="{y + 4}" class="chip" text-anchor="middle" fill="{color}">{label}</text></g>'
        )

    def shuttle(self, w, color, x=0, y=0, h=30) -> str:
        return (
            f'<rect x="{x}" y="{y - h / 2}" width="{w}" height="{h}" rx="{h / 2}" class="shuttle" '
            f'stroke="{color}" filter="url(#glow)"/>'
        )

    def panel(self, x, y, w, h, fill="url(#panel)", stroke=EDGE) -> str:
        return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{fill}" stroke="{stroke}"/>'

    def rail(self, x1, y, x2, color) -> str:
        return (
            f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" class="rail"/>'
            f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" class="flow" stroke="{color}"/>'
        )

    def bracket(self, x, y, w, label="lane") -> str:
        return (
            f'<path d="M{x + 8} {y - 17} h-8 v34 h8 M{x + w - 8} {y - 17} h8 v34 h-8" class="bracket"/>'
            + self.text(x + w / 2, y + 32, label, "tiny", anchor="middle")
        )

    def pulse(self, x, y, color, at: float, r=12) -> str:
        style = self.anim([
            (0.0, "opacity:0;transform:scale(0.4)"),
            (at, "opacity:0;transform:scale(0.4)"),
            (at + 0.05, "opacity:0.9;transform:scale(0.4)"),
            (at + 0.7, "opacity:0;transform:scale(2.2)"),
            (self.T, "opacity:0;transform:scale(2.2)"),
        ])
        return (
            f'<circle cx="{x}" cy="{y}" r="{r}" fill="none" stroke="{color}" stroke-width="2" class="pulse" '
            f'style="{style}"/>'
        )

    def badge(self, x, y, s, color, style="") -> str:
        w = 9 + len(s) * 7.4
        return (
            f'<g style="{style}"><rect x="{x}" y="{y - 11}" width="{w}" height="18" rx="4" fill="{color}" '
            f'fill-opacity="0.14" stroke="{color}" stroke-opacity="0.6"/>'
            f'<text x="{x + w / 2}" y="{y + 2}" class="badge" text-anchor="middle" fill="{color}">{s}</text></g>'
        )

    # --- output -------------------------------------------------------------------------------------------------

    def svg(self) -> str:
        W, H = self.w, self.h
        return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="{self.label}">
<defs>
  <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
    <feGaussianBlur stdDeviation="2.5" result="blur"/>
    <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
  <pattern id="grid" width="24" height="24" patternUnits="userSpaceOnUse">
    <path d="M24 0H0V24" fill="none" stroke="{LINE}" stroke-width="1"/>
  </pattern>
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#070b16"/><stop offset="1" stop-color="#0b1222"/>
  </linearGradient>
  <linearGradient id="panel" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#1e293b"/><stop offset="1" stop-color="#0f172a"/>
  </linearGradient>
</defs>
<style>
  text {{ font-family: {MONO}; }}
  .title {{ font-size: 15px; font-weight: 700; fill: {TEXT}; letter-spacing: .5px; }}
  .hud {{ font-size: 12px; fill: {DIM}; }}
  .key {{ font-size: 15px; font-weight: 700; fill: {TEXT}; }}
  .mode {{ font-size: 12px; }}
  .tiny {{ font-size: 10px; fill: {FAINT}; letter-spacing: 1px; }}
  .tag {{ font-size: 12px; font-weight: 700; }}
  .chip {{ font-size: 10px; font-weight: 700; }}
  .badge {{ font-size: 10.5px; font-weight: 700; letter-spacing: .5px; }}
  .log {{ font-size: 13px; }}
  .big {{ font-size: 14px; font-weight: 700; fill: {TEXT}; }}
  .bracket {{ fill: none; stroke: {EDGE}; stroke-width: 1.5; }}
  .rail {{ stroke: {LINE}; stroke-width: 3; }}
  .flow {{ stroke-width: 1.5; stroke-dasharray: 2 10; stroke-opacity: .55; animation: flow 1s linear infinite; }}
  .shuttle {{ fill: #0b1222; stroke-width: 1.6; }}
  .pulse {{ transform-box: fill-box; transform-origin: center; }}
  .arrow {{ fill: none; stroke-width: 1.6; }}
  @keyframes flow {{ to {{ stroke-dashoffset: -12; }} }}
  {"".join(self.css)}
</style>
<rect width="{W}" height="{H}" rx="14" fill="url(#bg)"/>
<rect width="{W}" height="{H}" rx="14" fill="url(#grid)" opacity=".55"/>
<rect x=".75" y=".75" width="{W - 1.5}" height="{H - 1.5}" rx="14" fill="none" stroke="#1e3a5f"/>
<text x="28" y="40" class="title">{self.title}</text>
<text x="{W - 28}" y="40" class="hud" text-anchor="end">{self.hud}</text>
<line x1="28" y1="58" x2="{W - 28}" y2="58" stroke="{LINE}"/>
{"".join(self.body)}
<line x1="28" y1="{H - 50}" x2="{W - 28}" y2="{H - 50}" stroke="{LINE}"/>
<text x="28" y="{H - 24}" class="hud">{self.footer}</text>
</svg>
"""

    def write(self, path: str) -> None:
        with open(path, "w") as f:
            f.write(self.svg())

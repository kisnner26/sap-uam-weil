"""Primitivas para dibujar diagramas UML con el aspecto de Enterprise Architect (SVG)."""
from xml.sax.saxutils import escape

FONT = "Arial, Helvetica, sans-serif"
FILL = "#FEFECE"
INK = "#000000"
SOFT = "#FFFFFF"


def tw(text, size=12, bold=False):
    return len(text) * size * (0.58 if bold else 0.53)


class Diagram:
    def __init__(self, kind, name, width, height):
        self.w, self.h = width, height
        self.parts = []
        self.kind, self.name = kind, name

    # --- estructura -----------------------------------------------------
    def svg(self):
        defs = f"""<defs>
<marker id="open" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto"><path d="M1,1 L9,5 L1,9" fill="none" stroke="{INK}" stroke-width="1.2"/></marker>
<marker id="filled" markerWidth="11" markerHeight="10" refX="10" refY="5" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="{INK}"/></marker>
<marker id="tri" markerWidth="12" markerHeight="12" refX="11" refY="6" orient="auto"><path d="M1,1 L11,6 L1,11 z" fill="{SOFT}" stroke="{INK}" stroke-width="1.1"/></marker>
</defs>"""
        saved = self.parts
        self.parts = []
        self.frame(self.kind, self.name)
        body = "\n".join(self.parts + saved)
        self.parts = saved
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
                f'viewBox="0 0 {self.w} {self.h}" font-family="{FONT}" font-size="12">'
                f'<rect width="{self.w}" height="{self.h}" fill="{SOFT}"/>{defs}\n{body}\n</svg>')

    def add(self, s):
        self.parts.append(s)

    def frame(self, kind, name):
        label = f"{kind} {name}"
        tabw = tw(label, 12, True) + 26
        self.add(f'<rect x="8" y="8" width="{self.w - 16}" height="{self.h - 16}" fill="none" stroke="{INK}" stroke-width="1.2"/>')
        self.add(f'<path d="M8,8 H{8 + tabw} V{8 + 14} L{8 + tabw - 10},{8 + 26} H8 Z" fill="{SOFT}" stroke="{INK}" stroke-width="1.2"/>')
        self.add(f'<text x="18" y="26" font-weight="bold">{escape(label)}</text>')

    # --- texto ----------------------------------------------------------
    def text(self, x, y, s, size=12, bold=False, italic=False, anchor="start", fill=INK):
        st = ""
        if bold:
            st += ' font-weight="bold"'
        if italic:
            st += ' font-style="italic"'
        self.add(f'<text x="{x}" y="{y}" font-size="{size}" text-anchor="{anchor}" fill="{fill}"{st}>{escape(s)}</text>')

    def label(self, x, y, s, anchor="middle"):
        w = tw(s) + 6
        lx = x - w / 2 if anchor == "middle" else (x if anchor == "start" else x - w)
        self.add(f'<rect x="{lx:.1f}" y="{y - 12}" width="{w:.1f}" height="16" fill="{SOFT}" opacity="0.92"/>')
        self.text(x, y, s, anchor=anchor)

    # --- elementos ------------------------------------------------------
    def box(self, x, y, w, h, title, stereo=None, body=(), fill=FILL, rx=0, icon=None, dashed=False):
        dash = ' stroke-dasharray="6,4"' if dashed else ""
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{INK}" stroke-width="1.2"{dash}/>')
        cy = y + 20
        if stereo:
            self.text(x + w / 2, cy, f"«{stereo}»", anchor="middle", italic=True)
            cy += 16
        self.text(x + w / 2, cy, title, anchor="middle", bold=True)
        cy += 16
        for line in body:
            self.text(x + w / 2, cy, line, size=11, anchor="middle")
            cy += 14
        if icon == "component":
            ix, iy = x + w - 24, y + 8
            self.add(f'<rect x="{ix}" y="{iy}" width="16" height="12" fill="{fill}" stroke="{INK}"/>')
            self.add(f'<rect x="{ix - 4}" y="{iy + 2}" width="8" height="3" fill="{fill}" stroke="{INK}"/>')
            self.add(f'<rect x="{ix - 4}" y="{iy + 7}" width="8" height="3" fill="{fill}" stroke="{INK}"/>')

    def component(self, x, y, w, h, title, body=(), stereo="component"):
        self.box(x, y, w, h, title, stereo, body, icon="component")

    def node3d(self, x, y, w, h, title, stereo, body=()):
        d = 10
        self.add(f'<path d="M{x},{y} L{x + d},{y - d} H{x + w + d} V{y + h - d} L{x + w},{y + h} H{x} Z" fill="{FILL}" stroke="{INK}" stroke-width="1.2"/>')
        self.add(f'<path d="M{x},{y} H{x + w} L{x + w + d},{y - d} M{x + w},{y} V{y + h}" fill="none" stroke="{INK}" stroke-width="1.2"/>')
        cy = y + 20
        self.text(x + w / 2, cy, f"«{stereo}»", anchor="middle", italic=True)
        self.text(x + w / 2, cy + 16, title, anchor="middle", bold=True)
        cy += 32
        for line in body:
            self.text(x + w / 2, cy, line, size=11, anchor="middle")
            cy += 14

    def actor(self, x, y, name):
        self.add(f'<circle cx="{x}" cy="{y + 9}" r="9" fill="{SOFT}" stroke="{INK}" stroke-width="1.2"/>')
        self.add(f'<path d="M{x},{y + 18} V{y + 44} M{x - 16},{y + 27} H{x + 16} M{x},{y + 44} L{x - 14},{y + 64} M{x},{y + 44} L{x + 14},{y + 64}" fill="none" stroke="{INK}" stroke-width="1.2"/>')
        self.text(x, y + 80, name, anchor="middle", bold=True)

    def ellipse(self, cx, cy, rx, ry, lines, bold=True):
        self.add(f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="{FILL}" stroke="{INK}" stroke-width="1.2"/>')
        base = cy - (len(lines) - 1) * 7 + 4
        for i, s in enumerate(lines):
            self.text(cx, base + i * 14, s, anchor="middle", bold=bold and i == 0, size=11)

    # --- conectores -----------------------------------------------------
    def line(self, pts, head=None, dashed=False, tail=None):
        d = "M" + " L".join(f"{x},{y}" for x, y in pts)
        dash = ' stroke-dasharray="6,4"' if dashed else ""
        m = f' marker-end="url(#{head})"' if head else ""
        ms = f' marker-start="url(#{tail})"' if tail else ""
        self.add(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="1.1"{dash}{m}{ms}/>')

    def lollipop(self, x, y, name, side="up"):
        self.add(f'<circle cx="{x}" cy="{y}" r="7" fill="{SOFT}" stroke="{INK}" stroke-width="1.2"/>')
        self.text(x + 12, y - 8, name, size=11, italic=True)

    def initial(self, x, y):
        self.add(f'<circle cx="{x}" cy="{y}" r="8" fill="{INK}"/>')

    def final(self, x, y):
        self.add(f'<circle cx="{x}" cy="{y}" r="10" fill="{SOFT}" stroke="{INK}" stroke-width="1.4"/>')
        self.add(f'<circle cx="{x}" cy="{y}" r="6" fill="{INK}"/>')

    # --- tablas (modelado de datos de EA) -------------------------------
    def table(self, x, y, w, name, rows):
        rh = 16
        h = 48 + rh * len(rows)
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{FILL}" stroke="{INK}" stroke-width="1.2"/>')
        self.add(f'<line x1="{x}" y1="{y + 38}" x2="{x + w}" y2="{y + 38}" stroke="{INK}"/>')
        self.text(x + w / 2, y + 15, "«table»", anchor="middle", italic=True, size=11)
        self.text(x + w / 2, y + 30, name, anchor="middle", bold=True)
        for i, (key, col, typ) in enumerate(rows):
            ry = y + 38 + 14 + i * rh
            self.text(x + 8, ry, key, size=10, bold=True)
            self.text(x + 34, ry, col, size=11)
            self.text(x + w - 8, ry, typ, size=10, anchor="end", fill="#444")
        return h

    # --- secuencia ------------------------------------------------------
    def lifeline(self, x, y, h_end, title, actor=False):
        w = max(110, tw(title, 12, True) + 20)
        if not actor:
            self.add(f'<rect x="{x - w / 2}" y="{y}" width="{w}" height="30" fill="{FILL}" stroke="{INK}" stroke-width="1.2"/>')
            self.text(x, y + 20, title, anchor="middle", bold=True)
        else:
            self.add(f'<rect x="{x - w / 2}" y="{y}" width="{w}" height="30" fill="{FILL}" stroke="{INK}" stroke-width="1.2"/>')
            self.text(x, y + 20, title, anchor="middle", bold=True)
        self.add(f'<line x1="{x}" y1="{y + 30}" x2="{x}" y2="{h_end}" stroke="{INK}" stroke-dasharray="6,4"/>')

    def activation(self, x, y1, y2):
        self.add(f'<rect x="{x - 5}" y="{y1}" width="10" height="{y2 - y1}" fill="{FILL}" stroke="{INK}"/>')

    def message(self, x1, x2, y, text, ret=False):
        if x1 == x2:
            return
        sx = x1 + (5 if x2 > x1 else -5)
        ex = x2 - (5 if x2 > x1 else -5)
        self.line([(sx, y), (ex, y)], head="open" if ret else "filled", dashed=ret)
        mid = (sx + ex) / 2
        w = tw(text, 11) + 8
        self.add(f'<rect x="{mid - w / 2:.1f}" y="{y - 17}" width="{w:.1f}" height="15" fill="{SOFT}"/>')
        self.text(mid, y - 5, text, anchor="middle", size=11)

    def selfmsg(self, x, y, text):
        self.line([(x + 5, y), (x + 40, y), (x + 40, y + 16), (x + 5, y + 16)], head="filled")
        self.text(x + 46, y + 11, text, size=11)

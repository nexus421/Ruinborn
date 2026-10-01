"""
Mini isometric renderer for placeholder sprites.

Idea: every object is built from simple 3D primitives (boxes, prisms, roofs ...)
and rasterized with a z-buffer using a true 2:1 dimetric projection (camera raised 30 degrees,
rotated 45 degrees). This way all buildings, units and directions automatically
fit each other and the 128x64 tile grid.

Coordinates (3D, "pixel length"):
  x  -> to the bottom right on screen   (world +x, direction "se")
  y  -> to the bottom left on screen    (world +y, direction "sw")
  z  -> up
A tile edge is TILE = 64*sqrt(2) long -> diamond of 128x64 pixels.
"""
import math

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

SS = 4                                  # supersampling factor (antialiasing)
TILE = 64 * math.sqrt(2)                # 3D length of a tile edge
C45 = math.sqrt(0.5)
COS30 = math.sqrt(3) / 2
VIEW = np.array([C45 * COS30, C45 * COS30, 0.5])       # direction to the camera
_L = np.array([0.3, 0.7, 1.1])
LIGHT = _L / np.linalg.norm(_L)                        # direction to the light
AMBIENT, DIFFUSE = 0.52, 0.55
OUTLINE_RGB = (30, 28, 36)


# ---------------------------------------------------------------- Transformations
def T(x=0.0, y=0.0, z=0.0):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def S(sx, sy=None, sz=None):
    sy = sx if sy is None else sy
    sz = sx if sz is None else sz
    return np.diag([sx, sy, sz, 1.0])


def Rz(deg):
    """Yaw: +deg rotates +x towards +y."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    m = np.eye(4)
    m[:2, :2] = [[c, -s], [s, c]]
    return m


def Ry(deg):
    """Pitch: +deg tilts the z axis towards +x (forward)."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    m = np.eye(4)
    m[0, 0], m[0, 2], m[2, 0], m[2, 2] = c, s, -s, c
    return m


def Rx(deg):
    """Roll: +deg tilts the z axis towards -y."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    m = np.eye(4)
    m[1, 1], m[1, 2], m[2, 1], m[2, 2] = c, -s, s, c
    return m


def fwd(deg):
    """Swings a downward hanging limb forward (+x) by deg."""
    return Ry(-deg)


def project(P, ox, oy):
    """3D points (N,3) -> screen (1x pixels, y down)."""
    sx = ox + (P[:, 0] - P[:, 1]) * C45
    sy = oy + (P[:, 0] + P[:, 1]) * C45 * 0.5 - P[:, 2] * COS30
    return sx, sy


def shade(rgb, k):
    return tuple(max(0, min(255, int(round(c * k)))) for c in rgb)


def mix(a, b, t):
    return tuple(int(round(a[i] * (1 - t) + b[i] * t)) for i in range(3))


# ---------------------------------------------------------------- Scene
class Face:
    __slots__ = ("pts", "normal", "color", "part", "two_sided", "emissive", "ground", "casts")

    def __init__(self, pts, normal, color, part, two_sided, emissive, ground, casts):
        self.pts, self.normal, self.color, self.part = pts, normal, color, part
        self.two_sided, self.emissive, self.ground, self.casts = two_sided, emissive, ground, casts


class Scene:
    def __init__(self):
        self.faces = []
        self.stack = [np.eye(4)]
        self._part = 0
        self.overlays = []   # 2D draw functions (ox, oy, draw, scale) for smoke etc.

    # ---- Transform stack
    @property
    def M(self):
        return self.stack[-1]

    def push(self, m):
        scene = self

        class _Ctx:
            def __enter__(self_inner):
                scene.stack.append(scene.M @ m)
                return scene

            def __exit__(self_inner, *a):
                scene.stack.pop()
        return _Ctx()

    def xf(self, pts):
        P = np.asarray(pts, dtype=float)
        P = np.c_[P, np.ones(len(P))] @ self.M.T
        return P[:, :3]

    def new_part(self):
        self._part += 1
        return self._part

    # ---- Basic building block: a flat polygon
    def _add(self, P, color, inside=None, part=None, two_sided=False, emissive=False,
             ground=False, casts=True):
        """P: already transformed points. inside: a point inside the body (the normal points away from it)."""
        n = np.zeros(3)
        for i in range(len(P)):                       # Newell normal
            a, b = P[i], P[(i + 1) % len(P)]
            n += [(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])]
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            return
        n /= ln
        if inside is not None and np.dot(n, P.mean(axis=0) - inside) < 0:
            n = -n
        self.faces.append(Face(P, n, tuple(color), part if part is not None else self.new_part(),
                               two_sided, emissive, ground, casts))

    def poly(self, pts, color, inside=None, **kw):
        P = self.xf(pts)
        ins = self.xf([inside])[0] if inside is not None else None
        self._add(P, color, ins, **kw)

    def quad2(self, pts, color, **kw):
        """Two-sided surface (flag, sign ...)."""
        self.poly(pts, color, two_sided=True, **kw)

    # ---- Convex bodies from point lists
    def solid(self, faces_pts, colors, part=None, **kw):
        part = part or self.new_part()
        allp = self.xf([p for f in faces_pts for p in f])
        c = allp.mean(axis=0)
        for fp, col in zip(faces_pts, colors):
            self._add(self.xf(fp), col, c, part=part, **kw)
        return part

    def box(self, x0, y0, z0, x1, y1, z1, color, top=None, side=None, **kw):
        """Box. color = side walls, top = lid (default: color)."""
        top = top or color
        side = side or color
        v = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
             (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
        f = [[v[0], v[1], v[2], v[3]], [v[4], v[5], v[6], v[7]],
             [v[1], v[2], v[6], v[5]], [v[0], v[3], v[7], v[4]],
             [v[3], v[2], v[6], v[7]], [v[0], v[1], v[5], v[4]]]
        cols = [color, top, color, side, color, side]
        return self.solid(f, cols, **kw)

    def beam(self, p0, p1, w, h, color, **kw):
        """Beam between two points (local coordinates), cross section w x h."""
        p0, p1 = np.array(p0, float), np.array(p1, float)
        d = p1 - p0
        L = np.linalg.norm(d)
        u = d / L
        ref = np.array([0, 0, 1.0]) if abs(u[2]) < 0.9 else np.array([1.0, 0, 0])
        a = np.cross(u, ref)
        a /= np.linalg.norm(a)
        b = np.cross(u, a)
        corners = []
        for t in (0, 1):
            for (sa, sb) in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                corners.append(p0 + d * t + a * sa * w / 2 + b * sb * h / 2)
        v = corners
        f = [[v[0], v[1], v[2], v[3]], [v[4], v[5], v[6], v[7]],
             [v[0], v[1], v[5], v[4]], [v[1], v[2], v[6], v[5]],
             [v[2], v[3], v[7], v[6]], [v[3], v[0], v[4], v[7]]]
        return self.solid(f, [color] * 6, **kw)

    def prism(self, cx, cy, z0, z1, r, color, n=10, top=None, bottom=None, r_top=None, rot=0.0, **kw):
        """Vertical n-sided prism (cylinder) or truncated cone (r_top)."""
        top = top or color
        bottom = bottom or color
        rt = r if r_top is None else r_top
        ang = [math.radians(rot) + 2 * math.pi * i / n for i in range(n)]
        lo = [(cx + r * math.cos(a), cy + r * math.sin(a), z0) for a in ang]
        hi = [(cx + rt * math.cos(a), cy + rt * math.sin(a), z1) for a in ang]
        faces = [lo[::-1]] + ([hi] if rt > 0.01 else [])
        cols = [bottom] + ([top] if rt > 0.01 else [])
        for i in range(n):
            j = (i + 1) % n
            if rt > 0.01:
                faces.append([lo[i], lo[j], hi[j], hi[i]])
            else:
                faces.append([lo[i], lo[j], (cx, cy, z1)])
            cols.append(color)
        return self.solid(faces, cols, **kw)

    def cone(self, cx, cy, z0, z1, r, color, n=10, rot=0.0, **kw):
        return self.prism(cx, cy, z0, z1, r, color, n=n, r_top=0, rot=rot, **kw)

    def sphere(self, cx, cy, cz, r, color, n=10, rings=5, z_scale=1.0, **kw):
        pts = []
        for i in range(rings + 1):
            th = math.pi * i / rings
            row = []
            for j in range(n):
                ph = 2 * math.pi * j / n
                row.append((cx + r * math.sin(th) * math.cos(ph), cy + r * math.sin(th) * math.sin(ph),
                            cz + r * z_scale * math.cos(th)))
            pts.append(row)
        faces = []
        for i in range(rings):
            for j in range(n):
                k = (j + 1) % n
                if i == 0:
                    faces.append([pts[0][0], pts[1][j], pts[1][k]])
                elif i == rings - 1:
                    faces.append([pts[i][j], pts[i + 1][0], pts[i][k]])
                else:
                    faces.append([pts[i][j], pts[i + 1][j], pts[i + 1][k], pts[i][k]])
        return self.solid(faces, [color] * len(faces), **kw)

    def dome(self, cx, cy, z0, r, color, n=12, rings=4, **kw):
        pts = []
        for i in range(rings + 1):
            th = (math.pi / 2) * i / rings
            pts.append([(cx + r * math.cos(th) * math.cos(2 * math.pi * j / n),
                         cy + r * math.cos(th) * math.sin(2 * math.pi * j / n),
                         z0 + r * math.sin(th)) for j in range(n)])
        faces, cols = [pts[0][::-1]], [color]
        for i in range(rings):
            for j in range(n):
                k = (j + 1) % n
                if i == rings - 1:
                    faces.append([pts[i][j], pts[i][k], (cx, cy, z0 + r)])
                else:
                    faces.append([pts[i][j], pts[i][k], pts[i + 1][k], pts[i + 1][j]])
                cols.append(color)
        return self.solid(faces, cols, **kw)

    def gable(self, x0, y0, x1, y1, z0, h, color, gable_color=None, ridge="x", **kw):
        """Gable roof. ridge = direction of the ridge ('x' or 'y')."""
        gc = gable_color or color
        if ridge == "x":
            ym = (y0 + y1) / 2
            a, b, c, d = (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)
            r0, r1 = (x0, ym, z0 + h), (x1, ym, z0 + h)
            faces = [[a, b, r1, r0], [d, c, r1, r0], [a, d, r0], [b, c, r1], [a, b, c, d]]
        else:
            xm = (x0 + x1) / 2
            a, b, c, d = (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)
            r0, r1 = (xm, y0, z0 + h), (xm, y1, z0 + h)
            faces = [[a, d, r1, r0], [b, c, r1, r0], [a, b, r0], [d, c, r1], [a, b, c, d]]
        return self.solid(faces, [color, color, gc, gc, gc], **kw)

    def halfcyl(self, x0, x1, cy, z0, r, color, end_color=None, n=10, **kw):
        """Half cylinder (corrugated iron hall) along x."""
        ec = end_color or color
        arc = [(cy + r * math.cos(math.pi * i / n), z0 + r * math.sin(math.pi * i / n)) for i in range(n + 1)]
        faces, cols = [], []
        for i in range(n):
            (ya, za), (yb, zb) = arc[i], arc[i + 1]
            faces.append([(x0, ya, za), (x1, ya, za), (x1, yb, zb), (x0, yb, zb)])
            cols.append(color)
        faces.append([(x0, y, z) for y, z in arc])
        faces.append([(x1, y, z) for y, z in arc])
        faces.append([(x0, cy - r, z0), (x1, cy - r, z0), (x1, cy + r, z0), (x0, cy + r, z0)])
        cols += [ec, ec, ec]
        return self.solid(faces, cols, **kw)

    # ---- Decals (windows, doors) on flat walls
    def decal_x(self, x, y0, y1, z0, z1, color, off=0.5, **kw):
        """Rectangle on a wall at x (visible from +x)."""
        self.poly([(x + off, y0, z0), (x + off, y1, z0), (x + off, y1, z1), (x + off, y0, z1)],
                  color, inside=(x - 1, (y0 + y1) / 2, (z0 + z1) / 2), casts=False, **kw)

    def decal_y(self, y, x0, x1, z0, z1, color, off=0.5, **kw):
        """Rectangle on a wall at y (visible from +y)."""
        self.poly([(x0, y + off, z0), (x1, y + off, z0), (x1, y + off, z1), (x0, y + off, z1)],
                  color, inside=((x0 + x1) / 2, y - 1, (z0 + z1) / 2), casts=False, **kw)

    def decal_z(self, z, pts_xy, color, off=0.4, **kw):
        """Polygon on a roof surface at height z (visible from above)."""
        c = np.mean(pts_xy, axis=0)
        self.poly([(x, y, z + off) for x, y in pts_xy], color, inside=(c[0], c[1], z - 1), casts=False, **kw)

    def overlay(self, fn):
        """fn(draw, to_px) draws 2D on top of the image. to_px((x,y,z)) -> supersample pixel."""
        self.overlays.append((fn, self.M.copy()))


# ---------------------------------------------------------------- Rasterizer
def _fan(sx, sy, d):
    """Fan triangulation from the centroid (also works for star-shaped polygons)."""
    cx, cy, cd = sx.mean(), sy.mean(), d.mean()
    n = len(sx)
    for i in range(n):
        j = (i + 1) % n
        yield (cx, cy, cd, sx[i], sy[i], d[i], sx[j], sy[j], d[j])


def render(scene, W, H, ox, oy, outline_parts=True, silhouette=True, ground_shadow=True,
           under=None, crease=0.62):
    """
    Renders the scene to a W x H image (1x). (ox, oy) = image position of the 3D origin.
    under: optional function(draw, s) for ground shadows of units (below the figure).
    Returns: PIL RGBA image.
    """
    Ws, Hs = W * SS, H * SS
    zbuf = np.full((Hs, Ws), -np.inf, np.float32)
    col = np.zeros((Hs, Ws, 3), np.float32)
    pid = np.full((Hs, Ws), -1, np.int32)
    gnd = np.zeros((Hs, Ws), bool)

    for f in scene.faces:
        n = f.normal
        vn = float(n @ VIEW)
        if vn <= 1e-4:
            if not f.two_sided:
                continue
            n = -n
        k = 1.0 if f.emissive else AMBIENT + DIFFUSE * max(0.0, float(n @ LIGHT))
        c = np.array(shade(f.color, k), np.float32)
        sx, sy = project(f.pts, ox, oy)
        sx, sy = sx * SS, sy * SS
        d = f.pts @ VIEW
        for ax, ay, ad, bx, by, bd, cx, cy, cd in _fan(sx, sy, d):
            area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if abs(area) < 1e-9:
                continue
            x0 = max(int(math.floor(min(ax, bx, cx))), 0)
            x1 = min(int(math.ceil(max(ax, bx, cx))), Ws - 1)
            y0 = max(int(math.floor(min(ay, by, cy))), 0)
            y1 = min(int(math.ceil(max(ay, by, cy))), Hs - 1)
            if x0 > x1 or y0 > y1:
                continue
            X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            w0 = ((bx - X) * (cy - Y) - (by - Y) * (cx - X)) / area
            w1 = ((cx - X) * (ay - Y) - (cy - Y) * (ax - X)) / area
            w2 = 1.0 - w0 - w1
            eps = -1e-6
            inside = (w0 >= eps) & (w1 >= eps) & (w2 >= eps)
            depth = w0 * ad + w1 * bd + w2 * cd
            zs = zbuf[y0:y1 + 1, x0:x1 + 1]
            m = inside & (depth > zs)
            if not m.any():
                continue
            zs[m] = depth[m]
            col[y0:y1 + 1, x0:x1 + 1][m] = c
            pid[y0:y1 + 1, x0:x1 + 1][m] = f.part
            gnd[y0:y1 + 1, x0:x1 + 1][m] = f.ground

    covered = pid >= 0

    # Outlines at edges where a nearer part covers a farther one
    if outline_parts:
        r = 2
        edge = np.zeros_like(covered)
        zp = np.pad(zbuf, r, constant_values=-np.inf)
        pp = np.pad(pid, r, constant_values=-1)
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if (dx or dy) and dx * dx + dy * dy <= r * r:
                    zn = zp[r + dy:r + dy + Hs, r + dx:r + dx + Ws]
                    pn = pp[r + dy:r + dy + Hs, r + dx:r + dx + Ws]
                    edge |= (pn >= 0) & (pn != pid) & (zn > zbuf + 0.4)
        col[edge & covered] *= crease

    # Drop shadows on floor plates
    if ground_shadow and gnd.any():
        sh = Image.new("L", (Ws, Hs), 0)
        dr = ImageDraw.Draw(sh)
        for f in scene.faces:
            if f.ground or not f.casts or f.emissive:
                continue
            P = f.pts.copy()
            t = P[:, 2] / LIGHT[2]
            P = P - np.outer(t, LIGHT)
            sx, sy = project(P, ox, oy)
            dr.polygon(list(zip(sx * SS, sy * SS)), fill=255)
        shm = (np.asarray(sh) > 0) & gnd
        col[shm] *= 0.70

    obj = np.dstack([col, covered.astype(np.float32) * 255])

    def to_px_factory(M):
        def to_px(p):
            P = (np.array([[*p, 1.0]]) @ M.T)[:, :3]
            sx, sy = project(P, ox, oy)
            return float(sx[0] * SS), float(sy[0] * SS)
        return to_px

    # Layers: shadow (below) -> object -> overlays (smoke, muzzle flash)
    img = np.zeros((Hs, Ws, 4), np.float32)
    if under is not None:
        u = Image.new("RGBA", (Ws, Hs), (0, 0, 0, 0))
        under(ImageDraw.Draw(u), lambda p: to_px_factory(np.eye(4))(p))
        img = _over(np.asarray(u, np.float32), img)
    img = _over(obj, img)
    struct = covered & ~gnd
    if scene.overlays:
        o = Image.new("RGBA", (Ws, Hs), (0, 0, 0, 0))
        dr = ImageDraw.Draw(o)
        for fn, M in scene.overlays:
            fn(dr, to_px_factory(M))
        img = _over(np.asarray(o, np.float32), img)

    out = _downsample(img)
    if silhouette and struct.any():
        a_struct = struct.reshape(H, SS, W, SS).mean(axis=(1, 3))
        cross = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], bool)
        dil = ndimage.grey_dilation(a_struct, footprint=cross)
        oa = np.clip(dil - a_struct, 0, 1) * 0.85
        ol = np.zeros_like(out)
        ol[..., :3] = OUTLINE_RGB
        ol[..., 3] = oa * 255
        out = _over(ol, out)
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8), "RGBA")


def _over(fg, bg):
    """Porter-Duff 'over' for straight (not premultiplied) RGBA float arrays 0..255."""
    fa, ba = fg[..., 3:4] / 255.0, bg[..., 3:4] / 255.0
    oa = fa + ba * (1 - fa)
    rgb = np.where(oa > 0, (fg[..., :3] * fa + bg[..., :3] * ba * (1 - fa)) / np.maximum(oa, 1e-6), 0)
    return np.concatenate([rgb, oa * 255], axis=-1)


def _downsample(img):
    """SSxSS block mean with premultiplied alpha (no dark edges)."""
    Hs, Ws, _ = img.shape
    H, W = Hs // SS, Ws // SS
    a = img[..., 3:4] / 255.0
    prem = img[..., :3] * a
    prem = prem.reshape(H, SS, W, SS, 3).mean(axis=(1, 3))
    am = a.reshape(H, SS, W, SS, 1).mean(axis=(1, 3))
    rgb = np.where(am > 0, prem / np.maximum(am, 1e-6), 0)
    return np.concatenate([rgb, am * 255], axis=-1)


# ---------------------------------------------------------------- 2D helpers
def ss_canvas(W, H):
    im = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def ss_finish(im):
    arr = np.asarray(im, np.float32)
    out = _downsample(arr)
    return Image.fromarray(np.clip(np.round(out), 0, 255).astype(np.uint8), "RGBA")


def alpha_bbox(images):
    """Common bounding box (x0, y0, x1, y1) of all images (visible pixels only)."""
    box = None
    for im in images:
        b = im.getchannel("A").point(lambda v: 255 if v > 3 else 0).getbbox()
        if b is None:
            continue
        box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    return box

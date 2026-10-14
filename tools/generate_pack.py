#!/usr/bin/env python3
"""Génère le resource pack LoupGarouMC : cartes de rôle, animation de retournement,
fonds de menus custom et modèles d'items. Lancer :  python3 tools/generate_pack.py"""
import json, math, os, random, shutil, zipfile, hashlib
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "resourcepack")
NS = "lgt"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
FONT_SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# Doit rester dans le même ordre que l'enum Role.java
ROLES = [
    ("loup_garou", "LOUP-GAROU", "LES LOUPS", (192, 57, 43)),
    ("villageois", "VILLAGEOIS", "LE VILLAGE", (212, 163, 90)),
    ("voyante", "VOYANTE", "LE VILLAGE", (155, 89, 182)),
    ("sorciere", "SORCIÈRE", "LE VILLAGE", (39, 174, 96)),
    ("chasseur", "CHASSEUR", "LE VILLAGE", (230, 126, 34)),
    ("cupidon", "CUPIDON", "LE VILLAGE", (255, 111, 168)),
    ("salvateur", "SALVATEUR", "LE VILLAGE", (52, 152, 219)),
]
# Doit rester dans le même ordre que l'enum MenuTheme.java
THEMES = [
    ("vote", "VOTE DU VILLAGE", (201, 151, 62)),
    ("wolves", "LA MEUTE CHOISIT", (176, 40, 40)),
    ("seer", "VISION DE LA VOYANTE", (142, 80, 190)),
    ("witch", "POTIONS DE LA SORCIÈRE", (46, 160, 90)),
    ("cupid", "FLÈCHE DE CUPIDON", (232, 96, 150)),
    ("hunter", "DERNIER TIR DU CHASSEUR", (214, 110, 30)),
    ("guard", "PROTECTION DU SALVATEUR", (52, 140, 210)),
    ("admin", "COMPOSITION DE LA PARTIE", (210, 170, 80)),
]

S = 2                      # sur-échantillonnage pour l'anti-aliasing
CW, CH = 128, 192          # taille finale d'une carte


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def font(path, size):
    return ImageFont.truetype(path, size)


def centered_text(d, cx, y, txt, f, fill, stroke=0, stroke_fill=(0, 0, 0)):
    w = d.textlength(txt, font=f)
    d.text((cx - w / 2, y), txt, font=f, fill=fill, stroke_width=stroke, stroke_fill=stroke_fill)


def fit_font(d, txt, path, size, maxw):
    while size > 8:
        f = font(path, size)
        if d.textlength(txt, font=f) <= maxw:
            return f
        size -= 1
    return font(path, size)


def card_frame(color, dark=(18, 16, 28)):
    W, H = CW * S, CH * S
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bg = Image.new("RGBA", (W, H))
    px = bg.load()
    for y in range(H):
        t = y / H
        c = mix(mix(color, dark, 0.55), dark, t * 0.8)
        for x in range(W):
            px[x, y] = c + (255,)
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, W - 1, H - 1), radius=16 * S, fill=255)
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)
    gold = (226, 190, 110)
    d.rounded_rectangle((2 * S, 2 * S, W - 3 * S, H - 3 * S), radius=14 * S, outline=gold, width=3 * S)
    d.rounded_rectangle((7 * S, 7 * S, W - 8 * S, H - 8 * S), radius=10 * S, outline=gold + (120,), width=1 * S)
    for cx, cy in ((12, 12), (CW - 12, 12), (12, CH - 12), (CW - 12, CH - 12)):
        d.regular_polygon((cx * S, cy * S, 3 * S), 4, fill=gold)
    return img, d


def draw_emblem(d, role, cx, cy, r, color):
    gold = (240, 210, 140)
    # halo
    for i in range(6, 0, -1):
        a = int(18 * i)
        d.ellipse((cx - r - i * 3, cy - r - i * 3, cx + r + i * 3, cy + r + i * 3), fill=color + (max(0, 40 - a // 3),))
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(24, 20, 36), outline=gold, width=3 * S)
    k = r / 60.0
    P = lambda pts: [(cx + x * k, cy + y * k) for x, y in pts]
    if role == "loup_garou":
        d.ellipse(P([(-20, -48), (40, 12)]), fill=(235, 230, 200))          # lune
        d.polygon(P([(-38, -10), (-30, -46), (-14, -22), (14, -22), (30, -46), (38, -10),
                     (30, 18), (12, 30), (0, 44), (-12, 30), (-30, 18)]), fill=(70, 70, 82))
        d.polygon(P([(-30, -40), (-22, -20), (-32, -14)]), fill=(120, 60, 60))
        d.polygon(P([(30, -40), (22, -20), (32, -14)]), fill=(120, 60, 60))
        d.polygon(P([(-24, -2), (-8, 2), (-20, 8)]), fill=(255, 40, 30))
        d.polygon(P([(24, -2), (8, 2), (20, 8)]), fill=(255, 40, 30))
        d.polygon(P([(-6, 26), (6, 26), (0, 36)]), fill=(20, 20, 20))
        d.polygon(P([(-10, 30), (-6, 40), (-2, 32)]), fill=(255, 255, 255))
        d.polygon(P([(10, 30), (6, 40), (2, 32)]), fill=(255, 255, 255))
    elif role == "villageois":
        d.polygon(P([(-36, -4), (0, -38), (36, -4)]), fill=(170, 60, 40))
        d.rectangle(P([(-28, -4), (28, 36)]), fill=(222, 196, 150))
        d.rectangle(P([(-8, 10), (8, 36)]), fill=(110, 70, 40))
        d.rectangle(P([(-22, 4), (-12, 14)]), fill=(255, 220, 120))
        d.rectangle(P([(12, 4), (22, 14)]), fill=(255, 220, 120))
        d.rectangle(P([(16, -34), (24, -14)]), fill=(120, 60, 40))
    elif role == "voyante":
        d.ellipse(P([(-36, -40), (36, 32)]), fill=(120, 80, 190), outline=(220, 190, 255), width=2 * S)
        d.ellipse(P([(-26, -30), (-8, -18)]), fill=(230, 210, 255))
        d.polygon(P([(-26, -4), (0, -18), (26, -4), (0, 10)]), fill=(250, 245, 255))
        d.ellipse(P([(-8, -12), (8, 4)]), fill=(70, 30, 120))
        d.ellipse(P([(-3, -7), (3, -1)]), fill=(10, 0, 20))
        d.polygon(P([(-30, 34), (30, 34), (22, 46), (-22, 46)]), fill=(150, 110, 60))
    elif role == "sorciere":
        for ox, col in ((-20, (60, 220, 110)), (20, (220, 50, 60))):
            d.ellipse(P([(ox - 18, -6), (ox + 18, 32)]), fill=col, outline=(240, 240, 240), width=2 * S)
            d.rectangle(P([(ox - 6, -24), (ox + 6, -4)]), fill=(220, 230, 240))
            d.rectangle(P([(ox - 8, -30), (ox + 8, -24)]), fill=(140, 90, 50))
            d.ellipse(P([(ox - 10, 2), (ox - 2, 10)]), fill=(255, 255, 255, 180))
    elif role == "chasseur":
        d.ellipse(P([(-38, -38), (38, 38)]), outline=(240, 120, 40), width=4 * S)
        d.ellipse(P([(-20, -20), (20, 20)]), outline=(240, 120, 40), width=3 * S)
        for a, b in (((-46, 0), (-26, 0)), ((26, 0), (46, 0)), ((0, -46), (0, -26)), ((0, 26), (0, 46))):
            d.line(P([a, b]), fill=(240, 220, 200), width=4 * S)
        d.ellipse(P([(-4, -4), (4, 4)]), fill=(255, 60, 40))
    elif role == "cupidon":
        hx = [(0, 34), (-36, 0), (-38, -18), (-28, -32), (-12, -32), (0, -18),
              (12, -32), (28, -32), (38, -18), (36, 0)]
        d.polygon(P(hx), fill=(240, 70, 120))
        d.line(P([(-46, 30), (44, -32)]), fill=(240, 210, 140), width=4 * S)
        d.polygon(P([(44, -32), (30, -30), (38, -20)]), fill=(240, 210, 140))
        d.polygon(P([(-46, 30), (-40, 18), (-34, 26)]), fill=(255, 255, 255))
    elif role == "salvateur":
        sh = [(0, -40), (34, -28), (30, 10), (0, 42), (-30, 10), (-34, -28)]
        d.polygon(P(sh), fill=(52, 120, 200), outline=(230, 240, 255))
        d.line(P([(0, -30), (0, 30)]), fill=(240, 240, 255), width=5 * S)
        d.line(P([(-22, -10), (22, -10)]), fill=(240, 240, 255), width=5 * S)


def make_front(role_id, title, team, color):
    img, d = card_frame(color)
    W = CW * S
    gold = (240, 214, 150)
    f = fit_font(d, title, FONT_BOLD, 17 * S, (CW - 30) * S)
    d.rounded_rectangle((14 * S, 14 * S, W - 15 * S, 36 * S), radius=5 * S, fill=(15, 12, 22, 220), outline=gold)
    centered_text(d, W / 2, 16 * S, title, f, (255, 244, 220), stroke=1 * S)
    draw_emblem(d, role_id, W / 2, 98 * S, 44 * S, color)
    f2 = font(FONT_SANS, 9 * S)
    d.line((26 * S, 156 * S, W - 27 * S, 156 * S), fill=gold + (160,), width=S)
    centered_text(d, W / 2, 162 * S, team, f2, color if role_id != "villageois" else gold)
    return img.resize((CW, CH), Image.LANCZOS)


def make_back():
    img, d = card_frame((40, 50, 110), (8, 10, 30))
    W, H = CW * S, CH * S
    rnd = random.Random(7)
    for _ in range(70):
        x, y = rnd.randint(14, CW - 14) * S, rnd.randint(14, CH - 14) * S
        s = rnd.choice((1, 1, 1, 2)) * S
        d.ellipse((x - s, y - s, x + s, y + s), fill=(255, 250, 220, rnd.randint(120, 255)))
    cx, cy, r = W / 2, H / 2 - 10 * S, 34 * S
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(245, 236, 200))
    d.ellipse((cx - r + 18 * S, cy - r - 6 * S, cx + r + 18 * S, cy + r - 6 * S), fill=(18, 22, 54))
    # silhouette de loup hurlant
    k = S
    wolf = [(40, 150), (52, 128), (56, 112), (64, 100), (70, 84), (74, 92), (76, 82), (82, 96),
            (80, 108), (86, 122), (92, 150)]
    d.polygon([(x * k, y * k) for x, y in wolf], fill=(10, 10, 20))
    d.rectangle((14 * S, 148 * S, W - 15 * S, 152 * S), fill=(10, 10, 20))
    f = font(FONT_BOLD, 12 * S)
    centered_text(d, W / 2, 160 * S, "LOUP-GAROU", f, (226, 190, 110))
    return img.resize((CW, CH), Image.LANCZOS)


def squash(img, ratio):
    """Retournement : la carte est écrasée horizontalement et COLLÉE À GAUCHE, car Minecraft
    calcule la largeur d'un glyphe jusqu'au dernier pixel opaque (sinon le centrage du titre décale)."""
    w = max(2, int(CW * ratio))
    small = img.resize((w, CH), Image.LANCZOS)
    shade = Image.new("RGBA", small.size, (0, 0, 0, int(110 * (1 - ratio))))
    small = Image.alpha_composite(small, Image.composite(shade, Image.new("RGBA", small.size), small.split()[3]))
    canvas = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    canvas.paste(small, (0, 0))
    return canvas


def slot(d, x, y, accent=None):
    d.rectangle((x, y, x + 17, y + 17), fill=(139, 139, 139))
    d.line((x, y, x + 16, y), fill=(55, 55, 55)); d.line((x, y, x, y + 16), fill=(55, 55, 55))
    d.line((x + 1, y + 17, x + 17, y + 17), fill=(255, 255, 255)); d.line((x + 17, y + 1, x + 17, y + 17), fill=(255, 255, 255))
    if accent:
        d.rectangle((x - 1, y - 1, x + 18, y + 18), outline=accent)


def make_gui(title, color):
    W, H = 176, 222
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    dark = mix(color, (20, 16, 24), 0.78)
    mid = mix(color, (40, 34, 44), 0.55)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=5, fill=dark + (255,), outline=(10, 8, 12))
    d.rounded_rectangle((1, 1, W - 2, H - 2), radius=4, outline=mix(color, (255, 255, 255), 0.35))
    # bandeau de titre
    d.rectangle((4, 3, W - 5, 14), fill=mid)
    d.line((4, 15, W - 5, 15), fill=color)
    f = fit_font(d, title, FONT_SANS, 9, W - 16)
    centered_text(d, W / 2, 3, title, f, (255, 240, 210))
    # zone joueurs (lignes 1-3, colonnes 1-7)
    d.rounded_rectangle((22, 34, W - 23, 92), radius=3, fill=mix(color, (12, 10, 14), 0.86))
    for r in range(1, 4):
        for c in range(1, 8):
            slot(d, 7 + c * 18, 17 + r * 18)
    # case info (ligne 0, colonne 4)
    slot(d, 7 + 4 * 18, 17, accent=(240, 214, 150))
    # boutons (ligne 5, colonnes 2, 4, 6)
    for c in (2, 4, 6):
        slot(d, 7 + c * 18, 17 + 5 * 18, accent=color)
    # Zone de l'inventaire du joueur : recouverte par une scène de village de nuit (opaque),
    # l'inventaire est vidé par le plugin pendant que le menu est ouvert.
    top, bottom = 131, H - 5
    sky = Image.new("RGBA", (W - 10, bottom - top))
    spx = sky.load()
    for y in range(sky.height):
        c = mix(mix(color, (10, 12, 30), 0.82), (4, 4, 10), y / sky.height)
        for x in range(sky.width):
            spx[x, y] = c + (255,)
    smask = Image.new("L", sky.size, 0)
    ImageDraw.Draw(smask).rounded_rectangle((0, 0, sky.width - 1, sky.height - 1), radius=4, fill=255)
    img.paste(sky, (5, top), smask)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((5, top, W - 6, bottom), radius=4, outline=mix(color, (255, 255, 255), 0.25))
    rnd = random.Random(hash(title) & 0xFFFF)
    for _ in range(26):
        x, y = rnd.randint(9, W - 10), rnd.randint(top + 4, top + 50)
        d.point((x, y), fill=(255, 250, 220, rnd.randint(110, 255)))
    # lune
    d.ellipse((W - 40, top + 8, W - 20, top + 28), fill=(240, 232, 200))
    d.ellipse((W - 34, top + 5, W - 14, top + 25), fill=mix(color, (10, 12, 30), 0.82))
    # silhouette du village
    ground = bottom - 16
    sil = (8, 8, 14)
    houses = [(12, 14, 12), (30, 18, 16), (52, 12, 10), (66, 16, 20), (104, 14, 12), (122, 20, 14), (142, 12, 16)]
    for hx, hw, hh in houses:
        d.rectangle((hx, ground - hh, hx + hw, ground), fill=sil)
        d.polygon([(hx - 2, ground - hh), (hx + hw / 2, ground - hh - 8), (hx + hw + 2, ground - hh)], fill=sil)
        if rnd.random() < 0.6:
            wx = hx + hw // 2 - 1
            d.rectangle((wx, ground - hh + 4, wx + 2, ground - hh + 6), fill=(255, 200, 90))
    # clocher
    d.rectangle((86, ground - 30, 96, ground), fill=sil)
    d.polygon([(84, ground - 30), (91, ground - 44), (98, ground - 30)], fill=sil)
    d.rectangle((90, ground - 50, 91, ground - 43), fill=sil)
    d.rectangle((88, ground - 48, 93, ground - 47), fill=sil)
    d.rectangle((5, ground, W - 6, bottom - 1), fill=sil)
    f2 = fit_font(d, HINTS.get(title, ""), FONT_SANS, 8, W - 20)
    centered_text(d, W / 2, ground + 3, HINTS.get(title, ""), f2, mix(color, (255, 255, 255), 0.6))
    return img


ROLE_INFO = {
    "loup_garou": ("Chaque nuit, la meute se réveille et choisit ensemble un villageois à dévorer. "
                   "Le jour, fais-toi passer pour un innocent.",
                   "Éliminer les villageois jusqu'à être aussi nombreux qu'eux."),
    "villageois": ("Aucun pouvoir spécial. Observe, écoute, accuse : ton vote est ton arme.",
                   "Démasquer et éliminer tous les Loups-Garous."),
    "voyante": ("Chaque nuit, choisis un joueur : sa vraie carte se retourne sous tes yeux. "
                "Partage tes visions avec prudence.",
                "Aider le village à éliminer tous les Loups-Garous."),
    "sorciere": ("Tu possèdes une potion de vie (sauver la victime des loups) et une potion de mort "
                 "(empoisonner un joueur). Une seule fois chacune.",
                 "Aider le village à éliminer tous les Loups-Garous."),
    "chasseur": ("Quand tu meurs, de nuit comme de jour, tu tires une dernière balle "
                 "sur le joueur de ton choix.",
                 "Aider le village à éliminer tous les Loups-Garous."),
    "cupidon": ("La première nuit, désigne deux amoureux. Si l'un meurt, l'autre meurt de chagrin.",
                "Aider le village. Les amoureux gagnent s'ils sont les deux derniers."),
    "salvateur": ("Chaque nuit, protège un joueur de l'attaque des loups. "
                  "Jamais deux nuits de suite la même personne.",
                  "Aider le village à éliminer tous les Loups-Garous."),
}


def wrap(d, text, f, maxw):
    lines, cur = [], ""
    for word in text.split():
        test = (cur + " " + word).strip()
        if d.textlength(test, font=f) <= maxw:
            cur = test
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def make_role_sheet(ri, rid, title, team, color):
    """Fiche explicative 176x222 (texture 352x444) : grande carte à gauche, texte à droite."""
    K = 2
    W, H = 176 * K, 222 * K
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    dark = mix(color, (16, 14, 22), 0.82)
    gold = (240, 214, 150)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=6 * K, fill=dark + (255,), outline=(10, 8, 12), width=K)
    d.rounded_rectangle((K, K, W - 1 - K, H - 1 - K), radius=5 * K, outline=mix(color, (255, 255, 255), 0.35), width=K)
    # bandeau
    d.rectangle((4 * K, 3 * K, W - 5 * K, 15 * K), fill=mix(color, (40, 34, 44), 0.55))
    d.line((4 * K, 16 * K, W - 5 * K, 16 * K), fill=color, width=K)
    f = font(FONT_SANS, 9 * K)
    centered_text(d, W / 2, 4 * K, "TA CARTE", f, (255, 240, 210))
    # carte (64x96 GUI px = 128x192 texture px : résolution d'origine)
    front = make_front(rid, title, team, color)
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).rounded_rectangle((8 * K, 22 * K, 76 * K, 122 * K), radius=10 * K, fill=color + (150,))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(6 * K)))
    img.alpha_composite(front, (10 * K, 24 * K))
    d = ImageDraw.Draw(img)
    # colonne de texte
    x0, maxw = 82 * K, (176 - 82 - 7) * K
    ftitle = fit_font(d, title, FONT_BOLD, 11 * K, maxw)
    d.text((x0, 24 * K), title, font=ftitle, fill=(255, 244, 220))
    fteam = font(FONT_SANS, 6 * K)
    camp = "Camp des Loups" if rid == "loup_garou" else "Camp du Village"
    d.text((x0, 38 * K), camp.upper(), font=fteam, fill=color if rid != "villageois" else gold)
    d.line((x0, 47 * K, x0 + maxw, 47 * K), fill=gold + (140,), width=K)
    power, goal = ROLE_INFO[rid]
    flab = font(FONT_SANS, 6 * K)
    ftxt = font("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 6 * K)
    y = 51 * K
    d.text((x0, y), "POUVOIR", font=flab, fill=gold)
    y += 8 * K
    for line in wrap(d, power, ftxt, maxw):
        d.text((x0, y), line, font=ftxt, fill=(230, 226, 236))
        y += 7 * K
    y += 3 * K
    d.text((x0, y), "OBJECTIF", font=flab, fill=gold)
    y += 8 * K
    for line in wrap(d, goal, ftxt, maxw):
        d.text((x0, y), line, font=ftxt, fill=(230, 226, 236))
        y += 7 * K
    # bas : rappel
    d.rounded_rectangle((8 * K, 132 * K, W - 9 * K, 168 * K), radius=4 * K, fill=mix(color, (8, 8, 12), 0.88),
                        outline=mix(color, (255, 255, 255), 0.2), width=K)
    fh = font(FONT_SANS, 7 * K)
    centered_text(d, W / 2, 138 * K, "Clique sur la carte pour la retourner", fh, (255, 240, 210))
    centered_text(d, W / 2, 152 * K, "Ne montre ta carte à personne !", ftxt, mix(color, (255, 255, 255), 0.55))
    # décor nocturne en bas
    rnd = random.Random(ri)
    for _ in range(30):
        x, yy = rnd.randint(8, 168) * K, rnd.randint(174, 196) * K
        d.ellipse((x, yy, x + K, yy + K), fill=(255, 250, 220, rnd.randint(110, 230)))
    sil = (8, 8, 14)
    ground = 208 * K
    for hx, hw, hh in [(12, 14, 12), (30, 18, 16), (52, 12, 10), (104, 14, 12), (122, 20, 14), (142, 12, 16)]:
        d.rectangle((hx * K, ground - hh * K, (hx + hw) * K, ground), fill=sil)
        d.polygon([((hx - 2) * K, ground - hh * K), ((hx + hw / 2) * K, ground - (hh + 8) * K),
                   ((hx + hw + 2) * K, ground - hh * K)], fill=sil)
    d.rectangle((84 * K, ground - 30 * K, 96 * K, ground), fill=sil)
    d.polygon([(82 * K, ground - 30 * K), (90 * K, ground - 44 * K), (98 * K, ground - 30 * K)], fill=sil)
    d.rounded_rectangle((5 * K, ground, W - 6 * K, H - 5 * K), radius=3 * K, fill=sil)
    d.ellipse((150 * K, 176 * K, 162 * K, 188 * K), fill=(240, 232, 200))
    d.ellipse((154 * K, 174 * K, 166 * K, 186 * K), fill=dark)
    return img


def make_admin_gui(title, color):
    """Menu admin : 7 cartes de rôle (ligne 1), mode/raz (ligne 3), boutons (ligne 5), inventaire normal."""
    W, H = 176, 222
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    dark = mix(color, (20, 16, 24), 0.8)
    d.rounded_rectangle((0, 0, W - 1, H - 1), radius=5, fill=dark + (255,), outline=(10, 8, 12))
    d.rounded_rectangle((1, 1, W - 2, H - 2), radius=4, outline=mix(color, (255, 255, 255), 0.35))
    d.rectangle((4, 3, W - 5, 14), fill=mix(color, (40, 34, 44), 0.55))
    d.line((4, 15, W - 5, 15), fill=color)
    centered_text(d, W / 2, 3, title, fit_font(d, title, FONT_SANS, 9, W - 16), (255, 240, 210))
    slot(d, 7 + 4 * 18, 17, accent=(240, 214, 150))
    d.rounded_rectangle((22, 34, W - 23, 54), radius=3, fill=mix(color, (12, 10, 14), 0.86))
    for c in range(1, 8):
        slot(d, 7 + c * 18, 35, accent=color)
    f = font(FONT_SANS, 7)
    centered_text(d, W / 2, 57, "Clic gauche +1   ·   Clic droit -1", f, (240, 225, 190))
    centered_text(d, W / 2, 65, "Places restantes = Villageois", f, mix(color, (255, 255, 255), 0.3))
    slot(d, 7 + 3 * 18, 89, accent=color)
    slot(d, 7 + 5 * 18, 89, accent=color)
    for c, col in ((2, (90, 200, 110)), (4, (220, 70, 70)), (6, (220, 70, 70))):
        slot(d, 7 + c * 18, 107, accent=col)
    d.line((7, 133, W - 8, 133), fill=mix(color, (255, 255, 255), 0.2))
    for r in range(3):
        for c in range(9):
            slot(d, 7 + c * 18, 139 + r * 18)
    for c in range(9):
        slot(d, 7 + c * 18, 197)
    return img


HINTS = {
    "VOTE DU VILLAGE": "Clique sur la tête du suspect",
    "LA MEUTE CHOISIT": "Mettez-vous d'accord sur une victime",
    "VISION DE LA VOYANTE": "Choisis l'âme à sonder",
    "POTIONS DE LA SORCIÈRE": "Vie à gauche · mort au centre",
    "FLÈCHE DE CUPIDON": "Choisis deux amoureux",
    "DERNIER TIR DU CHASSEUR": "Une seule balle. Vise bien.",
    "PROTECTION DU SALVATEUR": "Qui protèges-tu cette nuit ?",
}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)


def main():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    tex = os.path.join(OUT, "assets", NS, "textures")
    os.makedirs(os.path.join(tex, "font"), exist_ok=True)
    os.makedirs(os.path.join(tex, "item"), exist_ok=True)

    # Titre : échelle x4, texte dessiné à y=-10 → ascent = H/2 - 3 pour centrer la carte
    GH = 46
    GA = GH // 2 - 3
    providers = []

    def add(name, img, char):
        img.save(os.path.join(tex, "font", name + ".png"))
        providers.append({"type": "bitmap", "file": f"{NS}:font/{name}.png",
                          "height": GH, "ascent": GA, "chars": [char]})

    back = make_back()
    for i, ratio in enumerate((1.0, 0.62, 0.25)):
        add(f"card_back_{i}", back if ratio == 1 else squash(back, ratio), chr(0xE000 + i))
    previews = [back]
    for ri, (rid, title, team, color) in enumerate(ROLES):
        front = make_front(rid, title, team, color)
        previews.append(front)
        for i, ratio in enumerate((1.0, 0.62, 0.25)):
            add(f"card_{rid}_{i}", front if ratio == 1 else squash(front, ratio), chr(0xE010 + ri * 3 + i))
        # icône d'item (carte miniature dans un carré 32x32)
        icon = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
        icon.paste(front.resize((21, 32), Image.LANCZOS), (5, 0))
        icon.save(os.path.join(tex, "item", f"card_{rid}.png"))
        write_json(os.path.join(OUT, "assets", NS, "models", "item", f"card_{rid}.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/card_{rid}"}})
        # Le porteur voit sa carte, les autres joueurs ne voient que le dos
        write_json(os.path.join(OUT, "assets", NS, "items", f"card_{rid}.json"),
                   {"model": {"type": "minecraft:condition", "property": "minecraft:view_entity",
                              "on_true": {"type": "minecraft:model", "model": f"{NS}:item/card_{rid}"},
                              "on_false": {"type": "minecraft:model", "model": f"{NS}:item/card_back"}}})
    back_icon = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    back_icon.paste(back.resize((21, 32), Image.LANCZOS), (5, 0))
    back_icon.save(os.path.join(tex, "item", "card_back.png"))
    write_json(os.path.join(OUT, "assets", NS, "items", "card_back_item.json"),
               {"model": {"type": "minecraft:model", "model": f"{NS}:item/card_back"}})
    write_json(os.path.join(OUT, "assets", NS, "models", "item", "card_back.json"),
               {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/card_back"}})
    write_json(os.path.join(OUT, "assets", NS, "font", "cards.json"), {"providers": providers})

    # Menus : glyphe 176x222, ascent 13 → aligné sur le coin haut-gauche du menu
    gui_prov = [{"type": "space", "advances": {"": -8}}]
    gui_previews = []
    for ti, (tid, title, color) in enumerate(THEMES):
        g = make_admin_gui(title, color) if tid == "admin" else make_gui(title, color)
        gui_previews.append(g)
        g.save(os.path.join(tex, "font", f"gui_{tid}.png"))
        gui_prov.append({"type": "bitmap", "file": f"{NS}:font/gui_{tid}.png",
                         "height": 222, "ascent": 13, "chars": [chr(0xE100 + ti)]})
    # Fiches de rôle (texture 2x pour un texte net), caractères E120+
    role_previews = []
    for ri, (rid, title, team, color) in enumerate(ROLES):
        g = make_role_sheet(ri, rid, title, team, color)
        role_previews.append(g)
        g.save(os.path.join(tex, "font", f"role_{rid}.png"))
        gui_prov.append({"type": "bitmap", "file": f"{NS}:font/role_{rid}.png",
                         "height": 222, "ascent": 13, "chars": [chr(0xE120 + ri)]})
    write_json(os.path.join(OUT, "assets", NS, "font", "gui.json"), {"providers": gui_prov})

    # Masque le libellé « Inventaire » dessiné par-dessus le fond des menus
    for lang in ("fr_fr", "en_us"):
        write_json(os.path.join(OUT, "assets", "minecraft", "lang", f"{lang}.json"), {"container.inventory": ""})

    write_json(os.path.join(OUT, "pack.mcmeta"), {"pack": {
        "description": "§cLoup-Garou §7— cartes & menus",
        "pack_format": 75, "min_format": 69, "max_format": 999}})
    previews[1].resize((64, 96), Image.LANCZOS).crop((0, 16, 64, 80)).save(os.path.join(OUT, "pack.png"))

    # zip + sha1
    dist = os.path.join(ROOT, "dist")
    os.makedirs(dist, exist_ok=True)
    zpath = os.path.join(dist, "LoupGarouMC-pack.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(OUT):
            for fn in sorted(files):
                full = os.path.join(base, fn)
                z.write(full, os.path.relpath(full, OUT).replace(os.sep, "/"))
    sha1 = hashlib.sha1(open(zpath, "rb").read()).hexdigest()
    with open(os.path.join(dist, "LoupGarouMC-pack.sha1"), "w") as fh:
        fh.write(sha1 + "\n")

    # planches d'aperçu
    sheet = Image.new("RGBA", (len(previews) * (CW + 8) + 8, CH + 16), (30, 30, 36, 255))
    for i, p in enumerate(previews):
        sheet.alpha_composite(p, (8 + i * (CW + 8), 8))
    sheet.save(os.path.join(dist, "apercu_cartes.png"))
    gs = Image.new("RGBA", (4 * 184 + 8, 2 * 230 + 8), (30, 30, 36, 255))
    for i, g in enumerate(gui_previews):
        gs.alpha_composite(g, (8 + (i % 4) * 184, 8 + (i // 4) * 230))
    gs.save(os.path.join(dist, "apercu_menus.png"))
    rs = Image.new("RGBA", (4 * 360 + 8, 2 * 452 + 8), (30, 30, 36, 255))
    for i, g in enumerate(role_previews):
        rs.alpha_composite(g, (8 + (i % 4) * 360, 8 + (i // 4) * 452))
    rs.save(os.path.join(dist, "apercu_fiches_roles.png"))
    print("pack:", zpath, "sha1:", sha1)


if __name__ == "__main__":
    main()

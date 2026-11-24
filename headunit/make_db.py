#!/usr/bin/env python3
"""Writes DrivelogBridge_ALL_Texts.zip and DrivelogBridge_ALL_Images.zip (python3, standard library only).

Texts: one <lang>.txt per HMI language, lines "id=text", ASCII only (the HMI's text database shows nothing else
reliably). 100000 = the language's name, 1 = the menu entry. The ids match src/drivelog_bridge.cpp:
  1000 + state    line 1 (0 starting, 1 no Wi-Fi, 2 waiting for a phone, 3 phone connected, 4 port busy, 5 stopped)
  2000 + n        line 2, phones connected (0..4)
  3000 + n        line 3, car values received (0..15)
Image 57101: the 63x54 icon of the menu entry. Run: python3 make_db.py   (or: make db)
"""
import struct
import zipfile
import zlib

PROPS = 15
LANGS = ["de", "en-UK", "en-US", "es-ES", "es-US", "fr-FR", "fr-CA", "it", "pt", "ru", "pl"]
LANG_NAMES = {"de": "Deutsch", "en-UK": "English (UK)", "en-US": "English (US)", "es-ES": "Espanol",
              "es-US": "Espanol (US)", "fr-FR": "Francais", "fr-CA": "Francais (CA)", "it": "Italiano",
              "pt": "Portugues", "ru": "Russkij", "pl": "Polski"}

EN = {
    "title": "Drivelog",
    "states": ["Starting...", "Car Wi-Fi: off", "Waiting for the phone", "Phone connected", "Port busy: see the log",
               "Stopped"],
    "phones": "Phones connected: {}",
    "values": "Car values: {} of {}",
}
PL = {
    "title": "Drivelog",
    "states": ["Uruchamianie...", "Wi-Fi auta: wylaczone", "Czekam na telefon", "Telefon polaczony",
               "Port zajety: zobacz log", "Zatrzymano"],
    "phones": "Polaczone telefony: {}",
    "values": "Dane auta: {} z {}",
}


def texts(lang):
    t = PL if lang == "pl" else EN
    lines = ["100000=" + LANG_NAMES[lang], "1=" + t["title"]]
    lines += ["%d=%s" % (1000 + i, s) for i, s in enumerate(t["states"])]
    lines += ["%d=%s" % (2000 + n, t["phones"].format(n)) for n in range(0, 5)]
    lines += ["%d=%s" % (3000 + n, t["values"].format(n, PROPS)) for n in range(0, PROPS + 1)]
    text = "\r\n".join(lines) + "\r\n"
    text.encode("ascii")              # fails here when a text is not ASCII
    return text


def png(width, height, pixel):
    """pixel(x, y) -> (r, g, b, a). RGBA, 8 bit."""
    raw = b"".join(b"\x00" + b"".join(bytes(pixel(x, y)) for x in range(width)) for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def icon(x, y):
    """A white road with a dashed centre line, drawn into perspective, on transparent ground."""
    w, h = 63, 54
    t = y / (h - 1)                               # 0 at the top (far), 1 at the bottom (near)
    half = 4 + t * 22                             # half width of the road
    cx = w / 2
    d = abs(x - cx)
    if d > half:
        return (0, 0, 0, 0)
    if d > half - 2.5:
        return (255, 255, 255, 255)               # road edges
    if d < 0.8 + t * 1.2 and int(t * 6) % 2 == 0:
        return (255, 255, 255, 255)               # dashes
    return (255, 255, 255, 70)


def write_zip(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files:
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 23, 0, 0, 0))   # fixed: same bytes on every run
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


def main():
    write_zip("DrivelogBridge_ALL_Texts.zip", [(lang + ".txt", texts(lang).encode("ascii")) for lang in LANGS])
    write_zip("DrivelogBridge_ALL_Images.zip", [("57101.png", png(63, 54, icon))])
    print("DrivelogBridge_ALL_Texts.zip, DrivelogBridge_ALL_Images.zip written")


if __name__ == "__main__":
    main()

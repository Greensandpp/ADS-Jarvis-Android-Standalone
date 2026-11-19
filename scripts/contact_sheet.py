#!/usr/bin/env python3
"""CI helper: contact sheet of real emulator screenshots, printed as base64
so it can be reviewed straight from the job log."""
import base64, glob, io, os, sys
from PIL import Image, ImageDraw
os.chdir(sys.argv[1])
shots = sorted(glob.glob("*.png"))
W, H, cols = 240, 534, 6
rows = max(1, (len(shots) + cols - 1) // cols)
sheet = Image.new("RGB", (cols * W, rows * (H + 16)), "black")
dr = ImageDraw.Draw(sheet)
for i, s in enumerate(shots):
    x, y = (i % cols) * W, (i // cols) * (H + 16)
    sheet.paste(Image.open(s).convert("RGB").resize((W, H)), (x, y + 16))
    dr.text((x + 3, y + 2), s, fill="white")
buf = io.BytesIO(); sheet.save(buf, "JPEG", quality=68)
open("contact-sheet.jpg", "wb").write(buf.getvalue())
print("-----BEGIN CONTACT SHEET-----"); print(base64.b64encode(buf.getvalue()).decode()); print("-----END CONTACT SHEET-----")

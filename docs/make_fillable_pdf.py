"""Adds real form fields to a Chrome-printed sheet (client decisions, pilot test plan): a checkbox on every drawn box,
a text field on every dotted answer line (full-width .ans boxes and the 15px .line boxes inside table cells). Usage: print the HTML with headless Chrome to base.pdf, then python -I docs/make_fillable_pdf.py <base.pdf> <out.pdf> (needs PyMuPDF)"""
import sys
from collections import defaultdict
import fitz

src, out = sys.argv[1], sys.argv[2]
doc = fitz.open(src)
n_cb = n_tx = 0


def add_text_field(page, rect):
    global n_tx
    n_tx += 1
    w = fitz.Widget()
    w.field_type = fitz.PDF_WIDGET_TYPE_TEXT
    w.field_name = f"answer_{n_tx:02d}"
    w.rect = rect
    w.field_flags = fitz.PDF_TX_FIELD_IS_MULTILINE
    w.text_fontsize = 9 if rect.height > 14 else 0  # 0 = auto-size, so a one-line table cell never clips
    w.text_color = (0.12, 0.16, 0.22)
    w.fill_color = (0.94, 0.97, 1.0)
    w.border_width = 0
    page.add_widget(w)


for pno, page in enumerate(doc):
    drawings = page.get_drawings()

    # drawn tick boxes: stroked squares about 7.5 pt
    boxes = [d["rect"] for d in drawings
             if d["type"] == "s" and 7 <= d["rect"].width <= 8.5 and 7 <= d["rect"].height <= 8.5]
    for r in sorted(boxes, key=lambda r: (round(r.y0), r.x0)):
        w = fitz.Widget()
        w.field_type = fitz.PDF_WIDGET_TYPE_CHECKBOX
        n_cb += 1
        w.field_name = f"tick_{n_cb:03d}"
        w.rect = fitz.Rect(r.x0 - 0.5, r.y0 - 0.5, r.x1 + 0.5, r.y1 + 0.5)
        w.border_width = 0
        w.text_color = (0.06, 0.24, 0.43)
        w.field_value = False
        page.add_widget(w)

    # dotted answer lines: rows of tiny filled dots sharing one y, split where a gap separates two table cells
    rows = defaultdict(list)
    for d in drawings:
        r = d["rect"]
        if d["type"] == "f" and r.width < 1.2 and r.height < 1.2:
            rows[round(r.y0, 1)].append(r)
    lines = []  # (y, x0, x1)
    for y, dots in rows.items():
        dots.sort(key=lambda r: r.x0)
        start = prev = dots[0]
        for r in dots[1:] + [None]:
            if r is None or r.x0 - prev.x1 > 4:
                run = [d for d in dots if start.x0 <= d.x0 <= prev.x0]
                if len(run) >= 12:  # a real line (even a narrow table cell), not stray dots
                    lines.append((y, start.x0, prev.x1))
                start = r
            prev = r if r is not None else prev
    words = page.get_text("words")
    for y, x0, x1 in sorted(lines):
        if x1 - x0 < 400:
            # a line inside a table cell is the bottom of a 15px box (CSS .line): the field is exactly that box
            add_text_field(page, fitz.Rect(x0, y - 11, x1, y - 0.5))
            continue
        # a full-width answer box: its label ("Your answer / notes:") sits just above the line, at its left edge
        label = [wd for wd in words if y - 32 <= wd[3] <= y and wd[2] > x0 and wd[0] < x0 + 260]
        top = max(wd[3] for wd in label) + 1 if label else y - 28
        add_text_field(page, fitz.Rect(x0, top, x1, y - 0.5))

doc.save(out, garbage=3, deflate=True)
print(f"checkboxes={n_cb} textfields={n_tx}")

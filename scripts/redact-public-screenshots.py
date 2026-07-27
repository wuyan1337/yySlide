"""Replace real infrastructure details in public yySlide screenshots.

The original deployment archive is intentionally ignored by Git. This script
uses opaque replacement rectangles (not blur) so redacted text cannot be
recovered from the published images.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "website" / "assets"


def find_font(monospaced: bool, bold: bool = False) -> Path:
    windows_fonts = Path("C:/Windows/Fonts")
    candidates = (
        ["consola.ttf", "CascadiaMono.ttf", "DejaVuSansMono.ttf"]
        if monospaced
        else (["segoeuib.ttf", "arialbd.ttf"] if bold else ["segoeui.ttf", "arial.ttf"])
    )
    candidates += ["DejaVuSansMono.ttf" if monospaced else "DejaVuSans.ttf"]
    for candidate in candidates:
        path = windows_fonts / candidate
        if path.exists():
            return path
        try:
            ImageFont.truetype(candidate, 12)
            return Path(candidate)
        except OSError:
            continue
    raise RuntimeError("No suitable local font was found")


UI_FONT = find_font(monospaced=False)
MONO_FONT = find_font(monospaced=True)


def scale_box(box: tuple[int, int, int, int], source: tuple[int, int], target: tuple[int, int]) -> tuple[int, int, int, int]:
    sx = target[0] / source[0]
    sy = target[1] / source[1]
    return tuple(round(value * (sx if index % 2 == 0 else sy)) for index, value in enumerate(box))  # type: ignore[return-value]


def scaled_point(point: tuple[int, int], source: tuple[int, int], target: tuple[int, int]) -> tuple[int, int]:
    return round(point[0] * target[0] / source[0]), round(point[1] * target[1] / source[1])


def font(path: Path, size: int, source_height: int, target_height: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(path), max(10, round(size * target_height / source_height)))


def redact_desktop(path: Path) -> None:
    base = (1800, 1065)
    with Image.open(path) as source:
        image = source.convert("RGBA")
    draw = ImageDraw.Draw(image)
    size = image.size

    ui_background = (249, 251, 252, 255)
    terminal_background = (0, 0, 0, 255)
    terminal_green = (110, 222, 65, 255)
    dark_text = (45, 52, 59, 255)

    replacements = [
        ((64, 94, 306, 126), (72, 99), "203.0.113.10", UI_FONT, 14, dark_text, ui_background),
        ((64, 128, 396, 159), (72, 133), "demo-server", UI_FONT, 14, dark_text, ui_background),
        ((720, 55, 826, 84), (746, 61), "demo", UI_FONT, 13, dark_text, ui_background),
        ((454, 92, 830, 126), (463, 98), "demo@demo-server:~$", MONO_FONT, 15, terminal_green, terminal_background),
        ((454, 727, 790, 761), (460, 735), "/home/demo", UI_FONT, 14, (91, 98, 106, 255), ui_background),
        ((556, 957, 664, 984), (560, 962), "demo-a", UI_FONT, 13, dark_text, ui_background),
        ((556, 987, 664, 1017), (560, 993), "demo", UI_FONT, 13, dark_text, ui_background),
        ((825, 886, 958, 920), (830, 893), "projects", UI_FONT, 13, dark_text, ui_background),
    ]
    for box, point, text, font_path, font_size, text_fill, box_fill in replacements:
        draw.rectangle(scale_box(box, base, size), fill=box_fill)
        draw.text(
            scaled_point(point, base, size),
            text,
            font=font(font_path, font_size, base[1], size[1]),
            fill=text_fill,
        )

    account_box = scale_box((1494, 4, 1619, 37), base, size)
    draw.rounded_rectangle(
        account_box,
        radius=max(6, round(9 * size[1] / base[1])),
        fill=(250, 252, 254, 255),
        outline=(217, 224, 230, 255),
        width=max(1, round(size[1] / base[1])),
    )
    draw.text(
        scaled_point((1538, 10), base, size),
        "demo",
        font=font(UI_FONT, 12, base[1], size[1]),
        fill=dark_text,
    )

    save_image(image, path)


def redact_terminal(path: Path) -> None:
    base = (883, 1800)
    with Image.open(path) as source:
        image = source.convert("RGBA")
    draw = ImageDraw.Draw(image)
    size = image.size
    terminal_background = (26, 27, 47, 255)
    label_background = (67, 69, 100, 255)
    terminal_green = (47, 224, 132, 255)

    draw.rounded_rectangle(scale_box((0, 0, 126, 80), base, size), radius=max(10, round(20 * size[1] / base[1])), fill=label_background)
    draw.text(
        scaled_point((31, 18), base, size),
        "demo",
        font=font(UI_FONT, 34, base[1], size[1]),
        fill=(238, 238, 246, 255),
    )

    replacements = [
        ((0, 488, 300, 520), (0, 491), "192.0.2.10", 27),
        ((0, 1102, 883, 1158), (0, 1108), "Last login: Tue May 26 13:06:11 2026 from 203.0.113.10", 27),
        ((0, 1150, 883, 1206), (0, 1157), "demo@demo-server:~$", 29),
    ]
    for box, point, text, font_size in replacements:
        draw.rectangle(scale_box(box, base, size), fill=terminal_background)
        draw.text(
            scaled_point(point, base, size),
            text,
            font=font(MONO_FONT, font_size, base[1], size[1]),
            fill=terminal_green,
        )

    save_image(image, path)


def update_cloud_endpoint(path: Path) -> None:
    base = (856, 1800)
    with Image.open(path) as source:
        image = source.convert("RGBA")
    draw = ImageDraw.Draw(image)
    size = image.size
    field_background = (229, 231, 244, 255)
    draw.rectangle(scale_box((142, 601, 758, 663), base, size), fill=field_background)
    draw.text(
        scaled_point((148, 606), base, size),
        "https://yyslide.ccwu.cc",
        font=font(UI_FONT, 34, base[1], size[1]),
        fill=(37, 39, 49, 255),
    )
    save_image(image, path)


def save_image(image: Image.Image, path: Path) -> None:
    if path.suffix.lower() == ".webp":
        image.convert("RGB").save(path, "WEBP", quality=90, method=6)
    else:
        image.save(path, "PNG", optimize=True)


def main() -> None:
    for name in ("desktop.png", "desktop.webp"):
        redact_desktop(ASSETS / name)
    for name in ("android-terminal.png", "android-terminal.webp"):
        redact_terminal(ASSETS / name)
    for name in ("android-cloud.png", "android-cloud.webp"):
        update_cloud_endpoint(ASSETS / name)


if __name__ == "__main__":
    main()

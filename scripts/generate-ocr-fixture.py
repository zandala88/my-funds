#!/usr/bin/env python3
"""Optional developer fixture. Requires Pillow; no real account or screenshot is used."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser()
parser.add_argument("--font", default="/System/Library/Fonts/STHeiti Medium.ttc")
args = parser.parse_args()
output = Path(__file__).resolve().parents[1] / "core/src/test/resources/ocr"
output.mkdir(parents=True, exist_ok=True)
image = Image.new("RGB", (1080, 1640), "#f7f8f5")
draw = ImageDraw.Draw(image)
font = ImageFont.truetype(args.font, 42)
small = ImageFont.truetype(args.font, 32)
draw.text((60, 48), "测试样例（非真实持仓）", font=small, fill="#65716a")
draw.text((60, 120), "我的基金", font=font, fill="#203329")
draw.text((60, 210), "总金额（元） 50,000.00", font=font, fill="#203329")
for top in [355, 985]:
    draw.rounded_rectangle((30, top - 35, 1050, top + 535), radius=30, fill="white")
for y, text in [(360, "华夏成长混合A"), (430, "基金代码 000001"), (520, "持有份额 1,234.5678"),
        (600, "平均成本 1.2345"), (680, "持有金额 1,524.16"), (760, "持有收益 +23.45"),
        (995, "招商中证白酒指数A"), (1070, "基金代码 161725"),
        (1160, "持有金额 8,888.88"), (1240, "持有收益 -345.60")]:
    draw.text((60, y), text, font=font, fill="#203329")
image.save(output / "alipay-synthetic.png")

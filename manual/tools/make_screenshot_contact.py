from PIL import Image, ImageDraw
from pathlib import Path
files = sorted(Path('manual/assets/screenshots').glob('*.png'))
columns, cell_w, cell_h = 3, 500, 500
canvas = Image.new('RGB', (columns*cell_w, ((len(files)+columns-1)//columns)*cell_h), '#e6ebf2')
draw = ImageDraw.Draw(canvas)
for i, path in enumerate(files):
    image = Image.open(path).convert('RGB')
    image.thumbnail((cell_w-24, cell_h-48))
    x = (i%columns)*cell_w + (cell_w-image.width)//2
    y = (i//columns)*cell_h + 32
    canvas.paste(image, (x, y))
    draw.text(((i%columns)*cell_w+10, (i//columns)*cell_h+8), path.stem, fill='#142238')
canvas.save('work/screenshot-contact.png')
print(f'{len(files)} captures; contact sheet {canvas.size}')

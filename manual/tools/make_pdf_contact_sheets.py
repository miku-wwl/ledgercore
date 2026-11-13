from pathlib import Path
from PIL import Image, ImageDraw
paths = sorted(Path('work/book-pdf-render').glob('page-*.png'))
print('rendered pages', len(paths), 'first', paths[:2], 'last', paths[-1:] if paths else [])
for sheet_index in range((len(paths)+11)//12):
    batch = paths[sheet_index*12:(sheet_index+1)*12]
    cols, cell_w, cell_h = 3, 300, 430
    sheet = Image.new('RGB', (cols*cell_w, ((len(batch)+cols-1)//cols)*cell_h), '#e5eaf0')
    d = ImageDraw.Draw(sheet)
    for j, path in enumerate(batch):
        im = Image.open(path).convert('RGB')
        im.thumbnail((cell_w-18, cell_h-34))
        x = (j%cols)*cell_w+(cell_w-im.width)//2
        y = (j//cols)*cell_h+24
        sheet.paste(im,(x,y))
        d.text(((j%cols)*cell_w+10,(j//cols)*cell_h+5), path.stem, fill='#17263c')
    out = Path(f'work/pdf-contact-{sheet_index+1}.png')
    sheet.save(out)
    print(out, sheet.size)

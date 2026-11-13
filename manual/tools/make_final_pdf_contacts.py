from pathlib import Path
from PIL import Image,ImageDraw
paths=sorted(Path('work/book-pdf-render-final6').glob('page-*.png'))
for si in range((len(paths)+11)//12):
 batch=paths[si*12:(si+1)*12];cols,cw,ch=3,300,430
 sheet=Image.new('RGB',(cols*cw,((len(batch)+cols-1)//cols)*ch),'#e5eaf0');d=ImageDraw.Draw(sheet)
 for j,path in enumerate(batch):
  im=Image.open(path).convert('RGB');im.thumbnail((cw-18,ch-34));x=(j%cols)*cw+(cw-im.width)//2;y=(j//cols)*ch+24;sheet.paste(im,(x,y));d.text(((j%cols)*cw+10,(j//cols)*ch+5),path.stem,fill='#17263c')
 sheet.save(f'work/pdf-final6-contact-{si+1}.png')
print(f'Created all {len(paths)} final page previews.')

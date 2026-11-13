from pathlib import Path
from pypdf import PdfReader
import hashlib, json
import pdfplumber

root=Path(__file__).resolve().parents[2]
pdf_path=root/'manual/dist/LedgerCore-Enterprise-Java-Fullstack-Engineering.pdf'
reader=PdfReader(str(pdf_path))
texts=[page.extract_text() or '' for page in reader.pages]
all_text='\n'.join(texts)
outline=reader.outline
flat=[]
def visit(items):
    for item in items:
        if isinstance(item,list): visit(item)
        else: flat.append(str(getattr(item,'title','')))
visit(outline)
chapter_marks=[x for x in flat if x.startswith('第 ') and ' 章 ' in x]
annotations=sum(len(page.get('/Annots') or []) for page in reader.pages)
external_links=0
for page in reader.pages:
    for annotation in page.get('/Annots') or []:
        obj=annotation.get_object()
        action=obj.get('/A')
        if action and action.get('/URI'): external_links+=1
font_names=set()
for page in reader.pages:
    resources=page.get('/Resources')
    if resources:
        font_dict=resources.get_object().get('/Font',{})
        for font in font_dict.values():
            font_names.add(str(font.get_object().get('/BaseFont','')))
with pdfplumber.open(str(pdf_path)) as doc:
    out_of_page=[]
    for page_no,page in enumerate(doc.pages,1):
        for ch in page.chars:
            if ch['x0'] < -0.5 or ch['x1'] > page.width+0.5 or ch['top'] < -0.5 or ch['bottom'] > page.height+0.5:
                out_of_page.append({'page':page_no,'char':ch.get('text'),'box':[ch['x0'],ch['top'],ch['x1'],ch['bottom']]})
        if abs(page.width-595.276)>2 or abs(page.height-841.89)>2:
            raise SystemExit(f'Unexpected page size on page {page_no}: {page.width}x{page.height}')
rendered=sorted((root/'work/book-pdf-render-final6').glob('page-*.png'))
results={
    'pdf':str(pdf_path), 'sha256':hashlib.sha256(pdf_path.read_bytes()).hexdigest(),
    'pages':len(reader.pages), 'chapters_in_outline':len(chapter_marks), 'chapter_titles':chapter_marks,
    'bookmark_count':len(flat), 'annotations':annotations, 'external_links':external_links,
    'cjk_characters':sum(1 for c in all_text if '\u4e00'<=c<='\u9fff'),
    'searchable_text_characters':len(all_text), 'text_empty_pages':[i+1 for i,t in enumerate(texts) if not t.strip()],
    'rendered_page_images':len(rendered), 'out_of_page_characters':out_of_page,
    'font_names':sorted(font_names), 'file_bytes':pdf_path.stat().st_size,
}
(root/'work/pdf-qa-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in results.items() if k not in ('chapter_titles','out_of_page_characters')},ensure_ascii=False,indent=2))
if len(reader.pages)!=len(rendered): raise SystemExit('Not every PDF page was rendered')
if len(chapter_marks)!=20: raise SystemExit(f'Expected 20 chapter bookmarks, got {len(chapter_marks)}')
if results['text_empty_pages']: raise SystemExit('Found text-empty pages')
if results['cjk_characters']<10000: raise SystemExit('CJK text extraction below expected content threshold')
if annotations<80 or external_links<35: raise SystemExit('Expected clickable source/reference links were not found')
if out_of_page: raise SystemExit(f'Found {len(out_of_page)} characters outside page bounds')

from pathlib import Path
from pypdf import PdfReader
import json
p=PdfReader('manual/dist/LedgerCore-Enterprise-Java-Fullstack-Engineering.pdf')
info=[]
for n,page in enumerate(p.pages,1):
    t=page.extract_text() or ''
    info.append({'page':n,'chars':len(t),'first':t[:100].replace('\n',' '),'last':t[-100:].replace('\n',' ')})
Path('work/pdf-page-text.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
print('page count',len(info),'blank',[(x['page'],x['chars']) for x in info if x['chars']<20])
for x in info:
    print(x['page'],x['chars'],x['first'][:55])

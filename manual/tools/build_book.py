import argparse
import json
import os
import re
import shutil
import subprocess
from html import escape
from pathlib import Path
from xml.sax.saxutils import quoteattr

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm, inch
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, Flowable, Frame, Image, KeepTogether,
                                NextPageTemplate, PageBreak, PageTemplate, Paragraph,
                                Spacer, Table, TableStyle)
from reportlab.platypus.tableofcontents import TableOfContents
from reportlab.pdfgen.canvas import Canvas

ROOT = Path(__file__).resolve().parents[2]
BOOK = json.loads((ROOT / 'manual/book.json').read_text(encoding='utf-8-sig'))
SOURCE_EXCERPTS = json.loads((ROOT / 'manual/reference/source-excerpts.json').read_text(encoding='utf-8-sig'))
OUT_DEFAULT = ROOT / 'manual/dist/LedgerCore-Enterprise-Java-Fullstack-Engineering.pdf'

NAVY = colors.HexColor('#142238')
INK = colors.HexColor('#1B2A3D')
MUTED = colors.HexColor('#586B82')
BLUE = colors.HexColor('#2D5D9F')
CYAN = colors.HexColor('#73C9D3')
PALE = colors.HexColor('#F3F6FA')
LINE = colors.HexColor('#DDE4EC')
GOLD = colors.HexColor('#B8751C')

FONT_REG = r'C:\Windows\Fonts\msyh.ttc'
FONT_BOLD = r'C:\Windows\Fonts\msyhbd.ttc'
FONT_CODE = r'C:\Windows\Fonts\consola.ttf'

pdfmetrics.registerFont(TTFont('YaHei', FONT_REG, subfontIndex=0))
pdfmetrics.registerFont(TTFont('YaHei-Bold', FONT_BOLD, subfontIndex=0))
pdfmetrics.registerFont(TTFont('ConsolasBook', FONT_CODE))
pdfmetrics.registerFontFamily('YaHei', normal='YaHei', bold='YaHei-Bold', italic='YaHei', boldItalic='YaHei-Bold')

PAGE_W, PAGE_H = A4

styles = {
    'body': ParagraphStyle('Body', fontName='YaHei', fontSize=10.1, leading=16.2, textColor=INK,
                           spaceAfter=7.2, wordWrap='CJK', allowWidows=0, allowOrphans=0),
    'h1': ParagraphStyle('ChapterTitle', fontName='YaHei-Bold', fontSize=21.5, leading=29,
                         textColor=NAVY, spaceBefore=4, spaceAfter=15, keepWithNext=True, wordWrap='CJK'),
    'h2': ParagraphStyle('SectionTitle', fontName='YaHei-Bold', fontSize=13.2, leading=19,
                         textColor=BLUE, spaceBefore=10, spaceAfter=6, keepWithNext=True, wordWrap='CJK'),
    'caption': ParagraphStyle('Caption', fontName='YaHei', fontSize=8.6, leading=12.2, textColor=MUTED,
                              alignment=TA_CENTER, spaceBefore=4, spaceAfter=12, wordWrap='CJK'),
    'source': ParagraphStyle('SourceLabel', fontName='YaHei', fontSize=7.8, leading=10.5, textColor=MUTED,
                             spaceBefore=7, spaceAfter=4, wordWrap='CJK'),
    'small': ParagraphStyle('Small', fontName='YaHei', fontSize=8.8, leading=13, textColor=MUTED,
                            spaceAfter=6, wordWrap='CJK'),
    'toc_title': ParagraphStyle('TOCTitle', fontName='YaHei-Bold', fontSize=24, leading=30,
                                textColor=NAVY, spaceAfter=16, keepWithNext=True),
    'toc': ParagraphStyle('TOCChapter', fontName='YaHei', fontSize=10.2, leading=17,
                          leftIndent=4, firstLineIndent=0, textColor=INK, wordWrap='CJK'),
    'glossary_term': ParagraphStyle('GlossaryTerm', fontName='YaHei-Bold', fontSize=9.2, leading=13,
                                    textColor=BLUE, wordWrap='CJK'),
    'glossary_def': ParagraphStyle('GlossaryDef', fontName='YaHei', fontSize=8.7, leading=12.6,
                                   textColor=INK, wordWrap='CJK'),
    'ref': ParagraphStyle('Reference', fontName='YaHei', fontSize=8.9, leading=13, textColor=INK,
                          spaceAfter=5, wordWrap='CJK'),
}


def inline(text: str) -> str:
    """Small safe Markdown inline parser for bold, code, and links."""
    escaped = escape(text)
    link_pat = re.compile(r'\[([^\]]+)\]\((https?://[^)]+)\)')
    escaped = link_pat.sub(lambda m: f'<link href={quoteattr(m.group(2))} color="#2D5D9F"><u>{m.group(1)}</u></link>', escaped)
    escaped = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', escaped)
    escaped = re.sub(r'`([^`]+)`', r'<font name="ConsolasBook" size="9">\1</font>', escaped)
    escaped = re.sub(r'\*([^*]+)\*', r'<i>\1</i>', escaped)
    return escaped


class Cover(Flowable):
    def __init__(self):
        super().__init__()
        self.width, self.height = PAGE_W, PAGE_H

    def wrap(self, availWidth, availHeight):
        return PAGE_W, PAGE_H

    def draw(self):
        c = self.canv
        c.saveState()
        c.setFillColor(NAVY)
        c.rect(0, 0, PAGE_W, PAGE_H, fill=1, stroke=0)
        c.setFillColor(colors.HexColor('#1B3554'))
        c.circle(PAGE_W+35, 112, 184, fill=1, stroke=0)
        c.setStrokeColor(colors.HexColor('#365776'))
        c.setLineWidth(0.7)
        for i in range(9):
            y = 82 + i*15
            c.line(0, y, PAGE_W, y+80)
        c.setFillColor(CYAN)
        c.roundRect(54, PAGE_H-100, 48, 4, 2, fill=1, stroke=0)
        c.setFont('YaHei-Bold', 11)
        c.setFillColor(CYAN)
        c.drawString(54, PAGE_H-130, 'LEDGERCORE  ·  工程教材')
        title = Paragraph(escape(BOOK['title']), ParagraphStyle('CoverTitle', fontName='YaHei-Bold', fontSize=30,
                         leading=43, textColor=colors.white, wordWrap='CJK'))
        title.wrapOn(c, PAGE_W-108, 180)
        title.drawOn(c, 54, PAGE_H-340)
        subtitle = Paragraph(escape(BOOK['subtitle']), ParagraphStyle('CoverSubtitle', fontName='YaHei', fontSize=14.5,
                             leading=23, textColor=colors.HexColor('#C6D3E3'), wordWrap='CJK'))
        subtitle.wrapOn(c, PAGE_W-108, 130)
        subtitle.drawOn(c, 56, PAGE_H-440)
        c.setFont('ConsolasBook', 10.5)
        c.setFillColor(colors.HexColor('#A9BDCF'))
        c.drawString(56, PAGE_H-512, BOOK['englishTitle'])
        c.setStrokeColor(colors.HexColor('#405770'))
        c.line(56, PAGE_H-555, PAGE_W-56, PAGE_H-555)
        c.setFont('YaHei', 10)
        c.setFillColor(colors.HexColor('#D3DCE6'))
        c.drawString(56, PAGE_H-584, BOOK['edition'])
        c.setFont('ConsolasBook', 8)
        c.setFillColor(colors.HexColor('#91A7BC'))
        c.drawString(56, 54, f"Source {BOOK['sourceCommit']}")
        c.restoreState()


class CodePanel(Flowable):
    def __init__(self, code: str, start_line: int, width: float):
        super().__init__()
        self.code = code
        self.start_line = start_line
        self.width = width
        self.font_size = 6.4
        self.leading = 8.35
        self.pad_x = 8
        self.pad_y = 8
        self.number_w = 27
        self.lines = self._wrap_lines()
        self.height = len(self.lines)*self.leading + self.pad_y*2

    def _wrap_lines(self):
        max_width = self.width - self.pad_x*2 - self.number_w - 4
        result = []
        for original in self.code.splitlines():
            line = original.expandtabs(4)
            if not line:
                result.append(("", None))
                continue
            chunks = []
            remaining = line
            while pdfmetrics.stringWidth(remaining, 'ConsolasBook', self.font_size) > max_width:
                cut = len(remaining)
                while cut > 1 and pdfmetrics.stringWidth(remaining[:cut], 'ConsolasBook', self.font_size) > max_width:
                    cut -= 1
                split_at = remaining.rfind(' ', 0, cut)
                if split_at > 8:
                    cut = split_at + 1
                chunks.append(remaining[:cut].rstrip())
                remaining = '    ' + remaining[cut:].lstrip()
            chunks.append(remaining)
            result.extend((chunk, None) for chunk in chunks)
        return result

    def wrap(self, availWidth, availHeight):
        self.width = min(self.width, availWidth)
        self.lines = self._wrap_lines()
        self.height = len(self.lines)*self.leading + self.pad_y*2
        return self.width, self.height

    def draw(self):
        c = self.canv
        c.saveState()
        c.setFillColor(PALE)
        c.setStrokeColor(LINE)
        c.roundRect(0, 0, self.width, self.height, 5, fill=1, stroke=1)
        y = self.height - self.pad_y - self.font_size
        source_idx = self.start_line
        for text, _ in self.lines:
            if text and not text.startswith('    '):
                c.setFillColor(colors.HexColor('#9AA8B8'))
                c.setFont('ConsolasBook', self.font_size-0.2)
                c.drawRightString(self.pad_x+self.number_w-4, y, str(source_idx))
                source_idx += 1
            c.setFillColor(colors.HexColor('#23344A'))
            c.setFont('ConsolasBook', self.font_size)
            c.drawString(self.pad_x+self.number_w, y, text)
            y -= self.leading
        c.restoreState()


class BookDocTemplate(BaseDocTemplate):
    def __init__(self, filename):
        super().__init__(filename, pagesize=A4, title=BOOK['title'], author='LedgerCore Engineering',
                         subject=BOOK['subtitle'], leftMargin=19*mm, rightMargin=19*mm,
                         topMargin=19*mm, bottomMargin=18*mm, pageCompression=1)
        cover_frame = Frame(0, 0, PAGE_W, PAGE_H, id='cover', leftPadding=0, rightPadding=0,
                           topPadding=0, bottomPadding=0, showBoundary=0)
        body_frame = Frame(self.leftMargin, self.bottomMargin, PAGE_W-self.leftMargin-self.rightMargin,
                           PAGE_H-self.topMargin-self.bottomMargin, id='body', leftPadding=0, rightPadding=0,
                           topPadding=0, bottomPadding=0, showBoundary=0)
        self.addPageTemplates([
            PageTemplate(id='Cover', frames=cover_frame, onPage=self._draw_cover),
            PageTemplate(id='Body', frames=body_frame, onPage=self._draw_body),
        ])
        self.chapter_count = 0

    def _draw_cover(self, canvas, doc):
        canvas.saveState()
        canvas.setTitle(BOOK['title'])
        canvas.setAuthor('LedgerCore Engineering')
        canvas.restoreState()

    def _draw_body(self, canvas, doc):
        page = canvas.getPageNumber()
        canvas.saveState()
        canvas.setStrokeColor(LINE)
        canvas.setLineWidth(0.55)
        canvas.line(doc.leftMargin, PAGE_H-12*mm, PAGE_W-doc.rightMargin, PAGE_H-12*mm)
        canvas.setFont('YaHei', 7.5)
        canvas.setFillColor(MUTED)
        canvas.drawString(doc.leftMargin, PAGE_H-9*mm, '企业级 Java 全栈与金融交易一致性工程')
        canvas.drawRightString(PAGE_W-doc.rightMargin, PAGE_H-9*mm, 'LedgerCore · 第 1 版')
        canvas.line(doc.leftMargin, 13*mm, PAGE_W-doc.rightMargin, 13*mm)
        canvas.setFont('ConsolasBook', 8)
        canvas.drawString(doc.leftMargin, 8.5*mm, BOOK['sourceCommit'][:12])
        canvas.setFont('YaHei', 8)
        canvas.drawRightString(PAGE_W-doc.rightMargin, 8.5*mm, str(page))
        canvas.restoreState()

    def afterFlowable(self, flowable):
        level = getattr(flowable, '_book_level', None)
        if level is None:
            return
        title = flowable.getPlainText()
        key = getattr(flowable, '_book_key', f'part-{self.page}-{len(title)}')
        self.canv.bookmarkPage(key)
        self.canv.addOutlineEntry(title, key, level=0, closed=False)
        self.notify('TOCEntry', (0, title, self.page, key))


def chapter_heading(title: str, key: str) -> Paragraph:
    p = Paragraph(inline(title), styles['h1'])
    p._book_level = 0
    p._book_key = key
    return p


def source_excerpt(key: str, max_width: float):
    item = next((x for x in SOURCE_EXCERPTS['excerpts'] if x['id'] == key), None)
    if item is None:
        raise ValueError(f'Unknown source excerpt: {key}')
    label = (f"固定源码：{escape(item['path'])}:{item['startLine']}–{item['endLine']} · "
             f"<link href={quoteattr(item['url'])} color=\"#2D5D9F\"><u>打开该提交的精确行范围</u></link>")
    panel = CodePanel(item['code'], item['startLine'], max_width)
    return [Paragraph(label, styles['source']), panel, Spacer(1, 9)]


def image_flowable(alt: str, target: str, figure_no: int, max_width: float):
    rel = target.replace('\\', '/')
    if rel.startswith('manual/figures/'):
        img_path = ROOT / 'work/book-build/figures' / (Path(rel).stem + '.png')
        kind = '机制图'
    else:
        img_path = ROOT / rel
        kind = '运行截图'
    if not img_path.exists():
        raise FileNotFoundError(f'Image referenced by chapter is missing: {img_path}')
    img = Image(str(img_path))
    max_height = 5.35*inch if kind == '运行截图' else 4.25*inch
    scale = min(max_width/img.imageWidth, max_height/img.imageHeight, 1.0)
    img.drawWidth = img.imageWidth*scale
    img.drawHeight = img.imageHeight*scale
    img.hAlign = 'CENTER'
    caption = Paragraph(f"图 {figure_no:02d}　{inline(alt)} <font color='#8290A2'>· {kind}</font>", styles['caption'])
    return [Spacer(1, 4), KeepTogether([img, caption])]


def parse_chapter(path: Path, max_width: float, figure_counter: list[int]):
    story = []
    lines = path.read_text(encoding='utf-8-sig').splitlines()
    pending = []

    def flush():
        if pending:
            text = ' '.join(part.strip() for part in pending).strip()
            if text:
                story.append(Paragraph(inline(text), styles['body']))
            pending.clear()

    for raw in lines:
        line = raw.strip()
        if not line:
            flush()
            continue
        if line.startswith('# '):
            flush()
            match = re.match(r'^#\s+(第\s*\d+\s*章\s*.+)$', line)
            title = match.group(1) if match else line[2:].strip()
            key = re.sub(r'[^a-z0-9]+', '-', path.stem.lower()).strip('-')
            story.append(chapter_heading(title, key))
            continue
        if line.startswith('## '):
            flush()
            story.append(Paragraph(inline(line[3:]), styles['h2']))
            continue
        image = re.match(r'^!\[(.*?)\]\((.*?)\)$', line)
        if image:
            flush()
            figure_counter[0] += 1
            story.extend(image_flowable(image.group(1), image.group(2), figure_counter[0], max_width))
            continue
        code = re.fullmatch(r'\{\{code:([a-z0-9_-]+)\}\}', line)
        if code:
            flush()
            story.extend(source_excerpt(code.group(1), max_width))
            continue
        pending.append(line)
    flush()
    return story


def toc_flowable():
    toc = TableOfContents()
    toc.levelStyles = [ParagraphStyle('TOC0', parent=styles['toc'], fontName='YaHei', fontSize=10.1,
                                      leading=17.1, leftIndent=0, firstLineIndent=0, spaceBefore=0,
                                      spaceAfter=0, textColor=INK)]
    toc.dottedLines = 1
    return toc


def glossary_story(max_width):
    terms = [
        ('Aggregate / 聚合', '维护一组相关状态与一致性规则的领域边界。本项目并不把跨账户转账虚构成一个内存聚合。'),
        ('Append-only / 只追加', '历史只增加，不通过 UPDATE 或 DELETE 改写已记录的账本事实。纠错用反向分录。'),
        ('Balance projection / 余额投影', '由 Posting 重算或同步更新、用于快速读取的余额视图；不取代账本历史。'),
        ('CSRF', '跨站请求伪造。浏览器会自动附带 Cookie，因此写请求还需验证 token。'),
        ('Cursor pagination / 游标分页', '以稳定排序键定位下一页，而不是用越来越大的偏移量跳过记录。'),
        ('DDD', '领域驱动设计。以业务语言、边界与不变量组织模型和协作。'),
        ('Double-entry / 复式记账', '同一币种下每个凭证借方总额等于贷方总额的记账方法。'),
        ('Idempotency-Key', '标识一次业务意图的客户端键。相同键、相同请求可恢复已有回执。'),
        ('Isolation / 隔离级别', '数据库事务对并发读写及其可见性的规则集合。LedgerCore 的转账显式选择 READ COMMITTED 并加行锁。'),
        ('Journal entry / 凭证', '把一次经济事件关联在一起的分录集合。'),
        ('MVCC', '多版本并发控制；数据库维护行版本并按快照决定可见性。'),
        ('Optimistic locking / 乐观锁', '通过版本列检测冲突更新；账户实体版本与账本行锁解决不同的问题。'),
        ('Posting / 分录', '把金额、借贷方向和账本账户绑定起来的一行不可变账本记录。'),
        ('Problem Details', 'RFC 9457 定义的 HTTP API 错误响应表示法。'),
        ('Projection reconciliation / 投影对账', '比较权威 Posting 余额与缓存投影，发现二者不一致。'),
        ('READ COMMITTED', 'PostgreSQL 默认隔离级别之一；每条命令使用可见提交快照。'),
        ('Spring Modulith', '为 Spring 单体应用定义和验证业务模块边界的工具集。'),
        ('Transaction receipt / 事务回执', '提交后持久化的稳定结果表示，可由同幂等意图重新取得。'),
        ('Unknown outcome / 结果未知', '服务端可能已提交、客户端却未收到响应的状态；需要原意图核实。'),
        ('UUID lock ordering / UUID 排序锁', '对多行资源按同一全序加锁，降低交叉等待形成死锁的可能。'),
        ('Write model / 写模型', '负责校验并记录业务变更的一组服务和持久化操作。'),
    ]
    rows = []
    for term, definition in terms:
        rows.append([Paragraph(inline(term), styles['glossary_term']),
                     Paragraph(inline(definition), styles['glossary_def'])])
    table = Table(rows, colWidths=[47*mm, max_width-47*mm], repeatRows=0, hAlign='LEFT')
    table.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (0,-1), colors.HexColor('#F0F5FB')),
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('LEFTPADDING', (0,0), (-1,-1), 7), ('RIGHTPADDING', (0,0), (-1,-1), 7),
        ('TOPPADDING', (0,0), (-1,-1), 6), ('BOTTOMPADDING', (0,0), (-1,-1), 6),
        ('LINEBELOW', (0,0), (-1,-1), 0.45, LINE),
    ]))
    return [table]


def references_story():
    file = ROOT / 'manual/reference/REFERENCES.md'
    story = []
    for line in file.read_text(encoding='utf-8-sig').splitlines():
        s = line.strip()
        if not s or s.startswith('#'):
            continue
        if s.startswith('- '):
            s = s[2:]
            s = re.sub(r'<(https?://[^>]+)>', r'[\1](\1)', s)
            story.append(Paragraph('· ' + inline(s), styles['ref']))
    return story


def build_story():
    max_width = PAGE_W - 38*mm
    story = [Cover(), NextPageTemplate('Body'), PageBreak()]
    intro = [
        ('序言', 'A ledger system is a story about durable facts, not just a balance on a screen. This book follows a user intent from React to PostgreSQL and back again. Its central question is practical: when a transfer is competing with another request or a response disappears, what does the system know and what can the user safely do next?'),
        ('范围说明', f"本书解释 LedgerCore 在固定提交 {BOOK['sourceCommit']} 中实际实现的机制。它是模拟 NZD 的教育应用，不连接真实银行，不执行外部支付，也没有生产认证结论。每项经验都附上能支持它的源码、测试、截图或实验边界。"),
    ]
    for title, body in intro:
        key = re.sub(r'[^a-z0-9]+', '-', title.lower())
        story.append(chapter_heading(title, key))
        story.append(Paragraph(inline(body), styles['body']))
    story.append(Spacer(1, 10))
    story.append(Paragraph('阅读方式', styles['h2']))
    story.append(Paragraph('把“数据库已提交”与“浏览器已收到回执”看成两个事实。章节从复式记账走到并发锁、原子事务和同键恢复，然后再讨论浏览器如何呈现确定成功、明确拒绝和结果未知。代码节选都由脚本从固定提交提取，源码链接能直接打开对应行。', styles['body']))
    story.extend([PageBreak(), chapter_heading('目录', 'contents'), toc_flowable(), PageBreak()])
    figure_counter = [0]
    for idx, item in enumerate(BOOK['chapters']):
        chapter_path = ROOT / 'manual' / item['file']
        story.extend(parse_chapter(chapter_path, max_width, figure_counter))
        if idx < len(BOOK['chapters'])-1:
            story.append(PageBreak())
    story.extend([PageBreak(), chapter_heading('附录 A　术语表', 'appendix-glossary'),
                  Paragraph('按本书中的语境解释术语；同一词在其他系统可能有不同边界。', styles['body'])])
    story.extend(glossary_story(max_width))
    story.extend([PageBreak(), chapter_heading('附录 B　固定源码索引', 'appendix-source-map'),
                  Paragraph(f"源码唯一基线：{BOOK['sourceCommit']}。每个摘录含精确路径、起止行和 GitHub 永久链接。提取脚本位于 manual/tools/extract_source.py。", styles['body'])])
    for item in SOURCE_EXCERPTS['excerpts']:
        url = item['url']
        label = f"{item['id']}　{item['path']}:{item['startLine']}–{item['endLine']}"
        story.append(Paragraph(f"· <link href={quoteattr(url)} color=\"#2D5D9F\"><u>{escape(label)}</u></link>", styles['ref']))
    story.extend([PageBreak(), chapter_heading('附录 C　参考资料', 'appendix-references')])
    story.extend(references_story())
    story.extend([PageBreak(), chapter_heading('附录 D　验收与证据范围', 'appendix-acceptance')])
    acceptance = [
        f"教材源码固定于 {BOOK['sourceCommit']}；与本书相关的应用代码不在后续元数据提交中变化。",
        '该提交的 GitHub Actions verify 与 dependency-scan 均通过；运行记录编号 43377985041。',
        '本地后端完整回归在 PostgreSQL 18.7 上为 42 项通过；前端 lint/build、OpenAPI 类型漂移检查与 Chromium 端到端流程通过。',
        '界面截图由本书附带的 Playwright 捕获脚本从本地打包应用、Vite 页面和一次性 PostgreSQL 数据库产生。网络未知场景先确认服务器 HTTP 200，再丢弃浏览器响应。',
        '性能实验为单机、合成数据、单用户 SQL 查询；报告只支持对应数据形状下的查询计划观察，不构成容量或服务时限承诺。',
        '教材包含 22 幅原创 SVG、12 张真实 UI 截图和 9 段固定源码摘录；共 20 章，所有 PDF 页面均完成渲染与视觉复核。',
        '依赖审计记录 0 high、0 critical、4 moderate、2 low；无外部渗透测试、生产负载评估或合规认证结论。',
        '本 PDF 使用 Microsoft YaHei 中文字体嵌入；章节导航、书签、目录页码和源代码链接经构建与 PDF 元数据检查。逐页渲染和视觉审阅结果记录在 manual/reference/EDITORIAL-ACCEPTANCE.md。',
    ]
    for entry in acceptance:
        story.append(Paragraph('· ' + inline(entry), styles['body']))
    return story


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=OUT_DEFAULT)
    args = parser.parse_args()
    output = args.output if args.output.is_absolute() else (ROOT / args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    story = build_story()
    doc = BookDocTemplate(str(output))
    doc.multiBuild(story, maxPasses=5)
    print(f"Built {output}")
    print(f"Chapters: {len(BOOK['chapters'])}; editable SVG: {len(list((ROOT/'manual/figures').glob('*.svg')))}; source excerpts: {len(SOURCE_EXCERPTS['excerpts'])}")

if __name__ == '__main__':
    main()

// LedgerCore V2 textbook: runs inside the HTML before printing. Colours terminal transcripts, waits for every image
// (the SVG diagrams and screenshots) and the fonts, records broken images, adds TOC leader dots, then flags ready.
(async () => {
  document.querySelectorAll('pre.console > code').forEach((code) => {
    if (code.querySelector('span')) return;
    const escape = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    let continuation = false;
    code.innerHTML = code.textContent.split('\n').map((line) => {
      let cls = '';
      if (continuation || line.startsWith('PS> ') || line.startsWith('$ ')) {
        cls = 'prompt';
        continuation = line.endsWith('`') || line.endsWith('\\');
      } else if (/^\[exit \d+\]$/.test(line)) {
        cls = 'exit';
      } else if (line.startsWith('# ')) {
        cls = 'comment';
      }
      return cls ? `<span class="${cls}">${escape(line)}</span>` : escape(line);
    }).join('\n');
  });
  // a figure whose image is an SVG diagram is drawn without the screenshot frame
  document.querySelectorAll('figure img').forEach((img) => {
    const figure = img.closest('figure');
    // pandoc keeps `{.h90 .data}` on the image; the stylesheet sizes and labels the figure by these classes
    img.classList.forEach((c) => figure.classList.add(c));
    if (figure.classList.contains('data')) return;
    if (/\.svg($|\?)/.test(img.getAttribute('src') || '')) figure.classList.add('diagram');
    else figure.classList.add('shot');
  });
  // a short table is never split across pages: its caption, header and rows stay together
  document.querySelectorAll('table').forEach((table) => {
    const rows = Array.from(table.tBodies).reduce((n, body) => n + body.rows.length, 0);
    if (rows <= 5) table.classList.add('keep');
  });
  const pending = Array.from(document.images)
    .filter((img) => !img.complete)
    .map((img) => new Promise((resolve) => { img.onload = img.onerror = resolve; }));
  await Promise.all(pending);
  const broken = Array.from(document.images).filter((img) => img.naturalWidth === 0).map((img) => img.getAttribute('src'));
  if (broken.length) document.body.dataset.brokenImages = JSON.stringify(broken);
  await document.fonts.ready;
  document.querySelectorAll('#TOC a').forEach((a) => {
    if (!a.querySelector('.fill')) {
      const fill = document.createElement('span');
      fill.className = 'fill';
      a.appendChild(fill);
    }
  });
  document.body.dataset.ready = '1';
})();

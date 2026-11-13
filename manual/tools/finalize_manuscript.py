from pathlib import Path
root = Path(__file__).resolve().parents[2]
updates = {
    'manual/chapters/11-idempotency.md': (
        '![原始意图恢复界面](manual/assets/screenshots/transfer-unknown-recovery.png)',
        '![网络响应丢失后的真实页面](manual/assets/screenshots/transfer-unknown.png)\n\n![原始意图恢复界面](manual/assets/screenshots/transfer-unknown-recovery.png)'),
    'manual/chapters/12-projections-reconciliation.md': (
        '![本地运行中的账户历史与分录详情](manual/assets/screenshots/history-detail.png)',
        '![本地实际运行的账户历史列表](manual/assets/screenshots/history-list.png)\n\n![本地运行中的账户历史与分录详情](manual/assets/screenshots/history-detail.png)'),
    'manual/chapters/13-rest-security.md': (
        '![本地实际运行的账户详情授权视图](manual/assets/screenshots/account-details.png)',
        '![本地实际运行的登录界面](manual/assets/screenshots/login.png)\n\n![本地实际运行的账户详情授权视图](manual/assets/screenshots/account-details.png)'),
}
for rel, (old, new) in updates.items():
    path = root / rel
    text = path.read_text(encoding='utf-8-sig')
    if old not in text:
        raise SystemExit(f'missing expected insertion anchor: {rel}')
    path.write_text(text.replace(old, new), encoding='utf-8')
path = root / 'manual/tools/build_book.py'
s = path.read_text(encoding='utf-8-sig')
s = s.replace("Paragraph('• ' + inline(s), styles['ref'])", "Paragraph('· ' + inline(s), styles['ref'])")
s = s.replace('Paragraph(f"• <link href=', 'Paragraph(f"· <link href=')
path.write_text(s, encoding='utf-8')
print('Embedded all requested UI states; replaced Symbol bullet fallback.')

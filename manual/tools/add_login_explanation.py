from pathlib import Path
p=Path('manual/chapters/13-rest-security.md')
s=p.read_text(encoding='utf-8-sig')
needle='![本地实际运行的登录界面](manual/assets/screenshots/login.png)\n\n'
insert=needle+'登录页说明该应用只用于练习，并提供注册入口；它是身份交互的前端，不负责决定账户所有权。页面显示“已登录”仍不能授权读取他人的账户。\n\n'
if needle not in s: raise SystemExit('login screenshot anchor missing')
p.write_text(s.replace(needle,insert),encoding='utf-8')

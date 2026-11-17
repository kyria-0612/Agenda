"""Final APK smoke using the synthetic emulator dataset created by daily_features_review."""
from pathlib import Path
source=Path(__file__).with_name('daily_features_review.py').read_text(encoding='utf-8')
exec(compile(source.split('# Seed')[0],str(Path(__file__)), 'exec'))
report=[]
def check(name,passed):
 report.append(dict(check=name,passed=bool(passed)))
 (out/'final-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 assert passed,name
start();tap('日程');check('final-build-restored-caption',bool(nodes('Keep10','text')))
day=adb('shell','date','+%Y-%m-%d').strip();tap('展开日期：'+day);time.sleep(.8)
capture('08-final-hanging-expanded')
check('final-build-date-expands',bool(nodes('收起日期：'+day)))
tap('收起日期：'+day);time.sleep(.5);tap('背景与文字','text')
check('final-build-appearance-editor',bool(nodes('Keep10','text')) and bool(nodes('更换背景图片','text')))
tap('关闭')
(out/'final-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('Final APK smoke passed')

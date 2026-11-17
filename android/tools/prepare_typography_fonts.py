"""Prepare local font resources for the approved native mixed typography."""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
import hashlib,json,shutil
root=Path(__file__).resolve().parents[1];fonts=root/'app/src/main/res/font';licenses=root/'app/src/main/assets/font-licenses';windows=Path('C:/Windows/Fonts')
records=[]
def record(src,dst):
 f=TTFont(dst);c=f.getBestCmap()
 records.append({'file':dst.name,'source':str(src),'sha256':hashlib.sha256(dst.read_bytes()).hexdigest(),'weight':f['OS/2'].usWeightClass,'glyphs':len(c),'embeddingFlags':f['OS/2'].fsType})
 print(dst.name,'weight',f['OS/2'].usWeightClass,'glyphs',len(c),flush=True)
for original,target in [('times.ttf','times_new_roman.ttf'),('timesbd.ttf','times_new_roman_bold.ttf'),('Lato-Regular.ttf','lato.ttf'),('Lato-Bold.ttf','lato_bold.ttf')]:
 src=windows/original;dst=fonts/target;shutil.copy2(src,dst);record(src,dst)
 f=TTFont(src)
 notices={str(k):list(dict.fromkeys(n.toUnicode() for n in f['name'].names if n.nameID==k)) for k in [0,13,14]}
 (licenses/(target+'.metadata.json')).write_text(json.dumps(notices,ensure_ascii=False,indent=2),encoding='utf-8')
for name,source in [('notosanssc','NotoSansSC-VF.ttf'),('notoserifsc','NotoSerifSC-VF.ttf')]:
 for weight,suffix in [(400,''),(600,'_semibold')]:
  src=windows/source;dst=fonts/(name+suffix+'.ttf')
  print('Instancing',source,weight,flush=True)
  font=TTFont(src);instantiateVariableFont(font,{'wght':weight},inplace=True);font.flavor=None;font.save(dst);record(src,dst)
src=root.parent/'prototype/visual-directions/assets/fonts/manrope.woff'
f=TTFont(src);instantiateVariableFont(f,{'wght':600},inplace=True);f.flavor=None;f.save(fonts/'manrope_semibold.ttf');record(src,fonts/'manrope_semibold.ttf')
f=TTFont(fonts/'spacegrotesk_semibold.ttf');assert f['OS/2'].usWeightClass==600
record(fonts/'spacegrotesk_semibold.ttf',fonts/'spacegrotesk_semibold.ttf')
(licenses/'typography-0.14.0-sources.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
print('Local fonts prepared.',flush=True)

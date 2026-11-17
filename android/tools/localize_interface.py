"""One-time extraction of literal interface copy; user-content expressions stay untouched."""
from pathlib import Path
import json,re,sys
root=Path(__file__).resolve().parents[1]/'app/src/main/kotlin/com/agenda/app'
catalog={}
def q(s):return json.dumps(s,ensure_ascii=False).replace('$','\\$')
def literal(s):
 return re.sub(r'\\([nrt"\\$])',lambda m:{'n':'\n','r':'\r','t':'\t','"':'"','\\':'\\','$':'$'}[m[1]],s)
def end_brace(s,i):
 start=i;depth=1
 while i<len(s):
  if s.startswith('//',i):i=s.find('\n',i);i=len(s) if i<0 else i;continue
  if s.startswith('/*',i):i=s.index('*/',i+2)+2;continue
  if s[i]=='"':_,i=parse_string(s,i);continue
  if s[i]=="'":
   i+=1
   while i<len(s) and s[i]!="'":i+=2 if s[i]=='\\' else 1
   i+=1;continue
  if s[i]=='{':depth+=1
  if s[i]=='}':
   depth-=1
   if not depth:return s[start:i],i+1
  i+=1
 raise ValueError('Unclosed interpolation')
def parse_string(s,i):
 if s.startswith('"""',i):
  j=s.index('"""',i+3)+3;return s[i:j],j
 i+=1;parts=[];raw='';args=[];reconstructed='"'
 while i<len(s):
  c=s[i]
  if c=='\\':raw+=s[i:i+2];i+=2;continue
  if c=='"':
   parts.append(literal(raw));reconstructed+=raw+'"';i+=1;break
  if c=='$' and i+1<len(s) and (s[i+1]=='{' or re.match(r'[A-Za-z_]',s[i+1])):
   parts.append(literal(raw));reconstructed+=raw;raw=''
   if s[i+1]=='{':expr,i=end_brace(s,i+2);expr=code(expr);reconstructed+='${'+expr+'}'
   else:
    m=re.match(r'[A-Za-z_][A-Za-z_0-9]*',s[i+1:]);expr=m[0];i+=1+len(expr);reconstructed+='$'+expr
   parts.append('{'+str(len(args))+'}');args.append(expr);continue
  raw+=c;i+=1
 key=''.join(parts)
 if re.search(r'[\u4e00-\u9fff]',key) or ('：' in key and args):
  catalog.setdefault(key,[]).append(str(current.relative_to(root)))
  return 'tr('+q(key)+(','+','.join(args) if args else '')+')',i
 return reconstructed,i

def code(s):
 out=[];i=0
 while i<len(s):
  if s.startswith('//',i):
   j=s.find('\n',i);j=len(s) if j<0 else j;out.append(s[i:j]);i=j;continue
  if s.startswith('/*',i):j=s.index('*/',i+2)+2;out.append(s[i:j]);i=j;continue
  if s[i]=='"':v,i=parse_string(s,i);out.append(v);continue
  if s[i]=="'":
   j=i+1
   while j<len(s) and s[j]!="'":j+=2 if s[j]=='\\' else 1
   j+=1;out.append(s[i:j]);i=j;continue
  out.append(s[i]);i+=1
 return ''.join(out)

changed={}
for current in root.rglob('*.kt'):
 if current.name in ['UiStrings.kt','EnglishCatalog.kt']:continue
 original=current.read_text(encoding='utf-8');new=code(original)
 if new!=original:
  if 'package com.agenda.app.design' not in new and 'import com.agenda.app.design.*' not in new:
   new=new.replace('package com.agenda.app\n','package com.agenda.app\n\nimport com.agenda.app.design.tr\n',1)
  changed[current]=new
out=root.parents[7] if False else Path(__file__).resolve().parents[2]/'documents/ui-translation-keys.json'
out.write_text(json.dumps(catalog,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'{len(catalog)} interface keys in {len(changed)} files')
if '--apply' in sys.argv:
 for p,s in changed.items():p.write_text(s,encoding='utf-8')
 print('Applied literal-only interface localization')

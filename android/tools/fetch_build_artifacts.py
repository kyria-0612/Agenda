"""Fetch a small explicit Maven Central coordinate list with official SHA256 checks.
Project-local build recovery only; no global repository or tool configuration.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib,json,subprocess,sys,re

root=Path(__file__).resolve().parents[1];base='https://repo.maven.apache.org/maven2/'
flags=getattr(subprocess,'CREATE_NO_WINDOW',0)
def fetch(coordinate):
 group,artifact,version=coordinate.split(':')
 assert all(re.fullmatch(r'[A-Za-z0-9_.-]+',p) for p in [group,artifact,version])
 relative=f'{group.replace(".","/")}/{artifact}/{version}'
 directory=root/'dependency-downloads/maven'/relative;directory.mkdir(parents=True,exist_ok=True)
 records=[]
 for suffix in ['jar','pom']:
  name=f'{artifact}-{version}.{suffix}';url=f'{base}{relative}/{name}';target=directory/name;check=directory/(name+'.sha256')
  for source,dest in [(url,target),(url+'.sha256',check)]:
   subprocess.run(['curl.exe','--fail','--silent','--show-error','--location','--connect-timeout','15','--max-time','180','--retry','1',source,'-o',str(dest)],check=True,creationflags=flags)
  digest=hashlib.sha256(target.read_bytes()).hexdigest()
  assert digest==check.read_text().split()[0].lower(),f'Official SHA256 mismatch: {name}'
  records.append(dict(source=url,sha256=digest,verified=True))
 print('Verified '+coordinate,flush=True)
 return records
with ThreadPoolExecutor(max_workers=4) as pool:records=[r for result in pool.map(fetch,sys.argv[1:]) for r in result]
(root/'dependency-downloads/additional-verification.json').write_text(json.dumps(records,indent=2),encoding='utf-8')

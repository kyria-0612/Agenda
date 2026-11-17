"""Recover an official KSP artifact on slow/TLS-fragile networks; verify before cache.
Uses only Maven Central endpoints and writes only the project's dependency cache.
"""
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
import hashlib,json,re,shutil,subprocess

root=Path(__file__).resolve().parents[1]
version='2.2.21-2.0.4';name=f'symbol-processing-aa-embeddable-{version}.jar'
relative=f'com/google/devtools/ksp/symbol-processing-aa-embeddable/{version}/{name}'
base='https://repo.maven.apache.org/maven2/'
target=root/'dependency-downloads';target.mkdir(exist_ok=True)
flags=getattr(subprocess,'CREATE_NO_WINDOW',0)
def curl(*args):subprocess.run(['curl.exe','--fail','--silent','--show-error','--location','--connect-timeout','15',*args],check=True,creationflags=flags)
headers=target/'ksp-headers.txt'
curl('--head','--max-time','30',base+relative,'-o',str(headers))
size=int(re.findall(r'content-length:\s*(\d+)',headers.read_text(),re.I)[-1])
checksum=target/(name+'.sha256');curl('--max-time','30',base+relative+'.sha256','-o',str(checksum))
expected=checksum.read_text().split()[0].lower();assert re.fullmatch('[0-9a-f]{64}',expected)
parts=target/'ksp-parts';parts.mkdir(exist_ok=True)
count=24;chunk=(size+count-1)//count
def fetch(index):
 start=index*chunk;end=min(size-1,start+chunk-1)
 path=parts/f'{index:02d}.part';head=parts/f'{index:02d}.headers'
 if path.exists() and path.stat().st_size==end-start+1:return index,path
 endpoint=base if index%2==0 else 'https://repo1.maven.org/maven2/'
 curl('--max-time','180','--retry','1','--range',f'{start}-{end}',endpoint+relative,'-D',str(head),'-o',str(path))
 assert path.stat().st_size==end-start+1,(index,path.stat().st_size)
 assert re.search(rf'content-range:\s*bytes {start}-{end}/{size}',head.read_text(),re.I),(index,'range response')
 return index,path
with ThreadPoolExecutor(max_workers=24) as pool:
 futures=[pool.submit(fetch,n) for n in range(count)]
 for done,future in enumerate(as_completed(futures),1):
  index,path=future.result();print(f'Completed {done}/{count}',flush=True)
jar=target/name
with jar.open('wb') as output:
 for n in range(count):
  with (parts/f'{n:02d}.part').open('rb') as source:shutil.copyfileobj(source,output)
assert jar.stat().st_size==size
actual=hashlib.sha256(jar.read_bytes()).hexdigest();assert actual==expected,'Official SHA256 mismatch'
sha1=hashlib.sha1(jar.read_bytes()).hexdigest()
cache=root/'.gradle-user-home/caches/modules-2/files-2.1/com.google.devtools.ksp/symbol-processing-aa-embeddable'/version/sha1
cache.mkdir(parents=True,exist_ok=True);shutil.copy2(jar,cache/name)
pomName=name.removesuffix('.jar')+'.pom'
pom=target/pomName;pomHash=target/(pomName+'.sha256')
curl('--max-time','30',base+relative.removesuffix('.jar')+'.pom','-o',str(pom))
curl('--max-time','30',base+relative.removesuffix('.jar')+'.pom.sha256','-o',str(pomHash))
assert hashlib.sha256(pom.read_bytes()).hexdigest()==pomHash.read_text().split()[0].lower()
maven=target/'maven/com/google/devtools/ksp/symbol-processing-aa-embeddable'/version
maven.mkdir(parents=True,exist_ok=True);shutil.copy2(jar,maven/name);shutil.copy2(pom,maven/pomName)
(target/'ksp-verification.json').write_text(json.dumps(dict(source=base+relative,size=size,sha256=actual,sha1=sha1,verified=True),indent=2),encoding='utf-8')
print('Verified official artifact and POM; populated project-local cache.',flush=True)

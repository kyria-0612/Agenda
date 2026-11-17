"""Fetch the official minimum-version emulator image, verifying its published digest."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib, urllib.request, zipfile
folder=Path(__file__).resolve().parents[1]/'.compat-runtime';folder.mkdir(exist_ok=True)
archive=folder/'api31.zip';size=657246510;digest='58bff3cb182c79bbfe3980fe77b87b51b9f7ad71'
url='https://dl.google.com/android/repository/sys-img/android/x86_64-31_r05.zip'
offset=archive.stat().st_size if archive.exists() else 0
assert offset<=size
ranges=[]
for i in range(8):
 start=offset+(size-offset)*i//8;end=offset+(size-offset)*(i+1)//8-1
 if end>=start:ranges.append((i,start,end))
def fetch(part):
 i,start,end=part;path=folder/f'api31-part-{i}'
 request=urllib.request.Request(url,headers={'Range':f'bytes={start}-{end}'})
 with urllib.request.urlopen(request,timeout=90) as response,path.open('wb') as output:
  assert response.status==206 and response.headers['Content-Range'].startswith(f'bytes {start}-'),response.headers
  while chunk:=response.read(1024*1024):output.write(chunk)
 assert path.stat().st_size==end-start+1
 print('Image segment ready',i,flush=True);return path
with ThreadPoolExecutor(max_workers=8) as pool:parts=list(pool.map(fetch,ranges))
with archive.open('ab') as output:
 for part in parts:
  with part.open('rb') as source:
   while chunk:=source.read(1024*1024):output.write(chunk)
assert archive.stat().st_size==size
assert hashlib.file_digest(archive.open('rb'),'sha1').hexdigest()==digest
with zipfile.ZipFile(archive) as source:source.extractall(folder/'api31')
print('Official API 31 image digest verified and extracted',flush=True)

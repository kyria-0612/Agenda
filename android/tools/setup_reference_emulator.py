from pathlib import Path
import urllib.request,zipfile,xml.etree.ElementTree as ET,concurrent.futures
root=Path('agenda-main/android/.build-tools').resolve()

def install(repo,package,destination):
    doc=ET.fromstring(urllib.request.urlopen(repo,timeout=60).read())
    pkg=next(p for p in doc.findall('remotePackage') if p.get('path')==package)
    arc=next(a for a in pkg.findall('archives/archive') if a.findtext('host-os') in ('windows',None))
    url=repo.rsplit('/',1)[0]+'/'+arc.findtext('complete/url')
    archive=root/Path(url).name
    if not archive.exists():
        print('Downloading '+archive.name,flush=True);urllib.request.urlretrieve(url,archive)
    target=root/'sdk'/destination;target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        prefix=z.namelist()[0].split('/')[0]+'/'
        for name in z.namelist():
            if name.endswith('/'):continue
            out=target/name.removeprefix(prefix);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(name))
    print('Installed '+package,flush=True)
with concurrent.futures.ThreadPoolExecutor() as pool:
    fs=[pool.submit(install,'https://dl.google.com/android/repository/repository2-1.xml',p,d) for p,d in [('emulator','emulator'),('platform-tools','platform-tools')]]
    fs.append(pool.submit(install,'https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml','system-images;android-31;default;x86_64','system-images/android-31/default/x86_64'))
    for f in fs:f.result()

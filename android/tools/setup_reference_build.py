from pathlib import Path
import urllib.request, zipfile, json, xml.etree.ElementTree as ET, concurrent.futures
root=Path(__file__).resolve().parents[1]
cache=root/'.build-tools'; cache.mkdir(exist_ok=True)

def download(url,path):
    if not path.exists():
        print('Downloading '+path.name,flush=True)
        urllib.request.urlretrieve(url,path)
    return path

def jdk():
    archive=download('https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip',cache/'jdk17.zip')
    if not (cache/'jdk').exists():
        with zipfile.ZipFile(archive) as z:
            prefix=z.namelist()[0].split('/')[0];z.extractall(cache/'jdk')
    return next((cache/'jdk').glob('*/bin/java.exe')).parents[1]

def sdk():
    raw=urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml',timeout=60).read()
    doc=ET.fromstring(raw)
    for package,dest in [('platforms;android-36','platforms/android-36'),('build-tools;35.0.0','build-tools/35.0.0')]:
        pkg=max((p for p in doc.findall('remotePackage') if p.get('path')==package),key=lambda p:int(p.findtext('revision/major')))
        archives=pkg.findall('archives/archive')
        arc=next(a for a in archives if a.findtext('host-os') in ('windows',None))
        url=arc.findtext('complete/url')
        archive=download('https://dl.google.com/android/repository/'+url,cache/url)
        target=cache/'sdk'/dest
        target.mkdir(parents=True,exist_ok=True)
        (target/'package.xml').unlink(missing_ok=True)
        with zipfile.ZipFile(archive) as z:
            prefix=z.namelist()[0].split('/')[0]+'/'
            for name in z.namelist():
                if name.endswith('/'): continue
                out=target/name.removeprefix(prefix);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(name))
    (root/'local.properties').write_text('sdk.dir=.build-tools/sdk\n',encoding='ascii')
    return cache/'sdk'

with concurrent.futures.ThreadPoolExecutor() as pool:
    a=pool.submit(jdk);b=pool.submit(sdk)
    print('JDK='+str(a.result()),flush=True);print('SDK='+str(b.result()),flush=True)

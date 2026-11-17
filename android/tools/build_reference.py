from pathlib import Path
import os,sys,zipfile,subprocess,urllib.request,urllib.parse
root=Path('agenda-main/android').resolve();cache=root/'.build-tools'
drive=None
if os.name=='nt' and (not str(root).isascii() or "'" in str(root)):
    for letter in ['Z','Y','X','W']:
        if not Path(letter+':/').exists():
            drive=letter+':'
            subprocess.run(['subst',drive,str(root)],check=True)
            root=Path(drive+'/');cache=root/'.build-tools'
            break
if not (cache/'gradle-8.13').exists():
    with zipfile.ZipFile(cache/'gradle-8.13-bin.zip') as z:z.extractall(cache)
os.environ['JAVA_HOME']=str(next((cache/'jdk').iterdir()));os.environ['GRADLE_USER_HOME']=str(root/'.gradle-user-home')
proxy=urllib.parse.urlsplit(urllib.request.getproxies()['https'])
tasks=sys.argv[1:] or [':app:assembleDebug',':app:lintDebug',':domain:test',':data:testDebugUnitTest']
args=[str(cache/'gradle-8.13/bin/gradle.bat'),*tasks,'-Pandroid.overridePathCheck=true','--console','plain','--no-daemon']
for protocol in ['http','https']:args.extend([f'-D{protocol}.proxyHost={proxy.hostname}',f'-D{protocol}.proxyPort={proxy.port}'])
args.extend(['-Djavax.net.ssl.trustStoreType=Windows-ROOT','-Djavax.net.ssl.trustStore=NONE'])
try:
    result=subprocess.call(args,cwd=root)
finally:
    if drive:subprocess.run(['subst',drive,'/D'],check=True)
raise SystemExit(result)

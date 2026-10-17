#!/usr/bin/env python3
import os, subprocess, zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
sdk=Path.home()/'Android/Sdk';java=Path('/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin')
out=root/'tools/prpr-audit/device-build';classes=out/'classes';dex=out/'dex'
classes.mkdir(parents=True,exist_ok=True);dex.mkdir(exist_ok=True)
production=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
rjar=root/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'
android=sdk/'platforms/android-35/android.jar'
subprocess.run([str(java/'javac'),'-cp',':'.join(map(str,[android,production,rjar])),'-d',str(classes),str(root/'tools/prpr-audit/PlaybackDeviceProbe.java')],check=True)
with zipfile.ZipFile(out/'probe.jar','w') as z:
 for base in [classes,production]:
  for file in base.rglob('*.class'):z.write(file,file.relative_to(base))
 with zipfile.ZipFile(rjar) as r:
  for name in r.namelist():
   if name.endswith('.class'):z.writestr(name,r.read(name))
output=dex/'classes.dex'
if output.exists():output.chmod(0o600)
subprocess.run([str(java/'java'),'-cp',str(sdk/'build-tools/35.0.0/lib/d8.jar'),'com.android.tools.r8.D8','--min-api','26','--lib',str(android),'--output',str(dex),str(out/'probe.jar')],check=True)
output.chmod(0o400)
env=os.environ.copy();env['CLASSPATH']=str(output)
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
subprocess.run(['/system/bin/app_process64','/system/bin','com.xpe.mobile.preview.PlaybackDeviceProbe',str(apk)],env=env,check=True)

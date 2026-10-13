#!/usr/bin/env python3
"""Compare offscreen Android UI images from baseline/current production code."""
from pathlib import Path
import argparse,subprocess,zipfile,os,hashlib,json
p=argparse.ArgumentParser();p.add_argument('--baseline',required=True,type=Path);a=p.parse_args()
root=Path(__file__).resolve().parents[2];build=root/'tools/refactor/build/android';build.mkdir(parents=True,exist_ok=True)
sdk=Path.home()/'Android/Sdk';java=Path('/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin');android=sdk/'platforms/android-35/android.jar'
production=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes';rjar=root/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'
classes=build/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(java/'javac'),'-cp',':'.join(map(str,[android,production,rjar])),'-d',str(classes),str(root/'tools/refactor/UiEquivalenceProbe.java')],check=True)
results={}
for label,implementation in [('baseline',a.baseline.resolve()),('refactored',production)]:
 folder=build/label;folder.mkdir(exist_ok=True);dex=folder/'dex';dex.mkdir(exist_ok=True)
 with zipfile.ZipFile(folder/'probe.jar','w') as bundle:
  for file in classes.rglob('*.class'):bundle.write(file,file.relative_to(classes))
  if implementation.is_dir():
   for file in implementation.rglob('*.class'):bundle.write(file,file.relative_to(implementation))
  else:
   with zipfile.ZipFile(implementation) as old:
    for name in old.namelist():bundle.writestr(name,old.read(name))
  with zipfile.ZipFile(rjar) as res:
   for name in res.namelist():
    if name.endswith('.class'):bundle.writestr(name,res.read(name))
 output=dex/'classes.dex'
 if output.exists():output.chmod(0o600)
 subprocess.run([str(java/'java'),'-cp',str(sdk/'build-tools/35.0.0/lib/d8.jar'),'com.android.tools.r8.D8','--min-api','26','--lib',str(android),'--output',str(dex),str(folder/'probe.jar')],check=True)
 output.chmod(0o400);env=os.environ.copy();env['CLASSPATH']=str(output)
 captures=folder/'images'
 subprocess.run(['/system/bin/app_process64','/system/bin','com.xpe.mobile.UiEquivalenceProbe',str(root/'app/build/outputs/apk/debug/app-debug.apk'),str(captures),str(root/'app/src/main/assets/demo_chart.json')],env=env,check=True)
 results[label]={file.name:hashlib.sha256(file.read_bytes()).hexdigest() for file in sorted(captures.glob('*.png'))}
assert len(results['baseline'])==8 and results['baseline']==results['refactored'],results
(root/'tools/refactor/android-equivalence.json').write_text(json.dumps(results,indent=2)+'\n')
print('PASS: 8 Android-rendered editor/preview/shader images match byte-for-byte')

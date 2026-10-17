#!/usr/bin/env python3
"""Compare current Java behavior with a preserved baseline classes jar."""
from pathlib import Path
import argparse,subprocess,os,json
parser=argparse.ArgumentParser();parser.add_argument('--baseline',required=True,type=Path);parser.add_argument('--charts',type=Path,default=Path.home()/'storage/shared/Download');args=parser.parse_args()
root=Path(__file__).resolve().parents[2];build=root/'tools/refactor/build/parity';build.mkdir(parents=True,exist_ok=True)
java=Path('/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin')
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
jsonjar=next((cache/'org.json/json/20240303').rglob('*.jar'))
classes=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
cp=os.pathsep.join(map(str,[classes,jsonjar]))
subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(build),str(root/'tools/refactor/SceneEquivalenceProbe.java'),str(root/'tools/refactor/AudioEquivalenceProbe.java')],check=True)
charts=[args.charts/name for name in ['Parallel Universe Shifter.zip','ANTINEUTRINO WITCHCRAFT.zip']]
if not all(p.is_file() for p in charts):raise SystemExit('Missing real chart fixtures: '+str(charts))
results={}
for label,implementation in [('baseline',args.baseline.resolve()),('refactored',classes)]:
 runtime_cp=os.pathsep.join(map(str,[build,implementation,jsonjar]))
 output={}
 for name,probe,params in [('scenes','SceneEquivalenceProbe',[root/'app/src/main/assets/demo_chart.json',build/(label+'-workspaces'),*charts]),('pcm','com.xpe.mobile.audio.AudioEquivalenceProbe',[])]:
  result=subprocess.run([str(java/'java'),'-Xmx1g','-cp',runtime_cp,probe,*map(str,params)],check=True,capture_output=True,text=True)
  output[name]=result.stdout.strip();print(label,name,output[name],flush=True)
 results[label]=output
assert results['baseline']==results['refactored'],results
(root/'tools/refactor/runtime-equivalence.json').write_text(json.dumps(results,indent=2)+'\n')
print('PASS: scene/model/package snapshots and short/float PCM are identical')

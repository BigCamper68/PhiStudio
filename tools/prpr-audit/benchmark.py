#!/usr/bin/env python3
from pathlib import Path
import subprocess, os
root=Path(__file__).resolve().parents[2]
cache=Path('/data/data/com.termux/files/home/.gradle/caches/modules-2/files-2.1')
jars=[next((cache/name).rglob('*.jar')) for name in ['junit/junit/4.13.2','org.hamcrest/hamcrest-core/1.3','org.json/json/20240303']]
base=root/'tools/prpr-audit/baseline'; base.mkdir(exist_ok=True)
baseline_ref='329404cbe9144b3cc15ddaa6264a38e16be53435'
paths=subprocess.check_output(['git','ls-tree','-r','--name-only',baseline_ref,'app/src/main/java/com/xpe/mobile/model'],cwd=root,text=True).splitlines()
paths+=['app/src/main/java/com/xpe/mobile/preview/ChartEvaluator.java','app/src/main/java/com/xpe/mobile/preview/RenderScene.java']
files=[]
for path in paths:
    dest=base/Path(path).name
    dest.write_bytes(subprocess.check_output(['git','show',baseline_ref+':'+path],cwd=root));files.append(str(dest))
classes=base/'classes'; classes.mkdir(exist_ok=True)
java='/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin/'
cp=os.pathsep.join(map(str,jars))
subprocess.run([java+'javac','-cp',cp,'-d',str(classes),*files],check=True)
app=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
tests=root/'app/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes'
env=os.environ.copy();env['PHISTUDIO_CHART_DIR']='/data/data/com.termux/files/home/storage/shared/Download'
for label,impl in [('1.0.4',classes),('1.0.5',app)]:
    result=subprocess.run([java+'java','-Xmx2g','-cp',os.pathsep.join([str(impl),str(tests),cp]),'org.junit.runner.JUnitCore','com.xpe.mobile.preview.LargeChartBenchmarkTest'],env=env,capture_output=True,text=True)
    print(label+'\n'+result.stdout+result.stderr)
    (root/f'tools/prpr-audit/benchmark-{label}.txt').write_text(result.stdout+result.stderr)
    if result.returncode: raise SystemExit(result.returncode)

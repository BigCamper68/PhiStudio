#!/usr/bin/env python3
"""Run the production Java GLES compositor on Android without installing an APK."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--charts', required=True, type=Path)
parser.add_argument('--skip-compile', action='store_true')
parser.add_argument('--report', type=Path)
parser.add_argument('--performance', action='store_true')
parser.add_argument('--view', action='store_true')
parser.add_argument('--editor', action='store_true')
parser.add_argument('--apk', type=Path)
args = parser.parse_args()
project = Path(__file__).resolve().parents[2]
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk'))))
java_home = Path(os.environ.get('JAVA_HOME', '/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk'))
java, javac = str(java_home / 'bin/java'), str(java_home / 'bin/javac')
environment = os.environ.copy()
environment['JAVA_HOME'] = str(java_home)
environment['ANDROID_HOME'] = str(sdk)
if not args.skip_compile:
    command = ['sh', './gradlew', '--no-daemon', 'compileDebugJavaWithJavac']
    if shutil.which('aapt2'):
        command.append('-Pandroid.aapt2FromMavenOverride=' + shutil.which('aapt2'))
    subprocess.run(command, cwd=project, env=environment, check=True)
build = project / 'tools/shader-tests/build'
classes, dex = build / 'classes', build / 'dex'
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(exist_ok=True)
production = project / 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
android = sdk / 'platforms/android-35/android.jar'
rjar = project / 'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'
sources = list((project / 'tools/shader-tests').glob('*.java'))
subprocess.run([javac, '-source', '17', '-target', '17', '-cp', ':'.join(map(str, (android, production, rjar))),
                '-d', str(classes), *map(str, sources)], check=True)
with zipfile.ZipFile(build / 'probe.jar', 'w') as bundle:
    for base in (classes, production):
        for source in base.rglob('*.class'):
            bundle.write(source, source.relative_to(base))
    with zipfile.ZipFile(rjar) as resources:
        for name in resources.namelist():
            if name.endswith('.class'): bundle.writestr(name, resources.read(name))
output = dex / 'classes.dex'
if output.exists():
    output.chmod(0o600)
subprocess.run([java, '-cp', str(sdk / 'build-tools/35.0.0/lib/d8.jar'), 'com.android.tools.r8.D8',
                '--min-api', '26', '--lib', str(android), '--output', str(dex), str(build / 'probe.jar')], check=True)
output.chmod(0o400)  # Android ART requires dynamically loaded DEX files to be read-only.
environment['CLASSPATH'] = str(output)
probe = 'com.xpe.mobile.ShaderEditorDeviceProbe' if args.editor else 'com.xpe.mobile.preview.' + ('ShaderViewDeviceProbe' if args.view else 'ShaderPerformanceProbe' if args.performance else 'ShaderDeviceProbe')
if (args.performance or args.view or args.editor) and not args.apk: parser.error('--performance/--view/--editor requires --apk')
arguments = [str(args.apk.resolve()), str(build / 'workspaces')] if args.performance or args.view or args.editor else []
result = subprocess.Popen(['/system/bin/app_process64', '/system/bin', probe,
                         str(project / 'app/src/main/assets/shaders'), str(args.charts.resolve()), *arguments],
                        env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
output_lines = []
for line in result.stdout:
    output_lines.append(line)
    print(line, end='', flush=True)
if args.report:
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(''.join(output_lines), encoding='utf-8')
raise SystemExit(result.wait())

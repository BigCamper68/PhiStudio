#!/usr/bin/env python3
"""Compare actual chart evaluation with a preserved pre-patch classes JAR; does not measure display FPS."""
from pathlib import Path
import argparse, os, subprocess
parser = argparse.ArgumentParser()
parser.add_argument('--chart', required=True, type=Path)
parser.add_argument('--baseline', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
classes = root / 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
json_jar = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.json/json/20240303').rglob('*.jar'))
build = args.output / 'classes'
build.mkdir(parents=True, exist_ok=True)
cp = os.pathsep.join(map(str, [classes, json_jar]))
subprocess.run(['javac', '-cp', cp, '-d', str(build), str(Path(__file__).with_name('PlaybackProfile.java'))], check=True)
checksums = []
for label, implementation in [('baseline', args.baseline.resolve()), ('candidate', classes)]:
    runtime = os.pathsep.join(map(str, [build, implementation, json_jar]))
    result = subprocess.run(['java', '-Xmx2g', '-cp', runtime, 'com.xpe.mobile.preview.PlaybackProfile', str(args.chart.resolve())],
                            text=True, capture_output=True, check=True)
    (args.output / (label + '.txt')).write_text(result.stdout + result.stderr)
    print(label + '\n' + result.stdout, flush=True)
    checksums.append(result.stdout.split('checksum ')[-1].strip())
assert checksums[0] == checksums[1], checksums

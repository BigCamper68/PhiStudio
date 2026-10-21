"""Compare the production Java GLES renderer with the published GLES3 shader extracts.
Both runs execute NoiseFieldGlRenderer through host/build.py's JNI adapter.
This checks shader adaptation and real GL state, not Android SurfaceTexture or HWUI.
"""
from pathlib import Path
import argparse,hashlib,json,re,shutil,subprocess
p=argparse.ArgumentParser();p.add_argument('--reference',type=Path,required=True);p.add_argument('--chart',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
repo=Path(__file__).resolve().parents[2];out=a.output.resolve();out.mkdir(exist_ok=True,parents=True)
assets=repo/'app/src/main/assets';native=out/'native-assets';shutil.copytree(assets/'noise-field',native/'noise-field',dirs_exist_ok=True)
for file in (a.reference/'knowladge/phigros/shaders').glob('Unlit_*.md'):
 name=file.stem.removeprefix('Unlit_')
 if not (native/'noise-field'/f'{name}-0.vert').is_file():continue
 for stage,extension in [('VERTEX','vert'),('FRAGMENT','frag')]:
  programs=re.findall(r'#ifdef '+stage+r'\n#version 300 es\n(.*?\n}\n)\n#endif',file.read_text(),re.S)
  for index,source in enumerate(programs):
   source='\n'.join(line for line in source.splitlines() if not line.lstrip().startswith('#'))+'\n'
   source=re.sub(r'UNITY_LOCATION\(\d+\)\s*','',source)
   (native/'noise-field'/f'{name}-{index}.{extension}').write_text('#version 300 es\n'+source)
host=out/'host';subprocess.run(['python',str(repo/'tools/noise-field/host/build.py'),str(host)],check=True)
cp=(host/'classpath.txt').read_text().strip()
for name,source in [('ported',assets),('native',native)]:
 subprocess.run(['java','-Djava.awt.headless=true','-Dnoise.gl.lib='+str(host/'libnoisegles.so'),'-cp',cp,'com.xpe.mobile.preview.NoiseFieldHost',str(source),str(a.chart),str(out/name)],check=True)
ported=json.loads((out/'ported/frames.json').read_text());original=json.loads((out/'native/frames.json').read_text());assert ported==original
report=[]
for filename in sorted((out/'ported').glob('*.png')):
 reference=out/'native'/filename.name
 equal=filename.read_bytes()==reference.read_bytes()
 report.append({'file':filename.name,'png_identical':equal,'ported_sha256':hashlib.sha256(filename.read_bytes()).hexdigest(),'original_sha256':hashlib.sha256(reference.read_bytes()).hexdigest()})
result={'reference_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=a.reference,text=True).strip(),'compared_frames':len(report),'source_samples':len(ported),'all_png_identical':all(row['png_identical'] for row in report),'scope':'Production Java renderer on surfaceless Mesa GLES; original GLES3 fragment/vertex arithmetic versus GLES2 adaptation. Geometry and compositor configuration are shared. Not a physical-device capture.','frames':report}
(out/'comparison.json').write_text(json.dumps(result,indent=2)+'\n')
assert result['all_png_identical'],'Shader adaptation differs; inspect comparison.json'
print(json.dumps({key:value for key,value in result.items() if key!='frames'},indent=2))

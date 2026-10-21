"""Reproduce the GLES2 adaptation of published Phigros 4 shader extracts.
Usage: python tools/noise-field/generate_assets.py /path/to/CHCAT-Docs
"""
from pathlib import Path
import argparse, hashlib, json, re, shutil, subprocess
p=argparse.ArgumentParser();p.add_argument('reference',type=Path);p.add_argument('--phira-pro',type=Path);args=p.parse_args()
reference=args.reference.resolve();source=reference/'knowladge/phigros'
output=Path(__file__).resolve().parents[2]/'app/src/main/assets/noise-field';output.mkdir(parents=True,exist_ok=True)
manifest={'reference':'https://github.com/CHCAT1320/CHCAT-Docs','commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=reference,text=True).strip(),'files':{},'adaptation':'GLES 3 compiled shader extracts to GLES 2; original arithmetic and precision qualifiers retained. Explicit LOD 0 becomes texture2D on textures without mipmaps.'}
for name in ['BlockSprite','BlockCompose','SubtractBlockBlender','EdgeMask','GlowMask','DisabledBlock','ReadyBlock','ActiveBlock']:
    path=source/f'shaders/Unlit_{name}.md';text=path.read_text()
    manifest['files'][str(path.relative_to(reference))]=hashlib.sha256(path.read_bytes()).hexdigest()
    for stage,extension in [('VERTEX','vert'),('FRAGMENT','frag')]:
        programs=re.findall(r'#ifdef '+stage+r'\n#version 300 es\n(.*?\n}\n)\n#endif',text,re.S)
        assert programs,(name,stage)
        for index,program in enumerate(programs):
            program='\n'.join(line for line in program.splitlines() if not line.lstrip().startswith('#'))+'\n'
            program=re.sub(r'UNITY_LOCATION\(\d+\)\s*','',program)
            if stage=='VERTEX':
                program=re.sub(r'\bin\s+(?=(?:highp|mediump|lowp)\s+)', 'attribute ',program)
                program=re.sub(r'\bout\s+(?=(?:highp|mediump|lowp)\s+)', 'varying ',program)
            else:
                match=re.search(r'layout\(location\s*=\s*0\)\s*out\s+(mediump|highp|lowp)\s+(float|vec2|vec4)\s+SV_Target0;',program)
                assert match,(name,index)
                precision,kind=match.groups();program=program.replace(match.group(),f'{precision} {kind} SV_Target0;')
                program=re.sub(r'\bin\s+(?=(?:highp|mediump|lowp)\s+)', 'varying ',program)
                value={'float':'vec4(SV_Target0, 0.0, 0.0, 1.0)','vec2':'vec4(SV_Target0, 0.0, 1.0)','vec4':'SV_Target0'}[kind]
                program=program.replace('    return;','    gl_FragColor = '+value+';\n    return;')
                program=re.sub(r'textureLod\((\w+),\s*([^,]+),\s*0\.0\)',r'texture2D(\1, \2)',program)
                program=re.sub(r'\btexture\(', 'texture2D(',program)
            target=output/f'{name}-{index}.{extension}'
            target.write_text('#version 100\n// Generated from '+str(path.relative_to(reference))+'.\n'+program)
for name in ['Block','BlockNoise1','PointNoise','FD_Noise']:
    path=source/f'tex/{name}.png';shutil.copy2(path,output/path.name)
    manifest['files'][str(path.relative_to(reference))]=hashlib.sha256(path.read_bytes()).hexdigest()
parameter_path=source/'block-params.md'
parameters=json.loads(parameter_path.read_text().split('````json\n')[1].split('````')[0])
(output/'parameters.json').write_text(json.dumps(parameters['materials'],indent=2)+'\n')
manifest['files'][str(parameter_path.relative_to(reference))]=hashlib.sha256(parameter_path.read_bytes()).hexdigest()
if args.phira_pro:
    pro=args.phira_pro.resolve()
    manifest['corrected_textures']={'reference':'https://github.com/Phira-Pro/Phira-Pro','commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=pro,text=True).strip(),'files':{}}
    for source_name,target_name in [('BlockNoise1.png','BlockNoise1.png'),('PointNoise.png','PointNoise.png'),('FD_Noise_00000.png','FD_Noise.png'),('TouchHover.png','TouchHover.png')]:
        path=pro/'assets/blockarea'/source_name;shutil.copy2(path,output/target_name)
        manifest['corrected_textures']['files'][str(path.relative_to(pro))]=hashlib.sha256(path.read_bytes()).hexdigest()
(output/'sources.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Generated',len(list(output.glob('*'))),'noise field assets')

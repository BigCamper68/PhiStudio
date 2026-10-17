from pathlib import Path
import argparse,json,zipfile,subprocess,shutil
parser=argparse.ArgumentParser(description='Compare RPE rotation layers and Scale X with upstream prpr f32 Anim')
parser.add_argument('upstream',type=Path)
parser.add_argument('chart',type=Path)
parser.add_argument('output',type=Path)
args=parser.parse_args()
root=args.upstream;out=args.output;out.mkdir(parents=True,exist_ok=True)
s=(root/'prpr/src/core/tween.rs').read_text().replace('use macroquad::prelude::{vec2, Color, Rect, Vec2};','').replace('use once_cell::sync::Lazy;','use std::sync::LazyLock as Lazy;')
for name in ['Vec2','Color','Rect']:
 start=s.index('impl Tweenable for '+name+' {');depth=0
 for i in range(s.index('{',start),len(s)):
  if s[i]=='{':depth+=1
  if s[i]=='}':depth-=1
  if depth==0:break
 s=s[:start]+s[i+1:]
a=(root/'prpr/src/core/anim.rs').read_text().replace('use super::{StaticTween, TweenFunction, TweenId, Tweenable, Vector};','use super::{StaticTween, TweenFunction, TweenId, Tweenable};')
a=a[:a.index('#[derive(Default)]\npub struct AnimVector')]
s+='\nmod anim {\n'+a+'\n}\nuse anim::{Anim, AnimFloat, Keyframe};\n'
p=(root/'prpr/src/parse.rs').read_text();p=p[p.index('pub const RPE_TWEEN_MAP:'):];p=p[:p.index('\n};')+3].replace('crate::core::TweenId','TweenId').replace('use crate::core::{easing_from as e, TweenMajor::*, TweenMinor::*};','use crate::{easing_from as e, TweenMajor::*, TweenMinor::*};')
s+=p+'\n'
with zipfile.ZipFile(args.chart) as z:chart=json.loads(z.read('chart.json'))
assert len(chart['BPMList'])==1 and chart['BPMList'][0]['bpm']==130, "This corpus expects the Disk Failure constant 130 BPM fixture"
def beat(t):return t[0]+t[1]/t[2]
def num(v,kind):return repr(float(v))+kind
count=0
def anim(events,factor):
 global s,count
 if not events:return f'AnimFloat::fixed({num(1 if factor==.5 else 0,"f32")})'
 rows=[]
 for e in events:
  assert not e.get('bezier',0)
  ease=e.get('easingType',1);left=e.get('easingLeft',0);right=e.get('easingRight',1)
  tween=f'StaticTween::get_rc(RPE_TWEEN_MAP[{ease}])' if ease<=1 or (abs(left)<1e-5 and abs(right-1)<1e-5) or left>=right else f'Rc::new(ClampedTween::new(RPE_TWEEN_MAP[{ease}],{num(left,"f32")}..{num(right,"f32")}))'
  rows.append(f'Keyframe {{time:{num(beat(e["startTime"])*60/130,"f64")},value:{num(e["start"],"f32")},tween:{tween}}}')
  rows.append(f'Keyframe::new({num(beat(e["endTime"])*60/130,"f64")},{num(e["end"],"f32")},0)')
 name=f'anim_{count}';count+=1
 s+=f'fn {name}()->AnimFloat {{ let mut a=Anim::new(vec![{",".join(rows)}]);a.map_value(|v|v*{num(factor,"f32")});a }}\n'
 return name+'()'
expr=[]
for l in chart['judgeLineList']:
 layers=[v['rotateEvents'] for v in l['eventLayers'] if v and v.get('rotateEvents')]
 rotations='AnimFloat::chain(vec!['+','.join(anim(e,-1) for e in layers)+'])'
 sx=anim((l.get('extended') or {}).get('scaleXEvents',[]),.5)
 expr.append(f'({rotations},{sx})')
s+='''fn main(){
 if std::env::args().nth(1).as_deref()==Some("angles") {
  println!("degrees,radians,cos,sin");
  for d in (-180..=180).step_by(15).map(|x|x as f32).chain([-2164864320f32,-2164864256.,-414720.,-10000.,10000.,1e9]) {
   let r=d.to_radians();let(si,co)=r.sin_cos();println!("{d},{r},{co},{si}");
  }return;
 }
 println!("beat,line,rotation,scale_x,cos,sin");
 let mut lines=vec!['''+','.join(expr)+'''];
 for beat in [0f64,0.001,0.1,1.,90.,98.,100.,100.125,100.5,101.,102.,103.,104.] {
  for (i,(rot,sx)) in lines.iter_mut().enumerate(){
   rot.set_time(beat*60./130.);sx.set_time(beat*60./130.);
   let r=rot.now();let rad=r.to_radians();let(si,co)=rad.sin_cos();
   println!("{beat},{i},{r},{},{co},{si}",sx.now());
  }
 }
}\n'''
(out/'disk_oracle.rs').write_text(s)
rust=shutil.which('rustc')
if rust:
 subprocess.run([rust,'--edition=2021','-Awarnings',str(out/'disk_oracle.rs'),'-o',str(out/'disk_oracle')],check=True)
 for name,args in [('disk-oracle.csv',[]),('rotation-oracle.csv',['angles'])]:
  (out/name).write_bytes(subprocess.check_output([str(out/'disk_oracle')]+args))
 print((out/'disk-oracle.csv').read_text()[:800])
else:print('Generated upstream Rust oracle; install rustc and rerun to produce CSV files')

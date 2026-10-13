#!/usr/bin/env python3
"""Extract and run upstream scalar Rust math. Needs rustc, no graphics/cargo dependencies.
Usage: python3 tools/prpr-audit/generate_oracle.py /path/to/pinned/phira
Generated code remains GPL-3.0 (upstream); no Rust code is shipped in the app.
"""
from pathlib import Path
import sys, subprocess
root=Path(sys.argv[1]); out=Path(__file__).resolve().parent
source=(root/'prpr/src/core/tween.rs').read_text()
source=source.replace('use macroquad::prelude::{vec2, Color, Rect, Vec2};', '').replace('use once_cell::sync::Lazy;', 'use std::sync::LazyLock as Lazy;')
# Unchanged scalar implementation; remove unrelated graphical Tweenable impls.
for name in ['Vec2', 'Color', 'Rect']:
    start=source.index('impl Tweenable for '+name+' {'); depth=0; end=start
    for i in range(source.index('{',start),len(source)):
        if source[i]=='{': depth+=1
        if source[i]=='}': depth-=1
        if depth==0: end=i+1; break
    source=source[:start]+source[end:]
rpe=(root/'prpr/src/parse/rpe.rs').read_text()
start=rpe.index('#[derive(Copy, Clone)]\nenum SpeedEasingMode'); end=rpe.index('#[derive(Deserialize)]\n#[serde(rename_all = "camelCase")]\nstruct RPEChart',start)
source+='\nconst EPS: f64 = 1e-5;\n'+rpe[start:end]
parse=(root/'prpr/src/parse.rs').read_text();mapping=parse[parse.index('pub const RPE_TWEEN_MAP:'):];mapping=mapping[:mapping.index('\n};')+3].replace('crate::core::TweenId','TweenId').replace('use crate::core::{easing_from as e, TweenMajor::*, TweenMinor::*};','use crate::{easing_from as e, TweenMajor::*, TweenMinor::*};')
source+='\n'+mapping
source+='''
fn bezier_oracle() {
    println!("x1,y1,x2,y2,x,value,integral");
    for points in [[0f32,0.,1.,1.],[0.42,0.,0.58,1.],[0.,1.,0.,1.],
                   [1.,0.,1.,0.],[0.25,-0.5,0.8,1.5],[0.9,0.1,0.1,0.9],
                   [0.05,0.4,0.95,0.6],[0.33,0.66,0.67,0.34]] {
        let tween: Rc<dyn TweenFunction> = Rc::new(BezierTween::new((points[0],points[1]),(points[2],points[3])));
        let integral = GeneralIntTween::new(Rc::clone(&tween));
        for x in [-0.2f32,-0.01,0.,0.00001,0.01,0.05,0.125,0.25,0.5,0.75,0.9,0.95,0.99,1.] {
            println!("{},{},{},{},{},{},{}",points[0],points[1],points[2],points[3],x,tween.y(x),integral.y(x));
        }
    }
}
fn main() {
    if std::env::args().len() > 1 { bezier_oracle(); return; }
    println!("kind,easing,left,right,x,value");
    for id in 1..=29 {
        for (left,right) in [(0f32,1f32),(0.2,0.8)] {
            let tween: Rc<dyn TweenFunction> = if left==0. || id==1 {StaticTween::get_rc(RPE_TWEEN_MAP[id])} else {Rc::new(ClampedTween::new(RPE_TWEEN_MAP[id],left..right))};
            for x in [0f32,0.125,0.25,0.5,0.75,0.875,1.] {
                println!("easing,{id},{left},{right},{x},{}",tween.y(x));
                for (mode,name) in [(SpeedEasingMode::Legacy,"legacy"),(SpeedEasingMode::Modern,"modern")] {
                    let (speed,factor)=speed_segment_tween(mode,2.,8.,Rc::clone(&tween));
                    println!("{name},{id},{left},{right},{x},{}",speed.y(x)*factor);
                }
            }
        }
    }
}
'''
(out/'oracle.rs').write_text(source)
subprocess.run(['rustc','--edition=2021','-Awarnings',str(out/'oracle.rs'),'-o',str(out/'oracle')],check=True)
with (out/'scalar-oracle.csv').open('w') as f: subprocess.run([str(out/'oracle')],stdout=f,check=True)
with (out/'bezier-oracle.csv').open('w') as f: subprocess.run([str(out/'oracle'),'bezier'],stdout=f,check=True)
print('Wrote scalar-oracle.csv and bezier-oracle.csv from upstream Rust')

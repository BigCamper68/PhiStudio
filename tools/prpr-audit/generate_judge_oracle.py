#!/usr/bin/env python3
"""Run upstream Phira judgement loops with inert rendering/audio and fixed line transforms.

Usage: generate_judge_oracle.py /path/to/phira/prpr/src/judge.rs OUTPUT_DIR [--core CORE_RS] [--rustc RUSTC]
Generated Rust embeds GPL-3.0 upstream code; it is a test artifact, never linked into PhiStudio.
The candidate selection, sustain, miss and pre-judge blocks are extracted without rewriting.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('source', type=Path)
p.add_argument('output', type=Path)
p.add_argument('--rustc', default='rustc')
p.add_argument('--core', type=Path, help='prpr/src/core.rs; defaults to core.rs beside judge.rs')
a = p.parse_args()
src = a.source.read_text()
core = (a.core or a.source.with_name('core.rs')).read_text()
width = re.search(r'pub const NOTE_WIDTH_RATIO_BASE:\s*f64\s*=\s*([^;]+);', core)
assert width, 'Use the actual upstream core.rs judgement coefficient'
a.output.mkdir(parents=True, exist_ok=True)
constants = src[src.index('pub const FLICK_SPEED_THRESHOLD:'):src.index('#[derive(Debug, Clone)]')]
tracker = src[src.index('pub struct FlickTracker {'):src.index('#[derive(Debug)]\npub enum JudgeStatus')]
statuses = src[src.index('#[derive(Debug)]\npub enum JudgeStatus'):src.index('#[cfg(not(closed))]')]
statuses = statuses.replace(', Serialize', '')
selection = src[src.index('        // clicks & flicks'):src.index('        for (judgement, line_id, id, diff) in judgements {')]
rust = r'''
use std::{cell::RefCell, collections::HashMap};
macro_rules! debug { ($($t:tt)*) => {}; }
#[derive(Clone,Copy,Debug)] struct Point { x:f32,y:f32 }
type Vector = Point;
impl Point {
 fn new(x:f32,y:f32)->Self {Self{x,y}}
 fn dot(&self,o:&Self)->f32 {self.x*o.x+self.y*o.y}
 fn magnitude(&self)->f32 {(self.x*self.x+self.y*self.y).sqrt()}
 fn normalize(&self)->Self {let d=self.magnitude();Self::new(self.x/d,self.y/d)}
}
impl std::ops::Sub for Point {type Output=Point;fn sub(self,o:Point)->Point {Point::new(self.x-o.x,self.y-o.y)}}
#[derive(Clone,Copy,PartialEq)] enum TouchPhase {Started,Moved,Stationary,Ended}
struct Touch {id:u64,phase:TouchPhase,time:f64}
struct Fixed(f32);
impl Fixed {fn set_time(&mut self,_:f64){} fn now(&self)->f32 {self.0}}
struct Translation(Fixed);
struct Object {translation:Translation}
impl Object {fn set_time(&mut self,_:f64){}}
struct Resource;
struct Sound;
impl Sound {fn play(&mut self,_:&mut Resource){}}
#[derive(Clone,Debug)] enum NoteKind {Click,Hold{end_time:f64},Flick,Drag}
struct Note {time:f64,kind:NoteKind,judge:JudgeStatus,judge_area:f32,object:Object,hitsound:Sound}
struct Line {notes:Vec<Note>,object:Object}
struct Chart {lines:Vec<Line>}
trait NotNanExt {fn not_nan(self)->u64;}
impl NotNanExt for f64 {fn not_nan(self)->u64 {self.to_bits()}}
type Judgements = Vec<(f64,u32,u32,Result<Judgement,bool>)>;
struct Judge {notes:Vec<(Vec<u32>,usize)>,trackers:HashMap<u64,FlickTracker>,key_down_count:u32,
 judgements:RefCell<Judgements>,counts:[u32;4]}
'''
rust += width.group(0) + '\n' + constants + tracker + statuses
rust += r'''
impl Judge {
 fn update(&mut self, chart:&mut Chart,t:f64,x:f32,phase:TouchPhase,flick:bool) {
  const X_DIFF_MAX:f64=0.21/(16./9.)*2.;
  let spd=1.;let keys_down=0;
  self.trackers.insert(0,FlickTracker::new(275,t as f32,Point::new(x,0.)));
  self.trackers.get_mut(&0).unwrap().flicked=flick;
  let touches=vec![Touch{id:0,phase,time:t}];
  let pos=vec![vec![Some(Point::new(x,0.))]];
  let time_of=|touch:&Touch| touch.time;
  let res=&mut Resource;
  let mut judgements=Vec::new();
'''
rust += selection
rust += r'''
  for (judgement,_,_,_) in judgements {self.counts[judgement as usize]+=1;}
  for (line,(idx,st)) in chart.lines.iter().zip(self.notes.iter_mut()) {
   while idx.get(*st).is_some_and(|id| matches!(line.notes[*id as usize].judge,JudgeStatus::Judged)) {*st+=1;}
  }
 }
}
fn obj()->Object {Object{translation:Translation(Fixed(0.))}}
fn main() {
 if std::env::args().nth(1).as_deref()==Some("flick") {
  println!("dt,dx1,dy1,dx2,dy2,flicked");
  for dt in [0.001f32,0.01,0.05,0.1,0.2] {for axis in 0..2 {
   for d1 in [-0.2f32,0.,0.001,0.01,0.05,0.2] {for d2 in [-0.2f32,0.,0.001,0.01,0.05,0.2] {
    let (dx1,dy1,dx2,dy2)=if axis==0 {(d1,0.,d2,0.)}else{(0.,d1,0.,d2)};
    let mut f=FlickTracker::new(275,0.,Point::new(0.,0.));
    f.push(dt,Point::new(dx1,dy1));f.push(dt*2.,Point::new(dx1+dx2,dy1+dy2));
    println!("{dt},{dx1},{dy1},{dx2},{dy2},{}",f.flicked);
   }}
  }}return;
 }
 println!("kind,offset_ms,x,flick,perfect,good,bad,miss");
 for kind in 0..4 {for ms in (-240..=240).step_by(5) {
  for x in [-0.25f32,-0.236,-0.23,-0.12,0.,0.12,0.23,0.236,0.25] {for flick in [false,true] {
   if flick && kind!=2 {continue;}
   let kind_enum=match kind {0=>NoteKind::Click,1=>NoteKind::Hold{end_time:2.},2=>NoteKind::Flick,_=>NoteKind::Drag};
   let note=Note{time:1.,kind:kind_enum,judge:JudgeStatus::NotJudged,judge_area:1.,object:obj(),hitsound:Sound};
   let mut chart=Chart{lines:vec![Line{notes:vec![note],object:obj()}]};
   let mut judge=Judge{notes:vec![(vec![0],0)],trackers:HashMap::new(),key_down_count:0,
    judgements:RefCell::new(Vec::new()),counts:[0;4]};
   judge.update(&mut chart,1.+ms as f64/1000.,x,if flick {TouchPhase::Moved}else{TouchPhase::Started},flick);
   judge.update(&mut chart,2.,x,TouchPhase::Stationary,false);
   let c=judge.counts;
   println!("{kind},{ms},{x},{flick},{},{},{},{}",c[0],c[1],c[2],c[3]);
  }}
 }}
}
'''
rs = a.output / 'judge-oracle.rs'
rs.write_text(rust)
subprocess.run([a.rustc, '--edition=2021', '-Awarnings', '-C', 'link-arg=-fuse-ld=bfd',
                str(rs), '-o', str(a.output / 'judge-oracle')], check=True)
for name, args in [('judge-oracle.csv', []), ('flick-oracle.csv', ['flick'])]:
    (a.output / name).write_bytes(subprocess.check_output([str(a.output / 'judge-oracle'), *args]))
manifest = {'reference': 'TeamFlos/phira 1559e58bdfc5b0e57f3e864d307d33e666d1e8f6',
            'source_sha256': hashlib.sha256(src.encode()).hexdigest(),
            'core_sha256': hashlib.sha256(core.encode()).hexdigest(),
            'note_width_ratio_base': float(width.group(1)),
            'generated': {f.name: {'sha256': hashlib.sha256(f.read_bytes()).hexdigest(),
                                   'cases': len(f.read_text().splitlines()) - 1}
                          for f in a.output.glob('*.csv')},
            'scope': 'Actual upstream candidate selection, sustain, miss and pre-judge loops; '
                     'actual FlickTracker. Fixed line-local X, one note, speed 1. '
                     'Rendering, sound, keyboard and OS event delivery are outside this corpus.'}
(a.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))

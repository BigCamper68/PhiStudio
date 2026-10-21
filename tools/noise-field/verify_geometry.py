"""Independent float32 implementation of the published PreviewBlockControl equations.
Compares area IDs, lifecycle, readiness, opacity, anchored geometry and rotation.
"""
from pathlib import Path
import argparse,bisect,ctypes,json,math,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('--chart',type=Path,required=True);p.add_argument('--host',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
a.output.mkdir(exist_ok=True,parents=True);data=a.output/'geometry.json'
subprocess.run(['java','-Xmx1g','-cp',(a.host/'classpath.txt').read_text().strip(),'com.xpe.mobile.preview.NoiseFieldGeometryProbe',str(a.chart),str(data)],check=True)
with zipfile.ZipFile(a.chart) as archive:fields=json.loads(archive.read('2856450826125632.json'))['blockAreaList']
f=lambda v:ctypes.c_float(v).value
power=lambda x,n:f(math.pow(x,n))
tables=[]
for kind in range(15):
 row=[]
 for i in range(101):
  t=f(i/100);n=(kind-1)//3+2;direction=(kind-1)%3
  if kind==0:value=t
  elif kind==13:value=0
  elif kind==14:value=1
  elif direction==0:value=power(t,n)
  elif direction==1:value=f(1-power(f(1-t),n))
  elif t<.5:value=f(power(f(2*t),n)/2)
  else:value=f(1-f(power(f(2-f(2*t)),n)/2))
  row.append(value)
 tables.append(row)
def ease(kind,t):
 q=f(max(0,min(1,t))*100);i=int(q)
 if i>=100:return tables[kind][100]
 return f(tables[kind][i]+f(f(q-i)*f(tables[kind][i+1]-tables[kind][i])))
def sample_track(keys,time,axis):
 i=bisect.bisect_right([f(k['time']) for k in keys],time)-1
 if i<0:return i,None
 key=keys[i]
 def value(k):return f(k['rotation'] if axis=='rotation' else k['scale'][axis] if 'scale' in k else k['endPosition'][axis])
 start=value(key)
 if i+1==len(keys):return i,start
 end=keys[i+1];progress=f(f(time-f(key['time']))/f(f(end['time'])-f(key['time'])))
 kind=key.get('easeType',0) if axis=='rotation' else key.get('easeType'+axis.upper(),0)
 return i,f(start+f(f(value(end)-start)*ease(kind,progress)))
def expected(area,t,aspect):
 if t<f(area['appearTime']) or t>=f(area['disappearTime']):return None
 sw=f(10*aspect);sh=10
 def point(p):return f(f(f(p['x'])-.5)*sw),f(f(f(p['y'])-.5)*sh)
 bl=point(area['bottomLeftPercentage']);tr=point(area['topRightPercentage'])
 base=(f(f(bl[0]+tr[0])*.5),f(f(bl[1]+tr[1])*.5));center=list(base)
 def div(a,b):return 1 if b==0 else f(a/b)
 keys=area['scaleEvents'];si,sx=sample_track(keys,t,'x');_,sy=sample_track(keys,t,'y');sx=1 if sx is None else sx;sy=1 if sy is None else sy
 for k in range(si+1):
  if k+1>=len(keys):break
  key,next=keys[k:k+2];anchor=point(key['anchor'])
  for axis,name,sampled in [(0,'x',sx),(1,'y',sy)]:
   ratio=div(sampled if k==si else f(next['scale'][name]),f(key['scale'][name]))
   center[axis]=f(anchor[axis]+f(f(center[axis]-anchor[axis])*ratio))
 keys=area['rotateEvents'];ri,rotation=sample_track(keys,t,'rotation');rotation=0 if rotation is None else rotation
 for k in range(ri+1):
  if k+1>=len(keys):break
  key,next=keys[k:k+2];anchor=point(key['anchor']);delta=f((rotation if k==ri else f(next['rotation']))-f(key['rotation']))
  r=f(delta*f(.017453292));c=f(math.cos(r));s=f(math.sin(r));dx=f(center[0]-anchor[0]);dy=f(center[1]-anchor[1])
  center=[f(anchor[0]+f(f(dx*c)-f(dy*s))),f(anchor[1]+f(f(dx*s)+f(dy*c)))]
 keys=area['moveEvents'];mi,mx=sample_track(keys,t,'x');_,my=sample_track(keys,t,'y')
 if mi>=0:
  move=(f(f(mx-.5)*sw),f(f(my-.5)*sh));center=[f(center[i]+f(move[i]-base[i])) for i in [0,1]]
 width=abs(f(f(tr[0]-bl[0])*sx));height=abs(f(f(tr[1]-bl[1])*sy))
 if width==0 or height==0:return None
 active=t>=f(area['enableTime']) and t<f(area['disableTime']);ready=not active and t<f(area['enableTime']) and t>=f(f(area['enableTime'])-.5)
 fade=not(f(area['appearTime'])>=f(area['enableTime']) and f(area['appearTime'])<f(area['disableTime']))
 opacity=max(0,min(1,f(f(t-f(area['appearTime']))/.5))) if not active and fade else 1
 return [f(f(center[0]*2)/sw),f(f(center[1]*2)/sw),f(f(width*2)/sw),f(f(height*2)/sw),rotation,active,ready,area['isSubtract'],opacity]
rows=json.loads(data.read_text());checked=0;maximum=0
for aspect,time,zones in rows:
 aspect=f(aspect);time=f(time);actual={z[0]:z[1:] for z in zones};reference={}
 for i,area in enumerate(fields):
  value=expected(area,time,aspect)
  if value is not None:reference[i]=value
 assert actual.keys()==reference.keys(),('visible area IDs',aspect,time,actual.keys(),reference.keys())
 for i,value in reference.items():
  for k,(want,got) in enumerate(zip(value,actual[i])):
   if isinstance(want,bool):assert want==got,(aspect,time,i,k,want,got)
   else:
    error=abs(want-got);maximum=max(maximum,error)
    assert error<=max(2e-5,abs(want)*1e-6),(aspect,time,i,k,want,got,error)
  checked+=1
result={'sampled_frames':len(rows),'compared_visible_areas':checked,'max_absolute_float_difference':maximum,'source':'Published PreviewBlockControl equations; independent Python float32 world-space transforms and easing tables. Not a captured original game execution.'}
(a.output/'geometry-comparison.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

#!/usr/bin/env python3
"""Fast offline regression bench for the v1.1 fixed-grid Omron reader."""
from __future__ import annotations
import argparse
from pathlib import Path
import cv2
import numpy as np

DIGIT_SEGS={0:set('abcdef'),1:set('bc'),2:set('abdeg'),3:set('abcdg'),4:set('bcfg'),5:set('acdfg'),6:set('acdefg'),7:set('abcf'),8:set('abcdefg'),9:set('abcdfg')}
RECTS={'sys':(100,190,420,265),'dia':(100,450,420,270),'pulse':(170,705,350,195)}
RIGHTS={'sys':[59,209,359],'dia':[59,209,359],'pulse':[99,195,291]}
EXPECTED={'reference_136_90_61.png':(136,90,61),'reference_111_75_56.png':(111,75,56),'auto_geometry_136_90_61.png':(136,90,61),'auto_geometry_111_75_56.png':(111,75,56)}

def robust_rgb_binary(img):
    h,w=img.shape[:2]; rgb=cv2.cvtColor(img,cv2.COLOR_BGR2RGB).astype(np.float32)
    radius=min(45,max(9,min(w//22,h//7))); k=2*radius+1
    ds=[]
    for c in range(3):
        bg=cv2.boxFilter(rgb[:,:,c],-1,(k,k),normalize=True,borderType=cv2.BORDER_REPLICATE)
        ds.append(np.maximum(bg-rgb[:,:,c],0))
    dark=np.minimum(np.minimum(ds[0],ds[1]),ds[2]); u=np.clip(dark,0,255).astype(np.uint8)
    t,_=cv2.threshold(u,0,255,cv2.THRESH_BINARY+cv2.THRESH_OTSU); t=max(8,min(int(t),80))
    bw=(u>=t).astype(np.uint8)*255
    n=cv2.filter2D((bw>0).astype(np.uint8),-1,np.ones((3,3),np.uint8))-((bw>0).astype(np.uint8))
    bw[(bw>0)&(n<=1)]=0
    return bw

def segment_polys(kind):
    if kind in ('sys','dia'): top,mid,bot,length,thick=30,132,234,96,26
    else: top,mid,bot,length,thick=42,108,174,62,17
    r=0.; lc=r-length+thick*.48
    def hp(cy):
        xr=r+thick*.35; xl=r-length; hh=thick*.42; b=thick*.40
        return np.array([[xl+b,cy-hh],[xr-b,cy-hh],[xr,cy],[xr-b,cy+hh],[xl+b,cy+hh],[xl,cy]],np.float32)
    def vp(cx,y0,y1):
        hh=thick*.42; b=thick*.40
        return np.array([[cx,y0],[cx+hh,y0+b],[cx+hh,y1-b],[cx,y1],[cx-hh,y1-b],[cx-hh,y0+b]],np.float32)
    return {'a':hp(top),'b':vp(r,top+thick*.25,mid-thick*.25),'c':vp(r,mid+thick*.25,bot-thick*.25),'d':hp(bot),'e':vp(lc,mid+thick*.25,bot-thick*.25),'f':vp(lc,top+thick*.25,mid-thick*.25),'g':hp(mid)}

def build_samples(kind):
    pp=segment_polys(kind); out={}; step=2 if kind=='pulse' else 3; edge_step=3 if kind=='pulse' else 4
    for s,p in pp.items():
        xi=range(int(np.floor(p[:,0].min())),int(np.ceil(p[:,0].max()))+1,step)
        yi=range(int(np.floor(p[:,1].min())),int(np.ceil(p[:,1].max()))+1,step)
        interior=[]
        pc=p.reshape((-1,1,2))
        for y in yi:
            for x in xi:
                if cv2.pointPolygonTest(pc,(float(x),float(y)),True)>=2.0: interior.append((x,y))
        outline=[]
        for i in range(len(p)):
            a,b=p[i],p[(i+1)%len(p)]; L=float(np.linalg.norm(b-a)); n=max(1,int(L/edge_step))
            for j in range(n+1):
                q=a+(b-a)*(j/n); outline.append((int(round(q[0])),int(round(q[1]))))
        out[s]=(np.unique(np.asarray(interior,dtype=np.int32),axis=0),np.unique(np.asarray(outline,dtype=np.int32),axis=0))
    return out
SAMPLES={k:build_samples(k) for k in ('sys','dia','pulse')}

def contour_dt(bw):
    fg=(bw>0).astype(np.uint8); er=cv2.erode(fg,np.ones((3,3),np.uint8),iterations=1); bd=((fg-er)>0).astype(np.uint8)
    return cv2.distanceTransform((bd==0).astype(np.uint8),cv2.DIST_L2,3)

def stats(bw,dt,kind,right,dx,dy):
    H,W=bw.shape; occ={}; cp={}
    for s,(inside,outline) in SAMPLES[kind].items():
        x=inside[:,0]+right+dx; y=inside[:,1]+dy; valid=(x>=0)&(x<W)&(y>=0)&(y<H)
        occ[s]=float(np.mean(bw[y[valid],x[valid]]>0)) if np.any(valid) else 0.0
        x=outline[:,0]+right+dx; y=outline[:,1]+dy; valid=(x>=0)&(x<W)&(y>=0)&(y<H)
        vals=np.ones(len(x),dtype=np.float32)
        vals[valid]=np.minimum(dt[y[valid],x[valid]],9.0)/9.0
        cp[s]=float(vals.mean())
    return occ,cp

def classify(bw,dt,kind,right,dx,dy):
    occ,cp=stats(bw,dt,kind,right,dx,dy); allseg=set('abcdefg')
    cand=[(min(1.2,2.0*np.mean(list(occ.values()))),None)]
    for d,active in DIGIT_SEGS.items():
        present=np.mean([.70*(1-occ[s])+.30*cp[s] for s in active]); absent=np.mean([occ[s] for s in allseg-active]) if allseg-active else 0
        cand.append((present+.55*absent,d))
    return sorted(cand)

def read_row(img,kind):
    bw=robust_rgb_binary(img); dt=contour_dt(bw)
    # The photo guide fixes the pitch.  We only search one common translation
    # for the whole row.  Ranges intentionally absorb a modest framing error.
    xr=range(-50,41,4) if kind!='pulse' else range(-42,35,4)
    yr=range(-20,21,4) if kind!='pulse' else range(-26,19,4)
    best=None
    for dx in xr:
        for dy in yr:
            cells=[classify(bw,dt,kind,r,dx,dy) for r in RIGHTS[kind]]
            # Omron suppresses leading zeroes: every displayed field contains
            # either 2 or 3 right-aligned digits.  This known spacing is part
            # of the recognizer, not a post-hoc medical heuristic.
            for ndigits in (2,3):
                digs=[]; cost=0.0
                for i,cands in enumerate(cells):
                    if ndigits==2 and i==0:
                        cand=next(c for c in cands if c[1] is None)
                    else:
                        first_sig = (ndigits==2 and i==1) or (ndigits==3 and i==0)
                        allowed=[c for c in cands if c[1] is not None and (not first_sig or c[1]!=0)]
                        cand=allowed[0]
                    cost+=cand[0]; digs.append(cand[1])
                cost+=.00025*(dx*dx+dy*dy)
                value=0
                for d in digs:
                    if d is not None: value=10*value+d
                plausible=(20<=value<=300) if kind=='sys' else ((10<=value<=300) if kind=='dia' else (10<=value<=300))
                if not plausible: continue
                if best is None or cost<best[0]: best=(cost,digs,dx,dy)
    if best is None: return None
    return int(''.join(str(d) for d in best[1] if d is not None))

def read_screen(screen):
    ans=[]
    for k in ('sys','dia','pulse'):
        x,y,w,h=RECTS[k]; ans.append(read_row(screen[y:y+h,x:x+w],k))
    return tuple(ans)

def perturbations(img):
    H,W=img.shape[:2]
    yield 'original',img
    yield 'dark',cv2.convertScaleAbs(img,alpha=.82,beta=0)
    yield 'bright',cv2.convertScaleAbs(img,alpha=1.18,beta=0)
    yield 'low-contrast',cv2.convertScaleAbs(img,alpha=.82,beta=22)
    yield 'blur-0.8',cv2.GaussianBlur(img,(0,0),.8)
    yield 'blur-1.3',cv2.GaussianBlur(img,(0,0),1.3)
    for dx,dy in [(12,0),(-12,0),(0,8),(0,-8)]:
        M=np.float32([[1,0,dx],[0,1,dy]]); yield f'shift-{dx}-{dy}',cv2.warpAffine(img,M,(W,H),borderMode=cv2.BORDER_REPLICATE)
    src=np.float32([[0,0],[W-1,0],[W-1,H-1],[0,H-1]]); dst=np.float32([[7,4],[W-9,0],[W-2,H-7],[4,H-1]])
    yield 'perspective-small',cv2.warpPerspective(img,cv2.getPerspectiveTransform(src,dst),(W,H),borderMode=cv2.BORDER_REPLICATE)
    glare=img.astype(np.float32); overlay=np.zeros_like(glare); overlay[:,:,2]=210; overlay[:,:,1]=75
    mask=np.zeros((H,W),np.float32); cv2.rectangle(mask,(int(.37*W),int(.60*H)),(int(.48*W),H-1),1,-1); mask=cv2.GaussianBlur(mask,(0,0),18)[:,:,None]
    yield 'warm-glare',np.clip(glare*(1-.32*mask)+overlay*(.32*mask),0,255).astype(np.uint8)

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--samples',type=Path,default=Path(__file__).resolve().parents[1]/'samples'); args=ap.parse_args()
    fails=[]; total=0
    for name,expected in EXPECTED.items():
        img=cv2.imread(str(args.samples/name))
        if img is None: raise SystemExit(f'missing {args.samples/name}')
        for variant,frame in perturbations(img):
            total+=1; got=read_screen(frame); ok=got==expected
            print(f'{name:28s} {variant:18s} got={got} expected={expected} {"OK" if ok else "FAIL"}')
            if not ok: fails.append((name,variant,got,expected))
    print(f'\n{total-len(fails)}/{total} passed')
    if fails:
        for f in fails: print('FAIL',f)
        raise SystemExit(1)
if __name__=='__main__': main()

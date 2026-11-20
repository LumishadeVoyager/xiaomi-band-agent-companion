"""Small, offline raster assets from original geometric artwork. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'powerprofile-wearable/src/common'
ROOT.mkdir(parents=True, exist_ok=True)
(ROOT / 'icons').mkdir(exist_ok=True)
SCALE = 4

def glyph(name, color):
    image = Image.new('RGBA', (96*SCALE, 96*SCALE))
    draw = ImageDraw.Draw(image)
    def line(points, width=6):
        points = [(x*SCALE,y*SCALE) for x,y in points]
        draw.line(points, fill=color, width=width*SCALE, joint='curve')
        r=width*SCALE/2
        for x,y in (points[0],points[-1]): draw.ellipse((x-r,y-r,x+r,y+r),fill=color)
    def box(coords, radius=8): draw.rounded_rectangle(tuple(v*SCALE for v in coords), radius=radius*SCALE, outline=color, width=6*SCALE)
    if name.startswith('power'):
        draw.arc((23*SCALE,23*SCALE,73*SCALE,73*SCALE),-55,235,fill=color,width=6*SCALE)
        line([(48,16),(48,48)],6)
    elif name=='monitor':
        box((15,20,81,65)); line([(48,66),(48,78)]);line([(33,79),(63,79)])
    elif name=='energy':
        line([(53,13),(27,51),(47,51),(41,83),(70,42),(50,42),(53,13)],5)
    elif name=='game':
        box((10,27,86,72),17);line([(25,49),(43,49)],5);line([(34,40),(34,58)],5)
        for x,y in [(65,43),(73,55)]:draw.ellipse(((x-3)*SCALE,(y-3)*SCALE,(x+3)*SCALE,(y+3)*SCALE),fill=color)
    elif name=='ai':
        box((27,27,69,69),9)
        for a in [36,48,60]:
            for points in [[(a,15),(a,24)],[(a,72),(a,81)],[(15,a),(24,a)],[(72,a),(81,a)]]:line(points,4)
        draw.ellipse((40*SCALE,40*SCALE,56*SCALE,56*SCALE),fill=color)
    elif name=='refresh':
        draw.arc((19*SCALE,19*SCALE,77*SCALE,77*SCALE),45,320,fill=color,width=6*SCALE)
        line([(77,22),(77,42),(57,42)],5)
    elif name=='update':
        line([(48,66),(48,20)],6);line([(29,39),(48,20),(67,39)],6);line([(24,73),(24,80),(72,80),(72,73)],5)
    image.resize((64,64),Image.Resampling.LANCZOS).save(ROOT/'icons'/f'{name}.png')

for name,color in [('monitor','#79B5FF'),('energy','#AFE581'),('game','#FFB56E'),('ai','#BBACFF'),('refresh','#DCE1E8'),('update','#DCE1E8'),('power-on','#9CE6B1'),('power-off','#98A1AF')]:glyph(name,color)

# Open power ring and its stem remain legible at 24 px in the app list.
image=Image.new('RGBA',(512,512));d=ImageDraw.Draw(image)
d.rounded_rectangle((0,0,512,512),radius=150,fill='#182B3B')
d.arc((112,112,400,400),-57,237,fill='#9EDCFF',width=39)
d.rounded_rectangle((236,87,276,259),radius=20,fill='#B4F2C1')
image.resize((64,64),Image.Resampling.LANCZOS).save(ROOT/'logo.png')
print('Generated compact PowerProfile logo and six original offline icons')

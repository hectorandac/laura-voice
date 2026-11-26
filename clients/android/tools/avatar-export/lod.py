#!/usr/bin/env python3
"""Adds the watch LOD measure to the exported model: items[i].wpx = the most pixels item i covers in any frame of
any state or reaction at the watch's 480 px (ID render). The native watch model keeps body cubes above a threshold.
  lod.py <laura-model.json> <lod.json> <out.json>"""
import json
import sys

model = json.load(open(sys.argv[1]))
pix = json.load(open(sys.argv[2]))['pix']
assert len(pix) == len(model['items'])
for it, p in zip(model['items'], pix):
    it['wpx'] = int(p)
model['lodPx'] = 480
json.dump(model, open(sys.argv[3], 'w'), separators=(',', ':'))
body = [it['wpx'] for it in model['items'] if it['k'] == 0]
for t in (0, 1, 10, 40, 100, 200, 400):
    print(f'body items with > {t} px at 480: {sum(1 for p in body if p > t)} of {len(body)}')

#!/usr/bin/env python3
"""Exercise the normal anime-mode UI against the local test add-on; no app-state injection."""
import argparse
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote

p = argparse.ArgumentParser()
p.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
p.add_argument('--package', default='com.vyrk.tv')
p.add_argument('--out', type=Path, required=True)
p.add_argument('--addon', default='http://10.0.2.2:7000/anime-mode/manifest.json')
p.add_argument('--fresh', action='store_true')
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output([a.adb, *args], text=True, encoding='utf-8', errors='replace')

def key(k, pause=.4):
    adb('shell', 'input', 'keyevent', 'KEYCODE_'+k)
    time.sleep(pause)

def focus():
    key('SHIFT_LEFT', .1)
    lines=adb('logcat', '-d', '-s', 'VyrkFocus').splitlines()
    return lines[-1] if lines else ''

def wait_focus(label, seconds=35):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if re.search(label, focus()): return
        time.sleep(.3)
    raise AssertionError('Focus timeout: '+label+'; '+focus())

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/anime-mode.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/anime-mode.xml')).iter('node'))

def click(text):
    tab(0)
    key('DPAD_DOWN')
    wait_focus('browse:mode',5)
    if not text.startswith('Switch to '):
        for _ in range(18):
            if 'browse:genre:'+text in focus(): break
            key('DPAD_RIGHT')
        wait_focus('browse:genre:'+text,5)
    key('DPAD_CENTER',1)

def capture(name):
    (a.out/(name+'.png')).write_bytes(subprocess.check_output([a.adb,'exec-out','screencap','-p']))
    ns=nodes()
    (a.out/(name+'.xml')).write_text(ET.tostring(ET.fromstring(adb('shell','cat','/sdcard/anime-mode.xml')),encoding='unicode'),encoding='utf-8')
    return ' '.join(n.get('text','')+' '+n.get('content-desc','') for n in ns)

def tab(index):
    for _ in range(5):
        if 'tab:' in focus() or 'nav:' in focus(): break
        key('DPAD_UP')
    for _ in range(9):
        if 'tab:home' in focus(): break
        key('DPAD_LEFT')
    wait_focus('tab:home',5)
    for _ in range(index): key('DPAD_RIGHT')
    time.sleep(1)

if a.fresh:
    adb('shell','am','start','-n',a.package+'/com.vyrk.tv.MainActivity')
    wait_focus('welcome:guest'); key('DPAD_CENTER',3)
adb('shell','am','start','-a','android.intent.action.VIEW','-d',
    "'vyrk://install?url="+quote(a.addon,safe='')+"'",'-p',a.package)
wait_focus('install:confirm'); key('DPAD_CENTER',3)
click('Switch to Anime')
time.sleep(3)
text=capture('anime-home')
assert 'Switch to Regular' in text and 'Anime Fixture' in text
assert 'Regular Fixture Show' not in text
tab(1)
text=capture('anime-movies')
assert 'Anime Fixture Film' in text and 'Anime Fixture Action' not in text and 'Anime Fixture Comedy' not in text
tab(2)
text=capture('anime-series')
assert 'Anime Fixture Film' not in text and 'Anime Fixture Action' in text
click('Action')
time.sleep(2)
text=capture('anime-action')
assert 'Anime Fixture Action' in text and 'Anime Fixture Comedy' not in text
# Genre chips remain focused while data refreshes. Select Comedy using the TV remote.
wait_focus('browse:genre:Action',5)
key('DPAD_RIGHT'); wait_focus('browse:genre:Adventure',5)
key('DPAD_RIGHT'); wait_focus('browse:genre:Comedy',5)
key('DPAD_CENTER',2)
text=capture('anime-comedy')
assert 'Anime Fixture Comedy' in text and 'Anime Fixture Action' not in text
for _ in range(13): key('DPAD_RIGHT', .2)
wait_focus('browse:genre:Thriller',5)
key('DPAD_CENTER',2)
text=capture('anime-empty-genre')
assert 'No matching titles' in text and 'Anime Fixture Comedy' not in text
for _ in range(13): key('DPAD_LEFT', .2)
wait_focus('browse:genre:Comedy',5)
key('DPAD_CENTER',2)
adb('shell','am','force-stop',a.package)
adb('shell','am','start','-n',a.package+'/com.vyrk.tv.MainActivity')
time.sleep(5)
text=capture('anime-reopened')
assert 'Switch to Regular' in text and 'Anime Fixture Action' not in text
tab(2)
text=capture('anime-saved-genre')
assert 'Anime Fixture Comedy' in text and 'Anime Fixture Action' not in text
click('Switch to Regular')
time.sleep(2)
assert 'Switch to Anime' in capture('regular-restored')
(a.out/'results.json').write_text(json.dumps({'passed':True,'checks':['anime home','movie/series separation',
    'genre filtering','remote genre navigation','saved mode and genre','regular view restored']},indent=2))
print('PASS: normal anime-mode UI and profile persistence')

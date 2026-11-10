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
    return subprocess.check_output([a.adb, *args], text=True, encoding='utf-8', errors='replace', timeout=45)

# VYRK's focus trace is off unless switched on.
adb('shell', 'setprop', 'log.tag.VyrkFocus', 'DEBUG')

def record():
    lines=adb('logcat', '-d', '-s', 'VyrkFocus').splitlines()
    return lines[-1] if lines else ''

def focus():
    before=record()
    adb('shell','input','keyevent','KEYCODE_SHIFT_LEFT')
    end=time.monotonic()+8
    while time.monotonic()<end:
        current=record()
        if current != before and 'KEYCODE_SHIFT_LEFT -> ' in current: return current
        time.sleep(.15)
    raise AssertionError('No fresh focus report')

def key(k, pause=.4):
    adb('shell', 'input', 'keyevent', 'KEYCODE_'+k)
    time.sleep(pause)
    return focus()

def wait_focus(label, seconds=35):
    end=time.monotonic()+seconds
    last=''
    while time.monotonic()<end:
        try: last=focus()
        except (AssertionError,subprocess.SubprocessError): last='No fresh focus report'
        if re.search(label, last): return
        time.sleep(.3)
    raise AssertionError('Focus timeout: '+label+'; '+last)

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/anime-mode.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/anime-mode.xml')).iter('node'))

def click(text):
    tab(0)
    if text.startswith('Switch to '):
        for _ in range(10):
            if 'nav:anime' in focus(): break
            key('DPAD_RIGHT')
        wait_focus('nav:anime',5)
    else:
        key('DPAD_DOWN'); wait_focus('browse:genre:',5)
        for _ in range(18):
            if 'browse:genre:all' in focus(): break
            key('DPAD_LEFT')
        for _ in range(18):
            if 'browse:genre:'+text in focus(): break
            key('DPAD_RIGHT')
        wait_focus('browse:genre:'+text,5)
    key('DPAD_CENTER',1)

def capture(name):
    (a.out/(name+'.png')).write_bytes(subprocess.check_output([a.adb,'exec-out','screencap','-p'], timeout=45))
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
assert 'browse:genre' not in adb('logcat', '-d', '-s', 'VyrkFocus')
tab(1)
for _ in range(4):
    if 'poster:' in focus(): break
    key('DPAD_DOWN')
wait_focus('poster:',5)
# Load more (or See all at the end) sits after the posters; Load more brings in every film page.
for _ in range(3):
    for _ in range(6):
        if re.search(r'row:.*:more', focus()): break
        key('DPAD_RIGHT')
    wait_focus(r'row:.*:more',5)
    text=capture('anime-load-more')
    if 'Anime Fixture Next Film' in text: break
    key('DPAD_CENTER',2)
assert 'Anime Fixture Drama Film' in text and 'Anime Fixture Next Film' in text
adb('shell','am','force-stop',a.package)
adb('shell','am','start','-n',a.package+'/com.vyrk.tv.MainActivity')
time.sleep(5)
text=capture('anime-reopened')
assert 'Switch to Regular' in text
click('Switch to Regular')
time.sleep(2)
assert 'Switch to Anime' in capture('regular-restored')
# Normal pages keep their focus memory and tab navigation.
tab(0); key('DPAD_DOWN')
for _ in range(5):
    if 'poster:' in focus(): break
    key('DPAD_DOWN')
wait_focus('poster:',10)
key('DPAD_DOWN')
wait_focus('poster:',10)
key('DPAD_RIGHT'); key('DPAD_RIGHT')
saved=focus().split(' -> ',1)[-1].strip()
assert saved.startswith('poster:') and saved.endswith('#2')
key('BACK'); wait_focus('tab:home',10)
key('DPAD_RIGHT'); wait_focus('tab:movies',10)
key('DPAD_LEFT'); wait_focus('tab:home',10)
key('DPAD_DOWN'); wait_focus(re.escape(saved)+r'$',15)
capture('regular-focus-restored')
tab(0)
key('DPAD_RIGHT'); wait_focus('tab:movies',5)
key('DPAD_RIGHT'); wait_focus('tab:series',5)
# Discover has its own type/catalogue/genre selectors and follows Anime mode.
click('Switch to Anime')
tab(3); key('DPAD_DOWN'); wait_focus('discover:type',10)

def discover_pick(which, target):
    # Return to the filter row from the grid, then start at Content type.
    tab(3); key('DPAD_DOWN'); wait_focus('discover:type',10)
    for _ in range(which): key('DPAD_RIGHT')
    key('DPAD_CENTER'); wait_focus('discover:option:',10)
    for _ in range(30):
        adb('shell','uiautomator','dump','/sdcard/discover.xml')
        root=ET.fromstring(adb('shell','cat','/sdcard/discover.xml'))
        focused=[n for n in root.iter('node') if n.get('focused')=='true']
        text=' '.join(n.get('text','')+' '+n.get('content-desc','') for f in focused for n in f.iter('node'))
        if target in text:
            key('DPAD_CENTER',2); return
        key('DPAD_DOWN')
    raise AssertionError('Discover option not reached: '+target)

discover_pick(1,'Anime fixture films')
text=capture('discover-movies')
assert 'Anime Fixture Film' in text and 'Anime Fixture Action' not in text
# Series changes catalogue selection, and the new selector remains usable.
discover_pick(0,'Series')
discover_pick(1,'Anime fixture shows')
discover_pick(2,'Action')
text=capture('discover-series-action')
assert 'Anime Fixture Action' in text and 'Anime Fixture Comedy' not in text
discover_pick(2,'Comedy')
text=capture('discover-series-comedy')
assert 'Anime Fixture Comedy' in text and 'Anime Fixture Action' not in text
# Back cancels a selector, retains the chosen genre, and returns to its button.
tab(3); key('DPAD_DOWN'); key('DPAD_RIGHT'); key('DPAD_RIGHT'); key('DPAD_CENTER')
wait_focus('discover:option:',5); key('BACK'); wait_focus('discover:genre',5)
click('Switch to Regular')
(a.out/'results.json').write_text(json.dumps({'passed':True,'checks':['anime home','movie/series separation',
    'no genre bar','saved mode','regular view restored',
    'normal poster focus restored','single-key tab navigation','Discover type and catalogue','Discover genres','selector cancellation']},indent=2))
print('PASS: normal anime-mode UI and profile persistence')

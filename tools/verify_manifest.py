#!/usr/bin/env python3
"""Fail if any merged app manifest requests networking or broad storage permission.

Checked for every merged manifest AGP produced - debug, perf and release, both flavours -
because a release build merges the same library manifests and must come out identical on these
points. Copied from Still and widened for a studio: the microphone is allowed (it is what a
recorder is), the camera and location never.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

paths = [p for p in Path('app/build/intermediates').glob('**/AndroidManifest.xml')
         if 'merged_manifests' in str(p) and 'androidtest' not in str(p).lower()]
if not paths:
    sys.exit('No merged app manifest; run assembleDebug first.')

A = '{http://schemas.android.com/apk/res/android}'
FORBIDDEN = {
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO', 'android.permission.READ_MEDIA_AUDIO',
    'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.CAMERA', 'android.permission.READ_CONTACTS', 'android.permission.READ_PHONE_STATE',
}
for p in paths:
    root = ET.parse(p).getroot()
    declared = {e.get(A + 'name') for e in root.findall('uses-permission')}
    if FORBIDDEN & declared:
        sys.exit(f'Forbidden permissions in {p}: {sorted(FORBIDDEN & declared)}')
    app = root.find('application')
    if app.get(A + 'allowBackup') != 'false':
        sys.exit(f'{p}: private working copies must not be backed up.')
    if app.get(A + 'dataExtractionRules') != '@xml/data_extraction_rules':
        sys.exit(f'{p}: device-transfer exclusions must be declared.')
    for provider in app.findall('provider'):
        name = provider.get(A + 'name')
        if name == 'androidx.core.content.FileProvider' and provider.get(A + 'exported') != 'false':
            sys.exit(f'{p}: FileProvider must not be exported.')
        if any(m.get(A + 'name') == 'androidx.emoji2.text.EmojiCompatInitializer' for m in provider.findall('meta-data')):
            sys.exit(f'{p}: automatic emoji-font downloads must remain disabled.')

rules = ET.parse('app/src/main/res/xml/data_extraction_rules.xml').getroot()
for mode in ('cloud-backup', 'device-transfer'):
    excluded = {e.get('domain') for e in rules.find(mode).findall('exclude')}
    if not {'root', 'file', 'database', 'sharedpref'}.issubset(excluded):
        sys.exit(f'Missing private-data exclusions in {mode}')
print(f'Privacy manifest check passed ({len(paths)} merged manifests); backup/transfer exclusions verified.')

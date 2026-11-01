"""Cross-platform source/package checks. This does NOT replace xcodebuild on Mac."""
import argparse
import hashlib
import json
import plistlib
import re
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
arguments = argparse.ArgumentParser()
arguments.add_argument('--parser-deps', type=Path, help='Optional isolated Python dependency directory')
args = arguments.parse_args()
if args.parser_deps: sys.path.insert(0, str(args.parser_deps.resolve()))
from openstep_parser import OpenStepDecoder
from tree_sitter import Language, Parser
import tree_sitter_swift

checks = []
def check(condition, message):
    if not condition: raise AssertionError(message)
    checks.append(message)

parser = Parser(Language(tree_sitter_swift.language()))
swift_files = sorted(p for p in ROOT.rglob('*.swift') if not any(part in ('build','dist') for part in p.relative_to(ROOT).parts))
for path in swift_files:
    source = path.read_bytes()
    # Grammar 0.7.4 falsely inserts a missing ! for Swift's valid empty tuple
    # literal in these two call contexts. Normalize ONLY those parser inputs;
    # source/package bytes remain unchanged. Native Swift is the final authority.
    grammar_input = source.replace(b'.success(())', b'.success(Void())').replace(b'value: ()', b'value: Void()')
    tree = parser.parse(grammar_input)
    errors = []
    pending = [tree.root_node]
    while pending:
        node = pending.pop()
        if node.type == 'ERROR' or node.is_missing: errors.append((node.start_point, node.type))
        pending.extend(node.children)
    check(not errors, f'Swift syntax parse: {path.relative_to(ROOT)} {errors}')

plists = {p.name: plistlib.loads(p.read_bytes()) for p in (ROOT/'Configuration').iterdir()}
app = plists['AppInfo.plist']; extension = plists['CameraExtensionInfo.plist']
host_entitlements = plists['App.entitlements']; camera_entitlements = plists['CameraExtension.entitlements']
check(host_entitlements.get('com.apple.developer.system-extension.install') is True, 'Host requests normal system-extension installation')
check(host_entitlements.get('com.apple.security.app-sandbox') is True and camera_entitlements.get('com.apple.security.app-sandbox') is True,
      'Both executable targets use the App Sandbox')
check(host_entitlements['com.apple.security.application-groups'] == camera_entitlements['com.apple.security.application-groups'], 'Matching host/extension App Group entitlements')
check(app['AEAppGroup'] == extension['AEAppGroup'], 'Matching expanded group configuration')
check(extension['CMIOExtension']['CMIOExtensionMachServiceName'].startswith(extension['AEAppGroup']+'.'), 'CMIO Mach service is prefixed by its entitled group')
check('NSLocalNetworkUsageDescription' in app and 'NSSystemExtensionUsageDescription' in app, 'User-facing local-network/camera-extension descriptions')

with (ROOT/'AlterEgoMac.xcodeproj/project.pbxproj').open(encoding='utf-8') as file:
    project = OpenStepDecoder.ParseFromFile(file)
objects = project['objects']
check(project['rootObject'] in objects, 'Xcode project root resolves')
targets = {obj['name']: obj for obj in objects.values() if obj['isa'] == 'PBXNativeTarget'}
check(set(targets) == {'AlterEgoMac', 'AlterEgoCamera', 'AlterEgoMacTests'}, 'App, camera-extension and XCTest targets exist')

file_paths = {}
def walk_group(identifier, prefix):
    node = objects[identifier]
    if node['isa'] == 'PBXGroup':
        here = prefix / node.get('path','')
        for child in node.get('children',[]): walk_group(child, here)
    elif node['isa'] == 'PBXFileReference' and node.get('sourceTree') == '<group>':
        full = prefix / node['path']; file_paths[identifier] = full
        check(full.exists(), f'Xcode file reference exists: {full.relative_to(ROOT)}')
walk_group(objects[project['rootObject']]['mainGroup'], ROOT)

def sources(target):
    result = set()
    for phase in targets[target]['buildPhases']:
        value = objects[phase]
        if value['isa'] == 'PBXSourcesBuildPhase':
            result.update(file_paths[objects[entry]['fileRef']].resolve() for entry in value['files'])
    return result
check(sources('AlterEgoMac') == {p.resolve() for p in (ROOT/'AlterEgoMac').glob('*.swift')} | {p.resolve() for p in (ROOT/'Shared').glob('*.swift')}, 'App compiles all host/shared Swift sources, no extension entrypoint')
check(sources('AlterEgoCamera') == {p.resolve() for p in (ROOT/'CameraExtension').glob('*.swift')} | {(ROOT/'Shared/ProductConfiguration.swift').resolve()}, 'Camera target compiles provider/main/shared configuration')
check(sources('AlterEgoMacTests') == {p.resolve() for p in (ROOT/'Tests').glob('*.swift')}, 'XCTest target includes every native test source')
copy_phases = [objects[p] for p in targets['AlterEgoMac']['buildPhases'] if objects[p]['isa'] == 'PBXCopyFilesBuildPhase']
check(len(copy_phases)==1 and copy_phases[0]['dstPath']=='$(CONTENTS_FOLDER_PATH)/Library/SystemExtensions', 'Camera extension embeds in the app SystemExtensions directory')
check('CodeSignOnCopy' in objects[copy_phases[0]['files'][0]]['settings']['ATTRIBUTES'], 'Embedded extension is signed on copy')
check(len(targets['AlterEgoMac']['dependencies'])==1, 'App build depends on camera extension')

scheme = ET.parse(ROOT/'AlterEgoMac.xcodeproj/xcshareddata/xcschemes/AlterEgoMac.xcscheme')
check(scheme.find('.//TestAction/EnvironmentVariables/EnvironmentVariable[@key="ALTEREGO_TESTING"]').get('value')=='1', 'Native test host isolates real pairing/background state')
check(scheme.find('.//TestableReference/BuildableReference').get('BlueprintName')=='AlterEgoMacTests', 'Shared scheme runs the native test target')

icons = ROOT/'AlterEgoMac/Assets.xcassets/AppIcon.appiconset'
for item in json.loads((icons/'Contents.json').read_text())['images']:
    data=(icons/item['filename']).read_bytes(); width,height=struct.unpack('>II',data[16:24])
    desired=int(item['size'].split('x')[0])*int(item['scale'][0])
    check(data[:8]==b'\x89PNG\r\n\x1a\n' and (width,height)==(desired,desired), f'Mac icon size: {item["filename"]}')

for path in ROOT.rglob('*'):
    if not path.is_file() or any(p in ('build','dist','__pycache__','xcuserdata') for p in path.relative_to(ROOT).parts): continue
    check(path.suffix.lower() not in ('.p12','.p8','.exe','.dll','.mobileprovision','.provisionprofile'), f'No signing key/Windows binary in source package: {path.relative_to(ROOT)}')

test_count=sum(len(re.findall(r'\bfunc test\w*\s*\(',p.read_text())) for p in (ROOT/'Tests').glob('*.swift'))
report={'validation':'source/package-only', 'native_compilation':'NOT RUN — requires macOS/Xcode',
        'native_tests':'AUTHORED, NOT EXECUTED', 'swift_files':len(swift_files), 'xctest_methods_authored':test_count,
        'checks_passed':len(checks), 'checks':checks}
(ROOT/'VALIDATION.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
print(f'{len(checks)} source/package checks passed; {len(swift_files)} Swift files parsed.')
print(f'{test_count} XCTest methods authored; NOT executed on Windows. Native build/signing/camera verification remain pending.')

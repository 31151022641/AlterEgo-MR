"""Regenerate the checked-in Xcode project. No third-party generator is needed on Mac."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
objects = {}
def identifier(key): return hashlib.sha1(key.encode()).hexdigest()[:24].upper()
def add(key, isa, **fields):
    i = identifier(key); objects[i] = {'isa': isa, **fields}; return i
def ref(path, filetype, key=None, tree='<group>'):
    return add(key or 'file:'+path, 'PBXFileReference', lastKnownFileType=filetype, path=path, sourceTree=tree)
def buildfile(file, key): return add('build:'+key, 'PBXBuildFile', fileRef=file)
def group(name, path, children): return add('group:'+name, 'PBXGroup', name=name, path=path, children=children, sourceTree='<group>')

app_sources = sorted(p.name for p in (ROOT/'AlterEgoMac').glob('*.swift'))
extension_sources = sorted(p.name for p in (ROOT/'CameraExtension').glob('*.swift'))
shared_sources = sorted(p.name for p in (ROOT/'Shared').glob('*.swift'))
test_sources = sorted(p.name for p in (ROOT/'Tests').glob('*.swift'))
app_refs = {p:ref(p,'sourcecode.swift','app:'+p) for p in app_sources}
ext_refs = {p:ref(p,'sourcecode.swift','ext:'+p) for p in extension_sources}
shared_refs = {p:ref(p,'sourcecode.swift','shared:'+p) for p in shared_sources}
test_refs = {p:ref(p,'sourcecode.swift','test:'+p) for p in test_sources}
asset = ref('Assets.xcassets','folder.assetcatalog')
configs = [ref(p.name, 'text.plist.entitlements' if p.suffix=='.entitlements' else 'text.plist.xml', 'config:'+p.name)
           for p in sorted((ROOT/'Configuration').iterdir()) if p.suffix in ('.plist','.entitlements')]
app_product = ref('AlterEgo MR Webcam.app','wrapper.application',tree='BUILT_PRODUCTS_DIR')
ext_product = ref('com.misterslee.alterego.mac.camera-extension.systemextension','wrapper.system-extension',tree='BUILT_PRODUCTS_DIR')
test_product = ref('AlterEgoMacTests.xctest','wrapper.cfbundle',tree='BUILT_PRODUCTS_DIR')
products = group('Products','',[app_product,ext_product,test_product])
framework_names = ['AppKit','SwiftUI','Foundation','Network','Security','CryptoKit','SystemExtensions','SystemConfiguration',
                   'CoreMediaIO','CoreMedia','CoreVideo','CoreImage','CoreGraphics','CoreAudio','ImageIO','UniformTypeIdentifiers']
frameworks = {n:ref('System/Library/Frameworks/'+n+'.framework','wrapper.framework','framework:'+n,'SDKROOT') for n in framework_names}
main_group = group('Root','',[
    group('App','AlterEgoMac',list(app_refs.values())+[asset]),
    group('CameraExtension','CameraExtension',list(ext_refs.values())),
    group('Shared','Shared',list(shared_refs.values())),group('Tests','Tests',list(test_refs.values())),
    group('Configuration','Configuration',configs),group('Frameworks','',list(frameworks.values())),products])

def phase(key, isa, files, **kw):
    return add(key,isa,buildActionMask=2147483647,files=files,runOnlyForDeploymentPostprocessing=0,**kw)
app_source_phase=phase('app:sources','PBXSourcesBuildPhase', [buildfile(v,'app:'+k) for k,v in {**app_refs,**shared_refs}.items()])
ext_source_phase=phase('ext:sources','PBXSourcesBuildPhase', [buildfile(v,'ext:'+k) for k,v in {**ext_refs,'ProductConfiguration.swift':shared_refs['ProductConfiguration.swift']}.items()])
test_source_phase=phase('test:sources','PBXSourcesBuildPhase',[buildfile(v,'test:'+k) for k,v in test_refs.items()])
app_framework_phase=phase('app:frameworks','PBXFrameworksBuildPhase',[buildfile(v,'appframework:'+k) for k,v in frameworks.items()])
ext_framework_phase=phase('ext:frameworks','PBXFrameworksBuildPhase',[buildfile(frameworks[k],'extframework:'+k) for k in ['Foundation','CoreMediaIO','CoreMedia','CoreVideo','CoreImage','CoreGraphics','CoreAudio','Security']])
test_framework_phase=phase('test:frameworks','PBXFrameworksBuildPhase',[])
app_resource_phase=phase('app:resources','PBXResourcesBuildPhase',[buildfile(asset,'app:asset')])
embed = add('embed:extension','PBXBuildFile',fileRef=ext_product,settings={'ATTRIBUTES':['CodeSignOnCopy','RemoveHeadersOnCopy']})
embed_phase=phase('app:embed','PBXCopyFilesBuildPhase',[embed],dstPath='$(CONTENTS_FOLDER_PATH)/Library/SystemExtensions',dstSubfolderSpec=16,name='Embed System Extensions')
project_id=identifier('project'); app_id=identifier('target:app'); ext_id=identifier('target:extension'); test_id=identifier('target:tests')
proxy=add('proxy:extension','PBXContainerItemProxy',containerPortal=project_id,proxyType=1,remoteGlobalIDString=ext_id,remoteInfo='AlterEgoCamera')
dependency=add('dependency:extension','PBXTargetDependency',target=ext_id,targetProxy=proxy)
test_proxy=add('proxy:app','PBXContainerItemProxy',containerPortal=project_id,proxyType=1,remoteGlobalIDString=app_id,remoteInfo='AlterEgoMac')
test_dependency=add('dependency:app','PBXTargetDependency',target=app_id,targetProxy=test_proxy)

base={'CLANG_ENABLE_MODULES':'YES','SDKROOT':'macosx','MACOSX_DEPLOYMENT_TARGET':'13.0','SWIFT_VERSION':'5.0',
      'ARCHS':'arm64','MARKETING_VERSION':'1.0.0','CURRENT_PROJECT_VERSION':'1','CODE_SIGN_STYLE':'Automatic',
      'ENABLE_HARDENED_RUNTIME':'YES','SWIFT_STRICT_CONCURRENCY':'minimal'}
app={'PRODUCT_NAME':'AlterEgo MR Webcam','PRODUCT_MODULE_NAME':'AlterEgoMac','PRODUCT_BUNDLE_IDENTIFIER':'com.misterslee.alterego.mac',
     'INFOPLIST_FILE':'Configuration/AppInfo.plist','GENERATE_INFOPLIST_FILE':'NO','CODE_SIGN_ENTITLEMENTS':'Configuration/App.entitlements',
     'ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','COMBINE_HIDPI_IMAGES':'YES','LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/../Frameworks']}
extension={'PRODUCT_NAME':'com.misterslee.alterego.mac.camera-extension','PRODUCT_MODULE_NAME':'AlterEgoCamera',
           'PRODUCT_BUNDLE_IDENTIFIER':'com.misterslee.alterego.mac.camera-extension','INFOPLIST_FILE':'Configuration/CameraExtensionInfo.plist',
           'GENERATE_INFOPLIST_FILE':'NO','CODE_SIGN_ENTITLEMENTS':'Configuration/CameraExtension.entitlements',
           'SKIP_INSTALL':'YES','APPLICATION_EXTENSION_API_ONLY':'YES','LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/../Frameworks']}
tests={'PRODUCT_NAME':'AlterEgoMacTests','PRODUCT_MODULE_NAME':'AlterEgoMacTests','PRODUCT_BUNDLE_IDENTIFIER':'com.misterslee.alterego.mac.tests',
       'ENABLE_TESTING_SEARCH_PATHS':'YES',
       'GENERATE_INFOPLIST_FILE':'YES','BUNDLE_LOADER':'$(TEST_HOST)',
       'TEST_HOST':'$(BUILT_PRODUCTS_DIR)/AlterEgo MR Webcam.app/Contents/MacOS/AlterEgo MR Webcam'}
def config_list(key, settings):
    ids=[]
    for configuration in ['Debug','Release']:
        values=dict(settings)
        values.update({'SWIFT_OPTIMIZATION_LEVEL':'-Onone' if configuration=='Debug' else '-O',
                       'DEBUG_INFORMATION_FORMAT':'dwarf' if configuration=='Debug' else 'dwarf-with-dsym'})
        if configuration=='Debug': values['ENABLE_TESTABILITY']='YES'
        ids.append(add('configuration:'+key+configuration,'XCBuildConfiguration',buildSettings=values,name=configuration))
    return add('configlist:'+key,'XCConfigurationList',buildConfigurations=ids,defaultConfigurationIsVisible=0,defaultConfigurationName='Release')
project_configs=config_list('project',base)
add('target:extension','PBXNativeTarget',buildConfigurationList=config_list('ext',extension),buildPhases=[ext_source_phase,ext_framework_phase],
    buildRules=[],dependencies=[],name='AlterEgoCamera',productName=extension['PRODUCT_NAME'],productReference=ext_product,productType='com.apple.product-type.system-extension')
add('target:app','PBXNativeTarget',buildConfigurationList=config_list('app',app),buildPhases=[app_source_phase,app_framework_phase,app_resource_phase,embed_phase],
    buildRules=[],dependencies=[dependency],name='AlterEgoMac',productName=app['PRODUCT_NAME'],productReference=app_product,productType='com.apple.product-type.application')
add('target:tests','PBXNativeTarget',buildConfigurationList=config_list('tests',tests),buildPhases=[test_source_phase,test_framework_phase],buildRules=[],
    dependencies=[test_dependency],name='AlterEgoMacTests',productName=tests['PRODUCT_NAME'],productReference=test_product,productType='com.apple.product-type.bundle.unit-test')
add('project','PBXProject',attributes={'BuildIndependentTargetsInParallel':'YES','LastUpgradeCheck':'1600',
    'TargetAttributes':{app_id:{'CreatedOnToolsVersion':'16.0'},ext_id:{'CreatedOnToolsVersion':'16.0'},test_id:{'CreatedOnToolsVersion':'16.0','TestTargetID':app_id}}},
    buildConfigurationList=project_configs,compatibilityVersion='Xcode 14.0',developmentRegion='en',hasScannedForEncodings=0,
    knownRegions=['en','Base'],mainGroup=main_group,productRefGroup=products,projectDirPath='',projectRoot='',targets=[app_id,ext_id,test_id])

def serialize(obj, indent=0):
    pad='\t'*indent
    if isinstance(obj,dict): return '{\n'+''.join(pad+'\t'+json.dumps(k)+' = '+serialize(v,indent+1)+';\n' for k,v in obj.items())+pad+'}'
    if isinstance(obj,list): return '(\n'+''.join(pad+'\t'+serialize(v,indent+1)+',\n' for v in obj)+pad+')'
    return str(obj) if isinstance(obj,int) else json.dumps(obj)
project=ROOT/'AlterEgoMac.xcodeproj'; project.mkdir(exist_ok=True)
(project/'project.pbxproj').write_text('// !$*UTF8*$!\n'+serialize({'archiveVersion':1,'classes':{},'objectVersion':56,'objects':objects,'rootObject':project_id})+'\n',encoding='utf-8')

scheme=ET.Element('Scheme',LastUpgradeVersion='1600',version='1.7')
build=ET.SubElement(scheme,'BuildAction',parallelizeBuildables='YES',buildImplicitDependencies='YES')
entries=ET.SubElement(build,'BuildActionEntries')
def buildable(parent,id,name,product):
    ET.SubElement(parent,'BuildableReference',BuildableIdentifier='primary',BlueprintIdentifier=id,BuildableName=product,
        BlueprintName=name,ReferencedContainer='container:AlterEgoMac.xcodeproj')
entry=ET.SubElement(entries,'BuildActionEntry',buildForTesting='YES',buildForRunning='YES',buildForProfiling='YES',buildForArchiving='YES',buildForAnalyzing='YES')
buildable(entry,app_id,'AlterEgoMac','AlterEgo MR Webcam.app')
test=ET.SubElement(scheme,'TestAction',buildConfiguration='Debug',selectedDebuggerIdentifier='Xcode.DebuggerFoundation.Debugger.LLDB',selectedLauncherIdentifier='Xcode.IDEFoundation.Launcher.LLDB',shouldUseLaunchSchemeArgsEnv='NO')
environment=ET.SubElement(test,'EnvironmentVariables'); ET.SubElement(environment,'EnvironmentVariable',key='ALTEREGO_TESTING',value='1',isEnabled='YES')
testables=ET.SubElement(test,'Testables'); testable=ET.SubElement(testables,'TestableReference',skipped='NO')
buildable(testable,test_id,'AlterEgoMacTests','AlterEgoMacTests.xctest')
launch=ET.SubElement(scheme,'LaunchAction',buildConfiguration='Debug',selectedDebuggerIdentifier='Xcode.DebuggerFoundation.Debugger.LLDB',selectedLauncherIdentifier='Xcode.IDEFoundation.Launcher.LLDB',launchStyle='0',useCustomWorkingDirectory='NO',ignoresPersistentStateOnLaunch='NO',debugDocumentVersioning='YES',debugServiceExtension='internal',allowLocationSimulation='NO')
run=ET.SubElement(launch,'BuildableProductRunnable',runnableDebuggingMode='0'); buildable(run,app_id,'AlterEgoMac','AlterEgo MR Webcam.app')
profile=ET.SubElement(scheme,'ProfileAction',buildConfiguration='Release',shouldUseLaunchSchemeArgsEnv='YES',savedToolIdentifier='',useCustomWorkingDirectory='NO',debugDocumentVersioning='YES')
buildable(ET.SubElement(profile,'BuildableProductRunnable',runnableDebuggingMode='0'),app_id,'AlterEgoMac','AlterEgo MR Webcam.app')
ET.SubElement(scheme,'AnalyzeAction',buildConfiguration='Debug'); ET.SubElement(scheme,'ArchiveAction',buildConfiguration='Release',revealArchiveInOrganizer='YES')
ET.indent(scheme)
path=project/'xcshareddata/xcschemes/AlterEgoMac.xcscheme'; path.parent.mkdir(parents=True,exist_ok=True)
ET.ElementTree(scheme).write(path,encoding='utf-8',xml_declaration=True)
print(f'Generated native app, embedded camera extension and {len(test_sources)} XCTest source targets.')

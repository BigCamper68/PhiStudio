#!/usr/bin/env node

// Deterministic Xcode project generator. It uses only Node's standard library so a
// contributor can add Swift files and regenerate the project without XcodeGen.

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const projectRoot = path.resolve(__dirname, "..");
const projectBundle = path.join(projectRoot, "PhiStudioIOS.xcodeproj");
const sourceRoot = path.join(projectRoot, "PhiStudioIOS");
const testsRoot = path.join(projectRoot, "PhiStudioIOSTests");

function id(key) {
  return crypto.createHash("sha1").update(key).digest("hex").slice(0, 24).toUpperCase();
}

function quoted(value) {
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function typeFor(filename) {
  if (filename.endsWith(".swift")) return "sourcecode.swift";
  if (filename.endsWith(".plist")) return "text.plist.xml";
  if (filename.endsWith(".json")) return "text.json";
  if (filename.endsWith(".png")) return "image.png";
  if (filename.endsWith(".wav")) return "audio.wav";
  if (filename.endsWith(".xcassets")) return "folder.assetcatalog";
  return "file";
}

const fileRefs = [];
const groups = [];
const appSources = [];
const testSources = [];
const appResources = [];

function addFileReference(absolutePath, logicalKey, filename, role) {
  const fileID = id(`file:${logicalKey}`);
  fileRefs.push({
    id: fileID,
    name: filename,
    path: filename,
    type: typeFor(filename),
  });
  if (role === "app-source") appSources.push({ fileID, key: logicalKey, name: filename });
  if (role === "test-source") testSources.push({ fileID, key: logicalKey, name: filename });
  if (role === "resource") appResources.push({ fileID, key: logicalKey, name: filename });
  return fileID;
}

function addDirectoryGroup(absolutePath, logicalKey, displayName, role, isRoot = false) {
  const groupID = id(`group:${logicalKey}`);
  const children = [];
  const entries = fs
    .readdirSync(absolutePath, { withFileTypes: true })
    .filter((entry) => !entry.name.startsWith("."))
    .sort((left, right) => left.name.localeCompare(right.name));

  for (const entry of entries) {
    const absoluteChild = path.join(absolutePath, entry.name);
    const childKey = `${logicalKey}/${entry.name}`;
    if (entry.isDirectory() && entry.name.endsWith(".xcassets")) {
      children.push(
        addFileReference(absoluteChild, childKey, entry.name, role === "app" ? "resource" : null),
      );
    } else if (entry.isDirectory()) {
      children.push(addDirectoryGroup(absoluteChild, childKey, entry.name, role));
    } else {
      let fileRole = null;
      if (entry.name.endsWith(".swift")) {
        fileRole = role === "test" ? "test-source" : "app-source";
      } else if (role === "app" && logicalKey.includes("/Resources")) {
        fileRole = "resource";
      }
      children.push(addFileReference(absoluteChild, childKey, entry.name, fileRole));
    }
  }

  groups.push({
    id: groupID,
    name: displayName,
    path: isRoot ? displayName : displayName,
    children,
  });
  return groupID;
}

const sourceGroupID = addDirectoryGroup(sourceRoot, "PhiStudioIOS", "PhiStudioIOS", "app", true);
const testsGroupID = addDirectoryGroup(testsRoot, "PhiStudioIOSTests", "PhiStudioIOSTests", "test", true);

const appProductRef = id("product:PhiStudioIOS.app");
const testsProductRef = id("product:PhiStudioIOSTests.xctest");
fileRefs.push({
  id: appProductRef,
  name: "PhiStudioIOS.app",
  path: "PhiStudioIOS.app",
  explicitType: "wrapper.application",
  sourceTree: "BUILT_PRODUCTS_DIR",
});
fileRefs.push({
  id: testsProductRef,
  name: "PhiStudioIOSTests.xctest",
  path: "PhiStudioIOSTests.xctest",
  explicitType: "wrapper.cfbundle",
  sourceTree: "BUILT_PRODUCTS_DIR",
});

const productsGroupID = id("group:Products");
groups.push({
  id: productsGroupID,
  name: "Products",
  children: [appProductRef, testsProductRef],
  sourceTree: "<group>",
});
const mainGroupID = id("group:root");
groups.push({
  id: mainGroupID,
  children: [sourceGroupID, testsGroupID, productsGroupID],
  sourceTree: "<group>",
});

const buildFiles = [];
function makeBuildFile(item, phase) {
  const buildID = id(`build:${phase}:${item.key}`);
  buildFiles.push({
    id: buildID,
    name: item.name,
    fileRef: item.fileID,
    phase,
  });
  return buildID;
}

const appSourceBuildIDs = appSources.map((item) => makeBuildFile(item, "Sources"));
const testSourceBuildIDs = testSources.map((item) => makeBuildFile(item, "Sources"));
const resourceBuildIDs = appResources.map((item) => makeBuildFile(item, "Resources"));

const packages = [
  {
    key: "ZIPFoundation",
    url: "https://github.com/weichsel/ZIPFoundation.git",
    minimum: "0.9.19",
    product: "ZIPFoundation",
  },
  {
    key: "ogg",
    url: "https://github.com/sbooth/ogg-binary-xcframework.git",
    minimum: "0.1.3",
    product: "ogg",
  },
  {
    key: "vorbis",
    url: "https://github.com/sbooth/vorbis-binary-xcframework.git",
    minimum: "0.1.2",
    product: "vorbis",
  },
].map((item) => ({
  ...item,
  packageID: id(`package:${item.key}`),
  productID: id(`package-product:${item.key}`),
  buildID: id(`build:framework:${item.key}`),
}));

for (const item of packages) {
  buildFiles.push({
    id: item.buildID,
    name: `${item.product} in Frameworks`,
    productRef: item.productID,
    phase: "Frameworks",
  });
}

const appSourcesPhase = id("phase:app:sources");
const appFrameworksPhase = id("phase:app:frameworks");
const appResourcesPhase = id("phase:app:resources");
const testSourcesPhase = id("phase:test:sources");
const testFrameworksPhase = id("phase:test:frameworks");
const testResourcesPhase = id("phase:test:resources");
const appTargetID = id("target:app");
const testTargetID = id("target:tests");
const projectID = id("project");
const proxyID = id("proxy:test-to-app");
const dependencyID = id("dependency:test-to-app");

const appDebugConfig = id("config:app:debug");
const appReleaseConfig = id("config:app:release");
const testDebugConfig = id("config:test:debug");
const testReleaseConfig = id("config:test:release");
const projectDebugConfig = id("config:project:debug");
const projectReleaseConfig = id("config:project:release");
const appConfigList = id("config-list:app");
const testConfigList = id("config-list:test");
const projectConfigList = id("config-list:project");

function refList(values, indent = "\t\t\t\t") {
  return values.map((value) => `${indent}${value},`).join("\n");
}

function buildSettings(settings) {
  return Object.entries(settings)
    .map(([key, value]) => {
      if (Array.isArray(value)) {
        return `\t\t\t\t${key} = (\n${value
          .map((item) => `\t\t\t\t\t${quoted(item)},`)
          .join("\n")}\n\t\t\t\t);`;
      }
      return `\t\t\t\t${key} = ${quoted(value)};`;
    })
    .join("\n");
}

const projectDebugSettings = {
  ALWAYS_SEARCH_USER_PATHS: "NO",
  CLANG_ENABLE_MODULES: "YES",
  CLANG_ENABLE_OBJC_ARC: "YES",
  COPY_PHASE_STRIP: "NO",
  DEBUG_INFORMATION_FORMAT: "dwarf",
  ENABLE_TESTABILITY: "YES",
  GCC_C_LANGUAGE_STANDARD: "gnu17",
  GCC_OPTIMIZATION_LEVEL: "0",
  IPHONEOS_DEPLOYMENT_TARGET: "17.0",
  ONLY_ACTIVE_ARCH: "YES",
  SDKROOT: "iphoneos",
  SWIFT_ACTIVE_COMPILATION_CONDITIONS: "DEBUG $(inherited)",
  SWIFT_OPTIMIZATION_LEVEL: "-Onone",
};
const projectReleaseSettings = {
  ALWAYS_SEARCH_USER_PATHS: "NO",
  CLANG_ENABLE_MODULES: "YES",
  CLANG_ENABLE_OBJC_ARC: "YES",
  COPY_PHASE_STRIP: "NO",
  DEBUG_INFORMATION_FORMAT: "dwarf-with-dsym",
  ENABLE_NS_ASSERTIONS: "NO",
  GCC_C_LANGUAGE_STANDARD: "gnu17",
  IPHONEOS_DEPLOYMENT_TARGET: "17.0",
  SDKROOT: "iphoneos",
  SWIFT_COMPILATION_MODE: "wholemodule",
  VALIDATE_PRODUCT: "YES",
};
const appSettings = {
  ASSETCATALOG_COMPILER_APPICON_NAME: "AppIcon",
  ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME: "AccentColor",
  CODE_SIGN_STYLE: "Automatic",
  CURRENT_PROJECT_VERSION: "5",
  ENABLE_PREVIEWS: "YES",
  GENERATE_INFOPLIST_FILE: "NO",
  INFOPLIST_FILE: "PhiStudioIOS/Info.plist",
  IPHONEOS_DEPLOYMENT_TARGET: "17.0",
  LD_RUNPATH_SEARCH_PATHS: ["$(inherited)", "@executable_path/Frameworks"],
  MARKETING_VERSION: "1.0.4",
  PRODUCT_BUNDLE_IDENTIFIER: "com.bigcamper68.PhiStudio",
  PRODUCT_NAME: "$(TARGET_NAME)",
  SUPPORTED_PLATFORMS: "iphoneos iphonesimulator",
  SUPPORTS_MACCATALYST: "NO",
  SWIFT_EMIT_LOC_STRINGS: "YES",
  SWIFT_STRICT_CONCURRENCY: "targeted",
  SWIFT_VERSION: "5.0",
  TARGETED_DEVICE_FAMILY: "1,2",
};
const testSettings = {
  BUNDLE_LOADER: "$(TEST_HOST)",
  CODE_SIGN_STYLE: "Automatic",
  GENERATE_INFOPLIST_FILE: "YES",
  IPHONEOS_DEPLOYMENT_TARGET: "17.0",
  LD_RUNPATH_SEARCH_PATHS: ["$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks"],
  PRODUCT_BUNDLE_IDENTIFIER: "com.bigcamper68.PhiStudioTests",
  PRODUCT_NAME: "$(TARGET_NAME)",
  SWIFT_VERSION: "5.0",
  TARGETED_DEVICE_FAMILY: "1,2",
  TEST_HOST:
    "$(BUILT_PRODUCTS_DIR)/PhiStudioIOS.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/PhiStudioIOS",
};

let output = `// !$*UTF8*$!
{
\tarchiveVersion = 1;
\tclasses = {
\t};
\tobjectVersion = 56;
\tobjects = {

/* Begin PBXBuildFile section */
${buildFiles
  .map((item) => {
    if (item.productRef) {
      return `\t\t${item.id} /* ${item.name} */ = {isa = PBXBuildFile; productRef = ${item.productRef} /* ${item.name.replace(" in Frameworks", "")} */; };`;
    }
    return `\t\t${item.id} /* ${item.name} in ${item.phase} */ = {isa = PBXBuildFile; fileRef = ${item.fileRef} /* ${item.name} */; };`;
  })
  .join("\n")}
/* End PBXBuildFile section */

/* Begin PBXContainerItemProxy section */
\t\t${proxyID} /* PBXContainerItemProxy */ = {
\t\t\tisa = PBXContainerItemProxy;
\t\t\tcontainerPortal = ${projectID} /* Project object */;
\t\t\tproxyType = 1;
\t\t\tremoteGlobalIDString = ${appTargetID};
\t\t\tremoteInfo = PhiStudioIOS;
\t\t};
/* End PBXContainerItemProxy section */

/* Begin PBXFileReference section */
${fileRefs
  .map((item) => {
    const sourceTree = item.sourceTree ?? "<group>";
    if (item.explicitType) {
      return `\t\t${item.id} /* ${item.name} */ = {isa = PBXFileReference; explicitFileType = ${item.explicitType}; includeInIndex = 0; path = ${quoted(item.path)}; sourceTree = ${sourceTree}; };`;
    }
    return `\t\t${item.id} /* ${item.name} */ = {isa = PBXFileReference; lastKnownFileType = ${item.type}; path = ${quoted(item.path)}; sourceTree = "<group>"; };`;
  })
  .join("\n")}
/* End PBXFileReference section */

/* Begin PBXFrameworksBuildPhase section */
\t\t${appFrameworksPhase} /* Frameworks */ = {
\t\t\tisa = PBXFrameworksBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
${refList(packages.map((item) => `${item.buildID} /* ${item.product} in Frameworks */`))}
\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t};
\t\t${testFrameworksPhase} /* Frameworks */ = {
\t\t\tisa = PBXFrameworksBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t};
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
${groups
  .map((group) => {
    const children = refList(
      group.children.map((child) => {
        const file = fileRefs.find((item) => item.id === child);
        const nested = groups.find((item) => item.id === child);
        return `${child} /* ${(file ?? nested)?.name ?? "Group"} */`;
      }),
    );
    const name = group.name ? `\n\t\t\tname = ${quoted(group.name)};` : "";
    const groupPath = group.path ? `\n\t\t\tpath = ${quoted(group.path)};` : "";
    return `\t\t${group.id} /* ${group.name ?? "Root"} */ = {
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
${children}
\t\t\t);${name}${groupPath}
\t\t\tsourceTree = ${quoted(group.sourceTree ?? "<group>")};
\t\t};`;
  })
  .join("\n")}
/* End PBXGroup section */

/* Begin PBXNativeTarget section */
\t\t${appTargetID} /* PhiStudioIOS */ = {
\t\t\tisa = PBXNativeTarget;
\t\t\tbuildConfigurationList = ${appConfigList} /* Build configuration list for PBXNativeTarget "PhiStudioIOS" */;
\t\t\tbuildPhases = (
\t\t\t\t${appSourcesPhase} /* Sources */,
\t\t\t\t${appFrameworksPhase} /* Frameworks */,
\t\t\t\t${appResourcesPhase} /* Resources */,
\t\t\t);
\t\t\tbuildRules = (
\t\t\t);
\t\t\tdependencies = (
\t\t\t);
\t\t\tname = PhiStudioIOS;
\t\t\tpackageProductDependencies = (
${refList(packages.map((item) => `${item.productID} /* ${item.product} */`))}
\t\t\t);
\t\t\tproductName = PhiStudioIOS;
\t\t\tproductReference = ${appProductRef} /* PhiStudioIOS.app */;
\t\t\tproductType = "com.apple.product-type.application";
\t\t};
\t\t${testTargetID} /* PhiStudioIOSTests */ = {
\t\t\tisa = PBXNativeTarget;
\t\t\tbuildConfigurationList = ${testConfigList} /* Build configuration list for PBXNativeTarget "PhiStudioIOSTests" */;
\t\t\tbuildPhases = (
\t\t\t\t${testSourcesPhase} /* Sources */,
\t\t\t\t${testFrameworksPhase} /* Frameworks */,
\t\t\t\t${testResourcesPhase} /* Resources */,
\t\t\t);
\t\t\tbuildRules = (
\t\t\t);
\t\t\tdependencies = (
\t\t\t\t${dependencyID} /* PBXTargetDependency */,
\t\t\t);
\t\t\tname = PhiStudioIOSTests;
\t\t\tproductName = PhiStudioIOSTests;
\t\t\tproductReference = ${testsProductRef} /* PhiStudioIOSTests.xctest */;
\t\t\tproductType = "com.apple.product-type.bundle.unit-test";
\t\t};
/* End PBXNativeTarget section */

/* Begin PBXProject section */
\t\t${projectID} /* Project object */ = {
\t\t\tisa = PBXProject;
\t\t\tattributes = {
\t\t\t\tBuildIndependentTargetsInParallel = 1;
\t\t\t\tLastSwiftUpdateCheck = 1600;
\t\t\t\tLastUpgradeCheck = 1600;
\t\t\t\tTargetAttributes = {
\t\t\t\t\t${appTargetID} = {
\t\t\t\t\t\tCreatedOnToolsVersion = 16.0;
\t\t\t\t\t};
\t\t\t\t\t${testTargetID} = {
\t\t\t\t\t\tCreatedOnToolsVersion = 16.0;
\t\t\t\t\t\tTestTargetID = ${appTargetID};
\t\t\t\t\t};
\t\t\t\t};
\t\t\t};
\t\t\tbuildConfigurationList = ${projectConfigList} /* Build configuration list for PBXProject "PhiStudioIOS" */;
\t\t\tcompatibilityVersion = "Xcode 14.0";
\t\t\tdevelopmentRegion = en;
\t\t\thasScannedForEncodings = 0;
\t\t\tknownRegions = (
\t\t\t\ten,
\t\t\t\tBase,
\t\t\t);
\t\t\tmainGroup = ${mainGroupID};
\t\t\tpackageReferences = (
${refList(packages.map((item) => `${item.packageID} /* XCRemoteSwiftPackageReference "${item.key}" */`))}
\t\t\t);
\t\t\tproductRefGroup = ${productsGroupID} /* Products */;
\t\t\tprojectDirPath = "";
\t\t\tprojectRoot = "";
\t\t\ttargets = (
\t\t\t\t${appTargetID} /* PhiStudioIOS */,
\t\t\t\t${testTargetID} /* PhiStudioIOSTests */,
\t\t\t);
\t\t};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
\t\t${appResourcesPhase} /* Resources */ = {
\t\t\tisa = PBXResourcesBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
${refList(resourceBuildIDs.map((buildID) => {
  const item = buildFiles.find((value) => value.id === buildID);
  return `${buildID} /* ${item.name} in Resources */`;
}))}
\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t};
\t\t${testResourcesPhase} /* Resources */ = {
\t\t\tisa = PBXResourcesBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t};
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
\t\t${appSourcesPhase} /* Sources */ = {
\t\t\tisa = PBXSourcesBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
${refList(appSourceBuildIDs.map((buildID) => {
  const item = buildFiles.find((value) => value.id === buildID);
  return `${buildID} /* ${item.name} in Sources */`;
}))}
\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t};
\t\t${testSourcesPhase} /* Sources */ = {
\t\t\tisa = PBXSourcesBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
${refList(testSourceBuildIDs.map((buildID) => {
  const item = buildFiles.find((value) => value.id === buildID);
  return `${buildID} /* ${item.name} in Sources */`;
}))}
\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t};
/* End PBXSourcesBuildPhase section */

/* Begin PBXTargetDependency section */
\t\t${dependencyID} /* PBXTargetDependency */ = {
\t\t\tisa = PBXTargetDependency;
\t\t\ttarget = ${appTargetID} /* PhiStudioIOS */;
\t\t\ttargetProxy = ${proxyID} /* PBXContainerItemProxy */;
\t\t};
/* End PBXTargetDependency section */

/* Begin XCBuildConfiguration section */
\t\t${projectDebugConfig} /* Debug */ = {
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {
${buildSettings(projectDebugSettings)}
\t\t\t};
\t\t\tname = Debug;
\t\t};
\t\t${projectReleaseConfig} /* Release */ = {
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {
${buildSettings(projectReleaseSettings)}
\t\t\t};
\t\t\tname = Release;
\t\t};
\t\t${appDebugConfig} /* Debug */ = {
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {
${buildSettings(appSettings)}
\t\t\t};
\t\t\tname = Debug;
\t\t};
\t\t${appReleaseConfig} /* Release */ = {
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {
${buildSettings({ ...appSettings, SWIFT_COMPILATION_MODE: "wholemodule" })}
\t\t\t};
\t\t\tname = Release;
\t\t};
\t\t${testDebugConfig} /* Debug */ = {
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {
${buildSettings(testSettings)}
\t\t\t};
\t\t\tname = Debug;
\t\t};
\t\t${testReleaseConfig} /* Release */ = {
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {
${buildSettings(testSettings)}
\t\t\t};
\t\t\tname = Release;
\t\t};
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
\t\t${projectConfigList} /* Build configuration list for PBXProject "PhiStudioIOS" */ = {
\t\t\tisa = XCConfigurationList;
\t\t\tbuildConfigurations = (
\t\t\t\t${projectDebugConfig} /* Debug */,
\t\t\t\t${projectReleaseConfig} /* Release */,
\t\t\t);
\t\t\tdefaultConfigurationIsVisible = 0;
\t\t\tdefaultConfigurationName = Release;
\t\t};
\t\t${appConfigList} /* Build configuration list for PBXNativeTarget "PhiStudioIOS" */ = {
\t\t\tisa = XCConfigurationList;
\t\t\tbuildConfigurations = (
\t\t\t\t${appDebugConfig} /* Debug */,
\t\t\t\t${appReleaseConfig} /* Release */,
\t\t\t);
\t\t\tdefaultConfigurationIsVisible = 0;
\t\t\tdefaultConfigurationName = Release;
\t\t};
\t\t${testConfigList} /* Build configuration list for PBXNativeTarget "PhiStudioIOSTests" */ = {
\t\t\tisa = XCConfigurationList;
\t\t\tbuildConfigurations = (
\t\t\t\t${testDebugConfig} /* Debug */,
\t\t\t\t${testReleaseConfig} /* Release */,
\t\t\t);
\t\t\tdefaultConfigurationIsVisible = 0;
\t\t\tdefaultConfigurationName = Release;
\t\t};
/* End XCConfigurationList section */

/* Begin XCRemoteSwiftPackageReference section */
${packages
  .map(
    (item) => `\t\t${item.packageID} /* XCRemoteSwiftPackageReference "${item.key}" */ = {
\t\t\tisa = XCRemoteSwiftPackageReference;
\t\t\trepositoryURL = ${quoted(item.url)};
\t\t\trequirement = {
\t\t\t\tkind = exactVersion;
\t\t\t\tversion = ${item.minimum};
\t\t\t};
\t\t};`,
  )
  .join("\n")}
/* End XCRemoteSwiftPackageReference section */

/* Begin XCSwiftPackageProductDependency section */
${packages
  .map(
    (item) => `\t\t${item.productID} /* ${item.product} */ = {
\t\t\tisa = XCSwiftPackageProductDependency;
\t\t\tpackage = ${item.packageID} /* XCRemoteSwiftPackageReference "${item.key}" */;
\t\t\tproductName = ${item.product};
\t\t};`,
  )
  .join("\n")}
/* End XCSwiftPackageProductDependency section */
\t};
\trootObject = ${projectID} /* Project object */;
}
`;

fs.mkdirSync(projectBundle, { recursive: true });
fs.writeFileSync(path.join(projectBundle, "project.pbxproj"), output);

const schemeDirectory = path.join(projectBundle, "xcshareddata", "xcschemes");
fs.mkdirSync(schemeDirectory, { recursive: true });
const scheme = `<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1600"
   version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "${appTargetID}" BuildableName = "PhiStudioIOS.app" BlueprintName = "PhiStudioIOS" ReferencedContainer = "container:PhiStudioIOS.xcodeproj"/>
         </BuildActionEntry>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "NO" buildForProfiling = "NO" buildForArchiving = "NO" buildForAnalyzing = "YES">
            <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "${testTargetID}" BuildableName = "PhiStudioIOSTests.xctest" BlueprintName = "PhiStudioIOSTests" ReferencedContainer = "container:PhiStudioIOS.xcodeproj"/>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
         <TestableReference skipped = "NO" parallelizable = "YES">
            <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "${testTargetID}" BuildableName = "PhiStudioIOSTests.xctest" BlueprintName = "PhiStudioIOSTests" ReferencedContainer = "container:PhiStudioIOS.xcodeproj"/>
         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "${appTargetID}" BuildableName = "PhiStudioIOS.app" BlueprintName = "PhiStudioIOS" ReferencedContainer = "container:PhiStudioIOS.xcodeproj"/>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES" savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "${appTargetID}" BuildableName = "PhiStudioIOS.app" BlueprintName = "PhiStudioIOS" ReferencedContainer = "container:PhiStudioIOS.xcodeproj"/>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug"/>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES"/>
</Scheme>
`;
fs.writeFileSync(path.join(schemeDirectory, "PhiStudioIOS.xcscheme"), scheme);

console.log(
  `Generated ${path.relative(process.cwd(), projectBundle)} with ${appSources.length} app sources, ` +
    `${testSources.length} test sources, and ${appResources.length} resources.`,
);

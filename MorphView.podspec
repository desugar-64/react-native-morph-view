require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "MorphView"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => "https://github.com/blazejkustra/react-native-morph-view.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift,cpp}"
  s.private_header_files = "ios/**/*.h"

  # The Metal alpha-threshold shader ships in its own resource bundle so it compiles into a
  # `default.metallib` we can locate deterministically at runtime via
  # `ShaderLibrary.bundle(...)` — see `shaderBundle` in MorphHostView.swift. (Putting .metal in
  # source_files instead leaves the metallib in a bundle SwiftUI can't find -> "no default library".)
  s.resource_bundles = {
    "MorphViewShaders" => ["ios/**/*.metal"]
  }

  # Required so the Obj-C++ Fabric component can import the generated "MorphView-Swift.h".
  s.pod_target_xcconfig = {
    "DEFINES_MODULE" => "YES",
    "SWIFT_VERSION" => "5.0"
  }

  install_modules_dependencies(s)
end

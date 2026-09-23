Pod::Spec.new do |s|
  s.name             = 'lumora_ble'
  s.version          = '0.1.0'
  s.summary          = 'Lumora BLE — Apple Watch health data via HealthKit.'
  s.description      = <<-DESC
One SDK for health wearables. The iOS implementation reads Apple Watch data
from HealthKit; the BLE device protocols are implemented on Android only.
                       DESC
  s.homepage         = 'https://github.com/lumoradevlab/BLE-Android'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Lumora' => 'dev@lumora.dev' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'

  # HealthKit is the whole point of the iOS build.
  s.frameworks = 'HealthKit'

  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.swift_version = '5.0'
end

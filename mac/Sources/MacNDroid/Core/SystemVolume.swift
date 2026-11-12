import AudioToolbox
import CoreAudio
import Foundation

/// The Mac's output volume (the one in Control Center), for the phone's "Music on your Mac" controls.
/// Plain CoreAudio: no permission needed. Follows the current output device (speakers, headphones, AirPods).
final class SystemVolume {
    static let shared = SystemVolume()

    /// Called on the main queue when the volume, mute or output device changes.
    var onChange: (() -> Void)?
    private var device = AudioObjectID(kAudioObjectUnknown)
    private let queue = DispatchQueue.main

    private init() {
        device = Self.defaultOutput()
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &addr, queue) { [weak self] _, _ in
            guard let self else { return }
            self.device = Self.defaultOutput()
            self.listen()
            self.onChange?()
        }
        listen()
    }

    private static func defaultOutput() -> AudioObjectID {
        var id = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &id)
        return id
    }

    private static func address(_ selector: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
    }

    private var listening: Set<AudioObjectID> = []
    private func listen() {
        guard device != kAudioObjectUnknown, !listening.contains(device) else { return }
        listening.insert(device)
        for sel in [kAudioHardwareServiceDeviceProperty_VirtualMainVolume, kAudioDevicePropertyMute] {
            var addr = Self.address(sel)
            guard AudioObjectHasProperty(device, &addr) else { continue }
            let dev = device
            AudioObjectAddPropertyListenerBlock(device, &addr, queue) { [weak self] _, _ in
                if self?.device == dev { self?.onChange?() }
            }
        }
    }

    /// Whether this output's volume can be changed (some HDMI and USB outputs can't).
    var isAdjustable: Bool {
        var addr = Self.address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume)
        var settable: DarwinBoolean = false
        guard AudioObjectHasProperty(device, &addr), AudioObjectIsPropertySettable(device, &addr, &settable) == noErr else { return false }
        return settable.boolValue
    }

    /// 0…100.
    var volume: Int {
        get {
            var v = Float32(0)
            var size = UInt32(MemoryLayout<Float32>.size)
            var addr = Self.address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume)
            guard AudioObjectGetPropertyData(device, &addr, 0, nil, &size, &v) == noErr else { return 0 }
            return Int((v * 100).rounded())
        }
        set {
            var v = Float32(min(max(newValue, 0), 100)) / 100
            var addr = Self.address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume)
            AudioObjectSetPropertyData(device, &addr, 0, nil, UInt32(MemoryLayout<Float32>.size), &v)
            if newValue > 0, muted { muted = false }
        }
    }

    var muted: Bool {
        get {
            var m = UInt32(0)
            var size = UInt32(MemoryLayout<UInt32>.size)
            var addr = Self.address(kAudioDevicePropertyMute)
            guard AudioObjectGetPropertyData(device, &addr, 0, nil, &size, &m) == noErr else { return false }
            return m != 0
        }
        set {
            var m = UInt32(newValue ? 1 : 0)
            var addr = Self.address(kAudioDevicePropertyMute)
            AudioObjectSetPropertyData(device, &addr, 0, nil, UInt32(MemoryLayout<UInt32>.size), &m)
        }
    }
}

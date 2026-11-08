import AppKit
import AVFoundation
import CoreMedia

/// Decodes the phone's H.264 stream (Annex B) and shows it with AVSampleBufferDisplayLayer,
/// which uses the hardware decoder (VideoToolbox) under the hood. Call from one serial queue.
final class MirrorRenderer {
    let layer = AVSampleBufferDisplayLayer()
    private var format: CMVideoFormatDescription?
    private var waitingForKey = true

    /// Called (on the decode queue) when the decoder needs a fresh key frame + config.
    var onNeedKeyFrame: (() -> Void)?

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = NSColor.black.cgColor
    }

    /// SPS + PPS from the encoder. Returns the video size.
    @discardableResult
    func configure(_ annexB: Data) -> CGSize? {
        let nals = Self.split(annexB)
        guard let sps = nals.first(where: { ($0.first ?? 0) & 0x1F == 7 }),
              let pps = nals.first(where: { ($0.first ?? 0) & 0x1F == 8 }) else { return nil }
        var fmt: CMFormatDescription?
        let status = sps.withUnsafeBytes { (s: UnsafeRawBufferPointer) -> OSStatus in
            pps.withUnsafeBytes { (p: UnsafeRawBufferPointer) -> OSStatus in
                let pointers = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2,
                    parameterSetPointers: pointers, parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4, formatDescriptionOut: &fmt)
            }
        }
        guard status == noErr, let fmt else { return nil }
        format = fmt
        waitingForKey = true
        layer.sampleBufferRenderer.flush()
        let d = CMVideoFormatDescriptionGetDimensions(fmt)
        return CGSize(width: CGFloat(d.width), height: CGFloat(d.height))
    }

    func frame(_ annexB: Data, isKey: Bool) {
        guard let format else { return }
        if waitingForKey && !isKey { return }

        let renderer = layer.sampleBufferRenderer
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            renderer.flush()
            waitingForKey = true
            onNeedKeyFrame?()
            if !isKey { return }
        }
        waitingForKey = false

        // Annex B (start codes) → AVCC (4-byte big-endian lengths); parameter sets live in `format`.
        var avcc = Data(capacity: annexB.count + 16)
        for nal in Self.split(annexB) {
            let type = (nal.first ?? 0) & 0x1F
            if type == 7 || type == 8 || type == 9 { continue }
            var len = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &len) { avcc.append(contentsOf: $0) }
            avcc.append(nal)
        }
        guard !avcc.isEmpty else { return }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
            dataLength: avcc.count, flags: 0, blockBufferOut: &block) == noErr, let block else { return }
        let copied = avcc.withUnsafeBytes {
            CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard copied == noErr else { return }

        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
            sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr,
              let sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        renderer.enqueue(sample)
    }

    func reset() {
        format = nil
        waitingForKey = true
        layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }

    /// Splits an Annex B byte stream on 00 00 01 / 00 00 00 01 start codes.
    static func split(_ data: Data) -> [Data] {
        var nals: [Data] = []
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let b = raw.bindMemory(to: UInt8.self)
            let n = b.count
            var i = 0
            var start = -1
            while i + 2 < n {
                if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                    if start >= 0 {
                        var end = i
                        if end > start, b[end - 1] == 0 { end -= 1 }   // 4-byte start code
                        if end > start { nals.append(Data(b[start..<end])) }
                    }
                    i += 3
                    start = i
                } else {
                    i += 1
                }
            }
            if start >= 0, start < n { nals.append(Data(b[start..<n])) }
        }
        return nals
    }
}

import AVFoundation
import Foundation
import Observation

#if canImport(ogg)
import ogg
#endif
#if canImport(vorbis)
import vorbis
#endif

public struct ScheduledHitSound: Sendable {
    public var type: NoteType
    public var delaySeconds: TimeInterval

    public init(type: NoteType, delaySeconds: TimeInterval = 0) {
        self.type = type
        self.delaySeconds = delaySeconds
    }
}

@MainActor
@Observable
public final class AudioController: NSObject, AVAudioPlayerDelegate {
    public private(set) var isLoaded = false
    public private(set) var isPlaying = false
    public private(set) var duration: TimeInterval = 0
    public private(set) var currentTime: TimeInterval = 0
    public private(set) var sourceURL: URL?
    public private(set) var rate: Float = 1 {
        didSet {
            player?.rate = rate
        }
    }
    public var isMuted = false {
        didSet {
            let volume: Float = isMuted ? 0 : 1
            player?.volume = volume
            hitSoundPlayers.values.joined().forEach { $0.volume = volume }
        }
    }

    private var player: AVAudioPlayer?
    private var hitSoundPlayers: [NoteType: [AVAudioPlayer]] = [:]
    private var hitSoundCursors: [NoteType: Int] = [:]
    private var decodedTemporaryURL: URL?

    override public init() {
        super.init()
        loadHitSounds()
    }

    public func shutdown() {
        stop()
        player = nil
        hitSoundPlayers.values.joined().forEach { $0.stop() }
        hitSoundPlayers.removeAll()
        hitSoundCursors.removeAll()
        if let decodedTemporaryURL {
            try? FileManager.default.removeItem(at: decodedTemporaryURL)
            self.decodedTemporaryURL = nil
        }
    }

    public func load(_ url: URL?) async throws {
        stop()
        player = nil
        isLoaded = false
        duration = 0
        currentTime = 0
        sourceURL = url
        if let decodedTemporaryURL {
            try? FileManager.default.removeItem(at: decodedTemporaryURL)
            self.decodedTemporaryURL = nil
        }
        guard let url else { return }
        let playableURL: URL
        if url.pathExtension.lowercased() == "ogg" {
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent("PhiStudio-\(UUID().uuidString).caf")
            playableURL = try await Task.detached(priority: .userInitiated) {
                try OggVorbisDecoder.decode(source: url, destination: destination)
                return destination
            }.value
            decodedTemporaryURL = destination
        } else {
            playableURL = url
        }
        try configureAudioSession()
        let audioPlayer = try AVAudioPlayer(contentsOf: playableURL)
        audioPlayer.enableRate = true
        audioPlayer.rate = rate
        audioPlayer.volume = isMuted ? 0 : 1
        audioPlayer.delegate = self
        audioPlayer.prepareToPlay()
        player = audioPlayer
        duration = audioPlayer.duration
        isLoaded = true
    }

    public func play() {
        guard let player else { return }
        if player.currentTime >= player.duration { player.currentTime = 0 }
        player.rate = rate
        isPlaying = player.play()
        updateTime()
    }

    public func setRate(_ value: Float) {
        rate = value.isFinite ? min(2, max(0.25, value)) : 1
    }

    public func pause() {
        player?.pause()
        isPlaying = false
        updateTime()
    }

    public func stop() {
        player?.stop()
        player?.currentTime = 0
        isPlaying = false
        updateTime()
    }

    public func seek(to seconds: TimeInterval) {
        guard let player else { return }
        player.currentTime = min(player.duration, max(0, seconds))
        updateTime()
    }

    public func updateTime() {
        currentTime = player?.currentTime ?? 0
        if player?.isPlaying != true { isPlaying = false }
    }

    public func playHitSound(for type: NoteType) {
        playHitSounds([ScheduledHitSound(type: type)])
    }

    /// Schedules a chord against one device clock. Equal-delay notes therefore start on the
    /// same audio sample instead of drifting while multiple `play()` calls are issued.
    public func playHitSounds(_ sounds: [ScheduledHitSound]) {
        guard !isMuted else { return }
        var pending: [(player: AVAudioPlayer, delay: TimeInterval)] = []
        var usedIndices: [NoteType: Set<Int>] = [:]
        for sound in sounds.prefix(48) {
            let resolvedType = hitSoundPlayers[sound.type] == nil ? NoteType.tap : sound.type
            guard let pool = hitSoundPlayers[resolvedType], !pool.isEmpty else { continue }
            let used = usedIndices[resolvedType, default: []]
            let cursor = hitSoundCursors[resolvedType, default: 0] % pool.count
            let index = (0 ..< pool.count)
                .map { (cursor + $0) % pool.count }
                .first { !used.contains($0) }
            guard let index else { continue }
            usedIndices[resolvedType, default: []].insert(index)
            hitSoundCursors[resolvedType] = (index + 1) % pool.count
            let hit = pool[index]
            hit.stop()
            hit.currentTime = 0
            hit.volume = 1
            pending.append(
                (
                    hit,
                    sound.delaySeconds.isFinite ? max(0, sound.delaySeconds) : 0
                )
            )
        }
        guard let clock = pending.first?.player.deviceCurrentTime else { return }
        let startTime = clock + 0.012
        for item in pending {
            item.player.play(atTime: startTime + item.delay)
        }
    }

    nonisolated public func audioPlayerDidFinishPlaying(
        _ player: AVAudioPlayer,
        successfully flag: Bool
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if player === self.player {
                self.isPlaying = false
                self.updateTime()
            }
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        try session.setActive(true)
    }

    private func loadHitSounds() {
        let names: [(NoteType, String)] = [
            (.tap, "hitsound_click"),
            (.drag, "hitsound_drag"),
            (.flick, "hitsound_flick"),
            (.hold, "hitsound_hold"),
        ]
        for (type, name) in names {
            guard let url = Bundle.main.url(
                forResource: name,
                withExtension: "wav",
                subdirectory: "Audio"
            ) ?? Bundle.main.url(forResource: name, withExtension: "wav"),
                let data = try? Data(contentsOf: url)
            else {
                continue
            }
            let playerCount = type == .tap ? 24 : 12
            let pool = (0 ..< playerCount).compactMap { _ -> AVAudioPlayer? in
                guard let hit = try? AVAudioPlayer(data: data) else { return nil }
                hit.prepareToPlay()
                return hit
            }
            if !pool.isEmpty {
                hitSoundPlayers[type] = pool
                hitSoundCursors[type] = 0
            }
        }
    }
}

public enum OggVorbisDecoder {
    public static func decode(source: URL, destination: URL) throws {
        #if canImport(vorbis)
        var vorbisFile = OggVorbis_File()
        let opened = source.path.withCString { path in
            ov_fopen(path, &vorbisFile)
        }
        guard opened == 0 else {
            throw ChartError.unsupportedFormat("Unable to open Ogg Vorbis audio (code \(opened))")
        }
        defer { ov_clear(&vorbisFile) }
        guard let info = ov_info(&vorbisFile, -1) else {
            throw ChartError.unsupportedFormat("Ogg Vorbis stream has no audio information")
        }
        let channels = AVAudioChannelCount(info.pointee.channels)
        let sampleRate = Double(info.pointee.rate)
        guard channels > 0,
              sampleRate.isFinite,
              sampleRate > 0,
              let format = AVAudioFormat(
                  commonFormat: .pcmFormatFloat32,
                  sampleRate: sampleRate,
                  channels: channels,
                  interleaved: false
              )
        else {
            throw ChartError.unsupportedFormat("Ogg Vorbis stream format is invalid")
        }
        let output = try AVAudioFile(
            forWriting: destination,
            settings: format.settings,
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )
        let capacity: AVAudioFrameCount = 4_096
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity),
              let destinationChannels = buffer.floatChannelData
        else {
            throw ChartError.fileSystem("Unable to allocate an Ogg decode buffer")
        }
        var bitstream: Int32 = 0
        while true {
            var sourceChannels: UnsafeMutablePointer<UnsafeMutablePointer<Float>?>?
            let frames = ov_read_float(
                &vorbisFile,
                &sourceChannels,
                Int32(capacity),
                &bitstream
            )
            if frames == 0 { break }
            guard frames > 0, let sourceChannels else {
                throw ChartError.unsupportedFormat("Ogg Vorbis decode failed (code \(frames))")
            }
            let frameCount = AVAudioFrameCount(frames)
            buffer.frameLength = frameCount
            for channel in 0 ..< Int(channels) {
                guard let sourceChannel = sourceChannels[channel] else {
                    throw ChartError.unsupportedFormat("Ogg Vorbis channel data is missing")
                }
                destinationChannels[channel].update(
                    from: sourceChannel,
                    count: Int(frameCount)
                )
            }
            try output.write(from: buffer)
        }
        #else
        throw ChartError.unsupportedFormat(
            "This build does not include the Ogg Vorbis decoder"
        )
        #endif
    }
}

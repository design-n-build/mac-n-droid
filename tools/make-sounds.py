"""Generates Mac n Droid's own subtle sounds (soft marimba-like blips) as 16-bit WAV.

Usage: python3 tools/make-sounds.py <out_dir>
Writes mnd_sent.wav, mnd_received.wav, mnd_tap.wav (Mac notification) and mnd_left_behind.wav.
"""
import math
import os
import struct
import sys
import wave

RATE = 44100


def note(freq, dur, start, decay, amp, attack_s=0.006, octave=0.18):
    """A soft mallet tone: sine + quiet octave, quick attack, exponential decay."""
    out = []
    n = int(dur * RATE)
    for i in range(n):
        t = i / RATE
        attack = min(1.0, t / attack_s)
        env = attack * math.exp(-t * decay)
        v = math.sin(2 * math.pi * freq * t) + octave * math.sin(2 * math.pi * freq * 2 * t)
        out.append((start + i, amp * env * v))
    return out


def render(notes, length):
    buf = [0.0] * int(length * RATE)
    for parts in notes:
        for idx, v in parts:
            if idx < len(buf):
                buf[idx] += v
    # Gentle fade-out to avoid clicks.
    fade = int(0.02 * RATE)
    for i in range(fade):
        buf[-1 - i] *= i / fade
    return buf


def write(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


def main(out):
    os.makedirs(out, exist_ok=True)
    s = lambda sec: int(sec * RATE)
    # Sent: two rising notes, light "whoosh away" feel.
    write(os.path.join(out, "mnd_sent.wav"), render([
        note(784, 0.25, s(0.0), 22, 0.22),     # G5
        note(1175, 0.35, s(0.075), 16, 0.20),  # D6
    ], 0.45))
    # Received: two falling notes, "landed".
    write(os.path.join(out, "mnd_received.wav"), render([
        note(1047, 0.25, s(0.0), 20, 0.20),    # C6
        note(784, 0.45, s(0.09), 11, 0.22),    # G5
    ], 0.6))
    # Notification: one quiet, rounded tone (~-23 dBFS peak). macOS plays notification sounds at
    # full alert volume, so the file itself has to be soft.
    write(os.path.join(out, "mnd_tap.wav"), render([
        note(698, 0.32, s(0.0), 13, 0.07, attack_s=0.012, octave=0.06),  # F5, almost pure sine
    ], 0.34))
    # Left behind: a clear rising C–E–G chime, twice. Noticeably louder than the others (~-7 dBFS)
    # so you hear it from a pocket, but rounded and short so it never feels like an alarm.
    chime = []
    for rep in (0.0, 1.05):
        chime += [
            note(1047, 0.5, s(rep + 0.00), 7, 0.42, attack_s=0.008, octave=0.12),  # C6
            note(1319, 0.5, s(rep + 0.16), 7, 0.40, attack_s=0.008, octave=0.12),  # E6
            note(1568, 0.8, s(rep + 0.32), 5, 0.40, attack_s=0.008, octave=0.12),  # G6
        ]
    write(os.path.join(out, "mnd_left_behind.wav"), render(chime, 2.3))

if __name__ == "__main__":
    main(sys.argv[1])

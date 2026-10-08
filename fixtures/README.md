# Shared test fixtures

Both implementations read these exact CSV files. No production microphone audio is stored.

- `tones.csv`: 114 cases. All 45 chromatic notes E2–C6 have sine and harmonic-rich cases at 48 kHz; E2, C3, C4, A4, C5, C6 also have ±25-cent cases at 44.1 kHz. Each recipe generates 0.5 seconds, delivered in 777-sample chunks to exercise window accumulation independently of capture chunk sizes. Tests inspect every analysis frame at or after 250 ms. At least 95% must have the right note and ≤5-cent absolute frequency error; no octave errors are allowed.
- `events.csv`: labeled analysis-frame sequences at 20 ms intervals. Zero frequency represents invalid/no pitch. `onset=1` represents a detected articulation, distinct from a legato pitch change.
- `expected-events.csv`: expected note sequences and onset/end times for those frame fixtures. Boundary tolerance is 150 ms. End times refer to the last valid audio frame, not the later time when the 150 ms dropout timeout confirms termination.
- `scores.csv`: canonical note order and notation duration in beats for the three bundled exercises. Tests ensure both apps' built-in definitions match.

For `tones.csv`, let `f = 440 * 2^((midi - 69)/12 + cents/1200)` and `p = 2π f i / sampleRate`:

- Sine: `0.4 * sin(p)`.
- Harmonic-rich: `0.2 * sin(p) + 0.3 * sin(2p) + 0.15 * sin(3p)`; the second harmonic is stronger than the fundamental.

Additional matching tests in Kotlin and Swift synthesize:

- Digital silence and noise. Noise uses unsigned 32-bit LCG state `seed = (1664525 * seed + 1013904223) mod 2^32`, initially 12345, scaled as `(seed/2^32 - 0.5) * 0.5`.
- A4 with a 20 dB amplitude dip from 350–420 ms followed by an attack, without silence.
- One second of continuous ±25-cent, 5 Hz frequency vibrato using phase accumulation.
- A4, silence, A4, then a legato B4, followed by silence, passing through the full audio pipeline into completed note history.

Practice tests separately verify confidence rejection, semitone-boundary hysteresis, 500 ms holds, success latching until articulation, wrong octaves, once-per-event score consumption, the sounding target after cursor advancement, manual navigation, reset, completion, and the 16-event history limit.

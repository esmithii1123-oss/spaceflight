# spaceflight — Real Liquidity Ladder for Bookmap

A single, display-only Bookmap add-on that cuts through the noisy heat map and shows
**real** resting liquidity — the levels worth watching — while deliberately *fading away*
instead of flickering when price trades through them, so buyer pull-outs stay observable.

Built with the public [BookmapAPI/DemoStrategies](https://github.com/BookmapAPI/DemoStrategies)
Simplified API. It never submits, modifies or cancels orders.

## What it does

| Concept | Implementation |
|---|---|
| **Real-liquidity detection** | A level is only promoted to "real" after it persists a minimum wall-clock time, holds depth, and stays quote-stable. Algorithmic noise (flickering, jittery quotes) never makes it onto the ladder — see `LiquidityLevel` + `RealLiquidityEngine`. |
| **Pre-market baseline** | Before the session open (default 09:30 America/New_York), every depth update feeds per-side resting-liquidity statistics into a decayed rolling window. Post-open strength is normalized against this baseline, so "big" always means *big relative to what the market normally supplies*. |
| **Rolling window + decay** | All statistics use an exponentially-weighted window (`DecayWindow`) with a configurable half-life. Nothing ever hard-expires: references age smoothly instead of resetting. |
| **Fade on break** | When price trades through a strong level, the level enters a `FADING` state whose displayed strength decays exponentially over a configurable half-life (default 12 s). Broken liquidity remains visible as an orange marker that dims progressively — you can see a buyer pull out rather than blink off on one tick. |

### What you see on the chart

- **`SF: Bid real-liquidity strength`** (green, bottom graph) — normalized strength of the bid-side ladder.
- **`SF: Ask real-liquidity strength`** (red, bottom graph) — same for asks.
- **`SF: Broken-level fade markers`** (price graph) — stamped at each broken strong level; opacity shrinks as remaining strength decays.

## Building

Requires JDK 17 (Gradle wrapper included):

```bash
./gradlew test jar
```

The loadable artifact is `build/libs/spaceflight-liquidity.jar`.

## Loading into Bookmap

1. Open Bookmap → **Settings → API plugins configuration → Add**
2. Select `spaceflight-liquidity.jar`
3. Attach the indicator **"spaceflight: Real Liquidity Ladder"** to your instrument.
4. Validate first in replay/simulation before relying on anything shown live.

## Configuration

All parameters are editable from the indicator's settings dialog:

| Parameter | Default | Meaning |
|---|---|---|
| Number of levels per side | 3 | How many strongest levels each side shows in its ladder. |
| Strictness: persistence (ms) | 3000 | Minimum wall-clock presence before a quote can count as real. Raise it to filter more aggressive algo quoting. |
| Strictness: max relative jitter | 0.45 | Allowed size instability (std/mean). Lower = stricter separation from algorithmic noise. |
| Strictness: min depth vs baseline | 0.5 | A level must hold at least this fraction of the baseline reference size to be confirmed real. Until the baseline is primed this gate passes and persistence/jitter do the filtering. |
| Clarity: fade half-life (sec) | 12 | How slowly broken levels dim away. Larger keeps pull-outs visible longer. |
| Clarity: fade floor | 0.05 | Strength below which a fading level disappears entirely. |
| Rolling window half-life (updates) | 20 | Decay speed of all statistics (baseline, depth averages). |
| Min samples before promotion | 24 | Updates a level needs before its stability statistics are trusted for promotion. Prevents early low-jitter misreads of flickering quotes. |
| Session open hour / minute | 9 / 30 | Exchange-timezone session open used to build the pre-market baseline. Rolls forward automatically each day; overnight activity re-primes the baseline. |
| Show break/fade markers | true | Toggle the price-chart fade markers. |
| Fade marker refresh (ms) | 5000 | How often a fading level re-stamps its marker while strength decays (deduplicated per price). |
| Log transitions to CSV (calibration) | false | Replay-calibration mode: appends every PROMOTE/BROKEN/BASELINE_READY transition to `~/.spaceflight/liquidity-<alias>-<time>.csv` with size, jitter and normalized depth — use it to tune strictness empirically. |

- Want a cleaner chart? Raise both strictness values and reduce levels per side.
- Want earlier warnings? Lower persistence and shorten the fade half-life.

## Interpreting signals

- **Strength rising pre-open** — incoming resting liquidity building toward the open.
- **Strong ladder into a level, then break + slow fade at that price** — buyer/seller pulled their interest after contact; classic absorption/pull-out signature.
- **One side persistently near full strength while other side is weak** — one-sided supply regime.
- **Displayed strength scales with depth**: after the baseline primes, displayed confidence multiplies lifecycle stability by how large the level actually is vs the rolling baseline reference.

## Calibrating strictness (recommended workflow)

The defaults are sane starting points, not validated constants. To calibrate them on *your* instrument:

1. Enable **Log transitions to CSV** and run several sessions through Bookmap replay.
2. Inspect `~/.spaceflight/liquidity-*.csv`: look at jitter / norm_depth distributions on rows where `type=PROMOTE` vs levels you can visually confirm were noise.
3. Adjust **max relative jitter**, **min depth vs baseline**, and **persistence** until promoted levels match what you see, then turn logging off.

## Project layout

```
src/main/java/io/spaceflight/liquidity/
├── RealLiquidityLadder.java   # Bookmap CustomModule wiring & rendering
├── RealLiquidityEngine.java   # real-vs-noise logic, trade-through detection
├── LiquidityLevel.java        # per-level state machine BUILDING→STRONG→FADING
├── PreMarketBaseline.java     # decayed rolling baseline around the session open
└── DecayWindow.java           # exponential rolling statistics
src/test/java/io/spaceflight/liquidity/
├── RealLiquidityEngineTest.java
└── PreMarketBaselineTest.java
```

## Reference

Public BookMap API repository: https://github.com/BookmapAPI/DemoStrategies

## Disclaimer

Educational/display tooling. Nothing here is trading advice, and no order-flow inference is guaranteed correct in every market regime. Validate against simulation first.

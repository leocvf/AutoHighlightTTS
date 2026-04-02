# Legacy Reliability Notes (X4 BLE `load_text` + `position`)

## Default tuning profile

The legacy path now uses a dedicated `LegacyTransportProfile` with runtime-derived defaults:

- `maxPayloadBytes`: `transport.maxPayloadBytes() - 8` (min 90 bytes)
- `writeIntervalMs`: adaptive (`12ms` healthy, `22ms` moderate, `34ms` congested)
- `positionDebounceMs`: max(`debounceMs`, `120ms`)
- `maxInFlightWrites`: `1` (single-writer semantics)
- `reconnectBackoffMs`: `[250, 600, 1200, 2000]`
- `queueCapacity`: `96`
- `meaningfulRangeDeltaChars`: `2`
- `loadQuietPeriodMs`: `220ms`
- `writeRetryLimit`: `3`
- `writeTimeoutMs`: `300ms`

The active profile is logged when a legacy document load starts.

## Reliability behavior added

1. **Deterministic document loading**
   - Text is chunked paragraph-first, then by payload-safe segments with stable ordering.
   - One `docId` is reused for the full document session.
   - A quiet period is enforced before first position updates after load.
   - Seeks that occur while loading are coalesced and only the latest is sent after quiet period.

2. **Position quality controls**
   - Positions are clamped to local document bounds.
   - Duplicate/near-duplicate ranges are dropped (`meaningfulRangeDeltaChars`).
   - High-frequency slider/TTS callbacks are debounced.
   - A last-sent cache prevents redundant packets.

3. **Write queue + recovery**
   - Single sender coroutine owns writes.
   - Queue has bounded capacity with stale-position drop/coalesce policy.
   - Bounded jittered retry for transient write failures.
   - On repeated failure, lightweight reconnect is requested and legacy session is resumed (active chunk + latest position).

4. **ACK-less pacing**
   - Pacing adapts to queue depth + measured write latency.
   - Slows down under congestion, cautiously speeds up when healthy.

5. **Observability**
   - Structured legacy counters tracked for attempts/success/fail/retries/reconnects/chunks/positions/RTT.
   - BLE test panel now displays a compact legacy telemetry summary.

## Before/after metrics (from regression tests)

- Rapid slider movement:
  - **Before target**: one position packet per callback (flood-prone).
  - **After assertion**: `< 8` position packets for 20 rapid updates; final position clamped to doc bounds.
- Retry + reconnect recovery:
  - **Before target**: repeated write failures can stall progression.
  - **After assertion**: reconnect is triggered after bounded retries when write failures persist.
- Deterministic chunking:
  - **Before target**: chunk boundaries can vary with payload pressure.
  - **After assertion**: repeated loads produce identical ordered `load_text` chunk list.

## Troubleshooting guide

- If highlights lag/jitter under weak links:
  - Check `legacySummary` in BLE test panel for high `failed`, `retry`, `rtt`, and `reconnect` values.
  - Increase `writeIntervalMs` and/or `positionDebounceMs`.
- If updates feel stale after reconnects:
  - Verify reconnect events are happening (`reconnect>0`) and `load`/`pos` counters continue increasing.
- If throughput is too low on healthy links:
  - Confirm queue depth is low and RTT stable; then reduce `writeIntervalMs` conservatively.

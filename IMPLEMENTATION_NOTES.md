# Implementation Notes (Phase 1)

## 1) Where TextToSpeech is initialized
- TTS is initialized in `AutoHighlightTTSEngine.init(app: Context)` via `TextToSpeech(app) { ... }`.
- The engine is created/configured in `AutoHighlightTTSViewModel.initTTS(...)` and used from `TTSScreen`.

## 2) Where highlighting state is updated
- Highlight state lives in `AutoHighlightTTSEngine.highlightTextPair`.
- It is updated:
  - when playback starts (`playTextToSpeech`) to sentence bounds,
  - in `onDone` when moving to next sentence,
  - in `highlightFunction()` for manual navigation/slider updates.
- Additional range callbacks come from `UtteranceProgressListener.onRangeStart`.

## 3) Cleanest extension point for spoken-range events
- `UtteranceProgressListener` inside `AutoHighlightTTSEngine.playTextToSpeech()` is the best extension point.
- It already has:
  - sentence-level lifecycle (`onStart`, `onDone`)
  - range-level updates (`onRangeStart`) when available.
- This is the recommended location to add a callback (for later phases) so BLE sync can attach without changing existing architecture.

## 4) Current architecture summary
- **AutoHighlightTTS module**:
  - owns the reusable `AutoHighlightTTSEngine` singleton and composables for highlighting UI.
  - manages text splitting, play/pause/seek, and highlighted range state.
- **app module**:
  - hosts Android app shell (`MainActivity`) and screen (`TTSScreen`).
  - `AutoHighlightTTSViewModel` builds/configures the engine.
  - UI binds to engine mutable state directly (Compose reads `highlightTextPair`, `sliderPosition`, etc.).
- **Flow of control**:
  1. ViewModel initializes engine with demo text.
  2. Screen renders text + controls.
  3. User play/seek actions call engine methods.
  4. Engine emits highlight/range progress through `UtteranceProgressListener`.

# UI and Alipay OCR Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans; implement inline and request one independent final review.

**Goal:** A softer native interface without market navigation, plus offline Alipay screenshot import with explicit review.

**Architecture:** Existing Kotlin core gains OCR-line parsing and reviewed-import rules. A separate native OCR activity runs the bundled Tesseract model and returns a validated backup-shaped portfolio for one active-main-thread commit. UI helpers own rounded components; source screenshots never persist.

**Tech Stack:** Existing Kotlin/Android Views/SQLite; Tesseract4Android 4.9.0 and tessdata_fast 4.1.0 chi_sim, no cloud services.

**Spec:** docs/superpowers/specs/2026-11-24-ui-alipay-ocr-design.md

## Global Constraints

- Develop in the requested existing checkout and branch; retain old backup compatibility and release signing identity.
- No market module; only fund-data searches/quotes/details may use network.
- OCR entirely local, no automatic portfolio writes; missing shares/cost are never inferred from amounts/current NAV.
- Native rounded UI, no heavyweight UI dependency; measure APK and offer ABI-specific builds.

## Review Focus

- Summary amounts and adjacent profit columns misread as fund shares/cost.
- Truncated names or A/C variants mapped silently to the wrong fund.
- Repeated screenshots or existing holdings adding duplicate quantities or corrupting ledger.
- Large/rotated images, cancellation and late OCR callbacks after activity destruction.
- Stale activity portfolio replacing a newer state after screenshot review.

### Task 1: Parse and review holdings safely

**Files:** core/src/main/kotlin/com/myfunds/core/{HoldingsOcr,ReviewedImport}.kt; core/src/test/kotlin/com/myfunds/core/HoldingsOcrTest.kt.

**Interfaces:** OcrLine(text,left,top,right,bottom); HoldingCandidate(name,code,shares,cost,amount,profit); HoldingsOcr.parse; ReviewedImport.apply(current, rows, groupId).

- [x] Write failing parser/import tests and observe RED.
- [x] Implement labelled extraction, monetary normalization and non-destructive reviewed imports; observe GREEN.

### Task 2: Native offline recognition and editable review

**Files:** app/src/main/java/com/myfunds/android/{OfflineOcr,OcrImportActivity}.kt; app/src/main/assets/tessdata/chi_sim.traineddata; Gradle, manifest and notices.

**Interfaces:** OfflineOcr.recognize(uri) returns lines; review activity returns only explicitly selected candidates with user-confirmed action. MainActivity reloads current portfolio before applying those actions.

- [x] Add fixed-version dependency/model and licenses, with model SHA-256 documented.
- [x] Implement bounded image decode, cancellable worker, OCR text correction/reparse, fund-code/name matching, editable numeric fields and explicit new/watch/update actions.
- [x] Compile Android source/tests and test actual OCR engine with a generated Chinese fixture; record unavailable real-device checks.

### Task 3: Softer UI and remove market module

**Files:** Ui.kt, MainActivity.kt, MarketClient.kt and app resources.

- [x] Replace platform square button surfaces with rounded ripple buttons, softer cards and pill navigation; improve spacing and metric hierarchy.
- [x] Remove market navigation, market request code and source client methods; preserve fund details.
- [x] Add screenshot-import entry, code-independent result validation and transactional persistence using freshly loaded current state.

### Task 4: Verify and deliver

- [x] Run all core tests, Android debug/test compilation, Lint and release build.
- [x] Independent read-only code review; fix important findings with regressions and rerun changed checks.
- [x] Verify signature/permissions, record universal/ABI sizes, update docs/version and deliver APKs. Explicitly distinguish synthetic-fixture testing from real Alipay/device testing.

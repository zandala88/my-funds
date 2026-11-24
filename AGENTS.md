# Project workflow

- Work in this native Android checkout. Preserve the lightweight, local-only design; networking is for public fund data. No accounts, own server, telemetry or paid data service.
- User preference: at the end of every completed user/assistant development round, commit the relevant changes and push the current branch to GitHub. Push a handoff checkpoint before starting when requested. Never force-push or commit signing keys, private screenshots, user account data, or build artifacts.
- Preserve the application ID, signing key and backup compatibility when renaming the app.
- Verify affected core behavior with regression tests; compile and lint Android changes. Explicitly distinguish host OCR checks from real-device verification.

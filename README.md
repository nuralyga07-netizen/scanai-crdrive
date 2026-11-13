# ScanAI & CR DRIVE
n**Сайт / Live demo: https://scanai.nuralyga.workers.dev**

Research prototypes (investor / jury demo). **Not medical devices.**

## ScanAI — finger-on-camera glucose "scan"
Android app (`scanai/`): put a finger on the camera + flash for ~30 s, the app extracts a PPG signal and estimates glucose (mmol/L).
- Animated UI, scan history chart, calendar of high-glucose days, profile (name / age / diagnosis)
- Offline medical AI assistant (glucose-only Q&A, advice by reading level; no API key needed)
- Download & install: [ScanAI.apk](docs/ScanAI.apk)

> Honest status: the camera-glucose model is an unvalidated research prototype. On our small pilot set it is not yet better than a mean baseline. Do not use for treatment decisions.

## CR DRIVE — driver health monitoring
- `wear/` — Galaxy Watch 4 (Wear OS): pulse, ECG, SpO2, glucose test, trip monitoring
- `phone/` — driver app: state gauge, vitals, live map, events, Glucose AI (watch / camera)
- APKs: [phone](docs/CR-DRIVE-phone.apk), [watch](docs/CR-DRIVE-watch.apk)

## Build
Open this folder in Android Studio (SDK 36). The watch module needs Samsung's proprietary Health Sensor SDK AAR (`wear/libs/samsung-health-sensor-api-1.4.1.aar`), not included. ScanAI and the phone app build without it.

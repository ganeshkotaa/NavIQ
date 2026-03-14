# NavIQ

NavIQ is an Android assistive navigation app paired with a Python backend for real-time voice-guided walking navigation, object detection, and face recognition.

The project combines:

- An Android app built with Java, CameraX, OpenCV, speech recognition, text-to-speech, vibration, and location services
- A FastAPI backend that handles route generation, WebSocket streaming, YOLOv5 object detection, and face enrollment/recognition

## Features

- Voice-guided walking navigation using live location updates
- Real-time object detection with spoken distance and direction cues
- Face detection and face recognition with voice announcements
- Voice commands for starting navigation, starting camera mode, adding faces, and deleting faces
- Haptic feedback when nearby obstacles are detected
- Live camera preview on the Android app
- WebSocket-based communication for low-latency navigation and detection updates

## How It Works

### Android app

The mobile app:

- Captures GPS location and sends it to the backend during navigation
- Streams camera frames to the backend over WebSocket for detection
- Speaks route instructions and detection alerts using Android Text-to-Speech
- Accepts voice commands through Android SpeechRecognizer
- Vibrates when objects are detected at close range

### Python backend

The backend:

- Resolves destination names into coordinates using `geopy`
- Fetches walking directions using the Google Directions API
- Streams navigation steps over WebSocket
- Runs YOLOv5 object detection on incoming camera frames
- Supports face enrollment, training, deletion, and recognition

## Tech Stack

- Android Studio / Gradle
- Java
- CameraX
- OpenCV
- Google Play Services Location
- OkHttp and Java-WebSocket
- Python
- FastAPI
- PyTorch
- YOLOv5
- OpenCV face recognition
- Google Maps Directions API

## Project Structure

```text
NavIQ-master/
├── app/                     # Android application
├── Backend/
│   └── NavIQ_backend.py     # FastAPI backend server
├── openCVLibrary/           # Bundled OpenCV Android module
├── sdk/                     # OpenCV SDK files
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

## Prerequisites

### Android side

- Android Studio
- Android SDK 34
- JDK 17
- A physical Android device recommended for camera, GPS, microphone, and vibration testing

### Backend side

- Python 3.9+
- A machine with enough resources to run PyTorch + YOLOv5
- Internet access for:
  - downloading the YOLOv5 model on first run
  - calling the Google Directions API
  - geocoding destinations

## Important Configuration

Before running the project, replace the placeholder values in the source code.

### In `Backend/NavIQ_backend.py`

Replace:

- `GOOGLE_MAPS_API_KEY = "<YOUR_GOOGLE_MAPS_API>"`

with your actual Google Maps API key.

### In `app/src/main/java/com/example/navigation/MainActivity.java`

Replace all placeholder backend URLs:

- `ws://<YOUR_IP_ADDR>:<PORT>/ws/navigate`
- `ws://<YOUR_IP_ADDR>:<PORT>/ws/detect`
- `http://<YOUR_IP_ADDR>:<PORT>/add_face/`
- `http://<YOUR_IP_ADDR>:<PORT>/delete_face/`

with the IP address and port of the machine running the FastAPI backend.

Example:

```text
ws://192.168.1.10:8000/ws/navigate
http://192.168.1.10:8000/add_face/
```

Make sure the Android device and backend machine are on the same network.

## Backend Setup

1. Open a terminal in the `Backend` folder.
2. Create and activate a virtual environment.
3. Install the required dependencies.
4. Start the FastAPI server.

Example:

```bash
cd Backend
python -m venv venv
venv\Scripts\activate
pip install fastapi uvicorn opencv-contrib-python numpy torch torchvision torchaudio pyttsx3 geopy requests python-multipart
uvicorn NavIQ_backend:app --host 0.0.0.0 --port 8000
```

## Android Setup

1. Open the project in Android Studio.
2. Let Gradle sync complete.
3. Confirm the backend IP and port are updated in `MainActivity.java`.
4. Connect an Android device or start an emulator.
5. Build and run the app.

## Permissions Used

The app requests:

- Camera
- Internet
- Fine and coarse location
- Microphone
- Vibration

## Voice Commands

The app listens after pressing the volume-up key and supports commands like:

- `start navigation`
- `navigate to Charminar`
- `stop navigation`
- `start camera`
- `start camera face`
- `start camera object`
- `start camera both`
- `stop camera`
- `add face Alice`
- `delete face Alice`

## Backend API Summary

### HTTP routes

- `GET /start_camera?mode_select=face|object|both`
- `GET /stop_camera`
- `POST /add_face/`
- `DELETE /delete_face/?name=<person>`
- `POST /navigate`

### WebSocket routes

- `/ws/detect`
- `/ws/navigate`

## Known Notes

- The backend currently uses `winsound`, so the provided backend script is Windows-oriented.
- YOLOv5 is loaded from `torch.hub`, so first startup may take longer.
- Face recognition depends on `opencv-contrib-python` because it uses `cv2.face`.
- The Android app UI is XML-based even though Compose is enabled in Gradle.
- Some placeholder strings such as `Internal Employee Portal` in the layout should be cleaned up if you want a polished final app presentation.

## Suggested GitHub Improvements

If you plan to publish this repository, consider adding:

- A `requirements.txt` for backend dependencies
- Screenshots or a demo GIF
- Environment variable support for secrets like the Google Maps API key
- Better error handling around network failures and model loading
- A cleanup pass for unused imports and duplicate dependencies

## License

No license file is currently included in this repository. Add a license before publishing publicly on GitHub if you want others to reuse the code.

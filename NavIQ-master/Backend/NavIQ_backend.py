import base64
import cv2
import os
import numpy as np
import torch
import pyttsx3
import threading
import winsound
import time
import queue
import warnings
from datetime import datetime
from fastapi import FastAPI, Form, HTTPException, WebSocket, WebSocketDisconnect, File, UploadFile
import logging
import re
import html
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from geopy.geocoders import Nominatim
from geopy.distance import geodesic
import requests
import asyncio
import json
from pydantic import BaseModel
import traceback


# ----------------------------- Setup -------------------------------------


# Suppress warnings
warnings.filterwarnings("ignore")
app = FastAPI()

# Enable CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"]
)

# Logging Setup
log_filename = "detection_log.txt"
logging.basicConfig(filename=log_filename, level=logging.INFO, format='[%(asctime)s] %(message)s')
logger = logging.getLogger(__name__)

# Google Maps API Key
GOOGLE_MAPS_API_KEY = "<YOUR_GOOGLE_MAPS_API>"
geolocator = Nominatim(user_agent="android-navigation-app")


# ----------------------------- Globals -------------------------------------


# Global Variables
camera_running = False
mode = None
cap = None
speech_queue = queue.Queue()
face_data_dir = "face_data"
trained_model_path = "trainer.yml"
last_spoken_objects = {}
last_spoken_faces = set()
SPEECH_INTERVAL = 5

os.makedirs(face_data_dir, exist_ok=True)


# ----------------------------- Face Recognition -----------------------------


# Face Recognition
face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
recognizer = cv2.face.LBPHFaceRecognizer_create()
face_labels = {}

def log_detection(detection_type, name, location=None):
    entry = f"{detection_type} detected: {name}"
    if location:
        entry += f" - Location: {location}"
    logger.info(entry)

def train_model():
    global face_labels
    images, labels = [], []
    face_labels = {}

    files = [f for f in os.listdir(face_data_dir) if f.endswith(".jpg")]
    for idx, file in enumerate(files):
        path = os.path.join(face_data_dir, file)
        img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
        images.append(img)
        labels.append(idx)
        face_labels[idx] = file.split(".")[0]

    if images:
        print(f"[train_model] Training LBPH on {len(images)} images...")
        recognizer.train(images, np.array(labels))
        recognizer.save(trained_model_path)
        print("[INFO] Face model trained successfully")
    else:
        print("[train_model] No images found; skipping training.")    
    return face_labels

def load_trained_model():
    if os.path.exists(trained_model_path):
        recognizer.read(trained_model_path)
        return True
    return False

if load_trained_model():
    face_labels = train_model()


# ----------------------------- YOLOv5 Object Detection -----------------------------


# Object Detection
model = torch.hub.load('ultralytics/yolov5', 'yolov5s', pretrained=True)
KNOWN_WIDTH_METERS = 0.31
FOCAL_LENGTH_PIXELS = 3117.4
DETECTION_THRESHOLD = 8.0
DISTANCE_THRESHOLD = 1.0
UPDATE_INTERVAL = 3


def speech_worker():
    engine = pyttsx3.init()
    engine.setProperty('rate', 150)
    engine.setProperty('volume', 0.9)
    while True:
        text = speech_queue.get()
        if text is None:
            break
        engine.say(text)
        engine.runAndWait()
# Speech Engine
threading.Thread(target=lambda: speech_worker(), daemon=True).start()


def speak_text(text):
    if not speech_queue.empty():
        return
    speech_queue.put(text)

def play_alert_sound():
    winsound.Beep(1000, 500)

def get_direction(x, y, width, height):
    center_x, center_y = width // 2, height // 2
    if y < center_y // 2:
        return "Top"
    elif y > center_y * 1.5:
        return "Down"
    elif x < center_x // 2:
        return "Left"
    elif x > center_x * 1.5:
        return "Right"
    return "Center"



# ----------------------------- Camera Handling -----------------------------


def run_camera():
    global camera_running, cap, mode, last_spoken_objects, last_spoken_faces

    cap = cv2.VideoCapture(1)
    if not cap.isOpened():
        camera_running = False
        return

    previous_detections = {}
    last_output_time = time.time()

    while camera_running:
        ret, frame = cap.read()
        if not ret:
            break
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        current_time = time.time()

        if mode in ['face', 'both']:
            faces = face_cascade.detectMultiScale(gray, 1.15, 4, minSize=(50, 50))
            for (x, y, w, h) in faces:
                face_roi = gray[y:y+h, x:x+w]
                label, confidence = -1, 100

                if face_labels:
                    label, confidence = recognizer.predict(face_roi)

                name = face_labels.get(label, "Unknown") if confidence < 50 else "Unknown"
                if name != "Unknown" and name not in last_spoken_faces:
                    last_spoken_faces.add(name)
                    speak_text(f"{name} is ahead")
                    log_detection("Face", name)

                # Draw rectangle around the face
                cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 2)
                cv2.putText(frame, name, (x, y - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 255, 0), 2)

        if mode in ['object', 'both']:
            
            r = model(test, conf=0.10)
            print("Test detections:", r.xyxy[0])
            results = model(frame,conf=0.10)
            detections = results.xyxy[0].cpu().numpy()
            detected_objects = {}
            alert_triggered = False

            for *box, conf, cls in detections:
                x1, y1, x2, y2 = map(int, box)
                label = model.names[int(cls)]
                width_in_pixels = x2 - x1
                distance = (KNOWN_WIDTH_METERS * FOCAL_LENGTH_PIXELS) / width_in_pixels if width_in_pixels > 0 else float('inf')

                if distance <= DETECTION_THRESHOLD:
                    direction = get_direction(x1, y1, frame.shape[1], frame.shape[0])
                    detected_objects[label] = (distance, direction)

                    # Draw bounding box and label for detected objects
                    cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 0, 255), 2)
                    cv2.putText(frame, f"{label} {distance:.1f}m", (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 0, 255), 2)

                    if distance <= DISTANCE_THRESHOLD:
                        alert_triggered = True

            if alert_triggered:
                threading.Thread(target=play_alert_sound, daemon=True).start()

            if current_time - last_output_time >= UPDATE_INTERVAL:
                for label, (distance, direction) in detected_objects.items():
                    key = (label, direction)
                    if key not in last_spoken_objects or (current_time - last_spoken_objects[key]) >= SPEECH_INTERVAL:
                        speak_text(f"{label} at {distance:.1f} meters, {direction}")
                        log_detection("Object", f"{label} at {distance:.1f} m, {direction}")
                        last_spoken_objects[key] = current_time
                last_output_time = current_time

        # Display the frame with detections
        cv2.imshow("Camera Feed", frame)

        # Exit on 'q' key press
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()
    camera_running = False
    
    


# ----------------------------- API Routes -----------------------------


# FastAPI Routes
@app.get("/start_camera")
def start_camera(mode_select: str):
    global camera_running, mode, last_spoken_objects, last_spoken_faces
    if camera_running:
        return {"status": "Camera already running"}
    if mode_select not in ['face', 'object', 'both']:
        raise HTTPException(status_code=400, detail="Invalid mode")

    mode = mode_select
    camera_running = True
    last_spoken_objects.clear()
    last_spoken_faces.clear()
    threading.Thread(target=run_camera, daemon=True).start()
    return {"status": f"Camera started in '{mode}' mode"}

@app.get("/stop_camera")
def stop_camera():
    global camera_running
    camera_running = False
    
    return {"status": "Camera stopped"}



@app.post("/add_face/")
async def add_face(
    name: str = Form(...),
    file: UploadFile = File(...)
):
    print(f"[add_face] received name={name}, file.filename={file.filename}")
    if not name.strip():
        raise HTTPException(status_code=400, detail="Invalid name")

    # Read JPEG bytes sent from mobile
    contents = await file.read()
    nparr = np.frombuffer(contents, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise HTTPException(status_code=400, detail="Invalid image data")

    # Detect face
    faces = face_cascade.detectMultiScale(img, 1.15, 4, minSize=(50,50))
    if len(faces) == 0:
        raise HTTPException(status_code=404, detail="No face detected")

    x, y, w, h = faces[0]
    face_img = img[y:y+h, x:x+w]
    os.makedirs(face_data_dir, exist_ok=True)
    face_path = os.path.join(face_data_dir, f"{name}.jpg")
    cv2.imwrite(face_path, face_img)

    # Retrain recognizer
    face_labels = train_model()
    recognizer.read(trained_model_path)

    return {"status": f"Face '{name}' added and model retrained"}
    
@app.delete("/delete_face/")
def delete_face(name: str):
    file_path = os.path.join(face_data_dir, f"{name}.jpg")
    if os.path.exists(file_path):
        os.remove(file_path)
        global face_labels
        face_labels = train_model()
        recognizer.read(trained_model_path)
        return {"status": f"Face '{name}' deleted and model updated"}
    else:
        raise HTTPException(status_code=404, detail="Face not found")


@app.websocket("/ws/detect")
async def detect_ws(websocket: WebSocket):
    await websocket.accept()

    try:
        # 1 Initial handshake
        init = await websocket.receive_json()
        detect_mode = init.get("mode", "object")  # "face", "object" or "both"
        print(f"[detect_ws] init mode = {detect_mode!r}")

        # Detection params
        N = 3                     # only process every 3rd frame
        frame_counter = 0
        UPDATE_INTERVAL = 0.0
        last_update_time = time.time()

        # Keep track of what we've already notified
        previous_face_keys   = set()
        previous_object_keys = set()

        while True:
            # —— PURGE STALE FRAMES —— 
            while True:
                try:
                    await asyncio.wait_for(websocket.receive_bytes(), timeout=0.001)
                except asyncio.TimeoutError:
                    break

            # Grab the next (freshest) frame
            img_bytes = await websocket.receive_bytes()
            frame_counter += 1

            # Skip until every Nth frame
            if frame_counter % N != 0:
                continue

            # Decode JPEG → BGR
            arr = np.frombuffer(img_bytes, np.uint8)
            frame_bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
            if frame_bgr is None:
                continue

            h, w = frame_bgr.shape[:2]
            now = time.time()

            # Prepare small RGB for YOLO
            frame_rgb   = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
            frame_small = cv2.resize(frame_rgb, (640, 640))

            # Collect this frame's detections separately
            face_keys   = set()
            object_keys = {}

            # — Face detection —
            if detect_mode in ("face", "both"):
                gray  = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
                faces = face_cascade.detectMultiScale(gray, 1.3, 5, minSize=(50,50))
                print(f"[detect_ws] found {len(faces)} faces")
                for (x, y, fw, fh) in faces:
                    roi = gray[y:y+fh, x:x+fw]
                    if face_labels:
                        label, conf = recognizer.predict(roi)
                    else:
                        label, conf = -1, 100

                    name = face_labels.get(label, "Unknown")
                    print(f"[detect_ws] raw face prediction: label={label}, conf={conf:.1f}")
                    cx = x + fw // 2
                    cy = y + fh // 2
                    direction = get_direction(cx, cy, w, h)
                    face_keys.add((name, direction))
                    #if name != "Unknown":
                        
                        
                        

            # — Object detection —
            if detect_mode in ("object", "both"):
                results = model(frame_small)
                dets    = results.xyxy[0].cpu().numpy()
                x_scale, y_scale = w / 640, h / 640

                for *box, conf, cls in dets:
                    x1_s, y1_s, x2_s, y2_s = box
                    pix_w = (x2_s - x1_s) * x_scale
                    if pix_w <= 0:
                        continue

                    dist = (KNOWN_WIDTH_METERS * FOCAL_LENGTH_PIXELS) / pix_w
                    if dist > DETECTION_THRESHOLD:
                        continue

                    cx = int(((x1_s + x2_s) / 2) * x_scale)
                    cy = int(((y1_s + y2_s) / 2) * y_scale)
                    direction = get_direction(cx, cy, w, h)
                    label     = model.names[int(cls)]
                    object_keys[(label, direction)] = dist

            # — Send JSON for new faces —
            new_faces = face_keys - previous_face_keys
            for (name, direction) in new_faces:
                print(f"[detect_ws] sending FACE JSON: name={name}, dir={direction}")
                await websocket.send_json({
                    "type":      "face",
                    "name":      name,
                    "direction": direction
                })
            previous_face_keys = face_keys

            # — Send JSON for new objects at UPDATE_INTERVAL —
            if now - last_update_time >= UPDATE_INTERVAL:
                new_objects = set(object_keys) - previous_object_keys
                for (label, direction) in new_objects:
                    raw_dist = object_keys[(label, direction)]
                    distance = float(raw_dist)
                    await websocket.send_json({
                        "type":      "object",
                        "label":     label,
                        "distance":  float(round(distance, 1)),
                        "direction": direction
                    })
                previous_object_keys = set(object_keys)
                last_update_time     = now

    except WebSocketDisconnect:
        print("[detect_ws] client disconnected")
    except Exception as e:
        await websocket.send_json({"type": "error", "message": str(e)}) 
        traceback.print_exc()
        await websocket.close()


    


# ----------------------------- Navigation -----------------------------

def clean(instr: str) -> str:
    # Remove step number (e.g., "Step 1: ")
    clean_instr = re.sub(r"^Step\s*\d+:\s*", "", instr)

    # Clean any unnecessary details, like distance information, or road restrictions
    # For example, removing phrases like "on the left", "on the right", "in 350m", etc.
    clean_instr = re.sub(r"(\(.*?\))", "", clean_instr)

    # Simplify or remove repetitive words like "Continue" or "At"
    clean_instr = re.sub(r"\bContinue\b|\bAt\b", "", clean_instr)

    return clean_instr.strip()



# Navigation support classes

class Coordinates(BaseModel):
    latitude: float
    longitude: float

class LocationRequest(BaseModel):
    current_location: Coordinates
    destination: str

def get_directions(src_lat, src_lon, dest_lat, dest_lon):
    """
    Fetch walking directions from Google Maps and return a list of steps,
    each containing 'instruction' (text) and 'end_location' (lat/lng) for internal use.
    """
    base_url = "https://maps.googleapis.com/maps/api/directions/json"
    params = {
        "origin":      f"{src_lat},{src_lon}",
        "destination": f"{dest_lat},{dest_lon}",
        "mode":        "walking",
        "key":         GOOGLE_MAPS_API_KEY
    }

    resp = requests.get(base_url, params=params)
    data = resp.json()

    if data.get("status") != "OK":
        # Return a single error step (no end_location)
        return [{"instruction": f"Error fetching directions: {data.get('status')}"}]

    try:
        steps_raw = data["routes"][0]["legs"][0]["steps"]
    except (IndexError, KeyError):
        return [{"instruction": "Error parsing directions: incomplete response"}]

    steps = []
    for step in steps_raw:
        instr_html = step.get("html_instructions", "")
        clean_instr = re.sub(r"<[^>]+>", "", instr_html).strip()
        end = step.get("end_location")
        if not end:
            # skip malformed steps
            continue
        steps.append({
            "instruction": clean_instr,
            "end_location": {"lat": end["lat"], "lng": end["lng"]}
        })

    if not steps:
        return [{"instruction": "No valid steps found for the given route."}]

    return steps



#app = FastAPI()
geolocator = Nominatim(user_agent="nav_app")



def log_detection(module, message, location=None):
    print(f"[{module}] {message} {f'at {location}' if location else ''}")

@app.post("/navigate")
async def navigate(req: LocationRequest):
    try:
        logger.debug(f"Navigate request received: {req}")
        src_lat = req.current_location.latitude
        src_lon = req.current_location.longitude

        dest = geolocator.geocode(req.destination)
        if not dest:
            logger.error(f"Destination not found: {req.destination}")
            return JSONResponse({"error": "Destination not found"}, status_code=404)

        dest_lat, dest_lon = dest.latitude, dest.longitude
        logger.debug(f"Resolved destination coordinates: {dest_lat}, {dest_lon}")

        instructions = get_directions(src_lat, src_lon, dest_lat, dest_lon)

        formatted_steps = []
        for idx, step in enumerate(instructions):
            formatted = f"Step {idx + 1}: {step['instruction']}"
            formatted_steps.append(formatted)
            logger.debug(f"Instruction {idx + 1}: {formatted}")

        response_data = {
            "steps": formatted_steps,
            "destination_coordinates": {
                "latitude": dest_lat,
                "longitude": dest_lon
            }
        }
        logger.debug(f"Response data: {response_data}")
        return JSONResponse(content=response_data)

    except Exception as e:
        logger.error(f"Error during navigation: {str(e)}")
        traceback.print_exc()
        return JSONResponse({"error": str(e)}, status_code=500)






@app.websocket("/ws/navigate")
async def navigate_ws(websocket: WebSocket):
    await websocket.accept()
    try:
        # 1️⃣ Receive init payload
        init = await websocket.receive_json()
        src = init.get("current_location")
        dest_name = init.get("destination")
        if not src or not dest_name:
            await websocket.send_json({"type": "error", "message": "Missing current_location or destination"})
            await websocket.close()
            return

        # 2️⃣ Geocode destination
        dest_loc = geolocator.geocode(dest_name)
        if not dest_loc:
            await websocket.send_json({"type": "error", "message": "Destination not found."})
            await websocket.close()
            return
        dest_lat, dest_lon = dest_loc.latitude, dest_loc.longitude

        # 3️⃣ Fetch walking directions
        steps = get_directions(src["latitude"], src["longitude"], dest_lat, dest_lon)
        first = steps[0]
        if "end_location" not in first:
            await websocket.send_json({"type": "error", "message": first["instruction"]})
            await websocket.close()
            return

        # 4️⃣ Initialize step tracking
        current_step = 0
        total_steps = len(steps)
        target = steps[current_step]["end_location"]

        # Compute initial distance
        last_sent_distance = geodesic(
            (src["latitude"], src["longitude"]),
            (target["lat"], target["lng"])
        ).meters

        # Send first instruction
        await websocket.send_json({
            "type": "navigation_step",
            "step": 1,
            "instruction": steps[0]["instruction"],
            "distance_to_target": round(last_sent_distance, 1)
        })

        # 5️⃣ Loop on client location updates
        while True:
            try:
                loc = await websocket.receive_json()
                lat = loc.get("latitude")
                lng = loc.get("longitude")
                if lat is None or lng is None:
                    continue

                # Check how far we are from the current step's end_location
                target = steps[current_step]["end_location"]
                dist = geodesic((lat, lng), (target["lat"], target["lng"])).meters

                # ❶ Re-send the same instruction every 50 m of progress
                if (last_sent_distance - dist) >= 50:
                    last_sent_distance = dist
                    await websocket.send_json({
                        "type": "navigation_step",
                        "step": current_step + 1,
                        "instruction": steps[current_step]["instruction"],
                        "distance_to_target": round(dist, 1)
                    })

                # ❷ If within 25 m, advance to next step
                if dist <= 25.0:
                    current_step += 1
                    if current_step < total_steps:
                        # Reset for the next step
                        target = steps[current_step]["end_location"]
                        last_sent_distance = geodesic((lat, lng), (target["lat"], target["lng"])).meters
                        await websocket.send_json({
                            "type": "navigation_step",
                            "step": current_step + 1,
                            "instruction": steps[current_step]["instruction"],
                            "distance_to_target": round(last_sent_distance, 1)
                        })
                    else:
                        await websocket.send_json({
                            "type": "done",
                            "message": "You have arrived at your destination."
                        })
                        await websocket.close()
                        return

            except WebSocketDisconnect:
                break

    except Exception as e:
        await websocket.send_json({"type": "error", "message": f"Server error: {e}"})
        await websocket.close()
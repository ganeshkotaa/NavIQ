package com.example.navigation;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import org.json.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.opencv.android.OpenCVLoader;

import okhttp3.FormBody;


import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;


import android.speech.RecognitionListener;

import android.os.Handler;
import android.os.Looper;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.FormBody;
import okhttp3.RequestBody;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;






public class MainActivity extends AppCompatActivity {

    // --- Voice & Wake-word members ---

    private SpeechRecognizer speechRecognizer;

    // --- UI elements ---
    private EditText destinationInput;
    private Spinner modeSpinner;
    private TextView statusText;
    private TextView detectionText;
    private Button navigateBtn, stopNavBtn, startCameraBtn, stopCameraBtn;
    private PreviewView cameraPreviewView;


    // --- Other members ---
    private static final int LOCATION_REQUEST_CODE = 101;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 102;
    private TextToSpeech tts;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private boolean isNavigating = false;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private WebSocketClient webSocketClient;
    private WebSocketClient detectSocket;

    private ImageCapture imageCapture;


    private static final int AUDIO_REQUEST_CODE = 101;
    private long lastFrameTime = 0;

    // vibration members
    private Vibrator vibrator;
    private boolean isVibrating = false;
    // pattern: [delay ms, vibrate ms, pause ms]
    private static final long[] VIBRATION_PATTERN = { 0, 500, 250 };




    private final OkHttpClient httpClient = new OkHttpClient();


    static {
        if (!OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "OpenCV initialization failed");
        } else {
            Log.d("OpenCV", "OpenCV successfully initialized");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Find UI views
        destinationInput  = findViewById(R.id.destinationInput);
        statusText        = findViewById(R.id.statusText);
        detectionText     = findViewById(R.id.detectionText);
        navigateBtn       = findViewById(R.id.navigateBtn);
        stopNavBtn        = findViewById(R.id.stopNavBtn);
        startCameraBtn    = findViewById(R.id.startCameraBtn);
        stopCameraBtn     = findViewById(R.id.stopCameraBtn);
        cameraPreviewView = findViewById(R.id.previewView);
        modeSpinner       = findViewById(R.id.modeSpinner);

        // Core services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        cameraExecutor      = Executors.newSingleThreadExecutor();

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        cameraPreviewView = findViewById(R.id.previewView);

        // Initialize TTS
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR) {
                tts.setLanguage(Locale.US);
            }
        });

        // Location callback
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location loc : locationResult.getLocations()) {
                    sendLocationUpdate(loc);
                }
            }
        };

        // Initialize voice & wake-word

        initSpeechRecognizer();

        setupCameraX();

        // Setup WebSockets and UI callbacks
        setupWebSocket();
        setupDetectWebSocket();
        navigateBtn.setOnClickListener(v -> startNavigation());
        stopNavBtn.setOnClickListener(v -> stopNavigation());
        startCameraBtn.setOnClickListener(v -> checkCameraPermissionAndStart());
        stopCameraBtn.setOnClickListener(v -> stopCamera());


        // In onCreate():
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.RECORD_AUDIO },
                    AUDIO_REQUEST_CODE);
        }


    }

    /** Called when "wake" is triggered via volume-up button */
    private void onWakeTriggered() {
        tts.speak("Yes?", TextToSpeech.QUEUE_FLUSH, null, "wake");
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizer.startListening(intent);
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            onWakeTriggered();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }



    /** Initialize Android SpeechRecognizer */
    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }

            @Override
            public void onError(int error) {
                Log.e("ASR", "Recognition error: " + error);
                // Optionally prompt user to retry
                tts.speak("Sorry, I didn't catch that.", TextToSpeech.QUEUE_FLUSH, null, "err");
            }

            @Override
            public void onResults(Bundle results) {
                List<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                );
                if (matches != null && !matches.isEmpty()) {
                    handleCommand(matches.get(0));
                }
            }

            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
    }





    /** Parse and handle recognized voice commands */
    private void handleCommand(String transcript) {
        String cmd = transcript.toLowerCase(Locale.US);
        if (cmd.contains("start navigation") || cmd.contains("navigate to")) {
            String dest = cmd.contains("navigate to")
                    ? cmd.replaceFirst(".*navigate to", "").trim()
                    : destinationInput.getText().toString();
            startNavigation(dest);
        } else if (cmd.contains("stop navigation")) {
            stopNavigation();
        } else if (cmd.contains("start camera")) {
            // Select mode if mentioned
            for (int i = 0; i < modeSpinner.getCount(); i++) {
                String mode = modeSpinner.getItemAtPosition(i)
                        .toString().toLowerCase();
                if (cmd.contains(mode)) {
                    modeSpinner.setSelection(i);
                    break;
                }
            }
            checkCameraPermissionAndStart();
        } else if (cmd.contains("stop camera")) {
            stopCamera();
        } else if (cmd.startsWith("add face")) {
            String name = cmd.replaceFirst("add face\\s*", "").trim();
            if (!name.isEmpty()) {
                startAddFaceSequence(name);
            } else {
                tts.speak("Please say the name after add face.",
                        TextToSpeech.QUEUE_FLUSH, null, "err");
            }
        }else if (cmd.startsWith("delete face")) {
            String name = cmd.replaceFirst("delete face\\s*", "").trim();
            if (!name.isEmpty()) {
                tts.speak("Deleting face " + name, TextToSpeech.QUEUE_FLUSH, null, "prep");
                deleteFaceByVoice(name);
            } else {
                tts.speak("Please say the name after delete face.", TextToSpeech.QUEUE_FLUSH, null, "err");
            }
        }
        else {
            tts.speak("Sorry, I didn't understand.",
                    TextToSpeech.QUEUE_FLUSH, null, "err");
        }
    }

    // ---------------- Navigation WebSocket ----------------

    private void setupWebSocket() {
        try {
            URI uri = new URI("ws://<YOUR_IP_ADDR>:<PORT>/ws/navigate");
            webSocketClient = new WebSocketClient(uri) {
                @Override public void onOpen(ServerHandshake handshakedata) {}
                @Override public void onMessage(String message) {
                    runOnUiThread(() -> {
                        try {
                            JSONObject obj = new JSONObject(message);
                            String type = obj.optString("type");
                            String spokenText;

                            if ("navigation_step".equals(type)) {
                                int step = obj.optInt("step");
                                String instruction = obj.optString("instruction");
                                //double dist = obj.optDouble("distance_to_target");

                                // customize this string however you like:
                                spokenText = String.format("Step %d. %s.", step, instruction);

                            } else if ("done".equals(type)) {
                                spokenText = obj.optString("message");  // “You have arrived…”
                            } else if ("error".equals(type)) {
                                spokenText = "Error: " + obj.optString("message");
                            } else {
                                // fallback for unexpected messages
                                spokenText = message;
                            }

                            // update UI
                            statusText.setText(spokenText);

                            // speak formatted text
                            tts.speak(spokenText,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "instrID");

                            if ("done".equals(type)) {
                                stopNavigation();
                            }
                        } catch (Exception e) {
                            // if your server ever sends non-JSON, fall back gracefully:
                            statusText.setText(message);
                            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "instrID");
                        }
                    });
                }

                @Override public void onClose(int code, String reason, boolean remote) {}
                @Override public void onError(Exception ex) {}
            };
            webSocketClient.connect();
        } catch (Exception e) {
            Log.e("WebSocket", "URI Error", e);
        }
    }

    private void startNavigation() {
        String dest = destinationInput.getText().toString().trim();
        startNavigation(dest);
    }

    private void startNavigation(String dest) {
        if (dest.isEmpty()) {
            statusText.setText("Please enter a destination.");
            return;
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_REQUEST_CODE);
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        sendWebSocketInit(location, dest);
                        startLocationUpdates();
                        isNavigating = true;
                        navigateBtn.setEnabled(false);
                    } else {
                        statusText.setText("Couldn't get location.");
                    }
                });
    }

    private void sendWebSocketInit(Location location, String dest) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject init = new JSONObject();
                init.put("current_location", new JSONObject()
                        .put("latitude", location.getLatitude())
                        .put("longitude", location.getLongitude()));
                init.put("destination", dest);
                webSocketClient.send(init.toString());
            } catch (Exception e) {
                Log.e("WebSocket", "Init error", e);
            }
        }
    }

    private void sendLocationUpdate(Location location) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject upd = new JSONObject();
                upd.put("latitude", location.getLatitude());
                upd.put("longitude", location.getLongitude());
                webSocketClient.send(upd.toString());
            } catch (Exception e) {
                Log.e("WebSocket", "Loc update error", e);
            }
        }
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void startLocationUpdates() {
        if (!isNavigating) {
            LocationRequest req = LocationRequest.create()
                    .setInterval(3000)
                    .setFastestInterval(2000)
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            fusedLocationClient.requestLocationUpdates(
                    req, locationCallback, Looper.getMainLooper());
        }
    }

    private void stopNavigation() {
        if (isNavigating) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            isNavigating = false;
        }
        if (webSocketClient != null) webSocketClient.close();
        navigateBtn.setEnabled(true);
        statusText.setText("Navigation stopped.");
    }

    // ------------- Detection WebSocket & Camera --------------

    private void setupDetectWebSocket() {
        try {
            URI uri = new URI("ws://<YOUR_IP_ADDR>:<PORT>/ws/detect");
            detectSocket = new WebSocketClient(uri) {
                @Override public void onOpen(ServerHandshake handshakedata) {
                    try {
                        String selected = modeSpinner
                                .getSelectedItem().toString().toLowerCase();
                        JSONObject init = new JSONObject()
                                .put("mode", selected);
                        send(init.toString());
                    } catch (Exception e) {
                        Log.e("DetectWS", "init error", e);
                    }
                    runOnUiThread(() -> startCameraMode());
                }
                @Override public void onMessage(String msg) {
                    runOnUiThread(() -> {
                        try {
                            JSONObject o = new JSONObject(msg);
                            String type = o.getString("type");
                            if (type.equals("face")) {
                                String name = o.getString("name");
                                detectionText.append("👤 Face: " + name + "\n");
                                tts.speak(name + " ahead",
                                        TextToSpeech.QUEUE_FLUSH,
                                        null, "FACE");
                            } else if (type.equals("object")) {
                                String lbl = o.getString("label");
                                double d   = o.getDouble("distance");
                                String dir = o.getString("direction");
                                detectionText.append(String.format("📦 %s at %.1fm %s\n", lbl, d, dir));

                                tts.speak(String.format("%s at %.1f meters %s", lbl, d, dir),

                                        TextToSpeech.QUEUE_ADD,
                                        null, "OBJ");
                                vibrateByDistance(d);
                            }
                        } catch (Exception e) {
                            Log.e("DetectWS","parse error",e);
                        }
                    });
                }
                @Override public void onClose(int code, String reason, boolean remote) {}
                @Override public void onError(Exception ex) {
                    Log.e("DetectWS","socket error",ex);
                }
            };
        } catch (Exception e) {
            Log.e("DetectWS","setup error",e);
        }
    }

    private void vibrateByDistance(double d) {
        if (vibrator == null) return;

        boolean shouldVibrate = (d < 2.0);

        if (shouldVibrate && !isVibrating) {
            // start repeating vibration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createWaveform(
                        VIBRATION_PATTERN,
                        1  // repeat index => loop from the vibrate phase
                );
                vibrator.vibrate(effect);
            } else {
                // deprecated fallback
                vibrator.vibrate(VIBRATION_PATTERN, 1);
            }
            isVibrating = true;

        } else if (!shouldVibrate && isVibrating) {
            // stop as soon as we're out of range
            vibrator.cancel();
            isVibrating = false;
        }
        // else: no change, leave vibration running or stopped
    }

    private void checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            if (detectSocket == null) setupDetectWebSocket();
            if (!detectSocket.isOpen()) detectSocket.connect();
            else startCameraMode();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void startCameraMode() {
        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new android.util.Size(1280,720))
                .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        analysis.setAnalyzer(cameraExecutor, image -> {
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < 500) { image.close(); return; }
            lastFrameTime = now;
            try {
                ImageProxy.PlaneProxy[] planes = image.getPlanes();
                int w = image.getWidth(), h = image.getHeight();
                ByteBuffer y = planes[0].getBuffer();
                ByteBuffer u = planes[1].getBuffer();
                ByteBuffer v = planes[2].getBuffer();
                int yStride = planes[0].getRowStride();
                int uvStride = planes[1].getRowStride();
                int uvPix = planes[1].getPixelStride();
                byte[] nv21 = new byte[w*h*3/2];
                int pos=0;
                for(int row=0;row<h;row++){
                    y.position(row*yStride);
                    y.get(nv21,pos,w);
                    pos+=w;
                }
                for(int row=0;row<h/2;row++){
                    int base = row*uvStride;
                    for(int col=0;col<w/2;col++){
                        nv21[pos++]=v.get(base+col*uvPix);
                        nv21[pos++]=u.get(base+col*uvPix);
                    }
                }
                YuvImage yuv = new YuvImage(nv21,
                        android.graphics.ImageFormat.NV21,w,h,null);
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                if (!yuv.compressToJpeg(new Rect(0,0,w,h),80,os)){
                    image.close();return;
                }
                if (detectSocket!=null && detectSocket.isOpen()){
                    detectSocket.send(os.toByteArray());
                }
            }catch(Exception e){
                Log.e("Analyzer","stream error",e);
            }finally{ image.close(); }
        });
        Preview preview = new Preview.Builder().build();
        ProcessCameraProvider.getInstance(this)
                .addListener(() -> {
                    try {
                        cameraProvider = ProcessCameraProvider
                                .getInstance(this).get();
                        cameraProvider.unbindAll();
                        preview.setSurfaceProvider(
                                cameraPreviewView.getSurfaceProvider());
                        cameraProvider.bindToLifecycle(
                                this, selector, preview, analysis);
                    } catch (Exception e) {
                        Log.e("CameraX","bind failed",e);
                    }
                }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (detectSocket != null && detectSocket.isOpen()) {
            detectSocket.close();
            vibrator.cancel();
            isVibrating = false;
        }
        detectSocket = null;
    }

    private void startAddFaceSequence(String name) {
        tts.speak("Please align. Capturing in five seconds.", TextToSpeech.QUEUE_FLUSH, null, "prep");
        Handler h = new Handler(Looper.getMainLooper());
        for (int i = 5; i >= 1; i--) {
            int count = i;
            h.postDelayed(() ->
                            tts.speak(String.valueOf(count), TextToSpeech.QUEUE_ADD, null, "count" + count),
                    (6 - count) * 1000
            );
        }
        // After 6 seconds (5→1), do the capture
        h.postDelayed(() -> captureFaceAndUpload(name), 6000);
    }
    // (unchanged) this just does the HTTP POST
    private void captureFaceAndUpload(String name) {
        // 1) Make sure cameraProvider is ready
        if (cameraProvider == null) {
            Log.e("AddFace", "CameraProvider not initialized");
            return;
        }

        // 2) Unbind prior use-cases (preview/analysis)
        cameraProvider.unbindAll();

        // 3) Setup front-camera-only capture
        CameraSelector frontSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        imageCapture = new ImageCapture.Builder()
                .setTargetRotation(getWindowManager()
                        .getDefaultDisplay().getRotation())
                .build();

        cameraProvider.bindToLifecycle(
                this,
                frontSelector,
                imageCapture
        );

        // 4) Take the picture
        File tmp = new File(getCacheDir(), "new_face.jpg");
        ImageCapture.OutputFileOptions opts =
                new ImageCapture.OutputFileOptions.Builder(tmp).build();

        imageCapture.takePicture(opts,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults results) {
                        runOnUiThread(() -> {
                            statusText.setText("Face data captured!");
                            tts.speak("Captured", TextToSpeech.QUEUE_FLUSH, null, "ack");
                        });
                        uploadFaceJpeg(name, tmp);
                        // 5) Restore your normal back-camera preview+analysis
                        setupCameraX();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exc) {
                        runOnUiThread(() -> {
                            statusText.setText("Capture failed");
                            tts.speak("Failed to capture", TextToSpeech.QUEUE_FLUSH, null, "err");
                        });
                        setupCameraX();
                    }
                });
    }


    private void uploadFaceJpeg(String name, File photoFile) {
        MediaType JPEG = MediaType.parse("image/jpeg");
        RequestBody fileBody = RequestBody.create(photoFile, JPEG);

        RequestBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
                .addFormDataPart("file", "face.jpg", fileBody)
                .build();

        Request req = new Request.Builder()
                .url("http://<YOUR_IP_ADDR>:<PORT>/add_face/")
                .post(multipart)
                .build();

        httpClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    tts.speak("Failed to add face " + name,
                            TextToSpeech.QUEUE_FLUSH, null, "err");
                    statusText.setText("Upload failed: " + e.getMessage());
                    Log.e("AddFace", "HTTP failure", e);
                });
            }
            @Override public void onResponse(Call call, Response resp)
                    throws IOException {
                boolean ok = resp.isSuccessful();
                String body = resp.body().string();
                runOnUiThread(() -> {
                    if (ok) {
                        tts.speak("Face " + name + " added successfully",
                                TextToSpeech.QUEUE_FLUSH, null, "ok");
                    } else {
                        tts.speak("Error adding face " + name,
                                TextToSpeech.QUEUE_FLUSH, null, "err");
                        statusText.setText("Server error: " + body);
                        Log.e("AddFace", "Server responded " + resp.code() + ": " + body);

                    }
                });
            }
        });
    }

    private void setupCameraX() {
        ProcessCameraProvider.getInstance(this)
                .addListener(() -> {
                            try {
                                // Grab the provider and save it
                                cameraProvider = ProcessCameraProvider.getInstance(this).get();

                                // Unbind anything that’s already bound
                                cameraProvider.unbindAll();

                                // Preview use-case
                                Preview preview = new Preview.Builder().build();
                                preview.setSurfaceProvider(cameraPreviewView.getSurfaceProvider());

                                // Analysis use-case (if you need it)
                                ImageAnalysis analysis = new ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build();
                                analysis.setAnalyzer(
                                        Executors.newSingleThreadExecutor(),
                                        this::analyzeFrame
                                );

                                // Capture use-case
                                imageCapture = new ImageCapture.Builder()
                                        .setTargetRotation(
                                                getWindowManager().getDefaultDisplay().getRotation()
                                        )
                                        .build();

                                // Bind to lifecycle with back camera by default
                                cameraProvider.bindToLifecycle(
                                        this,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis,
                                        imageCapture
                                );
                            } catch (Exception e) {
                                Log.e("CameraX", "bind failed", e);
                            }
                        },
                        ContextCompat.getMainExecutor(this)
                );
    }

    private void analyzeFrame(ImageProxy image) {
        // your existing byte→JPEG→detectSocket.send logic here...
        image.close();
    }
    private void deleteFaceByVoice(String name) {
        // Build URL: GET /delete_face/?name=Alice
        HttpUrl url = HttpUrl.get("http://<YOUR_IP_ADDR>:<PORT>/delete_face/")
                .newBuilder()
                .addQueryParameter("name", name)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    statusText.setText("Delete failed: " + e.getMessage());
                    tts.speak("Failed to delete face " + name, TextToSpeech.QUEUE_FLUSH, null, "err");
                    Log.e("DeleteFace", "Network error", e);
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        statusText.setText("Face " + name + " deleted");
                        tts.speak("Face " + name + " deleted successfully", TextToSpeech.QUEUE_FLUSH, null, "ok");
                    } else {
                        statusText.setText("Error deleting face: " + response.code());
                        tts.speak("Error deleting face " + name, TextToSpeech.QUEUE_FLUSH, null, "err");
                        Log.e("DeleteFace", "Server error " + response.code() + ": " + body);
                    }
                });
            }
        });
    }




    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length>0 && grantResults[0]
                    == PackageManager.PERMISSION_GRANTED) {
                checkCameraPermissionAndStart();
            } else {
                statusText.setText("Camera permission is required.");
            }
        } else if (requestCode == LOCATION_REQUEST_CODE) {
            startNavigation();
        }
        if (requestCode == AUDIO_REQUEST_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            initSpeechRecognizer();
        } else {
            statusText.setText("Audio permission is required for voice commands.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Shutdown TTS
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        // Close navigation socket
        if (webSocketClient != null) {
            webSocketClient.close();
        }

        // Close detection socket
        if (detectSocket != null) {
            detectSocket.close();
        }

        // Stop location updates
        if (vibrator != null) {
            vibrator.cancel();
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

    }

}
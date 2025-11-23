package com.example.plantapp;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {

    private static final String ESP_SSID = "ESP32-CAM";
    private static final String ESP_PASS = "12345678";
    private static final String ESP_BASE = "http://192.168.4.1";
    private static final int REQ_WIFI = 1001;

    // How long we wait for camera connection before timing out (ms)
    private static final long CONNECT_TIMEOUT_MS = 30_000L;

    private String userRole;
    private WebView webView;
    private ImageButton shutterButton;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network espNetwork;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // --- Connecting overlay views + animation ---
    private View connectingOverlay;
    private ImageView overlayLogo;
    private TextView overlayStatusText;
    private ObjectAnimator logoPulseAnimator;

    // --- Timeout handling for camera connection ---
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable connectTimeoutRunnable;
    private boolean connectionDone = false; // guards against double-callback/timeout

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera);

        userRole = getIntent().getStringExtra("userRole");

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        webView = findViewById(R.id.webView);
        shutterButton = findViewById(R.id.ShutterButton);
        ImageButton backButton = findViewById(R.id.BackButton);

        // find overlay views
        connectingOverlay = findViewById(R.id.cameraConnectingOverlay);
        overlayLogo       = findViewById(R.id.cameraOverlayLogo);
        overlayStatusText = findViewById(R.id.cameraOverlayStatusText);

        if (webView == null || shutterButton == null || backButton == null) {
            Toast.makeText(this, "Missing views in activity_camera.xml", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        webView.setWebViewClient(new WebViewClient());
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        backButton.setOnClickListener(v -> {
            startActivity(new Intent(CameraActivity.this, MainActivity.class));
            finish();
        });

        shutterButton.setOnClickListener(v -> {
            shutterButton.setEnabled(false);
            captureFromEsp();
        });

        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        // Android 13 test phone => we only use the modern path
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Requires Android 10 or higher", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!hasWifiPermissions()) {
            requestWifiPermissions();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectToEspAp();
            }
        }
    }

    // ---- Permissions (Android 13) ----
    private boolean hasWifiPermissions() {
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean nearby = ContextCompat.checkSelfPermission(this,
                Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        return fine && nearby;
    }

    private void requestWifiPermissions() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                },
                REQ_WIFI
        );
    }

    // ---- overlay helpers ----
    private void showConnectingOverlay() {
        if (connectingOverlay == null) return;

        connectingOverlay.setVisibility(View.VISIBLE);
        connectingOverlay.setAlpha(1f);

        if (overlayLogo != null) {
            overlayLogo.setVisibility(View.VISIBLE);
            overlayLogo.setAlpha(1f);
            overlayLogo.setScaleX(1f);
            overlayLogo.setScaleY(1f);
        }

        if (overlayStatusText != null) {
            overlayStatusText.setText("Connecting to camera...");
        }

        startLogoPulse();
    }

    private void startLogoPulse() {
        if (overlayLogo == null) return;

        if (logoPulseAnimator != null) {
            logoPulseAnimator.cancel();
        }

        PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.15f);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f);

        logoPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(overlayLogo, scaleX, scaleY);
        logoPulseAnimator.setDuration(700);
        logoPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        logoPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        logoPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        logoPulseAnimator.start();
    }

    private void stopLogoPulse() {
        if (logoPulseAnimator != null) {
            logoPulseAnimator.cancel();
            logoPulseAnimator = null;
        }
    }

    private void fadeOutConnectingOverlay() {
        if (connectingOverlay == null) return;

        stopLogoPulse();

        connectingOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    connectingOverlay.setVisibility(View.GONE);
                    connectingOverlay.setAlpha(1f);
                })
                .start();
    }

    private void cancelConnectTimeout() {
        if (connectTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
    }

    // ---- Connect to ESP AP (Android 10+) ----
    @RequiresApi(29)
    private void connectToEspAp() {
        WifiNetworkSpecifier.Builder builder =
                new WifiNetworkSpecifier.Builder().setSsid(ESP_SSID);

        if (ESP_PASS != null && ESP_PASS.length() >= 8) {
            builder.setWpa2Passphrase(ESP_PASS);
        }

        WifiNetworkSpecifier spec = builder.build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build();

        if (connectivityManager == null) {
            Toast.makeText(this, "No ConnectivityManager", Toast.LENGTH_LONG).show();
            return;
        }

        // While connecting: disable shutter + show overlay
        shutterButton.setEnabled(false);
        showConnectingOverlay();

        connectionDone = false; // new connection attempt

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // If timeout already fired or we've already handled result, ignore.
                if (connectionDone) return;
                connectionDone = true;
                cancelConnectTimeout();

                espNetwork = network;
                connectivityManager.bindProcessToNetwork(network);

                runOnUiThread(() -> {
                    String html =
                            "<html><head><style>" +
                                    "body,html{margin:0;padding:0;background:black;overflow:hidden;}" +
                                    "img{width:100vw;height:100vh;object-fit:cover;}" +
                                    "</style></head><body>" +
                                    "<img src='" + ESP_BASE + ":81/stream' />" +
                                    "</body></html>";

                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                    Toast.makeText(CameraActivity.this, "Connected to ESP Wi-Fi", Toast.LENGTH_SHORT).show();

                    // Camera ready → enable shutter and hide overlay
                    shutterButton.setEnabled(true);
                    fadeOutConnectingOverlay();
                });
            }

            @Override
            public void onUnavailable() {
                if (connectionDone) return;
                connectionDone = true;
                cancelConnectTimeout();

                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "ESP network unavailable", Toast.LENGTH_SHORT).show();
                    fadeOutConnectingOverlay();
                    shutterButton.setEnabled(false);
                });
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);

        // --- TIMEOUT: if no onAvailable within CONNECT_TIMEOUT_MS, go back to main ---
        connectTimeoutRunnable = () -> {
            if (connectionDone) return; // already handled
            connectionDone = true;

            try {
                if (connectivityManager != null && networkCallback != null) {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {}

            fadeOutConnectingOverlay();
            Toast.makeText(CameraActivity.this,
                    "Camera connection timed out. Returning to home.",
                    Toast.LENGTH_LONG).show();

            // Return to main page
            Intent i = new Intent(CameraActivity.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        };

        timeoutHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    private void releaseEspNetwork() {
        try {
            if (connectivityManager != null) {
                connectivityManager.bindProcessToNetwork(null);
                if (networkCallback != null) {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                }
            }
        } catch (Exception ignored) {
        }
        espNetwork = null;
    }

    // ---- Capture from ESP and upload ----
    private void captureFromEsp() {
        io.execute(() -> {
            try {
                URL url = new URL(ESP_BASE + "/capture");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new RuntimeException("HTTP " + code);
                }

                InputStream in = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                in.close();
                conn.disconnect();

                byte[] jpeg = baos.toByteArray();

                // Disconnect from ESP so phone can use its normal internet again
                runOnUiThread(this::releaseEspNetwork);

                // Give the phone a moment to switch back (mobile data / normal Wi-Fi)
                Thread.sleep(2000);

                uploadToFirebase(jpeg);

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "Capture error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    shutterButton.setEnabled(true);
                });
            }
        });
    }

    private void uploadToFirebase(byte[] jpeg) {
        String uid;
        try {
            uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(CameraActivity.this,
                        "Not logged in", Toast.LENGTH_SHORT).show();
                shutterButton.setEnabled(true);
            });
            return;
        }

        String fileName = System.currentTimeMillis() + ".jpg";
        StorageReference ref = FirebaseStorage.getInstance()
                .getReference("captures")
                .child(uid)
                .child(fileName);

        ref.putBytes(jpeg)
                .continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    return ref.getDownloadUrl();
                })
                .addOnSuccessListener(uri -> {
                    // Only go to DescriptionActivity – no Firestore write here
                    Intent intent =
                            new Intent(CameraActivity.this, DescriptionActivity.class);
                    intent.putExtra("userRole", userRole);
                    intent.putExtra("imageUrl", uri.toString());
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(CameraActivity.this,
                            "Upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    shutterButton.setEnabled(true);
                });
    }

    // ---- Lifecycle ----
    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelConnectTimeout();
        releaseEspNetwork();
        io.shutdown();
        if (webView != null) webView.destroy();
        stopLogoPulse();
    }

    // ---- Permission result ----
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WIFI) {
            boolean granted = true;
            for (int r : grantResults) {
                granted &= (r == PackageManager.PERMISSION_GRANTED);
            }
            if (granted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectToEspAp();
                }
            } else {
                Toast.makeText(this,
                        "Wi-Fi permissions are required to connect to ESP",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}

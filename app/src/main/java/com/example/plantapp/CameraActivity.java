package com.example.plantapp;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
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

import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CameraActivity extends AppCompatActivity {

    private static final String ESP_SSID = "ESP32-CAM";
    private static final String ESP_PASS = "12345678";
    private static final String ESP_BASE = "http://192.168.4.1";
    private static final int REQ_PERMS = 1001;

    private String userRole;
    private WebView webView;
    private ImageButton shutterButton;

    // 29+ binding
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback espCallback;
    private Network espNetwork;

    // ≤ 28 legacy
    private WifiManager wifiManager;
    private int legacyNetId = -1;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ExecutorService io = Executors.newSingleThreadExecutor();

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

        if (webView == null || shutterButton == null || backButton == null) {
            Toast.makeText(this, "Layout IDs missing (webView/Shutter/Back). Check activity_camera.xml", Toast.LENGTH_LONG).show();
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
            captureFromEspThenUpload();
        });

        wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        if (wifiManager == null) {
            Toast.makeText(this, "WifiManager unavailable", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        connectToEspApThenLoad();
    }

    // ---------- Permissions ----------
    private boolean haveWifiPermissions() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean access = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE)
                == PackageManager.PERMISSION_GRANTED;
        boolean change = ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE)
                == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 33) {
            boolean nearby = ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
            return fine && access && change && nearby;
        }
        return fine && access && change;
    }

    private void requestWifiPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.NEARBY_WIFI_DEVICES,
                            Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.CHANGE_WIFI_STATE
                    },
                    REQ_PERMS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.CHANGE_WIFI_STATE
                    },
                    REQ_PERMS);
        }
    }

    // ---------- Connect to ESP ----------
    private void connectToEspApThenLoad() {
        if (!haveWifiPermissions()) {
            requestWifiPermissions();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectQPlus();
        } else {
            connectLegacy();
        }
    }

    @RequiresApi(29)
    private void connectQPlus() {
        WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder().setSsid(ESP_SSID);
        if (ESP_PASS != null && ESP_PASS.length() >= 8) builder.setWpa2Passphrase(ESP_PASS);
        WifiNetworkSpecifier spec = builder.build();

        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build();

        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "ConnectivityManager unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        espCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                cm.bindProcessToNetwork(network);
                espNetwork = network;
                runOnUiThread(() -> {
                    // This commented out code alters camera filters on the esp side
                    //Can Minorly improve quality but leads to feed crashes and lagging
                    // Adjust brightness  +2 = maximum brightness to -2 = minimum brightness
                    /*   new Thread(() -> {
                        try {
                            URL url = new URL(ESP_BASE + "/control?var=brightness&val=2");
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.getResponseCode();
                            conn.disconnect();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start(); */

                    // adjust contrast -2 to 2
                   /* new Thread(() -> {
                        try {
                            URL url = new URL(ESP_BASE + "/control?var=contrast&val=1"); // slight contrast boost
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.getResponseCode();
                            conn.disconnect();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start(); */

                    //Adjust JPEG quality
                 /*   new Thread(() -> {
                        try {
                            // Example: set quality (0 = highest, 63 = lowest for ESP32-CAM)
                            URL url = new URL(ESP_BASE + "/control?var=quality&val=3");
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.getResponseCode();
                            conn.disconnect();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start(); */
                    //Skyler - changed this block to get rid of black background
                    String html = "<html><head><style>" +
                            "body,html {margin:0;padding:0;background:black;overflow:hidden;}" +
                            "img {width:100vw;height:100vh;object-fit:cover;}" +
                            "</style></head><body>" +
                            "<img src='" + ESP_BASE + ":81/stream' />" +
                            "</body></html>";

                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

                    Toast.makeText(CameraActivity.this, "Connected to ESP Wi-Fi", Toast.LENGTH_SHORT).show();
                });

            }
            @Override public void onUnavailable() {
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "ESP Wi-Fi unavailable", Toast.LENGTH_SHORT).show());
            }
        };
        cm.requestNetwork(req, espCallback);
    }

    @SuppressWarnings("deprecation")
    private void connectLegacy() {
        try {
            if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestWifiPermissions();
                return;
            }
            List<WifiConfiguration> existing = wifiManager.getConfiguredNetworks();
            if (existing != null) {
                for (WifiConfiguration c : existing) {
                    if (("\"" + ESP_SSID + "\"").equals(c.SSID)) {
                        wifiManager.removeNetwork(c.networkId);
                        wifiManager.saveConfiguration();
                        break;
                    }
                }
            }

            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = "\"" + ESP_SSID + "\"";
            if (ESP_PASS != null && ESP_PASS.length() >= 8) {
                cfg.preSharedKey = "\"" + ESP_PASS + "\"";
            } else {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            }

            legacyNetId = wifiManager.addNetwork(cfg);
            if (legacyNetId == -1) {
                Toast.makeText(this, "Failed to add ESP network", Toast.LENGTH_SHORT).show();
                return;
            }

            wifiManager.disconnect();
            wifiManager.enableNetwork(legacyNetId, true);
            wifiManager.reconnect();

            webView.postDelayed(() -> {
                webView.loadUrl(ESP_BASE + ":81/stream");
                Toast.makeText(this, "Connecting to ESP Wi-Fi…", Toast.LENGTH_SHORT).show();
            }, 1500);
        } catch (SecurityException se) {
            Toast.makeText(this, "Wi-Fi permission denied: " + se.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Wi-Fi error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void releaseEspNetwork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (cm != null) cm.bindProcessToNetwork(null);
            if (cm != null && espCallback != null) {
                try { cm.unregisterNetworkCallback(espCallback); } catch (Exception ignored) {}
            }
            espNetwork = null;
        } else {
            if (wifiManager != null && legacyNetId != -1) {
                try {
                    wifiManager.disableNetwork(legacyNetId);
                    wifiManager.disconnect();
                    wifiManager.reconnect();
                } catch (Exception ignored) {}
            }
            legacyNetId = -1;
        }
    }

    // ---------- Capture & Upload ----------
    private void captureFromEspThenUpload() {
        io.execute(() -> {
            try {
                Request req = new Request.Builder()
                        .url(ESP_BASE + "/capture")
                        .get()
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        runOnUiThread(() -> {
                            shutterButton.setEnabled(true);
                            Toast.makeText(this, "Capture failed: " +
                                    (resp != null ? resp.code() : "no response"), Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    byte[] jpeg = resp.body().bytes();

                    // Leave ESP AP and return to default (school Wi-Fi / LTE)
                    runOnUiThread(this::releaseEspNetwork);

                    // Wait for validated internet, then upload
                    String fileName = System.currentTimeMillis() + ".jpg";
                    waitForInternetThenUpload(jpeg, fileName);
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    shutterButton.setEnabled(true);
                });
            }
        });
    }

    // Wait until we have real internet before uploading (prevents "object does not exist")
    private void waitForInternetThenUpload(byte[] jpeg, String fileName) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        io.execute(() -> {
            for (int i = 0; i < 20; i++) { // ~10s max wait
                Network n = cm.getActiveNetwork();
                if (n != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps != null &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {

                        // ✅ Get logged in user
                        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

                        // ✅ Store file under captures/<uid>/<fileName>.jpg
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

                                    // ✅ Save metadata to Firestore
                                    FirebaseFirestore db = FirebaseFirestore.getInstance();
                                    Map<String, Object> data = new HashMap<>();
                                    data.put("url", uri.toString());
                                    data.put("role", userRole);
                                    data.put("timestamp", System.currentTimeMillis());

                                    db.collection("users")
                                            .document(uid)
                                            .collection("captures")
                                            .add(data) // auto doc ID
                                            .addOnSuccessListener(doc -> {
                                                Intent intent = new Intent(CameraActivity.this, DescriptionActivity.class);
                                                intent.putExtra("userRole", userRole);
                                                intent.putExtra("imageUrl", uri.toString());
                                                startActivity(intent);
                                                finish();
                                            })
                                            .addOnFailureListener(e -> {
                                                Toast.makeText(CameraActivity.this, "Metadata save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                                shutterButton.setEnabled(true);
                                            });
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(CameraActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    shutterButton.setEnabled(true);
                                });

                        return;
                    }
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            runOnUiThread(() -> {
                Toast.makeText(CameraActivity.this, "No internet after leaving ESP network", Toast.LENGTH_LONG).show();
                shutterButton.setEnabled(true);
            });
        });
    }



    // ---------- Lifecycle ----------
    @Override protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        releaseEspNetwork();
        io.shutdown();
        if (webView != null) webView.destroy();
    }

    // ---------- Permission result ----------
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean granted = true;
            for (int r : grantResults) granted &= (r == PackageManager.PERMISSION_GRANTED);
            if (granted) connectToEspApThenLoad();
            else Toast.makeText(this, "Wi-Fi permission required to connect to ESP", Toast.LENGTH_SHORT).show();
        }
    }
}

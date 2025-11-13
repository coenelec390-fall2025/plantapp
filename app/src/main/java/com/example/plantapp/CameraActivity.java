package com.example.plantapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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

    private String userRole;
    private WebView webView;
    private ImageButton shutterButton;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network espNetwork;

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
            connectToEspAp();
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

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
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
                });
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() ->
                        Toast.makeText(CameraActivity.this,
                                "ESP network unavailable", Toast.LENGTH_SHORT).show());
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);
    }

    private void releaseEspNetwork() {
        if (connectivityManager != null) {
            connectivityManager.bindProcessToNetwork(null);
            if (networkCallback != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {
                }
            }
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
        releaseEspNetwork();
        io.shutdown();
        if (webView != null) webView.destroy();
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

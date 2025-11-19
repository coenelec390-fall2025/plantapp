package com.example.plantapp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DescriptionActivity extends AppCompatActivity {

    private TextView descriptionTv, scientificNameTv, commonNameTv, confidenceTv, plantTitle;
    private ProgressBar confidenceBar;
    private ImageButton backBtn;
    private Button takeAnotherBtn;
    private ImageView plantImageView;

    private String descriptionText = "";
    private String scientificName  = "";
    private String commonName      = "";
    private int confidenceScore    = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable loadingRunnable = null;
    private boolean loading = false;

    private final AtomicInteger pending = new AtomicInteger(4);
    private final AtomicBoolean savedOnce = new AtomicBoolean(false);

    private String userRole;
    private String imageUrl;
    private Bitmap imageBitmap;

    private boolean isPlant = true;

    // NEW: tells us this came from My Garden
    private boolean fromGarden = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_description);

        // Extras
        userRole    = getIntent().getStringExtra("userRole");
        imageUrl    = getIntent().getStringExtra("imageUrl");
        fromGarden  = getIntent().getBooleanExtra("fromGarden", false);

        if (userRole == null) userRole = "Hiker";
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            Toast.makeText(this, "Missing image URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // handle insets
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // UI refs
        descriptionTv     = findViewById(R.id.PlantDescriptionText);
        scientificNameTv  = findViewById(R.id.PlantScientificNameText);
        commonNameTv      = findViewById(R.id.PlantNameText);
        confidenceTv      = findViewById(R.id.ConfidenceText);
        confidenceBar     = findViewById(R.id.ConfidenceBar);
        backBtn           = findViewById(R.id.BackButton);
        takeAnotherBtn    = findViewById(R.id.TakeAnotherPictureButton);
        plantTitle        = findViewById(R.id.PlantTitle);
        plantImageView    = findViewById(R.id.PlantImageView);

        plantTitle.setText("Plant Description\n" + userRole);

        disableButton(backBtn);
        disableButton(takeAnotherBtn);

        takeAnotherBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, CameraActivity.class);
            i.putExtra("userRole", userRole);
            startActivity(i);
            finish();
        });

        backBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        // --------------------------------------------
        // CASE 1: COMING FROM MY GARDEN – skip Gemini
        // --------------------------------------------
        if (fromGarden) {
            loadGardenPlantAndDisplay();
            return;
        }

        // --------------------------------------------
        // CASE 2: NORMAL – NEW CAPTURE (run Gemini)
        // --------------------------------------------
        confidenceTv.setVisibility(View.GONE);
        confidenceBar.setVisibility(View.GONE);
        confidenceBar.setProgress(0);

        startLoadingDots(commonNameTv, "Loading");

        downloadImageAndRunGemini(imageUrl, userRole);
    }

    // ---------------------------------------------------------
    // Garden Case: display existing saved plant info instantly
    // ---------------------------------------------------------
    private void loadGardenPlantAndDisplay() {

        // load image via Glide
        Glide.with(this).load(imageUrl).into(plantImageView);

        // load data passed from adapter
        commonName     = getIntent().getStringExtra("commonName");
        scientificName = getIntent().getStringExtra("scientificName");
        descriptionText = getIntent().getStringExtra("descriptionText");
        confidenceScore = getIntent().getIntExtra("confidence", 0);

        // Show UI
        commonNameTv.setText(commonName);
        scientificNameTv.setText(scientificName);
        descriptionTv.setText(descriptionText);

        confidenceBar.setVisibility(View.VISIBLE);
        confidenceTv.setVisibility(View.VISIBLE);
        confidenceBar.setProgress(confidenceScore);
        confidenceTv.setText("Confidence: " + confidenceScore + "%");

        enableButton(backBtn);
        enableButton(takeAnotherBtn);
    }

    // ---------------------------------------------------------
    // Capture Case: Download → Gemini → Save To Firestore
    // ---------------------------------------------------------
    private void downloadImageAndRunGemini(String url, String role) {
        StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(url);
        final long MAX = 8L * 1024L * 1024L;

        ref.getBytes(MAX).addOnSuccessListener(bytes -> {
            imageBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

            if (imageBitmap == null) {
                stopLoadingDots();
                Toast.makeText(this, "Failed to decode image", Toast.LENGTH_LONG).show();
                enableButton(backBtn);
                enableButton(takeAnotherBtn);
                while (pending.getAndDecrement() > 0) {}
                maybeDeliverAll();
                return;
            }

            plantImageView.setImageBitmap(imageBitmap);

            Executor callbackExecutor = MoreExecutors.directExecutor();

            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel("gemini-2.5-flash");

            GenerativeModelFutures model = GenerativeModelFutures.from(ai);

            // Plant check
            Content checkContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText("Is the main subject of this image a plant? Answer YES or NO.")
                    .build();

            ListenableFuture<GenerateContentResponse> checkFuture =
                    model.generateContent(checkContent);

            Futures.addCallback(checkFuture, new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse result) {

                    String raw = (result != null && result.getText() != null)
                            ? result.getText().trim() : "";

                    if (raw.toUpperCase(Locale.getDefault()).startsWith("N")) {

                        isPlant = false;

                        commonName = "Not a plant";
                        scientificName = "";
                        descriptionText = "This image has not been recognized as a plant.";
                        confidenceScore = 0;

                        deleteImageFromStorage(imageUrl);

                        runOnUiThread(() -> {
                            stopLoadingDots();
                            commonNameTv.setText(commonName);
                            scientificNameTv.setText(scientificName);
                            descriptionTv.setText(descriptionText);
                            confidenceBar.setVisibility(View.VISIBLE);
                            confidenceTv.setVisibility(View.VISIBLE);
                            enableButton(backBtn);
                            enableButton(takeAnotherBtn);
                        });

                    } else {
                        isPlant = true;
                        runPlantDetailCalls(model, role, callbackExecutor);
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    isPlant = true;
                    runPlantDetailCalls(model, role, callbackExecutor);
                }
            }, callbackExecutor);

        }).addOnFailureListener(e -> {
            stopLoadingDots();
            Toast.makeText(this, "Failed to download image: " + e.getMessage(), Toast.LENGTH_LONG).show();
            enableButton(backBtn);
            enableButton(takeAnotherBtn);
            while (pending.getAndDecrement() > 0) {}
            maybeDeliverAll();
        });
    }

    private void runPlantDetailCalls(GenerativeModelFutures model, String role, Executor callbackExecutor) {
        pending.set(4);

        // (1) description
        String descPromptText = buildRolePromptNoFormatting(role, "this plant");
        Content descContent = new Content.Builder()
                .addImage(imageBitmap)
                .addText(descPromptText)
                .build();

        ListenableFuture<GenerateContentResponse> descFuture = model.generateContent(descContent);
        Futures.addCallback(descFuture, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String raw = (result != null && result.getText() != null) ? result.getText().trim() : "";
                descriptionText = sanitizePlainTextKeepDashes(raw);
                maybeDeliverAll();
            }
            @Override public void onFailure(Throwable t) {
                descriptionText = "Error: " + t.getMessage();
                maybeDeliverAll();
            }
        }, callbackExecutor);

        // (2) scientific name
        Content sciContent = new Content.Builder()
                .addImage(imageBitmap)
                .addText("What is the scientific name?")
                .build();

        ListenableFuture<GenerateContentResponse> sciFuture = model.generateContent(sciContent);
        Futures.addCallback(sciFuture, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String raw = result != null && result.getText() != null ? result.getText().trim() : "";
                scientificName = cleanScientificName(sanitizePlainText(raw));
                maybeDeliverAll();
            }
            @Override public void onFailure(Throwable t) {
                scientificName = "Error getting scientific name";
                maybeDeliverAll();
            }
        }, callbackExecutor);

        // (3) common name
        Content commonContent = new Content.Builder()
                .addImage(imageBitmap)
                .addText("What is the common name?")
                .build();

        ListenableFuture<GenerateContentResponse> commonFuture = model.generateContent(commonContent);
        Futures.addCallback(commonFuture, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String raw = result != null && result.getText() != null ? result.getText().trim() : "";
                commonName = cleanCommonName(sanitizePlainText(raw));
                maybeDeliverAll();
            }
            @Override public void onFailure(Throwable t) {
                commonName = "Error getting common name";
                maybeDeliverAll();
            }
        }, callbackExecutor);

        // (4) confidence
        Content confContent = new Content.Builder()
                .addImage(imageBitmap)
                .addText("Confidence from 0 to 100?")
                .build();

        ListenableFuture<GenerateContentResponse> confFuture = model.generateContent(confContent);
        Futures.addCallback(confFuture, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String raw = (result != null && result.getText() != null) ? result.getText().trim() : "0";
                confidenceScore = clamp0to100(extractFirstInt(raw));
                maybeDeliverAll();
            }
            @Override public void onFailure(Throwable t) {
                confidenceScore = 0;
                maybeDeliverAll();
            }
        }, callbackExecutor);
    }

    private void maybeDeliverAll() {
        if (pending.decrementAndGet() == 0) {

            runOnUiThread(() -> {
                stopLoadingDots();
                String formattedDescription = formatPoints(descriptionText);

                commonNameTv.setText(commonName);
                scientificNameTv.setText(scientificName);
                descriptionTv.setText(formattedDescription);

                confidenceTv.setVisibility(View.VISIBLE);
                confidenceBar.setVisibility(View.VISIBLE);
                confidenceBar.setProgress(confidenceScore);
                confidenceTv.setText("Confidence: " + confidenceScore + "%");

                enableButton(backBtn);
                enableButton(takeAnotherBtn);
            });

            saveCaptureMetadataIfNeeded();
        }
    }

    private void saveCaptureMetadataIfNeeded() {
        if (fromGarden) return;   // skip saving duplicates
        if (!isPlant) return;
        if (savedOnce.getAndSet(true)) return;

        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        long nowMillis = System.currentTimeMillis();

        String formattedDateTime =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(nowMillis));

        Map<String, Object> data = new HashMap<>();
        data.put("url", imageUrl);
        data.put("role", userRole);
        data.put("timestamp", nowMillis);
        data.put("dateTime", formattedDateTime);
        data.put("commonName", commonName);
        data.put("scientificName", scientificName);
        data.put("description", descriptionText);
        data.put("confidence", confidenceScore);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("captures")
                .add(data);
    }

    private void deleteImageFromStorage(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(url);
            ref.delete();
        } catch (Exception ignored) {}
    }

    // -----------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------

    private String buildRolePromptNoFormatting(String roleRaw, String plantNameHint) {
        String role = (roleRaw == null) ? "" : roleRaw.trim().toLowerCase();
        String baseRule =
                "Write in plain text only. Do not use any formatting. "
                        + "Use '>' at the start of each point. Keep it under 100 words. ";

        switch (role) {
            case "hiker":
                return baseRule + "Give a field-guide style summary for hikers.";
            case "chef":
                return baseRule + "Give a culinary summary including edible parts, flavor, uses.";
            case "gardener":
                return baseRule + "Give gardening instructions: soil, watering, light, pests.";
            default:
                return baseRule + "Give a simple factual plant summary.";
        }
    }

    private int extractFirstInt(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("(\\d{1,3})").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private void startLoadingDots(TextView target, String base) {
        if (target == null || loading) return;
        loading = true;
        final String[] dots = {".", "..", "..."};
        target.setText(base + dots[0]);

        loadingRunnable = new Runnable() {
            int i = 1;
            @Override public void run() {
                if (!loading) return;
                target.setText(base + dots[i]);
                i = (i + 1) % dots.length;
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(loadingRunnable, 500);
    }

    private void stopLoadingDots() {
        loading = false;
        if (loadingRunnable != null)
            handler.removeCallbacks(loadingRunnable);
    }

    private void disableButton(View btn) {
        btn.setEnabled(false);
        btn.setAlpha(0.5f);
    }

    private void enableButton(View btn) {
        btn.setEnabled(true);
        btn.setAlpha(1f);
    }

    private String sanitizePlainTextKeepDashes(String s) {
        if (s == null) return "";
        return s.replaceAll("[*_`~]", "")
                .replaceAll("^\"+|\"+$", "")
                .replaceAll("\\s+", " ").trim();
    }

    private String formatPoints(String text) {
        return text == null ? "" : text.replace(">", "\n\n>").trim();
    }

    private String sanitizePlainText(String s) {
        if (s == null) return "";
        return s.replaceAll("[*_`~]", "")
                .replaceAll("^\"+|\"+$", "")
                .replaceAll("\\s+", " ").trim();
    }

    private String cleanScientificName(String raw) {
        if (raw == null) return "";
        String s = sanitizePlainText(raw);
        Matcher m = Pattern.compile("([A-Z][a-z]+\\s+[a-z]+)").matcher(s);
        if (m.find()) return m.group(1);
        return s.split("\\s")[0];
    }

    private String cleanCommonName(String raw) {
        if (raw == null) return "";
        String first = sanitizePlainText(raw);
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLoadingDots();
    }

    @Override
    protected void onDestroy() {
        stopLoadingDots();
        super.onDestroy();
    }
}

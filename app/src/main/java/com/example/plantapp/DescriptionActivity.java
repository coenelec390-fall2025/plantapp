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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

// Firebase AI (Gemini)
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;

// Firebase Auth/Firestore/Storage
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

public class DescriptionActivity extends AppCompatActivity {

    private TextView descriptionTv, scientificNameTv, commonNameTv, confidenceTv, plantTitle;
    private ProgressBar confidenceBar;
    private ImageButton backBtn;
    private Button takeAnotherBtn;
    private ImageView plantImageView;

    private String descriptionText = "";
    private String scientificName  = "";
    private String commonName      = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable loadingRunnable = null;
    private boolean loading = false;

    // wait for: description + scientific + common
    private final AtomicInteger pending = new AtomicInteger(3);
    private final AtomicBoolean savedOnce = new AtomicBoolean(false);

    private String userRole;
    private String imageUrl;     // Firebase Storage download URL
    private Bitmap imageBitmap;  // decoded for UI + Gemini

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_description);

        // ---- intent extras ----
        userRole = getIntent().getStringExtra("userRole");
        imageUrl = getIntent().getStringExtra("imageUrl");
        if (userRole == null) userRole = "Hiker";
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            Toast.makeText(this, "Missing image URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // avoid system bars overlap
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // ---- UI refs ----
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

        confidenceTv.setVisibility(View.GONE);
        confidenceBar.setVisibility(View.GONE);
        confidenceBar.setProgress(0);

        startLoadingDots(commonNameTv, "Loading");

        // ---- download bytes -> set ImageView -> run Gemini ----
        downloadImageAndRunGemini(imageUrl, userRole);
    }

    /** Downloads the image from Firebase Storage, decodes to Bitmap, shows it, then runs three Gemini prompts. */
    private void downloadImageAndRunGemini(String url, String role) {
        StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(url);
        final long MAX = 8L * 1024L * 1024L; // 8MB

        ref.getBytes(MAX).addOnSuccessListener(bytes -> {
            imageBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (imageBitmap == null) {
                stopLoadingDots();
                Toast.makeText(this, "Failed to decode image", Toast.LENGTH_LONG).show();
                enableButton(backBtn);
                enableButton(takeAnotherBtn);
                return;
            }

            // ✅ show the image the user just took
            plantImageView.setImageBitmap(imageBitmap);

            // Build Gemini model
            Executor callbackExecutor = MoreExecutors.directExecutor();
            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel("gemini-2.5-flash");
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);

            // 1) role-specific description (image + text)
            String descPromptText = buildRolePromptNoFormatting(role, "this plant");
            Content descContent = new Content.Builder()
                    .addImage(imageBitmap)   // Bitmap is required
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

            // 2) scientific name from the image
            Content sciContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText("From this image, what is the scientific name (Genus species) of this plant? " +
                            "Respond with only the scientific name as plain text, no punctuation or formatting.")
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

            // 3) common name from the image
            Content commonContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText("From this image, what is the common name of this plant? " +
                            "Respond with only the common name as plain text, no punctuation or formatting.")
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

        }).addOnFailureListener(e -> {
            stopLoadingDots();
            Toast.makeText(this, "Failed to download image: " + e.getMessage(), Toast.LENGTH_LONG).show();
            enableButton(backBtn);
            enableButton(takeAnotherBtn);
        });
    }

    // ---------- role prompt ----------
    private String buildRolePromptNoFormatting(String roleRaw, String plantNameHint) {
        String role = (roleRaw == null) ? "" : roleRaw.trim().toLowerCase();
        String baseRule =
                "Write in plain text only. Do not use any formatting such as bold, italics, underline, " +
                        "markdown, asterisks, underscores, or backticks. Use greater than (>) to denote each point. " +
                        "Put each point on its own line. Keep response under 100 words total. ";

        switch (role) {
            case "hiker":
                return baseRule + "From the provided image of a plant, write a concise field-guide entry " +
                        "for a hiker (one line per point). Focus on habitat, identifying features, seasonality, " +
                        "elevation, and safety notes about toxic or similar-looking plants.";
            case "chef":
                return baseRule + "From the provided image of a plant, write a culinary summary for a chef " +
                        "(one line per point). Focus on edible parts, flavor, aroma, seasonal availability, " +
                        "texture, preparation methods, and ideal pairings.";
            case "gardener":
                return baseRule + "From the provided image of a plant, write a horticultural overview for a gardener " +
                        "(one line per point). Cover light requirements, soil, watering, propagation, and common pests/diseases.";
            default:
                return baseRule + "From the provided image, write an encyclopedia-style summary of the plant " +
                        "(one line per point), describing appearance, natural habitat, and uses.";
        }
    }

    // ---------- deliver & UI ----------
    private void maybeDeliverAll() {
        if (pending.decrementAndGet() == 0) {
            runOnUiThread(() -> {
                stopLoadingDots();
                String formattedDescription = formatPoints(descriptionText);

                commonNameTv.setText(commonName);
                scientificNameTv.setText(scientificName);
                descriptionTv.setText(formattedDescription);

                confidenceTv.setVisibility(View.VISIBLE);
                confidenceTv.setText("Confidence: 100%");
                confidenceBar.setVisibility(View.VISIBLE);
                confidenceBar.setProgress(100);

                enableButton(backBtn);
                enableButton(takeAnotherBtn);
            });

            // ✅ save once after all three are ready
            saveCaptureMetadataIfNeeded();
        }
    }

    /** Writes the full history doc to Firestore: users/{uid}/captures/{autoId} */
    private void saveCaptureMetadataIfNeeded() {
        if (savedOnce.getAndSet(true)) return; // ensure single write

        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Map<String, Object> data = new HashMap<>();
        data.put("url", imageUrl);
        data.put("role", userRole);
        data.put("timestamp", System.currentTimeMillis());
        data.put("commonName", commonName);
        data.put("scientificName", scientificName);
        data.put("description", descriptionText);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("captures")
                .add(data)
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to save history: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    // ---------- helpers ----------
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
        if (loadingRunnable != null) handler.removeCallbacks(loadingRunnable);
    }

    private void disableButton(View btn) {
        if (btn == null) return;
        btn.setEnabled(false);
        btn.setAlpha(0.5f);
    }

    private void enableButton(View btn) {
        if (btn == null) return;
        btn.setEnabled(true);
        btn.setAlpha(1f);
    }

    private String sanitizePlainTextKeepDashes(String s) {
        if (s == null) return "";
        s = s.replaceAll("[*_`~]", "");
        s = s.replaceAll("^\"+|\"+$", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private String formatPoints(String text) {
        if (text == null) return "";
        String formatted = text.replaceAll("\\s*>\\s*", "\n\n");
        return formatted.trim();
    }

    private String sanitizePlainText(String s) {
        if (s == null) return "";
        s = s.replaceAll("[*_`~]", "");
        s = s.replaceAll("^\"+|\"+$", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private String cleanScientificName(String raw) {
        if (raw == null) return "";
        String s = sanitizePlainText(raw);
        s = s.replaceAll("\\.$", "");
        Pattern p = Pattern.compile("([A-Z][a-z]+\\s+[a-z]+(?:\\s+[a-z]+)?)");
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1);
        return s.split("\\R", 2)[0].trim();
    }

    private String cleanCommonName(String raw) {
        if (raw == null) return "";
        String s = sanitizePlainText(raw);
        s = s.replaceAll("\\.$", "");
        String first = s.split("\\R", 2)[0].trim();
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    @Override protected void onPause() {
        super.onPause();
        stopLoadingDots();
    }

    @Override protected void onDestroy() {
        stopLoadingDots();
        super.onDestroy();
    }
}

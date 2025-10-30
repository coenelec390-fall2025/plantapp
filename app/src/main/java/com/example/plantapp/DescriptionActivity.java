package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DescriptionActivity extends AppCompatActivity {
    private TextView descriptionTv;
    private TextView scientificNameTv;
    private TextView commonNameTv;
    private TextView confidenceTv;
    private ProgressBar confidenceBar;
    private ImageButton backBtn;
    private TextView plantTitle;
    private Button takeAnotherBtn;
    private String descriptionText = "";
    private String scientificName  = "";
    private String commonName      = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable loadingRunnable = null;
    private boolean loading = false;
    private final AtomicInteger pending = new AtomicInteger(3);

    String userRole = getIntent().getStringExtra("userRole");
    String imageUrl = getIntent().getStringExtra("imageUrl"); // from Firebase Storage

// Download the image or send the URL directly to your Gemini pipeline,
// depending on how you’ve set it up (Firebase AI Extensions or custom).


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_description);

        userRole  = getIntent().getStringExtra("userRole"); // retrieve user role
        String plantName = "Papaya"; // placeholder for now, to be replaced with actual predictions
        if (userRole == null) userRole = "Hiker";

        // prevents system bar from overlapping app buttons
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        descriptionTv     = findViewById(R.id.PlantDescriptionText);
        scientificNameTv  = findViewById(R.id.PlantScientificNameText);
        commonNameTv      = findViewById(R.id.PlantNameText);
        confidenceTv      = findViewById(R.id.ConfidenceText);
        confidenceBar     = findViewById(R.id.ConfidenceBar);
        backBtn           = findViewById(R.id.BackButton);
        takeAnotherBtn    = findViewById(R.id.TakeAnotherPictureButton);
        plantTitle        = findViewById(R.id.PlantTitle);

        plantTitle.setText("Plant Description\n" + userRole);

        // disable navigation until info is processed
        disableButton(backBtn);
        disableButton(takeAnotherBtn);

        // go to camera activity when takeAnotherButton is clicked
        if (takeAnotherBtn != null) {
            takeAnotherBtn.setOnClickListener(v -> {
                Intent i = new Intent(this, CameraActivity.class);
                i.putExtra("userRole", userRole);
                startActivity(i);
                finish();
            });
        }

        // go back to main activity when backButton is clicked
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            });

        }

        // keep confidence measures hidden until confidence is found
        if (confidenceTv != null)  confidenceTv.setVisibility(View.GONE);
        if (confidenceBar != null) {
            confidenceBar.setVisibility(View.GONE);
            confidenceBar.setProgress(0);
        }

        // loading animation until name and desc is found
        startLoadingDots(commonNameTv, "Loading");

        Executor callbackExecutor = MoreExecutors.directExecutor();

        GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                .generativeModel("gemini-2.5-flash");
        GenerativeModelFutures model = GenerativeModelFutures.from(ai);

        // generates description in the description slot
        String descriptionPromptText = buildRolePromptNoFormatting(userRole, plantName);
        Content descriptionPrompt = new Content.Builder()
                .addText(descriptionPromptText)
                .build();

        ListenableFuture<GenerateContentResponse> descFuture = model.generateContent(descriptionPrompt);
        Futures.addCallback(descFuture, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String raw = (result != null && result.getText() != null) ? result.getText().trim() : "";
                descriptionText = sanitizePlainTextKeepDashes(raw); // removes unnecessary special characters
                maybeDeliverAll();
            }
            @Override public void onFailure(Throwable t) {
                descriptionText = "Error: " + t.getMessage();
                maybeDeliverAll();
            }
        }, callbackExecutor);

        // generates and returns the scientific name of the plant
        Content sciPrompt = new Content.Builder()
                .addText("What is the scientific name of " + plantName +
                        "? Respond with only the scientific name as plain text (Genus species), " +
                        "no punctuation, no markdown, no italics, no underscores, no quotes.")
                .build();

        ListenableFuture<GenerateContentResponse> sciFuture = model.generateContent(sciPrompt);
        Futures.addCallback(sciFuture, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String raw = result != null && result.getText() != null ? result.getText().trim() : "";
                scientificName = cleanScientificName(sanitizePlainText(raw)); // removes unnecessary special characters
                maybeDeliverAll();
            }
            @Override public void onFailure(Throwable t) {
                scientificName = "Error getting scientific name";
                maybeDeliverAll();
            }
        }, callbackExecutor);

        // generates and returns the common name of the plant
        Content commonPrompt = new Content.Builder()
                .addText("What is the common name of " + plantName +
                        "? Respond with only the common name as plain text, " +
                        "no punctuation, no markdown, no italics, no underscores, no quotes.")
                .build();

        ListenableFuture<GenerateContentResponse> commonFuture = model.generateContent(commonPrompt);
        Futures.addCallback(commonFuture, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String raw = result != null && result.getText() != null ? result.getText().trim() : "";
                commonName = cleanCommonName(sanitizePlainText(raw)); // removes unnecessary special characters
                maybeDeliverAll();
            }
            @Override public void onFailure(Throwable t) {
                commonName = "Error getting common name";
                maybeDeliverAll();
            }
        }, callbackExecutor);
    }

    // prompts that are sent to the AI model
    private String buildRolePromptNoFormatting(String roleRaw, String plantName) {
        String role = (roleRaw == null) ? "" : roleRaw.trim().toLowerCase();
        String baseRule =
                "Write in plain text only. Do not use any formatting such as bold, italics, underline, " +
                        "markdown, asterisks, underscores, or backticks. Use greater than (>) to denote each point. " +
                        "Put each point on its own line. Keep response under 100 words total. ";

        switch (role) {
            case "hiker":
                return baseRule + "Write a concise field-guide entry about " + plantName +
                        " for a hiker in point form (one line per point). Focus on habitat, " +
                        "identifying features, seasonality, elevation, and safety notes about toxic or similar-looking plants.";
            case "chef":
                return baseRule + "Write a culinary summary about " + plantName +
                        " for a chef in point form (one line per point). Focus on flavor, aroma, edible parts, " +
                        "seasonal availability, texture, preparation methods, and ideal pairings.";
            case "gardener":
                return baseRule + "Write a horticultural overview about " + plantName +
                        " for a gardener in point form (one line per point). Cover light requirements, soil, watering, " +
                        "propagation, and common pests or diseases.";
            default:
                return baseRule + "Write an encyclopedia-style summary about " + plantName +
                        " in point form (one line per point), describing appearance, natural habitat, and uses.";
        }
    }

    // all information from AI is placed into their respective slots on the description page
    private void maybeDeliverAll() {
        if (pending.decrementAndGet() == 0) {
            runOnUiThread(() -> {
                stopLoadingDots();
                String formattedDescription = formatPoints(descriptionText);

                if (commonNameTv != null)      commonNameTv.setText(commonName);
                if (scientificNameTv != null)  scientificNameTv.setText(scientificName);
                if (descriptionTv != null)     descriptionTv.setText(formattedDescription);

                if (confidenceTv != null) {
                    confidenceTv.setVisibility(View.VISIBLE);
                    confidenceTv.setText("Confidence: 100%");
                }
                if (confidenceBar != null) {
                    confidenceBar.setVisibility(View.VISIBLE);
                    confidenceBar.setProgress(100);
                }

                // enables navigation buttons
                enableButton(backBtn);
                enableButton(takeAnotherBtn);
            });
        }
    }

    // loading animation to hide empty spaces while AI generates
    private void startLoadingDots(TextView target, String base) {
        if (target == null) return;
        if (loading) return;
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

    // ends loading animation
    private void stopLoadingDots() {
        loading = false;
        if (loadingRunnable != null) handler.removeCallbacks(loadingRunnable);
    }

    // disables the buttons
    private void disableButton(View btn) {
        if (btn == null) return;
        btn.setEnabled(false);
        btn.setAlpha(0.5f);
    }

    // enables the buttons
    private void enableButton(View btn) {
        if (btn == null) return;
        btn.setEnabled(true);
        btn.setAlpha(1f);
    }

    // removes unnecessary special characters
    private String sanitizePlainTextKeepDashes(String s) {
        if (s == null) return "";
        // remove markdown-like emphasis markers but keep '-'
        s = s.replaceAll("[*_`~]", "");
        s = s.replaceAll("^\"+|\"+$", "");   // surrounding quotes
        s = s.replaceAll("\\s+", " ").trim(); // normalize spaces
        return s;
    }

    // formats information
    private String formatPoints(String text) {
        if (text == null) return "";
        String formatted = text.replaceAll("\\s*>\\s*", "\n\n");
        formatted = formatted.trim();
        return formatted;
    }

    // removes unnecessary special characters
    private String sanitizePlainText(String s) {
        if (s == null) return "";
        s = s.replaceAll("[*_`~]", "");
        s = s.replaceAll("^\"+|\"+$", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    // ensures scientific name is clear and concise
    private String cleanScientificName(String raw) {
        if (raw == null) return "";
        String s = sanitizePlainText(raw);
        s = s.replaceAll("\\.$", "");
        Pattern p = Pattern.compile("([A-Z][a-z]+\\s+[a-z]+(?:\\s+[a-z]+)?)");
        Matcher m = p.matcher(s);
        if (m.find()) return m.group(1);
        return s.split("\\R", 2)[0].trim();
    }

    //ensures common name is clear and concise
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

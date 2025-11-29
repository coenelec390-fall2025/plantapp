package com.example.plantapp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
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
    private int confidenceScore    = 0;

    private String finalDescriptionText = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable loadingRunnable = null;
    private boolean loading = false;

    private final AtomicInteger pending = new AtomicInteger(5);
    private final AtomicBoolean savedOnce = new AtomicBoolean(false);

    private String userRole;
    private String imageUrl;
    private Bitmap imageBitmap;

    static class Candidate {
        final String commonName, scientificName;
        final int confidence;
        Candidate(String c, String s, int k) { commonName = c; scientificName = s; confidence = k; }
    }
    private final List<Candidate> alternates = new ArrayList<>();

    private View identificationOverlay;
    private ImageView overlayLogo;
    private ImageView overlayStatusIcon;
    private TextView overlayStatusText;
    private ObjectAnimator logoPulseAnimator;

    private enum IdentificationResult {
        IDENTIFIED_OK,
        WARNING,
        UNKNOWN
    }

    private static final Pattern LEAK_HEAD = Pattern.compile(
            "^(?:\\s*(?i:(THOUGHTS?|THOUGHT|ANALYSIS|ANALYZE|REASONING|REASON|THINKING|THINK|PLAN|REFLECTION|CHAIN[- ]?OF[- ]?THOUGHT))\\s*:?\\b.*\\R)+",
            Pattern.DOTALL);
    private static final Pattern LEAK_LINES = Pattern.compile(
            "(?m)^(?i:(THOUGHTS?|THOUGHT|ANALYSIS|ANALYZE|REASONING|REASON|THINKING|THINK|PLAN|REFLECTION|CHAIN[- ]?OF[- ]?THOUGHT))\\s*:?\\b.*\\R?");
    private static final Pattern LETS_THINK = Pattern.compile("(?i)\\b(let'?s\\s+think|step\\s+by\\s+step)\\b.*");

    private static final Pattern INSTRUCTION_ECHO_LINES = Pattern.compile(
            "(?mi)^(?:do\\s*not\\s*include.*|never\\s*output.*|plain\\s*text\\s*only.*|respond\\s*with\\s*only.*|no\\s*prose.*|no\\s*code\\s*fences.*)\\s*$"
    );

    private static final Pattern NAME_ALLOW = Pattern.compile("^[A-Za-z][A-Za-z .'-]{0,63}$");

    private String stripThoughtPreamble(String s) {
        if (s == null) return "";
        String t = LEAK_HEAD.matcher(s).replaceFirst("");
        t = LEAK_LINES.matcher(t).replaceAll("");
        t = LETS_THINK.matcher(t).replaceAll("");
        return t.trim();
    }
    private boolean startsWithLeak(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.matches("(?is)^(THOUGHTS?|THOUGHT|ANALYSIS|ANALYZE|REASONING|REASON|THINKING|THINK|PLAN|REFLECTION|CHAIN[- ]?OF[- ]?THOUGHT)\\s*:?\\b.*")) return true;
        if (t.matches("(?is)^```[a-zA-Z0-9]*\\s*(THOUGHTS?|THOUGHT|ANALYSIS|REASONING|THINKING|CHAIN[- ]?OF[- ]?THOUGHT)\\b.*")) return true;
        if (t.matches("(?is)^let'?s\\s+think.*")) return true;
        return false;
    }
    private String hardStripMetaLabels(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?mi)^(THOUGHTS?|THOUGHT|ANALYSIS|REASONING|THINKING|PLAN|REFLECTION|CHAIN[- ]?OF[- ]?THOUGHT)\\s*:.*$", "");
        s = s.replaceAll("(?i)\\b(let'?s\\s+think|step\\s+by\\s+step)\\b.*", "");
        return s.trim();
    }
    private String stripInstructionEcho(String s) {
        if (s == null) return "";
        String t = INSTRUCTION_ECHO_LINES.matcher(s).replaceAll("");
        return t.trim();
    }
    private String gateNameOrFallback(String candidate, String fallback) {
        if (candidate == null) return fallback;
        String c = candidate.trim();
        if (c.contains("\n")) {
            for (String line : c.split("\\R")) {
                String ln = line.trim();
                if (!ln.isEmpty()) { c = ln; break; }
            }
        }
        if (c.matches("(?is)^(THOUGHTS?|THOUGHT|ANALYSIS|REASONING|THINKING|PLAN|REFLECTION|CHAIN[- ]?OF[- ]?THOUGHT)\\s*:.*"))
            return fallback;
        if (!NAME_ALLOW.matcher(c).matches())
            return fallback;
        return c;
    }

    private interface OnText { void accept(String text); }

    private void generateTextWithRetry(GenerativeModelFutures model, Content content, int maxRetries, OnText onText) {
        Executor cb = MoreExecutors.directExecutor();
        ListenableFuture<GenerateContentResponse> fut = model.generateContent(content);
        Futures.addCallback(fut, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse r) {
                String raw = (r != null && r.getText() != null) ? r.getText() : "";
                if (startsWithLeak(raw) && maxRetries > 0) {
                    generateTextWithRetry(model, content, maxRetries - 1, onText);
                    return;
                }
                String cleaned = stripThoughtPreamble(raw);
                onText.accept(cleaned);
            }
            @Override public void onFailure(Throwable t) { onText.accept(""); }
        }, cb);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_description);

        userRole = getIntent().getStringExtra("userRole");
        imageUrl = getIntent().getStringExtra("imageUrl");
        if (userRole == null) userRole = "Hiker";
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            Toast.makeText(this, "Missing image URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

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
        plantImageView    = findViewById(R.id.PlantImageView);

        identificationOverlay = findViewById(R.id.identificationOverlay);
        overlayLogo           = findViewById(R.id.overlayLogo);
        overlayStatusIcon     = findViewById(R.id.overlayStatusIcon);
        overlayStatusText     = findViewById(R.id.overlayStatusText);

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

        showIdentificationOverlay();

        startLoadingDots(commonNameTv, "Loading");

        downloadImageAndRunGemini(imageUrl, userRole);
    }

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

            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel("gemini-2.5-flash");
            GenerativeModelFutures model = GenerativeModelFutures.from(ai);

            String descPromptText =
                    buildRolePromptNoFormatting(role, "this plant") +
                            " Never output lines starting with THOUGHT:, THOUGHTS:, ANALYSIS:, REASONING:, or similar. Do not include meta text.";
            Content descContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText(descPromptText)
                    .build();
            generateTextWithRetry(model, descContent, 1, cleaned -> {
                descriptionText = sanitizePlainTextKeepDashes(cleaned);
                maybeDeliverAll();
            });

            Content sciContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText(
                            "From this image, what is the scientific name (Genus species) of this plant? " +
                                    "Respond with only the scientific name as plain text, no punctuation or formatting. " +
                                    "Do not include analysis, steps, thoughts, or meta labels."
                    ).build();
            generateTextWithRetry(model, sciContent, 1, cleaned -> {
                String raw = (cleaned == null) ? "" : cleaned.trim();
                scientificName = cleanScientificName(sanitizePlainText(raw));
                scientificName = gateNameOrFallback(scientificName, "");
                maybeDeliverAll();
            });

            Content commonContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText(
                            "From this image, return STRICT JSON only with the common name of this plant. " +
                                    "Schema: {\"common_name\":\"...\"}. No prose, no code fences, no analysis, no thoughts."
                    ).build();
            generateTextWithRetry(model, commonContent, 1, cleaned -> {
                String raw = (cleaned == null) ? "" : cleaned.trim();
                String parsed = parseCommonNameJson(raw);
                if (parsed.isEmpty()) parsed = cleanCommonName(sanitizePlainText(raw));
                parsed = stripInstructionEcho(parsed);
                commonName = gateNameOrFallback(parsed, "Unknown plant");
                maybeDeliverAll();
            });

            Content confContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText(
                            "On a scale from 0 to 100, how confident are you in your plant identification from this image? " +
                                    "Respond with only the integer number (0-100), no words, no percent sign. " +
                                    "Do not include analysis, steps, thoughts, or meta labels."
                    ).build();
            generateTextWithRetry(model, confContent, 1, cleaned -> {
                String raw = (cleaned == null || cleaned.isEmpty()) ? "0" : cleaned.trim();
                confidenceScore = clamp0to100(extractFirstInt(raw));
                maybeDeliverAll();
            });

            Content altsContent = new Content.Builder()
                    .addImage(imageBitmap)
                    .addText(
                            "Identify up to 3 alternate plausible plant species for this image. " +
                                    "Return STRICT JSON array only, no prose. " +
                                    "Each item must be: {\"common_name\":\"...\",\"scientific_name\":\"Genus species\",\"confidence\":INT0to100}. " +
                                    "Confidence is your reliability score 0-100. If none, return []. " +
                                    "Do not include analysis, steps, thoughts, meta labels, or code fences."
                    )
                    .build();
            generateTextWithRetry(model, altsContent, 1, cleaned -> {
                String raw = (cleaned == null || cleaned.isEmpty()) ? "[]" : cleaned.trim();
                parseAlternatesJson(raw);
                maybeDeliverAll();
            });

        }).addOnFailureListener(e -> {
            stopLoadingDots();
            Toast.makeText(this, "Failed to download image: " + e.getMessage(), Toast.LENGTH_LONG).show();
            enableButton(backBtn);
            enableButton(takeAnotherBtn);
            while (pending.getAndDecrement() > 0) {}
            maybeDeliverAll();
        });
    }

    // prompts
    private String buildRolePromptNoFormatting(String roleRaw, String plantNameHint) {
        String role = (roleRaw == null) ? "" : roleRaw.trim().toLowerCase();
        String baseRule =
                "Write final answer only. Do NOT include analysis, steps, thoughts, or reasoning. " +
                        "Never output lines starting with THOUGHT:, THOUGHTS:, ANALYSIS:, REASONING:, or similar. " +
                        "Plain text only. Use greater than (>) to denote each point; one point per line. " +
                        "Keep under 100 words total. ";

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

    private void maybeDeliverAll() {
        if (pending.decrementAndGet() == 0) {
            runOnUiThread(() -> {
                stopLoadingDots();

                String formattedDescription = formatPoints(descriptionText);
                formattedDescription = hardStripMetaLabels(formattedDescription);
                formattedDescription = stripInstructionEcho(formattedDescription);
                if (formattedDescription.isEmpty()) {
                    formattedDescription = "No description available for this image.";
                }

                if (confidenceScore < 80 && !alternates.isEmpty()) {
                    String sentence = buildAlternatesSentence(alternates);
                    if (!sentence.isEmpty()) {
                        formattedDescription = formattedDescription + "\n\n" + sentence;
                    }
                }

                finalDescriptionText = formattedDescription;

                commonName = stripInstructionEcho(commonName);
                scientificName = stripInstructionEcho(scientificName);
                if (startsWithLeak(commonName)) commonName = "Unknown plant";
                if (startsWithLeak(scientificName)) scientificName = "";

                commonNameTv.setText(commonName);
                scientificNameTv.setText(scientificName);
                descriptionTv.setText(formattedDescription);

                confidenceTv.setVisibility(View.VISIBLE);
                confidenceBar.setVisibility(View.VISIBLE);
                confidenceBar.setProgress(confidenceScore);
                confidenceTv.setText("Confidence: " + confidenceScore + "%");
                applyConfidenceColor(confidenceScore);

                String descLower = formattedDescription.toLowerCase(Locale.ROOT);
                String nameLower = commonName.toLowerCase(Locale.ROOT);

                boolean isNonPlant =
                        descLower.contains("not a plant") ||
                                descLower.contains("no plant") ||
                                descLower.contains("does not appear to be a plant") ||
                                descLower.contains("this image does not show a plant") ||
                                commonName.equalsIgnoreCase("unknown plant");

                boolean mentionsNonToxic =
                        descLower.contains("non-toxic") ||
                                descLower.contains("non toxic") ||
                                descLower.contains("not toxic") ||
                                descLower.contains("rarely toxic") ||
                                descLower.contains("generally safe") ||
                                descLower.contains("safe to handle") ||
                                descLower.contains("safe if touched");

                boolean isToxic = false;
                if (!mentionsNonToxic) {
                    Pattern toxicPattern = Pattern.compile(
                            "\\b(is|are|can be|may be|considered|generally|often)\\s+(highly\\s+)?(toxic|poisonous|venomous)\\b",
                            Pattern.CASE_INSENSITIVE
                    );
                    Matcher toxMatcher = toxicPattern.matcher(formattedDescription);
                    if (toxMatcher.find()) {
                        isToxic = true;
                    }

                    if (!isToxic) {
                        String dl = descLower;
                        if (dl.contains("highly toxic plant") ||
                                dl.contains("poisonous plant") ||
                                dl.contains("toxic plant") ||
                                dl.contains("causes severe poisoning") ||
                                dl.contains("can cause poisoning if ingested")) {
                            isToxic = true;
                        }
                    }
                }

                IdentificationResult result;
                if (isNonPlant) {
                    result = IdentificationResult.UNKNOWN;
                } else if (isToxic) {
                    result = IdentificationResult.WARNING;
                } else if (confidenceScore >= 80) {
                    result = IdentificationResult.IDENTIFIED_OK;
                } else {
                    result = IdentificationResult.UNKNOWN;
                }

                onIdentificationFinished(result);

                enableButton(backBtn);
                enableButton(takeAnotherBtn);
            });

            saveCaptureMetadataIfNeeded();
        }
    }

    private void applyConfidenceColor(int score) {
        int res = (score > 80)
                ? R.drawable.progress_green
                : (score >= 50 ? R.drawable.progress_yellow : R.drawable.progress_red);
        confidenceBar.setProgressDrawable(ContextCompat.getDrawable(this, res));
        confidenceBar.setProgress(confidenceBar.getProgress());
    }

    private void saveCaptureMetadataIfNeeded() {
        if (savedOnce.getAndSet(true)) return;
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Map<String, Object> data = new HashMap<>();
        data.put("url", imageUrl);
        data.put("role", userRole);
        data.put("timestamp", System.currentTimeMillis());
        data.put("commonName", commonName);
        data.put("scientificName", scientificName);

        // save the final formatted description (with alternates) if available
        String toSaveDesc = finalDescriptionText != null && !finalDescriptionText.isEmpty()
                ? finalDescriptionText
                : descriptionText;
        data.put("description", toSaveDesc);

        data.put("confidence", confidenceScore);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("captures")
                .add(data)
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to save history: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void showIdentificationOverlay() {
        if (identificationOverlay == null) return;

        identificationOverlay.setVisibility(View.VISIBLE);
        identificationOverlay.setAlpha(1f);

        if (overlayLogo != null) {
            overlayLogo.setVisibility(View.VISIBLE);
            overlayLogo.setAlpha(1f);
            overlayLogo.setScaleX(1f);
            overlayLogo.setScaleY(1f);
            overlayLogo.setRotation(0f);
        }

        if (overlayStatusIcon != null) {
            overlayStatusIcon.setVisibility(View.INVISIBLE);
            overlayStatusIcon.setAlpha(0f);
        }

        if (overlayStatusText != null) {
            overlayStatusText.setText("Identifying plant...");
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

    private void onIdentificationFinished(IdentificationResult result) {
        if (identificationOverlay == null) return;

        stopLogoPulse();

        if (result == IdentificationResult.IDENTIFIED_OK) {
            if (overlayStatusText != null) {
                overlayStatusText.setText("Plant identified!");
            }
            if (overlayStatusIcon != null) {
                overlayStatusIcon.setVisibility(View.GONE);
                overlayStatusIcon.setAlpha(0f);
            }
            animateLogoSuccess();
            return;
        }

        int iconRes;
        int tintColor;
        String statusText;

        if (result == IdentificationResult.WARNING) {
            iconRes = android.R.drawable.ic_dialog_alert;
            tintColor = 0xFFF44336;
            statusText = "Toxic plant detected";
        } else {
            iconRes = android.R.drawable.ic_menu_help;
            tintColor = 0xFF9E9E9E;
            statusText = "Not sure";
        }

        if (overlayStatusIcon != null) {
            overlayStatusIcon.setImageResource(iconRes);
            overlayStatusIcon.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
            overlayStatusIcon.setVisibility(View.VISIBLE);
            overlayStatusIcon.setAlpha(0f);
        }

        if (overlayStatusText != null) {
            overlayStatusText.setText(statusText);
        }

        if (overlayLogo != null) {
            overlayLogo.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> overlayLogo.setVisibility(View.INVISIBLE))
                    .start();
        }

        if (overlayStatusIcon != null) {
            overlayStatusIcon.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .withEndAction(() -> overlayStatusIcon.postDelayed(this::fadeOutOverlay, 600))
                    .start();
        } else {
            fadeOutOverlay();
        }
    }

    // Spin + pop animation for the logo on success
    private void animateLogoSuccess() {
        if (overlayLogo == null) {
            fadeOutOverlay();
            return;
        }

        overlayLogo.setVisibility(View.VISIBLE);
        overlayLogo.setAlpha(1f);
        overlayLogo.setScaleX(1f);
        overlayLogo.setScaleY(1f);
        overlayLogo.setRotation(0f);

        ObjectAnimator spin = ObjectAnimator.ofFloat(overlayLogo, View.ROTATION, 0f, 360f);
        spin.setDuration(450);
        spin.setInterpolator(new AccelerateDecelerateInterpolator());

        PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f, 1f);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f, 1f);
        ObjectAnimator pop = ObjectAnimator.ofPropertyValuesHolder(overlayLogo, scaleX, scaleY);
        pop.setDuration(300);
        pop.setInterpolator(new OvershootInterpolator());

        spin.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                pop.start();
            }
        });

        pop.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fadeOutOverlay();
            }
        });

        spin.start();
    }

    private void fadeOutOverlay() {
        if (identificationOverlay == null) return;

        identificationOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    identificationOverlay.setVisibility(View.GONE);
                    identificationOverlay.setAlpha(1f);
                })
                .start();
    }

    private int extractFirstInt(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("(\\d{1,3})").matcher(s);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }
    private int clamp0to100(int v) { return Math.max(0, Math.min(100, v)); }

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
    private void disableButton(View btn) { if (btn != null) { btn.setEnabled(false); btn.setAlpha(0.5f); } }
    private void enableButton(View btn)  { if (btn != null) { btn.setEnabled(true);  btn.setAlpha(1f);   } }

    private String sanitizePlainTextKeepDashes(String s) {
        if (s == null) return "";
        s = stripThoughtPreamble(s);
        s = hardStripMetaLabels(s);
        s = stripInstructionEcho(s);
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
        s = stripThoughtPreamble(s);
        s = hardStripMetaLabels(s);
        s = stripInstructionEcho(s);
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
        String first = "";
        for (String line : s.split("\\R")) {
            String ln = line.trim();
            if (ln.isEmpty()) continue;
            if (ln.matches("(?is)^(THOUGHTS?|THOUGHT|ANALYSIS|REASONING|THINKING|PLAN|REFLECTION)\\s*:.*")) continue;
            first = ln; break;
        }
        if (first.isEmpty()) first = s.split("\\R",2)[0].trim();
        if (startsWithLeak(first)) first = "Unknown plant";
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }
    private String parseCommonNameJson(String raw) {
        try {
            String json = raw.replaceAll("```(?:json)?", "").replace("```", "").trim();
            JSONObject o = new JSONObject(json);
            String cn = o.optString("common_name", "").trim();
            return cleanCommonName(cn);
        } catch (Exception e) {
            return "";
        }
    }
    private void parseAlternatesJson(String raw) {
        alternates.clear();
        try {
            String json = raw.replaceAll("```(?:json)?", "").replace("```", "").trim();
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String cn = cleanCommonName(sanitizePlainText(o.optString("common_name","")));
                String sn = cleanScientificName(sanitizePlainText(o.optString("scientific_name","")));
                int k = clamp0to100(o.optInt("confidence", 0));
                if (!cn.isEmpty() || !sn.isEmpty()) {
                    alternates.add(new Candidate(cn, sn, k));
                }
            }
            Collections.sort(alternates, (a, b) -> Integer.compare(b.confidence, a.confidence));
            if (alternates.size() > 3) alternates.subList(3, alternates.size()).clear();
        } catch (Exception ignored) {}
    }
    private String buildAlternatesSentence(List<Candidate> items) {
        List<String> names = new ArrayList<>();
        for (Candidate c : items) {
            String name = (c.commonName != null && !c.commonName.isEmpty())
                    ? c.commonName
                    : (c.scientificName != null ? c.scientificName : "");
            if (!name.isEmpty()) names.add(name);
        }
        if (names.isEmpty()) return "";
        if (names.size() == 1) return "It could also be: " + names.get(0) + ".";
        if (names.size() == 2) return "It could also be: " + names.get(0) + " or " + names.get(1) + ".";
        return "It could also be: " + names.get(0) + ", " + names.get(1) + " or " + names.get(2) + ".";
    }

    @Override protected void onPause() { super.onPause(); stopLoadingDots(); }

    @Override
    protected void onDestroy() {
        stopLoadingDots();
        stopLogoPulse();
        super.onDestroy();
    }
}

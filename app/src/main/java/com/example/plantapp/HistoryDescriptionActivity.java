package com.example.plantapp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
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

import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class HistoryDescriptionActivity extends AppCompatActivity {

    private TextView descriptionTv, scientificNameTv, commonNameTv, confidenceTv, plantTitle;
    private ProgressBar confidenceBar;
    private ImageButton backBtn;
    private Button takeAnotherBtn;
    private ImageView plantImageView;

    private String userRole;
    private String imageUrl;
    private String commonName;
    private String scientificName;
    private String description;
    private int confidence;
    private String dateTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_description); // reuse same layout

        // ---- Get extras ----
        Intent i = getIntent();
        userRole = i.getStringExtra("userRole");
        imageUrl = i.getStringExtra("imageUrl");
        commonName = i.getStringExtra("commonName");
        scientificName = i.getStringExtra("scientificName");
        description = i.getStringExtra("description");
        confidence = i.getIntExtra("confidence", 0);
        dateTime = i.getStringExtra("dateTime");

        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            Toast.makeText(this, "Missing image URL", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // ---- Insets ----
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

        // ---- Hide "Take Another Picture" button ----
        takeAnotherBtn.setVisibility(View.GONE);

        // ---- Title ----
        if (dateTime != null && !dateTime.isEmpty()) {
            plantTitle.setText("Plant Description\n" + userRole + "\n" + dateTime);
        } else {
            plantTitle.setText("Plant Description\n" + userRole);
        }


        // ---- Back button ----
        backBtn.setOnClickListener(v -> finish());

        // ---- Set UI text ----
        commonNameTv.setText(commonName != null ? commonName : "Unknown Plant");
        scientificNameTv.setText(scientificName != null ? scientificName : "");
        descriptionTv.setText(formatPoints(description));
        confidenceBar.setProgress(confidence);
        confidenceTv.setText("Confidence: " + confidence + "%");

        confidenceTv.setVisibility(View.VISIBLE);
        confidenceBar.setVisibility(View.VISIBLE);

        // ---- Load image ----
        loadImageFromStorage(imageUrl);
    }

    private void loadImageFromStorage(String url) {
        try {
            StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(url);
            final long MAX = 8L * 1024L * 1024L; // 8 MB

            ref.getBytes(MAX)
                    .addOnSuccessListener(bytes -> {
                        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bmp != null) {
                            plantImageView.setImageBitmap(bmp);
                        } else {
                            Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .addOnFailureListener(e ->
                            Toast.makeText(this,
                                    "Failed to load image: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Toast.makeText(this,
                    "Invalid image reference: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String formatPoints(String text) {
        if (text == null) return "";
        String formatted = text.replaceAll("\\s*>\\s*", "\n\n");
        return formatted.trim();
    }
}

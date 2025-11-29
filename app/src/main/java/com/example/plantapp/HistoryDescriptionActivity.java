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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class HistoryDescriptionActivity extends AppCompatActivity {

    private TextView plantTitleTv;
    private ImageButton backBtn;
    private ImageView plantImageView;
    private TextView commonNameTv;
    private TextView scientificNameTv;
    private TextView descriptionTv;
    private TextView confidenceTv;
    private ProgressBar confidenceBar;
    private Button deleteFromHistoryBtn;

    private String docId;
    private String userRole;
    private String imageUrl;
    private String commonName;
    private String scientificName;
    private String description;
    private int confidence;
    private String dateTime;
    private boolean allowDelete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_history_description);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        plantTitleTv        = findViewById(R.id.PlantTitle);
        backBtn             = findViewById(R.id.BackButton);
        plantImageView      = findViewById(R.id.PlantImageView);
        commonNameTv        = findViewById(R.id.PlantNameText);
        scientificNameTv    = findViewById(R.id.PlantScientificNameText);
        descriptionTv       = findViewById(R.id.PlantDescriptionText);
        confidenceTv        = findViewById(R.id.ConfidenceText);
        confidenceBar       = findViewById(R.id.ConfidenceBar);
        deleteFromHistoryBtn= findViewById(R.id.DeleteFromHistoryButton);

        Intent intent = getIntent();
        docId          = intent.getStringExtra("docId");
        userRole       = intent.getStringExtra("userRole");
        imageUrl       = intent.getStringExtra("imageUrl");
        commonName     = intent.getStringExtra("commonName");
        scientificName = intent.getStringExtra("scientificName");
        description    = intent.getStringExtra("description");
        confidence     = intent.getIntExtra("confidence", 0);
        dateTime       = intent.getStringExtra("dateTime");
        allowDelete    = intent.getBooleanExtra("allowDelete", false);

        if (userRole == null || userRole.isEmpty()) {
            userRole = "Hiker";
        }

        if (plantTitleTv != null) {
            if (dateTime != null && !dateTime.isEmpty()) {
                plantTitleTv.setText("Previous Capture\n" + dateTime + " · " + userRole);
            } else {
                plantTitleTv.setText("Previous Capture\n" + userRole);
            }
        }

        commonNameTv.setText(
                (commonName != null && !commonName.isEmpty()) ? commonName : "Unknown plant"
        );
        scientificNameTv.setText(scientificName != null ? scientificName : "");
        descriptionTv.setText(description != null ? description : "No description available.");

        confidenceBar.setMax(100);
        confidenceBar.setProgress(confidence);
        confidenceTv.setText("Confidence: " + confidence + "%");
        applyConfidenceColor(confidence);

        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            try {
                StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(imageUrl);
                final long MAX = 8L * 1024L * 1024L;
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
                                Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Toast.makeText(this, "Invalid image URL", Toast.LENGTH_SHORT).show();
            }
        }

        backBtn.setOnClickListener(v -> finish());

        if (!allowDelete || docId == null || docId.isEmpty()) {
            deleteFromHistoryBtn.setVisibility(View.GONE);
        } else {
            deleteFromHistoryBtn.setVisibility(View.VISIBLE);
            deleteFromHistoryBtn.setOnClickListener(v -> deleteCaptureFromHistory());
        }
    }


    private void applyConfidenceColor(int score) {
        int resId;
        if (score > 80) {
            resId = R.drawable.progress_green;
        } else if (score >= 50) {
            resId = R.drawable.progress_yellow;
        } else {
            resId = R.drawable.progress_red;
        }

        confidenceBar.setProgressDrawable(ContextCompat.getDrawable(this, resId));
        confidenceBar.setProgress(confidenceBar.getProgress());
    }

    private void deleteCaptureFromHistory() {
        if (docId == null || docId.isEmpty()) {
            Toast.makeText(this, "Missing capture ID", Toast.LENGTH_SHORT).show();
            return;
        }
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("captures")
                .document(docId)
                .delete()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Deleted from history", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to delete: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }
}

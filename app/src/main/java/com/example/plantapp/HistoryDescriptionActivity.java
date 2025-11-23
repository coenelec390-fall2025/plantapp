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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class HistoryDescriptionActivity extends AppCompatActivity {

    private TextView descriptionTv, scientificNameTv, commonNameTv, confidenceTv, plantTitle;
    private ProgressBar confidenceBar;
    private ImageButton backBtn;
    private Button deleteBtn;   // reusing TakeAnotherPictureButton as delete
    private ImageView plantImageView;

    private String userRole;
    private String imageUrl;
    private String commonName;
    private String scientificName;
    private String description;
    private int confidence;
    private String dateTime;
    private String docId;       // Firestore document ID for this history item
    private boolean allowDelete; // NEW

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_description); // reuse same layout

        // ---- Get extras ----
        Intent i = getIntent();
        docId        = i.getStringExtra("docId");
        userRole     = i.getStringExtra("userRole");
        imageUrl     = i.getStringExtra("imageUrl");
        commonName   = i.getStringExtra("commonName");
        scientificName = i.getStringExtra("scientificName");
        description  = i.getStringExtra("description");
        confidence   = i.getIntExtra("confidence", 0);
        dateTime     = i.getStringExtra("dateTime");
        allowDelete  = i.getBooleanExtra("allowDelete", true); // default true for your own history

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
        deleteBtn         = findViewById(R.id.TakeAnotherPictureButton); // reuse as delete button
        plantTitle        = findViewById(R.id.PlantTitle);
        plantImageView    = findViewById(R.id.PlantImageView);

        // ---- Set title ----
        if (dateTime != null && !dateTime.isEmpty()) {
            plantTitle.setText("Plant Description\n" + userRole + "\n" + dateTime);
        } else {
            plantTitle.setText("Plant Description\n" + userRole);
        }

        // ---- Back button ----
        backBtn.setOnClickListener(v -> finish());

        // ---- Configure delete button (hide for friend history) ----
        if (!allowDelete) {
            // viewing a friend's capture -> no delete
            deleteBtn.setVisibility(View.GONE);
        } else {
            // your own capture -> can delete
            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setText("Delete from History");
            deleteBtn.setOnClickListener(v -> deleteCurrentHistoryEntry());
        }

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

    /** Delete this history entry from Firestore (and try to delete its image from Storage). */
    private void deleteCurrentHistoryEntry() {
        if (docId == null || docId.isEmpty()) {
            Toast.makeText(this, "Cannot delete: missing history ID", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        deleteBtn.setEnabled(false);

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // Delete the Firestore document
        db.collection("users")
                .document(uid)
                .collection("captures")
                .document(docId)
                .delete()
                .addOnSuccessListener(aVoid -> {
                    // Try to also delete the image from Storage (optional, best-effort)
                    try {
                        StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(imageUrl);
                        ref.delete(); // no need to block on this
                    } catch (Exception ignored) {}

                    Toast.makeText(this, "Deleted from history", Toast.LENGTH_SHORT).show();
                    finish(); // go back to SettingsActivity
                })
                .addOnFailureListener(e -> {
                    deleteBtn.setEnabled(true);
                    Toast.makeText(this,
                            "Failed to delete: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
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

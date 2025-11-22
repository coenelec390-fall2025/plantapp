package com.example.plantapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    // UI
    private ImageButton profileButton, cameraButton, infoButton;
    private Spinner roleSpinner;

    // "My Garden" UI
    private LinearLayout gardenStripLayout;
    private TextView gardenEmptyText;

    private final String[] roles = {"Hiker", "Gardener", "Chef"};
    private boolean suppressSpinnerCallback = false;
    // default role is hiker
    private String currentRole = "Hiker";

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // padding
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // initialize firestore / auth instance
        mAuth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        // link xml elements by id
        profileButton = findViewById(R.id.ProfileButton);
        cameraButton  = findViewById(R.id.CameraButton);
        roleSpinner   = findViewById(R.id.RoleSpinner);
        infoButton    = findViewById(R.id.InfoButton);

        gardenStripLayout = findViewById(R.id.GardenStrip);
        gardenEmptyText   = findViewById(R.id.GardenEmptyText);

        // SPINNER ADAPTER Role
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_role_item,      // layout for the closed spinner
                roles
        );
        adapter.setDropDownViewResource(R.layout.spinner_role_dropdown_item);
        roleSpinner.setAdapter(adapter);

        // listener for spinner options
        roleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSpinnerCallback) return;

                String chosen = roles[position];
                if (!chosen.equals(currentRole)) {
                    currentRole = chosen;
                    saveUserRole(currentRole);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        // info (i) button – show persona description
        infoButton.setOnClickListener(v -> showRoleInfoDialog());

        // go to profile/settings activity
        profileButton.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });

        // go to camera activity
        cameraButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            intent.putExtra("userRole", currentRole); // pass the role forward
            startActivity(intent);
        });

        // load role that is stored in firebase
        loadRoleFromFirestore();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // refresh garden thumbnails whenever we come back to main screen
        loadGardenThumbnails();
    }

    // small dialog that explains the currently selected persona
    private void showRoleInfoDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_role_info, null, false);

        TextView titleTv = dialogView.findViewById(R.id.RoleInfoTitle);
        TextView bodyTv  = dialogView.findViewById(R.id.RoleInfoBody);
        Button closeBtn  = dialogView.findViewById(R.id.buttonCloseRoleInfo);

        String role = currentRole != null ? currentRole : "Hiker";
        String body;

        switch (role.toLowerCase()) {
            case "gardener":
                titleTv.setText("Gardener persona");
                body = "Descriptions focus on how to grow and care for the plant: " +
                        "light, watering, soil, propagation, and common pests or diseases.";
                break;
            case "chef":
                titleTv.setText("Chef persona");
                body = "Descriptions focus on edible parts, flavor, aroma, seasonal availability, " +
                        "and ideas for cooking, pairing and preparation.";
                break;
            case "hiker":
            default:
                titleTv.setText("Hiker persona");
                body = "Descriptions are written like a field guide for hikers: habitat, " +
                        "identifying features, seasonality, and safety notes about toxic " +
                        "or similar-looking plants.";
                break;
        }

        bodyTv.setText(body);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        closeBtn.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // ---------- MY GARDEN ----------

    /** Load up to 20 recent plant images into the horizontal "My Garden" strip. */
    private void loadGardenThumbnails() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            gardenStripLayout.removeAllViews();
            gardenEmptyText.setVisibility(View.VISIBLE);
            return;
        }

        String uid = user.getUid();

        db.collection("users")
                .document(uid)
                .collection("captures")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    gardenStripLayout.removeAllViews();

                    if (querySnapshot.isEmpty()) {
                        // No plants yet
                        gardenEmptyText.setVisibility(View.VISIBLE);
                        return;
                    }

                    gardenEmptyText.setVisibility(View.GONE);

                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        String imageUrl = doc.getString("url");
                        if (imageUrl == null || imageUrl.trim().isEmpty()) continue;
                        addGardenThumbnail(imageUrl);
                    }
                })
                .addOnFailureListener(e -> {
                    gardenStripLayout.removeAllViews();
                    gardenEmptyText.setVisibility(View.VISIBLE);
                });
    }

    /** Add a single thumbnail ImageView for the given storage URL. */
    private void addGardenThumbnail(String imageUrl) {
        final ImageView iv = new ImageView(this);

        // 1.5x larger than 72dp  → 108dp
        int size = dpToPx(225);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(size, size);
        lp.setMargins(0, 0, dpToPx(8), 0); // only right margin between images
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.history_item_bg);
        iv.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));

        gardenStripLayout.addView(iv);

        try {
            StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(imageUrl);
            final long MAX = 1024L * 1024L; // 1 MB per thumbnail

            ref.getBytes(MAX)
                    .addOnSuccessListener(bytes -> {
                        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bmp != null) {
                            iv.setImageBitmap(bmp);
                        }
                    })
                    .addOnFailureListener(e -> {
                        // silently fail for this thumbnail
                    });
        } catch (Exception e) {
            // invalid url, ignore this one
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // ---------- ROLE LOADING / SAVING ----------

    // load role stored in firebase
    private void loadRoleFromFirestore() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        db.collection("users").document(user.getUid())
                .get()
                .addOnSuccessListener(doc -> {
                    String role = (doc.exists() ? doc.getString("role") : null);
                    if (role == null || role.trim().isEmpty()) role = "Hiker";

                    currentRole = role;

                    int idx = Arrays.asList(roles).indexOf(role);
                    if (idx < 0) idx = 0;

                    suppressSpinnerCallback = true;
                    roleSpinner.setSelection(idx, false);
                    suppressSpinnerCallback = false;
                })
                .addOnFailureListener(e -> {
                    currentRole = "Hiker";
                    suppressSpinnerCallback = true;
                    roleSpinner.setSelection(0, false);
                    suppressSpinnerCallback = false;
                });
    }

    // save role to firestore
    private void saveUserRole(String role) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("role", role);

        db.collection("users").document(user.getUid())
                .set(updates, SetOptions.merge())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to update role: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    // if not logged in, take user to log in page
    @Override
    protected void onStart() {
        super.onStart();
        if (mAuth.getCurrentUser() == null) {
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        }
    }
}

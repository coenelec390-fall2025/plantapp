package com.example.plantapp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
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
import android.widget.FrameLayout;

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

//main screen of the app
public class MainActivity extends AppCompatActivity {

    //Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    //UI
    private ImageButton profileButton, cameraButton, infoButton;
    private Spinner roleSpinner;

    //"My Garden" UI
    private LinearLayout gardenStripLayout;
    private TextView gardenEmptyText;

    //PERSONA!!!!!!! (disturbing the peace)
    private final String[] roles = {"Hiker", "Gardener", "Chef"};
    private boolean suppressSpinnerCallback = false;
    //default role is hiker
    private String currentRole = "Hiker";

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        //initialize firestore / auth instance
        mAuth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        profileButton = findViewById(R.id.ProfileButton);
        cameraButton  = findViewById(R.id.CameraButton);
        roleSpinner   = findViewById(R.id.RoleSpinner);
        infoButton    = findViewById(R.id.InfoButton);

        gardenStripLayout = findViewById(R.id.GardenStrip);
        gardenEmptyText   = findViewById(R.id.GardenEmptyText);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_role_item,
                roles
        );
        adapter.setDropDownViewResource(R.layout.spinner_role_dropdown_item);
        roleSpinner.setAdapter(adapter);

        //listener for spinner options
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

        //info button to show persona description
        infoButton.setOnClickListener(v -> showRoleInfoDialog());

        //go to profile activity
        profileButton.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });

        //go to camera activity
        cameraButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            intent.putExtra("userRole", currentRole); // pass the role forward
            startActivity(intent);
        });

        //load role that is stored in firebase
        loadRoleFromFirestore();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGardenThumbnails();
    }

    //small dialog that explains then persona
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

    //load recent pictures from firebase and show in my garden
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
                .limit(50)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    gardenStripLayout.removeAllViews();

                    if (querySnapshot.isEmpty()) {
                        gardenEmptyText.setVisibility(View.VISIBLE);
                        return;
                    }

                    int added = 0;

                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        if (added >= 20) break; // only show up to 20 thumbnails

                        Long confLong = doc.getLong("confidence");
                        int confidence = (confLong != null) ? confLong.intValue() : 0;

                        // Only show plants with confidence >= 50
                        if (confidence < 50) continue;

                        String docId          = doc.getId();
                        String imageUrl       = doc.getString("url");
                        String role           = doc.getString("role");
                        String commonName     = doc.getString("commonName");
                        String scientificName = doc.getString("scientificName");
                        String description    = doc.getString("description");
                        String dateTime       = doc.getString("dateTime");

                        if (imageUrl == null || imageUrl.trim().isEmpty()) continue;

                        addGardenThumbnail(
                                docId,
                                imageUrl,
                                role,
                                commonName,
                                scientificName,
                                description,
                                confidence,
                                dateTime
                        );
                        added++;
                    }

                    if (added == 0) {
                        gardenEmptyText.setVisibility(View.VISIBLE);
                    } else {
                        gardenEmptyText.setVisibility(View.GONE);
                    }
                })
                .addOnFailureListener(e -> {
                    gardenStripLayout.removeAllViews();
                    gardenEmptyText.setVisibility(View.VISIBLE);
                });
    }


    //create a thumbnail view and add it to the garden strip, can be clicked to go to history
    private void addGardenThumbnail(String docId,
                                    String imageUrl,
                                    String role,
                                    String commonName,
                                    String scientificName,
                                    String description,
                                    int confidence,
                                    String dateTime) {
        FrameLayout frame = new FrameLayout(this);

        int size = dpToPx(225);
        LinearLayout.LayoutParams frameLp =
                new LinearLayout.LayoutParams(size, size);
        frameLp.setMargins(0, 0, dpToPx(8), 0);
        frame.setLayoutParams(frameLp);

        frame.setBackgroundResource(R.drawable.history_item_bg);

        int innerPad = dpToPx(4);
        frame.setPadding(innerPad, innerPad, innerPad, innerPad);

        ImageView iv = new ImageView(this);
        FrameLayout.LayoutParams ivLp =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
        iv.setLayoutParams(ivLp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

        iv.setBackgroundResource(R.drawable.garden_thumb_rounded_bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            iv.setClipToOutline(true);
        }

        frame.addView(iv);
        gardenStripLayout.addView(frame);

        frame.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, HistoryDescriptionActivity.class);
            intent.putExtra("docId", docId);
            intent.putExtra("userRole", role != null ? role : "Hiker");
            intent.putExtra("imageUrl", imageUrl);
            intent.putExtra("commonName", commonName);
            intent.putExtra("scientificName", scientificName);
            intent.putExtra("description", description);
            intent.putExtra("confidence", confidence);
            intent.putExtra("dateTime", dateTime);
            intent.putExtra("allowDelete", true);  // from your own garden, so allow delete
            startActivity(intent);
        });

        // Load image from Firebase Storage into the ImageView
        try {
            StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(imageUrl);
            final long MAX = 1024L * 1024L;

            ref.getBytes(MAX)
                    .addOnSuccessListener(bytes -> {
                        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bmp != null) {
                            iv.setImageBitmap(bmp);
                        }
                    })
                    .addOnFailureListener(e -> {
                    });
        } catch (Exception e) {
            // invalid URL - ignore this thumbnail
        }
    }

    //convert dp units to pixels
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }


    //load role stored in firebase
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

    //save role to firestore
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

    //if not logged in, take user to log in page
    @Override
    protected void onStart() {
        super.onStart();
        if (mAuth.getCurrentUser() == null) {
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        }
    }
}

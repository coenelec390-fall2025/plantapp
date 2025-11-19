package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    // UI
    private ImageButton profileButton, cameraButton;
    private Spinner roleSpinner;

    // My Garden
    private RecyclerView myGardenRecyclerView;
    private GardenAdapter gardenAdapter;
    private final List<GardenPlant> gardenPlants = new ArrayList<>();

    private final String[] roles = {"Hiker", "Gardener", "Chef"};
    private boolean suppressSpinnerCallback = false;

    private String currentRole = "Hiker";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // padding for system bars
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // Firebase
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        // Link XML
        profileButton = findViewById(R.id.ProfileButton);
        cameraButton = findViewById(R.id.CameraButton);
        roleSpinner = findViewById(R.id.RoleSpinner);
        myGardenRecyclerView = findViewById(R.id.MyGardenRecyclerView);

        // Spinner setup
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, roles
        );
        roleSpinner.setAdapter(adapter);

        roleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSpinnerCallback) return;

                String chosen = roles[position];
                if (!chosen.equals(currentRole)) {
                    currentRole = chosen;
                    saveUserRole(currentRole);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // RecyclerView setup
        setupMyGardenRecyclerView();
        loadAllCapturesFromFirestore();   // ← NEW: loads all captures, no filter

        // Profile button
        profileButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        // Camera button
        cameraButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            intent.putExtra("userRole", currentRole);
            startActivity(intent);
        });

        loadRoleFromFirestore();
    }

    // ----------------------------------------------
    // MY GARDEN SETUP
    // ----------------------------------------------

    private void setupMyGardenRecyclerView() {
        LinearLayoutManager layoutManager =
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);

        myGardenRecyclerView.setLayoutManager(layoutManager);
        gardenAdapter = new GardenAdapter(this, gardenPlants);
        myGardenRecyclerView.setAdapter(gardenAdapter);
    }

    /** Load ALL captures — NO FILTER */
    private void loadAllCapturesFromFirestore() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        db.collection("users")
                .document(user.getUid())
                .collection("captures")
                .orderBy("timestamp", Query.Direction.DESCENDING)   // ONLY sort by time
                .limit(30)
                .get()
                .addOnSuccessListener(query -> {
                    gardenPlants.clear();

                    for (QueryDocumentSnapshot doc : query) {
                        String url  = doc.getString("url");
                        String name = doc.getString("commonName");
                        String sci  = doc.getString("scientificName");
                        String desc = doc.getString("description");
                        Long confL  = doc.getLong("confidence");
                        int conf    = confL != null ? confL.intValue() : 0;

                        if (url != null && name != null) {
                            gardenPlants.add(new GardenPlant(
                                    name,
                                    sci != null ? sci : "",
                                    desc != null ? desc : "",
                                    conf,
                                    url
                            ));
                        }
                    }

                    gardenAdapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Failed to load garden: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    // ----------------------------------------------
    // ROLE LOAD / SAVE
    // ----------------------------------------------

    private void loadRoleFromFirestore() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        db.collection("users").document(user.getUid())
                .get()
                .addOnSuccessListener(doc -> {
                    String role = doc.exists() ? doc.getString("role") : null;
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
                        Toast.makeText(this,
                                "Failed to update role: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    // ----------------------------------------------
    // LOGIN CHECK
    // ----------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        if (mAuth.getCurrentUser() == null) {
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        }
    }
}

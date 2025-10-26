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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private ImageButton profileButton, cameraButton;
    private Spinner roleSpinner;

    private final String[] roles = {"Hiker", "Gardener", "Chef"};
    private boolean suppressSpinnerCallback = false;   // prevents the first auto-callback
    private String currentRole = "Hiker";              // what we have loaded/last saved

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

        mAuth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        profileButton = findViewById(R.id.ProfileButton);
        cameraButton  = findViewById(R.id.CameraButton);
        roleSpinner   = findViewById(R.id.RoleSpinner);

        // Populate spinner (do NOT setSelection here)
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, roles);
        roleSpinner.setAdapter(adapter);

        // Listen for changes (but we’ll suppress the first one after programmatic selection)
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

        profileButton.setOnClickListener(v -> {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    //finish();
                });

        cameraButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            intent.putExtra("userRole", currentRole); // pass the role forward
            startActivity(intent);
            //finish();
        });

        // Load the role from Firestore and set spinner accordingly
        loadRoleFromFirestore();
    }

    private void loadRoleFromFirestore() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        db.collection("users").document(user.getUid())
                .get()
                .addOnSuccessListener(doc -> {
                    String role = (doc.exists() ? doc.getString("role") : null);
                    if (role == null || role.trim().isEmpty()) role = "Hiker"; // default only if missing

                    currentRole = role;

                    int idx = Arrays.asList(roles).indexOf(role);
                    if (idx < 0) idx = 0;

                    // Programmatic selection → suppress callback so it doesn't re-save
                    suppressSpinnerCallback = true;
                    roleSpinner.setSelection(idx, false);
                    suppressSpinnerCallback = false;
                })
                .addOnFailureListener(e -> {
                    // If read fails, fall back to Hiker visually but don’t write anything
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

        // Use merge so it creates/updates the field without requiring an existing doc
        db.collection("users").document(user.getUid())
                .set(updates, SetOptions.merge())
                .addOnSuccessListener(aVoid ->
                        Toast.makeText(this, "Role set to " + role, Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to update role: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mAuth.getCurrentUser() == null) {
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
        }
    }
}

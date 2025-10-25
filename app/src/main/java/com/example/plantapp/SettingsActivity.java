package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
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

public class SettingsActivity extends AppCompatActivity {

    private TextView usernameDisplay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom); // add ~40dp extra top padding
            return insets;
        });

        // 🔙 Back button
        findViewById(R.id.BackButton).setOnClickListener(v -> finish());
        usernameDisplay = findViewById(R.id.UsernameDisplay);

        // 🚪 Logout button
        Button logoutBtn = findViewById(R.id.LogoutButton);
        logoutBtn.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();  // Sign out from Firebase

            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();

            // Go to LoginActivity and clear back stack
            Intent i = new Intent(SettingsActivity.this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        });


        loadUsername();
    }

    private void loadUsername() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            usernameDisplay.setText("Unknown User");
            return;
        }

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users").document(uid).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        String username = documentSnapshot.getString("username");
                        usernameDisplay.setText(username != null ? username : "Unnamed User");
                    } else {
                        usernameDisplay.setText("No user data found");
                    }
                })
                .addOnFailureListener(e -> {
                    usernameDisplay.setText("Failed to load username");
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }
}

package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class CameraActivity extends AppCompatActivity {

    private String userRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera);

        userRole = getIntent().getStringExtra("userRole");

        // ensures system bar does not overlap app buttons
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // camera shutter button for CameraActivity
        ImageButton shutterButton = findViewById(R.id.ShutterButton);
        shutterButton.setOnClickListener(v -> {
            String userRole = getIntent().getStringExtra("userRole"); // retrieve user role

            Intent intent = new Intent(CameraActivity.this, DescriptionActivity.class);
            intent.putExtra("userRole", userRole); // pass to next activity
            startActivity(intent);
            finish(); // close CameraActivity to prevent return using back button
        });

        // back button for CameraActivity
        ImageButton backButton = findViewById(R.id.BackButton);
        backButton.setOnClickListener(v -> {
            Intent intent = new Intent(CameraActivity.this, MainActivity.class);
            startActivity(intent);
            finish();  // closes CameraActivity and returns to MainActivity
        });

    }
}

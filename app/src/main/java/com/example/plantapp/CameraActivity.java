package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

public class CameraActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera);

        ImageButton shutterButton = findViewById(R.id.ShutterButton);
        shutterButton.setOnClickListener(v -> {
            // Temporary toast for feedback
            Toast.makeText(this, "📸 Picture taken (mock)", Toast.LENGTH_SHORT).show();

            // Navigate to DescriptionActivity
            Intent intent = new Intent(CameraActivity.this, DescriptionActivity.class);
            startActivity(intent);
        });

        ImageButton backButton = findViewById(R.id.BackButton);
        backButton.setOnClickListener(v -> {
            finish();  // closes CameraActivity and returns to MainActivity
        });

    }
}

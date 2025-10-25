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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera);

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom); // add ~40dp extra top padding
            return insets;
        });


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
            Intent intent = new Intent(CameraActivity.this, MainActivity.class);
            startActivity(intent);
            finish();  // closes CameraActivity and returns to MainActivity
        });

    }
}

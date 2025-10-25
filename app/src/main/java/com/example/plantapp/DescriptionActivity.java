package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.firebase.ai.*;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DescriptionActivity extends AppCompatActivity {

    TextView resultTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_description);

        Button takeAnotherButton = findViewById(R.id.TakeAnotherPictureButton);
        takeAnotherButton.setOnClickListener(v -> {
            Intent intent = new Intent(DescriptionActivity.this, CameraActivity.class);
            startActivity(intent);
            finish(); // optional: prevents returning here again unless new description is made
        });

        findViewById(R.id.BackButton).setOnClickListener(v -> {
            Intent intent = new Intent(DescriptionActivity.this, MainActivity.class);
            startActivity(intent);
        });
        resultTextView = findViewById(R.id.PlantDescriptionText);

        Executor callbackExecutor = MoreExecutors.directExecutor();

        GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI()).generativeModel("gemini-2.5-flash");
        GenerativeModelFutures model = GenerativeModelFutures.from(ai);

        Content prompt = new Content.Builder().addText("Name 5 plants separated by commas").build();

        // To generate text output, call generateContent with the text input
        ListenableFuture<GenerateContentResponse> response = model.generateContent(prompt);
        Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                String resultText = result.getText();
                runOnUiThread(() -> resultTextView.setText(resultText));
            }

            @Override
            public void onFailure(Throwable t) {
                t.printStackTrace();
                runOnUiThread(() -> resultTextView.setText("Error: " + t.getMessage()));
            }
        }, callbackExecutor);
    }
}
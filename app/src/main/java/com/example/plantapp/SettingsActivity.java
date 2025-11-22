package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
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
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private TextView usernameDisplay;
    private TextView plantCounterText;
    private TextView plantRankingText;
    private ProgressBar rankProgressBar;
    private ListView historyListView;

    private final List<PlantCapture> historyItems = new ArrayList<>();
    private HistoryAdapter historyAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        // padding to not overlap with phone camera
        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // back button listener
        ImageButton backButton = findViewById(R.id.BackButton);
        backButton.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        // username display id
        usernameDisplay = findViewById(R.id.UsernameDisplay);
        plantCounterText = findViewById(R.id.PlantCounterText);
        plantRankingText = findViewById(R.id.PlantRankingText);
        rankProgressBar = findViewById(R.id.RankProgressBar);
        historyListView = findViewById(R.id.listView);

        // logout button linked to xml element + listener
        Button logoutBtn = findViewById(R.id.LogoutButton);
        logoutBtn.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
            Intent i = new Intent(SettingsActivity.this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });

        // Clear History button
        Button clearHistoryBtn = findViewById(R.id.ClearHistoryButton);
        clearHistoryBtn.setOnClickListener(v -> clearHistory());

        // Set up history list + adapter
        historyAdapter = new HistoryAdapter(historyItems);
        historyListView.setAdapter(historyAdapter);

        // load username once
        loadUsername();
        // DO NOT call loadHistory() here; we do it in onResume so it refreshes after delete
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload history every time we return to this screen,
        // so deleted items disappear immediately.
        loadHistory();
    }

    // load username from firestore
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

    /** Load plant capture history from Firestore into the list. */
    private void loadHistory() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users")
                .document(uid)
                .collection("captures")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    historyItems.clear();

                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        String docId = doc.getId();
                        String imageUrl = doc.getString("url");
                        String role = doc.getString("role");
                        String commonName = doc.getString("commonName");
                        String dateTime = doc.getString("dateTime");
                        String scientificName = doc.getString("scientificName");
                        String description = doc.getString("description");
                        Long confLong = doc.getLong("confidence");
                        int confidence = confLong != null ? confLong.intValue() : 0;

                        if (imageUrl == null) continue;

                        PlantCapture item = new PlantCapture(
                                docId,
                                imageUrl,
                                role != null ? role : "Hiker",
                                commonName,
                                dateTime,
                                scientificName,
                                description,
                                confidence
                        );
                        historyItems.add(item);
                    }

                    historyAdapter.notifyDataSetChanged();
                    updatePlantStats(historyItems.size());
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this,
                            "Failed to load plant history: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    /** Update the counter text, rank text, and progress bar based on number of plants. */
    private void updatePlantStats(int count) {
        plantCounterText.setText("You've identified " + count + " plants!");

        String rank;
        int progress; // 0-100

        if (count <= 0) {
            // Stage 1: Beginner, no plants
            rank = "You are a Plant Beginner";
            progress = 0;
        } else if (count < 3) {
            // Stage 1: Beginner, filling bar over first 3 plants
            rank = "You are a Plant Beginner";
            progress = (int) Math.round((count / 3.0) * 100.0);
        } else if (count < 6) {
            // Stage 2: Amateur, bar reset, 3 plants to fill
            rank = "You are a Plant Amateur";
            int stageCount = count - 3; // 0..2
            progress = (int) Math.round((stageCount / 3.0) * 100.0);
        } else {
            // Stage 3: Pro, bar reset, fills after 4 plants then stays full
            rank = "You are a Plant Pro";
            int stageCount = count - 6; // 0..?
            if (stageCount <= 0) {
                progress = 0;
            } else if (stageCount < 4) {
                progress = (int) Math.round((stageCount / 4.0) * 100.0);
            } else {
                progress = 100;
            }
        }

        plantRankingText.setText(rank);
        rankProgressBar.setProgress(progress);
    }

    /** Clear all history entries from Firestore (for this user). */
    private void clearHistory() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        if (historyItems.isEmpty()) {
            Toast.makeText(this, "No history to clear", Toast.LENGTH_SHORT).show();
            return;
        }

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        for (PlantCapture item : historyItems) {
            db.collection("users")
                    .document(uid)
                    .collection("captures")
                    .document(item.docId)
                    .delete();
        }

        historyItems.clear();
        historyAdapter.notifyDataSetChanged();
        updatePlantStats(0);
        Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
    }

    // ---------- Model class for a capture ----------
    private static class PlantCapture {
        final String docId;
        final String imageUrl;
        final String role;
        final String commonName;
        final String dateTime;
        final String scientificName;
        final String description;
        final int confidence;

        PlantCapture(String docId,
                     String imageUrl,
                     String role,
                     String commonName,
                     String dateTime,
                     String scientificName,
                     String description,
                     int confidence) {
            this.docId = docId;
            this.imageUrl = imageUrl;
            this.role = role;
            this.commonName = commonName;
            this.dateTime = dateTime;
            this.scientificName = scientificName;
            this.description = description;
            this.confidence = confidence;
        }
    }

    // ---------- Adapter that renders each row as a "button" ----------
    private class HistoryAdapter extends ArrayAdapter<PlantCapture> {

        HistoryAdapter(List<PlantCapture> items) {
            super(SettingsActivity.this, android.R.layout.simple_list_item_1, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Button btn;
            if (convertView instanceof Button) {
                btn = (Button) convertView;
            } else {
                btn = new Button(SettingsActivity.this);
                btn.setLayoutParams(new ListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                btn.setAllCaps(false);
                btn.setPadding(20, 10, 20, 10);
                btn.setBackgroundResource(R.drawable.history_item_bg);
                btn.setTextColor(getResources().getColor(R.color.history_item_text, null));

            }

            PlantCapture item = getItem(position);
            if (item != null) {
                String name = (item.commonName != null && !item.commonName.isEmpty())
                        ? item.commonName
                        : "Unknown Plant";
                String role = (item.role != null && !item.role.isEmpty())
                        ? item.role
                        : "Unknown Role";
                String dt = (item.dateTime != null && !item.dateTime.isEmpty())
                        ? item.dateTime
                        : "";

                StringBuilder label = new StringBuilder();
                label.append(name).append(" • ").append(role);
                if (!dt.isEmpty()) {
                    label.append(" • ").append(dt);
                }
                btn.setText(label.toString());

                btn.setOnClickListener(v -> {
                    Intent intent = new Intent(SettingsActivity.this, HistoryDescriptionActivity.class);
                    intent.putExtra("docId", item.docId);
                    intent.putExtra("userRole", item.role);
                    intent.putExtra("imageUrl", item.imageUrl);
                    intent.putExtra("commonName", item.commonName);
                    intent.putExtra("scientificName", item.scientificName);
                    intent.putExtra("description", item.description);
                    intent.putExtra("confidence", item.confidence);
                    intent.putExtra("dateTime", item.dateTime);
                    startActivity(intent);
                });
            }

            return btn;
        }
    }
}

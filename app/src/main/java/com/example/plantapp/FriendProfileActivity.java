package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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

public class FriendProfileActivity extends AppCompatActivity {

    private TextView friendUsernameDisplay;
    private TextView friendPlantCounterText;
    private TextView friendPlantRankingText;
    private ProgressBar friendRankProgressBar;
    private ListView friendHistoryListView;

    private final List<PlantCapture> friendHistoryItems = new ArrayList<>();
    private FriendHistoryAdapter friendHistoryAdapter;

    private String friendUid;
    private String friendUsername;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_friend_profile);

        // padding for status bar notch etc
        View root = findViewById(R.id.friendProfileRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // Get friend data from intent
        Intent i = getIntent();
        friendUid = i.getStringExtra("friendUid");
        friendUsername = i.getStringExtra("friendUsername");

        if (friendUid == null || friendUid.isEmpty()) {
            Toast.makeText(this, "No friend selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Back button
        ImageButton backButton = findViewById(R.id.FriendBackButton);
        backButton.setOnClickListener(v -> finish());

        // Views
        friendUsernameDisplay = findViewById(R.id.FriendUsernameDisplay);
        friendPlantCounterText = findViewById(R.id.FriendPlantCounterText);
        friendPlantRankingText = findViewById(R.id.FriendPlantRankingText);
        friendRankProgressBar = findViewById(R.id.FriendRankProgressBar);
        friendHistoryListView = findViewById(R.id.FriendHistoryListView);

        // Set basic username text
        if (friendUsername != null && !friendUsername.isEmpty()) {
            friendUsernameDisplay.setText(friendUsername);
        } else {
            friendUsernameDisplay.setText("Friend");
        }

        // Adapter for friend plant history
        friendHistoryAdapter = new FriendHistoryAdapter(friendHistoryItems);
        friendHistoryListView.setAdapter(friendHistoryAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFriendHistory();
    }

    // Load this friend's captures and update stats
    private void loadFriendHistory() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users")
                .document(friendUid)
                .collection("captures")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    friendHistoryItems.clear();

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
                        friendHistoryItems.add(item);
                    }

                    friendHistoryAdapter.notifyDataSetChanged();
                    updateFriendStats(friendHistoryItems.size());
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this,
                            "Failed to load friend history: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    // Same rank logic as your own profile, but for friend
    private void updateFriendStats(int count) {
        friendPlantCounterText.setText(friendUsername + " has identified " + count + " plants!");

        String rank;
        int progress;

        if (count <= 0) {
            rank = friendUsername + " is a Plant Beginner";
            progress = 0;
        } else if (count < 3) {
            rank = friendUsername + " is a Plant Beginner";
            progress = (int) Math.round((count / 3.0) * 100.0);
        } else if (count < 6) {
            rank = friendUsername + " is a Plant Amateur";
            int stageCount = count - 3;
            progress = (int) Math.round((stageCount / 3.0) * 100.0);
        } else {
            rank = friendUsername + " is a Plant Pro";
            int stageCount = count - 6;
            if (stageCount <= 0) {
                progress = 0;
            } else if (stageCount < 4) {
                progress = (int) Math.round((stageCount / 4.0) * 100.0);
            } else {
                progress = 100;
            }
        }

        friendPlantRankingText.setText(rank);
        friendRankProgressBar.setProgress(progress);
    }

    // Same model as in SettingsActivity
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

    // Adapter for friend's history list (styled like your own)
    private class FriendHistoryAdapter extends ArrayAdapter<PlantCapture> {

        FriendHistoryAdapter(List<PlantCapture> items) {
            super(FriendProfileActivity.this, android.R.layout.simple_list_item_1, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            android.widget.Button btn;
            if (convertView instanceof android.widget.Button) {
                btn = (android.widget.Button) convertView;
            } else {
                btn = new android.widget.Button(FriendProfileActivity.this);
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
                    Intent intent = new Intent(FriendProfileActivity.this, HistoryDescriptionActivity.class);
                    intent.putExtra("docId", item.docId);
                    intent.putExtra("userRole", item.role);
                    intent.putExtra("imageUrl", item.imageUrl);
                    intent.putExtra("commonName", item.commonName);
                    intent.putExtra("scientificName", item.scientificName);
                    intent.putExtra("description", item.description);
                    intent.putExtra("confidence", item.confidence);
                    intent.putExtra("dateTime", item.dateTime);

                    // Viewing a FRIEND's capture → NO delete allowed
                    intent.putExtra("allowDelete", false);

                    startActivity(intent);
                });

            }

            return btn;
        }
    }
}

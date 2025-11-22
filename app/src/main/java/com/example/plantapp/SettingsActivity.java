package com.example.plantapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
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
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private TextView usernameDisplay;
    private TextView plantCounterText;
    private TextView plantRankingText;
    private ProgressBar rankProgressBar;
    private ListView historyListView;
    private ListView friendsListView;
    private TextView friendRequestBadge;

    private final List<PlantCapture> historyItems = new ArrayList<>();
    private HistoryAdapter historyAdapter;

    // Friends list data + adapter
    private final List<FriendItem> friendItems = new ArrayList<>();
    private FriendAdapter friendAdapter;

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

        // back button
        ImageButton backButton = findViewById(R.id.BackButton);
        backButton.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        // find views
        usernameDisplay    = findViewById(R.id.UsernameDisplay);
        plantCounterText   = findViewById(R.id.PlantCounterText);
        plantRankingText   = findViewById(R.id.PlantRankingText);
        rankProgressBar    = findViewById(R.id.RankProgressBar);
        historyListView    = findViewById(R.id.listView);
        friendsListView    = findViewById(R.id.friendsListView);
        friendRequestBadge = findViewById(R.id.friendRequestBadge);

        Button logoutBtn = findViewById(R.id.LogoutButton);
        logoutBtn.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
            Intent i = new Intent(SettingsActivity.this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });

        Button clearHistoryBtn = findViewById(R.id.ClearHistoryButton);
        clearHistoryBtn.setOnClickListener(v -> clearHistory());

        // Friends button, dialog, etc. (keep your existing code elsewhere)

        // Set up history list + adapter
        historyAdapter = new HistoryAdapter(historyItems);
        historyListView.setAdapter(historyAdapter);

        // Set up friends list + adapter
        friendAdapter = new FriendAdapter(friendItems);
        friendsListView.setAdapter(friendAdapter);

        // load username once
        loadUsername();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
        loadFriends();   // refresh friends as well
        // also refresh incoming request badge wherever you do that
    }

    // -------------------- USERNAME --------------------
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

    // -------------------- HISTORY --------------------
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
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    historyItems.clear();

                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        String docId = doc.getId();
                        String imageUrl = doc.getString("url");
                        String role = doc.getString("role");
                        String commonName = doc.getString("commonName");
                        String dateTime = doc.getString("dateTime"); // optional, fallback
                        String scientificName = doc.getString("scientificName");
                        String description = doc.getString("description");
                        Long confLong = doc.getLong("confidence");
                        int confidence = confLong != null ? confLong.intValue() : 0;

                        Long tsLong = doc.getLong("timestamp");
                        long timestamp = tsLong != null ? tsLong : 0L;

                        if (imageUrl == null) continue;

                        PlantCapture item = new PlantCapture(
                                docId,
                                imageUrl,
                                role != null ? role : "Hiker",
                                commonName,
                                dateTime,
                                scientificName,
                                description,
                                confidence,
                                timestamp
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
            rank = "You are a Plant Beginner";
            progress = 0;
        } else if (count < 3) {
            rank = "You are a Plant Beginner";
            progress = (int) Math.round((count / 3.0) * 100.0);
        } else if (count < 6) {
            rank = "You are a Plant Amateur";
            int stageCount = count - 3;
            progress = (int) Math.round((stageCount / 3.0) * 100.0);
        } else {
            rank = "You are a Plant Pro";
            int stageCount = count - 6;
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
        final String dateTime;      // original string (if any)
        final String scientificName;
        final String description;
        final int confidence;
        final long timestamp;       // for formatted date

        PlantCapture(String docId,
                     String imageUrl,
                     String role,
                     String commonName,
                     String dateTime,
                     String scientificName,
                     String description,
                     int confidence,
                     long timestamp) {
            this.docId = docId;
            this.imageUrl = imageUrl;
            this.role = role;
            this.commonName = commonName;
            this.dateTime = dateTime;
            this.scientificName = scientificName;
            this.description = description;
            this.confidence = confidence;
            this.timestamp = timestamp;
        }
    }

    // ---------- Adapter: history row uses left title + right date ----------
    private class HistoryAdapter extends ArrayAdapter<PlantCapture> {

        private final LayoutInflater inflater = LayoutInflater.from(SettingsActivity.this);
        private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

        HistoryAdapter(List<PlantCapture> items) {
            super(SettingsActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = inflater.inflate(R.layout.item_history_capture, parent, false);
            }

            TextView titleTv = row.findViewById(R.id.historyTitle);
            TextView dateTv  = row.findViewById(R.id.historyDate);

            PlantCapture item = getItem(position);
            if (item != null) {
                String name = (item.commonName != null && !item.commonName.isEmpty())
                        ? item.commonName
                        : "Unknown Plant";
                String role = (item.role != null && !item.role.isEmpty())
                        ? item.role
                        : "Unknown Role";

                // Left: "Poison ivy · Hiker"
                titleTv.setText(name + " · " + role);

                // Right: formatted date
                dateTv.setText(formatDate(item));

                // Click opens HistoryDescriptionActivity
                row.setOnClickListener(v -> {
                    Intent intent = new Intent(SettingsActivity.this, HistoryDescriptionActivity.class);
                    intent.putExtra("docId", item.docId);
                    intent.putExtra("userRole", item.role);
                    intent.putExtra("imageUrl", item.imageUrl);
                    intent.putExtra("commonName", item.commonName);
                    intent.putExtra("scientificName", item.scientificName);
                    intent.putExtra("description", item.description);
                    intent.putExtra("confidence", item.confidence);
                    intent.putExtra("dateTime", item.dateTime);
                    // owner → can delete
                    intent.putExtra("allowDelete", true);
                    startActivity(intent);
                });
            }

            return row;
        }

        private String formatDate(PlantCapture item) {
            if (item == null) return "";
            if (item.timestamp > 0L) {
                Date d = new Date(item.timestamp);
                return sdf.format(d);
            }
            if (item.dateTime != null && !item.dateTime.isEmpty()) {
                return item.dateTime;
            }
            return "";
        }
    }

    // -------------------- FRIENDS LIST --------------------

    /** Model for friends shown under plant history. */
    private static class FriendItem {
        final String friendUid;
        final String friendUsername;

        FriendItem(String friendUid, String friendUsername) {
            this.friendUid = friendUid;
            this.friendUsername = friendUsername;
        }
    }

    /** Load this user's friends from Firestore into friendsListView. */
    private void loadFriends() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users")
                .document(uid)
                .collection("friends")
                .get()   // no orderBy to avoid index issues
                .addOnSuccessListener(querySnapshot -> {
                    friendItems.clear();

                    for (QueryDocumentSnapshot doc : querySnapshot) {
                        // Try flexible field names so it works with your existing data
                        String friendUid = doc.getString("friendUid");
                        if (friendUid == null || friendUid.isEmpty()) {
                            // Fall back to doc ID if you used that
                            friendUid = doc.getId();
                        }

                        String friendUsername = doc.getString("friendUsername");
                        if (friendUsername == null) {
                            friendUsername = doc.getString("username");
                        }
                        if (friendUsername == null) {
                            friendUsername = doc.getString("displayName");
                        }
                        if (friendUsername == null) {
                            friendUsername = friendUid; // last resort
                        }

                        if (friendUid == null || friendUid.isEmpty()) continue;

                        friendItems.add(new FriendItem(friendUid, friendUsername));
                    }

                    friendAdapter.notifyDataSetChanged();

                    // Debug toast so you see that it actually loaded something
                    //Toast.makeText(this,
                            //"Loaded " + friendItems.size() + " friends",
                            //Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Failed to load friends: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    /** Adapter for friends list – same style rectangles, click → friend profile. */
    private class FriendAdapter extends ArrayAdapter<FriendItem> {

        FriendAdapter(List<FriendItem> items) {
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

            FriendItem item = getItem(position);
            if (item != null) {
                btn.setText(item.friendUsername);

                // tap friend → open their profile page
                btn.setOnClickListener(v -> {
                    Intent intent = new Intent(SettingsActivity.this, FriendProfileActivity.class);
                    intent.putExtra("friendUid", item.friendUid);
                    intent.putExtra("friendUsername", item.friendUsername);
                    startActivity(intent);
                });
            }

            return btn;
        }
    }
}

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

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FriendProfileActivity extends AppCompatActivity {

    private TextView friendUsernameTv;
    private TextView friendCounterTv;
    private TextView friendRankTv;
    private ProgressBar friendRankBar;
    private TextView friendHistoryTitleTv;
    private ListView friendHistoryListView;
    private ImageButton backBtn;
    private Button removeFriendBtn;

    private final List<PlantCapture> friendHistoryItems = new ArrayList<>();
    private FriendHistoryAdapter friendHistoryAdapter;

    private String friendUid;
    private String friendUsername;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_friend_profile);

        // Get extras from intent
        friendUid = getIntent().getStringExtra("friendUid");
        friendUsername = getIntent().getStringExtra("friendUsername");

        if (friendUid == null || friendUid.isEmpty()) {
            Toast.makeText(this, "Missing friend ID", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Insets using your root ID
        View root = findViewById(R.id.friendProfileRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        // Link views
        backBtn              = findViewById(R.id.FriendBackButton);
        friendUsernameTv     = findViewById(R.id.FriendUsernameDisplay);
        friendCounterTv      = findViewById(R.id.FriendPlantCounterText);
        friendRankTv         = findViewById(R.id.FriendPlantRankingText);
        friendRankBar        = findViewById(R.id.FriendRankProgressBar);
        friendHistoryTitleTv = findViewById(R.id.FriendHistoryTitle);
        friendHistoryListView = findViewById(R.id.FriendHistoryListView);
        removeFriendBtn      = findViewById(R.id.RemoveFriendButton);

        if (friendUsername != null && !friendUsername.isEmpty()) {
            friendUsernameTv.setText(friendUsername);
        }

        backBtn.setOnClickListener(v -> finish());

        // Remove friend button
        removeFriendBtn.setOnClickListener(v -> removeFriend());

        // List + adapter for friend history
        friendHistoryAdapter = new FriendHistoryAdapter(friendHistoryItems);
        friendHistoryListView.setAdapter(friendHistoryAdapter);

        loadFriendHistory();
    }

    /** Load this friend's captures from Firestore. */
    private void loadFriendHistory() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users")
                .document(friendUid)
                .collection("captures")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    friendHistoryItems.clear();

                    int count = 0;
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
                        friendHistoryItems.add(item);
                        count++;
                    }

                    friendHistoryAdapter.notifyDataSetChanged();
                    updateFriendStats(count);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to load friend history: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    /** Same rank logic as your own profile, but for friend. */
    private void updateFriendStats(int count) {
        friendCounterTv.setText("They've identified " + count + " plants!");

        String rank;
        int progress;
        if (count <= 0) {
            rank = "Plant Beginner";
            progress = 0;
        } else if (count < 3) {
            rank = "Plant Beginner";
            progress = (int) Math.round((count / 3.0) * 100.0);
        } else if (count < 6) {
            rank = "Plant Amateur";
            int stageCount = count - 3;
            progress = (int) Math.round((stageCount / 3.0) * 100.0);
        } else {
            rank = "Plant Pro";
            int stageCount = count - 6;
            if (stageCount <= 0) {
                progress = 0;
            } else if (stageCount < 4) {
                progress = (int) Math.round((stageCount / 4.0) * 100.0);
            } else {
                progress = 100;
            }
        }

        friendRankTv.setText(rank);
        friendRankBar.setProgress(progress);
    }

    /** Remove this friend from both users' friends lists. */
    private void removeFriend() {
        FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
        if (current == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String currentUid = current.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        removeFriendBtn.setEnabled(false);

        // assume friends are stored as:
        // users/{uid}/friends/{friendUid}
        Task<Void> t1 = db.collection("users")
                .document(currentUid)
                .collection("friends")
                .document(friendUid)
                .delete();

        Task<Void> t2 = db.collection("users")
                .document(friendUid)
                .collection("friends")
                .document(currentUid)
                .delete();

        Tasks.whenAll(t1, t2)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Friend removed", Toast.LENGTH_SHORT).show();
                    finish(); // go back to settings, where list will refresh
                })
                .addOnFailureListener(e -> {
                    removeFriendBtn.setEnabled(true);
                    Toast.makeText(this,
                            "Failed to remove friend: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    /** Model for a capture (same as in SettingsActivity). */
    private static class PlantCapture {
        final String docId;
        final String imageUrl;
        final String role;
        final String commonName;
        final String dateTime;
        final String scientificName;
        final String description;
        final int confidence;
        final long timestamp;

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

    /** Adapter: "Poison ivy · Hiker" left, "yyyy/MM/dd HH:mm" right. */
    private class FriendHistoryAdapter extends ArrayAdapter<PlantCapture> {

        private final LayoutInflater inflater = LayoutInflater.from(FriendProfileActivity.this);
        private final SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

        FriendHistoryAdapter(List<PlantCapture> items) {
            super(FriendProfileActivity.this, 0, items);
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

                // Right: yyyy/MM/dd HH:mm (24h)
                dateTv.setText(formatDate(item));

                // Clicking opens HistoryDescriptionActivity with allowDelete = false
                row.setOnClickListener(v -> {
                    Intent intent = new Intent(FriendProfileActivity.this, HistoryDescriptionActivity.class);
                    intent.putExtra("docId", item.docId);
                    intent.putExtra("userRole", item.role);
                    intent.putExtra("imageUrl", item.imageUrl);
                    intent.putExtra("commonName", item.commonName);
                    intent.putExtra("scientificName", item.scientificName);
                    intent.putExtra("description", item.description);
                    intent.putExtra("confidence", item.confidence);
                    intent.putExtra("dateTime", item.dateTime);
                    intent.putExtra("allowDelete", false); // cannot delete friend history
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
}

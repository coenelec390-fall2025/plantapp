package com.example.plantapp;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final List<Friend> friends = new ArrayList<>();
    private FriendsAdapter friendsAdapter;

    // For refreshing dialog contents after sending/accepting/denying/cancelling
    private LinearLayout currentIncomingContainer;
    private LinearLayout currentOutgoingContainer;

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

        // "friends" / add friend button in top right
        ImageButton addFriendButton = findViewById(R.id.AddFriendButton);
        addFriendButton.setOnClickListener(v -> showFriendsPopup());

        // badge over friends button
        friendRequestBadge = findViewById(R.id.friendRequestBadge);

        // username + stats views
        usernameDisplay = findViewById(R.id.UsernameDisplay);
        plantCounterText = findViewById(R.id.PlantCounterText);
        plantRankingText = findViewById(R.id.PlantRankingText);
        rankProgressBar = findViewById(R.id.RankProgressBar);

        // history list
        historyListView = findViewById(R.id.listView);
        historyAdapter = new HistoryAdapter(historyItems);
        historyListView.setAdapter(historyAdapter);

        // friends list (styled similar to history)
        friendsListView = findViewById(R.id.friendsListView);
        friendsAdapter = new FriendsAdapter(friends);
        friendsListView.setAdapter(friendsAdapter);

        // logout button
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

        // load username once
        loadUsername();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
        loadFriends();
        updateFriendRequestBadge();
    }

    // ---------------- USERNAME ----------------

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

    // ---------------- HISTORY ----------------

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

    // ---------------- FRIENDS LIST ----------------

    private void loadFriends() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return;
        }

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("users")
                .document(uid)
                .collection("friends")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    friends.clear();
                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        String friendUid = doc.getString("uid");
                        String username = doc.getString("username");
                        if (friendUid == null) continue;
                        friends.add(new Friend(friendUid, username != null ? username : "Unknown"));
                    }
                    friendsAdapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this,
                            "Failed to load friends: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    // ---------------- FRIEND REQUEST BADGE ----------------

    /** Updates the red badge with the number of pending incoming friend requests. */
    private void updateFriendRequestBadge() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (friendRequestBadge == null) return;

        if (user == null) {
            friendRequestBadge.setVisibility(View.GONE);
            return;
        }

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("friendRequests")
                .whereEqualTo("toUid", uid)
                .whereEqualTo("status", "pending")
                .get()
                .addOnSuccessListener(qs -> {
                    int count = qs.size();
                    if (count > 0) {
                        friendRequestBadge.setVisibility(View.VISIBLE);
                        if (count > 99) {
                            friendRequestBadge.setText("99+");
                        } else {
                            friendRequestBadge.setText(String.valueOf(count));
                        }
                    } else {
                        friendRequestBadge.setVisibility(View.GONE);
                    }
                })
                .addOnFailureListener(e -> friendRequestBadge.setVisibility(View.GONE));
    }

    // ---------------- FRIENDS POPUP & REQUESTS ----------------

    private void showFriendsPopup() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_friends, null);

        LinearLayout incomingContainer = dialogView.findViewById(R.id.incomingContainer);
        LinearLayout outgoingContainer = dialogView.findViewById(R.id.outgoingContainer);
        Button sendRequestButton = dialogView.findViewById(R.id.sendRequestButton);

        // keep references so we can refresh from sendFriendRequest()
        currentIncomingContainer = incomingContainer;
        currentOutgoingContainer = outgoingContainer;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));


        // Load incoming/outgoing into the containers
        loadFriendRequests(incomingContainer, outgoingContainer);

        // Send-new-request button
        sendRequestButton.setOnClickListener(v -> showSendRequestDialog());

        dialog.show();
    }

    private void loadFriendRequests(LinearLayout incomingContainer,
                                    LinearLayout outgoingContainer) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        String uid = user.getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        incomingContainer.removeAllViews();
        outgoingContainer.removeAllViews();

        LayoutInflater inflater = getLayoutInflater();

        // Incoming pending requests
        db.collection("friendRequests")
                .whereEqualTo("toUid", uid)
                .whereEqualTo("status", "pending")
                .get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        addSimpleLabel(incomingContainer, "No incoming requests");
                    } else {
                        for (QueryDocumentSnapshot doc : qs) {
                            String requestId = doc.getId();
                            String fromUid = doc.getString("fromUid");
                            String fromUsername = doc.getString("fromUsername");

                            View itemView = inflater.inflate(R.layout.item_incoming_request,
                                    incomingContainer, false);
                            TextView name = itemView.findViewById(R.id.usernameText);
                            Button acceptBtn = itemView.findViewById(R.id.acceptButton);
                            Button denyBtn = itemView.findViewById(R.id.denyButton);

                            name.setText(fromUsername != null ? fromUsername : "Unknown");

                            acceptBtn.setOnClickListener(v ->
                                    acceptFriendRequest(requestId, fromUid, fromUsername,
                                            itemView, incomingContainer));
                            denyBtn.setOnClickListener(v ->
                                    denyFriendRequest(requestId, itemView, incomingContainer));

                            incomingContainer.addView(itemView);
                        }
                    }
                });

        // Outgoing pending requests
        db.collection("friendRequests")
                .whereEqualTo("fromUid", uid)
                .whereEqualTo("status", "pending")
                .get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        addSimpleLabel(outgoingContainer, "No outgoing requests");
                    } else {
                        for (QueryDocumentSnapshot doc : qs) {
                            String requestId = doc.getId();
                            String toUsername = doc.getString("toUsername");

                            View itemView = inflater.inflate(R.layout.item_outgoing_request,
                                    outgoingContainer, false);
                            TextView name = itemView.findViewById(R.id.usernameText);
                            Button cancelBtn = itemView.findViewById(R.id.cancelButton);

                            name.setText(toUsername != null ? toUsername : "Unknown");
                            cancelBtn.setOnClickListener(v ->
                                    cancelFriendRequest(requestId, itemView, outgoingContainer));

                            outgoingContainer.addView(itemView);
                        }
                    }
                });
    }

    private void addSimpleLabel(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(8, 8, 8, 8);
        container.addView(tv);
    }

    private void showSendRequestDialog() {
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_friend_request, null);

        EditText usernameInput = dialogView.findViewById(R.id.friendUsernameInput);
        Button cancelButton = dialogView.findViewById(R.id.cancelButton);
        Button sendButton = dialogView.findViewById(R.id.confirmSendButton);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));


        cancelButton.setOnClickListener(v -> dialog.dismiss());

        sendButton.setOnClickListener(v -> {
            String targetUsername = usernameInput.getText().toString().trim();
            if (targetUsername.isEmpty()) {
                Toast.makeText(this,
                        "Username cannot be empty", Toast.LENGTH_SHORT).show();
            } else {
                sendFriendRequest(targetUsername);
                dialog.dismiss();
            }
        });

        dialog.show();
    }


    // Block self-request, check not already friend, check no pending request, then create and refresh dialog
    private void sendFriendRequest(String targetUsername) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show();
            return;
        }

        String fromUid = currentUser.getUid();
        String fromUsername = usernameDisplay.getText().toString().trim();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        targetUsername = targetUsername.trim();

        // If they type their own username, bail early
        if (targetUsername.equalsIgnoreCase(fromUsername)) {
            Toast.makeText(this,
                    "You cannot send a request to yourself",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 1) Find user by username
        String finalTargetUsername = targetUsername;
        db.collection("users")
                .whereEqualTo("username", targetUsername)
                .limit(1)
                .get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        Toast.makeText(this,
                                "No user found with username " + finalTargetUsername,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    DocumentSnapshot target = qs.getDocuments().get(0);
                    String toUid = target.getId();
                    String toUsername = target.getString("username");

                    // Extra safety: also block by UID (in case username == your own)
                    if (toUid.equals(fromUid)) {
                        Toast.makeText(this,
                                "You cannot send a request to yourself",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 2) Check if already friends
                    db.collection("users")
                            .document(fromUid)
                            .collection("friends")
                            .document(toUid)
                            .get()
                            .addOnSuccessListener(friendDoc -> {
                                if (friendDoc.exists()) {
                                    Toast.makeText(this,
                                            "You are already friends with " + finalTargetUsername,
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }

                                // 3) Check if a pending request already exists
                                db.collection("friendRequests")
                                        .whereEqualTo("fromUid", fromUid)
                                        .whereEqualTo("toUid", toUid)
                                        .whereEqualTo("status", "pending")
                                        .get()
                                        .addOnSuccessListener(existing -> {
                                            if (!existing.isEmpty()) {
                                                Toast.makeText(this,
                                                        "Request already sent",
                                                        Toast.LENGTH_SHORT).show();
                                                return;
                                            }

                                            // 4) Create the friend request
                                            Map<String, Object> data = new HashMap<>();
                                            data.put("fromUid", fromUid);
                                            data.put("fromUsername", fromUsername);
                                            data.put("toUid", toUid);
                                            data.put("toUsername", toUsername);
                                            data.put("status", "pending");
                                            data.put("timestamp", FieldValue.serverTimestamp());

                                            db.collection("friendRequests")
                                                    .add(data)
                                                    .addOnSuccessListener(docRef -> {
                                                        Toast.makeText(this,
                                                                "Friend request sent",
                                                                Toast.LENGTH_SHORT).show();

                                                        // refresh outgoing list in dialog immediately if open
                                                        if (currentIncomingContainer != null &&
                                                                currentOutgoingContainer != null) {
                                                            loadFriendRequests(
                                                                    currentIncomingContainer,
                                                                    currentOutgoingContainer
                                                            );
                                                        }
                                                    })
                                                    .addOnFailureListener(e ->
                                                            Toast.makeText(this,
                                                                    "Failed to send request: " + e.getMessage(),
                                                                    Toast.LENGTH_SHORT).show());
                                        });
                            })
                            .addOnFailureListener(e -> Toast.makeText(this,
                                    "Error checking friends: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void acceptFriendRequest(String requestId,
                                     String otherUid,
                                     String otherUsername,
                                     View itemView,
                                     LinearLayout parentContainer) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null || otherUid == null) return;

        String myUid = currentUser.getUid();
        String myUsername = usernameDisplay.getText().toString();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // Create friend docs for both users
        Map<String, Object> friendForMe = new HashMap<>();
        friendForMe.put("uid", otherUid);
        friendForMe.put("username", otherUsername);

        Map<String, Object> friendForOther = new HashMap<>();
        friendForOther.put("uid", myUid);
        friendForOther.put("username", myUsername);

        db.collection("users").document(myUid)
                .collection("friends").document(otherUid)
                .set(friendForMe);

        db.collection("users").document(otherUid)
                .collection("friends").document(myUid)
                .set(friendForOther);

        // Update request status
        db.collection("friendRequests")
                .document(requestId)
                .update("status", "accepted")
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this,
                            "Friend request accepted",
                            Toast.LENGTH_SHORT).show();
                    // Remove this item from the dialog immediately
                    parentContainer.removeView(itemView);
                    if (parentContainer.getChildCount() == 0) {
                        addSimpleLabel(parentContainer, "No incoming requests");
                    }
                    loadFriends();
                    updateFriendRequestBadge();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Failed to update request: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    private void denyFriendRequest(String requestId,
                                   View itemView,
                                   LinearLayout parentContainer) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("friendRequests")
                .document(requestId)
                .update("status", "denied")
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this,
                            "Request denied",
                            Toast.LENGTH_SHORT).show();
                    // Remove this item from the dialog immediately
                    parentContainer.removeView(itemView);
                    if (parentContainer.getChildCount() == 0) {
                        addSimpleLabel(parentContainer, "No incoming requests");
                    }
                    updateFriendRequestBadge();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Failed to deny request: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    private void cancelFriendRequest(String requestId,
                                     View itemView,
                                     LinearLayout parentContainer) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("friendRequests")
                .document(requestId)
                .update("status", "cancelled")
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this,
                            "Request cancelled",
                            Toast.LENGTH_SHORT).show();
                    // Remove this item from the dialog immediately
                    parentContainer.removeView(itemView);
                    if (parentContainer.getChildCount() == 0) {
                        addSimpleLabel(parentContainer, "No outgoing requests");
                    }
                    updateFriendRequestBadge();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Failed to cancel request: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    // ---------------- MODELS & ADAPTERS ----------------

    // Model for one plant capture
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

    // Model for a friend
    private static class Friend {
        final String uid;
        final String username;

        Friend(String uid, String username) {
            this.uid = uid;
            this.username = username;
        }
    }

    // History list adapter
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

                    // You are the owner here → allow deletion
                    intent.putExtra("allowDelete", true);

                    startActivity(intent);
                });

            }

            return btn;
        }
    }

    // Friends list adapter – styled like history buttons
    private class FriendsAdapter extends ArrayAdapter<Friend> {

        FriendsAdapter(List<Friend> items) {
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

            Friend f = getItem(position);
            if (f != null) {
                btn.setText(f.username);

                // 👉 Tap friend to open their profile page
                btn.setOnClickListener(v -> {
                    Intent intent = new Intent(SettingsActivity.this, FriendProfileActivity.class);
                    intent.putExtra("friendUid", f.uid);
                    intent.putExtra("friendUsername", f.username);
                    startActivity(intent);
                });
            }

            return btn;
        }
    }
}

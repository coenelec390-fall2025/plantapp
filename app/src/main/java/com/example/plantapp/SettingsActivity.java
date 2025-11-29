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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

//this may say settings activity, but if you read a little between the lines you can see that its actually the profile activity. just a glimpse into my dark twisted mind.
public class SettingsActivity extends AppCompatActivity {

    private TextView usernameDisplay;
    private TextView plantCounterText;
    private TextView plantRankingText;
    private ProgressBar rankProgressBar;
    private ListView historyListView;
    private ListView friendsListView;
    private TextView friendRequestBadge;

    private TextView historyEmptyText;
    private TextView friendsEmptyText;

    //history
    private final List<PlantCapture> historyItems = new ArrayList<>();
    private HistoryAdapter historyAdapter;

    //friends list (bottom of screen)
    private final List<FriendItem> friendItems = new ArrayList<>();
    private FriendAdapter friendAdapter;

    //friend request dialog data
    private final List<FriendRequestItem> incomingRequests = new ArrayList<>();
    private final List<FriendRequestItem> outgoingRequests = new ArrayList<>();
    private IncomingAdapter incomingAdapter;
    private OutgoingAdapter outgoingAdapter;

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private String currentUid;
    private String currentUsername;

    private static final String SUB_FRIENDS = "friends";
    private static final String SUB_REQ_IN = "friendRequestsIncoming";
    private static final String SUB_REQ_OUT = "friendRequestsOutgoing";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        //sends user to login if not signed in
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        currentUid = user.getUid();

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top + 40, sys.right, sys.bottom);
            return insets;
        });

        ImageButton backButton = findViewById(R.id.BackButton);
        backButton.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        ImageButton addFriendButton = findViewById(R.id.AddFriendButton);
        addFriendButton.setOnClickListener(v -> openFriendsDialog());

        usernameDisplay    = findViewById(R.id.UsernameDisplay);
        plantCounterText   = findViewById(R.id.PlantCounterText);
        plantRankingText   = findViewById(R.id.PlantRankingText);
        rankProgressBar    = findViewById(R.id.RankProgressBar);
        historyListView    = findViewById(R.id.listView);
        friendsListView    = findViewById(R.id.friendsListView);
        friendRequestBadge = findViewById(R.id.friendRequestBadge);

        historyEmptyText   = findViewById(R.id.historyEmptyText);
        friendsEmptyText   = findViewById(R.id.friendsEmptyText);

        historyListView.setEmptyView(historyEmptyText);
        friendsListView.setEmptyView(friendsEmptyText);

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

        historyAdapter = new HistoryAdapter(historyItems);
        historyListView.setAdapter(historyAdapter);

        friendAdapter = new FriendAdapter(friendItems);
        friendsListView.setAdapter(friendAdapter);

        loadUsername();
    }


    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
        loadFriends();
        loadFriendRequestCounts();
    }

    //load the username from the user
    private void loadUsername() {
        db.collection("users").document(currentUid).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        currentUsername = doc.getString("username");
                        usernameDisplay.setText(currentUsername != null ? currentUsername : "Unnamed User");
                    } else {
                        usernameDisplay.setText("No user data found");
                    }
                })
                .addOnFailureListener(e -> {
                    usernameDisplay.setText("Failed to load username");
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    //load the captures the user has taken
    private void loadHistory() {
        db.collection("users")
                .document(currentUid)
                .collection("captures")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(qs -> {
                    historyItems.clear();
                    for (QueryDocumentSnapshot doc : qs) {
                        String docId = doc.getId();
                        String imageUrl = doc.getString("url");
                        String role = doc.getString("role");
                        String commonName = doc.getString("commonName");
                        String dateTime = doc.getString("dateTime");
                        String scientificName = doc.getString("scientificName");
                        String description = doc.getString("description");
                        Long confLong = doc.getLong("confidence");
                        int confidence = Math.toIntExact(confLong != null ? confLong : 0);
                        Long tsLong = doc.getLong("timestamp");
                        long timestamp = tsLong != null ? tsLong : 0L;

                        if (imageUrl == null) continue;

                        historyItems.add(new PlantCapture(
                                docId,
                                imageUrl,
                                role != null ? role : "Hiker",
                                commonName,
                                dateTime,
                                scientificName,
                                description,
                                confidence,
                                timestamp
                        ));
                    }
                    historyAdapter.notifyDataSetChanged();
                    updatePlantStats(historyItems.size());
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to load plant history: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    //update the number of identified plants in the user profile
    private void updatePlantStats(int count) {
        plantCounterText.setText("You've identified " + count + " plants!");

        String rank;
        int progress;
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
            if (stageCount <= 0) progress = 0;
            else if (stageCount < 4) progress = (int) Math.round((stageCount / 4.0) * 100.0);
            else progress = 100;
        }

        plantRankingText.setText(rank);
        rankProgressBar.setProgress(progress);
    }

    //clears the user's history of plant captures
    private void clearHistory() {
        if (historyItems.isEmpty()) {
            Toast.makeText(this, "No history to clear", Toast.LENGTH_SHORT).show();
            return;
        }

        for (PlantCapture item : historyItems) {
            db.collection("users")
                    .document(currentUid)
                    .collection("captures")
                    .document(item.docId)
                    .delete();
        }
        historyItems.clear();
        historyAdapter.notifyDataSetChanged();
        updatePlantStats(0);
        Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
    }

    //model for plant capture in profile history list
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

    //adapter for the capture history list
    private class HistoryAdapter extends ArrayAdapter<PlantCapture> {
        private final LayoutInflater inflater = LayoutInflater.from(SettingsActivity.this);
        private final SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

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
                        ? item.commonName : "Unknown Plant";
                String role = (item.role != null && !item.role.isEmpty())
                        ? item.role : "Unknown Role";

                titleTv.setText(name + " · " + role);
                dateTv.setText(formatDate(item));

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
                    intent.putExtra("allowDelete", true);
                    startActivity(intent);
                });

            }
            return row;
        }

        //format date
        private String formatDate(PlantCapture item) {
            if (item.timestamp > 0L) {
                return sdf.format(new Date(item.timestamp));
            }
            if (item.dateTime != null && !item.dateTime.isEmpty()) {
                return item.dateTime;
            }
            return "";
        }
    }

    //friend list item model
    private static class FriendItem {
        final String friendUid;
        final String friendUsername;

        FriendItem(String friendUid, String friendUsername) {
            this.friendUid = friendUid;
            this.friendUsername = friendUsername;
        }
    }

    //load all friends from the users collection
    private void loadFriends() {
        db.collection("users")
                .document(currentUid)
                .collection(SUB_FRIENDS)
                .get()
                .addOnSuccessListener(qs -> {
                    friendItems.clear();
                    for (QueryDocumentSnapshot doc : qs) {
                        String friendUid = doc.getString("friendUid");
                        if (friendUid == null || friendUid.isEmpty())
                            friendUid = doc.getId();

                        String friendUsername = doc.getString("friendUsername");
                        if (friendUsername == null) friendUsername = friendUid;

                        if (friendUid == null || friendUid.isEmpty()) continue;
                        friendItems.add(new FriendItem(friendUid, friendUsername));
                    }
                    friendAdapter.notifyDataSetChanged();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to load friends: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    //adapter to display friends as buttons, opens a friends profile
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

    //model for friend request
    private static class FriendRequestItem {
        final String otherUid;
        final String otherUsername;

        FriendRequestItem(String otherUid, String otherUsername) {
            this.otherUid = otherUid;
            this.otherUsername = otherUsername;
        }
    }

    //open the friend dialog to send or receive requests
    private void openFriendsDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_friends, null, false);

        ListView incomingListView = dialogView.findViewById(R.id.incomingListView);
        ListView outgoingListView = dialogView.findViewById(R.id.outgoingListView);
        Button openSendRequestBtn = dialogView.findViewById(R.id.buttonOpenSendRequest);

        incomingAdapter = new IncomingAdapter(incomingRequests);
        outgoingAdapter = new OutgoingAdapter(outgoingRequests);
        incomingListView.setAdapter(incomingAdapter);
        outgoingListView.setAdapter(outgoingAdapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        openSendRequestBtn.setOnClickListener(v -> openSendRequestDialog(dialog));

        dialog.show();

        loadFriendRequests(() -> {
            incomingAdapter.notifyDataSetChanged();
            outgoingAdapter.notifyDataSetChanged();
            updateFriendRequestBadge();
        });
    }

    //open the dialog that allows the user to send a friend request
    private void openSendRequestDialog(AlertDialog parentDialog) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.dialog_send_friend_request, null, false);

        TextView errorTv = view.findViewById(R.id.errorText);
        TextView usernameInput = view.findViewById(R.id.editTextFriendUsername);
        Button sendBtn = view.findViewById(R.id.buttonSendRequest);
        Button cancelBtn = view.findViewById(R.id.buttonCancelSend);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        sendBtn.setOnClickListener(v -> {
            String targetName = usernameInput.getText().toString().trim();
            if (targetName.isEmpty()) {
                errorTv.setText("Enter a username");
                errorTv.setVisibility(View.VISIBLE);
                return;
            }
            if (currentUsername != null &&
                    targetName.equalsIgnoreCase(currentUsername)) {
                errorTv.setText("You cannot send a request to yourself");
                errorTv.setVisibility(View.VISIBLE);
                return;
            }

            sendFriendRequest(targetName,
                    () -> {
                        dialog.dismiss();
                        loadFriendRequests(() -> {
                            incomingAdapter.notifyDataSetChanged();
                            outgoingAdapter.notifyDataSetChanged();
                            updateFriendRequestBadge();
                        });
                    },
                    msg -> {
                        errorTv.setText(msg);
                        errorTv.setVisibility(View.VISIBLE);
                    });
        });

        dialog.show();
    }

    //load the pending friend requests
    private void loadFriendRequests(Runnable onDone) {
        incomingRequests.clear();
        outgoingRequests.clear();

        db.collection("users")
                .document(currentUid)
                .collection(SUB_REQ_IN)
                .get()
                .addOnSuccessListener(qs -> {
                    for (QueryDocumentSnapshot doc : qs) {
                        String fromUid = doc.getString("fromUid");
                        String fromUsername = doc.getString("fromUsername");
                        if (fromUid == null || fromUid.isEmpty()) continue;
                        incomingRequests.add(new FriendRequestItem(
                                fromUid,
                                fromUsername != null ? fromUsername : fromUid
                        ));
                    }
                    db.collection("users")
                            .document(currentUid)
                            .collection(SUB_REQ_OUT)
                            .get()
                            .addOnSuccessListener(qsOut -> {
                                for (QueryDocumentSnapshot doc : qsOut) {
                                    String toUid = doc.getString("toUid");
                                    String toUsername = doc.getString("toUsername");
                                    if (toUid == null || toUid.isEmpty()) continue;
                                    outgoingRequests.add(new FriendRequestItem(
                                            toUid,
                                            toUsername != null ? toUsername : toUid
                                    ));
                                }
                                if (onDone != null) onDone.run();
                            })
                            .addOnFailureListener(e -> {
                                Toast.makeText(this,
                                        "Failed to load outgoing requests: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                                if (onDone != null) onDone.run();
                            });

                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this,
                            "Failed to load incoming requests: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    if (onDone != null) onDone.run();
                });
    }

    //load the number of incoming requests
    private void loadFriendRequestCounts() {
        db.collection("users")
                .document(currentUid)
                .collection(SUB_REQ_IN)
                .get()
                .addOnSuccessListener(qs -> {
                    int count = qs.size();
                    if (count <= 0) {
                        friendRequestBadge.setVisibility(View.GONE);
                    } else {
                        friendRequestBadge.setVisibility(View.VISIBLE);
                        friendRequestBadge.setText(String.valueOf(count));
                    }
                })
                .addOnFailureListener(e -> friendRequestBadge.setVisibility(View.GONE));
    }

    //update the badge based on the number of incoming requests
    private void updateFriendRequestBadge() {
        int count = incomingRequests.size();
        if (count <= 0) {
            friendRequestBadge.setVisibility(View.GONE);
        } else {
            friendRequestBadge.setVisibility(View.VISIBLE);
            friendRequestBadge.setText(String.valueOf(count));
        }
    }

    //send a friend request to the specified user
    private void sendFriendRequest(String targetUsername,
                                   Runnable onSuccess,
                                   java.util.function.Consumer<String> onError) {
        db.collection("users")
                .whereEqualTo("username", targetUsername)
                .limit(1)
                .get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        onError.accept("No user found with that username");
                        return;
                    }

                    String targetUid = qs.getDocuments().get(0).getId();
                    String targetName = qs.getDocuments().get(0).getString("username");

                    if (targetUid.equals(currentUid)) {
                        onError.accept("You cannot send a request to yourself");
                        return;
                    }

                    db.collection("users")
                            .document(currentUid)
                            .collection(SUB_FRIENDS)
                            .document(targetUid)
                            .get()
                            .addOnSuccessListener(friendDoc -> {
                                if (friendDoc.exists()) {
                                    onError.accept("You are already friends");
                                    return;
                                }

                                DocumentReference myOutRef = db.collection("users")
                                        .document(currentUid)
                                        .collection(SUB_REQ_OUT)
                                        .document(targetUid);

                                DocumentReference theirInRef = db.collection("users")
                                        .document(targetUid)
                                        .collection(SUB_REQ_IN)
                                        .document(currentUid);

                                Map<String, Object> outData = new HashMap<>();
                                outData.put("toUid", targetUid);
                                outData.put("toUsername", targetName);
                                outData.put("status", "pending");

                                Map<String, Object> inData = new HashMap<>();
                                inData.put("fromUid", currentUid);
                                inData.put("fromUsername", currentUsername);
                                inData.put("status", "pending");

                                Tasks.whenAll(
                                                myOutRef.set(outData),
                                                theirInRef.set(inData))
                                        .addOnSuccessListener(aVoid -> {
                                            Toast.makeText(this, "Request sent", Toast.LENGTH_SHORT).show();
                                            if (onSuccess != null) onSuccess.run();
                                        })
                                        .addOnFailureListener(e ->
                                                onError.accept("Failed to send: " + e.getMessage()));
                            })
                            .addOnFailureListener(e ->
                                    onError.accept("Error checking friends: " + e.getMessage()));
                })
                .addOnFailureListener(e ->
                        onError.accept("Failed to search user: " + e.getMessage()));
    }


    //adapter for incoming friend request in the dialog
    private class IncomingAdapter extends ArrayAdapter<FriendRequestItem> {
        IncomingAdapter(List<FriendRequestItem> items) {
            super(SettingsActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(SettingsActivity.this)
                        .inflate(R.layout.item_incoming_request, parent, false);
            }

            TextView nameTv = row.findViewById(R.id.requestUsername);
            Button acceptBtn = row.findViewById(R.id.buttonAccept);
            Button denyBtn   = row.findViewById(R.id.buttonDeny);

            FriendRequestItem item = getItem(position);
            if (item != null) {
                nameTv.setText(item.otherUsername);

                acceptBtn.setOnClickListener(v -> acceptRequest(item));
                denyBtn.setOnClickListener(v -> denyRequest(item));
            }

            return row;
        }
    }

    //adapter for outgoing requests in the friends dialog
    private class OutgoingAdapter extends ArrayAdapter<FriendRequestItem> {
        OutgoingAdapter(List<FriendRequestItem> items) {
            super(SettingsActivity.this, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(SettingsActivity.this)
                        .inflate(R.layout.item_outgoing_request, parent, false);
            }

            TextView nameTv = row.findViewById(R.id.requestUsername);
            Button cancelBtn = row.findViewById(R.id.buttonCancelRequest);

            FriendRequestItem item = getItem(position);
            if (item != null) {
                nameTv.setText(item.otherUsername);
                cancelBtn.setOnClickListener(v -> cancelOutgoingRequest(item));
            }

            return row;
        }
    }

    //accept an incoming request
    private void acceptRequest(FriendRequestItem item) {
        String otherUid = item.otherUid;
        String otherName = item.otherUsername;

        Map<String, Object> meFriend = new HashMap<>();
        meFriend.put("friendUid", otherUid);
        meFriend.put("friendUsername", otherName);

        Map<String, Object> themFriend = new HashMap<>();
        themFriend.put("friendUid", currentUid);
        themFriend.put("friendUsername", currentUsername);

        DocumentReference meFriendRef = db.collection("users")
                .document(currentUid)
                .collection(SUB_FRIENDS)
                .document(otherUid);

        DocumentReference themFriendRef = db.collection("users")
                .document(otherUid)
                .collection(SUB_FRIENDS)
                .document(currentUid);

        DocumentReference myIncomingRef = db.collection("users")
                .document(currentUid)
                .collection(SUB_REQ_IN)
                .document(otherUid);

        DocumentReference theirOutgoingRef = db.collection("users")
                .document(otherUid)
                .collection(SUB_REQ_OUT)
                .document(currentUid);

        Tasks.whenAll(
                        meFriendRef.set(meFriend),
                        themFriendRef.set(themFriend),
                        myIncomingRef.delete(),
                        theirOutgoingRef.delete())
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Friend added", Toast.LENGTH_SHORT).show();

                    incomingRequests.remove(item);
                    incomingAdapter.notifyDataSetChanged();
                    updateFriendRequestBadge();
                    loadFriends();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to accept: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    //deny a friend request
    private void denyRequest(FriendRequestItem item) {
        String otherUid = item.otherUid;

        DocumentReference myIncomingRef = db.collection("users")
                .document(currentUid)
                .collection(SUB_REQ_IN)
                .document(otherUid);

        DocumentReference theirOutgoingRef = db.collection("users")
                .document(otherUid)
                .collection(SUB_REQ_OUT)
                .document(currentUid);

        Tasks.whenAll(
                        myIncomingRef.delete(),
                        theirOutgoingRef.delete())
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Request denied", Toast.LENGTH_SHORT).show();
                    incomingRequests.remove(item);
                    incomingAdapter.notifyDataSetChanged();
                    updateFriendRequestBadge();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to deny: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    //cancel a sent friend request
    private void cancelOutgoingRequest(FriendRequestItem item) {
        String otherUid = item.otherUid;

        DocumentReference myOutgoingRef = db.collection("users")
                .document(currentUid)
                .collection(SUB_REQ_OUT)
                .document(otherUid);

        DocumentReference theirIncomingRef = db.collection("users")
                .document(otherUid)
                .collection(SUB_REQ_IN)
                .document(currentUid);

        Tasks.whenAll(
                        myOutgoingRef.delete(),
                        theirIncomingRef.delete())
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Request cancelled", Toast.LENGTH_SHORT).show();
                    outgoingRequests.remove(item);
                    outgoingAdapter.notifyDataSetChanged();
                    updateFriendRequestBadge();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to cancel: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }
}

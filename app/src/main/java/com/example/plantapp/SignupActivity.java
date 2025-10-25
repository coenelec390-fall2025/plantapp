package com.example.plantapp;

import static android.content.ContentValues.TAG;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class SignupActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private EditText etUsername;
    private EditText etEmail;
    private EditText etPassword;
    private EditText etConfirmPassword;
    private Button btnCreateAccount;
    private Button btnBackToLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_signup);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        // Bind views
        etUsername        = findViewById(R.id.editTextUsername);
        etEmail           = findViewById(R.id.editTextEmail);
        etPassword        = findViewById(R.id.editTextPassword);
        etConfirmPassword = findViewById(R.id.editTextConfirmPassword);
        btnCreateAccount  = findViewById(R.id.buttonCreateAccount);
        btnBackToLogin    = findViewById(R.id.buttonBackToLogin);

        btnCreateAccount.setOnClickListener(v -> attemptSignup());
        btnBackToLogin.setOnClickListener(v -> startActivity(new Intent(SignupActivity.this, LoginActivity.class)));
    }

    private void attemptSignup() {
        String username = etUsername.getText().toString().trim();
        String email    = etEmail.getText().toString().trim();
        String pass     = etPassword.getText().toString();
        String confirm  = etConfirmPassword.getText().toString();

        // Validation
        if (TextUtils.isEmpty(username)) {
            etUsername.setError("Username required");
            etUsername.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(email)) {
            etEmail.setError("Email required");
            etEmail.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Enter a valid email");
            etEmail.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(pass)) {
            etPassword.setError("Password required");
            etPassword.requestFocus();
            return;
        }
        if (pass.length() < 6) {
            etPassword.setError("At least 6 characters");
            etPassword.requestFocus();
            return;
        }
        if (!pass.equals(confirm)) {
            etConfirmPassword.setError("Passwords do not match");
            etConfirmPassword.requestFocus();
            return;
        }

        setUiLoading(true);

        mAuth.createUserWithEmailAndPassword(email, pass)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user == null) {
                                setUiLoading(false);
                                Toast.makeText(SignupActivity.this, "User is null after signup.", Toast.LENGTH_LONG).show();
                                return;
                            }

                            UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                                    .setDisplayName(username)
                                    .build();
                            user.updateProfile(profile);

                            saveUsernameToFirestore(user.getUid(), username, email);
                        } else {
                            setUiLoading(false);
                            Exception e = task.getException();
                            if (e instanceof FirebaseAuthUserCollisionException) {
                                Toast.makeText(SignupActivity.this, "Email already in use.", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(SignupActivity.this,
                                        (e != null ? e.getMessage() : "Authentication failed."),
                                        Toast.LENGTH_LONG).show();
                            }
                            Log.w(TAG, "createUserWithEmail:failure", e);
                        }
                    }
                });
    }

    private void setUiLoading(boolean loading) {
        btnCreateAccount.setEnabled(!loading);
        btnCreateAccount.setAlpha(loading ? 0.6f : 1f);
    }

    private void saveUsernameToFirestore(String uid, String username, String email) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("username", username);
        userData.put("email", email);
        userData.put("createdAt", FieldValue.serverTimestamp());

        db.collection("users")
                .document(uid)
                .set(userData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "User data saved successfully!");
                    Toast.makeText(SignupActivity.this, "Account created successfully!", Toast.LENGTH_SHORT).show();

                    // TODO: Navigate to MainActivity (user is already signed in)
                    // startActivity(new Intent(SignupActivity.this, MainActivity.class));
                    // finish();

                    setUiLoading(false);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Error saving user data", e);
                    Toast.makeText(SignupActivity.this, "Failed to save user info: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setUiLoading(false);
                });
    }

    @Override
    public void onStart() {
        super.onStart();
        // No reload needed; user logs in immediately on creation.
    }
}

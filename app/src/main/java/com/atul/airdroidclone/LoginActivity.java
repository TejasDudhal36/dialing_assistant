package com.dialingassistant.agent;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

public class LoginActivity extends AppCompatActivity {

    private EditText etEmail, etPassword;
    private Button btnLogin;
    private ProgressBar progressBar;
    private TextView tvError;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

        // Already signed in → go straight to MainActivity
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            proceedToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        progressBar = findViewById(R.id.progress_bar);
        tvError = findViewById(R.id.tv_error);

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (TextUtils.isEmpty(email)) {
            etEmail.setError("Email is required");
            etEmail.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(password)) {
            etPassword.setError("Password is required");
            etPassword.requestFocus();
            return;
        }

        setLoading(true);
        tvError.setVisibility(View.GONE);

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    String uid = authResult.getUser().getUid();
                    // Persist UID so CallReceiver can send push notifications
                    // even when the app is killed and Firebase Auth hasn't loaded yet.
                    getSharedPreferences("agent_prefs", MODE_PRIVATE)
                            .edit().putString("uid", uid).apply();
                    // Check if account is disabled in RTDB
                    FirebaseDatabase.getInstance()
                            .getReference("users/" + uid)
                            .get()
                            .addOnSuccessListener(snap -> {
                                Object disabledObj = snap.child("disabled").getValue();
                                if (snap.exists() && Boolean.TRUE.equals(disabledObj instanceof Boolean ? disabledObj : null)) {
                                    mAuth.signOut();
                                    setLoading(false);
                                    showError("Your account has been disabled. Contact your administrator.");
                                    return;
                                }
                                proceedToMain();
                            })
                            .addOnFailureListener(e -> {
                                mAuth.signOut();
                                setLoading(false);
                                showError("Could not verify account status. Check your connection and try again.");
                            });
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    String msg = e.getMessage() != null ? e.getMessage() : "Login failed";
                    android.util.Log.e("LoginActivity", "Auth error: " + msg, e);
                    if (msg.contains("password") || msg.contains("credential") || msg.contains("INVALID_LOGIN")) {
                        showError("Incorrect email or password.");
                    } else if (msg.contains("network") || msg.contains("NETWORK")) {
                        showError("Network error. Check your connection.");
                    } else if (msg.contains("blocked") || msg.contains("TOO_MANY")) {
                        showError("Too many attempts. Try again later.");
                    } else {
                        showError("Login failed: " + msg);
                    }
                });
    }

    private void proceedToMain() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}

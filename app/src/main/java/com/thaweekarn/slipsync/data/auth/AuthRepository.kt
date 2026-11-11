package com.thaweekarn.slipsync.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.thaweekarn.slipsync.R
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AuthRepository(
    private val context: Context,
    private val preferencesRepository: SlipPreferencesRepository,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
        }
    }

    val isUserSignedIn: Boolean
        get() = auth.currentUser != null

    /**
     * Signs in with Google using Jetpack Credential Manager and authenticates against Firebase.
     * On successful login, syncs user profile and Google Drive auto-connection settings to Firestore.
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<FirebaseUser> = withContext(Dispatchers.IO) {
        try {
            val serverClientId = context.getString(R.string.default_web_client_id)
            val credentialManager = CredentialManager.create(activityContext)

            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = serverClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val result = credentialManager.getCredential(
                context = activityContext,
                request = request
            )

            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val authCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val user = authResult.user ?: throw IllegalStateException("Firebase user is null after sign in")

                _currentUser.value = user
                preferencesRepository.setGoogleDriveAutoConnected(true)

                // Sync profile and Drive connection record to Firestore
                syncUserToFirestore(user)

                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Unsupported credential type returned"))
            }
        } catch (cancellation: GetCredentialCancellationException) {
            Log.d("AuthRepository", "Google Sign-In was cancelled by user: ${cancellation.message}")
            Result.failure(cancellation)
        } catch (e: Exception) {
            Log.e("AuthRepository", "Google Sign-In failed", e)
            Result.failure(e)
        }
    }

    /**
     * Syncs user profile and Drive auto-connect preferences to Firestore user document.
     */
    private suspend fun syncUserToFirestore(user: FirebaseUser) {
        try {
            val databaseId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), databaseId)
            val userMap = mapOf(
                "userId" to user.uid,
                "email" to (user.email ?: ""),
                "displayName" to (user.displayName ?: ""),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "driveConnected" to true,
                "driveFolderName" to "Slips/Inbox",
                "autoUpload" to true,
                "lastLoginAt" to System.currentTimeMillis()
            )
            db.collection("users").document(user.uid)
                .set(userMap, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Failed to sync user profile to Firestore (proceeding locally): ${e.message}")
        }
    }

    suspend fun signOut() {
        auth.signOut()
        _currentUser.value = null
        preferencesRepository.setGoogleDriveAutoConnected(false)
    }
}

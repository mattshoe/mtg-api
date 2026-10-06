package org.mattshoe.mtg.android

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Signing in with Google, on the phone.
 *
 * Credential Manager rather than a browser round trip: the sheet
 * comes up over the app, Google hands back an ID token, and the
 * Worker verifies that token exactly as it verifies the one the
 * website's redirect produces. There is no code to exchange and
 * nothing to come back from, which is also why the phone has no use
 * for the callback the website needs.
 *
 * The **web** client id is what goes in as `serverClientId`, not
 * either of the Android ones. That is the part everybody gets wrong:
 * the Android clients exist so Google will talk to this package at
 * all — they are matched on the signing certificate — and the token
 * that comes out is audienced to the web client, which is the one
 * the server checks against.
 */
object GoogleSignIn {

    /**
     * The web client, from the same Google project as the two Android
     * ones. Public by nature: it travels in every authorization URL
     * the website emits. The secret that goes with it is a Worker
     * secret and is not here.
     */
    const val SERVER_CLIENT_ID =
        "353260536522-0m3u6otf1a9tf0k17a489jetodmnvom9.apps.googleusercontent.com"

    /**
     * Put the sheet up and come back with a Google ID token.
     *
     * Throws whatever Credential Manager throws, including the
     * cancellation that is a person deciding not to after all — the
     * caller tells those apart, because one deserves a message and
     * the other deserves silence.
     */
    suspend fun idToken(context: Context): String {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(SERVER_CLIENT_ID).build(),
            )
            .build()
        val response = CredentialManager.create(context).getCredential(context, request)
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        error("Google returned a credential this app does not understand: ${credential.type}")
    }
}

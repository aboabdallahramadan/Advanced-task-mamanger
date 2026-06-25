package net.qmindtech.tmap.data.auth

data class StoredProfile(val userId: String, val email: String, val timeZoneId: String)

interface TokenStore {
    suspend fun saveRefreshToken(token: String)
    suspend fun readRefreshToken(): String?
    /** Clears all stored tokens AND the persisted profile. */
    suspend fun clear()
    var accessToken: String?
    suspend fun saveProfile(userId: String, email: String, timeZoneId: String)
    suspend fun readProfile(): StoredProfile?
}

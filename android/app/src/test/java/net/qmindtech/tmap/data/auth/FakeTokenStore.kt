package net.qmindtech.tmap.data.auth

/** In-memory TokenStore for tests; real crypto lives in KeystoreTokenStore (unavailable under Robolectric). */
class FakeTokenStore : TokenStore {
    private var refresh: String? = null
    private var storedProfile: StoredProfile? = null
    var clearCalls: Int = 0
        private set

    override var accessToken: String? = null

    override suspend fun saveRefreshToken(token: String) {
        refresh = token
    }

    override suspend fun readRefreshToken(): String? = refresh

    override suspend fun saveProfile(userId: String, email: String, timeZoneId: String) {
        storedProfile = StoredProfile(userId, email, timeZoneId)
    }

    override suspend fun readProfile(): StoredProfile? = storedProfile

    override suspend fun clear() {
        clearCalls++
        refresh = null
        accessToken = null
        storedProfile = null
    }
}

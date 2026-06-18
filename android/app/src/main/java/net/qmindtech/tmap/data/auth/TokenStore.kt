package net.qmindtech.tmap.data.auth

interface TokenStore {
    suspend fun saveRefreshToken(token: String)
    suspend fun readRefreshToken(): String?
    suspend fun clear()
    var accessToken: String?
}

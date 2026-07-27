package com.abdullah.visionbridge.domain.repository

interface ApiKeyStore {
    suspend fun save(apiKey: String)
    suspend fun get(): String?
    suspend fun hasKey(): Boolean
    suspend fun clear()
}

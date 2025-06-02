package com.example.sinkanddestroybattleship.data.repository

import com.example.sinkanddestroybattleship.data.models.*
import com.example.sinkanddestroybattleship.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BattleshipRepository {
    private val api = NetworkModule.battleshipApi

    suspend fun ping(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = api.ping()
            if (response.isSuccessful) {
                Result.success(response.body()?.ping == true)
            } else {
                Result.failure(Exception(parseError(response.errorBody()?.string())))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinGame(player: String, gameKey: String, ships: List<Ship>): Result<EnemyFireResponse> = 
        withContext(Dispatchers.IO) {
            try {
                val request = JoinGameRequest(player, gameKey, ships)
                val response = api.joinGame(request)
                if (response.isSuccessful) {
                    response.body()?.let {
                        Result.success(it)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception(parseError(response.errorBody()?.string())))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun fire(player: String, gameKey: String, x: Int, y: Int): Result<FireResponse> = 
        withContext(Dispatchers.IO) {
            try {
                val request = FireRequest(player, gameKey, x, y)
                val response = api.fire(request)
                if (response.isSuccessful) {
                    response.body()?.let {
                        Result.success(it)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception(parseError(response.errorBody()?.string())))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun enemyFire(player: String, gameKey: String): Result<EnemyFireResponse> = 
        withContext(Dispatchers.IO) {
            try {
                val request = EnemyFireRequest(player, gameKey)
                val response = api.enemyFire(request)
                if (response.isSuccessful) {
                    response.body()?.let {
                        Result.success(it)
                    } ?: Result.failure(Exception("Empty response"))
                } else {
                    Result.failure(Exception(parseError(response.errorBody()?.string())))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseError(errorBody: String?): String {
        return try {
            errorBody?.let {
                if (it.contains("Error")) {
                    NetworkModule.moshi.adapter(ErrorResponse::class.java)
                        .fromJson(it)?.Error
                } else {
                    it
                }
            } ?: "Unknown error"
        } catch (e: Exception) {
            errorBody ?: "Unknown error"
        }
    }
} 
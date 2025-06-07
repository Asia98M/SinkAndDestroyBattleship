package com.example.sinkanddestroybattleship.data.repository

import android.util.Log
import com.example.sinkanddestroybattleship.data.models.*
import com.example.sinkanddestroybattleship.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BattleshipRepository {
    private val api = NetworkModule.battleshipApi
    private val TAG = "BattleshipRepository"

    companion object {
        // Error messages that can come from server
        const val ERROR_GAME_EXISTS = "Game already exists"
        const val ERROR_INVALID_GAME = "Invalid game"
        const val ERROR_GAME_NOT_FOUND = "Game not found"
        const val ERROR_NOT_YOUR_TURN = "Not your turn"
        const val ERROR_INVALID_COORDINATES = "Invalid coordinates"
        const val ERROR_ID_TOO_SHORT = "ID too short"
        const val ERROR_INVALID_SHIPS = "Invalid ship placement"
    }

    suspend fun ping(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = api.ping()
            Log.d(TAG, "Ping response: ${response.raw()}")
            if (response.isSuccessful) {
                Result.success(response.body()?.ping == true)
            } else {
                val error = parseError(response.errorBody()?.string())
                Log.e(TAG, "Ping error: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping exception: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun joinGame(player: String, gameKey: String, ships: List<Ship>): Result<EnemyFireResponse> = 
        withContext(Dispatchers.IO) {
            try {
                val request = JoinGameRequest(ships, gameKey, player)
                Log.d(TAG, "Join game request: $request")
                val response = api.joinGame(request)
                Log.d(TAG, "Join game response: ${response.raw()}")
                
                if (response.isSuccessful) {
                    response.body()?.let {
                        Log.d(TAG, "Join game success: $it")
                        Result.success(it)
                    } ?: run {
                        Log.e(TAG, "Join game empty response")
                        Result.failure(Exception("Server returned empty response"))
                    }
                } else {
                    val error = parseError(response.errorBody()?.string())
                    Log.e(TAG, "Join game error: $error")
                    when {
                        error.contains("too short") -> Result.failure(Exception(ERROR_ID_TOO_SHORT))
                        error.contains("already exists") -> Result.failure(Exception(ERROR_GAME_EXISTS))
                        error.contains("Invalid ship") -> Result.failure(Exception(ERROR_INVALID_SHIPS))
                        else -> Result.failure(Exception(error))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Join game exception: ${e.message}")
                Result.failure(e)
            }
        }

    suspend fun fire(player: String, gameKey: String, x: Int, y: Int): Result<FireResponse> = 
        withContext(Dispatchers.IO) {
            try {
                // Validate coordinates before sending to server
                if (x !in 0..9 || y !in 0..9) {
                    return@withContext Result.failure(Exception(ERROR_INVALID_COORDINATES))
                }

                val request = FireRequest(x = x, y = y, gamekey = gameKey, player = player)
                Log.d(TAG, "Fire request: $request")
                val response = api.fire(request)
                Log.d(TAG, "Fire response: ${response.raw()}")
                
                if (response.isSuccessful) {
                    response.body()?.let {
                        Log.d(TAG, "Fire success: $it")
                        Result.success(it)
                    } ?: run {
                        Log.e(TAG, "Fire empty response")
                        Result.failure(Exception("Server returned empty response"))
                    }
                } else {
                    val error = parseError(response.errorBody()?.string())
                    Log.e(TAG, "Fire error: $error")
                    when {
                        error.contains("not found") -> Result.failure(Exception(ERROR_GAME_NOT_FOUND))
                        error.contains("not your turn") -> Result.failure(Exception(ERROR_NOT_YOUR_TURN))
                        error.contains("Invalid coordinates") -> Result.failure(Exception(ERROR_INVALID_COORDINATES))
                        else -> Result.failure(Exception(error))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fire exception: ${e.message}")
                Result.failure(e)
            }
        }

    suspend fun enemyFire(player: String, gameKey: String): Result<EnemyFireResponse> = 
        withContext(Dispatchers.IO) {
            try {
                val request = EnemyFireRequest(gamekey = gameKey, player = player)
                Log.d(TAG, "Enemy fire request: $request")
                val response = api.enemyFire(request)
                Log.d(TAG, "Enemy fire response: ${response.raw()}")
                
                if (response.isSuccessful) {
                    response.body()?.let {
                        Log.d(TAG, "Enemy fire success: $it")
                        Result.success(it)
                    } ?: run {
                        Log.e(TAG, "Enemy fire empty response")
                        Result.failure(Exception("Server returned empty response"))
                    }
                } else {
                    val error = parseError(response.errorBody()?.string())
                    Log.e(TAG, "Enemy fire error: $error")
                    when {
                        error.contains("not found") -> Result.failure(Exception(ERROR_GAME_NOT_FOUND))
                        error.contains("Invalid game") -> Result.failure(Exception(ERROR_INVALID_GAME))
                        else -> Result.failure(Exception(error))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Enemy fire exception: ${e.message}")
                Result.failure(e)
            }
        }

    private fun parseError(errorBody: String?): String {
        return try {
            errorBody?.let {
                if (it.contains("Error")) {
                    NetworkModule.moshi.adapter(ErrorResponse::class.java)
                        .fromJson(it)?.Error ?: "Unknown server error"
                } else {
                    it
                }
            } ?: "Server returned no error details"
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server response: ${e.message}")
            errorBody ?: "Failed to parse server response"
        }
    }
} 
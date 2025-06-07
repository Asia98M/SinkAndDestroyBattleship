package com.example.sinkanddestroybattleship.data.repository

import android.util.Log
import com.example.sinkanddestroybattleship.data.models.*
import com.example.sinkanddestroybattleship.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

class BattleshipRepository {
    private val api = NetworkModule.battleshipApi
    private val TAG = "BattleshipRepository"

    private suspend fun <T> handleApiResponse(
        apiCall: suspend () -> Response<T>,
        errorContext: String
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val response = apiCall()
            Log.d(TAG, "$errorContext response: ${response.raw()}")
            
            when (response.code()) {
                200 -> {
                    // Even with 200, we need to check for error in body
                    response.body()?.let { body ->
                        // If the body is EnemyFireResponse or FireResponse, check for error field
                        when (body) {
                            is EnemyFireResponse -> {
                                if (body.error != null) {
                                    Log.e(TAG, "$errorContext error in body: ${body.error}")
                                    Result.failure(Exception(body.error))
                                } else {
                                    Result.success(body)
                                }
                            }
                            else -> Result.success(body)
                        }
                    } ?: run {
                        Log.e(TAG, "$errorContext empty response body")
                        Result.failure(Exception("Server returned empty response"))
                    }
                }
                418 -> {
                    val error = "Invalid API endpoint or request format"
                    Log.e(TAG, "$errorContext error (418): $error")
                    Result.failure(Exception(error))
                }
                else -> {
                    val errorBody = parseError(response.errorBody()?.string())
                    Log.e(TAG, "$errorContext error (${response.code()}): $errorBody")
                    Result.failure(Exception(errorBody))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$errorContext exception: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun ping(): Result<Boolean> = 
        handleApiResponse(
            apiCall = { api.ping() },
            errorContext = "Ping"
        ).map { it.ping == true }

    suspend fun joinGame(player: String, gameKey: String, ships: List<Ship>): Result<EnemyFireResponse> =
        handleApiResponse(
            apiCall = { api.joinGame(JoinGameRequest(player, gameKey, ships)) },
            errorContext = "Join game"
        )

    suspend fun fire(player: String, gameKey: String, x: Int, y: Int): Result<FireResponse> =
        handleApiResponse(
            apiCall = { api.fire(FireRequest(player, gameKey, x, y)) },
            errorContext = "Fire"
        )

    suspend fun enemyFire(player: String, gameKey: String): Result<EnemyFireResponse> =
        handleApiResponse(
            apiCall = { api.enemyFire(EnemyFireRequest(player, gameKey)) },
            errorContext = "Enemy fire"
        )

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
package com.example.sinkanddestroybattleship.data.api

import com.example.sinkanddestroybattleship.data.models.*
import retrofit2.Response
import retrofit2.http.*

interface BattleshipApi {
    @GET("ping")
    suspend fun ping(): Response<PingResponse>

    @POST("game/join")
    suspend fun joinGame(
        @Body request: JoinGameRequest
    ): Response<EnemyFireResponse>

    @POST("game/fire")
    suspend fun fire(
        @Body request: FireRequest
    ): Response<FireResponse>

    @POST("game/enemyFire")
    suspend fun enemyFire(
        @Body request: EnemyFireRequest
    ): Response<EnemyFireResponse>
} 
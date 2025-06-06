package com.example.sinkanddestroybattleship.data.models

data class GameState(
    val gameId: String,
    val player1: String,
    val player2: String?,
    val currentPlayer: String,
    val winner: String?,
    val status: GameStatus,
    val board1: Board,
    val board2: Board
)

data class Board(
    val ships: List<Ship>,
    val shots: List<Shot>
)

data class Ship(
    val ship: String,
    val x: Int,
    val y: Int,
    val orientation: String
)

data class Shot(
    val position: Position,
    val hit: Boolean,
)

data class Position(
    val x: Int,
    val y: Int
)

enum class Orientation {
    HORIZONTAL,
    VERTICAL
}

enum class GameStatus {
    WAITING_FOR_PLAYER,
    PLACING_SHIPS,
    IN_PROGRESS,
    FINISHED
}

data class GameCreationResponse(
    val gameId: String,
    val player1: String
)

data class JoinGameRequest(
    val player: String,
    val gamekey: String,
    val ships: List<Ship>
)

data class PlaceShipRequest(
    val playerId: String,
    val position: Position,
    val size: Int,
    val orientation: Orientation
)

data class ShootRequest(
    val playerId: String,
    val position: Position
)

data class GameResponse(
    val success: Boolean,
    val message: String?,
    val gameState: GameState?
)

data class FireRequest(
    val player: String,
    val gamekey: String,
    val x: Int,
    val y: Int
)

data class FireResponse(
    val hit: Boolean,
    val shipsSunk: List<String>
)

data class EnemyFireRequest(
    val player: String,
    val gamekey: String
)

data class EnemyFireResponse(
    val x: Int?,
    val y: Int?,
    val hit: Boolean? = null,
    val gameover: Boolean = false,
    val error: String? = null
)

data class PingResponse(
    val ping: Boolean
)

data class ErrorResponse(
    val Error: String
)

enum class ShipType(val size: Int) {
    CARRIER(5),
    BATTLESHIP(4),
    DESTROYER(3),
    SUBMARINE(3),
    PATROL_BOAT(2)
} 
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
    val orientation: String,
    val x: Int,
    val ship: String,
    val y: Int,

)

data class Shot(
    val position: Position,
    val hit: Boolean? = null
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
    val ships: List<Ship>,
    val gamekey: String,
    val player: String,


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
    val success: Boolean? = null,
    val message: String? = null,
    val gameState: GameState? = null
)

data class FireRequest(
    val player: String,
    val gamekey: String,
    val x: Int,
    val y: Int
)

data class FireResponse(
    val hit: Boolean? = null,
    val shipsSunk: List<String> = emptyList()
)

data class EnemyFireRequest(
    val player: String,
    val gamekey: String
)

data class EnemyFireResponse(
    val x: Int? = null,
    val y: Int? = null,
    val hit: Boolean? = null,
    val gameover: Boolean? = false,
    val error: String? = null
)

data class PingResponse(
    val ping: Boolean? = null
)

data class ErrorResponse(
    val Error: String? = null
)

enum class ShipType(val size: Int) {
    Carrier(5),
    Battleship(4),
    Destroyer(3),
    Submarine(3),
    PatrolBoat(2)
} 
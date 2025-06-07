package com.example.sinkanddestroybattleship.ui.viewmodel

import android.annotation.SuppressLint
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sinkanddestroybattleship.data.models.*
import com.example.sinkanddestroybattleship.data.repository.BattleshipRepository
import kotlinx.coroutines.*

class BattleshipViewModel : ViewModel() {
    private val repository = BattleshipRepository()

    enum class GamePhase {
        SETUP, WAITING, PLAYING, FINISHED
    }

    data class GameState(
        val phase: GamePhase = GamePhase.SETUP,
        val playerName: String? = null,
        val gameKey: String? = null,
        val playerShips: List<Ship> = emptyList(),
        val enemyShots: List<Position> = emptyList(),
        val playerHits: List<Position> = emptyList(),
        val playerMisses: List<Position> = emptyList(),
        val sunkEnemyShips: List<String> = emptyList(),
        val isMyTurn: Boolean = false,
        val isGameOver: Boolean = false
    )

    private val _gameState = MutableLiveData(GameState())
    val gameState: LiveData<GameState> = _gameState

    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isWaitingForOpponent = MutableLiveData<Boolean>(false)
    val isWaitingForOpponent: LiveData<Boolean> = _isWaitingForOpponent

    private val _bothPlayersConnected = MutableLiveData<Boolean>(false)
    val bothPlayersConnected: LiveData<Boolean> = _bothPlayersConnected

    private var gameLoopJob: Job? = null
    private var enemyFireJob: Job? = null
    private var retryCount = 0
    private val maxRetries = 3
    private val pollingDelay = 1000L
    private val longPollingTimeout = 30000L

    suspend fun ping(): Result<Boolean> = repository.ping()

    @SuppressLint("NullSafeMutableLiveData")
    fun joinGame(player: String, gameKey: String, ships: List<Ship>) {
        resetStateBeforeJoin(player, gameKey, ships) ?: return

        viewModelScope.launch {
            try {
                val request = JoinGameRequest(ships, gameKey, player)
                Log.d(TAG, "Joining game: $request")
                repository.joinGame(player, gameKey, ships).fold(
                    onSuccess = { handleJoinSuccess(it) },
                    onFailure = { handleJoinError(it.message ?: "Unknown error") }
                )
            } catch (e: Exception) {
                handleJoinError(e.message ?: "Unknown error")
            }
        }
    }

    private fun resetStateBeforeJoin(player: String, gameKey: String, ships: List<Ship>): Unit? {
        val idError = BattleshipGame().validateIds(player, gameKey)
        val shipError = BattleshipGame().validateShipPlacement(ships)

        if (idError != null) {
            _statusText.value = idError
            return null
        }

        if (shipError != null) {
            _statusText.value = shipError
            return null
        }

        retryCount = 0
        _isWaitingForOpponent.value = true
        _bothPlayersConnected.value = false
        _statusText.value = "Joining game..."

        _gameState.value = GameState(
            phase = GamePhase.WAITING,
            playerName = player,
            gameKey = gameKey,
            playerShips = ships
        )
        return Unit
    }

    private fun handleJoinError(error: String) {
        _error.value = error
        _statusText.value = "Failed to join game. Please try again."
        _isWaitingForOpponent.value = false
        _bothPlayersConnected.value = false
    }

    private fun handleJoinSuccess(response: EnemyFireResponse) {
        if (response.gameover) {
            updateGameState { it.copy(phase = GamePhase.FINISHED, isGameOver = true) }
            _statusText.value = "Game ended unexpectedly"
            return
        }
        startGameLoop()
    }

    private fun startGameLoop() {
        gameLoopJob?.cancel()
        enemyFireJob?.cancel()
        
        gameLoopJob = viewModelScope.launch {
            val state = _gameState.value ?: return@launch
            val player = state.playerName ?: return@launch
            val gameKey = state.gameKey ?: return@launch

            _statusText.value = "Waiting for game to start..."
            try {
                withTimeout(longPollingTimeout) {
                    repository.enemyFire(player, gameKey).fold(
                        onSuccess = { handleInitialGameState(it) },
                        onFailure = { handleGameLoopError(it) }
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleGameLoopError(e)
            }
        }
    }

    private fun handleInitialGameState(response: EnemyFireResponse) {
        _isWaitingForOpponent.value = false
        _bothPlayersConnected.value = true

        when {
            response.gameover -> {
                updateGameState { it.copy(phase = GamePhase.FINISHED, isGameOver = true) }
                _statusText.value = "Game ended unexpectedly"
            }
            response.x == null && response.y == null -> {
                _statusText.value = "You go first!"
                updateGameState { it.copy(phase = GamePhase.PLAYING, isMyTurn = true) }
            }
            else -> {
                _statusText.value = "Opponent starts. Processing their move..."
                updateGameState { it.copy(phase = GamePhase.PLAYING, isMyTurn = false) }
                handleEnemyShot(response)
            }
        }
        startListeningForEnemyFire()
    }

    private fun handleGameLoopError(exception: Throwable) {
        val message = exception.message ?: "Unknown error"
        _statusText.value = message
        if (++retryCount <= maxRetries) {
            viewModelScope.launch {
                delay(pollingDelay * retryCount)
                startGameLoop()
            }
        } else {
            updateGameState { it.copy(phase = GamePhase.FINISHED, isGameOver = true) }
            _error.value = "Could not start game after $maxRetries retries"
        }
    }

    private fun startListeningForEnemyFire() {
        enemyFireJob?.cancel()
        enemyFireJob = viewModelScope.launch {
            while (!gameState.value!!.isGameOver) {
                if (gameState.value!!.isMyTurn) {
                    delay(pollingDelay)
                    continue
                }

                try {
                    val state = gameState.value!!
                    val player = state.playerName ?: continue
                    val gameKey = state.gameKey ?: continue

                    _statusText.value = "Waiting for opponent's move..."
                    withTimeout(longPollingTimeout) {
                        repository.enemyFire(player, gameKey).fold(
                            onSuccess = { response ->
                                if (response.gameover) {
                                    updateGameState { it.copy(isGameOver = true, phase = GamePhase.FINISHED) }
                                    _statusText.value = "Game Over!"
                                } else if (response.x != null && response.y != null) {
                                    handleEnemyShot(response)
                                }
                            },
                            onFailure = { handleNetworkError(it) }
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    continue
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    handleNetworkError(e)
                }
                delay(pollingDelay)
            }
        }
    }

    private fun handleEnemyShot(response: EnemyFireResponse) {
        val x = response.x ?: return
        val y = response.y ?: return
        val pos = Position(x, y)
        
        val state = gameState.value!!
        val updatedShots = state.enemyShots + pos
        
        val hitShip = state.playerShips.find { ship ->
            BattleshipGame().calculateShipCells(ship).contains(pos)
        }
        
        val allSunk = state.playerShips.all { ship ->
            BattleshipGame().calculateShipCells(ship).all { updatedShots.contains(it) }
        }

        updateGameState {
            it.copy(
                enemyShots = updatedShots,
                isMyTurn = true,
                isGameOver = allSunk,
                phase = if (allSunk) GamePhase.FINISHED else GamePhase.PLAYING
            )
        }

        _statusText.value = when {
            allSunk -> "Game Over! All your ships have been sunk."
            hitShip != null -> "Enemy hit your ${hitShip.ship} at (${pos.x}, ${pos.y})! Your turn."
            else -> "Enemy missed at (${pos.x}, ${pos.y})! Your turn."
        }
    }

    fun fire(x: Int, y: Int) {
        val state = gameState.value!!
        if (!state.isMyTurn || state.phase != GamePhase.PLAYING) {
            _statusText.value = "Not your turn"
            return
        }

        val position = Position(x, y)
        if (state.playerHits.contains(position) || state.playerMisses.contains(position)) {
            _statusText.value = "Already fired here"
            return
        }

        _statusText.value = "Firing at (${x}, ${y})..."
        viewModelScope.launch {
            try {
                repository.fire(state.playerName!!, state.gameKey!!, x, y).fold(
                    onSuccess = { handleFireResponse(it, position) },
                    onFailure = { handleNetworkError(it) }
                )
            } catch (e: Exception) {
                handleNetworkError(e)
            }
        }
    }

    private fun handleFireResponse(response: FireResponse, pos: Position) {
        val state = gameState.value!!
        val hits = if (response.hit) state.playerHits + pos else state.playerHits
        val misses = if (!response.hit) state.playerMisses + pos else state.playerMisses
        val sunk = state.sunkEnemyShips + response.shipsSunk
        val gameOver = sunk.size == BattleshipGame.SHIP_TYPES.size

        updateGameState {
            it.copy(
                playerHits = hits,
                playerMisses = misses,
                sunkEnemyShips = sunk,
                isMyTurn = false,
                isGameOver = gameOver,
                phase = if (gameOver) GamePhase.FINISHED else GamePhase.PLAYING
            )
        }

        _statusText.value = when {
            gameOver -> "You won! All enemy ships sunk!"
            response.shipsSunk.isNotEmpty() -> "Hit and sunk ${response.shipsSunk.joinToString()}! Waiting for opponent..."
            response.hit -> "Hit at (${pos.x}, ${pos.y})! Waiting for opponent..."
            else -> "Miss at (${pos.x}, ${pos.y})! Waiting for opponent..."
        }

        startListeningForEnemyFire()
    }

    private fun handleNetworkError(exception: Throwable) {
        _error.value = exception.message ?: "Network error"
        if (++retryCount <= maxRetries) {
            _statusText.value = "Connection issue, retrying... (Attempt $retryCount/$maxRetries)"
            viewModelScope.launch {
                delay(pollingDelay * retryCount)
                if (gameState.value?.isMyTurn == false) startListeningForEnemyFire()
            }
        } else {
            updateGameState { it.copy(phase = GamePhase.FINISHED, isGameOver = true) }
            _statusText.value = "Connection lost after $maxRetries retries"
        }
    }

    private fun updateGameState(update: (GameState) -> GameState) {
        _gameState.value = update(_gameState.value!!)
    }

    override fun onCleared() {
        super.onCleared()
        gameLoopJob?.cancel()
        enemyFireJob?.cancel()
    }

    companion object {
        private const val TAG = "BattleshipViewModel"
    }
}

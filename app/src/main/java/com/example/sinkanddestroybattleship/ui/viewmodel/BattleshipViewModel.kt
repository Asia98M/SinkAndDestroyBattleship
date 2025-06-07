package com.example.sinkanddestroybattleship.ui.viewmodel

import android.annotation.SuppressLint
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sinkanddestroybattleship.data.models.*
import com.example.sinkanddestroybattleship.data.repository.BattleshipRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException

class BattleshipViewModel : ViewModel() {
    private val repository = BattleshipRepository()

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

    enum class GamePhase {
        SETUP,         // Initial phase, placing ships
        WAITING,       // Waiting for opponent to join
        PLAYING,       // Game in progress
        FINISHED       // Game over
    }

    private val _gameState = MutableLiveData<GameState>()
    val gameState: LiveData<GameState> = _gameState

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _playerShips = MutableLiveData<List<Ship>>()
    val playerShips: LiveData<List<Ship>> = _playerShips

    private val _enemyShots = MutableLiveData<List<Position>>()
    val enemyShots: LiveData<List<Position>> = _enemyShots

    private val _playerHits = MutableLiveData<List<Position>>()
    val playerHits: LiveData<List<Position>> = _playerHits

    private val _playerMisses = MutableLiveData<List<Position>>()
    val playerMisses: LiveData<List<Position>> = _playerMisses

    private val _isGameOver = MutableLiveData<Boolean>()
    val isGameOver: LiveData<Boolean> = _isGameOver

    private val _isMyTurn = MutableLiveData<Boolean>()
    val isMyTurn: LiveData<Boolean> = _isMyTurn

    private val _gameJoined = MutableLiveData<Boolean>()
    val gameJoined: LiveData<Boolean> = _gameJoined

    private val _sunkShips = MutableLiveData<List<String>>()
    val sunkShips: LiveData<List<String>> = _sunkShips

    private val _isWaitingForOpponent = MutableLiveData<Boolean>()
    val isWaitingForOpponent: LiveData<Boolean> = _isWaitingForOpponent

    private val _bothPlayersConnected = MutableLiveData<Boolean>()
    val bothPlayersConnected: LiveData<Boolean> = _bothPlayersConnected

    private var currentPlayer: String? = null
    private var currentGameKey: String? = null
    private var gameLoopJob: Job? = null
    private var enemyFireJob: Job? = null
    private var retryCount = 0
    private val maxRetries = 3
    private val pollingDelay = 1000L // 1 second
    private val longPollingTimeout = 30000L // 30 seconds

    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    init {
        resetGameState()
    }

    private fun resetGameState() {
        _gameState.value = GameState()
        _sunkShips.value = emptyList()
        _isWaitingForOpponent.value = false
        _bothPlayersConnected.value = false
        _statusText.value = "Place your ships to start"
    }

    suspend fun ping(): Result<Boolean> {
        return repository.ping()
    }

    @SuppressLint("NullSafeMutableLiveData")
    fun joinGame(player: String, gameKey: String, ships: List<Ship>) {
        // Reset game state before joining
        resetGameState()
        
        // Validate inputs
        val battleshipGame = BattleshipGame()
        val idError = battleshipGame.validateIds(player, gameKey)
        if (idError != null) {
            _statusText.value = idError
            return
        }

        val shipError = battleshipGame.validateShipPlacement(ships)
        if (shipError != null) {
            _statusText.value = shipError
            return
        }

        // Update game state for setup phase
        _gameState.value = GameState(
            phase = GamePhase.WAITING,
            playerName = player,
            gameKey = gameKey,
            playerShips = ships
        )
        _isWaitingForOpponent.value = true
        _statusText.value = "Joining game..."

        viewModelScope.launch {
            try {
                val request = JoinGameRequest(ships, gameKey, player)
                Log.d(TAG, "Joining game: $request")
                
                repository.joinGame(player, gameKey, ships).fold(
                    onSuccess = { response ->
                        handleJoinSuccess(response)
                    },
                    onFailure = { exception ->
                        handleJoinError(exception.message ?: "Unknown error occurred")
                    }
                )
            } catch (e: Exception) {
                handleJoinError(e.message ?: "Unknown error occurred")
            }
        }
    }

    private fun handleJoinError(error: String) {
        Log.e(TAG, "Join game error: $error")
        _error.value = when (error) {
            BattleshipRepository.ERROR_ID_TOO_SHORT -> "Player ID and Game Key must be at least 3 characters"
            BattleshipRepository.ERROR_GAME_EXISTS -> "Game already exists with this key. Try a different key."
            BattleshipRepository.ERROR_INVALID_SHIPS -> "Invalid ship placement. Please check ship positions."
            else -> "Failed to join game: $error"
        }
        currentPlayer = null
        currentGameKey = null
        _statusText.value = "Failed to join game. Please try again."
        _isWaitingForOpponent.value = false
        _bothPlayersConnected.value = false
    }

    private fun handleJoinSuccess(response: EnemyFireResponse) {
        Log.d(TAG, "Join success: $response")
        
        if (response.gameover) {
            updateGameState { it.copy(phase = GamePhase.FINISHED, isGameOver = true) }
            _statusText.value = "Game ended unexpectedly"
            return
        }

        _bothPlayersConnected.value = false
        startGameLoop()
    }

    private fun handleInitialGameState(response: EnemyFireResponse) {
        _bothPlayersConnected.value = true
        _isWaitingForOpponent.value = false
        
        when {
            response.gameover -> {
                updateGameState { it.copy(phase = GamePhase.FINISHED, isGameOver = true) }
                _statusText.value = "Game ended unexpectedly"
            }
            // First player selected by server
            response.x == null && response.y == null -> {
                Log.d(TAG, "Selected as first player")
                updateGameState { it.copy(
                    phase = GamePhase.PLAYING,
                    isMyTurn = true
                )}
                _statusText.value = "You go first! Make your move."
            }
            // Second player, process first player's move
            else -> {
                Log.d(TAG, "Selected as second player")
                updateGameState { it.copy(
                    phase = GamePhase.PLAYING,
                    isMyTurn = false
                )}
                _statusText.value = "Opponent goes first. Processing their move..."
                handleEnemyShot(response)
            }
        }

        startListeningForEnemyFire()
    }

    private fun handleGameLoopError(exception: Throwable) {
        when (exception.message) {
            BattleshipRepository.ERROR_GAME_NOT_FOUND -> {
                _error.value = "Game session has ended"
                _isGameOver.value = true
                _statusText.value = "Game ended - session expired"
            }
            BattleshipRepository.ERROR_INVALID_GAME -> {
                _error.value = "Invalid game session"
                _isGameOver.value = true
                _statusText.value = "Game ended - invalid session"
            }
            else -> {
                if (retryCount < maxRetries) {
                    retryCount++
                    _statusText.value = "Connection issue, retrying... (Attempt $retryCount/$maxRetries)"
                    viewModelScope.launch {
                        delay(pollingDelay * retryCount)
                        startGameLoop()
                    }
                } else {
                    _error.value = "Failed to start game after $maxRetries attempts"
                    _isGameOver.value = true
                    _statusText.value = "Game failed to start - please try again"
                }
            }
        }
    }

    private fun handleEnemyShot(response: EnemyFireResponse) {
        if (response.x == null || response.y == null) return

        val position = Position(response.x, response.y)
        val currentState = _gameState.value ?: return
        
        // Update enemy shots
        val newEnemyShots = currentState.enemyShots + position
        
        // Check if shot hit any of our ships
        val hitShip = currentState.playerShips.find { ship ->
            val shipCells = BattleshipGame().calculateShipCells(ship)
            shipCells.any { it.x == response.x && it.y == response.y }
        }

        if (hitShip != null) {
            // Check if ship was sunk
            val shipCells = BattleshipGame().calculateShipCells(hitShip)
            val allCellsHit = shipCells.all { cell ->
                newEnemyShots.any { shot -> shot.x == cell.x && shot.y == cell.y }
            }

            // Check if all ships are sunk
            val allShipsSunk = currentState.playerShips.all { ship ->
                val cells = BattleshipGame().calculateShipCells(ship)
                cells.all { cell -> newEnemyShots.any { shot -> shot.x == cell.x && shot.y == cell.y } }
            }

            if (allShipsSunk) {
                updateGameState { it.copy(
                    enemyShots = newEnemyShots,
                    phase = GamePhase.FINISHED,
                    isGameOver = true
                )}
                _statusText.value = "Game Over! All your ships have been sunk!"
            } else {
                updateGameState { it.copy(
                    enemyShots = newEnemyShots,
                    isMyTurn = true
                )}
                _statusText.value = if (allCellsHit) {
                    "Enemy sunk your ${hitShip.ship}! Your turn."
                } else {
                    "Enemy hit your ${hitShip.ship} at (${response.x}, ${response.y})! Your turn."
                }
            }
        } else {
            updateGameState { it.copy(
                enemyShots = newEnemyShots,
                isMyTurn = true
            )}
            _statusText.value = "Enemy missed at (${response.x}, ${response.y})! Your turn."
        }
    }

    private fun startGameLoop() {
        gameLoopJob?.cancel()
        enemyFireJob?.cancel()
        
        gameLoopJob = viewModelScope.launch {
            val state = _gameState.value ?: return@launch
            if (state.playerName == null || state.gameKey == null) {
                _statusText.value = "Game not initialized"
                return@launch
            }

            _statusText.value = "Waiting for game to start..."
            Log.d(TAG, "Starting game loop")

            try {
                withTimeout(longPollingTimeout) {
                    repository.enemyFire(state.playerName, state.gameKey).fold(
                        onSuccess = { response ->
                            Log.d(TAG, "Initial game state: $response")
                            handleInitialGameState(response)
                            gameLoopJob?.cancel()
                            gameLoopJob = null
                        },
                        onFailure = { exception ->
                            handleGameLoopError(exception)
                        }
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleGameLoopError(e)
            }
        }
    }

    private fun startListeningForEnemyFire() {
        // Cancel existing job first
        enemyFireJob?.cancel()
        
        // Reset retry count when starting fresh
        retryCount = 0
        
        enemyFireJob = viewModelScope.launch {
            val player = currentPlayer
            val gameKey = currentGameKey
            if (player == null || gameKey == null) {
                _error.value = "Game not initialized"
                return@launch
            }

            Log.d(TAG, "Starting to listen for enemy fire")
            while (!_isGameOver.value!!) {
                if (_isMyTurn.value == true) {
                    // If it's our turn, just wait briefly and check again
                    delay(pollingDelay)
                    continue
                }

                try {
                    _statusText.value = "Waiting for opponent's move..."
                    withTimeout(longPollingTimeout) {
                        repository.enemyFire(player, gameKey).fold(
                            onSuccess = { response ->
                                Log.d(TAG, "Received enemy fire response: $response")
                                retryCount = 0 // Reset retry count on success
                                
                                if (response.gameover) {
                                    _isGameOver.value = true
                                    _statusText.value = "Game Over!"
                                    return@fold
                                }
                                
                                if (response.x != null || response.y != null) {
                                    handleEnemyShot(response)
                                }
                            },
                            onFailure = { exception ->
                                Log.e(TAG, "Enemy fire error: ${exception.message}")
                                handleNetworkError(exception)
                            }
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.d(TAG, "Enemy fire polling timeout - continuing")
                    continue
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error in enemy fire loop: ${e.message}")
                    handleNetworkError(e)
                }

                delay(pollingDelay)
            }
        }
    }

    fun fire(x: Int, y: Int) {
        val currentState = _gameState.value ?: return
        
        if (currentState.phase != GamePhase.PLAYING) {
            _statusText.value = "Game is not in progress"
            return
        }

        if (!currentState.isMyTurn) {
            _statusText.value = "Not your turn"
            return
        }

        if (!_bothPlayersConnected.value!!) {
            _statusText.value = "Waiting for opponent"
            return
        }

        // Validate coordinates
        if (x !in 0..9 || y !in 0..9) {
            _statusText.value = "Invalid coordinates. Must be between 0 and 9."
            return
        }

        // Check if already fired at these coordinates
        val position = Position(x, y)
        if (currentState.playerHits.contains(position) || currentState.playerMisses.contains(position)) {
            _statusText.value = "You've already fired at these coordinates"
            return
        }

        viewModelScope.launch {
            try {
                _statusText.value = "Firing at (${x}, ${y})..."
                withTimeout(longPollingTimeout) {
                    repository.fire(
                        currentState.playerName!!,
                        currentState.gameKey!!,
                        x,
                        y
                    ).fold(
                        onSuccess = { response ->
                            handleFireResponse(response, position)
                        },
                        onFailure = { exception ->
                            handleFireError(exception)
                        }
                    )
                }
            } catch (e: Exception) {
                handleFireError(e)
            }
        }
    }

    private fun handleFireResponse(response: FireResponse, position: Position) {
        if (response.hit) {
            // Update hits and sunk ships
            updateGameState { state ->
                state.copy(
                    playerHits = state.playerHits + position,
                    sunkEnemyShips = state.sunkEnemyShips + response.shipsSunk,
                    isMyTurn = false
                )
            }

            if (response.shipsSunk.isNotEmpty()) {
                if (_gameState.value?.sunkEnemyShips?.size == BattleshipGame.SHIP_TYPES.size) {
                    updateGameState { it.copy(phase = GamePhase.FINISHED, isGameOver = true) }
                    _statusText.value = "Congratulations! You've won by sinking all enemy ships!"
                } else {
                    _statusText.value = buildString {
                        append("Hit and sunk ${response.shipsSunk.joinToString()}!")
                        append(" Ships remaining: ${BattleshipGame.SHIP_TYPES.size - (_gameState.value?.sunkEnemyShips?.size ?: 0)}")
                        append(" Waiting for opponent...")
                    }
                }
            } else {
                _statusText.value = "Hit! Waiting for opponent..."
            }
        } else {
            // Update misses
            updateGameState { state ->
                state.copy(
                    playerMisses = state.playerMisses + position,
                    isMyTurn = false
                )
            }
            _statusText.value = "Miss! Waiting for opponent..."
        }

        // Start listening for enemy's move
        startListeningForEnemyFire()
    }

    private fun handleFireError(exception: Throwable) {
        Log.e(TAG, "Fire error: ${exception.message}")
        when (exception) {
            is TimeoutCancellationException -> {
                _error.value = "Server request timed out. Please try again."
            }
            is CancellationException -> throw exception
            else -> {
                when (exception.message) {
                    BattleshipRepository.ERROR_NOT_YOUR_TURN -> {
                        _error.value = "Not your turn"
                        _statusText.value = "Wait for your turn"
                        _isMyTurn.value = false
                        startListeningForEnemyFire()
                    }
                    BattleshipRepository.ERROR_INVALID_COORDINATES -> {
                        _error.value = "Invalid coordinates"
                    }
                    BattleshipRepository.ERROR_GAME_NOT_FOUND -> {
                        _error.value = "Game session has expired"
                        _isGameOver.value = true
                        _statusText.value = "Game ended - session expired"
                    }
                    else -> handleNetworkError(exception)
                }
            }
        }
    }

    private fun handleNetworkError(exception: Throwable) {
        val errorMsg = exception.message.orEmpty()
        _error.value = errorMsg
        
        if (!errorMsg.contains("Timeout") && retryCount < maxRetries) {
            retryCount++
            _statusText.value = "Connection issue, retrying... (Attempt $retryCount/$maxRetries)"
            viewModelScope.launch {
                delay(pollingDelay * retryCount)
                if (!_isMyTurn.value!!) {
                    startListeningForEnemyFire()
                }
            }
        } else if (retryCount >= maxRetries) {
            _error.value = "Connection lost after $maxRetries retries"
            _isGameOver.value = true
        }
    }

    private fun updateGameState(update: (GameState) -> GameState) {
        _gameState.value = _gameState.value?.let(update)
    }

    override fun onCleared() {
        super.onCleared()
        enemyFireJob?.cancel()
        gameLoopJob?.cancel()
    }

    companion object {
        private const val TAG = "BattleshipViewModel"
    }
} 
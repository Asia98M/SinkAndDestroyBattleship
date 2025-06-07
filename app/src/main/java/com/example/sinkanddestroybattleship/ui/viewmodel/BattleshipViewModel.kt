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
        _playerHits.value = emptyList()
        _playerMisses.value = emptyList()
        _sunkShips.value = emptyList()
        _isWaitingForOpponent.value = false
        _bothPlayersConnected.value = false
        _isGameOver.value = false
        _isMyTurn.value = false
        _gameJoined.value = false
        _enemyShots.value = emptyList()
        retryCount = 0
    }

    suspend fun ping(): Result<Boolean> {
        return repository.ping()
    }

    @SuppressLint("NullSafeMutableLiveData")
    fun joinGame(player: String, gameKey: String, ships: List<Ship>) {
        // Reset game state before joining
        resetGameState()
        
        // Validate IDs first
        val battleshipGame = BattleshipGame()
        val idError = battleshipGame.validateIds(player, gameKey)
        if (idError != null) {
            _error.value = idError
            return
        }

        // Validate ship placement
        val shipError = battleshipGame.validateShipPlacement(ships)
        if (shipError != null) {
            _error.value = shipError
            return
        }

        currentPlayer = player
        currentGameKey = gameKey
        _playerShips.value = ships
        _isWaitingForOpponent.value = true

        viewModelScope.launch {
            try {
                _statusText.value = "Connecting to game..."
                val request = JoinGameRequest(ships, gameKey, player)
                Log.d(TAG, "Attempting to join game with request: $request")
                
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
        Log.d(TAG, "Join game success: $response")
        _gameJoined.value = true
        
        if (response.gameover) {
            _statusText.value = "Game ended unexpectedly"
            _isGameOver.value = true
            return
        }

        // Start the game loop immediately after joining
        startGameLoop()
    }

    private fun handleInitialGameState(response: EnemyFireResponse) {
        when {
            response.gameover -> {
                _statusText.value = "Game ended unexpectedly"
                _isGameOver.value = true
                return
            }
            // First player - no coordinates, just confirmation to start
            response.x == null && response.y == null -> {
                Log.d(TAG, "We are first player - our turn to start")
                _isMyTurn.value = true
                _bothPlayersConnected.value = true
                _statusText.value = "Both players connected! Your turn to start!"
            }
            // Second player - received first player's move
            else -> {
                Log.d(TAG, "We are second player - received first move")
                _isMyTurn.value = false
                _bothPlayersConnected.value = true
                _statusText.value = "Both players connected! Processing opponent's move..."
                handleEnemyShot(response)
            }
        }

        // Start listening for enemy moves
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
        if (response.x != null && response.y != null) {
            val position = Position(response.x, response.y)
            
            // Update enemy shots
            val currentShots = _enemyShots.value.orEmpty().toMutableList()
            currentShots.add(position)
            _enemyShots.value = currentShots
            
            // Check if the shot hit one of our ships
            val hitShip = _playerShips.value?.find { ship ->
                val shipCells = BattleshipGame().calculateShipCells(ship)
                shipCells.any { it.x == response.x && it.y == response.y }
            }

            if (hitShip != null) {
                // Check if the ship is sunk
                val shipCells = BattleshipGame().calculateShipCells(hitShip)
                val allCellsHit = shipCells.all { cell ->
                    currentShots.any { shot -> shot.x == cell.x && shot.y == cell.y }
                }

                _statusText.value = if (allCellsHit) {
                    "Enemy sunk your ${hitShip.ship}! Your turn."
                } else {
                    "Enemy hit your ${hitShip.ship} at (${response.x}, ${response.y})! Your turn."
                }

                // Check if all ships are sunk
                val allShipsSunk = _playerShips.value?.all { ship ->
                    val cells = BattleshipGame().calculateShipCells(ship)
                    cells.all { cell ->
                        currentShots.any { shot -> shot.x == cell.x && shot.y == cell.y }
                    }
                } ?: false

                if (allShipsSunk) {
                    _isGameOver.value = true
                    _statusText.value = "Game Over! All your ships have been sunk!"
                    return
                }
            } else {
                _statusText.value = "Enemy missed at (${response.x}, ${response.y})! Your turn."
            }
            _isMyTurn.value = true
        }
    }

    private fun startGameLoop() {
        // Cancel any existing jobs first
        gameLoopJob?.cancel()
        enemyFireJob?.cancel()
        
        gameLoopJob = viewModelScope.launch {
            val player = currentPlayer
            val gameKey = currentGameKey
            if (player == null || gameKey == null) {
                _error.value = "Game not initialized"
                return@launch
            }

            _statusText.value = "Waiting for game to start..."
            Log.d(TAG, "Starting game loop - waiting for initial server response")

            try {
                // Initial enemyFire call to get game state
                withTimeout(longPollingTimeout) {
                    repository.enemyFire(player, gameKey).fold(
                        onSuccess = { response ->
                            Log.d(TAG, "Initial game state received: $response")
                            handleInitialGameState(response)
                            // Cancel gameLoopJob as it's no longer needed
                            gameLoopJob?.cancel()
                            gameLoopJob = null
                        },
                        onFailure = { exception ->
                            Log.e(TAG, "Failed to get initial game state: ${exception.message}")
                            handleGameLoopError(exception)
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in game loop: ${e.message}")
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
        val player = currentPlayer
        val gameKey = currentGameKey
        if (player == null || gameKey == null) {
            _error.value = "Game not initialized"
            return
        }

        if (_isMyTurn.value != true) {
            _error.value = "Not your turn"
            return
        }

        if (!_bothPlayersConnected.value!!) {
            _error.value = "Waiting for opponent to join"
            return
        }

        // Check if we've already fired at these coordinates
        val alreadyFired = (_playerHits.value?.any { it.x == x && it.y == y } ?: false) ||
                          (_playerMisses.value?.any { it.x == x && it.y == y } ?: false)
        if (alreadyFired) {
            _error.value = "You've already fired at these coordinates"
            return
        }

        // Validate coordinates
        if (x !in 0..9 || y !in 0..9) {
            _error.value = "Invalid coordinates. Must be between 0 and 9."
            return
        }

        viewModelScope.launch {
            try {
                _statusText.value = "Firing at (${x}, ${y})..."
                withTimeout(longPollingTimeout) {
                    repository.fire(player, gameKey, x, y).fold(
                        onSuccess = { response ->
                            handleFireResponse(response, Position(x, y))
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
            val currentHits = _playerHits.value.orEmpty().toMutableList()
            currentHits.add(position)
            _playerHits.value = currentHits

            if (response.shipsSunk.isNotEmpty()) {
                val currentSunkShips = _sunkShips.value.orEmpty().toMutableList()
                currentSunkShips.addAll(response.shipsSunk)
                _sunkShips.value = currentSunkShips

                if (currentSunkShips.size == BattleshipGame.SHIP_TYPES.size) {
                    _isGameOver.value = true
                    _statusText.value = "Congratulations! You've won the game by sinking all enemy ships!"
                } else {
                    _statusText.value = buildString {
                        append("Hit and sunk ${response.shipsSunk.joinToString()}!")
                        append(" Ships remaining: ${BattleshipGame.SHIP_TYPES.size - currentSunkShips.size}")
                        append(" Waiting for opponent...")
                    }
                }
            } else {
                _statusText.value = "Hit! Waiting for opponent..."
            }
        } else {
            val currentMisses = _playerMisses.value.orEmpty().toMutableList()
            currentMisses.add(position)
            _playerMisses.value = currentMisses
            _statusText.value = "Miss! Waiting for opponent..."
        }
        
        _isMyTurn.value = false
        // Explicitly start listening for enemy fire after our turn
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

    override fun onCleared() {
        super.onCleared()
        enemyFireJob?.cancel()
        gameLoopJob?.cancel()
    }

    companion object {
        private const val TAG = "BattleshipViewModel"
    }
}

data class GameState(
    val isMyTurn: Boolean,
    val isGameOver: Boolean,
    val playerShips: List<Ship>,
    val enemyShots: List<Position>,
    val playerHits: List<Position>,
    val playerMisses: List<Position>
) 
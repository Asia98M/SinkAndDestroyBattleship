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

    private var currentPlayer: String? = null
    private var currentGameKey: String? = null
    private var enemyFireJob: Job? = null
    private var retryCount = 0
    private val maxRetries = 3
    private val pollingDelay = 1000L // 1 second
    private val longPollingTimeout = 30000L // 30 seconds

    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    init {
        _playerHits.value = emptyList()
        _playerMisses.value = emptyList()
        _sunkShips.value = emptyList()
    }

    suspend fun ping(): Result<Boolean> {
        return repository.ping()
    }

    @SuppressLint("NullSafeMutableLiveData")
    fun joinGame(player: String, gameKey: String, ships: List<Ship>) {
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
        _playerHits.value = emptyList()
        _playerMisses.value = emptyList()
        retryCount = 0

        viewModelScope.launch {
            try {
                _statusText.value = "Joining game..."
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
    }

    private fun handleJoinSuccess(response: EnemyFireResponse) {
        Log.d(TAG, "Join game success: $response")
        _gameJoined.value = true
        _sunkShips.value = emptyList()
        
        if (response.gameover) {
            _statusText.value = "Game ended unexpectedly"
            _isGameOver.value = true
            return
        }

        // If x and y are null, we're the first player (it's our turn)
        // If x and y are set, we're the second player and we need to process their first shot
        val isFirstPlayer = response.x == null && response.y == null
        _isMyTurn.value = isFirstPlayer
        
        if (isFirstPlayer) {
            _statusText.value = "You go first! Make your move."
        } else {
            // Process the opponent's first shot
            handleEnemyShot(response)
        }

        // Start listening for enemy moves
        startListeningForEnemyFire()
    }

    private fun handleEnemyShot(response: EnemyFireResponse) {
        if (response.x != null && response.y != null) {
            val currentShots = _enemyShots.value.orEmpty().toMutableList()
            val position = Position(response.x, response.y)
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
            } else {
                _statusText.value = "Enemy missed at (${response.x}, ${response.y})! Your turn."
            }
            _isMyTurn.value = true
        }
    }

    private fun startListeningForEnemyFire() {
        enemyFireJob?.cancel()
        enemyFireJob = viewModelScope.launch {
            val player = currentPlayer
            val gameKey = currentGameKey
            if (player == null || gameKey == null) {
                _error.value = "Game not initialized"
                return@launch
            }

            _isGameOver.value = false
            while (!_isGameOver.value!!) {
                if (_isMyTurn.value == true) {
                    // Even when it's our turn, keep the connection alive with less frequent polls
                    delay(pollingDelay)
                    continue
                }

                try {
                    _statusText.value = "Waiting for opponent's move..."
                    withTimeout(longPollingTimeout) {
                        repository.enemyFire(player, gameKey).fold(
                            onSuccess = { response ->
                                retryCount = 0 // Reset retry count on successful response
                                
                                if (response.gameover) {
                                    _isGameOver.value = true
                                    _statusText.value = "Game Over!"
                                    return@fold
                                }
                                
                                handleEnemyShot(response)
                            },
                            onFailure = { exception ->
                                when (exception.message) {
                                    BattleshipRepository.ERROR_GAME_NOT_FOUND,
                                    BattleshipRepository.ERROR_INVALID_GAME -> {
                                        _error.value = "Game session has ended"
                                        _isGameOver.value = true
                                        _statusText.value = "Game ended - session expired"
                                        return@fold
                                    }
                                    else -> {
                                        if (retryCount < maxRetries) {
                                            retryCount++
                                            _statusText.value = "Connection issue, retrying... (Attempt $retryCount/$maxRetries)"
                                            delay(pollingDelay * retryCount) // Exponential backoff
                                        } else {
                                            handleNetworkError(exception)
                                            _error.value = "Lost connection to server. Please rejoin the game."
                                            _isGameOver.value = true
                                            return@fold
                                        }
                                    }
                                }
                            }
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    // Handle long polling timeout by simply continuing the loop
                    continue
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    handleNetworkError(e)
                }

                // Small delay between polls to prevent overwhelming the server
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

        // Check if we've already fired at these coordinates
        val alreadyFired = (_playerHits.value?.any { it.x == x && it.y == y } ?: false) ||
                          (_playerMisses.value?.any { it.x == x && it.y == y } ?: false)
        if (alreadyFired) {
            _error.value = "You've already fired at these coordinates"
            return
        }

        viewModelScope.launch {
            try {
                _statusText.value = "Firing at (${x}, ${y})..."
                withTimeout(longPollingTimeout) {
                    repository.fire(player, gameKey, x, y).fold(
                        onSuccess = { response ->
                            val position = Position(x, y)
                            if (response.hit) {
                                val currentHits = _playerHits.value.orEmpty().toMutableList()
                                currentHits.add(position)
                                _playerHits.value = currentHits

                                if (response.shipsSunk.isNotEmpty()) {
                                    // Update sunk ships list
                                    val currentSunkShips = _sunkShips.value.orEmpty().toMutableList()
                                    currentSunkShips.addAll(response.shipsSunk)
                                    _sunkShips.value = currentSunkShips

                                    // Check if all ships are sunk
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
                            
                            // Immediately start checking for enemy's move
                            retryCount = 0
                        },
                        onFailure = { exception ->
                            when (exception.message) {
                                BattleshipRepository.ERROR_NOT_YOUR_TURN -> {
                                    _error.value = "Not your turn"
                                    _statusText.value = "Wait for your turn"
                                    _isMyTurn.value = false
                                }
                                BattleshipRepository.ERROR_INVALID_COORDINATES -> {
                                    _error.value = "Invalid coordinates"
                                }
                                BattleshipRepository.ERROR_GAME_NOT_FOUND -> {
                                    _error.value = "Game session has expired"
                                    _isGameOver.value = true
                                    _statusText.value = "Game ended - session expired"
                                }
                                else -> {
                                    handleNetworkError(exception)
                                    _error.value = "Connection error. Please try again."
                                }
                            }
                        }
                    )
                }
            } catch (e: TimeoutCancellationException) {
                _error.value = "Server request timed out. Please try again."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleNetworkError(e)
                _error.value = "Connection error. Please try again."
            }
        }
    }

    private fun handleServerError(error: String) {
        when {
            error.contains("Game not found") || error.contains("Invalid game") -> {
                _error.value = "Game session ended: $error"
                _isGameOver.value = true
                _statusText.value = "Game ended - session expired or invalid"
            }
            retryCount < maxRetries -> {
                retryCount++
                viewModelScope.launch {
                    delay(2000L * retryCount) // Exponential backoff
                    startListeningForEnemyFire()
                }
            }
            else -> {
                _error.value = "Server error after $maxRetries retries: $error"
                _isGameOver.value = true
                _statusText.value = "Game ended due to server error"
            }
        }
    }

    private fun handleNetworkError(exception: Throwable) {
        val errorMsg = exception.message.orEmpty()
        _error.value = errorMsg
        
        if (!errorMsg.contains("Timeout") && retryCount < maxRetries) {
            retryCount++
            viewModelScope.launch {
                delay(2000L * retryCount) // Exponential backoff
                startListeningForEnemyFire()
            }
        } else if (retryCount >= maxRetries) {
            _error.value = "Connection lost after $maxRetries retries"
            _isGameOver.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        enemyFireJob?.cancel()
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
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

    private var currentPlayer: String? = null
    private var currentGameKey: String? = null
    private var enemyFireJob: Job? = null
    private var retryCount = 0
    private val maxRetries = 3

    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    init {
        _playerHits.value = emptyList()
        _playerMisses.value = emptyList()
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
        
        if (response.gameover) {
            _statusText.value = "Game ended unexpectedly"
            _isGameOver.value = true
            return
        }

        // If x and y are null, we're the first player (it's our turn)
        // If x and y are set, we're the second player (opponent's turn)
        val isFirstPlayer = response.x == null && response.y == null
        _isMyTurn.value = isFirstPlayer
        
        if (isFirstPlayer) {
            _statusText.value = "Waiting for opponent to join..."
        } else {
            handleEnemyFireResponse(response)
        }

        startListeningForEnemyFire()
    }

    fun fire(x: Int, y: Int) {
        val player = currentPlayer
        val gameKey = currentGameKey
        if (player == null || gameKey == null) {
            _error.value = "Game not initialized"
            return
        }

        // Check if it's our turn
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

        // Validate coordinates are within grid
        if (x !in 0..9 || y !in 0..9) {
            _error.value = "Coordinates must be between 0 and 9"
            return
        }

        viewModelScope.launch {
            repository.fire(player, gameKey, x, y).fold(
                onSuccess = { response ->
                    val position = Position(x, y)
                    if (response.hit) {
                        val currentHits = _playerHits.value.orEmpty().toMutableList()
                        currentHits.add(position)
                        _playerHits.value = currentHits
                        _error.value = if (response.shipsSunk.isNotEmpty()) {
                            "Hit and sunk ${response.shipsSunk.joinToString()}!"
                        } else {
                            "Hit!"
                        }
                    } else {
                        val currentMisses = _playerMisses.value.orEmpty().toMutableList()
                        currentMisses.add(position)
                        _playerMisses.value = currentMisses
                        _error.value = "Miss!"
                    }
                    _isMyTurn.value = false
                    _statusText.value = "Waiting for opponent's move..."
                },
                onFailure = { exception ->
                    when (exception.message) {
                        BattleshipRepository.ERROR_NOT_YOUR_TURN -> {
                            _error.value = "Not your turn"
                            _statusText.value = "Wait for your turn"
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
                            _error.value = exception.message
                            _statusText.value = "Error occurred during your move"
                        }
                    }
                }
            )
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

            _isGameOver.value = false // Initialize to false
            while (_isGameOver.value == false) { // Safe null check
                repository.enemyFire(player, gameKey).fold(
                    onSuccess = { response ->
                        retryCount = 0
                        handleEnemyFireResponse(response)
                        if (!response.gameover) {
                            delay(1000) // Add a small delay before next poll
                        }
                    },
                    onFailure = { exception ->
                        when (exception.message) {
                            BattleshipRepository.ERROR_GAME_NOT_FOUND,
                            BattleshipRepository.ERROR_INVALID_GAME -> {
                                _error.value = "Game session has ended"
                                _isGameOver.value = true
                                _statusText.value = "Game ended - session expired"
                            }
                            else -> handleNetworkError(exception)
                        }
                    }
                )
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

    private fun handleEnemyFireResponse(response: EnemyFireResponse) {
        _isGameOver.value = response.gameover
        if (!response.gameover) {
            // Check if we have actual coordinates (a shot was made)
            if (response.x != null && response.y != null) {
                val currentShots = _enemyShots.value.orEmpty().toMutableList()
                val position = Position(response.x, response.y)
                currentShots.add(position)
                _enemyShots.value = currentShots
                
                // Check if the shot hit one of our ships
                val hitShip = _playerShips.value?.any { ship ->
                    val shipCells = BattleshipGame().calculateShipCells(ship)
                    shipCells.any { it.x == response.x && it.y == response.y }
                } ?: false

                _error.value = if (hitShip) "Enemy hit your ship!" else "Enemy missed!"
                _isMyTurn.value = true
                _statusText.value = "Your turn! Make your move."
            } else {
                // No shot coordinates means we're waiting for opponent
                if (_gameJoined.value == true) {
                    if (_isMyTurn.value == true) {
                        _statusText.value = "Your turn! Make your move."
                    } else {
                        _statusText.value = "Waiting for opponent's move..."
                    }
                } else {
                    _statusText.value = "Waiting for opponent to join..."
                }
            }
        } else {
            _statusText.value = "Game Over!"
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
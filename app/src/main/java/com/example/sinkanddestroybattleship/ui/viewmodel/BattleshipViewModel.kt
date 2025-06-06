package com.example.sinkanddestroybattleship.ui.viewmodel

import android.annotation.SuppressLint
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

    init {
        _playerHits.value = emptyList()
        _playerMisses.value = emptyList()
    }

    suspend fun ping(): Result<Boolean> {
        return repository.ping()
    }

    @SuppressLint("NullSafeMutableLiveData")
    fun joinGame(player: String, gameKey: String, ships: List<Ship>) {
        currentPlayer = player
        currentGameKey = gameKey
        _playerShips.value = ships
        _playerHits.value = emptyList()
        _playerMisses.value = emptyList()
        retryCount = 0

        viewModelScope.launch {
            repository.joinGame(player, gameKey, ships).fold(
                onSuccess = { response ->
                    if (response.error != null) {
                        _error.value = response.error
                        currentPlayer = null
                        currentGameKey = null
                    } else {
                        _gameJoined.value = true
                        handleEnemyFireResponse(response)
                        startListeningForEnemyFire()
                    }
                },
                onFailure = { exception ->
                    _error.value = exception.message
                    currentPlayer = null
                    currentGameKey = null
                }
            )
        }
    }

    fun fire(x: Int, y: Int) {
        val player = currentPlayer
        val gameKey = currentGameKey
        if (player == null || gameKey == null) {
            _error.value = "Game not initialized"
            return
        }

        viewModelScope.launch {
            repository.fire(player, gameKey, x, y).fold(
                onSuccess = { response ->
                    val position = Position(x, y)
                    response.hit?.let { isHit ->
                        if (isHit) {
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
                    } ?: run {
                        _error.value = "Invalid response from server"
                    }
                },
                onFailure = { exception ->
                    _error.value = exception.message
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

            repository.enemyFire(player, gameKey).fold(
                onSuccess = { response ->
                    if (response.error != null) {
                        handleServerError(response.error)
                    } else {
                        retryCount = 0
                        handleEnemyFireResponse(response)
                        if (!(response.gameover ?: false)) {
                            delay(1000) // Add a small delay before next poll
                            startListeningForEnemyFire()
                        }
                    }
                },
                onFailure = { exception ->
                    handleNetworkError(exception)
                }
            )
        }
    }

    private fun handleServerError(error: String) {
        if (error.contains("Game not found") || error.contains("Invalid game")) {
            _error.value = "Game session ended: $error"
            _isGameOver.value = true
        } else if (retryCount < maxRetries) {
            retryCount++
            viewModelScope.launch {
                delay(2000L * retryCount) // Exponential backoff
                startListeningForEnemyFire()
            }
        } else {
            _error.value = "Server error after $maxRetries retries: $error"
            _isGameOver.value = true
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
        _isGameOver.value = response.gameover ?: false
        if (!(response.gameover ?: false)) {
            // Check if we have actual coordinates (a shot was made)
            if (response.x != null && response.y != null) {
                val currentShots = _enemyShots.value.orEmpty().toMutableList()
                val position = Position(response.x, response.y)
                currentShots.add(position)
                _enemyShots.value = currentShots
                
                // Update the status text based on hit or miss
                response.hit?.let { isHit ->
                    _error.value = if (isHit) "Enemy hit your ship!" else "Enemy missed!"
                } ?: run {
                    _error.value = "Enemy fired at (${response.x}, ${response.y})"
                }
                _isMyTurn.value = true
            } else {
                // No shot coordinates means waiting for opponent
                _error.value = "Waiting for opponent's move..."
                _isMyTurn.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        enemyFireJob?.cancel()
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
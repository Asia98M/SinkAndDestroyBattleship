package com.example.sinkanddestroybattleship.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sinkanddestroybattleship.data.models.*
import com.example.sinkanddestroybattleship.data.repository.BattleshipRepository
import kotlinx.coroutines.Job
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

    private val _playerShots = MutableLiveData<List<Position>>()
    val playerShots: LiveData<List<Position>> = _playerShots

    private val _isGameOver = MutableLiveData<Boolean>()
    val isGameOver: LiveData<Boolean> = _isGameOver

    private val _isMyTurn = MutableLiveData<Boolean>()
    val isMyTurn: LiveData<Boolean> = _isMyTurn

    private var currentPlayer: String? = null
    private var currentGameKey: String? = null
    private var enemyFireJob: Job? = null

    fun joinGame(player: String, gameKey: String, ships: List<Ship>) {
        currentPlayer = player
        currentGameKey = gameKey
        _playerShips.value = ships

        viewModelScope.launch {
            repository.joinGame(player, gameKey, ships).fold(
                onSuccess = { response ->
                    handleEnemyFireResponse(response)
                    startListeningForEnemyFire()
                },
                onFailure = { exception ->
                    _error.value = exception.message
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
                    val currentShots = _playerShots.value.orEmpty().toMutableList()
                    currentShots.add(Position(x, y))
                    _playerShots.value = currentShots
                    if (response.hit) {
                        // Handle hit
                    }
                    if (response.shipsSunk.isNotEmpty()) {
                        // Handle sunk ships
                    }
                    _isMyTurn.value = false
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
                    handleEnemyFireResponse(response)
                    if (!response.gameover) {
                        startListeningForEnemyFire()
                    }
                },
                onFailure = { exception ->
                    _error.value = exception.message
                }
            )
        }
    }

    private fun handleEnemyFireResponse(response: EnemyFireResponse) {
        _isGameOver.value = response.gameover
        if (!response.gameover) {
            response.x?.let { x ->
                response.y?.let { y ->
                    val currentShots = _enemyShots.value.orEmpty().toMutableList()
                    currentShots.add(Position(x, y))
                    _enemyShots.value = currentShots
                }
            }
            _isMyTurn.value = true
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
    val playerShots: List<Position>
) 
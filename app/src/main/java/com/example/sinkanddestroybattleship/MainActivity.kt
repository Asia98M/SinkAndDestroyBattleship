package com.example.sinkanddestroybattleship

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.sinkanddestroybattleship.data.models.BattleshipGame
import com.example.sinkanddestroybattleship.data.models.Position
import com.example.sinkanddestroybattleship.data.models.Ship
import com.example.sinkanddestroybattleship.data.models.ShipType
import com.example.sinkanddestroybattleship.data.network.ConnectionTest
import com.example.sinkanddestroybattleship.databinding.ActivityMainBinding
import com.example.sinkanddestroybattleship.game.BattleshipGame
import com.example.sinkanddestroybattleship.game.Ship
import com.example.sinkanddestroybattleship.ui.viewmodel.BattleshipViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: BattleshipViewModel by viewModels()
    private val battleshipGame = BattleshipGame()
    
    private val ships = mutableListOf<Ship>()
    private var currentRotation = false // false = horizontal, true = vertical
    private var isPlacingShips = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupShipPlacement()
        setupClickListeners()
        observeViewModel()
        testServerConnection()
    }

    private fun testServerConnection() {
        binding.statusText.text = "Testing server connection..."
        binding.joinGameButton.isEnabled = false
        
        lifecycleScope.launch {
            val isReachable = ConnectionTest.isServerReachable()
            val connectionDetails = ConnectionTest.getConnectionDetails()
            
            if (isReachable) {
                binding.statusText.text = "Enter Player ID and Game ID to start"
                binding.joinGameButton.isEnabled = true
                
                // Test game server API
                viewModel.ping().onFailure { exception ->
                    Toast.makeText(
                        this@MainActivity,
                        "Warning: Game server not responding: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                binding.statusText.text = "Server connection failed"
                Toast.makeText(
                    this@MainActivity,
                    connectionDetails,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupShipPlacement() {
        // Setup ship type spinner with descriptions
        val shipDescriptions = BattleshipGame.SHIP_TYPES.map { battleshipGame.getShipDescription(it) }
        binding.shipTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            shipDescriptions
        )

        binding.rotateButton.setOnClickListener {
            if (!isPlacingShips) return@setOnClickListener
            
            currentRotation = !currentRotation
            Toast.makeText(this, "Orientation: ${if (currentRotation) "vertical" else "horizontal"}", Toast.LENGTH_SHORT).show()
            
            // Update preview if we're hovering over a cell
            binding.playerBoard.lastHoverPosition?.let { position ->
                updateShipPlacementPreview(position)
            }
        }

        binding.playerBoard.setOnCellHoverListener { position ->
            if (isPlacingShips && ships.size < BattleshipGame.SHIP_TYPES.size) {
                updateShipPlacementPreview(position)
            }
        }

        binding.playerBoard.setOnCellClickListener { position ->
            if (isPlacingShips && ships.size < BattleshipGame.SHIP_TYPES.size) {
                tryPlaceShip(position)
            }
        }
    }

    private fun updateShipPlacementPreview(position: Position) {
        val nextShip = battleshipGame.getNextShipToPlace(ships)
        if (nextShip != null) {
            val previewShip = Ship(currentRotation, position.x, nextShip.name, position.y)
            val previewCells = battleshipGame.calculateShipCells(previewShip)
            val isValid = battleshipGame.isValidPlacement(previewShip, ships)
            binding.playerBoard.setPreview(previewCells, isValid)
        } else {
            binding.playerBoard.clearPreview()
        }
    }

    private fun tryPlaceShip(position: Position) {
        val nextShip = battleshipGame.getNextShipToPlace(ships)
        if (nextShip == null) {
            binding.playerBoard.clearPreview()
            return
        }

        val ship = Ship(currentRotation, position.x, nextShip.name, position.y)
        
        if (battleshipGame.isValidPlacement(ship, ships)) {
            ships.add(ship)
            binding.playerBoard.setShips(ships)
            binding.playerBoard.clearPreview()
            
            if (ships.size < BattleshipGame.SHIP_TYPES.size) {
                val nextShipType = battleshipGame.getNextShipToPlace(ships)
                if (nextShipType != null) {
                    binding.shipTypeSpinner.setSelection(ships.size)
                    binding.statusText.text = "Place your ${battleshipGame.getShipDescription(nextShipType)}"
                }
            } else {
                // All ships placed
                isPlacingShips = false
                binding.shipTypeSpinner.isEnabled = false
                binding.rotateButton.isEnabled = false
                binding.startGameButton.visibility = View.VISIBLE
                binding.statusText.text = "All ships placed! Click Start to begin"
                binding.playerBoard.clearPreview()
                binding.playerBoard.setOnCellHoverListener(null)
            }
        } else {
            Toast.makeText(this, "Invalid placement - Ship must be within bounds and not overlap", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClickListeners() {
        binding.joinGameButton.setOnClickListener {
            val gameKey = binding.gameIdInput.text.toString()
            val playerId = binding.playerIdInput.text.toString()
            if (gameKey.isNotBlank() && playerId.isNotBlank()) {
                isPlacingShips = true
                binding.shipPlacementControls.visibility = View.VISIBLE
                binding.joinGameButton.isEnabled = false
                binding.shipTypeSpinner.isEnabled = true
                binding.rotateButton.isEnabled = true
                val nextShipType = battleshipGame.getNextShipToPlace(ships)
                binding.statusText.text = "Place your ${battleshipGame.getShipDescription(nextShipType!!)}"
            } else {
                Toast.makeText(this, "Please enter game key and player ID", Toast.LENGTH_SHORT).show()
            }
        }

        binding.startGameButton.setOnClickListener {
            if (ships.size != BattleshipGame.SHIP_TYPES.size) {
                Toast.makeText(this, "Please place all ships before starting", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val gameKey = binding.gameIdInput.text.toString()
            val playerId = binding.playerIdInput.text.toString()
            if (gameKey.isNotBlank() && playerId.isNotBlank()) {
                binding.startGameButton.isEnabled = false
                binding.statusText.text = "Joining game..."
                viewModel.joinGame(playerId, gameKey, ships)
            }
        }

        binding.opponentBoard.setOnCellClickListener { position ->
            viewModel.fire(position.x, position.y)
        }
    }

    private fun observeViewModel() {
        viewModel.gameState.observe(this) { state ->
            // Update ship and shot displays
            binding.playerBoard.setShips(state.playerShips)
            binding.playerBoard.setShots(state.enemyShots, false)
            binding.opponentBoard.setShots(state.playerHits, true)
            binding.opponentBoard.setShots(state.playerMisses, true)

            // Update UI based on game phase
            when (state.phase) {
                BattleshipViewModel.GamePhase.SETUP -> {
                    binding.shipPlacementControls.visibility = View.VISIBLE
                    binding.gameIdInput.isEnabled = true
                    binding.playerIdInput.isEnabled = true
                    binding.joinGameButton.isEnabled = true
                    binding.startGameButton.visibility = View.GONE
                }
                BattleshipViewModel.GamePhase.WAITING -> {
                    binding.shipPlacementControls.visibility = View.VISIBLE
                    binding.gameIdInput.isEnabled = false
                    binding.playerIdInput.isEnabled = false
                    binding.joinGameButton.isEnabled = false
                    binding.startGameButton.visibility = View.GONE
                }
                BattleshipViewModel.GamePhase.PLAYING -> {
                    binding.shipPlacementControls.visibility = View.GONE
                    binding.gameIdInput.isEnabled = false
                    binding.playerIdInput.isEnabled = false
                    binding.joinGameButton.isEnabled = false
                    binding.startGameButton.visibility = View.GONE
                    binding.opponentBoard.isEnabled = state.isMyTurn
                }
                BattleshipViewModel.GamePhase.FINISHED -> {
                    binding.shipPlacementControls.visibility = View.GONE
                    binding.opponentBoard.isEnabled = false
                    binding.startGameButton.visibility = View.GONE
                }
            }

            // Update opponent board click handling based on turn
            if (state.isMyTurn && state.phase == BattleshipViewModel.GamePhase.PLAYING) {
                binding.opponentBoard.setOnCellClickListener { position ->
                    viewModel.fire(position.x, position.y)
                }
            } else {
                binding.opponentBoard.setOnCellClickListener(null)
            }
        }

        viewModel.statusText.observe(this) { status ->
            binding.statusText.text = status
        }

        viewModel.error.observe(this) { error ->
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }

        viewModel.isWaitingForOpponent.observe(this) { waiting ->
            if (waiting) {
                binding.statusText.text = "Waiting for opponent to join..."
            }
        }

        viewModel.bothPlayersConnected.observe(this) { connected ->
            if (connected) {
                binding.shipPlacementControls.visibility = View.GONE
                isPlacingShips = false
            }
        }
    }

    private fun resetGameState() {
        binding.joinGameButton.isEnabled = true
        binding.gameIdInput.isEnabled = true
        binding.playerIdInput.isEnabled = true
        binding.shipPlacementControls.visibility = View.GONE
        binding.startGameButton.visibility = View.GONE
        binding.shipTypeSpinner.isEnabled = true
        binding.rotateButton.isEnabled = true
        isPlacingShips = false
        ships.clear()
        binding.playerBoard.setShips(emptyList())
        binding.playerBoard.setShots(emptyList(), false)
        binding.opponentBoard.setShots(emptyList(), true)
        binding.playerBoard.clearPreview()
        binding.statusText.text = "Enter Player ID and Game ID to start"
    }
}

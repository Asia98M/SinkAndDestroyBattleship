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
import com.example.sinkanddestroybattleship.ui.viewmodel.BattleshipViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: BattleshipViewModel by viewModels()
    private val battleshipGame = BattleshipGame()
    
    private val ships = mutableListOf<Ship>()
    private var currentOrientation = "horizontal"
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
            
            currentOrientation = if (currentOrientation == "horizontal") "vertical" else "horizontal"
            Toast.makeText(this, "Orientation: $currentOrientation", Toast.LENGTH_SHORT).show()
            
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
            val previewShip = Ship(nextShip.name, position.x,currentOrientation, position.y)
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

        val ship = Ship(currentOrientation, position.x, nextShip.name ,position.y)
        
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
                lifecycleScope.launch {
                    viewModel.joinGame(playerId, gameKey, ships)
                }
            }
        }

        binding.opponentBoard.setOnCellClickListener { position ->
            if (viewModel.isMyTurn.value == true) {
                lifecycleScope.launch {
                    viewModel.fire(position.x, position.y)
                }
            } else {
                Toast.makeText(this, "Not your turn!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.playerShips.observe(this) { ships ->
            binding.playerBoard.setShips(ships)
        }

        viewModel.enemyShots.observe(this) { shots ->
            binding.playerBoard.setShots(shots, false)
        }

        viewModel.playerHits.observe(this) { hits ->
            binding.opponentBoard.setShots(hits, true)
        }

        viewModel.playerMisses.observe(this) { misses ->
            binding.opponentBoard.setShots(misses, true)
        }

        viewModel.isMyTurn.observe(this) { isMyTurn ->
            binding.opponentBoard.isEnabled = isMyTurn
            if (isMyTurn) {
                binding.statusText.text = "Your turn! Click on opponent's board to fire."
                binding.opponentBoard.setOnCellClickListener { position ->
                    lifecycleScope.launch {
                        viewModel.fire(position.x, position.y)
                    }
                }
            } else {
                binding.statusText.text = "Opponent's turn - please wait..."
                binding.opponentBoard.setOnCellClickListener(null)
            }
        }

        viewModel.isGameOver.observe(this) { isGameOver ->
            if (isGameOver) {
                binding.statusText.text = "Game Over!"
                binding.opponentBoard.setOnCellClickListener(null)
                binding.opponentBoard.isEnabled = false
            }
        }

        viewModel.error.observe(this) { error ->
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            if (error.contains("Game not initialized")) {
                resetGameState()
            }
        }

        viewModel.gameJoined.observe(this) { joined ->
            if (joined) {
                binding.shipPlacementControls.visibility = View.GONE
                binding.gameIdInput.isEnabled = false
                binding.playerIdInput.isEnabled = false
                binding.startGameButton.visibility = View.GONE
                isPlacingShips = false
                binding.statusText.text = if (viewModel.isMyTurn.value == true) {
                    "Game started - Your turn!"
                } else {
                    "Game started - Waiting for opponent..."
                }
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

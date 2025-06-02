package com.example.sinkanddestroybattleship

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.sinkanddestroybattleship.data.models.Position
import com.example.sinkanddestroybattleship.data.models.Ship
import com.example.sinkanddestroybattleship.data.models.ShipType
import com.example.sinkanddestroybattleship.databinding.ActivityMainBinding
import com.example.sinkanddestroybattleship.ui.viewmodel.BattleshipViewModel

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: BattleshipViewModel by viewModels()

    private val shipTypes = listOf(
        ShipType.CARRIER,
        ShipType.BATTLESHIP,
        ShipType.DESTROYER,
        ShipType.SUBMARINE,
        ShipType.PATROL_BOAT
    )
    private val orientations = listOf("horizontal", "vertical")
    private var currentShipIndex = 0
    private val ships = mutableListOf<Ship>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupSpinners() {
        binding.shipSizeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            shipTypes.map { "${it.name} (${it.size})" }
        )

        binding.orientationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            orientations
        )
    }

    private fun setupClickListeners() {
        binding.joinGameButton.setOnClickListener {
            val gameKey = binding.gameIdInput.text.toString()
            val playerId = binding.playerIdInput.text.toString()
            if (gameKey.isNotBlank() && playerId.isNotBlank() && ships.size == shipTypes.size) {
                viewModel.joinGame(playerId, gameKey, ships)
            } else {
                Toast.makeText(this, "Please enter game key, player ID and place all ships", Toast.LENGTH_SHORT).show()
            }
        }

        binding.playerBoard.setOnCellClickListener { position ->
            if (currentShipIndex < shipTypes.size) {
                val orientation = orientations[binding.orientationSpinner.selectedItemPosition]
                val shipType = shipTypes[currentShipIndex]
                placeShip(position, shipType, orientation)
            }
        }

        binding.opponentBoard.setOnCellClickListener { position ->
            if (viewModel.isMyTurn.value == true) {
                viewModel.fire(position.x, position.y)
            } else {
                Toast.makeText(this, "Not your turn!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun placeShip(position: Position, shipType: ShipType, orientation: String) {
        val ship = Ship(shipType.name, position.x, position.y, orientation)
        ships.add(ship)
        currentShipIndex++
        binding.playerBoard.setShips(ships)
        
        if (currentShipIndex == shipTypes.size) {
            binding.shipPlacementControls.visibility = View.GONE
            binding.instructionsText.text = "All ships placed! Click Join Game to start"
        } else {
            binding.shipSizeSpinner.setSelection(currentShipIndex)
            binding.instructionsText.text = "Place your ${shipTypes[currentShipIndex].name}"
        }
    }

    private fun observeViewModel() {
        viewModel.playerShips.observe(this) { ships ->
            binding.playerBoard.setShips(ships)
        }

        viewModel.enemyShots.observe(this) { shots ->
            binding.playerBoard.setShots(shots)
        }

        viewModel.playerShots.observe(this) { shots ->
            binding.opponentBoard.setShots(shots)
        }

        viewModel.isMyTurn.observe(this) { isMyTurn ->
            binding.statusText.text = if (isMyTurn) "Your turn!" else "Opponent's turn"
        }

        viewModel.isGameOver.observe(this) { isGameOver ->
            if (isGameOver) {
                binding.statusText.text = "Game Over!"
                binding.opponentBoard.setOnCellClickListener(null)
            }
        }

        viewModel.error.observe(this) { error ->
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }
}
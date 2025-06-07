package com.example.sinkanddestroybattleship.data.models

class BattleshipGame {
    companion object {
        const val BOARD_SIZE = 10
        const val MIN_ID_LENGTH = 3  // Minimum length for player/game IDs

        val SHIP_TYPES = listOf(
            ShipType.CARRIER,      // Size: 5
            ShipType.BATTLESHIP,   // Size: 4
            ShipType.DESTROYER,    // Size: 3
            ShipType.SUBMARINE,    // Size: 3
            ShipType.PATROL_BOAT   // Size: 2
        )

        val SHIP_DESCRIPTIONS = mapOf(
            ShipType.CARRIER to "Aircraft Carrier (5 spaces): The largest ship in your fleet",
            ShipType.BATTLESHIP to "Battleship (4 spaces): A powerful warship",
            ShipType.DESTROYER to "Destroyer (3 spaces): Fast and maneuverable",
            ShipType.SUBMARINE to "Submarine (3 spaces): Stealthy underwater vessel",
            ShipType.PATROL_BOAT to "Patrol Boat (2 spaces): Small but essential"
        )
    }

    fun validateShipPlacement(ships: List<Ship>): String? {
        // Check if we have all required ships
        val shipTypes = ships.map { it.ship }.toSet()
        if (shipTypes.size != SHIP_TYPES.size) {
            return "Must place all ship types: ${SHIP_TYPES.map { it.name }.joinToString()}"
        }

        // Check for duplicate ship types
        if (ships.size != shipTypes.size) {
            return "Duplicate ship types are not allowed"
        }

        // Validate each ship's position and orientation
        for (ship in ships) {
            val validationError = validateSingleShip(ship)
            if (validationError != null) {
                return validationError
            }
        }

        // Check for overlapping ships
        for (i in ships.indices) {
            for (j in i + 1 until ships.size) {
                if (shipsOverlap(ships[i], ships[j])) {
                    return "Ships cannot overlap"
                }
            }
        }

        return null
    }

    private fun validateSingleShip(ship: Ship): String? {
        val shipType = try {
            ShipType.valueOf(ship.ship)
        } catch (e: IllegalArgumentException) {
            return "Invalid ship type: ${ship.ship}"
        }

        // Validate orientation
        if (ship.orientation != "horizontal" && ship.orientation != "vertical") {
            return "Invalid orientation for ${ship.ship}: ${ship.orientation}"
        }

        // Validate coordinates are within bounds
        if (ship.x < 0 || ship.y < 0) {
            return "${ship.ship} position cannot be negative"
        }

        val shipSize = shipType.size
        val endX = if (ship.orientation == "horizontal") ship.x + shipSize - 1 else ship.x
        val endY = if (ship.orientation == "vertical") ship.y + shipSize - 1 else ship.y

        if (endX >= BOARD_SIZE || endY >= BOARD_SIZE) {
            return "${ship.ship} placement exceeds board boundaries"
        }

        return null
    }

    private fun shipsOverlap(ship1: Ship, ship2: Ship): Boolean {
        val cells1 = calculateShipCells(ship1)
        val cells2 = calculateShipCells(ship2)
        return cells1.any { it in cells2 }
    }

    fun validateMove(x: Int, y: Int): String? {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
            return "Coordinates ($x, $y) are outside the game grid (0-9)"
        }
        return null
    }

    fun validateIds(playerId: String, gameKey: String): String? {
        if (playerId.length < MIN_ID_LENGTH) {
            return "Player ID must be at least $MIN_ID_LENGTH characters long"
        }
        if (gameKey.length < MIN_ID_LENGTH) {
            return "Game key must be at least $MIN_ID_LENGTH characters long"
        }
        return null
    }

    fun getNextShipToPlace(placedShips: List<Ship>): ShipType? {
        return if (placedShips.size < SHIP_TYPES.size) {
            SHIP_TYPES[placedShips.size]
        } else null
    }

    fun getShipDescription(shipType: ShipType): String {
        return SHIP_DESCRIPTIONS[shipType] ?: ""
    }

    fun calculateShipCells(ship: Ship): List<Position> {
        val shipSize = ShipType.valueOf(ship.ship).size
        return if (ship.orientation == "horizontal") {
            (ship.x until ship.x + shipSize).map { Position(it, ship.y) }
        } else {
            (ship.y until ship.y + shipSize).map { Position(ship.x, it) }
        }
    }

    fun isValidPlacement(newShip: Ship, existingShips: List<Ship>): Boolean {
        // First validate the new ship's position
        if (validateSingleShip(newShip) != null) {
            return false
        }

        // Then check for overlaps with existing ships
        return existingShips.none { shipsOverlap(it, newShip) }
    }

    fun getShipPlacementPreview(x: Int, y: Int, shipType: ShipType, orientation: String): List<Position> {
        val ship = Ship(shipType.name, x, y, orientation)
        return calculateShipCells(ship)
    }
} 
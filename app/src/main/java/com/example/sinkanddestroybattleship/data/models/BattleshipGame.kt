package com.example.sinkanddestroybattleship.data.models

class BattleshipGame {
    companion object {
        const val BOARD_SIZE = 10
        const val MIN_ID_LENGTH = 3  // Minimum length for player/game IDs

        val SHIP_TYPES = listOf(
            ShipType.Carrier,      // Size: 5
            ShipType.Battleship,   // Size: 4
            ShipType.Destroyer,    // Size: 3
            ShipType.Submarine,    // Size: 3
            ShipType.PatrolBoat   // Size: 2
        )

        val SHIP_DESCRIPTIONS = mapOf(
            ShipType.Carrier to "Aircraft Carrier (5 spaces): The largest ship in your fleet",
            ShipType.Battleship to "Battleship (4 spaces): A powerful warship",
            ShipType.Destroyer to "Destroyer (3 spaces): Fast and maneuverable",
            ShipType.Submarine to "Submarine (3 spaces): Stealthy underwater vessel",
            ShipType.PatrolBoat to "Patrol Boat (2 spaces): Small but essential"
        )
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

    fun getShipPlacementPreview(orientation: String, shipType: ShipType, x: Int, y: Int): List<Position> {
        val ship = Ship(orientation, x, shipType.name, y)
        return calculateShipCells(ship)
    }
} 
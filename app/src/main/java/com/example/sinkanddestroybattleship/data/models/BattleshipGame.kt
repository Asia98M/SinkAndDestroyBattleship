package com.example.sinkanddestroybattleship.data.models

class BattleshipGame {
    companion object {
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
        val shipSize = ShipType.valueOf(newShip.ship).size
        val endX = if (newShip.orientation == "horizontal") newShip.x + shipSize - 1 else newShip.x
        val endY = if (newShip.orientation == "vertical") newShip.y + shipSize - 1 else newShip.y

        // Check board boundaries
        if (endX >= 10 || endY >= 10 || newShip.x < 0 || newShip.y < 0) {
            return false
        }

        // Get cells for new ship
        val newShipCells = calculateShipCells(newShip)

        // Check overlap with existing ships
        return existingShips.none { existingShip ->
            val existingShipCells = calculateShipCells(existingShip)
            newShipCells.any { it in existingShipCells }
        }
    }

    fun getShipPlacementPreview(x: Int, y: Int, shipType: ShipType, orientation: String): List<Position> {
        val ship = Ship(shipType.name, x, y, orientation)
        return calculateShipCells(ship)
    }
} 
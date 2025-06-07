package com.example.sinkanddestroybattleship.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.sinkanddestroybattleship.data.models.Position
import com.example.sinkanddestroybattleship.data.models.Ship
import com.example.sinkanddestroybattleship.data.models.ShipType
import kotlin.math.min

class BattleshipBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boardSize = 10
    private val paint = Paint()
    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private var ships: List<Ship> = emptyList()
    private var hits: List<Position> = emptyList()
    private var misses: List<Position> = emptyList()
    private var previewCells: List<Position> = emptyList()
    private var isValidPreview: Boolean = true
    private var onCellClickListener: ((Position) -> Unit)? = null
    private var onCellHoverListener: ((Position) -> Unit)? = null
    private var _lastHoverPosition: Position? = null
    
    // Public getter for lastHoverPosition
    val lastHoverPosition: Position?
        get() = _lastHoverPosition

    init {
        paint.isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellSize = min(w, h) / boardSize.toFloat()
        offsetX = (w - cellSize * boardSize) / 2
        offsetY = (h - cellSize * boardSize) / 2
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBoard(canvas)
        drawShips(canvas)
        drawPreview(canvas)
        drawShots(canvas)
    }

    private fun drawBoard(canvas: Canvas) {
        // Draw board background
        paint.color = Color.rgb(200, 230, 255) // Light blue for water
        paint.style = Paint.Style.FILL
        canvas.drawRect(
            offsetX,
            offsetY,
            offsetX + boardSize * cellSize,
            offsetY + boardSize * cellSize,
            paint
        )

        // Draw grid
        paint.color = Color.rgb(100, 150, 200) // Darker blue for grid
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f

        for (i in 0..boardSize) {
            // Vertical lines
            canvas.drawLine(
                offsetX + i * cellSize,
                offsetY,
                offsetX + i * cellSize,
                offsetY + boardSize * cellSize,
                paint
            )
            // Horizontal lines
            canvas.drawLine(
                offsetX,
                offsetY + i * cellSize,
                offsetX + boardSize * cellSize,
                offsetY + i * cellSize,
                paint
            )
        }
    }

    private fun drawShips(canvas: Canvas) {
        paint.style = Paint.Style.FILL

        ships.forEach { ship ->
            val shipSize = ShipType.valueOf(ship.ship).size
            paint.color = when (ship.ship) {
                ShipType.Carrier.name -> Color.DKGRAY
                ShipType.Battleship.name -> Color.rgb(80, 80, 80)
                ShipType.Destroyer.name -> Color.rgb(100, 100, 100)
                ShipType.Submarine.name -> Color.rgb(120, 120, 120)
                else -> Color.rgb(140, 140, 140)
            }

            val rect = RectF(
                offsetX + ship.x * cellSize + cellSize * 0.1f,
                offsetY + ship.y * cellSize + cellSize * 0.1f,
                offsetX + (ship.x + (if (ship.orientation == "horizontal") shipSize else 1)) * cellSize - cellSize * 0.1f,
                offsetY + (ship.y + (if (ship.orientation == "vertical") shipSize else 1)) * cellSize - cellSize * 0.1f
            )
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawPreview(canvas: Canvas) {
        if (previewCells.isEmpty()) return

        paint.style = Paint.Style.FILL
        paint.color = if (isValidPreview) {
            Color.argb(128, 0, 255, 0) // Semi-transparent green
        } else {
            Color.argb(128, 255, 0, 0) // Semi-transparent red
        }

        previewCells.forEach { position ->
            val rect = RectF(
                offsetX + position.x * cellSize + cellSize * 0.1f,
                offsetY + position.y * cellSize + cellSize * 0.1f,
                offsetX + (position.x + 1) * cellSize - cellSize * 0.1f,
                offsetY + (position.y + 1) * cellSize - cellSize * 0.1f
            )
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawShots(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        
        // Draw hits
        paint.color = Color.RED
        hits.forEach { position ->
            // Draw X for hits
            val x = offsetX + (position.x + 0.5f) * cellSize
            val y = offsetY + (position.y + 0.5f) * cellSize
            val size = cellSize * 0.3f
            paint.strokeWidth = cellSize * 0.1f
            canvas.drawLine(x - size, y - size, x + size, y + size, paint)
            canvas.drawLine(x - size, y + size, x + size, y - size, paint)
        }

        // Draw misses
        paint.color = Color.WHITE
        misses.forEach { position ->
            // Draw dot for misses
            canvas.drawCircle(
                offsetX + (position.x + 0.5f) * cellSize,
                offsetY + (position.y + 0.5f) * cellSize,
                cellSize * 0.2f,
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = ((event.x - offsetX) / cellSize).toInt()
        val y = ((event.y - offsetY) / cellSize).toInt()
        
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (x in 0 until boardSize && y in 0 until boardSize) {
                    val position = Position(x, y)
                    if (position != _lastHoverPosition) {
                        _lastHoverPosition = position
                        onCellHoverListener?.invoke(position)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (x in 0 until boardSize && y in 0 until boardSize) {
                    onCellClickListener?.invoke(Position(x, y))
                    return true
                }
            }
        }
        return true
    }

    fun setShips(ships: List<Ship>) {
        this.ships = ships
        invalidate()
    }

    fun setShots(shots: List<Position>, isOpponentBoard: Boolean = false) {
        if (isOpponentBoard) {
            this.hits = shots
            this.misses = emptyList()
        } else {
            this.hits = shots
            this.misses = emptyList()
        }
        invalidate()
    }

    fun setPreview(cells: List<Position>, isValid: Boolean) {
        this.previewCells = cells
        this.isValidPreview = isValid
        invalidate()
    }

    fun clearPreview() {
        this.previewCells = emptyList()
        invalidate()
    }

    fun setOnCellClickListener(listener: ((Position) -> Unit)?) {
        onCellClickListener = listener
    }

    fun setOnCellHoverListener(listener: ((Position) -> Unit)?) {
        onCellHoverListener = listener
    }
} 
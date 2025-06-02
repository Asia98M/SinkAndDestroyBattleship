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
import com.example.sinkanddestroybattleship.data.models.Shot
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
    private var shots: List<Position> = emptyList()
    private var onCellClickListener: ((Position) -> Unit)? = null

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
        drawGrid(canvas)
        drawShips(canvas)
        drawShots(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        paint.color = Color.GRAY
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
        paint.color = Color.GRAY
        paint.style = Paint.Style.FILL

        ships.forEach { ship ->
            val shipSize = ShipType.valueOf(ship.ship).size
            val rect = RectF(
                offsetX + ship.x * cellSize,
                offsetY + ship.y * cellSize,
                offsetX + (ship.x + (if (ship.orientation == "horizontal") shipSize else 1)) * cellSize,
                offsetY + (ship.y + (if (ship.orientation == "vertical") shipSize else 1)) * cellSize
            )
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawShots(canvas: Canvas) {
        shots.forEach { position ->
            paint.color = Color.BLUE // We'll use blue for all shots since we don't know if they hit
            paint.style = Paint.Style.FILL
            canvas.drawCircle(
                offsetX + (position.x + 0.5f) * cellSize,
                offsetY + (position.y + 0.5f) * cellSize,
                cellSize * 0.3f,
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = ((event.x - offsetX) / cellSize).toInt()
            val y = ((event.y - offsetY) / cellSize).toInt()

            if (x in 0 until boardSize && y in 0 until boardSize) {
                onCellClickListener?.invoke(Position(x, y))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setShips(ships: List<Ship>) {
        this.ships = ships
        invalidate()
    }

    fun setShots(shots: List<Position>) {
        this.shots = shots
        invalidate()
    }

    fun setOnCellClickListener(listener: ((Position) -> Unit)?) {
        onCellClickListener = listener
    }
} 
package org.mattshoe.mtg.core

data class CardZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed: Boolean get() = scale > 1f
    fun pinch(factor: Float, cx: Float, cy: Float, width: Float, height: Float): CardZoom = this
    fun pan(dx: Float, dy: Float, width: Float, height: Float): CardZoom = this
    fun settled(): CardZoom = this

    companion object {
        const val MAX = 4f
    }
}

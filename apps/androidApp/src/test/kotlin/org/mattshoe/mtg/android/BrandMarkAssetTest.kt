package org.mattshoe.mtg.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * The mark in the top bar is a mark, not a tile.
 *
 * Matt, with a screenshot: "The icon looks weird as fuck why is it so
 * tiny and so much black space" — and then, correctly: "Can't you
 * name the background transparent".
 *
 * The bar was drawing `ic_launcher_foreground`, which is two separate
 * problems wearing one name. It is not a foreground: every pixel of
 * it is opaque, corners included, all of them `#0E1116`, so on a dark
 * bar it reads as a black square sitting behind the lotus. And an
 * adaptive icon's foreground reserves a safe zone — roughly a third
 * of the width on every side is deliberately empty, because the
 * launcher masks and animates it — so the art inside that square came
 * out about two thirds the size of the box it was given.
 *
 * The website has never had this. `.brand-mark` draws
 * `icons/icon-32.png`, which is transparent and whose art fills the
 * frame, and that is the same artwork at a size a top bar wants.
 *
 * This reads the asset rather than the screen: no device, no pixels
 * off a rendered node, just the resource the bar is pointed at. A
 * launcher icon turning up in a top bar is a fact about the file.
 */
@RunWith(AndroidJUnit4::class)
class BrandMarkAssetTest {

    private fun mark(): Bitmap {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val options = BitmapFactory.Options().apply { inScaled = false }
        return BitmapFactory.decodeResource(context.resources, R.drawable.brand_mark, options)
            ?: error("R.drawable.brand_mark did not decode")
    }

    @Test
    fun theMarkHasNoBackgroundBehindIt() {
        val bmp = mark()
        // All four corners, because an icon with a rounded dark plate
        // under it still has opaque middles of edges.
        listOf(
            0 to 0,
            bmp.width - 1 to 0,
            0 to bmp.height - 1,
            bmp.width - 1 to bmp.height - 1,
        ).forEach { (x, y) ->
            val alpha = bmp.getPixel(x, y) ushr 24
            assertTrue(
                alpha < 8,
                "the top bar's mark has an opaque corner at ($x, $y) — it is a tile, not a mark",
            )
        }
    }

    @Test
    fun theArtFillsTheFrameRatherThanSittingInASafeZone() {
        val bmp = mark()
        var minX = bmp.width
        var maxX = -1
        var minY = bmp.height
        var maxY = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if ((bmp.getPixel(x, y) ushr 24) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val across = (maxX - minX + 1).toFloat() / bmp.width
        val down = (maxY - minY + 1).toFloat() / bmp.height
        // An adaptive icon's foreground keeps its art inside about
        // 66%, which is why the lotus looked tiny in a 34dp box. A
        // mark drawn for a bar should reach the edges.
        assertTrue(across > 0.8f, "the mark's art is only ${(across * 100).toInt()}% of its width")
        assertTrue(down > 0.8f, "the mark's art is only ${(down * 100).toInt()}% of its height")
    }

    @Test
    fun itIsMostlyNotBackground() {
        // A sanity check on the other side: a fully transparent file
        // would pass both tests above and show nothing at all.
        val bmp = mark()
        var painted = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if ((bmp.getPixel(x, y) ushr 24) > 8) painted++
            }
        }
        val share = painted.toFloat() / (bmp.width * bmp.height)
        assertTrue(share > 0.25f, "the mark is ${(share * 100).toInt()}% ink — is it blank?")
    }
}

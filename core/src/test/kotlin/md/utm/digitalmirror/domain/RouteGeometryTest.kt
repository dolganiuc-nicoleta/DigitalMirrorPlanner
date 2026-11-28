package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.RouteGeometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {

    @Test
    fun `traseul se scrie si se citeste inapoi`() {
        val points = listOf(Point(47.01051, 28.86381), Point(47.02, 28.87), Point(47.03, 28.88))
        val back = RouteGeometry.decode(RouteGeometry.encode(points))
        assertEquals(points.size, back.size)
        assertEquals(47.01051, back.first().lat, 1e-6)
        assertEquals(28.88, back.last().lon, 1e-6)
    }

    @Test
    fun `punctele prea apropiate se rarifica dar capetele raman`() {
        val dense = (0..100).map { Point(47.0 + it * 0.000001, 28.0) } + Point(47.01, 28.0)
        val simple = RouteGeometry.simplify(dense)
        assertTrue(simple.size < 10)
        assertEquals(dense.first(), simple.first())
        assertEquals(dense.last(), simple.last())
    }

    @Test
    fun `text gol sau stricat da lista goala`() {
        assertTrue(RouteGeometry.decode(null).isEmpty())
        assertTrue(RouteGeometry.decode("abc;1,2,3").isEmpty())
    }
}

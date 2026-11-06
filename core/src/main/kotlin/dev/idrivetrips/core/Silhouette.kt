package dev.idrivetrips.core

/**
 * A zone of the top-view car picture (front at the top, driver on the left), in a box 1.0 wide and [Silhouette.HEIGHT] high.
 * The picture itself is a drawing; these zones are painted over it when the matching part is open.
 */
data class SilhouetteZone(
    val part: CarPart,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val radius: Float,
    val open: Boolean = false,
)

/** Where each door, window, the sunroof, bonnet and tailgate are on the top-view picture of the car. */
object Silhouette {
    /** Size of the picture the zones are measured on. */
    const val PICTURE_WIDTH = 400f
    const val PICTURE_HEIGHT = 819f
    const val HEIGHT = PICTURE_HEIGHT / PICTURE_WIDTH

    /** [open]: the parts that are not closed (empty = all closed). */
    fun zones(open: Collection<CarPart>): List<SilhouetteZone> {
        val isOpen = open.toSet()
        val out = ArrayList<SilhouetteZone>()
        // coordinates in picture pixels; the left side is the driver's
        fun add(part: CarPart, x: Float, y: Float, w: Float, h: Float, r: Float) {
            out += SilhouetteZone(
                part, x / PICTURE_WIDTH, y / PICTURE_WIDTH, w / PICTURE_WIDTH, h / PICTURE_WIDTH, r / PICTURE_WIDTH,
                open = part in isOpen,
            )
        }
        fun side(left: Boolean, x: Float, w: Float) = if (left) x else PICTURE_WIDTH - x - w
        add(CarPart.BONNET, 70f, 45f, 260f, 225f, 40f)
        add(CarPart.SUNROOF, 132f, 443f, 136f, 114f, 12f)
        add(CarPart.TAILGATE, 95f, 650f, 210f, 140f, 30f)
        for (left in listOf(true, false)) {
            add(if (left) CarPart.DRIVER_DOOR else CarPart.PASSENGER_DOOR, side(left, 36f, 66f), 335f, 66f, 165f, 14f)
            add(if (left) CarPart.REAR_DRIVER_DOOR else CarPart.REAR_PASSENGER_DOOR, side(left, 36f, 66f), 500f, 66f, 150f, 14f)
            add(if (left) CarPart.DRIVER_WINDOW else CarPart.PASSENGER_WINDOW, side(left, 56f, 38f), 370f, 38f, 125f, 12f)
            add(if (left) CarPart.REAR_DRIVER_WINDOW else CarPart.REAR_PASSENGER_WINDOW, side(left, 56f, 38f), 500f, 38f, 165f, 12f)
        }
        return out
    }
}

package com.yadhuChoudhary.MyRuns5

class ActivityClassifier {

    companion object {
        val ACTIVITY_LABELS = arrayOf(
            "Standing",
            "Walking",
            "Running"
        )
    }

    fun classify(features: DoubleArray): Int {
        if (features.size != 17) {
            return 0
        }

        try {
            val featureObjects = Array<Any?>(features.size) { i -> features[i] }
            val prediction = WekaClassifier.classify(featureObjects)
            return prediction.toInt().coerceIn(0, 2)
        } catch (e: Exception) {
            e.printStackTrace()
            return 0
        }
    }

    fun getActivityLabel(activityType: Int): String {
        return if (activityType in ACTIVITY_LABELS.indices) {
            ACTIVITY_LABELS[activityType]
        } else {
            "Standing"
        }
    }

    fun isReady(): Boolean {
        return true
    }
}

object WekaClassifier {

    @Throws(Exception::class)
    fun classify(i: Array<Any?>): Double {
        var p = Double.NaN
        p = N3ccc6de90(i)
        return p
    }

    private fun N3ccc6de90(i: Array<Any?>): Double {
        var p = Double.NaN

        if (i[0] == null) {
            p = 0.0
        } else if ((i[0] as Double) <= 13.390311) {
            p = 0.0
        } else if ((i[0] as Double) > 13.390311) {
            p = N4d66c0961(i)
        }

        return p
    }

    private fun N4d66c0961(i: Array<Any?>): Double {
        var p = Double.NaN

        if (i.size <= 16 || i[16] == null) {
            p = 1.0
        } else if ((i[16] as Double) <= 14.534508) {
            p = N51f13be92(i)
        } else if ((i[16] as Double) > 14.534508) {
            p = 2.0
        }

        return p
    }

    private fun N51f13be92(i: Array<Any?>): Double {
        var p = Double.NaN

        if (i[4] == null) {
            p = 1.0
        } else if ((i[4] as Double) <= 14.034383) {
            p = N60645e843(i)
        } else if ((i[4] as Double) > 14.034383) {
            p = 1.0
        }

        return p
    }

    private fun N60645e843(i: Array<Any?>): Double {
        var p = Double.NaN

        if (i[7] == null) {
            p = 1.0
        } else if ((i[7] as Double) <= 4.804712) {
            p = 1.0
        } else if ((i[7] as Double) > 4.804712) {
            p = 2.0
        }

        return p
    }
}
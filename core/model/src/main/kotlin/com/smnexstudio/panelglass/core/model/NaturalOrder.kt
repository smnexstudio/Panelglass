package com.smnexstudio.panelglass.core.model

/**
 * File-name order as people read it: runs of digits compare by value (`2` before `10`, `p9` before `p10`), the rest
 * ignoring case. Used to order picked images and CBZ entries into pages.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val ea = digitsEnd(a, i)
                val eb = digitsEnd(b, j)
                val c = compareDigits(a.substring(i, ea), b.substring(j, eb))
                if (c != 0) return c
                i = ea
                j = eb
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val c = (a.length - i).compareTo(b.length - j)
        // Equal but for case: fall back to the plain order so the sort is stable across runs.
        return if (c != 0) c else a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var e = from
        while (e < s.length && s[e].isDigit()) e++
        return e
    }

    /** By value without parsing (runs can exceed Long), then fewer leading zeros first. */
    private fun compareDigits(x: String, y: String): Int {
        val tx = x.trimStart('0')
        val ty = y.trimStart('0')
        if (tx.length != ty.length) return tx.length.compareTo(ty.length)
        val c = tx.compareTo(ty)
        return if (c != 0) c else x.length.compareTo(y.length)
    }
}

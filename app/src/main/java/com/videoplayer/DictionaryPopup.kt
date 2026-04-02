package com.videoplayer

import android.view.View
import android.widget.ScrollView
import android.widget.TextView

class DictionaryPopup(
    private val popupView: ScrollView,
    private val tvTerm: TextView,
    private val tvReading: TextView,
    private val tvFreq: TextView,
    private val tvTags: TextView,
    private val tvMeanings: TextView
) {

    fun show(entries: List<DictionaryDatabase.DictEntry>, surface: String) {
        if (entries.isEmpty()) { hide(); return }

        val best = entries.first()

        tvTerm.text = best.term
        tvReading.text = if (best.reading != best.term) best.reading else ""

        val freq = best.frequency ?: entries.firstNotNullOfOrNull { it.frequency }
        if (freq != null && freq > 0) {
            tvFreq.text = "#$freq"
            tvFreq.visibility = View.VISIBLE
        } else {
            tvFreq.visibility = View.GONE
        }

        val tags = best.tags.trim()
        if (tags.isNotEmpty()) {
            tvTags.text = formatTags(tags)
            tvTags.visibility = View.VISIBLE
        } else {
            tvTags.visibility = View.GONE
        }

        val allMeanings = entries.flatMap { it.meanings }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)

        tvMeanings.text = allMeanings.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")

        popupView.visibility = View.VISIBLE
        popupView.scrollTo(0, 0)
    }

    fun hide() {
        popupView.visibility = View.GONE
    }

    val isVisible: Boolean get() = popupView.visibility == View.VISIBLE

    private fun formatTags(tags: String): String {
        return tags.split(" ").joinToString(" ") { tag ->
            when (tag) {
                "v1" -> "ichidan"
                "v5" -> "godan"
                "vs" -> "suru"
                "vt" -> "trans."
                "vi" -> "intrans."
                "adj-i" -> "i-adj"
                "adj-na" -> "na-adj"
                "n" -> "noun"
                "adv" -> "adv"
                "exp" -> "expr"
                "prt" -> "particle"
                "conj" -> "conj"
                "int" -> "interj"
                else -> tag
            }
        }
    }
}

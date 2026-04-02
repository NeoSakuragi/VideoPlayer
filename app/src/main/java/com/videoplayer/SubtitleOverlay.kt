package com.videoplayer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

class SubtitleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SubtitleOverlay"
    }

    private var tokenizer: Tokenizer? = null
    private var onWordTap: ((String, String, List<Token>) -> Unit)? = null
    private var currentHighlight: BackgroundColorSpan? = null

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        setPadding(24, 8, 24, 24)
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT

        // Initialize tokenizer in background
        Thread {
            try {
                tokenizer = Tokenizer()
                Log.d(TAG, "Kuromoji tokenizer initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init tokenizer", e)
            }
        }.start()
    }

    fun setOnWordTapListener(listener: (term: String, baseForm: String, tokens: List<Token>) -> Unit) {
        onWordTap = listener
    }

    fun applySettings(settings: AppSettings) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize.toFloat())
        try {
            val base = Typeface.createFromAsset(context.assets, settings.fontInfo.assetPath)
            typeface = if (settings.fontBold) Typeface.create(base, Typeface.BOLD) else base
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load font: ${e.message}")
        }
    }

    fun setSubtitleText(subtitleText: String?) {
        if (subtitleText.isNullOrBlank()) {
            text = ""
            visibility = View.GONE
            return
        }

        visibility = View.VISIBLE
        val tok = tokenizer
        if (tok == null) {
            // Tokenizer not ready yet, show plain text
            text = subtitleText
            return
        }

        val tokens = tok.tokenize(subtitleText)
        val spannable = SpannableStringBuilder()

        for (token in tokens) {
            val surface = token.surface
            val start = spannable.length
            spannable.append(surface)
            val end = spannable.length

            // Make Japanese words clickable (skip pure punctuation/whitespace)
            if (isJapanese(surface)) {
                val clickSpan = object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        // Remove previous highlight
                        currentHighlight?.let { spannable.removeSpan(it) }
                        // Add highlight to tapped word
                        currentHighlight = BackgroundColorSpan(0x667986CB)
                        spannable.setSpan(currentHighlight, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text = spannable

                        val baseForm = token.baseForm ?: surface
                        Log.d(TAG, "Word tapped: $surface baseForm=$baseForm reading=${token.reading}")
                        onWordTap?.invoke(surface, baseForm, tokens)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                        ds.color = Color.WHITE
                    }
                }
                spannable.setSpan(clickSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        text = spannable
    }

    fun clearHighlight() {
        val spannable = text as? SpannableStringBuilder ?: return
        currentHighlight?.let {
            spannable.removeSpan(it)
            text = spannable
        }
        currentHighlight = null
    }

    private fun isJapanese(text: String): Boolean {
        return text.any { c ->
            Character.UnicodeBlock.of(c) in setOf(
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
                Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
                Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            )
        }
    }
}

package com.footballmodel.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Shared motion language for all equivalent screen, filter and setting transitions.
 * Keep timings and easing centralized so the UI never mixes unrelated effects.
 */
internal object UiMotion {
    const val DURATION_MS = 220L
    private const val EXIT_MS = 70L
    private const val ENTER_MS = DURATION_MS - EXIT_MS
    private const val CONTENT_ALPHA = 0.45f
    private const val OFFSET_DP = 6f

    private fun easing() = DecelerateInterpolator(1.7f)

    fun animateContentChange(view: View, offsetPx: Float, update: () -> Unit) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) {
            update()
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.animate()
            .alpha(CONTENT_ALPHA)
            .translationY(offsetPx)
            .setDuration(EXIT_MS)
            .setInterpolator(easing())
            .withEndAction {
                update()
                view.alpha = CONTENT_ALPHA
                view.translationY = offsetPx
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(easing())
                    .withLayer()
                    .start()
            }
            .start()
    }

    fun animateEntry(view: View, offsetPx: Float) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = offsetPx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(easing())
            .withLayer()
            .start()
    }

    fun animateVisibility(view: View, visible: Boolean, offsetPx: Float) {
        view.animate().cancel()
        if (visible) {
            if (view.visibility == View.VISIBLE) return
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = offsetPx
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(DURATION_MS)
                .setInterpolator(easing())
                .withLayer()
                .start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate()
                .alpha(0f)
                .translationY(offsetPx)
                .setDuration(DURATION_MS)
                .setInterpolator(easing())
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationY = 0f
                }
                .start()
        }
    }

    fun animateTextChange(view: TextView, nextText: CharSequence) {
        if (view.text == nextText) return
        view.animate().cancel()
        view.animate()
            .alpha(CONTENT_ALPHA)
            .setDuration(EXIT_MS)
            .setInterpolator(easing())
            .withEndAction {
                view.text = nextText
                view.animate()
                    .alpha(1f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(easing())
                    .start()
            }
            .start()
    }

    fun animateTextColor(view: TextView, nextColor: Int) {
        val current = view.currentTextColor
        if (current == nextColor) return
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), current, nextColor).apply {
            duration = DURATION_MS
            interpolator = easing()
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
        }
        animator.start()
    }

    fun transitionBackground(view: View, next: Drawable) {
        val previous = view.background
        if (!view.isAttachedToWindow || previous == null) {
            view.background = next
            return
        }
        val from = previous.constantState?.newDrawable()?.mutate() ?: previous
        val to = next.constantState?.newDrawable()?.mutate() ?: next
        view.background = TransitionDrawable(arrayOf(from, to)).apply {
            isCrossFadeEnabled = true
            startTransition(DURATION_MS.toInt())
        }
    }
}

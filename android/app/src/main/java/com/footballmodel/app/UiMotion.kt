package com.footballmodel.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Shared motion language for all equivalent screen, filter and setting transitions.
 * Keep timings and easing centralized so the UI never mixes unrelated effects.
 */
internal object UiMotion {
    const val DURATION_MS = 220L
    private const val CONTENT_ALPHA = 0.45f
    private val textColorAnimations = WeakHashMap<TextView, ValueAnimator>()

    private fun easing() = DecelerateInterpolator(1.7f)

    private fun animateToRest(view: View) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(DURATION_MS)
            .setInterpolator(easing())
            .withLayer()
            .start()
    }

    fun animateContentChange(view: View, offsetPx: Float, update: () -> Unit) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) {
            update()
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = CONTENT_ALPHA
        view.translationY = offsetPx
        update()
        animateToRest(view)
    }

    fun animateEntry(view: View, offsetPx: Float) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = offsetPx
        animateToRest(view)
    }

    fun animateVisibility(view: View, visible: Boolean, offsetPx: Float) {
        view.animate().cancel()
        if (visible) {
            if (view.visibility == View.VISIBLE) {
                if (view.alpha < 0.999f || kotlin.math.abs(view.translationY) > 0.5f) {
                    animateToRest(view)
                }
                return
            }
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = offsetPx
            animateToRest(view)
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
        view.alpha = CONTENT_ALPHA
        view.text = nextText
        view.animate()
            .alpha(1f)
            .setDuration(DURATION_MS)
            .setInterpolator(easing())
            .start()
    }

    fun animateTextColor(view: TextView, nextColor: Int) {
        val current = view.currentTextColor
        if (current == nextColor) return
        textColorAnimations.remove(view)?.cancel()
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), current, nextColor).apply {
            duration = DURATION_MS
            interpolator = easing()
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (textColorAnimations[view] === animation) {
                        textColorAnimations.remove(view)
                    }
                }
            })
        }
        textColorAnimations[view] = animator
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

package com.footballmodel.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Shared, restrained motion language for the whole app.
 *
 * Transitions use opacity/color and a tiny press compression; no vertical movement, bounce or overshoot.
 * Keeping the same gentle timing everywhere avoids visual jumps between screens.
 */
internal object UiMotion {
    const val DURATION_MS = 260L
    private const val CONTENT_ALPHA = 0.94f
    private const val TEXT_ALPHA = 0.88f
    private val textColorAnimations = WeakHashMap<TextView, ValueAnimator>()
    private val pressFeedbackInstalled = WeakHashMap<View, Boolean>()

    private fun easing() = DecelerateInterpolator(1.2f)

    /**
     * Install the same restrained tactile response on every clickable surface.
     * A hierarchy listener keeps newly rendered and cached screens consistent.
     */
    fun installPressFeedback(root: ViewGroup) {
        installPressFeedbackTree(root)
    }

    private fun installPressFeedbackTree(view: View) {
        if (view.isClickable && pressFeedbackInstalled.put(view, true) == null) {
            view.setOnTouchListener { touched, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touched.animate().cancel()
                        touched.animate()
                            .alpha(0.88f)
                            .scaleX(0.985f)
                            .scaleY(0.985f)
                            .setDuration(90L)
                            .setInterpolator(easing())
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touched.animate().cancel()
                        touched.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150L)
                            .setInterpolator(easing())
                            .start()
                    }
                }
                // Preserve the view's normal click, scroll and accessibility handling.
                false
            }
        }
        if (view is ViewGroup) {
            view.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    child?.let { installPressFeedbackTree(it) }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
            for (index in 0 until view.childCount) {
                installPressFeedbackTree(view.getChildAt(index))
            }
        }
    }

    private fun resetMotion(view: View) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun animateToRest(view: View, duration: Long = DURATION_MS) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(easing())
            .start()
    }

    fun animateContentChange(view: View, offsetPx: Float, update: () -> Unit) {
        view.animate().cancel()
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) {
            update()
            resetMotion(view)
            return
        }
        // A very small opacity dip is enough to soften a redraw without flashing or shifting.
        view.alpha = CONTENT_ALPHA
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        update()
        animateToRest(view)
    }

    fun animateEntry(view: View, offsetPx: Float) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = CONTENT_ALPHA
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        animateToRest(view)
    }

    fun animateVisibility(view: View, visible: Boolean, offsetPx: Float) {
        view.animate().cancel()
        if (visible) {
            if (view.visibility == View.VISIBLE) {
                if (view.alpha < 0.999f) animateToRest(view, 200L)
                return
            }
            view.visibility = View.VISIBLE
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 0f
            animateToRest(view, DURATION_MS)
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate()
                .alpha(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200L)
                .setInterpolator(easing())
                .withEndAction {
                    view.visibility = View.GONE
                    resetMotion(view)
                }
                .start()
        }
    }

    fun animateTextChange(view: TextView, nextText: CharSequence) {
        if (view.text == nextText) return
        view.animate().cancel()
        view.alpha = TEXT_ALPHA
        view.text = nextText
        view.animate()
            .alpha(1f)
            .setDuration(200L)
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

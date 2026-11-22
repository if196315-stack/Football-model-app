package com.footballmodel.app

/**
 * Shared deterministic ranking for TOP candidates in the foreground list and
 * background notifications. Candidates are expected to have passed the same
 * probability, price, EV and edge eligibility gates before ranking.
 */
internal object TopSignalRanking {
    fun fullKellyFraction(probability: Double, odds: Double): Double {
        if (!probability.isFinite() || probability !in 0.0..1.0 ||
            !odds.isFinite() || odds <= 1.0) return Double.NEGATIVE_INFINITY
        return (probability * odds - 1.0) / (odds - 1.0)
    }

    fun bestIndex(candidates: List<Pair<Double, Double>>): Int? {
        if (candidates.isEmpty()) return null
        return candidates.indices.maxByOrNull { index ->
            fullKellyFraction(candidates[index].first, candidates[index].second)
        }?.takeIf { fullKellyFraction(candidates[it].first, candidates[it].second).isFinite() }
    }
}

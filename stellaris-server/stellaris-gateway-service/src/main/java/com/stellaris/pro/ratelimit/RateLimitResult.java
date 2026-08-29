package com.stellaris.pro.ratelimit;

/** 网关可观测的限流决策，而不是只有 true/false。 */
public record RateLimitResult(boolean allowed, long remainingTokens, long retryAfterMillis,
                              String ruleId, RateLimitDimension rejectDimension, boolean degraded,
                              RateLimitRejectionCause rejectionCause) {

    public static RateLimitResult allowed(long remainingTokens, String ruleId,
                                          RateLimitDimension dimension, boolean degraded) {
        return new RateLimitResult(true, remainingTokens, 0, ruleId, dimension, degraded, null);
    }

    public static RateLimitResult rejected(long remainingTokens, long retryAfterMillis, String ruleId,
                                           RateLimitDimension dimension, boolean degraded) {
        return new RateLimitResult(false, remainingTokens, retryAfterMillis, ruleId, dimension, degraded,
                RateLimitRejectionCause.QUOTA_EXCEEDED);
    }

    public static RateLimitResult dependencyUnavailable(long retryAfterMillis, String ruleId,
                                                        RateLimitDimension dimension) {
        return new RateLimitResult(false, -1, retryAfterMillis, ruleId, dimension, true,
                RateLimitRejectionCause.DEPENDENCY_UNAVAILABLE);
    }
}

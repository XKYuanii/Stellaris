package com.stellaris.pro.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RedisTokenBucketRateLimiterTest {

    @Test
    void parsesCompleteDecisionInsteadOfBooleanOnly() {
        RateLimitRule rule = validRule();

        RateLimitResult result = RedisTokenBucketRateLimiter.parse("0,2,1500", rule, false);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingTokens()).isEqualTo(2);
        assertThat(result.retryAfterMillis()).isEqualTo(1500);
        assertThat(result.ruleId()).isEqualTo("order-user");
        assertThat(result.rejectDimension()).isEqualTo(RateLimitDimension.USER);
        assertThat(result.rejectionCause()).isEqualTo(RateLimitRejectionCause.QUOTA_EXCEEDED);
    }

    @Test
    void failClosedDependencyFailureIsDistinctFromQuotaRejection() {
        RateLimitResult result = RateLimitResult.dependencyUnavailable(1000, "order-user", RateLimitDimension.USER);

        assertThat(result.rejectionCause()).isEqualTo(RateLimitRejectionCause.DEPENDENCY_UNAVAILABLE);
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void rejectsMalformedLuaReply() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> RedisTokenBucketRateLimiter.parse("invalid", validRule(), false));
    }

    private RateLimitRule validRule() {
        RateLimitRule rule = new RateLimitRule();
        rule.setId("order-user");
        rule.setPath("/**");
        rule.setDimension(RateLimitDimension.USER);
        rule.setCapacity(3);
        rule.setRefillTokens(3);
        rule.setRefillPeriodMillis(1000);
        return rule;
    }
}

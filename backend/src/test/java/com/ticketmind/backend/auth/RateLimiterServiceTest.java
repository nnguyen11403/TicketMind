package com.ticketmind.backend.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

	@Test
	void denyAfterCapacityExceeded() {
		RateLimitProperties props = new RateLimitProperties(
				new RateLimitProperties.Bucket(3, Duration.ofMinutes(5)),
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)),
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)));
		RateLimiterService limiter = new RateLimiterService(props);

		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-a").allowed()).isTrue();
		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-a").allowed()).isTrue();
		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-a").allowed()).isTrue();

		RateLimiterService.Outcome denied = limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-a");
		assertThat(denied.allowed()).isFalse();
		assertThat(denied.retryAfterSeconds()).isGreaterThan(0);
	}

	@Test
	void differentKeysHaveIndependentBuckets() {
		RateLimitProperties props = new RateLimitProperties(
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)),
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)),
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)));
		RateLimiterService limiter = new RateLimiterService(props);

		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-a").allowed()).isTrue();
		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-b").allowed()).isTrue();
		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-a").allowed()).isFalse();
		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-b").allowed()).isFalse();
	}

	@Test
	void differentBucketKindsAreIndependentPerKey() {
		RateLimitProperties props = new RateLimitProperties(
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)),
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)),
				new RateLimitProperties.Bucket(1, Duration.ofMinutes(5)));
		RateLimiterService limiter = new RateLimiterService(props);

		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.LOGIN, "ip-a").allowed()).isTrue();
		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.REGISTER, "ip-a").allowed()).isTrue();
		assertThat(limiter.tryConsume(RateLimiterService.BucketKind.REFRESH, "ip-a").allowed()).isTrue();
	}
}

package com.ticketmind.backend.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {

	private final RateLimitProperties properties;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	public RateLimiterService(RateLimitProperties properties) {
		this.properties = properties;
	}

	public Outcome tryConsume(BucketKind kind, String key) {
		Bucket bucket = buckets.computeIfAbsent(kind + "::" + key, k -> newBucket(kind));
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			return Outcome.permitted();
		}
		long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
		return Outcome.rejected(retryAfterSeconds);
	}

	void reset() {
		buckets.clear();
	}

	private Bucket newBucket(BucketKind kind) {
		RateLimitProperties.Bucket cfg = switch (kind) {
			case LOGIN -> properties.login();
			case REGISTER -> properties.register();
			case REFRESH -> properties.refresh();
		};
		Bandwidth limit = Bandwidth.classic(
				cfg.capacity(),
				Refill.intervally(cfg.capacity(), Duration.ofSeconds(cfg.refillPeriod().toSeconds())));
		return Bucket.builder().addLimit(limit).build();
	}

	public enum BucketKind { LOGIN, REGISTER, REFRESH }

	public record Outcome(boolean allowed, long retryAfterSeconds) {
		public static Outcome permitted() { return new Outcome(true, 0L); }
		public static Outcome rejected(long retryAfterSeconds) { return new Outcome(false, retryAfterSeconds); }
	}
}

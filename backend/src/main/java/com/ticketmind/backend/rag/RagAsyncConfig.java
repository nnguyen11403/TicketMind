package com.ticketmind.backend.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class RagAsyncConfig {

	private static final Logger log = LoggerFactory.getLogger(RagAsyncConfig.class);

	/**
	 * Dedicated pool so a slow or wedged RAG service cannot starve any other
	 * async work the application picks up later.
	 *
	 * <p>The queue is bounded and overflow is dropped rather than run on the
	 * caller: the caller here is the thread that just committed a ticket, and
	 * blocking it would turn a RAG backlog into user-visible latency on
	 * {@code POST /tickets}. Shedding triage is the correct trade, the ticket
	 * is already safely persisted.
	 */
	@Bean
	public Executor ragTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(200);
		executor.setThreadNamePrefix("rag-");
		executor.setRejectedExecutionHandler((task, pool) ->
				log.warn("RAG queue full ({} queued); dropping task", pool.getQueue().size()));
		executor.initialize();
		return executor;
	}
}

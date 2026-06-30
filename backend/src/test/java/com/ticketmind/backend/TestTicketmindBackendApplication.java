package com.ticketmind.backend;

import org.springframework.boot.SpringApplication;

public class TestTicketmindBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(TicketmindBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

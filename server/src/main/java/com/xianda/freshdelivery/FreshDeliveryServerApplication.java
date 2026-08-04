package com.xianda.freshdelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FreshDeliveryServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(FreshDeliveryServerApplication.class, args);
	}

}

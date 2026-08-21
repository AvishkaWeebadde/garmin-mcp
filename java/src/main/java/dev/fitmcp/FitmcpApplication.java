package dev.fitmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the stdio MCP server.
 *
 * <p>Nothing here writes to stdout, and nothing may: stdout is the JSON-RPC wire.
 * Logging configuration lives in {@code application.properties} and routes to a file.
 */
@SpringBootApplication
public class FitmcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(FitmcpApplication.class, args);
    }
}

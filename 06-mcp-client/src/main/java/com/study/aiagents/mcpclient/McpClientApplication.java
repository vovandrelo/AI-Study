package com.study.aiagents.mcpclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class McpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args);
    }

    // TODO: реализовать на этапе 6
    //  - StdioMcpTransport — запуск MCP-сервера как процесса (npx @modelcontextprotocol/server-filesystem)
    //  - StreamableHttpMcpTransport — удалённый MCP-сервер
    //  - DefaultMcpClient + McpToolProvider → AiServices.toolProvider(...)
    //  - whitelist/blacklist инструментов
    //  - примеры серверов: filesystem, fetch, GitHub, Playwright
}

package com.study.aiagents.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    // TODO: реализовать на этапе 7
    //  - Регистрация tools через McpServer.tool(...) (например, доменные операции CRM)
    //  - Регистрация resources (доменные данные/файлы)
    //  - Регистрация prompts (готовые шаблоны)
    //  - SSE/HTTP-транспорт через mcp-spring-webmvc
    //  - Тест через MCP Inspector: npx @modelcontextprotocol/inspector
    //  - Подключение этого сервера в модуле 06-mcp-client
}

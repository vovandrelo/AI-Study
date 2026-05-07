package com.study.aiagents.langgraph;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LangGraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(LangGraphApplication.class, args);
    }

    // TODO: реализовать на этапе 10
    //  - StateGraph<State> с описанием схемы состояния
    //  - addNode("agent", ...), addNode("tools", ...)
    //  - addEdge START → agent, conditional edge agent → tools/END
    //  - Чекпоинты в MemorySaver/JdbcSaver
    //  - Прерывание (interruptBefore("tools")) и возобновление — human-in-the-loop
    //  - Визуализация графа (Mermaid)
}

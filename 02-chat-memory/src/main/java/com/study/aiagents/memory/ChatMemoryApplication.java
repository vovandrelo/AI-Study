package com.study.aiagents.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChatMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatMemoryApplication.class, args);
    }

    // TODO: реализовать на этапе 2
    //  - MessageWindowChatMemory / TokenWindowChatMemory
    //  - кастомный ChatMemoryStore (Redis/Postgres)
    //  - выделение memoryId на пользователя
}

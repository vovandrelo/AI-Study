package com.study.aiagents.multiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MultiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiAgentApplication.class, args);
    }

    // TODO: реализовать на этапе 11
    //  - Supervisor: один агент-роутер выбирает специалиста (researcher/coder/critic)
    //  - Pipeline: researcher → writer → editor (последовательно)
    //  - Debate: два proponent-агента + judge выносит вердикт
    //  - Общая память между агентами (shared state в графе)
    //  - Параллельное выполнение веток (fan-out / fan-in)
}

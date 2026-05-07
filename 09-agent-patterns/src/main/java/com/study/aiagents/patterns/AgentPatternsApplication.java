package com.study.aiagents.patterns;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentPatternsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPatternsApplication.class, args);
    }

    // TODO: реализовать на этапе 9
    //  - ReAct: ручной цикл reason → act → observe (без LangGraph)
    //  - Plan-and-Execute: планировщик создаёт список шагов, исполнитель выполняет
    //  - Reflection: агент критикует свой ответ и переписывает (N итераций)
    //  - Сравнение: один и тот же тест-кейс на трёх паттернах
}

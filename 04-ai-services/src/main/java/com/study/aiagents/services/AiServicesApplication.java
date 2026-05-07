package com.study.aiagents.services;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiServicesApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServicesApplication.class, args);
    }

    // TODO: реализовать на этапе 4
    //  - интерфейс-агент с @SystemMessage / @UserMessage / @V
    //  - AiServices.builder() с моделью, памятью, retriever-ом
    //  - регистрация бином через Spring (@AiService starter)
    //  - стриминг ответа через Flux/TokenStream
}

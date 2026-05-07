package com.study.aiagents.structured;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StructuredOutputApplication {

    public static void main(String[] args) {
        SpringApplication.run(StructuredOutputApplication.class, args);
    }

    // TODO: реализовать на этапе 3
    //  - PromptTemplate с переменными
    //  - возврат POJO через AiServices
    //  - валидация результата (jakarta.validation)
    //  - retry на невалидный JSON
}

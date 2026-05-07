package com.study.aiagents.tools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ToolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolsApplication.class, args);
    }

    // TODO: реализовать на этапе 5
    //  - класс-инструменты с @Tool, @P для описания параметров
    //  - подключение в AiServices через .tools(...)
    //  - примеры: калькулятор, погода (REST), поиск в БД, чтение файла
    //  - ToolExecutor для перехвата вызовов и логирования
    //  - обработка ошибок инструмента + retry
}

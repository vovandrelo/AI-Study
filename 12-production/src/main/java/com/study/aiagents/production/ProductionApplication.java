package com.study.aiagents.production;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductionApplication.class, args);
    }

    // TODO: реализовать на этапе 12
    //  - OpenTelemetry: трассировка цепочки агент → tools → LLM
    //  - Метрики: токены, латентность, частота ошибок (Micrometer + Actuator)
    //  - Кэш промптов и результатов (Caffeine, Redis)
    //  - Guardrails: фильтр PII на входе, jailbreak-детектор, лимиты
    //  - Eval-датасет + автоматическая проверка качества при изменении промптов
    //  - Retry/Circuit breaker для LLM-вызовов (Resilience4j)
}

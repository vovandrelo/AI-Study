package com.sbt.pprbod.xsd;

import javax.xml.validation.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Результат работы {@link XsdSchemaCreator}: скомпилированная схема и исходное содержимое всех её файлов.
 *
 * @param schema скомпилированная схема (прошла стандартную валидацию; используется для валидации XML)
 * @param files  все файлы схемы: путь к файлу -> содержимое. Главный файл идёт первым,
 *               затем подключаемые в порядке загрузки. Ключ — абсолютный путь на диске
 *               (createByFilePath) или путь в classpath (createByClassPath)
 */
public record XsdSchema(Schema schema, Map<String, byte[]> files) {

    public XsdSchema {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(files, "files");
        // Копия с сохранением порядка: главный файл остаётся первым
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }
}

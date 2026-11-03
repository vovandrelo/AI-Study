package com.sbt.pprbod.xsd;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Предназначен для создания Schema-объектов.
 * Создание схемы = стандартная валидация XSD: если объект получен, схема корректна.
 * Вместе со схемой возвращается содержимое всех её файлов (основного и подключаемых).
 */
@Slf4j
public class XsdSchemaCreator {

    /**
     * Создание объекта схемы на основании абсолютного пути.
     * @param rootXsdStrPath абсолютный путь к основному xsd-файлу.
     * @return схема и содержимое всех её файлов.
     */
    public XsdSchema createByFilePath(String rootXsdStrPath) {
        if (rootXsdStrPath == null || rootXsdStrPath.isBlank()) {
            throw new IllegalArgumentException("[FILE-PATH]. XSD file path not specified or blank.");
        }

        Path rootXsdPath = Path.of(rootXsdStrPath);
        if (!rootXsdPath.isAbsolute()) {
            String errMsg = MessageFormat.format("[FILE-PATH]. XSD file path must be absolute: {0}.", rootXsdStrPath);
            throw new IllegalArgumentException(errMsg);
        }
        rootXsdPath = rootXsdPath.normalize();
        if (!Files.isRegularFile(rootXsdPath)) {
            String errMsg = MessageFormat.format("[FILE-PATH]. XSD file {0} does not exist or is not a file.", rootXsdStrPath);
            throw new IllegalStateException(errMsg);
        }

        byte[] xsdSchema;
        try {
            xsdSchema = Files.readAllBytes(rootXsdPath);
        } catch (IOException e) {
            String errMsg = MessageFormat.format("[FILE-PATH]. Failed to read XSD file: {0}.", rootXsdStrPath);
            throw new UncheckedIOException(errMsg, e);
        }

        // Основной файл кладём сам: resolver вызывается только для подключаемых
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(rootXsdPath.toString(), xsdSchema);

        SchemaFactory factory = createSchemaFactory(new FilePathResolver(rootXsdPath.getParent(), files));
        Schema schema = newSchema(factory, xsdSchema, "[FILE-PATH]", rootXsdStrPath);
        return new XsdSchema(schema, files);
    }

    /**
     * Создание объекта схемы на основании пути в class-path.
     * @param rootXsdStrPath путь к основному xsd-файлу относительно class-path.
     * @return схема и содержимое всех её файлов.
     */
    public XsdSchema createByClassPath(String rootXsdStrPath) {
        if (rootXsdStrPath == null || rootXsdStrPath.isBlank()) {
            throw new IllegalArgumentException("[CLASS-PATH]. XSD class path not specified or blank.");
        }

        // ClassLoader ищет ресурсы без ведущего "/"
        String resourcePath = rootXsdStrPath.startsWith("/") ? rootXsdStrPath.substring(1) : rootXsdStrPath;

        byte[] xsdSchema;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                String errMsg = MessageFormat.format("[CLASS-PATH]. XSD file {0} not found.", rootXsdStrPath);
                throw new IllegalStateException(errMsg);
            }
            xsdSchema = in.readAllBytes();
        } catch (IOException e) {
            String errMsg = MessageFormat.format("[CLASS-PATH]. Failed to read XSD file: {0}.", rootXsdStrPath);
            throw new UncheckedIOException(errMsg, e);
        }

        // Основной файл кладём сам: resolver вызывается только для подключаемых
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(resourcePath, xsdSchema);

        SchemaFactory factory = createSchemaFactory(new ClassPathResolver(files));
        Schema schema = newSchema(factory, xsdSchema, "[CLASS-PATH]", rootXsdStrPath);
        return new XsdSchema(schema, files);
    }

    /** Компиляция схемы. Во время неё resolver подгружает подключаемые файлы и заполняет мапу. */
    private Schema newSchema(SchemaFactory factory, byte[] xsdSchema, String mode, String rootXsdStrPath) {
        try {
            return factory.newSchema(new StreamSource(new ByteArrayInputStream(xsdSchema)));
        } catch (SAXException e) {
            String errMsg = MessageFormat.format("{0}. Failed to create Schema for XSD: {1}.", mode, rootXsdStrPath);
            throw new RuntimeException(errMsg, e);
        }
    }

    private SchemaFactory createSchemaFactory(LSResourceResolver resolver) {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setResourceResolver(resolver);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException ignored) {
            log.debug("XMLConstants.ACCESS_EXTERNAL properties not supported.");
        }
        return factory;
    }
}

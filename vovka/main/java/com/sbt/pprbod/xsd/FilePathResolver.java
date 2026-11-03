package com.sbt.pprbod.xsd;

import com.sbt.pprbod.adapter.SimpleLSInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.xml.XMLConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Вставляет внешние XSD-схемы (&lt;xs:import ... /&gt;, &lt;xs:include ... /&gt;) в основную XSD-схему.
 * Предназначен для случаев, когда путь к схемам задан через абсолютный путь.
 * Каждый загруженный файл сохраняется в {@code loadedFiles}.
 */
@Slf4j
@RequiredArgsConstructor
public class FilePathResolver implements LSResourceResolver {

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Папка основного XSD: от неё разрешаются относительные пути. */
    private final Path baseDirectory;

    /** Сюда складываются все загруженные файлы: абсолютный путь -> содержимое. */
    private final Map<String, byte[]> loadedFiles;

    @Override
    public LSInput resolveResource(String type,
                                   String namespaceURI,
                                   String publicId,
                                   String systemId,
                                   String baseURI) {

        if (!XS.equals(type) || systemId == null || systemId.isBlank()) {
            log.debug("[FILE-PATH]. Skip resource: type: {}, system-id: {}.", type, systemId);
            return null;
        }

        Path importedSchemaPath;
        try {
            importedSchemaPath = Path.of(systemId);
        } catch (InvalidPathException e) {
            log.error("[FILE-PATH]. Skip imported schema: {}. Error: invalid path.", systemId, e);
            return null;
        }
        if (!importedSchemaPath.isAbsolute()) {
            importedSchemaPath = baseDirectory.resolve(importedSchemaPath);
        }
        importedSchemaPath = importedSchemaPath.normalize();

        if (!Files.isRegularFile(importedSchemaPath)) {
            log.error("[FILE-PATH]. Skip imported schema: {}. Error: schema does not exist or is not a file.", systemId);
            return null;
        }

        try {
            byte[] content = Files.readAllBytes(importedSchemaPath);
            loadedFiles.putIfAbsent(importedSchemaPath.toString(), content);
            return new SimpleLSInput(publicId, systemId, new ByteArrayInputStream(content));
        } catch (IOException e) {
            log.error("[FILE-PATH]. Skip imported schema: {}. Error: failed to read file.", systemId, e);
            return null;
        }
    }
}

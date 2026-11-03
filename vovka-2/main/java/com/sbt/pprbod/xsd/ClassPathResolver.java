package com.sbt.pprbod.xsd;

import com.sbt.pprbod.adapter.SimpleLSInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.xml.XMLConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Вставляет внешние XSD-схемы (&lt;xs:import ... /&gt;, &lt;xs:include ... /&gt;) в основную XSD-схему.
 * Предназначен для случаев, когда путь к схемам задан через class-path (от корня class-path).
 * Каждый загруженный файл сохраняется в {@code loadedFiles}.
 */
@Slf4j
@RequiredArgsConstructor
public class ClassPathResolver implements LSResourceResolver {

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Сюда складываются все загруженные файлы: путь в class-path -> содержимое. */
    private final Map<String, byte[]> loadedFiles;

    @Override
    public LSInput resolveResource(String type,
                                   String namespaceURI,
                                   String publicId,
                                   String systemId,
                                   String baseURI) {

        if (!XS.equals(type) || systemId == null || systemId.isBlank()) {
            log.debug("[CLASS-PATH]. Skip resource: type: {}, system-id: {}.", type, systemId);
            return null;
        }

        // ClassLoader ищет ресурсы без ведущего "/"
        String resourcePath = systemId.startsWith("/") ? systemId.substring(1) : systemId;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.error("[CLASS-PATH]. Skip imported schema: {}. Error: schema does not exist.", systemId);
                return null;
            }
            byte[] content = in.readAllBytes();
            loadedFiles.putIfAbsent(resourcePath, content);
            return new SimpleLSInput(publicId, systemId, new ByteArrayInputStream(content));
        } catch (IOException e) {
            log.error("[CLASS-PATH]. Skip imported schema: {}. Error: failed to read resource.", systemId, e);
            return null;
        }
    }
}

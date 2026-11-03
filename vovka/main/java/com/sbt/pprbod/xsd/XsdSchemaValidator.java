package com.sbt.pprbod.xsd;

import java.util.Objects;

/**
 * Полная валидация XSD-схемы в два этапа.
 *
 * <ol>
 *   <li><b>Стандартная валидация.</b> {@link XsdSchemaCreator} читает основной файл, подгружает
 *       xs:import / xs:include через resolver и компилирует схему. Если схема некорректна
 *       по стандарту XML Schema, создатель бросает исключение и второй этап не выполняется.</li>
 *   <li><b>Кастомная валидация.</b> {@link XsdFacetValidator} проверяет каждый файл схемы
 *       на наши правила (ограничения строк и чисел, запрет xs:any / xs:anyType / xs:anySimpleType).
 *       При первом нарушении бросается {@link XsdValidationException}.</li>
 * </ol>
 *
 * <p>Возвращает {@link XsdSchema}: скомпилированную схему можно сразу использовать
 * для валидации XML-документов ({@code xsdSchema.schema().newValidator()}).
 *
 * <p>Класс без состояния, потокобезопасен.
 */
public class XsdSchemaValidator {

    private final XsdSchemaCreator creator;
    private final XsdFacetValidator facetValidator;

    public XsdSchemaValidator() {
        this(new XsdSchemaCreator(), new XsdFacetValidator());
    }

    public XsdSchemaValidator(XsdSchemaCreator creator, XsdFacetValidator facetValidator) {
        this.creator = Objects.requireNonNull(creator, "creator");
        this.facetValidator = Objects.requireNonNull(facetValidator, "facetValidator");
    }

    /**
     * Валидирует схему, основной файл которой задан абсолютным путём.
     *
     * @param rootXsdStrPath абсолютный путь к основному xsd-файлу
     * @return схема и содержимое всех её файлов
     * @throws XsdValidationException при нарушении кастомных правил (этап 2)
     */
    public XsdSchema validateByFilePath(String rootXsdStrPath) {
        return validate(creator.createByFilePath(rootXsdStrPath));
    }

    /**
     * Валидирует схему, основной файл которой лежит в class-path.
     *
     * @param rootXsdStrPath путь к основному xsd-файлу относительно class-path
     * @return схема и содержимое всех её файлов
     * @throws XsdValidationException при нарушении кастомных правил (этап 2)
     */
    public XsdSchema validateByClassPath(String rootXsdStrPath) {
        return validate(creator.createByClassPath(rootXsdStrPath));
    }

    private XsdSchema validate(XsdSchema xsdSchema) {
        facetValidator.validate(xsdSchema);
        return xsdSchema;
    }
}

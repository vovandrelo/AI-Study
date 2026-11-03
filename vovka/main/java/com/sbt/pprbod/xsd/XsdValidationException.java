package com.sbt.pprbod.xsd;

/**
 * Нарушение кастомных правил XSD (второй этап валидации, {@link XsdFacetValidator}).
 * Сообщение начинается с имени файла схемы: {@code "[xsd/order.xsd]: ..."}.
 */
public class XsdValidationException extends RuntimeException {

    public XsdValidationException(String message) {
        super(message);
    }
}

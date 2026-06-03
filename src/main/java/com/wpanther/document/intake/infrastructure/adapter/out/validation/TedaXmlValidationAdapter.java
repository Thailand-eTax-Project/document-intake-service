package com.wpanther.document.intake.infrastructure.adapter.out.validation;

import com.wpanther.document.intake.domain.model.ValidationResult;
import com.wpanther.document.intake.application.port.out.XmlValidationPort;
import com.wpanther.document.intake.infrastructure.config.validation.SchemaPathConfig;
import com.wpanther.etax.validation.SchematronError;
import com.wpanther.etax.validation.SchematronValidationResult;
import com.wpanther.etax.validation.SchematronValidator;
import com.wpanther.etax.validation.SchematronValidatorImpl;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.UnmarshalException;
import jakarta.xml.bind.Unmarshaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;
import javax.xml.transform.sax.SAXSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI;
import static javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING;

/**
 * Adapter implementation of XmlValidationPort that provides three-layer validation:
 * 1. XML well-formedness (JAXB unmarshaling)
 * 2. XSD schema validation via JAXB with schema
 * 3. Schematron business rules validation via teda library
 * <p>
 * This adapter validates Thai e-Tax documents against XSD schemas and Schematron rules
 * bundled in the teda library, using JAXB for strongly-typed document processing.
 * <p>
 * The teda library uses an interface/implementation pattern where JAXB contexts
 * must be initialized with .impl packages for proper unmarshaling.
 * <p>
 * This class lives in the adapter layer because it contains framework-specific
 * dependencies (teda library, JAXB, Spring) that must not leak into the domain.
 */
@Slf4j
@Service
public class TedaXmlValidationAdapter implements XmlValidationPort {

    private static final javax.xml.parsers.DocumentBuilderFactory XML_DBF;
    private static final javax.xml.transform.TransformerFactory XML_TF;

    static {
        XML_DBF = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        XML_DBF.setNamespaceAware(true);
        try {
            XML_DBF.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new ExceptionInInitializerError("Failed to configure secure XML parser: " + e.getMessage());
        }
        XML_TF = javax.xml.transform.TransformerFactory.newInstance();
        try {
            XML_TF.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            XML_TF.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException ignored) {
            // Some XSLT implementations do not support these attributes
        }
    }

    private final SchemaPathConfig schemaPathConfig;

    // Thread-safe cached JAXB contexts and schemas (initialized once at startup)
    private final Map<TedaDocumentType, JAXBContext> jaxbContexts;
    private final Map<TedaDocumentType, Schema> schemas;
    private final SchematronValidator schematronValidator;
    // DocumentBuilderFactory.newDocumentBuilder() is not guaranteed thread-safe by the JAXP spec.
    // Each thread gets its own DocumentBuilder instance via ThreadLocal, created from its own factory
    // (with XXE protections applied). builder.reset() restores it to that initial state before reuse.
    private final ThreadLocal<DocumentBuilder> threadLocalDocumentBuilder;
    // SAXParserFactory for XXE-protected XML reading in JAXB unmarshaling
    // Thread-safe SAXParserFactory with XXE protections enabled
    private final SAXParserFactory secureSaxParserFactory;

    public TedaXmlValidationAdapter(SchemaPathConfig schemaPathConfig) {
        this.schemaPathConfig = schemaPathConfig;
        log.info("Initializing TedaXmlValidationAdapter with config...");
        long startTime = System.currentTimeMillis();

        this.jaxbContexts = initializeJaxbContexts();
        this.schemas = initializeSchemas();
        this.schematronValidator = new SchematronValidatorImpl();
        this.threadLocalDocumentBuilder = ThreadLocal.withInitial(() -> {
            try {
                return createDocumentBuilderFactory().newDocumentBuilder();
            } catch (ParserConfigurationException e) {
                throw new IllegalStateException("Failed to create DocumentBuilder", e);
            }
        });
        this.secureSaxParserFactory = createSecureSaxParserFactory();

        long duration = System.currentTimeMillis() - startTime;
        log.info("TedaXmlValidationAdapter initialized in {}ms with {} JAXB contexts and {} schemas",
            duration, jaxbContexts.size(), schemas.size());
    }

    @Override
    public String normalize(String xmlContent) {
        if (xmlContent == null) throw new IllegalArgumentException("xmlContent must not be null");
        if (xmlContent.isBlank()) return xmlContent;
        try {
            // Creates a fresh DocumentBuilder per call rather than using threadLocalDocumentBuilder,
            // because the builder is not reused across calls — thread-local is only needed when a
            // single builder instance is retained across invocations.
            org.w3c.dom.Document doc = XML_DBF.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlContent.strip())));
            stripWhitespaceOnlyTextNodes(doc);
            javax.xml.transform.Transformer transformer = XML_TF.newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");
            java.io.StringWriter out = new java.io.StringWriter();
            transformer.transform(
                new javax.xml.transform.dom.DOMSource(doc),
                new javax.xml.transform.stream.StreamResult(out));
            return out.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("XML normalization failed: invalid XML content", e);
        }
    }

    private static void stripWhitespaceOnlyTextNodes(org.w3c.dom.Node node) {
        java.util.List<org.w3c.dom.Node> toRemove = new java.util.ArrayList<>();
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    && child.getNodeValue().strip().isEmpty()) {
                toRemove.add(child);
            } else {
                stripWhitespaceOnlyTextNodes(child);
            }
        }
        toRemove.forEach(node::removeChild);
    }

    @Override
    public ValidationResult validate(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            return ValidationResult.invalid(List.of("XML content is null or empty"));
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Step 1: Unmarshal XML with JAXB (includes XSD validation and document type detection)
            ValidationErrorHandler errorHandler = new ValidationErrorHandler();
            UnmarshalResult unmarshalResult = unmarshalXml(xmlContent, null, errorHandler);

            // Collect JAXB unmarshaling errors
            errors.addAll(errorHandler.getErrors());
            warnings.addAll(errorHandler.getWarnings());

            if (!errors.isEmpty()) {
                return ValidationResult.invalid(errors, warnings);
            }

            TedaDocumentType docType = unmarshalResult.documentType;
            Object jaxbObject = unmarshalResult.jaxbObject;

            if (docType == null) {
                return ValidationResult.invalid(List.of("Unable to detect document type from XML"));
            }
            log.debug("Detected document type: {}", docType.getDomainType());

            // Step 2: Schematron business rules validation (only if XSD passed)
            if (errors.isEmpty()) {
                applySchematronValidation(xmlContent, docType, errors, warnings);
            }

        } catch (Exception e) {
            errors.add("Unexpected validation error: " + e.getMessage());
            log.error("Unexpected validation error", e);
        }

        // Return result
        if (errors.isEmpty()) {
            return warnings.isEmpty()
                ? ValidationResult.success()
                : ValidationResult.validWithWarnings(warnings);
        } else {
            return ValidationResult.invalid(errors, warnings);
        }
    }

    @Override
    public String extractDocumentNumber(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            return null;
        }

        try {
            ValidationErrorHandler errorHandler = new ValidationErrorHandler();
            UnmarshalResult result = unmarshalXml(xmlContent, null, errorHandler);

            if (result.jaxbObject == null || errorHandler.hasErrors()) {
                return null;
            }

            return extractInvoiceNumberFromJaxb(result.jaxbObject);

        } catch (Exception e) {
            log.error("Failed to extract invoice number", e);
            return null;
        }
    }

    @Override
    public com.wpanther.document.intake.domain.model.DocumentType extractDocumentType(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            return null;
        }

        try {
            ValidationErrorHandler errorHandler = new ValidationErrorHandler();
            UnmarshalResult result = unmarshalXml(xmlContent, null, errorHandler);

            if (result.jaxbObject == null || errorHandler.hasErrors()) {
                // Fallback to DOM-based detection if JAXB unmarshaling fails
                Document doc = parseXmlDom(xmlContent, new ArrayList<>());
                if (doc != null) {
                    TedaDocumentType tedaType = detectDocumentTypeFromDom(doc);
                    return tedaType != null ? tedaType.getDomainType() : null;
                }
                return null;
            }

            return result.documentType.getDomainType();

        } catch (Exception e) {
            log.error("Failed to extract document type", e);
            return null;
        }
    }

    /**
     * Result of JAXB unmarshaling containing both the object and detected document type.
     */
    private record UnmarshalResult(Object jaxbObject, TedaDocumentType documentType) {}

    /**
     * Unmarshal XML content using JAXB.
     * Handles JAXBElement wrapper and detects document type from unmarshaled object.
     *
     * @param xmlContent the XML content to unmarshal
     * @param docType optional document type hint (null to auto-detect)
     * @param errorHandler validation event handler for collecting errors
     * @return the unmarshaled object with detected document type
     * @throws JAXBException if unmarshaling fails catastrophically
     */
    private UnmarshalResult unmarshalXml(String xmlContent, TedaDocumentType docType,
                                          ValidationErrorHandler errorHandler) throws JAXBException {
        // Try each document type until we find one that works
        if (docType != null) {
            return unmarshalWithDocumentType(xmlContent, docType, errorHandler);
        }

        // Auto-detect document type by trying each context
        JAXBException lastException = null;

        for (TedaDocumentType type : TedaDocumentType.values()) {
            try {
                return unmarshalWithDocumentType(xmlContent, type, new ValidationErrorHandler());
            } catch (UnmarshalException e) {
                // Try next document type
                lastException = e;
                log.debug("Failed to unmarshal as {}: {}", type.getDomainType(), e.getMessage());
            }
        }

        // If all failed, try TaxInvoice as default with detailed error reporting
        log.warn("Could not auto-detect document type, trying TAX_INVOICE as default");
        try {
            return unmarshalWithDocumentType(xmlContent, TedaDocumentType.TAX_INVOICE, errorHandler);
        } catch (UnmarshalException e) {
            if (lastException != null) {
                throw lastException;
            }
            throw e;
        }
    }

    /**
     * Unmarshal XML content with a specific document type's JAXB context.
     * Uses XXE-protected SAX parser to prevent XML External Entity attacks.
     */
    private UnmarshalResult unmarshalWithDocumentType(String xmlContent, TedaDocumentType docType,
                                                       ValidationErrorHandler errorHandler) throws JAXBException {
        JAXBContext jaxbContext = jaxbContexts.get(docType);
        if (jaxbContext == null) {
            throw new JAXBException("No JAXB context available for " + docType.getDomainType());
        }

        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();

        // Set schema for XSD validation
        Schema schema = schemas.get(docType);
        if (schema != null) {
            unmarshaller.setSchema(schema);
        }

        // Set validation event handler
        unmarshaller.setEventHandler(errorHandler);

        // Create XXE-protected SAX source for unmarshaling
        // This prevents XXE attacks by using a secure XMLReader
        try {
            XMLReader xmlReader = secureSaxParserFactory.newSAXParser().getXMLReader();
            SAXSource saxSource = new SAXSource(xmlReader, new InputSource(new StringReader(xmlContent)));

            // Unmarshal XML using the secure SAX source
            Object result = unmarshaller.unmarshal(saxSource);

            // Handle JAXBElement wrapper (common when no @XmlRootElement annotation)
            if (result instanceof JAXBElement) {
                JAXBElement<?> jaxbElement = (JAXBElement<?>) result;
                result = jaxbElement.getValue();
            }

            // Detect document type from unmarshaled object class
            TedaDocumentType detectedType = TedaDocumentType.fromJaxbClass(result.getClass());
            if (detectedType == null) {
                detectedType = docType; // Fallback to the type we tried
            }

            return new UnmarshalResult(result, detectedType);

        } catch (SAXException | ParserConfigurationException e) {
            throw new JAXBException("Failed to create secure XML reader for XXE protection", e);
        }
    }

    /**
     * Extract invoice number from JAXB object using strategy pattern.
     * Uses TedaDocumentType-specific extractor for type-safe invoice number extraction.
     */
    private String extractInvoiceNumberFromJaxb(Object jaxbObject) {
        try {
            if (jaxbObject == null) {
                return null;
            }

            // Get document type from JAXB object class
            TedaDocumentType tedaType = TedaDocumentType.fromJaxbClass(jaxbObject.getClass());
            if (tedaType == null) {
                log.debug("Could not determine document type for invoice number extraction");
                return null;
            }

            // Use strategy to extract invoice number
            return tedaType.getInvoiceNumberExtractor().extractInvoiceNumber(jaxbObject);

        } catch (Exception e) {
            log.error("Failed to extract invoice number from JAXB object", e);
            return null;
        }
    }

    /**
     * Initialize JAXB contexts for all document types.
     * Uses implementation packages (.impl) as required by teda library's interface/implementation pattern.
     */
    private Map<TedaDocumentType, JAXBContext> initializeJaxbContexts() {
        Map<TedaDocumentType, JAXBContext> contextMap = new HashMap<>();

        for (TedaDocumentType type : TedaDocumentType.values()) {
            try {
                String contextPath = type.getImplementationContextPath();
                JAXBContext jaxbContext = JAXBContext.newInstance(contextPath);
                contextMap.put(type, jaxbContext);
                log.info("Initialized JAXB context for {} using: {}", type.getDomainType(), contextPath);
            } catch (JAXBException e) {
                log.error("Failed to initialize JAXB context for {}: {}", type.getDomainType(), e.getMessage());
            }
        }

        return contextMap;
    }

    /**
     * Initialize XSD schemas for all document types.
     * Uses URL-based loading to allow schema factory to resolve relative imports.
     */
    private Map<TedaDocumentType, Schema> initializeSchemas() {
        Map<TedaDocumentType, Schema> schemaMap = new HashMap<>();
        SchemaFactory schemaFactory = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI);

        for (TedaDocumentType type : TedaDocumentType.values()) {
            try {
                String schemaPath = schemaPathConfig.getSchemaPath(type.getDomainType().name());
                java.net.URL schemaUrl = getClass().getClassLoader().getResource(schemaPath);

                if (schemaUrl != null) {
                    Schema schema = schemaFactory.newSchema(schemaUrl);
                    schemaMap.put(type, schema);
                    log.info("Loaded XSD schema for {} from {}", type.getDomainType(), schemaPath);
                } else {
                    log.warn("Schema file not found: {}. XSD validation will be skipped for {}", schemaPath, type.getDomainType());
                }
            } catch (Exception e) {
                log.error("Failed to load schema for {}: {}", type.getDomainType(), e.getMessage());
            }
        }

        return schemaMap;
    }

    /**
     * Create document builder factory with namespace awareness and XXE protection (for fallback DOM parsing).
     */
    private DocumentBuilderFactory createDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        // XXE protection - disable external entities to prevent XXE attacks
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception e) {
            log.warn("Could not set disallow-doctype-decl feature: {}", e.getMessage());
        }

        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception e) {
            log.warn("Could not set external-general-entities feature: {}", e.getMessage());
        }

        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            log.warn("Could not set external-parameter-entities feature: {}", e.getMessage());
        }

        try {
            factory.setXIncludeAware(false);
        } catch (Exception e) {
            log.warn("Could not disable XInclude: {}", e.getMessage());
        }

        try {
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            log.warn("Could not disable entity reference expansion: {}", e.getMessage());
        }

        return factory;
    }

    /**
     * Create SAX parser factory with XXE protection for JAXB unmarshaling.
     * <p>
     * This factory creates XMLReader instances that are protected against
     * XML External Entity (XXE) attacks by disabling:
     * - DTD declarations
     * - External general entities
     * - External parameter entities
     * - XInclude processing
     * <p>
     * The secure XMLReader is passed to JAXB unmarshaller to ensure
     * all XML parsing is protected from XXE vulnerabilities.
     *
     * @return SAXParserFactory configured with XXE protection
     */
    private SAXParserFactory createSecureSaxParserFactory() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);

        // Enable secure processing
        try {
            factory.setFeature(FEATURE_SECURE_PROCESSING, true);
        } catch (Exception e) {
            log.warn("Could not set secure processing feature: {}", e.getMessage());
        }

        // XXE Protection: Disable DTD declarations
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception e) {
            log.warn("Could not set disallow-doctype-decl feature: {}", e.getMessage());
        }

        // XXE Protection: Disable external general entities
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception e) {
            log.warn("Could not set external-general-entities feature: {}", e.getMessage());
        }

        // XXE Protection: Disable external parameter entities
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            log.warn("Could not set external-parameter-entities feature: {}", e.getMessage());
        }

        // XXE Protection: Disable XInclude
        try {
            factory.setFeature("http://xml.org/sax/features/xinclude", false);
        } catch (Exception e) {
            log.warn("Could not disable XInclude feature: {}", e.getMessage());
        }

        // XXE Protection: Disable external entities loading
        try {
            factory.setFeature("http://xml.org/sax/features/external-entity-apis", false);
        } catch (ParserConfigurationException | SAXException e) {
            log.warn("Could not disable external-entity-apis feature: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Could not disable external-entity-apis feature: {}", e.getMessage());
        }

        log.info("Created secure SAXParserFactory with XXE protection enabled");
        return factory;
    }

    /**
     * Parse XML content into DOM Document (fallback for document type detection).
     */
    private Document parseXmlDom(String xmlContent, List<String> errors) {
        try {
            DocumentBuilder builder = threadLocalDocumentBuilder.get();
            builder.reset();
            return builder.parse(new org.xml.sax.InputSource(new StringReader(xmlContent)));
        } catch (Exception e) {
            errors.add("Failed to parse XML: " + e.getMessage());
            return null;
        }
    }

    /**
     * Detect document type from DOM document namespace or root element (fallback).
     * Returns null if document type cannot be detected.
     */
    private TedaDocumentType detectDocumentTypeFromDom(Document doc) {
        String namespaceUri = doc.getDocumentElement().getNamespaceURI();
        String localName = doc.getDocumentElement().getLocalName();

        // Try namespace first
        TedaDocumentType type = TedaDocumentType.fromNamespaceUri(namespaceUri);
        if (type != null) {
            return type;
        }

        // Fallback to root element name
        type = TedaDocumentType.fromRootElementName(localName);
        if (type != null) {
            return type;
        }

        // Cannot detect document type - return null to indicate failure
        log.debug("Could not detect document type from DOM (namespace: {}, root: {})",
            namespaceUri, localName);
        return null;
    }

    /**
     * Apply Schematron business rules validation using teda library.
     */
    private void applySchematronValidation(String xmlContent, TedaDocumentType docType,
                                           List<String> errors, List<String> warnings) {
        try {
            var schematronType = docType.toDocumentSchematron();

            // Skip validation for document types with empty Schematron rules
            if (schematronType.isEmptySchematron()) {
                log.debug("Skipping Schematron validation for {} (empty ruleset)", docType.getDomainType());
                return;
            }

            SchematronValidationResult schematronResult =
                schematronValidator.validate(xmlContent, schematronType);

            // Convert Schematron errors to ValidationResult format
            if (schematronResult.hasErrors()) {
                for (SchematronError error : schematronResult.getErrors()) {
                    errors.add(formatSchematronMessage(error));
                }
            }

            // Convert Schematron warnings to ValidationResult format
            if (schematronResult.hasWarnings()) {
                for (SchematronError warning : schematronResult.getWarnings()) {
                    warnings.add(formatSchematronMessage(warning));
                }
            }

            log.debug("Schematron validation completed for {}: {} errors, {} warnings",
                docType.getDomainType(), schematronResult.getErrors().size(), schematronResult.getWarnings().size());

        } catch (Exception e) {
            log.error("Schematron validation failed for {}", docType.getDomainType(), e);
            warnings.add("Schematron validation skipped due to error: " + e.getMessage());
        }
    }

    /**
     * Format a SchematronError into a human-readable message.
     */
    private String formatSchematronMessage(SchematronError error) {
        StringBuilder sb = new StringBuilder();

        // Add rule ID if present
        if (error.getRuleId() != null && !error.getRuleId().isEmpty()) {
            sb.append("[").append(error.getRuleId()).append("] ");
        }

        // Add XPath location if present
        if (error.getLocation() != null && !error.getLocation().isEmpty()) {
            sb.append("at ").append(error.getLocation()).append(": ");
        }

        // Add message
        sb.append(error.getMessage());

        return sb.toString();
    }
}

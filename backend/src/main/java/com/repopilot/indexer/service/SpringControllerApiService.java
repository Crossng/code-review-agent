package com.repopilot.indexer.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.repopilot.common.ApiException;
import com.repopilot.project.domain.Project;
import com.repopilot.project.dto.ProjectControllerApiParameterResponse;
import com.repopilot.project.dto.ProjectControllerApiResponse;
import com.repopilot.project.dto.ProjectControllerDownstreamCallResponse;
import com.repopilot.project.dto.ProjectControllerRiskHintResponse;
import com.repopilot.project.dto.ProjectControllerServiceCallResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SpringControllerApiService {

    private final JavaParser javaParser;

    public SpringControllerApiService() {
        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(parserConfiguration);
    }

    public List<ProjectControllerApiResponse> listControllerApis(Project project) {
        Path root = projectPath(project);
        if (!Files.exists(root)) {
            return List.of();
        }
        List<ParsedJavaFile> parsedFiles = javaFiles(root).stream()
                .flatMap(file -> parseFile(root, file).map(unit -> new ParsedJavaFile(file, unit)).stream())
                .toList();
        Map<String, JavaTypeInfo> typeIndex = typeIndex(parsedFiles);
        List<ProjectControllerApiResponse> apis = new ArrayList<>();
        for (ParsedJavaFile parsedFile : parsedFiles) {
            apis.addAll(apisFromUnit(root, parsedFile.file(), parsedFile.unit(), typeIndex));
        }
        return apis.stream()
                .sorted(Comparator
                        .comparing(ProjectControllerApiResponse::path)
                        .thenComparing(ProjectControllerApiResponse::httpMethod)
                        .thenComparing(ProjectControllerApiResponse::qualifiedControllerName)
                        .thenComparing(ProjectControllerApiResponse::methodName))
                .toList();
    }

    private List<ProjectControllerApiResponse> apisFromUnit(
            Path root,
            Path file,
            CompilationUnit unit,
            Map<String, JavaTypeInfo> typeIndex
    ) {
        String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getName().asString()).orElse("");
        List<ProjectControllerApiResponse> apis = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            if (!isController(type)) {
                continue;
            }
            String controllerName = type.getNameAsString();
            String qualifiedName = packageName.isBlank() ? controllerName : packageName + "." + controllerName;
            List<String> classPaths = mappingPaths(type).orElse(List.of(""));
            Map<String, String> serviceFields = serviceFields(type);
            for (MethodDeclaration method : type.findAll(MethodDeclaration.class)) {
                MappingInfo mapping = mapping(method).orElse(null);
                if (mapping == null) {
                    continue;
                }
                for (String classPath : classPaths) {
                    for (String methodPath : mapping.paths()) {
                        List<ProjectControllerApiParameterResponse> parameters = parameters(method);
                        List<String> securityAnnotations = securityAnnotations(type, method);
                        List<ProjectControllerRiskHintResponse> riskHints = riskHints(
                                mapping.httpMethod(),
                                method,
                                parameters,
                                securityAnnotations,
                                typeIndex
                        );
                        int riskScore = riskScore(riskHints);
                        apis.add(new ProjectControllerApiResponse(
                                root.relativize(file).toString(),
                                controllerName,
                                qualifiedName,
                                method.getNameAsString(),
                                mapping.httpMethod(),
                                combinePaths(classPath, methodPath),
                                requestType(method),
                                parameters,
                                serviceCalls(method, serviceFields, typeIndex),
                                method.getType().asString(),
                                securityAnnotations,
                                riskScore,
                                riskLevel(riskScore),
                                riskHints,
                                startLine(method),
                                endLine(method)
                        ));
                    }
                }
            }
        }
        return apis;
    }

    private Map<String, JavaTypeInfo> typeIndex(List<ParsedJavaFile> parsedFiles) {
        Map<String, JavaTypeInfo> index = new LinkedHashMap<>();
        for (ParsedJavaFile parsedFile : parsedFiles) {
            String packageName = parsedFile.unit()
                    .getPackageDeclaration()
                    .map(declaration -> declaration.getName().asString())
                    .orElse("");
            for (TypeDeclaration<?> topLevelType : parsedFile.unit().getTypes()) {
                index.putIfAbsent(topLevelType.getNameAsString(), new JavaTypeInfo(parsedFile.file(), packageName, topLevelType));
                for (TypeDeclaration<?> nestedType : topLevelType.findAll(TypeDeclaration.class)) {
                    index.putIfAbsent(nestedType.getNameAsString(), new JavaTypeInfo(parsedFile.file(), packageName, nestedType));
                }
            }
        }
        return index;
    }

    private Optional<CompilationUnit> parseFile(Path root, Path file) {
        try {
            return javaParser.parse(file).getResult();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CONTROLLER_API_PARSE_FAILED",
                    "Failed to parse " + root.relativize(file) + ": " + exception.getMessage());
        }
    }

    private List<Path> javaFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isGitInternalPath(root, path))
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CONTROLLER_API_SCAN_FAILED", exception.getMessage());
        }
    }

    private Optional<MappingInfo> mapping(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if (annotationMatches(name, "GetMapping")) {
                return Optional.of(new MappingInfo("GET", paths(annotation)));
            }
            if (annotationMatches(name, "PostMapping")) {
                return Optional.of(new MappingInfo("POST", paths(annotation)));
            }
            if (annotationMatches(name, "PutMapping")) {
                return Optional.of(new MappingInfo("PUT", paths(annotation)));
            }
            if (annotationMatches(name, "DeleteMapping")) {
                return Optional.of(new MappingInfo("DELETE", paths(annotation)));
            }
            if (annotationMatches(name, "PatchMapping")) {
                return Optional.of(new MappingInfo("PATCH", paths(annotation)));
            }
            if (annotationMatches(name, "RequestMapping")) {
                return Optional.of(new MappingInfo(requestMethod(annotation), paths(annotation)));
            }
        }
        return Optional.empty();
    }

    private Optional<List<String>> mappingPaths(TypeDeclaration<?> type) {
        return type.getAnnotations()
                .stream()
                .filter(annotation -> annotationMatches(annotation.getNameAsString(), "RequestMapping"))
                .findFirst()
                .map(this::paths);
    }

    private List<String> paths(AnnotationExpr annotation) {
        List<String> paths = values(annotation, "path");
        if (paths.isEmpty()) {
            paths = values(annotation, "value");
        }
        if (paths.isEmpty() && annotation instanceof SingleMemberAnnotationExpr single) {
            paths = expressionValues(single.getMemberValue());
        }
        return paths.isEmpty() ? List.of("") : paths;
    }

    private List<String> values(AnnotationExpr annotation, String key) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) {
            return List.of();
        }
        return normal.getPairs()
                .stream()
                .filter(pair -> pair.getNameAsString().equals(key))
                .findFirst()
                .map(MemberValuePair::getValue)
                .map(this::expressionValues)
                .orElse(List.of());
    }

    private List<String> expressionValues(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return List.of(expression.asStringLiteralExpr().asString());
        }
        if (expression.isArrayInitializerExpr()) {
            ArrayInitializerExpr array = expression.asArrayInitializerExpr();
            return array.getValues().stream().flatMap(value -> expressionValues(value).stream()).toList();
        }
        return List.of(expression.toString());
    }

    private String requestMethod(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    return requestMethodValue(pair.getValue());
                }
            }
        }
        return "REQUEST";
    }

    private String requestMethodValue(Expression expression) {
        if (expression.isArrayInitializerExpr()) {
            return expression.asArrayInitializerExpr()
                    .getValues()
                    .stream()
                    .findFirst()
                    .map(this::requestMethodValue)
                    .orElse("REQUEST");
        }
        String value = expression.toString();
        int dot = value.lastIndexOf('.');
        return (dot >= 0 ? value.substring(dot + 1) : value).toUpperCase(Locale.ROOT);
    }

    private boolean isController(TypeDeclaration<?> type) {
        return type.getAnnotations()
                .stream()
                .map(annotation -> annotation.getNameAsString())
                .anyMatch(name -> annotationMatches(name, "RestController") || annotationMatches(name, "Controller"));
    }

    private String requestType(MethodDeclaration method) {
        return method.getParameters()
                .stream()
                .filter(parameter -> findAnnotation(parameter, "RequestBody").isPresent())
                .findFirst()
                .map(parameter -> parameter.getType().asString())
                .orElse(null);
    }

    private List<ProjectControllerApiParameterResponse> parameters(MethodDeclaration method) {
        return method.getParameters()
                .stream()
                .map(this::parameter)
                .toList();
    }

    private Map<String, String> serviceFields(TypeDeclaration<?> type) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldDeclaration field : type.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator variable : field.getVariables()) {
                String serviceType = variable.getType().asString();
                if (simpleTypeName(serviceType).endsWith("Service")) {
                    fields.put(variable.getNameAsString(), serviceType);
                }
            }
        }
        return fields;
    }

    private List<ProjectControllerServiceCallResponse> serviceCalls(
            MethodDeclaration method,
            Map<String, String> serviceFields,
            Map<String, JavaTypeInfo> typeIndex
    ) {
        if (serviceFields.isEmpty()) {
            return List.of();
        }
        return method.findAll(MethodCallExpr.class)
                .stream()
                .flatMap(call -> serviceCall(call, serviceFields, typeIndex).stream())
                .toList();
    }

    private Optional<ProjectControllerServiceCallResponse> serviceCall(
            MethodCallExpr call,
            Map<String, String> serviceFields,
            Map<String, JavaTypeInfo> typeIndex
    ) {
        Optional<String> receiverName = call.getScope().flatMap(this::receiverName);
        if (receiverName.isEmpty()) {
            return Optional.empty();
        }
        String serviceType = serviceFields.get(receiverName.get());
        if (serviceType == null) {
            return Optional.empty();
        }
        return Optional.of(new ProjectControllerServiceCallResponse(
                receiverName.get(),
                serviceType,
                call.getNameAsString(),
                startLine(call),
                downstreamCalls(serviceType, call.getNameAsString(), typeIndex)
        ));
    }

    private List<ProjectControllerDownstreamCallResponse> downstreamCalls(
            String serviceType,
            String methodName,
            Map<String, JavaTypeInfo> typeIndex
    ) {
        JavaTypeInfo serviceInfo = typeIndex.get(simpleTypeName(serviceType));
        if (serviceInfo == null) {
            return List.of();
        }
        Optional<MethodDeclaration> serviceMethod = serviceInfo.type().findAll(MethodDeclaration.class)
                .stream()
                .filter(method -> method.getNameAsString().equals(methodName))
                .findFirst();
        if (serviceMethod.isEmpty()) {
            return List.of();
        }
        Map<String, String> downstreamFields = downstreamFields(serviceInfo.type());
        if (downstreamFields.isEmpty()) {
            return List.of();
        }
        return serviceMethod.get().findAll(MethodCallExpr.class)
                .stream()
                .flatMap(call -> downstreamCall(call, downstreamFields).stream())
                .toList();
    }

    private Map<String, String> downstreamFields(TypeDeclaration<?> type) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldDeclaration field : type.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator variable : field.getVariables()) {
                String componentType = variable.getType().asString();
                String simpleType = simpleTypeName(componentType);
                if (simpleType.endsWith("Mapper") || simpleType.endsWith("Repository")) {
                    fields.put(variable.getNameAsString(), componentType);
                }
            }
        }
        return fields;
    }

    private Optional<ProjectControllerDownstreamCallResponse> downstreamCall(
            MethodCallExpr call,
            Map<String, String> downstreamFields
    ) {
        Optional<String> receiverName = call.getScope().flatMap(this::receiverName);
        if (receiverName.isEmpty()) {
            return Optional.empty();
        }
        String componentType = downstreamFields.get(receiverName.get());
        if (componentType == null) {
            return Optional.empty();
        }
        return Optional.of(new ProjectControllerDownstreamCallResponse(
                receiverName.get(),
                componentType,
                call.getNameAsString(),
                startLine(call)
        ));
    }

    private Optional<String> receiverName(Expression expression) {
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        if (expression.isFieldAccessExpr()) {
            return Optional.of(expression.asFieldAccessExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private ProjectControllerApiParameterResponse parameter(Parameter parameter) {
        Optional<AnnotationExpr> pathVariable = findAnnotation(parameter, "PathVariable");
        if (pathVariable.isPresent()) {
            return parameterResponse(parameter, pathVariable.get(), "PATH", true);
        }
        Optional<AnnotationExpr> requestParam = findAnnotation(parameter, "RequestParam");
        if (requestParam.isPresent()) {
            AnnotationExpr annotation = requestParam.get();
            return parameterResponse(parameter, annotation, "QUERY", required(annotation, true));
        }
        Optional<AnnotationExpr> requestBody = findAnnotation(parameter, "RequestBody");
        if (requestBody.isPresent()) {
            AnnotationExpr annotation = requestBody.get();
            return parameterResponse(parameter, annotation, "BODY", required(annotation, true));
        }
        Optional<AnnotationExpr> requestHeader = findAnnotation(parameter, "RequestHeader");
        if (requestHeader.isPresent()) {
            AnnotationExpr annotation = requestHeader.get();
            return parameterResponse(parameter, annotation, "HEADER", required(annotation, true));
        }
        return new ProjectControllerApiParameterResponse(
                parameter.getNameAsString(),
                "UNKNOWN",
                parameter.getType().asString(),
                false,
                null
        );
    }

    private ProjectControllerApiParameterResponse parameterResponse(
            Parameter parameter,
            AnnotationExpr annotation,
            String source,
            boolean required
    ) {
        return new ProjectControllerApiParameterResponse(
                parameterName(parameter, annotation),
                source,
                parameter.getType().asString(),
                required,
                singleValue(annotation, "defaultValue").orElse(null)
        );
    }

    private String parameterName(Parameter parameter, AnnotationExpr annotation) {
        return singleValue(annotation, "name")
                .filter(value -> !value.isBlank())
                .or(() -> singleValue(annotation, "value"))
                .filter(value -> !value.isBlank())
                .orElse(parameter.getNameAsString());
    }

    private boolean required(AnnotationExpr annotation, boolean defaultValue) {
        return singleValue(annotation, "required")
                .map(Boolean::parseBoolean)
                .or(() -> singleValue(annotation, "defaultValue").map(ignored -> false))
                .orElse(defaultValue);
    }

    private List<ProjectControllerRiskHintResponse> riskHints(
            String httpMethod,
            MethodDeclaration method,
            List<ProjectControllerApiParameterResponse> parameters,
            List<String> securityAnnotations,
            Map<String, JavaTypeInfo> typeIndex
    ) {
        List<ProjectControllerRiskHintResponse> hints = new ArrayList<>();
        boolean mutating = isMutatingMethod(httpMethod);
        boolean secured = !securityAnnotations.isEmpty();
        if (!secured) {
            hints.add(new ProjectControllerRiskHintResponse(
                    mutating ? "HIGH" : "MEDIUM",
                    mutating ? "UNGUARDED_MUTATION" : "NO_SECURITY_ANNOTATION",
                    mutating
                            ? "Mutating endpoint has no recognized method or class security annotation."
                            : "Endpoint has no recognized method or class security annotation."
            ));
        }
        boolean hasBody = parameters.stream().anyMatch(parameter -> parameter.source().equals("BODY"));
        if (mutating && !hasBody) {
            hints.add(new ProjectControllerRiskHintResponse(
                    "MEDIUM",
                    "MUTATION_WITHOUT_BODY",
                    "Mutating endpoint does not declare a request body parameter."
            ));
        }
        parameters.stream()
                .filter(parameter -> parameter.source().equals("BODY") && !parameter.required())
                .findFirst()
                .ifPresent(parameter -> hints.add(new ProjectControllerRiskHintResponse(
                        "LOW",
                        "OPTIONAL_REQUEST_BODY",
                        "Request body parameter is optional; verify null handling and validation."
                )));
        for (Parameter parameter : method.getParameters()) {
            findAnnotation(parameter, "RequestBody").ifPresent(annotation ->
                    addBodyValidationHints(hints, parameter, typeIndex)
            );
            findAnnotation(parameter, "RequestParam").ifPresent(annotation ->
                    addQueryParameterRiskHints(hints, parameter, annotation)
            );
        }
        if (parameters.stream().anyMatch(parameter -> parameter.source().equals("UNKNOWN"))) {
            hints.add(new ProjectControllerRiskHintResponse(
                    "LOW",
                    "UNCLASSIFIED_PARAMETER",
                    "One or more parameters are not annotated as path, query, header, or body inputs."
            ));
        }
        return hints;
    }

    private int riskScore(List<ProjectControllerRiskHintResponse> hints) {
        int score = hints.stream()
                .mapToInt(hint -> switch (hint.severity()) {
                    case "HIGH" -> 70;
                    case "MEDIUM" -> 35;
                    case "LOW" -> 10;
                    default -> 0;
                })
                .sum();
        return Math.min(score, 100);
    }

    private String riskLevel(int riskScore) {
        if (riskScore >= 70) {
            return "HIGH";
        }
        if (riskScore >= 30) {
            return "MEDIUM";
        }
        if (riskScore > 0) {
            return "LOW";
        }
        return "NONE";
    }

    private void addBodyValidationHints(
            List<ProjectControllerRiskHintResponse> hints,
            Parameter parameter,
            Map<String, JavaTypeInfo> typeIndex
    ) {
        String bodyType = parameter.getType().asString();
        if (!hasAnyAnnotation(parameter.getAnnotations(), "Valid", "Validated")) {
            hints.add(new ProjectControllerRiskHintResponse(
                    "MEDIUM",
                    "BODY_WITHOUT_VALIDATION",
                    "Request body " + bodyType + " is not annotated with @Valid or @Validated."
            ));
        }
        JavaTypeInfo bodyTypeInfo = typeIndex.get(simpleTypeName(bodyType));
        if (bodyTypeInfo != null) {
            TypeDeclaration<?> bodyTypeDeclaration = bodyTypeInfo.type();
            List<String> fieldsWithoutConstraints = fieldNamesWithoutValidationConstraints(bodyTypeDeclaration);
            if (!hasValidationConstraints(bodyTypeDeclaration)) {
                hints.add(new ProjectControllerRiskHintResponse(
                        "MEDIUM",
                        "BODY_TYPE_WITHOUT_CONSTRAINTS",
                        "Request body type " + bodyType
                                + " has no recognized validation constraint annotations"
                                + fieldDetailsSuffix(fieldsWithoutConstraints),
                        fieldsWithoutConstraints
                ));
            } else if (!fieldsWithoutConstraints.isEmpty()) {
                hints.add(new ProjectControllerRiskHintResponse(
                        "LOW",
                        "BODY_FIELDS_WITHOUT_CONSTRAINTS",
                        "Request body type " + bodyType
                                + " has fields without recognized validation constraints"
                                + fieldDetailsSuffix(fieldsWithoutConstraints),
                        fieldsWithoutConstraints
                ));
            }
        }
    }

    private void addQueryParameterRiskHints(
            List<ProjectControllerRiskHintResponse> hints,
            Parameter parameter,
            AnnotationExpr annotation
    ) {
        if (!isBoundedQueryCandidate(parameter, annotation)) {
            return;
        }
        List<String> missingBounds = missingQueryBoundDetails(parameter, annotation);
        if (missingBounds.isEmpty()) {
            return;
        }
        hints.add(new ProjectControllerRiskHintResponse(
                "LOW",
                "QUERY_PARAMETER_WITHOUT_BOUNDS",
                queryBoundMessage(parameterName(parameter, annotation), missingBounds),
                missingBounds
        ));
    }

    private List<String> missingQueryBoundDetails(Parameter parameter, AnnotationExpr annotation) {
        String parameterName = parameterName(parameter, annotation);
        List<String> missingBounds = new ArrayList<>();
        if (!hasAnyAnnotation(parameter.getAnnotations(), "Min", "Positive", "PositiveOrZero", "DecimalMin", "Range")) {
            missingBounds.add(parameterName + ": missing lower bound");
        }
        if (!hasAnyAnnotation(parameter.getAnnotations(), "Max", "DecimalMax", "Range")) {
            missingBounds.add(parameterName + ": missing upper bound");
        }
        return missingBounds;
    }

    private String queryBoundMessage(String parameterName, List<String> missingBounds) {
        boolean missingLower = missingBounds.stream().anyMatch(bound -> bound.contains("lower"));
        boolean missingUpper = missingBounds.stream().anyMatch(bound -> bound.contains("upper"));
        if (missingLower && missingUpper) {
            return "Numeric query parameter " + parameterName + " is missing lower and upper bound annotations.";
        }
        if (missingLower) {
            return "Numeric query parameter " + parameterName + " is missing a lower bound annotation.";
        }
        return "Numeric query parameter " + parameterName + " is missing an upper bound annotation.";
    }

    private boolean isBoundedQueryCandidate(Parameter parameter, AnnotationExpr annotation) {
        String parameterName = parameterName(parameter, annotation).toLowerCase(Locale.ROOT);
        String parameterType = simpleTypeName(parameter.getType().asString()).toLowerCase(Locale.ROOT);
        boolean paginationName = parameterName.equals("page")
                || parameterName.equals("size")
                || parameterName.equals("limit")
                || parameterName.equals("offset")
                || parameterName.equals("pagesize")
                || parameterName.endsWith("limit")
                || parameterName.endsWith("size");
        boolean numericType = parameterType.equals("int")
                || parameterType.equals("integer")
                || parameterType.equals("long")
                || parameterType.equals("short")
                || parameterType.equals("double")
                || parameterType.equals("float")
                || parameterType.equals("bigdecimal")
                || parameterType.equals("biginteger");
        return paginationName && numericType;
    }

    private boolean hasValidationConstraints(TypeDeclaration<?> type) {
        if (hasAnyAnnotation(type.getAnnotations(), validationConstraintNames())) {
            return true;
        }
        return directFields(type)
                .stream()
                .anyMatch(field -> hasAnyAnnotation(field.getAnnotations(), validationConstraintNames()));
    }

    private List<String> fieldNamesWithoutValidationConstraints(TypeDeclaration<?> type) {
        if (hasAnyAnnotation(type.getAnnotations(), validationConstraintNames())) {
            return List.of();
        }
        return directFields(type)
                .stream()
                .filter(field -> !hasAnyAnnotation(field.getAnnotations(), validationConstraintNames()))
                .flatMap(field -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .sorted()
                .toList();
    }

    private List<FieldDeclaration> directFields(TypeDeclaration<?> type) {
        return type.getMembers()
                .stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .toList();
    }

    private String fieldDetailsSuffix(List<String> fieldNames) {
        if (fieldNames.isEmpty()) {
            return ".";
        }
        List<String> preview = fieldNames.stream().limit(5).toList();
        String suffix = fieldNames.size() > preview.size()
                ? ", +" + (fieldNames.size() - preview.size()) + " more"
                : "";
        return ": " + String.join(", ", preview) + suffix + ".";
    }

    private boolean hasAnyAnnotation(List<AnnotationExpr> annotations, List<String> expectedNames) {
        return annotations.stream()
                .map(annotation -> annotation.getNameAsString())
                .anyMatch(name -> expectedNames.stream().anyMatch(expected -> annotationMatches(name, expected)));
    }

    private boolean hasAnyAnnotation(List<AnnotationExpr> annotations, String... expectedNames) {
        return hasAnyAnnotation(annotations, List.of(expectedNames));
    }

    private List<String> validationConstraintNames() {
        return List.of(
                "NotNull",
                "NotBlank",
                "NotEmpty",
                "Size",
                "Min",
                "Max",
                "Positive",
                "PositiveOrZero",
                "Negative",
                "NegativeOrZero",
                "Pattern",
                "Email",
                "DecimalMin",
                "DecimalMax",
                "Digits",
                "AssertTrue",
                "AssertFalse",
                "Past",
                "PastOrPresent",
                "Future",
                "FutureOrPresent",
                "Valid"
        );
    }

    private boolean isMutatingMethod(String httpMethod) {
        return httpMethod.equals("POST")
                || httpMethod.equals("PUT")
                || httpMethod.equals("PATCH")
                || httpMethod.equals("DELETE");
    }

    private List<String> securityAnnotations(TypeDeclaration<?> type, MethodDeclaration method) {
        List<String> annotations = new ArrayList<>();
        addSecurityAnnotations(annotations, type.getAnnotations());
        addSecurityAnnotations(annotations, method.getAnnotations());
        return annotations;
    }

    private void addSecurityAnnotations(List<String> annotations, List<AnnotationExpr> candidates) {
        for (AnnotationExpr annotation : candidates) {
            String name = annotation.getNameAsString();
            if ((annotationMatches(name, "PreAuthorize")
                    || annotationMatches(name, "PostAuthorize")
                    || annotationMatches(name, "Secured")
                    || annotationMatches(name, "RolesAllowed"))
                    && !annotations.contains(simpleAnnotationName(name))) {
                annotations.add(simpleAnnotationName(name));
            }
        }
    }

    private Optional<AnnotationExpr> findAnnotation(Parameter parameter, String expected) {
        return parameter.getAnnotations()
                .stream()
                .filter(annotation -> annotationMatches(annotation.getNameAsString(), expected))
                .findFirst();
    }

    private boolean annotationMatches(String name, String expected) {
        return name.equals(expected) || name.endsWith("." + expected);
    }

    private Optional<String> singleValue(AnnotationExpr annotation, String key) {
        List<String> found = values(annotation, key);
        if (!found.isEmpty()) {
            return Optional.of(found.get(0));
        }
        if ((key.equals("value") || key.equals("name")) && annotation instanceof SingleMemberAnnotationExpr single) {
            List<String> values = expressionValues(single.getMemberValue());
            if (!values.isEmpty()) {
                return Optional.of(values.get(0));
            }
        }
        return Optional.empty();
    }

    private String simpleAnnotationName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private String simpleTypeName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }

    private String combinePaths(String classPath, String methodPath) {
        String combined = normalizePath(classPath) + normalizePath(methodPath);
        if (combined.isBlank()) {
            return "/";
        }
        return combined.replaceAll("/{2,}", "/");
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private Integer startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(null);
    }

    private Integer endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(null);
    }

    private Path projectPath(Project project) {
        if (project.getLocalPath() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_WORKSPACE_NOT_READY", "Project workspace path is not initialized");
        }
        return Path.of(project.getLocalPath()).toAbsolutePath().normalize();
    }

    private boolean isGitInternalPath(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0 && ".git".equals(relative.getName(0).toString());
    }

    private record ParsedJavaFile(Path file, CompilationUnit unit) {
    }

    private record JavaTypeInfo(Path file, String packageName, TypeDeclaration<?> type) {
    }

    private record MappingInfo(String httpMethod, List<String> paths) {
    }
}

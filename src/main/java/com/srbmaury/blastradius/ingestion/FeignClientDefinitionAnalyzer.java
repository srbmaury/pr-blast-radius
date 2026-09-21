package com.srbmaury.blastradius.ingestion;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.srbmaury.blastradius.domain.StaticOutboundCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FeignClientDefinitionAnalyzer {

    private static final Map<String, String> SHORT_MAPPING_METHODS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    private static final Pattern REQUEST_METHOD =
            Pattern.compile("RequestMethod\\.([A-Z]+)");

    public Optional<StaticOutboundCall> resolve(
            String source,
            FeignInvocation invocation
    ) {
        if (source == null
                || source.isBlank()
                || invocation == null) {
            return Optional.empty();
        }

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        CompilationUnit unit = StaticJavaParser.parse(source);

        for (ClassOrInterfaceDeclaration type :
                unit.findAll(ClassOrInterfaceDeclaration.class)) {
            Optional<String> service = feignService(type);
            if (service.isEmpty()) {
                continue;
            }

            List<String> classPaths = classPaths(type);

            for (MethodDeclaration method :
                    type.getMethodsByName(invocation.methodName())) {
                if (method.getParameters().size()
                        != invocation.argumentCount()) {
                    continue;
                }

                for (EndpointMapping mapping :
                        methodMappings(method)) {
                    for (String classPath : classPaths) {
                        for (String methodPath : mapping.paths()) {
                            return Optional.of(new StaticOutboundCall(
                                    invocation.sourceMethod(),
                                    service.get(),
                                    "HTTP "
                                            + mapping.httpMethod()
                                            + " "
                                            + combinePaths(
                                                    classPath,
                                                    methodPath
                                            ),
                                    "OpenFeign",
                                    invocation.evidence()
                                            + " -> "
                                            + type.getNameAsString()
                                            + "#"
                                            + method.getNameAsString()
                            ));
                        }
                    }
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> feignService(
            ClassOrInterfaceDeclaration type
    ) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            if (!"FeignClient".equals(annotation.getNameAsString())) {
                continue;
            }

            if (annotation.isSingleMemberAnnotationExpr()) {
                return literalString(
                        annotation.asSingleMemberAnnotationExpr()
                                .getMemberValue()
                ).filter(this::isLiteralServiceName);
            }

            if (!annotation.isNormalAnnotationExpr()) {
                return Optional.empty();
            }

            NodeList<MemberValuePair> pairs =
                    annotation.asNormalAnnotationExpr().getPairs();

            for (String property : List.of("name", "value")) {
                for (MemberValuePair pair : pairs) {
                    if (!property.equals(pair.getNameAsString())) {
                        continue;
                    }

                    Optional<String> value = literalString(
                            pair.getValue()
                    );

                    if (value.isPresent()
                            && isLiteralServiceName(value.get())) {
                        return value;
                    }
                }
            }
        }

        return Optional.empty();
    }

    private boolean isLiteralServiceName(String value) {
        return value != null
                && !value.isBlank()
                && !(value.indexOf('$') >= 0
                        && value.indexOf('{') >= 0);
    }

    private List<EndpointMapping> methodMappings(
            MethodDeclaration method
    ) {
        List<EndpointMapping> mappings = new ArrayList<>();

        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();

            if (SHORT_MAPPING_METHODS.containsKey(name)) {
                mappings.add(new EndpointMapping(
                        SHORT_MAPPING_METHODS.get(name),
                        annotationPaths(annotation)
                ));
                continue;
            }

            if (!"RequestMapping".equals(name)) {
                continue;
            }

            List<String> methods = requestMethods(annotation);
            List<String> paths = annotationPaths(annotation);

            for (String httpMethod : methods) {
                mappings.add(new EndpointMapping(
                        httpMethod,
                        paths
                ));
            }
        }

        return mappings;
    }

    private List<String> classPaths(
            ClassOrInterfaceDeclaration type
    ) {
        List<String> feignPaths = feignClientPaths(type);
        List<String> requestPaths = requestMappingPaths(type);

        if (feignPaths.isEmpty()
                || requestPaths.isEmpty()) {
            return List.of();
        }

        List<String> combined = new ArrayList<>();

        for (String feignPath : feignPaths) {
            for (String requestPath : requestPaths) {
                combined.add(combinePaths(
                        feignPath,
                        requestPath
                ));
            }
        }

        return List.copyOf(combined);
    }

    private List<String> feignClientPaths(
            ClassOrInterfaceDeclaration type
    ) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            if (!"FeignClient".equals(
                    annotation.getNameAsString()
            ) || !annotation.isNormalAnnotationExpr()) {
                continue;
            }

            for (MemberValuePair pair :
                    annotation.asNormalAnnotationExpr().getPairs()) {
                if (!"path".equals(pair.getNameAsString())) {
                    continue;
                }

                List<String> paths = literalStrings(
                        pair.getValue()
                );

                return paths.isEmpty()
                        ? List.of()
                        : paths;
            }
        }

        return List.of("/");
    }

    private List<String> requestMappingPaths(
            ClassOrInterfaceDeclaration type
    ) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            if ("RequestMapping".equals(
                    annotation.getNameAsString()
            )) {
                return annotationPaths(annotation);
            }
        }

        return List.of("/");
    }

    private List<String> annotationPaths(
            AnnotationExpr annotation
    ) {
        if (annotation.isMarkerAnnotationExpr()) {
            return List.of("/");
        }

        if (annotation.isSingleMemberAnnotationExpr()) {
            return literalStrings(
                    annotation.asSingleMemberAnnotationExpr()
                            .getMemberValue()
            );
        }

        boolean pathPropertySeen = false;

        for (String property : List.of("path", "value")) {
            for (MemberValuePair pair :
                    annotation.asNormalAnnotationExpr().getPairs()) {
                if (!property.equals(pair.getNameAsString())) {
                    continue;
                }

                pathPropertySeen = true;
                List<String> values = literalStrings(
                        pair.getValue()
                );

                if (!values.isEmpty()) {
                    return values;
                }
            }
        }

        return pathPropertySeen
                ? List.of()
                : List.of("/");
    }

    private List<String> requestMethods(
            AnnotationExpr annotation
    ) {
        if (!annotation.isNormalAnnotationExpr()) {
            return List.of();
        }

        for (MemberValuePair pair :
                annotation.asNormalAnnotationExpr().getPairs()) {
            if (!"method".equals(pair.getNameAsString())) {
                continue;
            }

            Matcher matcher = REQUEST_METHOD.matcher(
                    pair.getValue().toString()
            );
            List<String> methods = new ArrayList<>();

            while (matcher.find()) {
                methods.add(
                        matcher.group(1)
                                .toUpperCase(Locale.ROOT)
                );
            }

            return List.copyOf(methods);
        }

        return List.of();
    }

    private List<String> literalStrings(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return List.of(
                    normalizePath(
                            expression.asStringLiteralExpr()
                                    .asString()
                    )
            );
        }

        if (expression.isArrayInitializerExpr()) {
            return expression.asArrayInitializerExpr()
                    .getValues()
                    .stream()
                    .filter(Expression::isStringLiteralExpr)
                    .map(value -> normalizePath(
                            value.asStringLiteralExpr().asString()
                    ))
                    .toList();
        }

        return List.of();
    }

    private Optional<String> literalString(
            Expression expression
    ) {
        if (!expression.isStringLiteralExpr()) {
            return Optional.empty();
        }

        return Optional.of(
                expression.asStringLiteralExpr().asString()
        );
    }

    private String combinePaths(
            String classPath,
            String methodPath
    ) {
        String prefix = normalizePath(classPath);
        String suffix = normalizePath(methodPath);

        if ("/".equals(prefix)) {
            return suffix;
        }

        if ("/".equals(suffix)) {
            return prefix;
        }

        return normalizePath(
                prefix + "/" + suffix.substring(1)
        );
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }

        String normalized = path.trim();
        normalized = normalized.startsWith("/")
                ? normalized
                : "/" + normalized;
        normalized = normalized.replaceAll("/{2,}", "/");

        if (normalized.length() > 1
                && normalized.endsWith("/")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            );
        }

        return normalized;
    }

    private record EndpointMapping(
            String httpMethod,
            List<String> paths
    ) {}
}

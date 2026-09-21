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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SpringEndpointOwnershipAnalyzer {

    private static final Map<String, String> SHORT_MAPPING_METHODS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    private static final Pattern REQUEST_METHOD =
            Pattern.compile("RequestMethod\\.([A-Z]+)");

    public List<SpringEndpointOwnership> findOwnedEndpoints(
            String source,
            Set<Integer> changedLines
    ) {
        if (source == null
                || source.isBlank()
                || changedLines == null
                || changedLines.isEmpty()) {
            return List.of();
        }

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        CompilationUnit unit = StaticJavaParser.parse(source);
        Set<SpringEndpointOwnership> endpoints = new LinkedHashSet<>();

        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!containsChangedLine(method, changedLines)) {
                continue;
            }

            List<EndpointMapping> methodMappings = methodMappings(method);
            if (methodMappings.isEmpty()) {
                continue;
            }

            List<String> classPaths = classPaths(method);

            for (EndpointMapping mapping : methodMappings) {
                for (String classPath : classPaths) {
                    for (String methodPath : mapping.paths()) {
                        String route = combinePaths(classPath, methodPath);
                        String endpoint = "HTTP "
                                + mapping.httpMethod()
                                + " "
                                + route;

                        endpoints.add(new SpringEndpointOwnership(
                                endpoint,
                                ownershipEvidence(method)
                        ));
                    }
                }
            }
        }

        return List.copyOf(endpoints);
    }

    private boolean containsChangedLine(
            MethodDeclaration method,
            Set<Integer> changedLines
    ) {
        return method.getRange()
                .map(range -> changedLines.stream()
                        .anyMatch(line -> line >= range.begin.line
                                && line <= range.end.line))
                .orElse(false);
    }

    private List<EndpointMapping> methodMappings(MethodDeclaration method) {
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
            if (methods.isEmpty()) {
                continue;
            }

            List<String> paths = annotationPaths(annotation);
            for (String httpMethod : methods) {
                mappings.add(new EndpointMapping(httpMethod, paths));
            }
        }

        return mappings;
    }

    private List<String> classPaths(MethodDeclaration method) {
        return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(type -> {
                    for (AnnotationExpr annotation : type.getAnnotations()) {
                        if (isSpringMapping(annotation.getNameAsString())) {
                            return annotationPaths(annotation);
                        }
                    }
                    return List.of("/");
                })
                .orElse(List.of("/"));
    }

    private boolean isSpringMapping(String annotationName) {
        return "RequestMapping".equals(annotationName)
                || SHORT_MAPPING_METHODS.containsKey(annotationName);
    }

    private List<String> annotationPaths(AnnotationExpr annotation) {
        if (annotation.isMarkerAnnotationExpr()) {
            return List.of("/");
        }

        if (annotation.isSingleMemberAnnotationExpr()) {
            List<String> paths = stringValues(
                    annotation.asSingleMemberAnnotationExpr().getMemberValue()
            );
            return paths.isEmpty() ? List.of("/") : normalizePaths(paths);
        }

        NodeList<MemberValuePair> pairs =
                annotation.asNormalAnnotationExpr().getPairs();

        for (String property : List.of("path", "value")) {
            for (MemberValuePair pair : pairs) {
                if (!property.equals(pair.getNameAsString())) {
                    continue;
                }

                List<String> paths = stringValues(pair.getValue());
                if (!paths.isEmpty()) {
                    return normalizePaths(paths);
                }
            }
        }

        return List.of("/");
    }

    private List<String> requestMethods(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            return List.of();
        }

        for (MemberValuePair pair :
                annotation.asNormalAnnotationExpr().getPairs()) {
            if (!"method".equals(pair.getNameAsString())) {
                continue;
            }

            Matcher matcher = REQUEST_METHOD.matcher(pair.getValue().toString());
            List<String> methods = new ArrayList<>();

            while (matcher.find()) {
                methods.add(matcher.group(1).toUpperCase(Locale.ROOT));
            }

            return List.copyOf(methods);
        }

        return List.of();
    }

    private List<String> stringValues(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return List.of(expression.asStringLiteralExpr().asString());
        }

        if (expression.isArrayInitializerExpr()) {
            return expression.asArrayInitializerExpr()
                    .getValues()
                    .stream()
                    .filter(Expression::isStringLiteralExpr)
                    .map(value -> value.asStringLiteralExpr().asString())
                    .toList();
        }

        return List.of();
    }

    private List<String> normalizePaths(List<String> paths) {
        return paths.stream()
                .map(this::normalizePath)
                .distinct()
                .toList();
    }

    private String combinePaths(String classPath, String methodPath) {
        String prefix = normalizePath(classPath);
        String suffix = normalizePath(methodPath);

        if ("/".equals(prefix)) {
            return suffix;
        }

        if ("/".equals(suffix)) {
            return prefix;
        }

        return normalizePath(prefix + "/" + suffix.substring(1));
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

        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private String ownershipEvidence(MethodDeclaration method) {
        String owner = method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .orElse("<unknown>");

        String range = method.getRange()
                .map(value -> value.begin.line + "-" + value.end.line)
                .orElse("unknown");

        return "changed lines belong to "
                + owner
                + "#"
                + method.getNameAsString()
                + " (lines "
                + range
                + ")";
    }

    private record EndpointMapping(
            String httpMethod,
            List<String> paths
    ) {}
}

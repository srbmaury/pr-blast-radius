package com.srbmaury.blastradius.ingestion;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.srbmaury.blastradius.domain.StaticOutboundCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StaticOutboundCallAnalyzer {

    private static final Map<String, String> REST_TEMPLATE_METHODS = Map.of(
            "getForObject", "GET",
            "getForEntity", "GET",
            "postForObject", "POST",
            "postForEntity", "POST",
            "put", "PUT",
            "delete", "DELETE",
            "patchForObject", "PATCH"
    );

    private static final Map<String, String> FLUENT_HTTP_METHODS = Map.of(
            "get", "GET",
            "post", "POST",
            "put", "PUT",
            "delete", "DELETE",
            "patch", "PATCH"
    );

    private static final Set<String> SUPPORTED_CLIENT_TYPES = Set.of(
            "RestTemplate",
            "RestClient",
            "WebClient"
    );

    private static final Pattern ABSOLUTE_HTTP_URL =
            Pattern.compile("^https?://([^/]+)(/[^?#]*)?.*$", Pattern.CASE_INSENSITIVE);

    public StaticSourceAnalysis analyze(
            String source,
            Set<Integer> changedLines
    ) {
        if (source == null
                || source.isBlank()
                || changedLines == null
                || changedLines.isEmpty()) {
            return new StaticSourceAnalysis(List.of(), List.of());
        }

        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        CompilationUnit unit = StaticJavaParser.parse(source);
        Map<String, String> imports = imports(unit);
        String packageName = unit.getPackageDeclaration()
                .map(value -> value.getNameAsString())
                .orElse("");

        Set<StaticOutboundCall> calls = new LinkedHashSet<>();
        Set<FeignInvocation> feignInvocations = new LinkedHashSet<>();

        for (ClassOrInterfaceDeclaration type :
                unit.findAll(ClassOrInterfaceDeclaration.class)) {
            Map<String, String> fieldTypes = fieldTypes(type);
            Map<String, String> baseUrls = fieldBaseUrls(type);

            for (MethodDeclaration method : type.getMethods()) {
                if (!containsChangedLine(method, changedLines)) {
                    continue;
                }

                collectFromMethod(
                        method,
                        type,
                        fieldTypes,
                        baseUrls,
                        imports,
                        packageName,
                        new HashSet<>(),
                        calls,
                        feignInvocations
                );
            }
        }

        return new StaticSourceAnalysis(
                List.copyOf(calls),
                List.copyOf(feignInvocations)
        );
    }

    private void collectFromMethod(
            MethodDeclaration method,
            ClassOrInterfaceDeclaration owner,
            Map<String, String> fieldTypes,
            Map<String, String> baseUrls,
            Map<String, String> imports,
            String packageName,
            Set<MethodKey> visited,
            Set<StaticOutboundCall> calls,
            Set<FeignInvocation> feignInvocations
    ) {
        MethodKey methodKey = new MethodKey(
                method.getNameAsString(),
                method.getParameters().size()
        );

        if (!visited.add(methodKey)) {
            return;
        }

        Map<String, String> variableTypes = new HashMap<>(fieldTypes);
        method.getParameters().forEach(parameter ->
                variableTypes.put(
                        parameter.getNameAsString(),
                        simpleType(parameter.getTypeAsString())
                )
        );

        method.findAll(VariableDeclarator.class).forEach(variable ->
                variableTypes.put(
                        variable.getNameAsString(),
                        simpleType(variable.getTypeAsString())
                )
        );

        String sourceMethod = owner.getNameAsString()
                + "#"
                + method.getNameAsString();

        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            detectRestTemplateCall(
                    call,
                    variableTypes,
                    baseUrls,
                    sourceMethod
            ).ifPresent(calls::add);

            detectFluentClientCall(
                    call,
                    variableTypes,
                    baseUrls,
                    sourceMethod
            ).ifPresent(calls::add);

            detectFeignInvocation(
                    call,
                    variableTypes,
                    imports,
                    packageName,
                    sourceMethod
            ).ifPresent(feignInvocations::add);

            if (isLocalMethodCall(call)) {
                owner.getMethodsByName(call.getNameAsString())
                        .stream()
                        .filter(candidate ->
                                candidate.getParameters().size()
                                        == call.getArguments().size())
                        .forEach(candidate -> collectFromMethod(
                                candidate,
                                owner,
                                fieldTypes,
                                baseUrls,
                                imports,
                                packageName,
                                visited,
                                calls,
                                feignInvocations
                        ));
            }
        }
    }

    private Optional<StaticOutboundCall> detectRestTemplateCall(
            MethodCallExpr call,
            Map<String, String> variableTypes,
            Map<String, String> baseUrls,
            String sourceMethod
    ) {
        String variable = scopedVariable(call);
        if (variable == null
                || !"RestTemplate".equals(variableTypes.get(variable))) {
            return Optional.empty();
        }

        String resolvedHttpMethod = REST_TEMPLATE_METHODS.get(
                call.getNameAsString()
        );

        if ("exchange".equals(call.getNameAsString())) {
            resolvedHttpMethod = exchangeMethod(call);
        }

        if (resolvedHttpMethod == null
                || call.getArguments().isEmpty()) {
            return Optional.empty();
        }

        final String httpMethod = resolvedHttpMethod;

        Optional<String> literal = literalString(call.getArgument(0));
        if (literal.isEmpty()) {
            return Optional.empty();
        }

        return resolveHttpTarget(
                literal.get(),
                baseUrls.get(variable)
        ).map(target -> new StaticOutboundCall(
                sourceMethod,
                target.service(),
                "HTTP " + httpMethod + " " + target.route(),
                "RestTemplate",
                call.toString()
        ));
    }

    private Optional<StaticOutboundCall> detectFluentClientCall(
            MethodCallExpr call,
            Map<String, String> variableTypes,
            Map<String, String> baseUrls,
            String sourceMethod
    ) {
        if (!"uri".equals(call.getNameAsString())
                || call.getArguments().isEmpty()) {
            return Optional.empty();
        }

        Optional<String> literal = literalString(call.getArgument(0));
        if (literal.isEmpty()) {
            return Optional.empty();
        }

        FluentClientChain chain = resolveFluentChain(call);
        if (chain == null) {
            return Optional.empty();
        }

        String clientType = variableTypes.get(chain.variable());
        if (!"RestClient".equals(clientType)
                && !"WebClient".equals(clientType)) {
            return Optional.empty();
        }

        return resolveHttpTarget(
                literal.get(),
                baseUrls.get(chain.variable())
        ).map(target -> new StaticOutboundCall(
                sourceMethod,
                target.service(),
                "HTTP " + chain.httpMethod() + " " + target.route(),
                clientType,
                call.toString()
        ));
    }

    private Optional<FeignInvocation> detectFeignInvocation(
            MethodCallExpr call,
            Map<String, String> variableTypes,
            Map<String, String> imports,
            String packageName,
            String sourceMethod
    ) {
        String variable = scopedVariable(call);
        if (variable == null) {
            return Optional.empty();
        }

        String type = variableTypes.get(variable);
        if (type == null
                || SUPPORTED_CLIENT_TYPES.contains(type)
                || !type.endsWith("Client")) {
            return Optional.empty();
        }

        String qualifiedType = resolveQualifiedType(
                type,
                imports,
                packageName
        );

        return Optional.of(new FeignInvocation(
                sourceMethod,
                qualifiedType,
                call.getNameAsString(),
                call.getArguments().size(),
                call.toString()
        ));
    }

    private FluentClientChain resolveFluentChain(MethodCallExpr uriCall) {
        Expression current = uriCall.getScope().orElse(null);
        String httpMethod = null;

        while (current instanceof MethodCallExpr methodCall) {
            String name = methodCall.getNameAsString();

            if (FLUENT_HTTP_METHODS.containsKey(name)) {
                httpMethod = FLUENT_HTTP_METHODS.get(name);
            } else if ("method".equals(name)
                    && !methodCall.getArguments().isEmpty()) {
                httpMethod = httpMethodExpression(
                        methodCall.getArgument(0)
                );
            }

            current = methodCall.getScope().orElse(null);
        }

        String variable = expressionVariable(current);

        if (variable == null || httpMethod == null) {
            return null;
        }

        return new FluentClientChain(variable, httpMethod);
    }

    private String exchangeMethod(MethodCallExpr call) {
        if (call.getArguments().size() < 2) {
            return null;
        }

        return httpMethodExpression(call.getArgument(1));
    }

    private String httpMethodExpression(Expression expression) {
        String text = expression.toString();
        int marker = text.lastIndexOf("HttpMethod.");

        if (marker < 0) {
            return null;
        }

        String value = text.substring(marker + "HttpMethod.".length())
                .replaceAll("[^A-Z].*$", "");

        return value.isBlank()
                ? null
                : value.toUpperCase(Locale.ROOT);
    }

    private Optional<String> literalString(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return Optional.of(
                    expression.asStringLiteralExpr().asString()
            );
        }

        return Optional.empty();
    }

    private Optional<HttpTarget> resolveHttpTarget(
            String uri,
            String baseUrl
    ) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }

        Matcher absolute = ABSOLUTE_HTTP_URL.matcher(uri.trim());
        if (absolute.matches()) {
            String host = normalizeHost(absolute.group(1));
            String route = normalizeRoute(
                    absolute.group(2) == null
                            ? "/"
                            : absolute.group(2)
            );

            return host == null
                    ? Optional.empty()
                    : Optional.of(new HttpTarget(host, route));
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }

        Matcher base = ABSOLUTE_HTTP_URL.matcher(baseUrl.trim());
        if (!base.matches()) {
            return Optional.empty();
        }

        String host = normalizeHost(base.group(1));
        String basePath = normalizeRoute(
                base.group(2) == null
                        ? "/"
                        : base.group(2)
        );
        String route = combineRoutes(
                basePath,
                stripQueryAndFragment(uri)
        );

        return host == null
                ? Optional.empty()
                : Optional.of(new HttpTarget(host, route));
    }

    private Map<String, String> fieldTypes(
            ClassOrInterfaceDeclaration type
    ) {
        Map<String, String> result = new LinkedHashMap<>();

        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                result.put(
                        variable.getNameAsString(),
                        simpleType(variable.getTypeAsString())
                );
            }
        }

        return result;
    }

    private Map<String, String> fieldBaseUrls(
            ClassOrInterfaceDeclaration type
    ) {
        Map<String, String> result = new LinkedHashMap<>();

        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                variable.getInitializer().ifPresent(initializer -> {
                    for (MethodCallExpr call :
                            initializer.findAll(MethodCallExpr.class)) {
                        boolean baseUrlCall =
                                "baseUrl".equals(call.getNameAsString());

                        boolean createCall =
                                "create".equals(call.getNameAsString())
                                        && call.getScope()
                                                .map(Expression::toString)
                                                .map(scope ->
                                                        "RestClient".equals(scope)
                                                                || "WebClient".equals(scope))
                                                .orElse(false);

                        if ((!baseUrlCall && !createCall)
                                || call.getArguments().isEmpty()) {
                            continue;
                        }

                        literalString(call.getArgument(0))
                                .ifPresent(value -> result.put(
                                        variable.getNameAsString(),
                                        value
                                ));
                    }
                });
            }
        }

        return result;
    }

    private Map<String, String> imports(CompilationUnit unit) {
        Map<String, String> result = new HashMap<>();

        unit.getImports().stream()
                .filter(value -> !value.isAsterisk())
                .filter(value -> !value.isStatic())
                .forEach(value -> {
                    String name = value.getNameAsString();
                    int dot = name.lastIndexOf('.');

                    if (dot > 0) {
                        result.put(
                                name.substring(dot + 1),
                                name
                        );
                    }
                });

        return result;
    }

    private String resolveQualifiedType(
            String type,
            Map<String, String> imports,
            String packageName
    ) {
        if (type.contains(".")) {
            return type;
        }

        String imported = imports.get(type);
        if (imported != null) {
            return imported;
        }

        return packageName == null || packageName.isBlank()
                ? type
                : packageName + "." + type;
    }

    private String scopedVariable(MethodCallExpr call) {
        return call.getScope()
                .map(this::expressionVariable)
                .orElse(null);
    }

    private String expressionVariable(Expression expression) {
        if (expression == null) {
            return null;
        }

        if (expression instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }

        if (expression instanceof FieldAccessExpr fieldAccess) {
            if (fieldAccess.getScope().isThisExpr()) {
                return fieldAccess.getNameAsString();
            }
            return fieldAccess.getNameAsString();
        }

        return null;
    }

    private boolean isLocalMethodCall(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return true;
        }

        Expression scope = call.getScope().orElseThrow();
        return scope.isThisExpr();
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

    private String simpleType(String rawType) {
        String type = rawType;
        int generic = type.indexOf('<');

        if (generic >= 0) {
            type = type.substring(0, generic);
        }

        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private String normalizeHost(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return null;
        }

        String value = hostPort.trim();
        int at = value.lastIndexOf('@');

        if (at >= 0) {
            value = value.substring(at + 1);
        }

        int colon = value.indexOf(':');
        return colon >= 0
                ? value.substring(0, colon)
                : value;
    }

    private String stripQueryAndFragment(String route) {
        String value = route == null ? "/" : route.trim();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();

        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }

        return value.substring(0, end);
    }

    private String normalizeRoute(String route) {
        String value = stripQueryAndFragment(route);

        if (value.isBlank()) {
            return "/";
        }

        value = value.startsWith("/") ? value : "/" + value;
        value = value.replaceAll("/{2,}", "/");

        return value.length() > 1 && value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }

    private String combineRoutes(String basePath, String relativePath) {
        String base = normalizeRoute(basePath);
        String relative = normalizeRoute(relativePath);

        if ("/".equals(base)) {
            return relative;
        }
        if ("/".equals(relative)) {
            return base;
        }

        return normalizeRoute(
                base + "/" + relative.substring(1)
        );
    }

    private record HttpTarget(String service, String route) {}

    private record FluentClientChain(
            String variable,
            String httpMethod
    ) {}

    private record MethodKey(
            String name,
            int argumentCount
    ) {}
}

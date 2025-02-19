/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.openapi.visitor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.micronaut.context.env.DefaultPropertyPlaceholderResolver;
import io.micronaut.context.env.PropertyPlaceholderResolver;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.beans.BeanMap;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.DefaultConversionService;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.core.value.OptionalValues;
import io.micronaut.core.value.PropertyResolver;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.uri.UriMatchTemplate;
import io.micronaut.http.uri.UriMatchVariable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.openapi.javadoc.JavadocDescription;
import io.micronaut.openapi.javadoc.JavadocParser;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.callbacks.Callback;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A {@link TypeElementVisitor} the builds the Swagger model from Micronaut controllers at compile time.
 *
 * @author graemerocher
 * @since 1.0
 */
@Experimental
public class OpenApiControllerVisitor extends AbstractOpenApiVisitor implements TypeElementVisitor<Controller, HttpMethodMapping> {

    private PropertyPlaceholderResolver propertyPlaceholderResolver;

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        processSecuritySchemes(element, context);
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        Optional<Class<? extends Annotation>> httpMethodOpt = element.getAnnotationTypeByStereotype(HttpMethodMapping.class);

        if (element.isAnnotationPresent(Hidden.class)) {
            return;
        }

        httpMethodOpt.ifPresent(httpMethodClass -> {
            HttpMethod httpMethod = null;
            try {
                httpMethod = HttpMethod.valueOf(httpMethodClass.getSimpleName().toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                // ignore
            }
            if (httpMethod == null) {
                return;
            }

            String controllerValue = element.getValue(Controller.class, String.class).orElse("/");
            controllerValue = getPropertyPlaceholderResolver().resolvePlaceholders(controllerValue).orElse(controllerValue);
            UriMatchTemplate matchTemplate = UriMatchTemplate.of(controllerValue);
            String methodValue = element.getValue(HttpMethodMapping.class, String.class).orElse("/");
            methodValue = getPropertyPlaceholderResolver().resolvePlaceholders(methodValue).orElse(methodValue);
            matchTemplate = matchTemplate.nest(methodValue);

            PathItem pathItem = resolvePathItem(context, matchTemplate);
            OpenAPI openAPI = resolveOpenAPI(context);

            final Optional<AnnotationValue<Operation>> operationAnnotation = element.findAnnotation(Operation.class);
            io.swagger.v3.oas.models.Operation swaggerOperation = operationAnnotation.flatMap(o -> {
                JsonNode jsonNode = toJson(o.getValues(), context);

                try {
                    return Optional.of(treeToValue(jsonNode, io.swagger.v3.oas.models.Operation.class));
                } catch (Exception e) {
                    context.warn("Error reading Swagger Operation for element [" + element + "]: " + e.getMessage(), element);
                    return Optional.empty();
                }
            }).orElse(new io.swagger.v3.oas.models.Operation());


            readTags(element, swaggerOperation);

            readSecurityRequirements(element, context, swaggerOperation);

            readApiResponses(element, context, swaggerOperation);

            readServers(element, context, swaggerOperation);

            readCallbacks(element, context, swaggerOperation);

            JavadocDescription javadocDescription = element.getDocumentation().map(s -> new JavadocParser().parse(s)).orElse(null);

            if (javadocDescription != null && StringUtils.isEmpty(swaggerOperation.getDescription())) {
                swaggerOperation.setDescription(javadocDescription.getMethodDescription());
                swaggerOperation.setSummary(javadocDescription.getMethodDescription());
            }

            setOperationOnPathItem(pathItem, swaggerOperation, httpMethod);

            if (element.isAnnotationPresent(Deprecated.class)) {
                swaggerOperation.setDeprecated(true);
            }

            if (StringUtils.isEmpty(swaggerOperation.getOperationId())) {
                swaggerOperation.setOperationId(element.getName());
            }

            boolean permitsRequestBody = HttpMethod.permitsRequestBody(httpMethod);

            List<Parameter> swaggerParameters = swaggerOperation.getParameters();
            List<UriMatchVariable> pv = matchTemplate.getVariables();
            Map<String, UriMatchVariable> pathVariables = new LinkedHashMap<>(pv.size()) ;
            for (UriMatchVariable variable : pv) {
                pathVariables.put(variable.getName(), variable);
            }

            OptionalValues<List> consumesMediaTypes = element.getValues(Consumes.class, List.class);
            String consumesMediaType = element.stringValue(Consumes.class).orElse(MediaType.APPLICATION_JSON);
            ApiResponses responses = swaggerOperation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();

                swaggerOperation.setResponses(responses);

                ApiResponse okResponse = new ApiResponse();

                if (javadocDescription != null) {
                    String returnDescription = javadocDescription.getReturnDescription();
                    okResponse.setDescription(returnDescription);
                } else {
                    okResponse.setDescription(swaggerOperation.getOperationId() + " default response");
                }

                ClassElement returnType = element.getReturnType();

                if (returnType.isAssignable("io.reactivex.Completable")) {
                    returnType = null;
                } else if (isResponseType(returnType)) {
                    returnType = returnType.getFirstTypeArgument().orElse(returnType);
                } else if (isSingleResponseType(returnType)) {
                    returnType = returnType.getFirstTypeArgument().get();
                    returnType = returnType.getFirstTypeArgument().orElse(returnType);
                }

                if (returnType != null) {
                    OptionalValues<List> mediaTypes = element.getValues(Produces.class, List.class);
                    Content content;
                    if (mediaTypes.isEmpty()) {
                        content = buildContent(element, returnType, MediaType.APPLICATION_JSON, openAPI, context);
                    } else {
                        content = buildContent(element, returnType, mediaTypes, openAPI, context);
                    }
                    okResponse.setContent(content);
                }
                responses.put(ApiResponses.DEFAULT, okResponse);
            }


            boolean hasExistingParameters = CollectionUtils.isNotEmpty(swaggerParameters);
            if (!hasExistingParameters) {
                swaggerParameters = new ArrayList<>();
                swaggerOperation.setParameters(swaggerParameters);
            }


            for (ParameterElement parameter : element.getParameters()) {

                ClassElement parameterType = parameter.getType();
                String parameterName = parameter.getName();

                if (isIgnoredParameterType(parameterType)) {
                    continue;
                }

                if (permitsRequestBody && swaggerOperation.getRequestBody() == null) {
                    readSwaggerRequestBody(parameter, context, swaggerOperation);
                }

                if (parameter.isAnnotationPresent(Body.class)) {

                    if (permitsRequestBody && swaggerOperation.getRequestBody() == null) {
                        RequestBody requestBody = new RequestBody();
                        if (javadocDescription != null) {

                            CharSequence desc = javadocDescription.getParameters().get(parameterName);
                            if (desc != null) {
                                requestBody.setDescription(desc.toString());
                            }
                        }
                        requestBody.setRequired(!parameter.isAnnotationPresent(Nullable.class) && !parameterType.isAssignable(Optional.class));

                        Content content;
                        if (consumesMediaTypes.isEmpty()) {
                            content = buildContent(
                                    parameter,
                                    parameterType,
                                    MediaType.APPLICATION_JSON,
                                    openAPI, context
                            );
                        } else {
                            content = buildContent(
                                    parameter,
                                    parameterType,
                                    consumesMediaTypes,
                                    openAPI, context
                            );
                        }
                        requestBody.setContent(content);
                        swaggerOperation.setRequestBody(requestBody);
                    }
                    continue;
                }

                if (hasExistingParameters) {
                    continue;
                }

                Parameter newParameter = null;

                if (!parameter.hasStereotype(Bindable.class) && pathVariables.containsKey(parameterName)) {
                    UriMatchVariable var = pathVariables.get(parameterName);
                    newParameter = new Parameter();

                    newParameter.setIn(var.isQuery() ? ParameterIn.QUERY.toString() : ParameterIn.PATH.toString());
                    final boolean exploded = var.isExploded();
                    if (exploded) {
                        newParameter.setExplode(exploded);
                    }
                } else if (parameter.isAnnotationPresent(PathVariable.class)) {
                    String paramName = parameter.getValue(PathVariable.class, String.class).orElse(parameterName);
                    UriMatchVariable var = pathVariables.get(paramName);
                    if (var == null) {
                        context.fail("Path variable name: '" + paramName  + "' not found in path.", parameter);
                        continue;
                    }
                    newParameter = new Parameter();
                    newParameter.setIn(ParameterIn.PATH.toString());
                    newParameter.setName(paramName);
                    final boolean exploded = var.isExploded();
                    if (exploded) {
                        newParameter.setExplode(exploded);
                    }
                } else if (parameter.isAnnotationPresent(Header.class)) {
                    String headerName = parameter.getValue(Header.class, "name", String.class)
                                                 .orElse(parameter.getValue(Header.class, String.class)
                                                 .orElseGet(() -> NameUtils.hyphenate(parameterName)));
                    newParameter = new Parameter();
                    newParameter.setIn(ParameterIn.HEADER.toString());
                    newParameter.setName(headerName);
                } else if (parameter.isAnnotationPresent(CookieValue.class)) {
                    String cookieName = parameter.getValue(CookieValue.class, String.class).orElse(parameterName);
                    newParameter = new Parameter();
                    newParameter.setIn(ParameterIn.COOKIE.toString());
                    newParameter.setName(cookieName);
                } else if (parameter.isAnnotationPresent(QueryValue.class)) {
                    String queryVar = parameter.getValue(QueryValue.class, String.class).orElse(parameterName);
                    newParameter = new Parameter();
                    newParameter.setIn(ParameterIn.QUERY.toString());
                    newParameter.setName(queryVar);
                }

                if (parameter.isAnnotationPresent(io.swagger.v3.oas.annotations.Parameter.class)) {
                    AnnotationValue<io.swagger.v3.oas.annotations.Parameter> paramAnn = parameter.findAnnotation(io.swagger.v3.oas.annotations.Parameter.class).orElse(null);

                    if (paramAnn != null) {

                        if (paramAnn.get("hidden", Boolean.class, false)) {
                            // ignore hidden parameters
                            continue;
                        }

                        Map<CharSequence, Object> paramValues = toValueMap(paramAnn.getValues(), context);
                        normalizeEnumValues(paramValues, Collections.singletonMap(
                                "in", ParameterIn.class
                        ));
                        if (parameter.isAnnotationPresent(Header.class)) {
                            paramValues.put("in", ParameterIn.HEADER.toString());
                        } else if (parameter.isAnnotationPresent(CookieValue.class)) {
                            paramValues.put("in", ParameterIn.COOKIE.toString());
                        } else if (parameter.isAnnotationPresent(QueryValue.class)) {
                            paramValues.put("in", ParameterIn.QUERY.toString());
                        }


                        JsonNode jsonNode = jsonMapper.valueToTree(paramValues);

                        if (newParameter == null) {
                            try {
                                newParameter = treeToValue(jsonNode, Parameter.class);
                            } catch (Exception e) {
                                context.warn("Error reading Swagger Parameter for element [" + parameter + "]: " + e.getMessage(), parameter);
                            }
                        } else {
                            try {
                                Parameter v = treeToValue(jsonNode, Parameter.class);
                                if (v != null) {
                                    // horrible hack because Swagger ParameterDeserializer breaks updating existing objects
                                    BeanMap<Parameter> beanMap = BeanMap.of(v);
                                    BeanMap<Parameter> target = BeanMap.of(newParameter);
                                    for (CharSequence name : paramValues.keySet()) {
                                        Object o = beanMap.get(name.toString());
                                        target.put(name.toString(), o);
                                    }
                                } else {
                                    BeanMap<Parameter> target = BeanMap.of(newParameter);
                                    for (CharSequence name : paramValues.keySet()) {
                                        Object o = paramValues.get(name.toString());
                                        try {
                                            target.put(name.toString(), o);
                                        } catch (Exception e) {
                                            // ignore
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                context.warn("Error reading Swagger Parameter for element [" + parameter + "]: " + e.getMessage(), parameter);
                            }
                        }

                        if (newParameter != null) {
                            final Schema parameterSchema = newParameter.getSchema();
                            if (paramAnn.contains("schema") && parameterSchema != null) {
                                final AnnotationValue schemaAnn = paramAnn.get("schema", AnnotationValue.class).orElse(null);
                                if (schemaAnn != null) {
                                    bindSchemaAnnotationValue(context, parameter, parameterSchema, schemaAnn);
                                }
                            }
                        }
                    }
                }

                if (newParameter != null) {

                    if (StringUtils.isEmpty(newParameter.getName())) {
                        newParameter.setName(parameterName);
                    }

                    if (newParameter.getRequired() == null) {
                        newParameter.setRequired(!parameter.isAnnotationPresent(Nullable.class));
                    }
                    // calc newParameter.setExplode();
                    if (javadocDescription != null && StringUtils.isEmpty(newParameter.getDescription())) {

                        CharSequence desc = javadocDescription.getParameters().get(parameterName);
                        if (desc != null) {
                            newParameter.setDescription(desc.toString());
                        }
                    }
                    swaggerParameters.add(newParameter);

                    Schema schema = newParameter.getSchema();
                    if (schema == null) {
                        schema = resolveSchema(openAPI, parameter, parameterType, context, consumesMediaType);
                    }

                    if (schema != null) {
                        bindSchemaForElement(context, parameter, parameter.getType(), schema);
                        newParameter.setSchema(schema);
                    }
                }
            }

            if (HttpMethod.requiresRequestBody(httpMethod) && swaggerOperation.getRequestBody() == null) {
                List<ParameterElement> bodyParameters = Arrays.stream(element.getParameters())
                  .filter(p -> !pathVariables.containsKey(p.getName())
                    && !p.isAnnotationPresent(Bindable.class)
                    && !p.isAnnotationPresent(JsonIgnore.class)
                    && !p.isAnnotationPresent(Hidden.class)
                    && !p.getValue(io.swagger.v3.oas.annotations.Parameter.class, "hidden", Boolean.class).orElse(false)
                    && !isIgnoredParameterType(p.getType())
                  )
                  .collect(Collectors.toList());

                if (!bodyParameters.isEmpty()) {
                    RequestBody requestBody = new RequestBody();
                    final Content content = new Content();
                    consumesMediaTypes = consumesMediaTypes.isEmpty() ? OptionalValues.of(List.class, Collections.singletonMap("value", MediaType.APPLICATION_JSON)) : consumesMediaTypes;
                    consumesMediaTypes.forEach((key, mediaTypeList) -> {
                        for (Object mediaType: mediaTypeList) {
                            io.swagger.v3.oas.models.media.MediaType mt = new io.swagger.v3.oas.models.media.MediaType();
                            ObjectSchema schema = new ObjectSchema();
                            for (ParameterElement parameter : bodyParameters) {
                                Schema propertySchema = resolveSchema(openAPI, parameter, parameter.getType(), context, mediaType.toString());
                                if (propertySchema != null) {

                                    processSchemaProperty(context, parameter, parameter.getType(), schema, propertySchema);

                                    propertySchema.setNullable(parameter.isAnnotationPresent(Nullable.class));
                                    if (javadocDescription != null && StringUtils.isEmpty(propertySchema.getDescription())) {
                                        CharSequence doc = javadocDescription.getParameters().get(parameter.getName());
                                        if (doc != null) {
                                            propertySchema.setDescription(doc.toString());
                                        }
                                    }
                                }

                            }
                            mt.setSchema(schema);
                            content.addMediaType(mediaType.toString(), mt);
                        }
                    });

                    requestBody.setContent(content);
                    requestBody.setRequired(true);
                    swaggerOperation.setRequestBody(requestBody);
                }
            }
        });
    }

    private boolean isIgnoredParameterType(ClassElement parameterType) {
        return parameterType == null ||
                parameterType.isAssignable(Principal.class) ||
            parameterType.isAssignable("io.micronaut.security.authentication.Authentication");
    }

    private boolean isResponseType(ClassElement returnType) {
        return returnType.isAssignable(HttpResponse.class) || returnType.isAssignable("org.springframework.http.HttpEntity");
    }

    private boolean isSingleResponseType(ClassElement returnType) {
        return returnType.isAssignable("io.reactivex.Single")
          && returnType.getFirstTypeArgument().isPresent()
          && isResponseType(returnType.getFirstTypeArgument().get());
    }

    private void setOperationOnPathItem(PathItem pathItem, io.swagger.v3.oas.models.Operation swaggerOperation, HttpMethod httpMethod) {
        switch (httpMethod) {
            case GET:
                pathItem.get(swaggerOperation);
            break;
            case POST:
                pathItem.post(swaggerOperation);
            break;
            case PUT:
                pathItem.put(swaggerOperation);
            break;
            case PATCH:
                pathItem.patch(swaggerOperation);
            break;
            case DELETE:
                pathItem.delete(swaggerOperation);
            break;
            case HEAD:
                pathItem.head(swaggerOperation);
            break;
            case OPTIONS:
                pathItem.options(swaggerOperation);
            break;
            case TRACE:
                pathItem.trace(swaggerOperation);
            break;
            default:
                // unprocessable
        }
    }

    private void readApiResponses(MethodElement element, VisitorContext context, io.swagger.v3.oas.models.Operation swaggerOperation) {
        List<AnnotationValue<io.swagger.v3.oas.annotations.responses.ApiResponse>> responseAnnotations = element.getAnnotationValuesByType(io.swagger.v3.oas.annotations.responses.ApiResponse.class);
        if (CollectionUtils.isNotEmpty(responseAnnotations)) {
            ApiResponses apiResponses = new ApiResponses();
            for (AnnotationValue<io.swagger.v3.oas.annotations.responses.ApiResponse> r : responseAnnotations) {

                JsonNode jn = toJson(r.getValues(), context);
                try {
                    Optional<ApiResponse> newResponse = Optional.of(treeToValue(jn, ApiResponse.class));
                    newResponse.ifPresent(apiResponse -> {
                        String name = r.get("responseCode", String.class).orElse("default");
                        apiResponses.put(name, apiResponse);
                    });
                } catch (Exception e) {
                    context.warn("Error reading Swagger ApiResponses for element [" + element + "]: " + e.getMessage(), element);
                }
            }
            swaggerOperation.setResponses(apiResponses);
        }
    }

    private void readSwaggerRequestBody(ParameterElement element, VisitorContext context, io.swagger.v3.oas.models.Operation swaggerOperation) {
        element.findAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class)
                .flatMap(annotation -> {
                    JsonNode jn = toJson(annotation.getValues(), context);
                    try {
                        return Optional.of(treeToValue(jn, RequestBody.class));
                    } catch (Exception e) {
                        context.warn("Error reading Swagger ResponseBody for element [" + element + "]: " + e.getMessage(), element);
                        return Optional.empty();
                    }

                })
                .ifPresent(swaggerOperation::setRequestBody);
    }

    private void readSecurityRequirements(MethodElement element, VisitorContext context, io.swagger.v3.oas.models.Operation swaggerOperation) {
        List<AnnotationValue<io.swagger.v3.oas.annotations.security.SecurityRequirement>> securityAnnotations = element.getAnnotationValuesByType(io.swagger.v3.oas.annotations.security.SecurityRequirement.class);
        if (CollectionUtils.isNotEmpty(securityAnnotations)) {
            for (AnnotationValue<io.swagger.v3.oas.annotations.security.SecurityRequirement> r : securityAnnotations) {
                try {
                    swaggerOperation.addSecurityItem(mapToSecurityRequirement(r));
                } catch (Exception e) {
                    context.warn("Error reading Swagger SecurityRequirement for element [" + element + "]: " + e.getMessage(), element);
                }
            }
        }
    }

    private void readServers(MethodElement element, VisitorContext context, io.swagger.v3.oas.models.Operation swaggerOperation) {
        List<AnnotationValue<io.swagger.v3.oas.annotations.servers.Server>> serverAnnotations = element.getAnnotationValuesByType(io.swagger.v3.oas.annotations.servers.Server.class);
        if (CollectionUtils.isNotEmpty(serverAnnotations)) {
            for (AnnotationValue<io.swagger.v3.oas.annotations.servers.Server> r : serverAnnotations) {
                JsonNode jn = toJson(r.getValues(), context);
                try {
                    Optional<Server> newRequirement = Optional.of(treeToValue(jn, Server.class));
                    newRequirement.ifPresent(swaggerOperation::addServersItem);
                } catch (Exception e) {
                    context.warn("Error reading Swagger Server for element [" + element + "]: " + e.getMessage(), element);
                }
            }
        }
    }

    private void readCallbacks(MethodElement element, VisitorContext context, io.swagger.v3.oas.models.Operation swaggerOperation) {
        List<AnnotationValue<Callback>> callbackAnnotations = element.getAnnotationValuesByType(Callback.class);
        if (CollectionUtils.isNotEmpty(callbackAnnotations)) {
            for (AnnotationValue<Callback> callbackAnn : callbackAnnotations) {
                final Optional<String> n = callbackAnn.get("name", String.class);
                n.ifPresent(callbackName -> {

                    final Optional<String> expr = callbackAnn.get("callbackUrlExpression", String.class);
                    if (expr.isPresent()) {

                        final String callbackUrl = expr.get();

                        final List<AnnotationValue<Operation>> operations = callbackAnn.getAnnotations("operation", Operation.class);
                        if (CollectionUtils.isEmpty(operations)) {
                            Map<String, io.swagger.v3.oas.models.callbacks.Callback> callbacks = initCallbacks(swaggerOperation);
                            final io.swagger.v3.oas.models.callbacks.Callback c = new io.swagger.v3.oas.models.callbacks.Callback();
                            c.addPathItem(callbackUrl, new PathItem());
                            callbacks.put(callbackName, c);
                        } else {
                            final PathItem pathItem = new PathItem();
                            for (AnnotationValue<Operation> operation : operations) {
                                final Optional<HttpMethod> operationMethod = operation.get("method", HttpMethod.class);
                                operationMethod.ifPresent(httpMethod -> {
                                    JsonNode jsonNode = toJson(operation.getValues(), context);

                                    try {
                                        final Optional<io.swagger.v3.oas.models.Operation> op = Optional.of(treeToValue(jsonNode, io.swagger.v3.oas.models.Operation.class));
                                        op.ifPresent(operation1 -> setOperationOnPathItem(pathItem, operation1, httpMethod));
                                    } catch (Exception e) {
                                        context.warn("Error reading Swagger Operation for element [" + element + "]: " + e.getMessage(), element);
                                    }
                                });
                            }
                            Map<String, io.swagger.v3.oas.models.callbacks.Callback> callbacks = initCallbacks(swaggerOperation);
                            final io.swagger.v3.oas.models.callbacks.Callback c = new io.swagger.v3.oas.models.callbacks.Callback();
                            c.addPathItem(callbackUrl, pathItem);
                            callbacks.put(callbackName, c);

                        }

                    } else {
                        final Components components = resolveComponents(resolveOpenAPI(context));
                        final Map<String, io.swagger.v3.oas.models.callbacks.Callback> callbackComponents = components.getCallbacks();
                        if (callbackComponents != null && callbackComponents.containsKey(callbackName)) {
                            Map<String, io.swagger.v3.oas.models.callbacks.Callback> callbacks = initCallbacks(swaggerOperation);
                            final io.swagger.v3.oas.models.callbacks.Callback callbackRef = new io.swagger.v3.oas.models.callbacks.Callback();
                            callbackRef.set$ref("#/components/callbacks/" + callbackName);
                            callbacks.put(callbackName, callbackRef);
                        }
                    }
                });

            }
        }
    }

    private Map<String, io.swagger.v3.oas.models.callbacks.Callback> initCallbacks(io.swagger.v3.oas.models.Operation swaggerOperation) {
        Map<String, io.swagger.v3.oas.models.callbacks.Callback> callbacks = swaggerOperation.getCallbacks();
        if (callbacks == null) {
            callbacks = new LinkedHashMap<>();
            swaggerOperation.setCallbacks(callbacks);
        }
        return callbacks;
    }

    private void readTags(MethodElement element, io.swagger.v3.oas.models.Operation swaggerOperation) {
        List<AnnotationValue<Tag>> tagAnnotations = element.getAnnotationValuesByType(Tag.class);
        if (CollectionUtils.isNotEmpty(tagAnnotations)) {
            for (AnnotationValue<Tag> r : tagAnnotations) {
                r.get("name", String.class).ifPresent(swaggerOperation::addTagsItem);
            }
        }
    }

    private Content buildContent(Element definingElement, ClassElement type, String mediaType, OpenAPI openAPI, VisitorContext context) {
        Content content = new Content();
        io.swagger.v3.oas.models.media.MediaType mt = new io.swagger.v3.oas.models.media.MediaType();
        mt.setSchema(resolveSchema(openAPI, definingElement, type, context, mediaType));
        content.addMediaType(mediaType, mt);
        return content;
    }

    private Content buildContent(Element definingElement, ClassElement type, OptionalValues<List> mediaTypes, OpenAPI openAPI, VisitorContext context) {
        Content content = new Content();
        mediaTypes.forEach((key, mediaTypesList) ->  {
            for (Object mediaType: mediaTypesList) {
                io.swagger.v3.oas.models.media.MediaType mt = new io.swagger.v3.oas.models.media.MediaType();
                mt.setSchema(resolveSchema(openAPI, definingElement, type, context, mediaType.toString()));
                content.addMediaType(mediaType.toString(), mt);
            }

        });
        return content;
    }

    /**
     *
     * @return An Instance of {@link PropertyPlaceholderResolver} to resolve placeholders.
     */
    PropertyPlaceholderResolver getPropertyPlaceholderResolver() {
        if (this.propertyPlaceholderResolver == null) {
            this.propertyPlaceholderResolver = new DefaultPropertyPlaceholderResolver(new PropertyResolver() {
                @Override
                public boolean containsProperty(@Nonnull String name) {
                    return false;
                }

                @Override
                public boolean containsProperties(@Nonnull String name) {
                    return false;
                }

                @Nonnull
                @Override
                public <T> Optional<T> getProperty(@Nonnull String name, @Nonnull ArgumentConversionContext<T> conversionContext) {
                    return Optional.empty();
                }
            }, new DefaultConversionService());
        }
        return this.propertyPlaceholderResolver;
    }
}

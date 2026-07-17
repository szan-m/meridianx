package com.hsyd.mx.modules.okxmcp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsyd.mx.modules.okxmcp.tools.OkxFlightMcpTools;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OKX AI MCP Stateless 端点。
 *
 * <p>实现 MCP Stateless JSON-RPC 协议（tools/list + tools/call），
 * 独立于 Spring AI MCP autoconfiguration，避免与现有 /mx/front/mcp 冲突。
 * 端点：POST /mx/front/mcp/okx</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OkxMcpController {

    private static final String JSONRPC_VERSION = "2.0";
    private static final String SERVER_NAME = "okx-flight-mcp";
    private static final String SERVER_VERSION = "1.0.0";

    private final OkxFlightMcpTools tools;
    private final ObjectMapper objectMapper;

    @PostMapping("/mx/front/mcp/okx")
    public ObjectNode handle(@RequestBody JsonNode request) {
        String method = request.path("method").asText("");
        Object id = extractId(request);

        return switch (method) {
            case "initialize" -> buildInitializeResponse(id);
            case "tools/list" -> buildToolsListResponse(id);
            case "tools/call" -> handleToolsCall(request, id);
            default -> buildErrorResponse(id, -32601, "Method not found: " + method);
        };
    }

    private ObjectNode buildInitializeResponse(Object id) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2025-11-25");
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.put("tools", true);
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);
        return wrapResult(id, result);
    }

    private ObjectNode buildToolsListResponse(Object id) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (Method method : OkxFlightMcpTools.class.getDeclaredMethods()) {
            McpTool annotation = method.getAnnotation(McpTool.class);
            if (annotation == null) {
                continue;
            }
            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("name", annotation.name());
            tool.put("description", annotation.description());
            tool.set("inputSchema", buildInputSchema(method));
            toolsArray.add(tool);
        }
        result.set("tools", toolsArray);
        return wrapResult(id, result);
    }

    private ObjectNode handleToolsCall(JsonNode request, Object id) {
        JsonNode params = request.path("params");
        String toolName = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        Method targetMethod = findToolMethod(toolName);
        if (targetMethod == null) {
            return buildErrorResponse(id, -32602, "Unknown tool: " + toolName);
        }

        try {
            Object[] args = resolveArguments(targetMethod, arguments);
            Object resultObj = targetMethod.invoke(tools, args);
            String resultJson = objectMapper.writeValueAsString(resultObj);

            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode textItem = objectMapper.createObjectNode();
            textItem.put("type", "text");
            textItem.put("text", resultJson);
            content.add(textItem);
            result.set("content", content);
            result.put("isError", false);
            return wrapResult(id, result);
        } catch (Exception e) {
            log.error("okx mcp tools/call failed: tool={}", toolName, e);
            String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode textItem = objectMapper.createObjectNode();
            textItem.put("type", "text");
            textItem.put("text", errorMsg != null ? errorMsg : "internal error");
            content.add(textItem);
            result.set("content", content);
            result.put("isError", true);
            return wrapResult(id, result);
        }
    }

    private JsonNode buildInputSchema(Method method) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        for (Parameter param : method.getParameters()) {
            McpToolParam paramAnnotation = param.getAnnotation(McpToolParam.class);
            if (paramAnnotation == null) {
                continue;
            }
            String paramName = param.getName();
            ObjectNode prop = objectMapper.createObjectNode();
            prop.put("type", resolveJsonType(param.getType()));
            prop.put("description", paramAnnotation.description());
            properties.set(paramName, prop);
            if (paramAnnotation.required()) {
                required.add(paramName);
            }
        }
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }

    private Object[] resolveArguments(Method method, JsonNode arguments) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            String paramName = param.getName();
            JsonNode argNode = arguments != null ? arguments.path(paramName) : null;
            args[i] = convertArgument(argNode, param.getType());
        }
        return args;
    }

    private Object convertArgument(JsonNode node, Class<?> targetType) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (targetType == String.class) {
            return node.isTextual() ? node.asText() : node.toString();
        }
        if (targetType == Integer.class || targetType == int.class) {
            return node.isNumber() ? node.asInt() : null;
        }
        if (targetType == Long.class || targetType == long.class) {
            return node.isNumber() ? node.asLong() : null;
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return node.isBoolean() ? node.asBoolean() : null;
        }
        if (targetType == List.class) {
            try {
                return objectMapper.treeToValue(node, List.class);
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return objectMapper.treeToValue(node, targetType);
        } catch (Exception e) {
            return null;
        }
    }

    private Method findToolMethod(String toolName) {
        for (Method method : OkxFlightMcpTools.class.getDeclaredMethods()) {
            McpTool annotation = method.getAnnotation(McpTool.class);
            if (annotation != null && annotation.name().equals(toolName)) {
                return method;
            }
        }
        return null;
    }

    private String resolveJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == Integer.class || type == int.class
                || type == Long.class || type == long.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (List.class.isAssignableFrom(type)) {
            return "array";
        }
        return "object";
    }

    private Object extractId(JsonNode request) {
        JsonNode idNode = request.path("id");
        if (idNode.isNumber()) {
            return idNode.numberValue();
        }
        if (idNode.isTextual()) {
            return idNode.asText();
        }
        return null;
    }

    private ObjectNode wrapResult(Object id, ObjectNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSONRPC_VERSION);
        if (id != null) {
            if (id instanceof Number n) {
                response.put("id", n);
            } else {
                response.put("id", id.toString());
            }
        }
        response.set("result", result);
        return response;
    }

    private ObjectNode buildErrorResponse(Object id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSONRPC_VERSION);
        if (id != null) {
            if (id instanceof Number n) {
                response.put("id", n);
            } else {
                response.put("id", id.toString());
            }
        }
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }
}

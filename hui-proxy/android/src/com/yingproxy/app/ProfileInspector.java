package com.yingproxy.app;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Import-time checks only. Passing these checks never means a profile is runnable. */
final class ProfileInspector {
    private ProfileInspector() {}

    static String inspect(byte[] contents, String suffix) throws Exception {
        if (contents.length == 0) throw new IllegalArgumentException("空配置");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(contents)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("文件不是有效的 UTF-8 文本");
        }
        String data = text.startsWith("\uFEFF") ? text.substring(1) : text;
        if (data.trim().isEmpty()) throw new IllegalArgumentException("空配置");
        if (".json".equals(suffix)) {
            try {
                JSONTokener tokener = new JSONTokener(data);
                Object value = tokener.nextValue();
                if (!(value instanceof JSONObject) || tokener.nextClean() != 0)
                    throw new IllegalArgumentException("JSON 必须是单个对象");
                JSONObject root = (JSONObject) value;
                if (!root.has("outbounds") && !root.has("proxies"))
                    throw new IllegalArgumentException("未发现 outbounds 或 proxies，无法识别代理配置");
            } catch (org.json.JSONException e) {
                throw new IllegalArgumentException("JSON 语法错误");
            }
            return "JSON 已完成语法检查，仍待内核验证";
        }
        if (".yaml".equals(suffix) || ".yml".equals(suffix))
            return "YAML 已导入；连接前由 Mihomo 校验";
        throw new IllegalArgumentException("不支持的配置格式");
    }
}

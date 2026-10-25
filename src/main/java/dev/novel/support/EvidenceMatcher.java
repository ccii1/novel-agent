package dev.novel.support;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;

@Component
public class EvidenceMatcher {
    private final Json json;

    public EvidenceMatcher(Json json) {
        this.json = json;
    }

    public Source source(String body) {
        return new Source(body);
    }

    public Map<String, String> paragraphs(String body) {
        Map<String, String> paragraphs = new LinkedHashMap<>();
        for (String paragraph : body.split("(?U)\\R\\s*\\R")) {
            String text = paragraph.strip();
            if (!text.isBlank()) paragraphs.put("p" + paragraphs.size(), text);
        }
        return paragraphs;
    }

    private static String compact(String value) {
        return value.codePoints().filter(code -> !Character.isWhitespace(code) && !Character.isSpaceChar(code) && code != 0x85)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    public class Source {
        private final String body;
        private final String text;
        private final int[] positions;
        private final String dialogueText;
        private final int[] dialoguePositions;

        private Source(String body) {
            this.body = body;
            positions = new int[body.length()];
            dialoguePositions = new int[body.length()];
            StringBuilder normalized = new StringBuilder();
            StringBuilder dialogue = new StringBuilder();
            for (int index = 0; index < body.length(); index++) {
                char character = body.charAt(index);
                if (Character.isWhitespace(character) || Character.isSpaceChar(character) || character == '\u0085')
                    continue;
                positions[normalized.length()] = index;
                normalized.append(character);
                if ("“”‘’「」『』".indexOf(character) < 0) {
                    dialoguePositions[dialogue.length()] = index;
                    dialogue.append(character);
                }
            }
            text = normalized.toString();
            dialogueText = dialogue.toString();
        }

        private String match(String candidate) {
            if (candidate.isEmpty()) return null;
            if (body.contains(candidate)) return candidate;
            String normalized = compact(candidate);
            if (normalized.isEmpty()) return null;
            int start = text.indexOf(normalized);
            if (start >= 0) return body.substring(positions[start], positions[start + normalized.length() - 1] + 1);
            // 模型省略正文对话引号时，恢复包含原有引号的连续正文片段。
            start = dialogueText.indexOf(normalized);
            return start < 0 ? null : body.substring(dialoguePositions[start], dialoguePositions[start + normalized.length() - 1] + 1);
        }

        private String candidate(String quote) {
            if (match(quote) != null) return quote;
            String normalized = compact(quote);
            if (normalized.length() < 2) return quote;
            var pairs = Map.of('“', '”', '‘', '’', '「', '」', '『', '』', '"', '"', '\'', '\'');
            Character closing = pairs.get(normalized.charAt(0));
            if (closing != null && closing == normalized.charAt(normalized.length() - 1)) {
                String inner = normalized.substring(1, normalized.length() - 1);
                if (match(inner) != null) return inner;
            }
            return quote;
        }

        public String find(String quote) {
            String candidate = candidate(quote);
            String normalized = compact(candidate);
            if (normalized.codePointCount(0, normalized.length()) < 4) return null;
            return match(candidate);
        }

        public String require(String quote, String label) {
            String candidate = candidate(quote);
            String normalized = compact(candidate);
            if (normalized.codePointCount(0, normalized.length()) < 4)
                throw new IllegalArgumentException(label + "的证据不足4个字符；空白和额外引用引号不计入原文长度，模型响应已保存");
            String result = match(candidate);
            if (result == null) throw new IllegalArgumentException(label + "的证据无法连续匹配：" +
                    json.write(quote.length() > 100 ? quote.substring(0, 100) + "…" : quote) +
                    "；换行与空白差异已允许，文字和标点必须与来源一致，模型响应已保存");
            return result;
        }
    }
}

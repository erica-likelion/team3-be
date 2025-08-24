package likelion.service;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
@RequiredArgsConstructor
public class AiChatService {

    private final OpenAiService openAiService;

    public String getAnalysisResponseFromAI(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "AI 프롬프트가 비어 있습니다.");
        }

        try {
            ChatMessage userMessage = new ChatMessage("user", prompt);
            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o")
                    .messages(List.of(userMessage))
                    .build();

            return openAiService.createChatCompletion(chatCompletionRequest)
                    .getChoices().get(0).getMessage().getContent();

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 서비스 호출 중 오류가 발생했습니다.", e);
        }

    }
}
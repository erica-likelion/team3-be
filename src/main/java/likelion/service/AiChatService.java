package likelion.service;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiChatService {

    private final OpenAiService openAiService;

    public String getAnalysisResponseFromAI(String prompt) {
        ChatMessage userMessage = new ChatMessage("user", prompt);

        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model("gpt-3.5-turbo")
                .messages(List.of(userMessage))
                .build();

        return openAiService.createChatCompletion(chatCompletionRequest)
                .getChoices().get(0).getMessage().getContent();
    }
}
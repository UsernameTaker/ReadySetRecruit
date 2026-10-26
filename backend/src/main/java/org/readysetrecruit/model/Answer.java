package org.readysetrecruit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

// one answer in an application. we copy the question text in here too, because if the team
// edits or deletes the question later the old applications would stop making sense
@Embeddable
public class Answer {
    private Long questionId;
    @Column(length = 1000)
    private String prompt;
    @Column(length = 20000)
    private String response;

    public Answer() {}
    public Answer(Long questionId, String prompt, String response) {
        this.questionId = questionId;
        this.prompt = prompt;
        this.response = response;
    }

    public Long getQuestionId() { return questionId; }
    public String getPrompt() { return prompt; }
    public String getResponse() { return response; }
}

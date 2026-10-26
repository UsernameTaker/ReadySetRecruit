package org.readysetrecruit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One answer inside an application. The question prompt is copied in, so applications stay readable
 * even if the team later edits or deletes the question.
 */
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

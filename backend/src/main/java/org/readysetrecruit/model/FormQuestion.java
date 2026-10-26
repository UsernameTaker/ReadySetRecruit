package org.readysetrecruit.model;

import jakarta.persistence.*;

@Entity
@Table(name = "form_questions")
public class FormQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private ApplicationForm form;

    @Column(name = "sort_order")
    private int position;

    @Column(nullable = false, length = 1000)
    private String prompt;
    @Column(length = 1000)
    private String helpText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType type = QuestionType.LONG_TEXT;

    private boolean required = true;
    // max words allowed in the answer. Null = no limit
    private Integer wordLimit;
    // choices for MULTIPLE_CHOICE, one per line
    @Column(length = 2000)
    private String options;

    public Long getId() { return id; }
    public ApplicationForm getForm() { return form; }
    public void setForm(ApplicationForm form) { this.form = form; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getHelpText() { return helpText; }
    public void setHelpText(String helpText) { this.helpText = helpText; }
    public QuestionType getType() { return type; }
    public void setType(QuestionType type) { this.type = type; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public Integer getWordLimit() { return wordLimit; }
    public void setWordLimit(Integer wordLimit) { this.wordLimit = wordLimit; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
}

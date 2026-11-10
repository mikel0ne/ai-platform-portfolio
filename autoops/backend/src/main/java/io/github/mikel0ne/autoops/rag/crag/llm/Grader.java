package io.github.mikel0ne.autoops.rag.crag.llm;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface Grader {

    @UserMessage(fromResource = "/prompts/crag_grade.txt")
    GradeResult grade(@V("questions") String questions, @V("context") String context);
}

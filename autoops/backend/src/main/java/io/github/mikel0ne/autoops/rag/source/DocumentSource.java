package io.github.mikel0ne.autoops.rag.source;

import java.util.List;

public interface DocumentSource {

    String name();

    List<SourceDocument> read();
}

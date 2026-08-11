package com.repopilot.indexer.embedding;

import java.util.List;

public interface EmbeddingModelClient {

    EmbeddingBatch embed(List<String> inputs);

    record EmbeddingBatch(String provider, String model, int dimension, List<List<Double>> vectors) {
    }
}

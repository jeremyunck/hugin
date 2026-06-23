package com.example.integration.modelsettings;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Looks up a model's maximum context window (in tokens) from the OpenRouter model catalog that
 * {@link OpenRouterModelService} keeps in sync. Used by the chat run loop to decide when a
 * conversation must be compacted to stay within the active model's context limit.
 *
 * <p>When the catalog has no entry for a model (for example a non-OpenRouter provider, or a model
 * OpenRouter does not publish a context length for) the limit is reported as empty and callers
 * skip compaction rather than guessing.
 */
@Service
public class ModelContextService {

    private final OpenRouterModelRepository modelRepository;

    public ModelContextService(OpenRouterModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    /** The context window (tokens) advertised by OpenRouter for {@code modelId}, if known. */
    public Optional<Long> contextLimit(String modelId) {
        return modelRepository.findById(modelId)
                .map(OpenRouterModelRecord::contextLength)
                .filter(limit -> limit != null && limit > 0);
    }
}

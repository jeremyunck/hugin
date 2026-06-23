package com.example.integration.modelsettings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Repository
public class OpenRouterModelRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpenRouterModelRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<OpenRouterModelRecord> findAll() {
        return jdbcTemplate.query(
                """
                        select id, name, description, context_length, prompt_price, completion_price,
                               reasoning_options, supported_parameters, updated_at
                        from openrouter_models
                        order by lower(name) asc, id asc
                        """,
                (rs, rowNum) -> new OpenRouterModelRecord(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getObject("context_length") == null ? null : rs.getLong("context_length"),
                        rs.getString("prompt_price"),
                        rs.getString("completion_price"),
                        readList(rs.getString("reasoning_options")),
                        readList(rs.getString("supported_parameters")),
                        toInstant(rs.getTimestamp("updated_at"))));
    }

    public java.util.Optional<OpenRouterModelRecord> findById(String id) {
        if (id == null || id.isBlank()) {
            return java.util.Optional.empty();
        }
        return jdbcTemplate.query(
                """
                        select id, name, description, context_length, prompt_price, completion_price,
                               reasoning_options, supported_parameters, updated_at
                        from openrouter_models
                        where id = ?
                        """,
                (rs, rowNum) -> new OpenRouterModelRecord(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getObject("context_length") == null ? null : rs.getLong("context_length"),
                        rs.getString("prompt_price"),
                        rs.getString("completion_price"),
                        readList(rs.getString("reasoning_options")),
                        readList(rs.getString("supported_parameters")),
                        toInstant(rs.getTimestamp("updated_at"))),
                id).stream().findFirst();
    }

    public Instant latestUpdate() {
        Timestamp timestamp = jdbcTemplate.query(
                "select max(updated_at) as updated_at from openrouter_models",
                rs -> rs.next() ? rs.getTimestamp("updated_at") : null);
        return toInstant(timestamp);
    }

    public void upsert(OpenRouterModelRecord record) {
        int updated = jdbcTemplate.update(
                """
                        update openrouter_models
                        set name = ?, description = ?, context_length = ?, prompt_price = ?, completion_price = ?,
                            reasoning_options = ?, supported_parameters = ?, updated_at = ?
                        where id = ?
                        """,
                record.name(),
                record.description(),
                record.contextLength(),
                record.promptPrice(),
                record.completionPrice(),
                writeList(record.reasoningOptions()),
                writeList(record.supportedParameters()),
                timestamp(record.updatedAt()),
                record.id());
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            insert into openrouter_models
                                (id, name, description, context_length, prompt_price, completion_price,
                                 reasoning_options, supported_parameters, updated_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    record.id(),
                    record.name(),
                    record.description(),
                    record.contextLength(),
                    record.promptPrice(),
                    record.completionPrice(),
                    writeList(record.reasoningOptions()),
                    writeList(record.supportedParameters()),
                    timestamp(record.updatedAt()));
        }
    }

    public void deleteMissing(Set<String> idsToKeep) {
        if (idsToKeep == null || idsToKeep.isEmpty()) {
            jdbcTemplate.update("delete from openrouter_models");
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(idsToKeep.size(), "?"));
        jdbcTemplate.update("delete from openrouter_models where id not in (" + placeholders + ")", idsToKeep.toArray());
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse stored model metadata", e);
        }
    }

    private String writeList(List<String> items) {
        try {
            return objectMapper.writeValueAsString(items == null ? List.of() : items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize model metadata", e);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

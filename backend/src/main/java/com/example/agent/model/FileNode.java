package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A single entry in a sandbox workspace file tree, returned by
 * {@code GET /api/sandboxes/{id}/files} so the UI can render the live workspace.
 *
 * <p>{@code path} is relative to the workspace root. {@code type} is either {@code "file"} or
 * {@code "dir"}. {@code size} is the byte size for files (null for directories), and
 * {@code children} holds a directory's entries (null for files).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileNode(
        String name,
        String path,
        String type,
        Long size,
        List<FileNode> children) {

    public static final String FILE = "file";
    public static final String DIR = "dir";

    public static FileNode file(String name, String path, long size) {
        return new FileNode(name, path, FILE, size, null);
    }

    public static FileNode directory(String name, String path, List<FileNode> children) {
        return new FileNode(name, path, DIR, null, children);
    }
}

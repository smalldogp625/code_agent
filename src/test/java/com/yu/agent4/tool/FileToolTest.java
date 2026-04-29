package com.yu.agent4.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteAndReadFileWithinWorkspace() throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());

        try {
            FileTool fileTool = new FileTool();

            String writeResult = fileTool.write("notes/todo.txt", "line1\nline2\nline3");
            String readResult = fileTool.read("notes/todo.txt", 2);

            assertEquals("Wrote 17 bytes to notes/todo.txt", writeResult);
            assertEquals("line1\nline2\n... (1 more lines)", readResult);
            assertEquals(
                    "line1\nline2\nline3",
                    Files.readString(tempDir.resolve("notes/todo.txt"), StandardCharsets.UTF_8)
            );
        }
        finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void shouldBlockPathThatEscapesWorkspace() {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());

        try {
            FileTool fileTool = new FileTool();

            String readResult = fileTool.read("../secret.txt", null);
            String writeResult = fileTool.write("../secret.txt", "blocked");

            assertTrue(readResult.startsWith("Error: Path escapes workspace: ../secret.txt"));
            assertTrue(writeResult.startsWith("Error: Path escapes workspace: ../secret.txt"));
        }
        finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void shouldEditMatchedTextWithinWorkspace() throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());

        try {
            Files.createDirectories(tempDir.resolve("notes"));
            Files.writeString(tempDir.resolve("notes/todo.txt"), "alpha\nbeta\ngamma", StandardCharsets.UTF_8);
            FileTool fileTool = new FileTool();

            String editResult = fileTool.edit("notes/todo.txt", "beta", "BETA");

            assertEquals("Edited notes/todo.txt", editResult);
            assertEquals(
                    "alpha\nBETA\ngamma",
                    Files.readString(tempDir.resolve("notes/todo.txt"), StandardCharsets.UTF_8)
            );
        }
        finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void shouldReturnErrorWhenEditTargetTextIsMissing() throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());

        try {
            Files.writeString(tempDir.resolve("todo.txt"), "alpha\nbeta", StandardCharsets.UTF_8);
            FileTool fileTool = new FileTool();

            String editResult = fileTool.edit("todo.txt", "gamma", "GAMMA");

            assertEquals("Error: Text not found in todo.txt", editResult);
        }
        finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }
}

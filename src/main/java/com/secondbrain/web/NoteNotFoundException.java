package com.secondbrain.web;

/** Заметки с таким идентификатором нет. */
public class NoteNotFoundException extends RuntimeException {

    private final String noteId;

    public NoteNotFoundException(String noteId) {
        super("Заметка не найдена: " + noteId);
        this.noteId = noteId;
    }

    public String noteId() {
        return noteId;
    }
}

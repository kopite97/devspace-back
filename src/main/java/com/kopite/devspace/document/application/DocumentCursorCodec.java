package com.kopite.devspace.document.application;
import java.util.UUID;
public interface DocumentCursorCodec {
    String encode(UUID workspace,String context,String position);
    String decode(UUID workspace,String context,String cursor);
}

package com.kopite.devspace.documentspace.application;

import com.kopite.devspace.workspace.domain.PersonalWorkspace;
import java.util.UUID;

/** Owner-specific resolution is implemented outside the generic Document domain. Caller owns the transaction. */
public interface DocumentSpaceAccess {
    PersonalWorkspace read(UUID user,UUID space);
    PersonalWorkspace lock(UUID user,UUID space);
    PersonalWorkspace maintenance(UUID space);
}

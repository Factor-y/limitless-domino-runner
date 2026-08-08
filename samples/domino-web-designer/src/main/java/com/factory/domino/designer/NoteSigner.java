package com.factory.domino.designer;

import lotus.domino.Database;
import lotus.domino.NotesException;
import lotus.domino.NotesFactory;
import lotus.domino.NotesThread;
import lotus.domino.Session;

/**
 * Signs notes through the classic {@code lotus.domino} API.
 *
 * <p>This exists because JNX cannot do it. {@code Document.sign()} in JNX fails on design notes
 * with <em>"Signature on document is invalid (inconsistent field signatures)"</em> (error 0x2dc)
 * — verified on a freshly imported view, with and without a prior {@code unsign()}, and on notes
 * carrying no signature items at all. Its other overload needs a {@code UserId} from an ID
 * vault, which a local client does not have.
 *
 * <p>The classic API's {@code Database.sign(type, existingSigsOnly, noteId, true)} does work,
 * and signs a single note when given its ID — so imported notes can be signed individually
 * rather than by re-signing the whole design.
 *
 * <p>Usable from the designer's jar because {@code lotus.domino} is one of the packages the
 * runner's class loader shares with the parent.
 */
final class NoteSigner {

  /** Signs every class of note; the caller decides which notes, not which kinds. */
  private static final int SIGN_ALL = Database.DBSIGN_DOC_ALL;

  private NoteSigner() {
  }

  /**
   * Signs one note, addressed by its hexadecimal note ID.
   *
   * <p>Runs on the caller's thread, which must already hold a Domino context — that is the
   * case inside a {@code DominoExecutor} worker. {@code sinitThread()} is still paired here:
   * the Notes C API reference-counts thread initialization, so nesting is safe and the classic
   * API needs its own bookkeeping.
   *
   * @param server server name, empty for local
   * @param dbPath database file path
   * @param noteIdHex note ID in hexadecimal, as {@code String.format("%X", noteId)} produces
   */
  static void signNote(String server, String dbPath, String noteIdHex) throws NotesException {
    NotesThread.sinitThread();
    try {
      Session session = NotesFactory.createSession();
      try {
        Database database = session.getDatabase(server == null ? "" : server, dbPath);
        if (database == null || !database.isOpen()) {
          throw new IllegalStateException("cannot open '" + dbPath + "' for signing");
        }
        // existingSigsOnly=false: sign whether or not a signature is already present.
        // nameIsNoteid=true: the 'name' argument is a note ID, which is what restricts the
        // operation to this one note.
        database.sign(SIGN_ALL, false, noteIdHex, true);
      } finally {
        session.recycle();
      }
    } finally {
      NotesThread.stermThread();
    }
  }
}

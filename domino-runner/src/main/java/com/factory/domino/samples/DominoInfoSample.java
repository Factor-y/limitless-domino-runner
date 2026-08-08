package com.factory.domino.samples;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import com.hcl.domino.DominoClient;
import com.hcl.domino.DominoClientBuilder;
import com.hcl.domino.dbdirectory.DatabaseData;
import com.hcl.domino.dbdirectory.DbDirectory;
import com.hcl.domino.dbdirectory.DirEntry;
import com.hcl.domino.dbdirectory.FileType;

import lotus.domino.NotesException;
import lotus.domino.NotesFactory;
import lotus.domino.NotesThread;
import lotus.domino.Session;

/**
 * Demonstrates using both Domino APIs from a single program launched by {@code DominoRunner}:
 * the classic {@code lotus.domino} API for session details, and Domino JNX to list the local
 * databases.
 *
 * <p>Nothing here initializes the Domino runtime — the runner has already done that, which is
 * exactly the point of running under it.
 *
 * <p>Usage: {@code DominoInfoSample [max-databases]} (default 25, {@code 0} for no limit).
 */
public final class DominoInfoSample {

  private static final int DEFAULT_LIMIT = 25;

  public static void main(String[] args) throws Exception {
    int limit = DEFAULT_LIMIT;
    if (args.length > 0) {
      try {
        limit = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Ignoring non-numeric limit '" + args[0] + "', using " + limit + ".");
      }
    }

    printSessionInfo();
    System.out.println();
    printLocalDatabases(limit);
  }

  /** Classic API: session identity and product version. */
  private static void printSessionInfo() throws NotesException {
    System.out.println("=== Notes.jar (lotus.domino) ===");

    // The runner's thread already has a Domino context; the Notes C API reference-counts
    // thread initialization, so this pairs safely with the runner's own.
    NotesThread.sinitThread();
    try {
      Session session = NotesFactory.createSession();
      try {
        System.out.println("  Product version : " + session.getNotesVersion());
        System.out.println("  User name       : " + session.getUserName());
        System.out.println("  Platform        : " + session.getPlatform());
        System.out.println("  Common user name: " + session.getCommonUserName());
      } finally {
        session.recycle();
      }
    } finally {
      NotesThread.stermThread();
    }
  }

  /** JNX: enumerate the databases in the local data directory. */
  private static void printLocalDatabases(int limit) {
    System.out.println("=== Domino JNX (local databases) ===");

    // asIDUser() runs as the identity of the ID the runtime was initialized with.
    try (DominoClient client = DominoClientBuilder.newDominoClient().asIDUser().build()) {
      DbDirectory directory = client.openDbDirectory();

      // An empty server name means the local data directory.
      List<DirEntry> entries = directory.query()
          .withServer("")
          .withFileTypes(EnumSet.of(FileType.DBANY))
          .stream()
          .collect(Collectors.toList());

      System.out.println("  Databases found : " + entries.size());
      System.out.println();

      int shown = 0;
      for (DirEntry entry : entries) {
        if (limit > 0 && shown >= limit) {
          System.out.println("  ... " + (entries.size() - shown) + " more (raise the limit to "
              + "see them all)");
          break;
        }
        String title = entry instanceof DatabaseData
            ? ((DatabaseData) entry).getTitle()
            : "";
        System.out.printf("  %-40s %s%n", entry.getFilePath(), title == null ? "" : title);
        shown++;
      }
    }
  }
}

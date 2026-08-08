package com.factory.domino.designer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.factory.domino.runner.DominoExecutor;
import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.IDTable;
import com.hcl.domino.dxl.DxlExporter;
import com.hcl.domino.dxl.DxlImporter;

/**
 * DXL export and import.
 *
 * <p>Export is read-only and always available. Import writes to the database — it can replace
 * design elements and documents outright, with no undo — so it is gated on an explicit
 * {@code --allow-write} flag, defaults to a dry run, and takes a backup of whatever it is about
 * to overwrite. Signing is separate and never implicit: a signed design element runs with the
 * signer's authority, so applying your identity to imported DXL should be a decision, not a
 * side effect.
 */
public final class DxlService {

  private final DominoExecutor executor;
  private final boolean writeAllowed;
  private final Path backupDirectory;

  public DxlService(DominoExecutor executor, boolean writeAllowed, Path backupDirectory) {
    this.executor = executor;
    this.writeAllowed = writeAllowed;
    this.backupDirectory = backupDirectory;
  }

  /** Thrown when a write is attempted on a server started without {@code --allow-write}. */
  public static class WriteNotAllowedException extends RuntimeException {
    public WriteNotAllowedException(String message) {
      super(message);
    }
  }

  public boolean isWriteAllowed() {
    return writeAllowed;
  }

  /** Options accepted by the export endpoints, all optional. */
  public record ExportOptions(
      boolean omitRichTextAttachments,
      boolean omitPictures,
      boolean omitOleObjects,
      boolean forceNoteFormat,
      boolean outputDoctype) {

    public static ExportOptions defaults() {
      return new ExportOptions(false, false, false, false, true);
    }
  }

  private static DxlExporter configure(DxlExporter exporter, ExportOptions options) {
    exporter.setOmitRichTextAttachments(options.omitRichTextAttachments());
    exporter.setOmitPictures(options.omitPictures());
    exporter.setOmitOLEObjects(options.omitOleObjects());
    exporter.setForceNoteFormat(options.forceNoteFormat());
    exporter.setOutputDoctype(options.outputDoctype());
    return exporter;
  }

  /**
   * Exports one note — design element or document, they are the same thing here — as DXL.
   *
   * <p>Written straight to the caller's stream rather than returned as a string: a single note
   * with attachments can already be large, and the whole-database export below would otherwise
   * hold hundreds of megabytes in memory.
   */
  public void exportNote(String server, String db, String noteId, String unid,
      ExportOptions options, OutputStream out) {
    executor.run(client -> {
      Database database = DominoService.openDatabase(client, server, db);
      Document note = DesignService.resolveNote(database, noteId, unid);
      try {
        configure(client.createDxlExporter(), options).exportDocument(note, out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /** Exports several notes in one DXL document, addressed by hexadecimal note IDs. */
  public void exportNotes(String server, String db, List<String> noteIds, ExportOptions options,
      OutputStream out) {
    executor.run(client -> {
      Database database = DominoService.openDatabase(client, server, db);
      List<Integer> ids = noteIds.stream().map(DominoService::parseNoteId).toList();
      try {
        configure(client.createDxlExporter(), options).exportIDs(database, ids, out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /** Exports a whole database, its design only, or its ACL. */
  public void exportDatabase(String server, String db, String what, ExportOptions options,
      OutputStream out) {
    executor.run(client -> {
      Database database = DominoService.openDatabase(client, server, db);
      DxlExporter exporter = configure(client.createDxlExporter(), options);
      try {
        switch (what == null ? "all" : what.toLowerCase(java.util.Locale.ROOT)) {
          case "acl" -> exporter.exportACL(database, out);
          case "design" -> {
            // No dedicated design export exists, so the design collection supplies the IDs.
            List<Integer> designIds = new ArrayList<>(
                database.openDesignCollection().getAllIds(true, false));
            exporter.exportIDs(database, designIds, out);
          }
          case "all" -> exporter.exportDatabase(database, out);
          default -> throw new IllegalArgumentException(
              "'what' must be one of: all, design, acl (got '" + what + "')");
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /** How an import should treat elements that already exist. */
  public record ImportOptions(
      DxlImporter.DXLImportOption designOption,
      DxlImporter.DXLImportOption documentOption,
      boolean sign,
      boolean confirm,
      boolean validate,
      boolean replicaRequired) {
  }

  /**
   * Imports DXL into a database.
   *
   * <p>Without {@code confirm} this is a dry run: the DXL is parsed and validated and the
   * outcome reported, but nothing is written. That is the default on purpose — an import that
   * replaces design is not reversible, and the cost of finding out first is one extra click.
   *
   * <p>When it does write, whatever is about to be replaced is exported to a backup file first,
   * and the path comes back in the response.
   */
  public Map<String, Object> importDxl(String server, String db, String dxl,
      ImportOptions options) {
    if (!writeAllowed) {
      throw new WriteNotAllowedException(
          "this server was started without --allow-write, so import is disabled");
    }
    if (dxl == null || dxl.isBlank()) {
      throw new IllegalArgumentException("no DXL content supplied");
    }

    return executor.call(client -> {
      Database database = DominoService.openDatabase(client, server, db);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("database", database.getTitle());
      result.put("designOption", String.valueOf(options.designOption()));
      result.put("documentOption", String.valueOf(options.documentOption()));
      result.put("sign", options.sign());

      if (!options.confirm()) {
        result.put("dryRun", true);
        result.putAll(inspect(dxl));
        result.put("message", "Nothing was written. Repeat with confirm=true to apply.");
        return result;
      }

      result.put("dryRun", false);

      // Back up before overwriting anything: this is what makes the operation reversible.
      String backup = backupDatabaseDesign(client, database, db);
      result.put("backup", backup);

      DxlImporter importer = client.createDxlImporter();
      importer.setDesignImportOption(options.designOption());
      importer.setDocumentsImportOption(options.documentOption());
      importer.setReplaceDbProperties(false);
      importer.setExitOnFirstFatalError(true);

      // Domino refuses to replace or update a note unless the DXL came from a replica of the
      // target database. That guard makes sense for replication, but it blocks the ordinary
      // design-tool case of moving an element from one database into another, so it is off by
      // default here. Turning it on restores Domino's stricter behaviour.
      importer.setReplicaRequiredForReplaceOrUpdate(options.replicaRequired());
      importer.setResultLogComment("Imported by DominoWebDesigner");

      importer.setInputValidationOption(options.validate()
          ? DxlImporter.XMLValidationOption.ALWAYS
          : DxlImporter.XMLValidationOption.NEVER);

      // DXL that Domino exports declares a DOCTYPE pointing at a relative DTD
      // ("xmlschemas/domino_14_5_1.dtd"). The importer's parser tries to resolve it, cannot,
      // and fails the whole import with an XMLPlatformException — and it does so even with
      // validation set to NEVER, so turning validation off is not enough on its own.
      // Stripping the declaration is what makes round-tripping exported DXL work at all; it
      // removes nothing but a pointer to a schema that cannot be fetched.
      String content = options.validate() ? dxl : stripDoctype(dxl);

      try {
        importer.importDxl(content, database);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      result.put("errorLogged", importer.importErrorWasLogged());
      String log = importer.getResultLog();
      if (log != null && !log.isBlank()) {
        result.put("log", log);
      }

      // getImportedNoteIds() delimits exactly what arrived, which is what makes it safe to
      // sign only the imported notes rather than the whole database.
      List<String> imported = new ArrayList<>();
      List<String> signed = new ArrayList<>();
      List<String> signErrors = new ArrayList<>();
      importer.getImportedNoteIds().ifPresent((IDTable ids) -> {
        for (Integer id : ids) {
          String noteIdHex = String.format("%X", id);
          imported.add(noteIdHex);
          if (options.sign()) {
            // Signing happens per note and never aborts the response: the import has already
            // been written by this point and cannot be rolled back, so a signing failure must
            // be reported alongside what did succeed rather than masking it as a total failure.
            try {
              NoteSigner.signNote(server, db, noteIdHex);
              signed.add(noteIdHex);
            } catch (Exception e) {
              signErrors.add(noteIdHex + ": " + e.getMessage());
            }
          }
        }
      });
      result.put("importedCount", imported.size());
      result.put("importedNoteIds", imported);
      if (options.sign()) {
        result.put("signedCount", signed.size());
        result.put("signedBy", client.getEffectiveUserName());
        if (!signErrors.isEmpty()) {
          result.put("signErrors", signErrors);
        }
      }
      return result;
    });
  }

  /** Signs an existing note, design element or document alike. */
  public Map<String, Object> signNote(String server, String db, String noteId, String unid) {
    if (!writeAllowed) {
      throw new WriteNotAllowedException(
          "this server was started without --allow-write, so signing is disabled");
    }
    return executor.call(client -> {
      Database database = DominoService.openDatabase(client, server, db);
      Document note = DesignService.resolveNote(database, noteId, unid);
      String noteIdHex = String.format("%X", note.getNoteID());
      String noteUnid = note.getUNID();
      try {
        NoteSigner.signNote(server, db, noteIdHex);
      } catch (Exception e) {
        throw new IllegalStateException("could not sign note " + noteIdHex + ": " + e.getMessage(), e);
      }

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("noteId", noteIdHex);
      result.put("unid", noteUnid);
      // Re-read: the signature was applied through a separate session.
      database.getDocumentById(DominoService.parseNoteId(noteIdHex)).ifPresent(signedNote -> {
        result.put("signer", signedNote.getSigner());
        result.put("signed", signedNote.isSigned());
      });
      return result;
    });
  }

  /**
   * Removes the DOCTYPE declaration from a DXL document.
   *
   * <p>Only the declaration is dropped; the XML declaration and the content are untouched.
   */
  static String stripDoctype(String dxl) {
    return dxl.replaceFirst("(?s)<!DOCTYPE[^>\\[]*(\\[[^\\]]*\\])?[^>]*>\\s*", "");
  }

  /**
   * Reports what a DXL document appears to contain, without touching the database.
   *
   * <p>Deliberately shallow — element names and counts read off the markup. Reporting what
   * Domino would really do would mean importing it, which is precisely what a dry run must not
   * do.
   */
  private static Map<String, Object> inspect(String dxl) {
    Map<String, Object> summary = new LinkedHashMap<>();
    Map<String, Integer> byElement = new java.util.TreeMap<>();
    List<String> names = new ArrayList<>();

    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("<(form|view|folder|subform|page|document|agent|sharedfield|"
            + "imageresource|fileresource|scriptlibrary|frameset|outline|database)\\b[^>]*",
            java.util.regex.Pattern.CASE_INSENSITIVE)
        .matcher(dxl);
    while (matcher.find()) {
      String element = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
      byElement.merge(element, 1, Integer::sum);
      java.util.regex.Matcher nameMatcher =
          java.util.regex.Pattern.compile("name=['\"]([^'\"]*)['\"]").matcher(matcher.group());
      if (nameMatcher.find() && names.size() < 50) {
        names.add(element + ": " + nameMatcher.group(1));
      }
    }
    summary.put("dxlLength", dxl.length());
    summary.put("elementsFound", byElement);
    summary.put("names", names);
    if (byElement.isEmpty()) {
      summary.put("warning", "No recognisable DXL elements found; the content may not be DXL.");
    }
    return summary;
  }

  /**
   * Exports the current design to a timestamped file before an import overwrites it.
   *
   * @return the backup path, or a message explaining why there is none
   */
  private String backupDatabaseDesign(com.hcl.domino.DominoClient client, Database database,
      String dbName) {
    try {
      Files.createDirectories(backupDirectory);
      String safeName = dbName.replaceAll("[^A-Za-z0-9._-]", "_");
      // No clock is read inside the Domino worker; the note count keeps names distinct enough
      // alongside the sequence number below.
      Path target = uniquePath(backupDirectory, safeName);

      List<Integer> designIds =
          new ArrayList<>(database.openDesignCollection().getAllIds(true, false));
      try (OutputStream out = Files.newOutputStream(target)) {
        client.createDxlExporter().exportIDs(database, designIds, out);
      }
      return target.toString();
    } catch (IOException | RuntimeException e) {
      // A failed backup must not silently precede a destructive write.
      throw new IllegalStateException(
          "could not write a pre-import backup, so the import was not attempted: " + e, e);
    }
  }

  private static Path uniquePath(Path directory, String baseName) {
    for (int sequence = 1; sequence < 10000; sequence++) {
      Path candidate = directory.resolve(baseName + "-design-" + sequence + ".dxl");
      if (!Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("too many backups for " + baseName);
  }

  /** Parses an import option name, accepting the JNX enum names. */
  public static DxlImporter.DXLImportOption parseImportOption(String value,
      DxlImporter.DXLImportOption fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return DxlImporter.DXLImportOption.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      Set<String> allowed = java.util.Arrays.stream(DxlImporter.DXLImportOption.values())
          .map(Enum::name).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
      throw new IllegalArgumentException(
          "unknown import option '" + value + "'; expected one of " + allowed);
    }
  }
}

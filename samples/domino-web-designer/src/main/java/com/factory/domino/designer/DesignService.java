package com.factory.domino.designer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.factory.domino.runner.DominoExecutor;
import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DominoCollection;
import com.hcl.domino.design.AboutDocument;
import com.hcl.domino.design.CompositeApplication;
import com.hcl.domino.design.CompositeComponent;
import com.hcl.domino.design.DesignAgent;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.design.FileResource;
import com.hcl.domino.design.Folder;
import com.hcl.domino.design.Form;
import com.hcl.domino.design.Frameset;
import com.hcl.domino.design.ImageResource;
import com.hcl.domino.design.Navigator;
import com.hcl.domino.design.Outline;
import com.hcl.domino.design.Page;
import com.hcl.domino.design.ScriptLibrary;
import com.hcl.domino.design.SharedActions;
import com.hcl.domino.design.SharedColumn;
import com.hcl.domino.design.SharedField;
import com.hcl.domino.design.StyleSheet;
import com.hcl.domino.design.Subform;
import com.hcl.domino.design.Theme;
import com.hcl.domino.design.UsingDocument;
import com.hcl.domino.design.View;
import com.hcl.domino.design.XPage;

/**
 * Enumerates the design of a database.
 *
 * <p>Getting a <em>complete</em> listing takes two passes, and the reason is worth stating.
 * JNX's own {@code queryDesignElements} throws {@code NotYetImplementedException} on databases
 * containing element kinds it cannot model, which takes down the whole listing rather than
 * skipping one entry. Asking for one type at a time is safe, but only covers types JNX models
 * — on a stock names.nsf that is 415 of 428 notes; the remainder are things like Eclipse
 * metadata files stored as design notes.
 *
 * <p>So: ask per type for everything JNX understands, then sweep the raw design collection for
 * whatever those passes missed and describe it from its own note items. Nothing is hidden
 * because a library does not have a class for it.
 */
public final class DesignService {

  /**
   * Design types JNX can model, in the order they are listed.
   *
   * <p>{@code ScriptLibrary} covers the script library family deliberately: asking for the
   * subtypes ({@code LotusScriptLibrary}, {@code JavaLibrary}, …) throws
   * {@code IllegalArgumentException}, while the supertype returns them all.
   */
  private static final List<Class<? extends DesignElement>> DESIGN_TYPES = List.of(
      Form.class, Subform.class, View.class, Folder.class, Page.class, Frameset.class,
      DesignAgent.class, ScriptLibrary.class, SharedField.class, SharedColumn.class,
      SharedActions.class, Outline.class, Navigator.class, XPage.class,
      ImageResource.class, FileResource.class, StyleSheet.class, Theme.class,
      CompositeApplication.class, CompositeComponent.class,
      AboutDocument.class, UsingDocument.class);

  private final DominoExecutor executor;

  public DesignService(DominoExecutor executor) {
    this.executor = executor;
  }

  /**
   * Lists every design element in a database.
   *
   * @param typeFilter when set, only elements of that type are returned (case-insensitive)
   * @param withSignatures read the signer of each element. Off by default: it opens every
   *     design note, which on a large database is the difference between fast and slow.
   */
  public Map<String, Object> listDesign(String server, String db, String typeFilter,
      boolean withSignatures) {
    return executor.call(client -> {
      Database database = DominoService.openDatabase(client, server, db);

      List<Map<String, Object>> elements = new ArrayList<>();
      Set<Integer> seen = new HashSet<>();
      Map<String, Object> unsupported = new LinkedHashMap<>();

      // Pass 1: everything JNX can model, one type at a time so a single unsupported
      // kind cannot fail the whole listing.
      for (Class<? extends DesignElement> type : DESIGN_TYPES) {
        String typeName = type.getSimpleName();
        try {
          database.getDesign().getDesignElements(type).forEach(element -> {
            seen.add(element.getNoteID());
            if (matchesFilter(typeName, typeFilter)) {
              elements.add(describe(element, typeName, withSignatures));
            }
          });
        } catch (RuntimeException e) {
          // Recorded rather than swallowed: the caller should know a type was skipped.
          unsupported.put(typeName, e.getClass().getSimpleName());
        }
      }

      // Pass 2: notes in the design collection that pass 1 did not produce.
      int otherCount = 0;
      try {
        DominoCollection designCollection = database.openDesignCollection();
        for (Integer noteId : designCollection.getAllIds(true, false)) {
          if (seen.contains(noteId)) {
            continue;
          }
          otherCount++;
          if (!matchesFilter("Other", typeFilter)) {
            continue;
          }
          database.getDocumentById(noteId)
              .map(document -> describeRaw(document, withSignatures))
              .ifPresent(elements::add);
        }
      } catch (RuntimeException e) {
        unsupported.put("designCollection", e.getClass().getSimpleName() + ": " + e.getMessage());
      }

      // Counts by type, from the full set rather than the filtered view.
      Map<String, Integer> byType = new TreeMap<>();
      for (Map<String, Object> element : elements) {
        byType.merge(String.valueOf(element.get("type")), 1, Integer::sum);
      }

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("database", database.getTitle());
      result.put("count", elements.size());
      result.put("modelledByJnx", seen.size());
      result.put("other", otherCount);
      result.put("byType", byType);
      if (!unsupported.isEmpty()) {
        result.put("skippedTypes", unsupported);
      }
      result.put("elements", elements);
      return result;
    });
  }

  private static boolean matchesFilter(String typeName, String filter) {
    return filter == null || filter.isEmpty() || typeName.equalsIgnoreCase(filter);
  }

  private static Map<String, Object> describe(DesignElement element, String typeName,
      boolean withSignatures) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", typeName);
    if (element instanceof DesignElement.NamedDesignElement named) {
      item.put("title", named.getTitle());
      item.put("aliases", named.getAliases());
    } else {
      item.put("title", "");
      item.put("aliases", List.of());
    }
    item.put("noteId", String.format("%X", element.getNoteID()));
    safePut(item, "unid", element::getUNID);
    safePut(item, "comment", element::getComment);
    safePut(item, "hideFromWeb", element::isHideFromWeb);
    safePut(item, "hideFromNotes", element::isHideFromNotes);
    if (withSignatures) {
      safePut(item, "signer", () -> element.getDocument().getSigner());
      safePut(item, "signed", () -> element.getDocument().isSigned());
    }
    return item;
  }

  /** Describes a design note JNX has no class for, straight from its items. */
  private static Map<String, Object> describeRaw(Document document, boolean withSignatures) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", "Other");
    // $TITLE holds name and aliases separated by |, exactly as the design stores them.
    String title = firstValue(document, "$TITLE");
    List<String> parts = List.of(title.split("\\|"));
    item.put("title", parts.isEmpty() ? "" : parts.get(0));
    item.put("aliases", parts.size() > 1 ? parts.subList(1, parts.size()) : List.of());
    item.put("noteId", String.format("%X", document.getNoteID()));
    safePut(item, "unid", document::getUNID);
    item.put("documentClass", String.valueOf(safeCall(document::getDocumentClass)));
    item.put("flags", firstValue(document, "$Flags"));
    if (withSignatures) {
      safePut(item, "signer", document::getSigner);
      safePut(item, "signed", document::isSigned);
    }
    return item;
  }

  private static String firstValue(Document document, String itemName) {
    try {
      List<?> values = document.getItemValue(itemName);
      return values.isEmpty() ? "" : String.valueOf(values.get(0));
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static Object safeCall(java.util.function.Supplier<Object> supplier) {
    try {
      return supplier.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void safePut(Map<String, Object> target, String key,
      java.util.function.Supplier<Object> supplier) {
    target.put(key, safeCall(supplier));
  }

  /**
   * Resolves a note by ID or UNID, which is what the DXL endpoints address elements with.
   * Design elements and documents are the same thing here — both are notes.
   */
  static Document resolveNote(Database database, String noteId, String unid) {
    Optional<Document> document;
    if (unid != null && !unid.isEmpty()) {
      document = database.getDocumentByUNID(unid);
    } else if (noteId != null && !noteId.isEmpty()) {
      document = database.getDocumentById(DominoService.parseNoteId(noteId));
    } else {
      throw new IllegalArgumentException("either 'noteid' or 'unid' must be given");
    }
    return document.orElseThrow(() -> new DominoService.NotFoundException(
        "note not found (noteid=" + noteId + ", unid=" + unid + ")"));
  }
}

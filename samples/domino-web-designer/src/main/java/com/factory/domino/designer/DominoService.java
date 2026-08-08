package com.factory.domino.designer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.factory.domino.runner.DominoExecutor;
import com.hcl.domino.DominoClient;
import com.hcl.domino.data.Database;
import com.hcl.domino.data.Document;
import com.hcl.domino.data.DominoDateTime;
import com.hcl.domino.misc.Ref;
import com.hcl.domino.dbdirectory.DatabaseData;
import com.hcl.domino.dbdirectory.DirEntry;
import com.hcl.domino.dbdirectory.FileType;
import com.hcl.domino.design.DbDesign;
import com.hcl.domino.design.DesignElement;
import com.hcl.domino.design.Form;
import com.hcl.domino.design.View;
import com.hcl.domino.richtext.FormField;

/**
 * Read-only view of a Domino environment, exposed as plain maps ready for JSON serialization.
 *
 * <p>Every method runs its Domino work through a {@link DominoExecutor}, so it executes on a
 * thread that holds a live Domino context regardless of which HTTP thread called it.
 *
 * <p>All access uses the identity of the ID the runner was initialized with, for local and
 * remote servers alike.
 */
public final class DominoService {

  private final DominoExecutor executor;

  public DominoService(DominoExecutor executor) {
    this.executor = executor;
  }

  /** Thrown for conditions that map to a 404, keeping HTTP concerns in the handler layer. */
  public static class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }

  /**
   * 1. Databases on a server; an empty server name means the local data directory.
   *
   * @param recursive when true, descends into subdirectories. Domino's directory search is
   *     flat by default, so without {@link FileType#RECURSE} only the data directory root is
   *     listed and databases in subfolders are silently missing.
   */
  public Map<String, Object> listDatabases(String server, boolean recursive) {
    return executor.call(client -> {
      EnumSet<FileType> fileTypes = recursive
          ? EnumSet.of(FileType.DBANY, FileType.RECURSE)
          : EnumSet.of(FileType.DBANY);

      List<DirEntry> entries = client.openDbDirectory()
          .query()
          .withServer(server)
          .withFileTypes(fileTypes)
          .stream()
          .toList();

      List<Map<String, Object>> databases = new ArrayList<>();
      for (DirEntry entry : entries) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("filePath", entry.getFilePath());
        item.put("fileName", entry.getFileName());
        item.put("fileLength", entry.getFileLength());
        if (entry instanceof DatabaseData database) {
          item.put("title", database.getTitle());
          item.put("templateName", database.getTemplateName());
          item.put("inheritTemplateName", database.getInheritTemplateName());
          item.put("category", database.getCategory());
        }
        databases.add(item);
      }

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("server", server == null || server.isEmpty() ? "(local)" : server);
      result.put("recursive", recursive);
      result.put("count", databases.size());
      result.put("databases", databases);
      return result;
    });
  }

  /** 2. Details of a single database, addressed by file path or replica ID. */
  public Map<String, Object> getDatabaseDetails(String server, String db) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("server", database.getServer());
      result.put("title", database.getTitle());
      result.put("replicaId", database.getReplicaID());
      result.put("designTemplateName", database.getDesignTemplateName());

      // Individual properties are best-effort: some are unavailable depending on the
      // database and on the rights the current ID has.
      putIfAvailable(result, "absoluteFilePath", database::getAbsoluteFilePath);
      putIfAvailable(result, "relativeFilePath", database::getRelativeFilePath);
      putIfAvailable(result, "templateName", database::getTemplateName);
      putIfAvailable(result, "categories", database::getCategories);
      putIfAvailable(result, "created", () -> formatDateTime(database.getCreated()));
      putIfAvailable(result, "dataModified", () -> {
        // Domino reports data and design modification times through out-parameters.
        Ref<DominoDateTime> dataModified = new Ref<>();
        Ref<DominoDateTime> designModified = new Ref<>();
        database.getModifiedTime(dataModified, designModified);
        return formatDateTime(dataModified.get());
      });
      putIfAvailable(result, "buildVersion", () -> String.valueOf(database.getBuildVersionInfo()));
      putIfAvailable(result, "effectiveAccess",
          () -> String.valueOf(database.getEffectiveAccessInfo().getAclLevel()));

      DbDesign design = database.getDesign();
      putIfAvailable(result, "viewCount", () -> design.getViews().count());
      putIfAvailable(result, "formCount", () -> design.getForms().count());
      return result;
    });
  }

  /** 3. Views of a database. */
  public Map<String, Object> listViews(String server, String db) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);
      List<Map<String, Object>> views = new ArrayList<>();
      database.getDesign().getViews().forEach(view -> {
        Map<String, Object> item = describeDesignElement(view);
        putIfAvailable(item, "selectionFormula", ((View) view)::getSelectionFormula);
        views.add(item);
      });
      return designResult(database, "views", views);
    });
  }

  /** 4. Forms of a database. */
  public Map<String, Object> listForms(String server, String db) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);
      List<Map<String, Object>> forms = new ArrayList<>();
      database.getDesign().getForms().forEach(form -> {
        Map<String, Object> item = describeDesignElement(form);
        putIfAvailable(item, "type", () -> String.valueOf(((Form) form).getType()));
        forms.add(item);
      });
      return designResult(database, "forms", forms);
    });
  }

  /**
   * 4b. Everything the design holds about a single form: its fields and their definitions,
   * plus the form's own properties, formulas, events and subform references.
   */
  public Map<String, Object> getFormDetails(String server, String db, String formName) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);
      Form form = database.getDesign()
          .getDesignElementByName(Form.class, formName)
          .orElseThrow(() -> new NotFoundException(
              "form '" + formName + "' not found in " + db));

      Map<String, Object> result = describeDesignElement(form);
      putIfAvailable(result, "type", () -> String.valueOf(form.getType()));

      result.put("properties", formProperties(form));
      result.put("formulas", formFormulas(form));
      result.put("events", formEvents(form));

      // Subforms change which fields a document actually gets, so they belong here.
      List<Map<String, Object>> subforms = new ArrayList<>();
      putIfAvailable(result, "subforms", () -> {
        form.getSubforms().forEach(reference -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("type", String.valueOf(reference.getType()));
          item.put("value", reference.getValue());
          subforms.add(item);
        });
        return subforms;
      });

      // LotusScript attached per field, keyed by field name.
      Map<String, String> fieldScripts;
      try {
        fieldScripts = form.getFieldLotusScript();
      } catch (RuntimeException e) {
        fieldScripts = Map.of();
      }

      List<Map<String, Object>> fields = new ArrayList<>();
      try {
        for (FormField field : form.getFields()) {
          fields.add(describeFormField(field, fieldScripts));
        }
      } catch (RuntimeException e) {
        result.put("fieldsError", "field definitions could not be read: " + e.getMessage());
      }
      result.put("fieldCount", fields.size());
      result.put("fields", fields);

      // Design LotusScript can be long; it is returned in full but flagged so the UI can
      // decide how much to show.
      putIfAvailable(result, "lotusScript", form::getLotusScript);
      putIfAvailable(result, "lotusScriptGlobals", form::getLotusScriptGlobals);
      return result;
    });
  }

  /** Definition of one field as stored in the form design. */
  private static Map<String, Object> describeFormField(FormField field,
      Map<String, String> fieldScripts) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("name", field.getName());
    putIfAvailable(item, "dataType", () -> field.getDataType().map(String::valueOf).orElse(null));
    putIfAvailable(item, "kind", () -> String.valueOf(field.getKind()));
    putIfAvailable(item, "displayType", () -> String.valueOf(field.getDisplayType()));
    putIfAvailable(item, "description", field::getDescription);

    // The formulas are the interesting part of a field definition.
    putIfAvailable(item, "defaultValueFormula", () -> field.getDefaultValueFormula().orElse(null));
    putIfAvailable(item, "inputTranslationFormula",
        () -> field.getInputTranslationFormula().orElse(null));
    putIfAvailable(item, "inputValidityCheckFormula",
        () -> field.getInputValidityCheckFormula().orElse(null));
    putIfAvailable(item, "keywordFormula", () -> field.getKeywordFormula().orElse(null));
    putIfAvailable(item, "choices", () -> field.getTextListValues().orElse(null));

    putIfAvailable(item, "listInputDelimiters",
        () -> String.valueOf(field.getListInputDelimiters()));
    putIfAvailable(item, "listDisplayDelimiter",
        () -> String.valueOf(field.getListDispayDelimiter()));

    Map<String, Object> html = new LinkedHashMap<>();
    putIfAvailable(html, "id", field::getHtmlId);
    putIfAvailable(html, "name", field::getHtmlName);
    putIfAvailable(html, "class", field::getHtmlClassName);
    putIfAvailable(html, "style", field::getHtmlStyle);
    putIfAvailable(html, "title", field::getHtmlTitle);
    putIfAvailable(html, "extraAttributes", field::getHtmlExtraAttr);
    html.values().removeIf(value -> value == null || "".equals(value));
    if (!html.isEmpty()) {
      item.put("html", html);
    }

    String script = fieldScripts.get(field.getName());
    if (script != null && !script.isEmpty()) {
      item.put("lotusScript", script);
    }
    return item;
  }

  private static Map<String, Object> formProperties(Form form) {
    Map<String, Object> properties = new LinkedHashMap<>();
    putIfAvailable(properties, "defaultForm", form::isDefaultForm);
    putIfAvailable(properties, "storeFormInDocument", form::isStoreFormInDocument);
    putIfAvailable(properties, "menuInclusionMode", () -> String.valueOf(form.getMenuInclusionMode()));
    putIfAvailable(properties, "includeInSearchBuilder", form::isIncludeInSearchBuilder);
    putIfAvailable(properties, "includeInPrint", form::isIncludeInPrint);
    putIfAvailable(properties, "versioningBehavior", () -> String.valueOf(form.getVersioningBehavior()));
    putIfAvailable(properties, "versionCreationAutomatic", form::isVersionCreationAutomatic);
    putIfAvailable(properties, "conflictBehavior", () -> String.valueOf(form.getConflictBehavior()));
    putIfAvailable(properties, "anonymousForm", form::isAnonymousForm);
    putIfAvailable(properties, "signDocuments", form::isSignDocuments);
    putIfAvailable(properties, "allowAutosave", form::isAllowAutosave);
    putIfAvailable(properties, "allowFieldExchange", form::isAllowFieldExchange);
    putIfAvailable(properties, "automaticallyRefreshFields", form::isAutomaticallyRefreshFields);
    putIfAvailable(properties, "automaticallyEnableEditMode", form::isAutomaticallyEnableEditMode);
    putIfAvailable(properties, "inheritSelectedDocumentValues", form::isInheritSelectedDocumentValues);
    putIfAvailable(properties, "includeFieldsInIndex", form::isIncludeFieldsInIndex);
    putIfAvailable(properties, "renderPassThroughHtmlInClient", form::isRenderPassThroughHtmlInClient);
    putIfAvailable(properties, "webRenderingSettings", () -> String.valueOf(form.getWebRenderingSettings()));
    return properties;
  }

  private static Map<String, Object> formFormulas(Form form) {
    Map<String, Object> formulas = new LinkedHashMap<>();
    putIfAvailable(formulas, "windowTitle", () -> form.getWindowTitleFormula().orElse(null));
    putIfAvailable(formulas, "targetFrame", () -> form.getTargetFrameFormula().orElse(null));
    putIfAvailable(formulas, "webQueryOpen", () -> form.getWebQueryOpenFormula().orElse(null));
    putIfAvailable(formulas, "webQuerySave", () -> form.getWebQuerySaveFormula().orElse(null));
    putIfAvailable(formulas, "htmlHeadContent", () -> form.getHtmlHeadContentFormula().orElse(null));
    putIfAvailable(formulas, "htmlBodyAttributes",
        () -> form.getHtmlBodyAttributesFormula().orElse(null));
    formulas.values().removeIf(value -> value == null || "".equals(value));
    return formulas;
  }

  /** Formula-language events keyed by their Domino event id. */
  private static Map<String, Object> formEvents(Form form) {
    Map<String, Object> events = new LinkedHashMap<>();
    try {
      form.getFormulaEvents().forEach((eventId, formula) -> {
        if (formula != null && !formula.isEmpty()) {
          events.put(String.valueOf(eventId), formula);
        }
      });
    } catch (RuntimeException e) {
      // Events are optional detail; their absence should not fail the whole response.
    }
    return events;
  }

  /** 5. Items of a document addressed by note ID or UNID. */
  public Map<String, Object> getDocument(String server, String db, String noteId, String unid) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);

      Optional<Document> document;
      if (unid != null && !unid.isEmpty()) {
        document = database.getDocumentByUNID(unid);
      } else if (noteId != null && !noteId.isEmpty()) {
        document = database.getDocumentById(parseNoteId(noteId));
      } else {
        throw new IllegalArgumentException("either 'noteid' or 'unid' must be given");
      }

      return describeDocument(document.orElseThrow(() -> new NotFoundException(
          "document not found in " + db + " (noteid=" + noteId + ", unid=" + unid + ")")));
    });
  }

  /** 6. Items of a named document. */
  public Map<String, Object> getNamedDocument(String server, String db, String name,
      String userName) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);
      Document document = database.getNamedDocument(name, userName == null ? "" : userName);
      // JNX returns a new, unsaved document when the name does not exist, identifiable by
      // note ID 0. For a read-only browser that is a miss, not a result.
      if (document == null || document.getNoteID() == 0) {
        throw new NotFoundException("named document '" + name + "' does not exist in " + db);
      }
      Map<String, Object> result = describeDocument(document);
      result.put("namedDocument", name);
      result.put("userName", userName);
      return result;
    });
  }

  /** Names of the named documents in a database, to make the API discoverable. */
  public Map<String, Object> listNamedDocuments(String server, String db) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);
      List<Map<String, Object>> names = new ArrayList<>();
      database.getNamedDocumentInfos().forEach(info -> {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", info.getNameOfDocument());
        item.put("userName", info.getUserNameOfDocument());
        item.put("noteId", info.getNoteID());
        names.add(item);
      });
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("database", db);
      result.put("count", names.size());
      result.put("namedDocuments", names);
      return result;
    });
  }

  /** 7. Items of a profile document. */
  public Map<String, Object> getProfileDocument(String server, String db, String profile,
      String userName) {
    return executor.call(client -> {
      Database database = openDatabase(client, server, db);
      Optional<Document> document = userName == null || userName.isEmpty()
          ? database.getProfileDocument(profile)
          : database.getProfileDocument(profile, userName);

      Map<String, Object> result = describeDocument(document.orElseThrow(() ->
          new NotFoundException("profile document '" + profile + "'"
              + (userName == null || userName.isEmpty() ? "" : " for user '" + userName + "'")
              + " not found in " + db)));
      result.put("profileName", profile);
      result.put("userName", userName);
      return result;
    });
  }

  // --- helpers ---------------------------------------------------------------------

  private static Database openDatabase(DominoClient client, String server, String db) {
    if (db == null || db.isEmpty()) {
      throw new IllegalArgumentException("parameter 'db' is required");
    }
    String serverName = server == null ? "" : server;
    try {
      // openDatabase accepts a file path or a replica ID in the same argument.
      return client.openDatabase(serverName, db);
    } catch (RuntimeException e) {
      throw new NotFoundException("cannot open database '" + db + "' on server '"
          + (serverName.isEmpty() ? "(local)" : serverName) + "': " + e.getMessage());
    }
  }

  private static int parseNoteId(String noteId) {
    String value = noteId.trim();
    try {
      // Note IDs are conventionally written in hex, often with an NT prefix.
      if (value.regionMatches(true, 0, "NT", 0, 2)) {
        value = value.substring(2);
      }
      if (value.regionMatches(true, 0, "0x", 0, 2)) {
        return Integer.parseUnsignedInt(value.substring(2), 16);
      }
      return Integer.parseUnsignedInt(value, 16);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("'" + noteId + "' is not a valid note ID"
          + " (expected hexadecimal, e.g. 8FA or NT000008FA)");
    }
  }

  /** Renders a Domino timestamp as ISO-8601, falling back to its own formatting. */
  private static String formatDateTime(DominoDateTime dateTime) {
    if (dateTime == null) {
      return null;
    }
    try {
      return dateTime.toOffsetDateTime().toString();
    } catch (RuntimeException e) {
      return dateTime.toString();
    }
  }

  private static Map<String, Object> designResult(Database database, String key,
      List<Map<String, Object>> elements) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("database", database.getTitle());
    result.put("count", elements.size());
    result.put(key, elements);
    return result;
  }

  private static Map<String, Object> describeDesignElement(DesignElement element) {
    Map<String, Object> item = new LinkedHashMap<>();
    if (element instanceof DesignElement.NamedDesignElement named) {
      item.put("title", named.getTitle());
      item.put("aliases", named.getAliases());
    }
    item.put("noteId", String.format("%X", element.getNoteID()));
    item.put("unid", element.getUNID());
    putIfAvailable(item, "comment", element::getComment);
    item.put("hideFromWeb", element.isHideFromWeb());
    item.put("hideFromNotes", element.isHideFromNotes());
    return item;
  }

  /** Renders a document as its item list, which is what all three document APIs return. */
  private static Map<String, Object> describeDocument(Document document) {
    List<Map<String, Object>> items = new ArrayList<>();
    document.allItems().forEach(item -> items.add(ItemMapper.describe(item)));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("unid", document.getUNID());
    result.put("noteId", String.format("%X", document.getNoteID()));
    putIfAvailable(result, "form", () -> String.join(", ",
        document.getItemValue("Form").stream().map(String::valueOf).toList()));
    result.put("itemCount", items.size());
    result.put("items", items);
    return result;
  }

  /**
   * Adds a property only if it can be read. Several Domino properties throw depending on the
   * database, the ID's rights, or the design, and one unavailable property should not fail an
   * otherwise useful response.
   */
  private static void putIfAvailable(Map<String, Object> target, String key,
      java.util.function.Supplier<Object> supplier) {
    try {
      target.put(key, supplier.get());
    } catch (RuntimeException e) {
      target.put(key, null);
    }
  }
}

package com.factory.domino.designer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.factory.domino.runner.DominoExecutor;
import com.factory.domino.runner.RunnerLifecycle;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

/**
 * A read-only web front end for browsing Domino databases, launched by {@code DominoRunner}.
 *
 * <p>Run it with:
 * <pre>
 *   domino-runner-macos.sh --jar domino-web-designer.jar --wait
 * </pre>
 *
 * <p>{@code main} returns as soon as the listener is up — the runner's {@code --wait} keeps the
 * process alive, and the cleanup registered with {@link RunnerLifecycle} stops the server before
 * the Domino runtime is released.
 *
 * <p>The server binds to the loopback interface only. Everything it exposes is readable with the
 * runner's Notes identity, so it is not something to put on a network interface without
 * authentication in front of it.
 */
public final class DominoWebDesigner {

  private static final int DEFAULT_PORT = 8080;
  private static final String DEFAULT_HOST = "127.0.0.1";
  private static final int DEFAULT_DOMINO_THREADS = 4;
  private static final String DEFAULT_MDNS_NAME = "domino.designer";

  /** Must match the org.webjars:swagger-ui version in the POM: it is part of the resource path. */
  private static final String SWAGGER_UI_VERSION = "5.25.3";

  public static void main(String[] args) {
    int port = DEFAULT_PORT;
    String host = DEFAULT_HOST;
    int dominoThreads = DEFAULT_DOMINO_THREADS;
    String mdnsName = DEFAULT_MDNS_NAME;
    boolean mdnsEnabled = true;
    boolean allowWrite = false;
    java.nio.file.Path backupDir = java.nio.file.Paths.get(
        System.getProperty("user.home"), "domino-web-designer-backups");

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--port" -> port = Integer.parseInt(args[++i]);
        case "--host" -> host = args[++i];
        case "--domino-threads" -> dominoThreads = Integer.parseInt(args[++i]);
        case "--mdns-name" -> mdnsName = args[++i];
        case "--no-mdns" -> mdnsEnabled = false;
        // Writing is opt-in: DXL import can replace design elements irreversibly, so the
        // server must be started deliberately for it rather than being able to write
        // simply because it is running.
        case "--allow-write" -> allowWrite = true;
        case "--backup-dir" -> backupDir = java.nio.file.Paths.get(args[++i]);
        default -> {
          System.err.println("domino-web-designer: unknown option '" + args[i] + "'");
          System.err.println("Usage: DominoWebDesigner [--port <port>] [--host <host>] "
              + "[--domino-threads <n>] [--mdns-name <name>] [--no-mdns] "
              + "[--allow-write] [--backup-dir <path>]");
          return;
        }
      }
    }

    // Domino work never runs on a Jetty thread: it is handed to workers that hold a live
    // Domino context. See DominoExecutor for why this is not optional.
    DominoExecutor executor = new DominoExecutor(dominoThreads);
    DominoService service = new DominoService(executor);
    DesignService designService = new DesignService(executor);
    DxlService dxlService = new DxlService(executor, allowWrite, backupDir);

    Javalin app = Javalin.create(config -> {
      config.staticFiles.add(staticFiles -> {
        staticFiles.hostedPath = "/";
        staticFiles.directory = "/web";
        staticFiles.location = Location.CLASSPATH;
      });
      // Swagger UI assets straight out of the webjar. The version is part of the path,
      // which is why SWAGGER_UI_VERSION has to track the POM.
      config.staticFiles.add(staticFiles -> {
        staticFiles.hostedPath = "/swagger-ui";
        staticFiles.directory = "/META-INF/resources/webjars/swagger-ui/" + SWAGGER_UI_VERSION;
        staticFiles.location = Location.CLASSPATH;
      });
      config.showJavalinBanner = false;
    });

    registerRoutes(app, service);
    registerDesignRoutes(app, designService, dxlService);
    registerErrorHandling(app);

    app.start(host, port);

    MdnsAnnouncer mdns = mdnsEnabled ? MdnsAnnouncer.announce(mdnsName, host, port) : null;

    // Ordered teardown: mDNS and server first, then Domino workers, then the runner releases
    // the runtime. A plain JVM shutdown hook would race that last step.
    RunnerLifecycle.onShutdown(() -> {
      System.out.println("[domino-web-designer] stopping server...");
      if (mdns != null) {
        mdns.close();
      }
      app.stop();
      executor.close();
      System.out.println("[domino-web-designer] stopped.");
    });

    System.out.println();
    System.out.println("  Domino Web Designer ready at http://" + host + ":" + port + "/");
    System.out.println("  API documentation:      http://" + host + ":" + port + "/swagger/");
    System.out.println("  Domino worker threads: " + executor.getThreadCount());
    if (allowWrite) {
      System.out.println();
      System.out.println("  *** WRITE ENABLED: DXL import and signing can modify databases.");
      System.out.println("  *** Pre-import backups go to " + backupDir);
    } else {
      System.out.println("  Read-only. Start with --allow-write to enable DXL import and signing.");
    }
    System.out.println();
  }

  private static void registerRoutes(Javalin app, DominoService service) {
    // Server and database are query parameters rather than path segments on purpose:
    // Domino paths contain '/' and '\' for subdirectories, which would need fragile
    // double-encoding as path segments.

    // 1. Databases on a server (empty server = local). Recursive by default: a flat listing
    // hides everything in subdirectories, which looks like missing databases.
    app.get("/api/databases", ctx ->
        ctx.json(service.listDatabases(ctx.queryParam("server"),
            !"false".equalsIgnoreCase(ctx.queryParam("recursive")))));

    // 2. Database details
    app.get("/api/database", ctx ->
        ctx.json(service.getDatabaseDetails(ctx.queryParam("server"), requiredDb(ctx))));

    // 3. Views
    app.get("/api/database/views", ctx ->
        ctx.json(service.listViews(ctx.queryParam("server"), requiredDb(ctx))));

    // 4. Forms
    app.get("/api/database/forms", ctx ->
        ctx.json(service.listForms(ctx.queryParam("server"), requiredDb(ctx))));

    // 4b. A single form's design: fields, properties, formulas, events, subforms
    app.get("/api/database/form", ctx ->
        ctx.json(service.getFormDetails(ctx.queryParam("server"), requiredDb(ctx),
            required(ctx, "form"))));

    // 5. Document by note ID or UNID
    app.get("/api/document", ctx ->
        ctx.json(service.getDocument(ctx.queryParam("server"), requiredDb(ctx),
            ctx.queryParam("noteid"), ctx.queryParam("unid"))));

    // 6. Named documents
    app.get("/api/document/named", ctx ->
        ctx.json(service.getNamedDocument(ctx.queryParam("server"), requiredDb(ctx),
            required(ctx, "name"), ctx.queryParam("username"))));
    app.get("/api/documents/named", ctx ->
        ctx.json(service.listNamedDocuments(ctx.queryParam("server"), requiredDb(ctx))));

    // 7. Profile documents
    app.get("/api/document/profile", ctx ->
        ctx.json(service.getProfileDocument(ctx.queryParam("server"), requiredDb(ctx),
            required(ctx, "profile"), ctx.queryParam("username"))));

    // Orderly shutdown. Needed because the Notes runtime swallows Ctrl+C, so without this the
    // only way to stop the process is SIGKILL, which skips every bit of cleanup.
    app.post("/api/shutdown", ctx -> {
      Map<String, Object> body = new LinkedHashMap<>();
      boolean accepted = RunnerLifecycle.requestShutdown();
      body.put("status", accepted ? "shutting down" : "not running under --wait; nothing to do");
      ctx.status(accepted ? 202 : 409);
      ctx.json(body);
      // The response has to be on its way before the server stops accepting connections.
    });
  }

  /** Design listing, DXL export and — behind --allow-write — DXL import and signing. */
  private static void registerDesignRoutes(Javalin app, DesignService design, DxlService dxl) {

    // Whether writing is possible at all, so the UI can say so instead of guessing.
    app.get("/api/capabilities", ctx -> {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("writeAllowed", dxl.isWriteAllowed());
      body.put("importOptions", java.util.Arrays.stream(
          com.hcl.domino.dxl.DxlImporter.DXLImportOption.values()).map(Enum::name).toList());
      ctx.json(body);
    });

    // Every design element of a database, of every kind
    app.get("/api/database/design", ctx ->
        ctx.json(design.listDesign(ctx.queryParam("server"), requiredDb(ctx),
            ctx.queryParam("type"), "true".equalsIgnoreCase(ctx.queryParam("signatures")))));

    // DXL of one note — design element or document
    app.get("/api/dxl/export", ctx -> {
      String name = ctx.queryParam("unid") != null && !ctx.queryParam("unid").isEmpty()
          ? ctx.queryParam("unid") : ctx.queryParam("noteid");
      prepareDxlResponse(ctx, "note-" + name + ".dxl", ctx.queryParam("download"));
      dxl.exportNote(ctx.queryParam("server"), requiredDb(ctx), ctx.queryParam("noteid"),
          ctx.queryParam("unid"), exportOptions(ctx), ctx.outputStream());
    });

    // DXL of several notes at once
    app.get("/api/dxl/export/notes", ctx -> {
      List<String> noteIds = java.util.Arrays.stream(required(ctx, "noteids").split(","))
          .map(String::trim).filter(s -> !s.isEmpty()).toList();
      prepareDxlResponse(ctx, "notes.dxl", ctx.queryParam("download"));
      dxl.exportNotes(ctx.queryParam("server"), requiredDb(ctx), noteIds, exportOptions(ctx),
          ctx.outputStream());
    });

    // DXL of a whole database, its design only, or its ACL
    app.get("/api/dxl/export/database", ctx -> {
      String what = ctx.queryParam("what") == null ? "all" : ctx.queryParam("what");
      prepareDxlResponse(ctx, requiredDb(ctx).replaceAll("[^A-Za-z0-9._-]", "_")
          + "-" + what + ".dxl", ctx.queryParam("download"));
      dxl.exportDatabase(ctx.queryParam("server"), requiredDb(ctx), what, exportOptions(ctx),
          ctx.outputStream());
    });

    // Import: a dry run unless confirm=true, and only with --allow-write
    app.post("/api/dxl/import", ctx -> {
      String content = ctx.uploadedFile("file") != null
          ? new String(ctx.uploadedFile("file").content().readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8)
          : ctx.body();

      DxlService.ImportOptions options = new DxlService.ImportOptions(
          DxlService.parseImportOption(ctx.queryParam("designOption"),
              com.hcl.domino.dxl.DxlImporter.DXLImportOption.REPLACE_ELSE_CREATE),
          DxlService.parseImportOption(ctx.queryParam("documentOption"),
              com.hcl.domino.dxl.DxlImporter.DXLImportOption.CREATE),
          "true".equalsIgnoreCase(ctx.queryParam("sign")),
          "true".equalsIgnoreCase(ctx.queryParam("confirm")),
          "true".equalsIgnoreCase(ctx.queryParam("validate")),
          "true".equalsIgnoreCase(ctx.queryParam("replicaRequired")));

      ctx.json(dxl.importDxl(ctx.queryParam("server"), requiredDb(ctx), content, options));
    });

    // Sign an existing note
    app.post("/api/dxl/sign", ctx ->
        ctx.json(dxl.signNote(ctx.queryParam("server"), requiredDb(ctx),
            ctx.queryParam("noteid"), ctx.queryParam("unid"))));
  }

  /** Sets the content type and, when asked, the attachment header for a DXL response. */
  private static void prepareDxlResponse(Context ctx, String fileName, String download) {
    ctx.contentType("application/xml; charset=utf-8");
    if ("true".equalsIgnoreCase(download)) {
      ctx.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
    }
  }

  private static DxlService.ExportOptions exportOptions(Context ctx) {
    return new DxlService.ExportOptions(
        "true".equalsIgnoreCase(ctx.queryParam("omitAttachments")),
        "true".equalsIgnoreCase(ctx.queryParam("omitPictures")),
        "true".equalsIgnoreCase(ctx.queryParam("omitOle")),
        "true".equalsIgnoreCase(ctx.queryParam("forceNoteFormat")),
        !"false".equalsIgnoreCase(ctx.queryParam("outputDoctype")));
  }

  private static void registerErrorHandling(Javalin app) {
    app.exception(DominoService.NotFoundException.class, (e, ctx) -> {
      ctx.status(404);
      ctx.json(error(e.getMessage()));
    });
    app.exception(IllegalArgumentException.class, (e, ctx) -> {
      ctx.status(400);
      ctx.json(error(e.getMessage()));
    });
    app.exception(DxlService.WriteNotAllowedException.class, (e, ctx) -> {
      ctx.status(403);
      ctx.json(error(e.getMessage()));
    });
    app.exception(Exception.class, (e, ctx) -> {
      // Domino failures are reported rather than swallowed: a stack trace on the console
      // plus a readable message in the response.
      System.err.println("[domino-web-designer] request failed: " + ctx.path());
      e.printStackTrace(System.err);
      ctx.status(500);
      ctx.json(error(e.getClass().getSimpleName() + ": " + e.getMessage()));
    });
  }

  private static Map<String, Object> error(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", message == null ? "unknown error" : message);
    return body;
  }

  private static String requiredDb(Context ctx) {
    return required(ctx, "db");
  }

  private static String required(Context ctx, String parameter) {
    String value = ctx.queryParam(parameter);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("query parameter '" + parameter + "' is required");
    }
    return value;
  }
}
